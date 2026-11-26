"""Offline pins, reviewed-only projection and append-only activation protocol."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import metadata_search_activation as search
from catalog_pool import CatalogError, digest, write_json


class SearchActivationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ctx, cls.compiled, cls.expected = search.fixed_context()
        cls.payload = search.view_payload(cls.ctx, cls.compiled)

    def test_resource_is_exact_reviewed_only_discovery_with_no_runtime(self):
        self.assertEqual(search.RESOURCE.read_bytes(), self.payload.encode())
        view = search.build_view(self.ctx, self.compiled)
        self.assertEqual(28, len(view['works']))
        self.assertEqual(26, sum(len(w['keywords']) for w in view['works']))
        self.assertEqual(55, sum(len(w['aliases']) for w in view['works']))
        self.assertNotIn('runtime', self.payload)
        self.assertEqual(81, sum(len(w['keywords'])+len(w['aliases']) for w in view['works']))
        self.assertEqual('-- Append-only discovery activation events. Raw/review candidates remain inactive.\n'+search.DDL,
                         (search.ROOT/'docs/sql/catalog_metadata_search_v1.sql').read_text(encoding='utf-8'))
        original = {e['source_row_sha'] for e in self.compiled['candidate']['entries'] if e['kind'] != 'RUNTIME'}
        self.assertEqual(original, {e['row_sha'] for w in view['works'] for f in ['keywords','aliases'] for e in w[f]})

    def test_unknown_or_unreviewed_aliases_never_enter_view(self):
        view = search.build_view(self.ctx, self.compiled)
        selected = {e['row_sha'] for w in view['works'] for e in w['aliases']}
        self.assertTrue(all(row['alias_kind'] != 'ALTERNATIVE_TITLE' for table,row in self.ctx['rows'].values() if row['row_sha'] in selected))
        self.assertEqual(self.ctx['public'][45]['overview'], next(w for w in view['works'] if w['content_id']==45)['overview'])

    def event(self, enabled=1, rows=None):
        return search.make_event({'schema_present': bool(rows), 'rows': rows or []}, enabled, self.payload, 'Offline test', '2026-11-26T14:00:00+00:00')

    def test_enable_withdraw_and_identical_event_hash_are_explicit(self):
        event = self.event(); self.assertEqual(event, self.event())
        saved = event | {'event_id': 1, 'created_at':'2026-11-26T14:00:00.000000Z'}
        self.assertEqual(event['event_sha'], self.event(0,[saved])['previous_sha'])
        with self.assertRaises(CatalogError): self.event(1,[saved])
        with self.assertRaises(CatalogError): self.event(0)
        with self.assertRaises(CatalogError): search.make_event({'rows':[]},1,self.payload,'Test','2026-11-26T14:00:00')

    def test_transition_rejects_foreign_partial_and_modified_old_rows(self):
        event = self.event()
        before = {'metadata': {'pin': 1}, 'events': {'schema_present':False,'rows':[]}}
        after = copy.deepcopy(before);after['events']={'schema_present':True,'rows':[event|{'event_id':1,'created_at':'fixed'}]}
        self.assertTrue(search.transition(before,after,event))
        after['metadata']['pin']=2
        with self.assertRaises(CatalogError):search.transition(before,after,event)
        after['metadata']['pin']=1;after['events']['rows'][0]['enabled']=0
        with self.assertRaises(CatalogError):search.transition(before,after,event)

    def test_pinned_plan_tampering_rejected_before_db_or_attempt_creation(self):
        folder=search.ROOT/'target/search-activation-unit-tmp';folder.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as name:
            out=Path(name)
            _,_,_,_,before,env=search.app.load_plan(search.BASE_PLAN,search.BASE_PLAN_SHA)
            state={'metadata':before|{'reviews':{'schema_present':True,'rows':[[r|{'created_at':'2026-11-26T14:00:00.000000Z'} for r in group] for group in self.expected]}},'events':{'schema_present':False,'rows':[]}}
            with patch.object(search,'current',return_value=(env,state)):
                plan=search.prepare(Mock(),1,'Offline synthetic state',out/'plan')
            search.load_plan(out/'plan/plan.json',plan['plan_sha256'])
            file=out/'plan/event.json';file.write_bytes(file.read_bytes()+b' ')
            db=Mock()
            with self.assertRaises(CatalogError):search.apply(db,out/'plan/plan.json',plan['plan_sha256'],out/'attempt')
            db.run.assert_not_called();self.assertFalse((out/'attempt').exists())


if __name__=='__main__':unittest.main()
