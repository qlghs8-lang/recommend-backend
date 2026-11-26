"""Opt-in controlled apply tests exclusively in new UUID-owned MySQL schemas."""
import copy
import os
from pathlib import Path
import threading
import time
import unittest
from unittest.mock import patch
from uuid import uuid4

import apply_metadata_review_release as app
from catalog_pool import CatalogError, LocalCatalog, write_json
from test_metadata_review_apply import setup_fixture
from test_metadata_storage_mysql import seed


@unittest.skipUnless(os.environ.get('METADATA_STAGE_MYSQL_TEST') == 'true', 'Owned schema opt-in required')
class MetadataReviewApplyMysqlTest(unittest.TestCase):
    def setUp(self):
        setup_fixture(self)
        self.db = app.stage.OwnedScratch(LocalCatalog()); self.addCleanup(self.db.close)
        seed(self.db, self.context['bundle'])
        app.stage.store_owned_scratch(self.db, self.context['bundle'], self.folder/'raw')
        self.raw = app.stage.read_state(self.db)
        self.plan = app.prepare_plan(self.db, self.collection, self.decisions, self.folder/'plan')
        self.serial = 0

    def apply(self):
        self.serial += 1
        return app.apply_plan(self.db, self.folder/'plan/plan.json', self.plan['plan_sha256'], self.folder/f'attempt-{self.serial}')

    def test_store_parent_review_candidate_and_replay_preserves_raw_provenance_timestamps(self):
        self.assertEqual(sum(map(len, self.expected)), self.apply()['inserted'])
        before = app.read_review_state(self.db)
        self.assertEqual([2, len(self.context['rows'])+3, 1], list(map(len, before['rows'])))
        self.assertEqual(0, self.apply()['inserted']); self.assertEqual(before, app.read_review_state(self.db))
        self.assertEqual(self.raw, app.stage.read_state(self.db))
        self.assertEqual('77\t77', self.db.run('SELECT MIN(view_count),MAX(view_count) FROM contents;').strip())
        self.assertEqual('preserve-existing-provenance', self.db.run('SELECT sentinel FROM catalog_runtime_enrichments;').strip())
        with self.assertRaises(CatalogError): self.db.run('UPDATE catalog_metadata_release_candidates SET active=1;')

    def test_midway_failure_rolls_back_all_data_keeps_ddl_and_requires_explicit_replay(self):
        # Do not modify pinned plan reconstruction; inject at the connection boundary.
        run = self.db.run
        def fail(sql):
            if '-- PINNED_INACTIVE_REVIEW_APPLICATION' in sql:
                sql = sql.replace('-- AFTER_REVIEW_1', 'INSERT INTO metadata_gate VALUES(1);\n-- AFTER_REVIEW_1', 1)
            return run(sql)
        with patch.object(self.db, 'run', side_effect=fail), self.assertRaises(CatalogError): self.apply()
        self.assertEqual([[], [], []], app.read_review_state(self.db)['rows'])
        result = app.stage.collect.load_json(self.folder/'attempt-1/result.json')
        self.assertEqual('FAILED_OR_UNACKNOWLEDGED', result['state'])
        self.assertEqual(sum(map(len, self.expected)), self.apply()['inserted']); self.assertEqual(self.raw, app.stage.read_state(self.db))

    def test_lost_acknowledgement_preserves_committed_rows_and_explicit_replay_is_zero(self):
        run = self.db.run
        def lose(sql):
            result = run(sql)
            return '' if '__INACTIVE_REVIEW_APPLICATION_ACK__' in sql else result
        with patch.object(self.db, 'run', side_effect=lose), self.assertRaises(CatalogError): self.apply()
        before = app.read_review_state(self.db)
        self.assertEqual(0, self.apply()['inserted']); self.assertEqual(before, app.read_review_state(self.db))

    def test_preflight_drift_rejected_before_any_ddl(self):
        self.db.run("UPDATE contents SET genres='DRAMA' WHERE id=1;")
        with self.assertRaises(CatalogError): self.apply()
        self.assertFalse(app.schema_present(self.db)); self.assertFalse((self.folder/'attempt-1').exists())

    def test_post_preflight_raw_drift_rejected_under_transaction_locks(self):
        run = self.db.run; raced = False
        def race(sql):
            nonlocal raced
            if '-- PINNED_INACTIVE_REVIEW_APPLICATION' in sql and not raced:
                raced = True; run("UPDATE contents SET genres='DRAMA' WHERE id=1;")
            return run(sql)
        with patch.object(self.db, 'run', side_effect=race), self.assertRaises(CatalogError): self.apply()
        self.assertTrue(raced); self.assertEqual([[], [], []], app.read_review_state(self.db)['rows'])

    def test_post_preflight_target_schema_drift_rejected_before_ddl(self):
        run = self.db.run; raced = False
        def race(sql):
            nonlocal raced
            if '-- PINNED_INACTIVE_REVIEW_APPLICATION' in sql and not raced:
                raced = True; run('ALTER TABLE contents MODIFY genres VARCHAR(512);')
            return run(sql)
        with patch.object(self.db, 'run', side_effect=race), self.assertRaises(CatalogError): self.apply()
        self.assertTrue(raced); self.assertFalse(app.schema_present(self.db))

    def test_partial_schema_and_weakened_active_check_refused_without_repair(self):
        self.db.run(app.migration_sql().split('CREATE TABLE `catalog_metadata_review_records`')[0])
        with self.assertRaises(CatalogError): self.apply()
        self.db.run('CREATE TABLE `catalog_metadata_review_records`'+app.migration_sql().split('CREATE TABLE `catalog_metadata_review_records`', 1)[1])
        # The active constraint is discoverable by metadata; drop exact generated name in owned schema.
        name = self.db.run("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='catalog_metadata_release_candidates' AND CONSTRAINT_TYPE='CHECK';").strip()
        self.assertRegex(name, r'^catalog_metadata_release_candidates_chk_\d+$')
        self.db.run('ALTER TABLE catalog_metadata_release_candidates DROP CHECK `'+name+'`;')
        with self.assertRaises(CatalogError): self.apply()
        self.assertEqual('0', self.db.run('SELECT COUNT(*) FROM catalog_metadata_review_versions;').strip())

    def test_partial_history_is_not_repaired(self):
        self.apply(); self.db.run('DELETE FROM catalog_metadata_review_records LIMIT 1;')
        before = app.read_review_state(self.db)
        with self.assertRaises(CatalogError): self.apply()
        self.assertEqual(before, app.read_review_state(self.db))

    def test_postcheck_unavailable_requires_explicit_replay(self):
        original = app.read_current; calls = 0
        def fail(*args):
            nonlocal calls
            calls += 1
            if calls == 2: raise CatalogError('Injected unavailable postcheck.')
            return original(*args)
        with patch.object(app, 'read_current', side_effect=fail), self.assertRaises(CatalogError): self.apply()
        before = app.read_review_state(self.db)
        self.assertEqual(0, self.apply()['inserted']); self.assertEqual(before, app.read_review_state(self.db))

    def test_shared_advisory_lock_blocks_ddl_and_does_not_retry(self):
        errors = []
        def hold():
            try: self.db.run('SELECT GET_LOCK('+app.sql_value(app.draft.LOCK)+',0); SELECT SLEEP(35); SELECT RELEASE_LOCK('+app.sql_value(app.draft.LOCK)+');')
            except CatalogError: errors.append('lock-holder-failed')
        thread = threading.Thread(target=hold); thread.start()
        try:
            acquired = False
            for _ in range(20):
                if self.db.run('SELECT IS_USED_LOCK('+app.sql_value(app.draft.LOCK)+') IS NOT NULL;').strip() == '1': acquired = True; break
                time.sleep(.1)
            self.assertTrue(acquired)
            with self.assertRaises(CatalogError): self.apply()
            self.assertFalse(app.schema_present(self.db))
        finally: thread.join(timeout=40)
        self.assertFalse(thread.is_alive()); self.assertEqual([], errors)
        self.assertEqual(sum(map(len, self.expected)), self.apply()['inserted'])

    def test_tampered_plan_input_code_or_artifact_rejected_before_db(self):
        for file in ['input/decisions.json', 'execution.sql', 'before.json']:
            path = self.folder/'plan'/file; original = path.read_bytes(); path.write_bytes(original+b' ')
            with patch.object(self.db, 'run') as run, self.assertRaises(CatalogError): self.apply()
            run.assert_not_called(); path.write_bytes(original)
        with patch.object(app, 'code_hashes', return_value={}):
            with patch.object(self.db, 'run') as run, self.assertRaises(CatalogError): self.apply()
            run.assert_not_called()


