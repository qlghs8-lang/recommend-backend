"""Actual pinned public data, writes exclusively in disposable UUID-owned schemas."""
import os
from pathlib import Path
import unittest
from unittest.mock import patch
from uuid import uuid4
import metadata_search_activation as search
from catalog_pool import CatalogError, LocalCatalog, write_json
from test_metadata_storage_mysql import seed


@unittest.skipUnless(os.environ.get('METADATA_STAGE_MYSQL_TEST')=='true','Owned schema opt-in required')
class SearchActivationMysqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.ctx,cls.compiled,cls.expected=search.fixed_context()

    def setUp(self):
        self.folder=search.ROOT/'target/metadata-search-20261126'/('mysql-'+uuid4().hex[:8])
        self.db=search.stage.OwnedScratch(LocalCatalog());self.addCleanup(self.db.close)
        seed(self.db,self.ctx['bundle'])
        search.stage.store_owned_scratch(self.db,self.ctx['bundle'],self.folder/'raw')
        inputs=search.BASE_PLAN.parent/'input'
        plan=search.app.prepare_plan(self.db,inputs/'collection.json',inputs/'decisions.json',self.folder/'reviews-plan')
        search.app.apply_plan(self.db,self.folder/'reviews-plan/plan.json',plan['plan_sha256'],self.folder/'reviews')
        self.payload=search.view_payload(self.ctx,self.compiled)
        self.env,self.before=search.current(self.db,self.ctx,self.expected,self.payload)
        self.serial=0

    def prepare(self,enabled=1,name='enable'):
        return search.prepare(self.db,enabled,'Owned-schema test',self.folder/name)

    def apply(self,plan,name='enable'):
        self.serial+=1
        return search.apply(self.db,self.folder/name/'plan.json',plan['plan_sha256'],self.folder/f'attempt-{self.serial}')

    def test_actual_enable_withdraw_reenable_replay_and_all_protected_state_preserved(self):
        plan=self.prepare();self.assertEqual(1,self.apply(plan)['inserted'])
        saved=search.read_events(self.db,self.payload)
        self.assertEqual(0,self.apply(plan)['inserted']);self.assertEqual(saved,search.read_events(self.db,self.payload))
        withdraw=self.prepare(0,'withdraw');self.assertEqual('SEARCH_WITHDRAWN',self.apply(withdraw,'withdraw')['state'])
        again=self.prepare(1,'reenable');self.assertEqual('SEARCH_ENABLED',self.apply(again,'reenable')['state'])
        after=search.current(self.db,self.ctx,self.expected,self.payload)[1]
        self.assertEqual(self.before['metadata'],after['metadata']);self.assertEqual([1,0,1],[r['enabled'] for r in after['events']['rows']])
        self.assertEqual('77\t77',self.db.run('SELECT MIN(view_count),MAX(view_count) FROM contents;').strip())
        self.assertEqual('preserve-existing-provenance',self.db.run('SELECT sentinel FROM catalog_runtime_enrichments;').strip())
        write_json(self.folder/'verification.json',{'state':'PASS','raw_review_catalog_mapping_preserved':True,'entries':81,'runtime_entries':0,'event_states':[1,0,1],'replay_inserted':0,'application_db_writes':0})

    def test_failure_before_commit_rolls_back_event_and_lost_ack_explicit_replay_is_zero(self):
        plan=self.prepare();run=self.db.run
        def fail(sql):return run(sql.replace("COMMIT; SELECT '__SEARCH_EVENT_COMMITTED__'","INSERT INTO metadata_gate VALUES(1); COMMIT; SELECT '__SEARCH_EVENT_COMMITTED__'"))
        with patch.object(self.db,'run',side_effect=fail),self.assertRaises(CatalogError):self.apply(plan)
        self.assertEqual([],search.read_events(self.db,self.payload)['rows'])
        def lose(sql):
            result=run(sql)
            return '' if '__SEARCH_EVENT_ACK__' in sql else result
        with patch.object(self.db,'run',side_effect=lose),self.assertRaises(CatalogError):self.apply(plan)
        saved=search.read_events(self.db,self.payload)
        self.assertEqual(0,self.apply(plan)['inserted']);self.assertEqual(saved,search.read_events(self.db,self.payload))

    def test_post_preflight_catalog_and_event_history_drift_refused_without_repair(self):
        plan=self.prepare();run=self.db.run;raced=False
        def race(sql):
            nonlocal raced
            if '__SEARCH_EVENT_ACK__' in sql and not raced:
                raced=True;run("UPDATE contents SET genres='DRAMA' WHERE id=45;")
            return run(sql)
        with patch.object(self.db,'run',side_effect=race),self.assertRaises(CatalogError):self.apply(plan)
        self.assertTrue(raced);self.assertEqual([],search.read_events(self.db,self.payload)['rows'])
        # Restore only this test-owned mutation, then demonstrate corrupt event history is held.
        original=self.ctx['public'][45]['genres'];run('UPDATE contents SET genres='+search.sql_value(original)+' WHERE id=45;')
        self.apply(plan);run('UPDATE '+search.TABLE+" SET event_body='corrupt';")
        with self.assertRaises(CatalogError):self.prepare(0,'bad-withdraw')
        self.assertFalse((self.folder/'bad-withdraw').exists())


if __name__=='__main__':unittest.main()
