"""Offline release eligibility/provenance/CLI tests; synthetic judgments, not semantic acceptance."""
import copy
from contextlib import redirect_stderr, redirect_stdout
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import metadata_review_release as review
import metadata_enrichment_dry_run as dry
import metadata_storage_dry_run as stage
from catalog_pool import CatalogError, digest, write_json
from test_metadata_storage_dry_run import bundle_fixture, reseal
from test_metadata_draft_apply import write_input


def review_fixture():
    bundle,contract=bundle_fixture()
    public={r['id']:r for r in bundle['baseline'][0]['rows']}
    for r in bundle['collection']['records']:
        if r['kind']=='detail':
            r['data']['fields']['title' if r['media_type']=='MOVIE' else 'name']['value']=public[r['content_id']]['title']
    with patch.object(dry,'CONTRACT_SHA',digest(contract)),patch.object(dry,'contract',return_value=contract):reseal(bundle,contract)
    return bundle,contract


def setup_context(bundle):
    projected=stage.projection(bundle)
    plan={'plan_sha256':None,'collection_sha256':projected['batch_sha'],'projection_sha256':projected['projection_sha256'],
          'approved':False,'active':False,'apply_supported':False,
          'row_reviews':[{'table':t,'row_sha':r.get('row_sha',r.get('batch_sha')),'source_value_sha256':digest(r),
                          'approved':False,'active':False} for t,rows in projected['rows'].items() for r in rows]}
    plan['plan_sha256']=digest({k:v for k,v in plan.items() if k!='plan_sha256'})
    context={'bundle':bundle,'projected':projected,'plan':plan,
             'rows':{r['row_sha']:(t,r) for t,rows in projected['rows'].items() if t in review.KINDS for r in rows},
             'public':{r['id']:r for r in bundle['baseline'][0]['rows']}}
    return context


def patch_context(test, bundle, contract):
    for p in (patch.object(dry,'CONTRACT_SHA',digest(contract)),patch.object(dry,'contract',return_value=contract),
              patch.object(review,'COLLECTION_SHA',bundle['expected_hash'])):
        p.start();test.addCleanup(p.stop)
    context=setup_context(bundle)
    for name,value in [('PROJECTION_SHA',context['projected']['projection_sha256']),('REVIEW_SHA',context['plan']['plan_sha256'])]:
        p=patch.object(review,name,value);p.start();test.addCleanup(p.stop)
    return context


def approved(context, kind):
    table,row=next((t,r) for t,r in context['rows'].values() if review.KINDS[t]==kind and (kind!='ALIAS' or r['alias_kind']=='OFFICIAL_TITLE'))
    if kind=='KEYWORD': value={'label_ko':'검토 가상 라벨','label_version':'test-v1','category':'TOPIC',
                              'centrality':'CENTRAL','overview_quote':context['public'][row['content_id']]['overview'][:8]}
    elif kind=='ALIAS': value={'language':'ko','scope':'WHOLE_WORK','source_form':row['payload']['raw_forms'][0]}
    else: value={'minutes':row['minutes'],'scope':'MOVIE_TOTAL' if row['target_field']=='runtime_minutes' else 'REPRESENTATIVE_EPISODE','expected_existing':None}
    return {'row_sha':row['row_sha'],'source_value_sha256':digest(row),'decision':'APPROVE',
            'reviewer':{'kind':'CODEX','id':'synthetic-test-reviewer','at_utc':'2026-11-26T10:00:00Z'},
            'rationale':'합성 테스트의 명시적 판정. 실제 의미 승인 아님.','value':value}


def decisions(context, records):
    return review.template(context) | {'records':records}


class MetadataReviewReleaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.bundle,cls.contract=review_fixture()

    def setUp(self):
        self.context=patch_context(self,copy.deepcopy(self.bundle),self.contract)
        self.temp_root=review.ROOT/'target/metadata-review-unit-tmp';self.temp_root.mkdir(parents=True,exist_ok=True)

    def compile(self, records): return review.compile_review(self.context,decisions(self.context,records))

    def test_unreviewed_template_and_empty_decisions_cannot_release(self):
        for d in [review.template(self.context),decisions(self.context,[])]:
            result=review.compile_review(self.context,d)
            self.assertEqual('BLOCKED_NO_ELIGIBLE_RECORDS',result['candidate']['state'])
            self.assertEqual([],result['candidate']['entries']);self.assertFalse(result['candidate']['active'])
        self.assertTrue(all(r['reviewer'] is None for r in review.template(self.context)['records']))

    def test_explicit_selected_records_are_bound_sorted_inactive_and_discovery_only(self):
        records=[approved(self.context,k) for k in ['RUNTIME','KEYWORD','ALIAS']]
        result=self.compile(records);again=self.compile(list(reversed(records)))
        self.assertEqual(result,again);self.assertEqual(3,result['summary']['selected_count'])
        self.assertEqual('DECLARED_NOT_AUTHENTICATED',result['version']['reviewer_identity'])
        self.assertEqual('DISCOVERY_ONLY',next(e['use'] for e in result['candidate']['entries'] if e['kind']=='KEYWORD'))
        self.assertEqual([],result['candidate']['content_updates']);self.assertFalse(result['candidate']['application_apply_supported'])
        self.assertEqual({'UNREVIEWED'}, {r['payload']['review_status'] for r in self.context['projected']['rows']['catalog_content_keywords']})

    def test_duplicate_unknown_source_and_wrong_hash_rejected(self):
        record=approved(self.context,'KEYWORD')
        for values in [[record,record],[record|{'row_sha':'f'*64}],[record|{'source_value_sha256':'0'*64}]]:
            with self.subTest(values=len(values)),self.assertRaises(CatalogError):self.compile(values)

    def test_sources_and_field_review_rows_cannot_be_approved_as_relations(self):
        for t in ['catalog_metadata_sources','catalog_metadata_field_reviews']:
            row=self.context['projected']['rows'][t][0]
            r=approved(self.context,'KEYWORD')|{'row_sha':row['row_sha'],'source_value_sha256':digest(row)}
            with self.subTest(table=t),self.assertRaises(CatalogError):self.compile([r])

    def test_no_reviewer_fabrication_or_unknown_fields(self):
        r=approved(self.context,'KEYWORD')
        for change in [{'reviewer':None},{'reviewer':r['reviewer']|{'kind':'PROVIDER'}},
                       {'reviewer':r['reviewer']|{'at_utc':'2026-11-26T10:00:00'}},
                       {'reviewer':r['reviewer']|{'at_utc':'2026-11-26T10:00:00+09:00'}},
                       {'rationale':''},{'active':True}]:
            with self.subTest(change=change),self.assertRaises((CatalogError,ValueError)):self.compile([r|change])

    def test_hold_reject_and_missing_records_stay_excluded(self):
        r=approved(self.context,'KEYWORD')
        for choice in ['HOLD','REJECT']:
            result=self.compile([r|{'decision':choice,'value':{}}])
            self.assertEqual([],result['candidate']['entries']);self.assertGreater(result['summary']['missing_records'],0)
            with self.assertRaises(CatalogError): self.compile([r|{'decision':choice}])

    def test_quote_must_exist_and_incidental_or_mood_is_not_certified(self):
        r=approved(self.context,'KEYWORD')
        with self.assertRaises(CatalogError):self.compile([r|{'value':r['value']|{'overview_quote':'없는 인용'}}])
        for value in [r['value']|{'centrality':v} for v in ['INCIDENTAL','UNSUPPORTED','UNJUDGED']]+[r['value']|{'category':'MOOD'}]:
            self.assertEqual([],self.compile([r|{'value':value}])['candidate']['entries'])

    def test_translation_fields_and_runtime_scope_are_strict(self):
        r=approved(self.context,'KEYWORD')
        for value in [r['value']|{'label_ko':'English only'},r['value']|{'label_version':''},r['value']|{'guaranteed':True}]:
            with self.assertRaises(CatalogError):self.compile([r|{'value':value}])
        r=approved(self.context,'RUNTIME')
        for value in [r['value']|{'minutes':True},r['value']|{'minutes':r['value']['minutes']+1},r['value']|{'scope':'SERIES_TOTAL'},r['value']|{'expected_existing':5}]:
            with self.assertRaises(CatalogError):self.compile([r|{'value':value}])

    def test_unknown_language_alias_forms_scope_risks_and_ambiguity(self):
        r=approved(self.context,'ALIAS')
        for value in [r['value']|{'language':'UNKNOWN'},r['value']|{'language':'und'},r['value']|{'source_form':'invented'}]:
            with self.assertRaises(CatalogError):self.compile([r|{'value':value}])
        self.assertEqual([],self.compile([r|{'value':r['value']|{'scope':'SEASON'}}])['candidate']['entries'])
        row=self.context['rows'][r['row_sha']][1]
        row['payload']['title_type']={'state':'VALUE','value':'season 3'}
        self.assertEqual([],self.compile([r|{'source_value_sha256':digest(row)}])['candidate']['entries'])
        row['payload']['title_type']={'state':'EMPTY','value':''}
        other=next(i for i in self.context['public'] if i!=row['content_id'])
        self.context['public'][other]['title']=r['value']['source_form']
        self.assertEqual([],self.compile([r|{'source_value_sha256':digest(row)}])['candidate']['entries'])

    def test_version_changes_preserve_previous_version_identity(self):
        d=decisions(self.context,[approved(self.context,'KEYWORD')]); first=review.compile_review(self.context,d)
        changed=d|{'revision':'review-002','parent_version_sha':first['version']['version_sha']}
        second=review.compile_review(self.context,changed)
        self.assertNotEqual(first['version']['version_sha'],second['version']['version_sha'])
        self.assertEqual(first['version']['version_sha'],second['version']['parent_version_sha'])
        self.assertEqual(set(review.code_hashes()),set(first['version']['code_sha256']))

    def test_modified_prior_payload_is_not_adopted(self):
        result=self.compile([approved(self.context,'KEYWORD')]);history=review.db_rows(result)
        review.validate_history(self.context,history)
        for index in range(3):
            damaged=copy.deepcopy(history)
            if index==0:damaged[0][0]['payload']['revision']='tampered'
            elif index==1:damaged[1][0]['payload']['rationale']='tampered'
            else:damaged[2][0]['payload']['entries']=[]
            with self.subTest(index=index),self.assertRaises(CatalogError):review.validate_history(self.context,damaged)

    def test_load_context_rejects_changed_batch_projection_or_review_plan(self):
        with tempfile.TemporaryDirectory(dir=self.temp_root) as name:
            folder=Path(name);write_input(folder,self.context['bundle']);write_json(folder/'field-plan.json',self.context['plan'])
            loaded=review.load_context(folder/'collection.json',folder/'field-plan.json')
            self.assertEqual(self.context['projected'],loaded['projected'])
            for attr in ['COLLECTION_SHA','PROJECTION_SHA','REVIEW_SHA']:
                with patch.object(review,attr,'f'*64),self.subTest(attr=attr),self.assertRaises(CatalogError):review.load_context(folder/'collection.json',folder/'field-plan.json')

    def test_application_adapter_is_never_accepted(self):
        db=Mock()
        with self.assertRaises(CatalogError):review.store_owned_scratch(db,self.context,decisions(self.context,[]))
        db.run.assert_not_called()

    def test_cli_is_offline_does_not_overwrite_and_hides_error_input(self):
        with tempfile.TemporaryDirectory(dir=self.temp_root) as name,patch.object(review,'load_context',return_value=self.context):
            out=Path(name)/'out';args=['plan','--collection','unused','--output',str(out)]
            with redirect_stdout(io.StringIO()):self.assertEqual(0,review.main(args))
            self.assertEqual([],dry.load_json(out/'release-candidate.json')['entries'])
            with redirect_stderr(io.StringIO()):self.assertEqual(1,review.main(args))
            bad=Path(name)/'bad.json';bad.write_text('{"PRIVATE BODY": "private"}',encoding='utf-8')
            with redirect_stderr(io.StringIO()) as error:self.assertEqual(1,review.main(['plan','--collection','unused','--decisions',str(bad),'--output',str(Path(name)/'bad-output')]))
            self.assertNotIn('PRIVATE',error.getvalue());self.assertFalse((Path(name)/'bad-output').exists())

    def test_schema_reference_keeps_separate_immutable_raw_tables(self):
        self.assertEqual(review.schema_sql(),(review.ROOT/'docs/sql/catalog_metadata_review_release_v1.sql').read_text(encoding='utf-8'))
        self.assertIn('CHECK (active=0)',review.schema_sql())
        self.assertNotIn('ALTER TABLE',review.schema_sql());self.assertNotIn('contents',review.schema_sql())


if __name__=='__main__':unittest.main()
