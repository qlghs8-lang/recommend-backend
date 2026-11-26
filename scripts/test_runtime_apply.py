"""Offline validation/SQL/backup contracts for bounded runtime apply."""
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from uuid import uuid4

import apply_runtime_enrichment as app
import audit_runtime_coverage as coverage
import runtime_detail_dry_run as dry
from catalog_pool import CatalogError, digest
from test_runtime_coverage import fixture
from test_runtime_detail_dry_run import record


def reviewed(snapshot=None):
    snapshot = snapshot or fixture()
    current = coverage.analyze(snapshot, len(snapshot['contents']))
    selected = dry.scope(current)
    c = {'version': 1, 'policy': dry.POLICY, 'job_id': uuid4().hex, 'started_at_utc': dry.utc_now(),
         'coverage_snapshot_sha256': current['coverage_snapshot_sha256'], 'scope': selected,
         'state': 'COMPLETE', 'stop_code': None, 'api_calls': len(selected), 'records': []}
    c['records'] = [record(candidate) for candidate in selected]
    c['finished_at_utc'] = dry.utc_now()
    dry.seal(c)
    p = dry.make_plan(c, current)
    return {'collection': c, 'proposal_sha256': p['proposal_sha256'], 'changes': p['proposed_changes']}, p


def applied_state(review, snapshot=None):
    snapshot = copy.deepcopy(snapshot or fixture())
    rows = {r['id']: r for r in snapshot['contents']}
    proof = []
    for change in review['changes']:
        rows[change['content_id']][change['target_field']] = change['proposed_minutes']
        proof.append({'content_id': change['content_id'], 'target_field': change['target_field'],
                      'minutes': change['proposed_minutes'], 'evidence': app.expected_evidence(change),
                      'collection_sha256': review['collection']['collection_sha256'],
                      'proposal_sha256': review['proposal_sha256'], 'applied_at': '2026-11-24T10:00:00.000000Z'})
    return snapshot, proof


class ReviewTest(unittest.TestCase):
    def test_review_pin_and_semantic_recomputation(self):
        review, proposal = reviewed()
        with tempfile.TemporaryDirectory(dir=app.ROOT) as folder:
            path = Path(folder) / 'collection.json'
            path.write_text(json.dumps(review['collection']))
            path.with_name('proposal.json').write_text(json.dumps(proposal))
            self.assertEqual(review, app.load_review(path, review['proposal_sha256'], 2))
            for expected_hash, count in [('b' * 64, 2), (review['proposal_sha256'], 1)]:
                with self.assertRaises(CatalogError):
                    app.load_review(path, expected_hash, count)
            proposal['proposed_changes'][0]['proposed_minutes'] = 999
            proposal['proposal_sha256'] = digest({k: v for k, v in proposal.items() if k != 'proposal_sha256'})
            path.with_name('proposal.json').write_text(json.dumps(proposal))
            with self.assertRaises(CatalogError):
                app.load_review(path, proposal['proposal_sha256'], 2)

    def test_collection_tampering_blocks_even_when_own_hash_is_resealed(self):
        review, proposal = reviewed()
        review['collection']['records'][0]['endpoint'] = '/3/discover/movie'
        dry.seal(review['collection'])
        with tempfile.TemporaryDirectory(dir=app.ROOT) as folder:
            path = Path(folder) / 'collection.json'
            path.write_text(json.dumps(review['collection']))
            path.with_name('proposal.json').write_text(json.dumps(proposal))
            with self.assertRaises(CatalogError):
                app.load_review(path, proposal['proposal_sha256'], 2)


class StateTest(unittest.TestCase):
    def state(self, review, snapshot, proof):
        with patch.object(app, 'read_snapshot', return_value=snapshot), patch.object(app, 'read_provenance', return_value=proof):
            return app.state(Mock(), review, 2)

    def test_pending_requires_original_null_baseline(self):
        review, _ = reviewed()
        self.assertEqual('PENDING', self.state(review, fixture(), [])['status'])

    def test_applied_normalizes_only_verified_batch_for_baseline_hash(self):
        review, _ = reviewed()
        snapshot, proof = applied_state(review)
        self.assertEqual('ALREADY_APPLIED', self.state(review, snapshot, proof)['status'])

    def test_existing_value_without_matching_proof_is_never_adopted(self):
        review, _ = reviewed()
        snapshot, _ = applied_state(review)
        with self.assertRaises(CatalogError):
            self.state(review, snapshot, [])

    def test_partial_application_is_blocked(self):
        review, _ = reviewed()
        snapshot, proof = applied_state(review)
        snapshot['contents'][1]['episode_runtime_minutes'] = None
        with self.assertRaises(CatalogError):
            self.state(review, snapshot, proof[:1])

    def test_changed_value_or_source_proof_is_blocked(self):
        review, _ = reviewed()
        for mutation in ('value', 'job', 'hash', 'extra'):
            snapshot, proof = applied_state(review)
            if mutation == 'value':
                snapshot['contents'][0]['runtime_minutes'] = 123
            elif mutation == 'job':
                proof[0]['evidence']['job_id'] = '0' * 32
            elif mutation == 'hash':
                proof[0]['proposal_sha256'] = 'c' * 64
            else:
                proof.append(copy.deepcopy(proof[0]))
            with self.subTest(mutation=mutation), self.assertRaises(CatalogError):
                self.state(review, snapshot, proof)

    def test_mapping_schema_and_catalog_changes_are_blocked(self):
        review, _ = reviewed()
        for mutation in ('mapping', 'schema', 'count'):
            snapshot = fixture()
            if mutation == 'mapping':
                snapshot['sources'][0]['tmdb_id'] = 999
            elif mutation == 'schema':
                snapshot['schema']['columns'][0]['nullable'] = 'NO'
            else:
                snapshot['contents'].pop()
            with self.subTest(mutation=mutation), self.assertRaises(CatalogError):
                self.state(review, snapshot, [])

    def test_racing_preflight_snapshots_block(self):
        review, _ = reviewed()
        changed = fixture()
        changed['contents'][0]['runtime_minutes'] = 99
        with patch.object(app, 'read_snapshot', side_effect=[fixture(), changed]), self.assertRaises(CatalogError):
            app.state(Mock(), review, 2)


