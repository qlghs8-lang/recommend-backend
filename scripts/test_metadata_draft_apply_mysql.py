"""Opt-in actual apply-path tests. Every write targets a fresh OwnedScratch schema."""
import copy
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from uuid import uuid4

import apply_metadata_drafts as apply
import metadata_enrichment_dry_run as dry
import metadata_storage_dry_run as stage
from catalog_pool import CatalogError, LocalCatalog, ROOT, digest, sql_value, write_json
from test_metadata_draft_apply import write_input
from test_metadata_storage_dry_run import bundle_fixture
from test_metadata_storage_mysql import seed


@unittest.skipUnless(os.environ.get('METADATA_STAGE_MYSQL_TEST')=='true','Owned-schema opt-in required')
class MetadataDraftApplyMysqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.template,cls.contract=bundle_fixture()

    def setUp(self):
        self.bundle=copy.deepcopy(self.template)
        for p in (patch.object(dry,'CONTRACT_SHA',digest(self.contract)),patch.object(dry,'contract',return_value=self.contract),
                  patch.object(apply,'COLLECTION_SHA',self.bundle['expected_hash'])):
            p.start(); self.addCleanup(p.stop)
        self.projected=stage.projection(self.bundle)
        p=patch.object(apply,'PROJECTION_SHA',self.projected['projection_sha256']); p.start(); self.addCleanup(p.stop)
        self.db=stage.OwnedScratch(LocalCatalog()); self.addCleanup(self.db.close); seed(self.db,self.bundle)
        tmp_root=ROOT/'target/metadata-apply-mysql-tmp'; tmp_root.mkdir(parents=True,exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(dir=tmp_root); self.addCleanup(self.temp.cleanup); self.root=Path(self.temp.name)
        write_input(self.root/'input',self.bundle)
        self.plan=apply.prepare_plan(self.db,self.root/'input/collection.json',self.root/'plan')

    def run_apply(self,name='first'):
        return apply.apply_plan(self.db,self.root/'plan/plan.json',self.plan['plan_sha256'],self.root/name)

    def assert_empty(self):
        state=stage.read_state(self.db)
        self.assertTrue(all(not r for r in state['metadata'].values()))
        return state

    def test_real_path_initial_migration_store_replay_and_new_noop_plan(self):
        before=stage.read_state(self.db); first=self.run_apply()
        self.assertEqual(421,first['inserted']); self.assertTrue(first['created_tables'])
        after=stage.read_state(self.db); self.assertEqual(before['catalog'],after['catalog']); self.assertEqual(before['mappings'],after['mappings'])
        self.assertEqual('preserve-existing-provenance',self.db.run('SELECT sentinel FROM catalog_runtime_enrichments;').strip())
        self.assertEqual('77\t77',self.db.run('SELECT MIN(view_count),MAX(view_count) FROM contents;').strip())
        self.assertEqual(0,self.run_apply('replay')['inserted']); self.assertEqual(after,stage.read_state(self.db))
        plan=apply.prepare_plan(self.db,self.root/'input/collection.json',self.root/'noop-plan')
        self.assertEqual('ALREADY_STORED_UNREVIEWED',plan['prepared_status']); self.assertEqual([],plan['create_tables_if_absent'])
        self.assertEqual(0,apply.apply_plan(self.db,self.root/'noop-plan/plan.json',plan['plan_sha256'],self.root/'noop')['inserted'])
        self.assertEqual(after,stage.read_state(self.db))

    def test_changed_catalog_mapping_or_base_schema_is_rejected_before_ddl(self):
        changes=[("UPDATE contents SET runtime_minutes=120 WHERE id=1;","UPDATE contents SET runtime_minutes=NULL WHERE id=1;"),
            ('UPDATE catalog_import_sources SET tmdb_id=999001 WHERE content_id=1;',
             'UPDATE catalog_import_sources SET tmdb_id=101 WHERE content_id=1;'),
            ('ALTER TABLE contents MODIFY genres VARCHAR(700);','ALTER TABLE contents MODIFY genres VARCHAR(500);')]
        for index,(sql,undo) in enumerate(changes):
            self.db.run(sql)
            with self.subTest(index=index),self.assertRaises(CatalogError): self.run_apply('changed'+str(index))
            self.assertFalse(stage.schema_status(self.db))
            self.db.run(undo)  # Each independent gate must pass with the other baseline values intact.

    def test_race_after_preflight_is_rejected_with_data_rollback(self):
        original=self.db.run; changed=False
        def race(sql):
            nonlocal changed
            if '-- PINNED_DRAFT_APPLICATION' in sql and not changed:
                changed=True; original("UPDATE contents SET genres='DRAMA' WHERE id=1;")
            return original(sql)
        with patch.object(self.db,'run',side_effect=race),self.assertRaises(CatalogError): self.run_apply()
        self.assertTrue(changed); self.assertTrue(self.assert_empty()['schema_present'])
        self.assertEqual('DRAMA',original('SELECT genres FROM contents WHERE id=1;').strip())

    def test_base_schema_race_is_rejected_by_connection_guard_before_migration(self):
        original=self.db.run; changed=False
        def race(sql):
            nonlocal changed
            if '-- PINNED_DRAFT_APPLICATION' in sql and not changed:
                changed=True; original('ALTER TABLE contents MODIFY genres VARCHAR(700);')
            return original(sql)
        with patch.object(self.db,'run',side_effect=race),self.assertRaises(CatalogError): self.run_apply()
        self.assertTrue(changed); self.assertFalse(stage.schema_status(self.db))

    def test_midway_data_failure_leaves_empty_complete_schema_and_same_plan_can_resume(self):
        original=apply.execution_sql
        def broken(*args):
            return original(*args).replace('-- AFTER_catalog_content_aliases','INSERT INTO metadata_gate VALUES(1);\n-- AFTER_catalog_content_aliases',1)
        with patch.object(apply,'execution_sql',side_effect=broken),self.assertRaises(CatalogError): self.run_apply()
        self.assertTrue(self.assert_empty()['schema_present'])
        self.assertEqual('FAILED_OR_UNACKNOWLEDGED',dry.load_json(self.root/'first/result.json')['state'])
        self.assertEqual(421,self.run_apply('resume')['inserted'])

    def test_partial_ddl_is_recorded_and_never_auto_repaired_or_dropped(self):
        original=apply.execution_sql
        def broken(*args):
            return original(*args).replace('CREATE TABLE `catalog_metadata_sources`','INSERT INTO metadata_apply_gate VALUES(1);\nCREATE TABLE `catalog_metadata_sources`',1)
        with patch.object(apply,'execution_sql',side_effect=broken),self.assertRaises(CatalogError): self.run_apply()
        self.assertEqual('1',self.db.run("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'catalog_metadata_%';").strip())
        self.assertEqual('FAILED_OR_UNACKNOWLEDGED',dry.load_json(self.root/'first/result.json')['state'])
        with self.assertRaises(CatalogError): self.run_apply('retry')
        self.assertFalse((self.root/'retry').exists())

    def test_lost_ack_and_unavailable_postcheck_require_explicit_receipt_replay(self):
        original=self.db.run
        def no_ack(sql):
            output=original(sql)
            return '' if '-- PINNED_DRAFT_APPLICATION' in sql else output
        with patch.object(self.db,'run',side_effect=no_ack),self.assertRaises(CatalogError): self.run_apply()
        before=stage.read_state(self.db)
        self.assertEqual(0,self.run_apply('replay')['inserted']); self.assertEqual(before,stage.read_state(self.db))
        read=apply.read_current; calls=0
        def failed_post(*args):
            nonlocal calls
            calls+=1
            if calls==2: raise CatalogError('Postcheck unavailable')
            return read(*args)
        with patch.object(apply,'read_current',side_effect=failed_post),self.assertRaises(CatalogError): self.run_apply('postcheck')
        self.assertEqual('FAILED_OR_UNACKNOWLEDGED',dry.load_json(self.root/'postcheck/result.json')['state'])
        self.assertEqual(0,self.run_apply('post-replay')['inserted'])

    def test_other_batch_or_partial_own_batch_is_preserved_and_rejected(self):
        self.run_apply(); self.db.run('DELETE FROM catalog_content_keywords LIMIT 1;')
        before=stage.read_state(self.db)
        with self.assertRaises(CatalogError): self.run_apply('partial')
        self.assertEqual(before,stage.read_state(self.db))

    def test_unrelated_batch_inserted_after_prepare_is_not_adopted(self):
        self.db.run(stage.schema_sql()+"INSERT INTO catalog_metadata_batches VALUES("+sql_value('a'*64)+','+sql_value('b'*64)+",JSON_OBJECT(),UTC_TIMESTAMP(6));")
        before=stage.read_state(self.db)
        with self.assertRaises(CatalogError): self.run_apply()
        self.assertEqual(before,stage.read_state(self.db))

    def test_busy_advisory_lock_blocks_migration_then_releases_without_retry(self):
        failures=[]
        def hold():
            try: self.db.run('SELECT GET_LOCK('+sql_value(apply.LOCK)+',0); SELECT SLEEP(25); SELECT RELEASE_LOCK('+sql_value(apply.LOCK)+');')
            except Exception as exc: failures.append(type(exc).__name__)
        thread=threading.Thread(target=hold); thread.start()
        try:
            acquired=False
            for _ in range(20):
                if self.db.run('SELECT IS_USED_LOCK('+sql_value(apply.LOCK)+') IS NOT NULL;').strip()=='1': acquired=True; break
                time.sleep(.1)
            self.assertTrue(acquired)
            with self.assertRaises(CatalogError): self.run_apply()
            self.assertFalse(stage.schema_status(self.db))
        finally: thread.join(timeout=30)
        self.assertFalse(thread.is_alive()); self.assertEqual([],failures)
        self.assertEqual(421,self.run_apply('explicit-retry')['inserted'])


@unittest.skipUnless(os.environ.get('METADATA_STAGE_MYSQL_TEST')=='true' and os.environ.get('METADATA_STAGE_PUBLIC_COLLECTION'),
                     'Explicit saved-public fixture required')
class PublicMetadataDraftApplyMysqlTest(unittest.TestCase):
    def test_real_public_batch_uses_same_apply_path_without_application_writes(self):
        collection=Path(os.environ['METADATA_STAGE_PUBLIC_COLLECTION'])
        bundle=stage.load_bundle(collection,apply.COLLECTION_SHA)
        admin=LocalCatalog(); app_before=stage.read_state(admin)
        public_before=dry.read_consistent(admin); dry.check_catalog(dry.contract(),*public_before)
        output=ROOT/'target/metadata-apply-path-20261126'/('public-rehearsal-'+uuid4().hex[:8])
        db=stage.OwnedScratch(admin)
        try:
            seed(db,bundle)
            plan=apply.prepare_plan(db,collection,output/'plan')
            first=apply.apply_plan(db,output/'plan/plan.json',plan['plan_sha256'],output/'first')
            self.assertEqual(1048,first['inserted'])
            after=stage.read_state(db)
            self.assertTrue(all(r['approved']==0 and r['active']==0 for table in stage.TABLES[2:] for r in after['metadata'][table]))
            self.assertEqual(21,sum(r['runtime_minutes'] is not None or r['episode_runtime_minutes'] is not None for r in after['catalog']))
            replay=apply.apply_plan(db,output/'plan/plan.json',plan['plan_sha256'],output/'replay')
            self.assertEqual(0,replay['inserted']); self.assertEqual(after,stage.read_state(db))
        finally: db.close()
        self.assertEqual(app_before,stage.read_state(admin)); self.assertEqual(public_before,dry.read_consistent(admin))
        write_json(output/'verification.json',{'state':'PASS','counts':first['counts'],'first_inserted':1048,'replay_inserted':0,
            'projection_sha256':apply.PROJECTION_SHA,'known_durations_preserved':21,'all_drafts_unapproved_inactive':True,
            'application_public_state_unchanged':True,'application_metadata_tables_present':app_before['schema_present'],
            'scratch_removed_verified':True,'provider_calls':0,'content_updates':0,'application_db_writes':0})


if __name__=='__main__': unittest.main()
