"""Offline tests of pinned plan packaging, drift gates and durable attempt receipts."""
import copy
from contextlib import redirect_stderr
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import apply_metadata_drafts as apply
import metadata_enrichment_dry_run as dry
import metadata_storage_dry_run as stage
from catalog_pool import CatalogError, digest, write_json
from test_metadata_storage_dry_run import bundle_fixture, empty_state, stored_state


def write_input(folder,bundle):
    for name,value in zip(apply.INPUT_FILES,[bundle['collection'],bundle['review'],*bundle['baseline']]):
        write_json(folder/name,value)


class MetadataDraftApplyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.bundle,cls.contract=bundle_fixture()

    def setUp(self):
        for p in (patch.object(dry,'CONTRACT_SHA',digest(self.contract)),patch.object(dry,'contract',return_value=self.contract),
                  patch.object(apply,'COLLECTION_SHA',self.bundle['expected_hash'])):
            p.start(); self.addCleanup(p.stop)
        self.projected=stage.projection(self.bundle)
        p=patch.object(apply,'PROJECTION_SHA',self.projected['projection_sha256']); p.start(); self.addCleanup(p.stop)
        tmp_root=apply.ROOT/'target/metadata-apply-unit-tmp'; tmp_root.mkdir(parents=True,exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(dir=tmp_root); self.addCleanup(self.temp.cleanup); self.root=Path(self.temp.name)
        write_input(self.root/'input',self.bundle)
        self.before=empty_state(self.bundle)
        self.env={'identity':{'database':'myproject','server_uuid':'test-only-uuid','server_version':'8.test'},'base_columns':[]}
        self.db=Mock()

    def prepare(self):
        with patch.object(apply,'read_current',return_value=(self.env,self.before,'PENDING_DRAFT_STORAGE')):
            return apply.prepare_plan(self.db,self.root/'input/collection.json',self.root/'plan')

    def test_prepare_pins_copies_artifacts_code_target_and_unapproved_scope_without_writing_db(self):
        plan=self.prepare(); self.db.run.assert_not_called()
        loaded=apply.load_plan(self.root/'plan/plan.json',plan['plan_sha256'])
        self.assertEqual(self.projected,loaded[2]); self.assertEqual(self.before,loaded[3])
        self.assertEqual(list(stage.TABLES),plan['create_tables_if_absent'])
        self.assertFalse(plan['approved']); self.assertFalse(plan['active']); self.assertEqual(0,plan['content_updates'])
        self.assertEqual(set(apply.CODE_FILES),set(plan['code_sha256']))
        self.assertEqual(set(apply.INPUT_FILES),set(plan['inputs']))

    def test_wrong_batch_or_projection_is_rejected(self):
        for field in ['COLLECTION_SHA','PROJECTION_SHA']:
            with patch.object(apply,field,'a'*64),self.subTest(field=field),self.assertRaises(CatalogError):
                apply.checked_projection(self.bundle)

    def test_modified_input_or_artifact_rejects_before_any_database_action(self):
        plan=self.prepare()
        for name in [*apply.ARTIFACTS,*('input/'+n for n in apply.INPUT_FILES)]:
            path=self.root/'plan'/name; data=path.read_bytes(); path.write_bytes(data+b' ')
            with self.subTest(file=name),self.assertRaises(CatalogError):
                apply.apply_plan(self.db,self.root/'plan/plan.json',plan['plan_sha256'],self.root/'attempt')
            path.write_bytes(data)
        self.db.run.assert_not_called(); self.assertFalse((self.root/'attempt').exists())

    def test_wrong_hash_changed_code_and_resealed_sql_are_rejected(self):
        plan=self.prepare(); path=self.root/'plan/plan.json'
        with self.assertRaises(CatalogError): apply.load_plan(path,'0'*64)
        with patch.object(apply,'code_hashes',return_value={}):
            with self.assertRaises(CatalogError): apply.load_plan(path,plan['plan_sha256'])
        sql=self.root/'plan/transaction.sql'; sql.write_text('DROP TABLE contents;',encoding='utf-8')
        plan['artifacts']['transaction.sql']=apply.file_sha(sql); apply.seal(plan); write_json(path,plan)
        with self.assertRaises(CatalogError): apply.load_plan(path,plan['plan_sha256'])

    def test_only_compatible_migration_and_exact_new_batch_transitions_are_accepted(self):
        migrated=copy.deepcopy(self.before); migrated['schema_present']=True
        apply.allowed_transition(self.bundle,self.projected,self.before,migrated,'PENDING_DRAFT_STORAGE')
        stored=stored_state(self.bundle,self.projected)
        apply.allowed_transition(self.bundle,self.projected,self.before,stored,'ALREADY_STORED_UNREVIEWED')
        stored['metadata'][stage.TABLES[0]].append({'batch_sha':'a'*64,'created_at':'other'})
        with self.assertRaises(CatalogError): apply.allowed_transition(self.bundle,self.projected,self.before,stored,'ALREADY_STORED_UNREVIEWED')
        changed=copy.deepcopy(migrated); changed['catalog'][0]['title']='changed'
        with self.assertRaises(CatalogError): apply.allowed_transition(self.bundle,self.projected,self.before,changed,'PENDING_DRAFT_STORAGE')

    def test_target_change_rejects_before_attempt_or_sql(self):
        plan=self.prepare(); env=copy.deepcopy(self.env); env['identity']['server_uuid']='other-server'
        with patch.object(apply,'read_current',return_value=(env,self.before,'PENDING_DRAFT_STORAGE')),self.assertRaises(CatalogError):
            apply.apply_plan(self.db,self.root/'plan/plan.json',plan['plan_sha256'],self.root/'attempt')
        self.db.run.assert_not_called(); self.assertFalse((self.root/'attempt').exists())

    def test_success_checks_receipt_and_writes_narrow_backup(self):
        plan=self.prepare(); after=stored_state(self.bundle,self.projected)
        self.db.run.return_value='__METADATA_DRAFT_COMMITTED__\n__METADATA_APPLICATION_ACK__'
        with patch.object(apply,'read_current',side_effect=[(self.env,self.before,'PENDING_DRAFT_STORAGE'),(self.env,after,'ALREADY_STORED_UNREVIEWED')]):
            result=apply.apply_plan(self.db,self.root/'plan/plan.json',plan['plan_sha256'],self.root/'attempt')
        self.assertEqual(421,result['inserted']); self.assertEqual('STORED_UNREVIEWED',result['state'])
        backup=dry.load_json(self.root/'attempt/backup-manifest.json')
        self.assertFalse(backup['full_database_backup']); self.assertEqual(digest(self.before),backup['before_sha256'])
        self.assertEqual(after,dry.load_json(self.root/'attempt/after.json'))

    def test_missing_commit_ack_and_failed_postcheck_persist_uncertain_status(self):
        plan=self.prepare()
        for index in [0,1]:
            self.db.run.return_value='' if index==0 else '__METADATA_DRAFT_COMMITTED__\n__METADATA_APPLICATION_ACK__'
            reads=[(self.env,self.before,'PENDING_DRAFT_STORAGE'),CatalogError('Postcheck unavailable')]
            with patch.object(apply,'read_current',side_effect=reads),self.subTest(index=index),self.assertRaises(CatalogError):
                apply.apply_plan(self.db,self.root/'plan/plan.json',plan['plan_sha256'],self.root/('attempt'+str(index)))
            receipt=dry.load_json(self.root/('attempt'+str(index))/'result.json')
            self.assertEqual('FAILED_OR_UNACKNOWLEDGED',receipt['state']); self.assertFalse(receipt['automatic_retry'])

    def test_execution_holds_advisory_lock_and_rechecks_environment_inside_transaction(self):
        sql=apply.execution_sql(self.bundle,self.projected,self.before,'PENDING_DRAFT_STORAGE',self.env)
        self.assertLess(sql.index('GET_LOCK('),sql.index('CREATE TABLE `catalog_metadata_batches`'))
        self.assertGreater(sql.index('RELEASE_LOCK('),sql.index('COMMIT;'))
        self.assertGreater(sql.rindex('@@server_uuid'),sql.index('FOR UPDATE;'))
        self.assertNotIn('UPDATE contents',sql); self.assertNotIn('DROP TABLE',sql)
        self.assertNotIn('DELETE FROM',sql); self.assertNotIn('catalog_runtime_enrichments',sql)

    def test_cli_invalid_plan_and_existing_output_do_not_initialize_adapter(self):
        plan=self.prepare()
        with patch.object(apply,'LocalCatalog',side_effect=AssertionError('No connection')),redirect_stderr(io.StringIO()):
            self.assertEqual(1,apply.main(['apply','--plan',str(self.root/'plan/plan.json'),'--plan-sha256','0'*64,'--output',str(self.root/'attempt')]))
            self.assertEqual(1,apply.main(['prepare','--collection',str(self.root/'input/collection.json'),'--output',str(self.root/'plan')]))


if __name__=='__main__': unittest.main()