class SqlAndBackupTest(unittest.TestCase):
    def before(self, review):
        with patch.object(app, 'read_snapshot', return_value=fixture()), patch.object(app, 'read_provenance', return_value=[]):
            return app.state(Mock(), review, 2)

    def test_lock_and_full_snapshot_gates_precede_updates_no_other_product_fields(self):
        review, _ = reviewed()
        sql = app.transaction_sql(review, self.before(review))
        self.assertLess(sql.index('FOR UPDATE'), sql.index('UPDATE contents'))
        self.assertLess(sql.index('NOT EXISTS'), sql.index('UPDATE contents'))
        self.assertIn('COALESCE', app.guard('NULL'))
        self.assertIn('runtime_gate SELECT 1', sql)
        self.assertEqual(2, sql.count('UPDATE contents SET'))
        self.assertEqual(1, sql.count('COMMIT;'))
        self.assertNotIn('CREATE TABLE catalog_runtime_enrichments', sql)
        self.assertNotIn('UPDATE catalog_import_sources', sql)
        for private in ('chat_messages', 'users', 'reactions', 'view_count', 'overview'):
            self.assertNotIn(private, sql)

    def test_reexecution_has_no_dml_for_content_or_provenance(self):
        review, _ = reviewed()
        snapshot, proof = applied_state(review)
        sql = app.transaction_sql(review, {'snapshot': snapshot, 'provenance': proof, 'status': 'ALREADY_APPLIED'})
        self.assertNotIn('UPDATE contents', sql)
        self.assertNotIn('INSERT INTO catalog_runtime_enrichments', sql)
        self.assertIn('evidence=CAST(', sql)

    def test_backup_is_verified_before_any_ddl_or_data_sql(self):
        review, _ = reviewed()
        before = self.before(review)
        after_snapshot, proof = applied_state(review)
        after = {'status': 'ALREADY_APPLIED', 'snapshot': after_snapshot, 'provenance': proof,
                 'coverage': coverage.analyze(after_snapshot, 2)['coverage']}
        with tempfile.TemporaryDirectory(dir=app.ROOT) as folder:
            output = Path(folder) / 'job'
            db = Mock()
            def run(sql):
                self.assertTrue((output / 'backup-manifest.json').is_file())
                return '__RUNTIME_COMMITTED__'
            db.run.side_effect = run
            with patch.object(app, 'state', side_effect=[before, after]), patch.object(app, 'schema_status', side_effect=[False, True]):
                result = app.apply_review(db, review, output, 2)
            self.assertEqual('APPLIED', result['status'])
            self.assertEqual(2, result['updated'])
            self.assertEqual(2, db.run.call_count)

    def test_failure_retains_backup_and_never_automatically_retries(self):
        review, _ = reviewed()
        before = self.before(review)
        with tempfile.TemporaryDirectory(dir=app.ROOT) as folder:
            output = Path(folder) / 'job'
            db = Mock()
            db.run.side_effect = CatalogError('sanitized')
            with patch.object(app, 'state', return_value=before), patch.object(app, 'schema_status', return_value=True), self.assertRaises(CatalogError):
                app.apply_review(db, review, output, 2)
            self.assertTrue((output / 'before.json').is_file())
            self.assertEqual('FAILED_OR_UNACKNOWLEDGED', json.loads((output / 'result.json').read_text())['status'])
            self.assertEqual(1, db.run.call_count)

    def test_incompatible_existing_table_is_not_altered(self):
        db = Mock()
        db.run.return_value = '{"present":1,"valid":0}\n'
        with self.assertRaises(CatalogError):
            app.schema_status(db)
        self.assertNotIn('ALTER', db.run.call_args.args[0])

    def test_cli_hides_errors_and_never_starts_database_on_invalid_review(self):
        with patch.object(app, 'load_review', side_effect=CatalogError('synthetic-private-value')), \
                patch.object(app, 'LocalCatalog') as db, patch('sys.stderr', new_callable=io.StringIO) as error:
            self.assertEqual(1, app.main(['check', '--collection', 'unused', '--reviewed-sha256', 'a'*64, '--expected-proposals', '2']))
        db.assert_not_called()
        self.assertNotIn('synthetic-private-value', error.getvalue())


if __name__ == '__main__':
    unittest.main()