@unittest.skipUnless(os.environ.get('METADATA_STAGE_MYSQL_TEST') == 'true' and os.environ.get('METADATA_REVIEW_APPLY_COLLECTION'),
                     'Explicit pinned public input and owned-schema opt-in required')
class MetadataReviewApplyPublicMysqlTest(unittest.TestCase):
    def test_actual_fixed_candidate_through_controlled_path(self):
        collection = Path(os.environ['METADATA_REVIEW_APPLY_COLLECTION'])
        decisions = app.ROOT/'docs/evaluation/metadata-review-decisions-v1.json'
        context, compiled, expected = app.compile_fixed(collection, decisions)
        folder = app.ROOT/'target/metadata-review-store-20261126'/('public-rehearsal-'+uuid4().hex[:8])
        db = app.stage.OwnedScratch(LocalCatalog())
        try:
            seed(db, context['bundle'])
            app.stage.store_owned_scratch(db, context['bundle'], folder/'raw')
            before = app.stage.read_state(db)
            plan = app.prepare_plan(db, collection, decisions, folder/'plan')
            first = app.apply_plan(db, folder/'plan/plan.json', plan['plan_sha256'], folder/'first')
            self.assertEqual(1537, first['inserted'])
            saved = app.read_review_state(db)
            replay = app.apply_plan(db, folder/'plan/plan.json', plan['plan_sha256'], folder/'replay')
            self.assertEqual(0, replay['inserted']); self.assertEqual(saved, app.read_review_state(db))
            self.assertEqual([2, 1534, 1], list(map(len, saved['rows'])))
            self.assertEqual(101, len(saved['rows'][2][0]['payload']['entries']))
            self.assertEqual(before, app.stage.read_state(db))
            self.assertEqual(21, sum(r['runtime_minutes'] is not None or r['episode_runtime_minutes'] is not None for r in before['catalog']))
            self.assertEqual('77\t77', db.run('SELECT MIN(view_count),MAX(view_count) FROM contents;').strip())
            self.assertEqual('preserve-existing-provenance', db.run('SELECT sentinel FROM catalog_runtime_enrichments;').strip())
        finally: db.close()
        write_json(folder/'verification.json', {'state': 'PASS', 'inserted': 1537, 'replay_inserted': 0,
            'counts': [2, 1534, 1], 'candidate_entries': 101, 'active': False, 'raw_catalog_mapping_preserved': True,
            'known_times_preserved': 21, 'scratch_removed_absence_verified': True, 'application_db_writes': 0, 'provider_calls': 0})


if __name__ == '__main__': unittest.main()
