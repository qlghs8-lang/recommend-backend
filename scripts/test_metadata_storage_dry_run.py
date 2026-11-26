"""Offline storage projection/guard tests; no DB, provider or credential access."""
import copy
from contextlib import redirect_stderr, redirect_stdout
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import metadata_storage_dry_run as stage
import metadata_enrichment_dry_run as dry
from audit_metadata_enrichment import propose
from catalog_pool import CatalogError, digest, write_json
from test_metadata_enrichment_audit import fixtures
from test_metadata_enrichment_dry_run import FakeClient


def bundle_fixture(known=None):
    public, mappings = fixtures(468)
    if known is not None:
        for row in public['rows']:
            row['runtime_minutes' if row['type']=='MOVIE' else 'episode_runtime_minutes'] = known if row['type']=='MOVIE' else 45
        for row in mappings['contents']:
            row['runtime_minutes' if row['type']=='MOVIE' else 'episode_runtime_minutes'] = known if row['type']=='MOVIE' else 45
    contract = propose(public,mappings)[1]
    with patch.object(dry,'CONTRACT_SHA',digest(contract)),patch.object(dry,'contract',return_value=contract):
        saved,baseline,current = dry.run(contract,lambda:(public,mappings),True,FakeClient,'TMDB_HTTP')
        review = dry.review(saved,contract,baseline,current,True)
    return {'collection':saved,'review':review,'baseline':baseline,'expected_hash':saved['collection_sha256']}, contract


def reseal(bundle, contract):
    dry.seal(bundle['collection']); bundle['expected_hash']=bundle['collection']['collection_sha256']
    bundle['review']=dry.review(bundle['collection'],contract,bundle['baseline'],bundle['baseline'],True)


def empty_state(bundle):
    return {'catalog':copy.deepcopy(bundle['baseline'][0]['rows']), 'mappings':copy.deepcopy(bundle['baseline'][1]['sources']),
            'metadata':{t:[] for t in stage.TABLES},'schema_present':False}


def stored_state(bundle, projected):
    result=empty_state(bundle); result['schema_present']=True
    result['metadata']={t:[row|{'created_at':'2026-11-26T00:00:00.000000Z'} for row in rows] for t,rows in projected['rows'].items()}
    return result


class MetadataStorageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.template,cls.contract=bundle_fixture()

    def setUp(self):
        self.bundle=copy.deepcopy(self.template)
        for p in (patch.object(dry,'CONTRACT_SHA',digest(self.contract)),patch.object(dry,'contract',return_value=self.contract)):
            p.start(); self.addCleanup(p.stop)

    def test_projection_has_complete_provenance_and_no_activation(self):
        p=stage.projection(self.bundle)
        self.assertEqual([1,84,28,84,196,28],[len(p['rows'][t]) for t in stage.TABLES])
        self.assertEqual([],p['content_updates']); self.assertFalse(p['application_apply_supported'])
        for table in stage.TABLES[2:]:
            self.assertTrue(all(row['approved']==0 and row['active']==0 for row in p['rows'][table]))
        self.assertTrue(all(r['payload']['language']=='UNKNOWN' and r['payload']['translated_labels']==[]
                            for r in p['rows']['catalog_content_keywords']))

    def test_projection_is_deterministic_and_every_child_has_a_source(self):
        p=stage.projection(self.bundle); self.assertEqual(p,stage.projection(self.bundle))
        sources={r['row_sha'] for r in p['rows'][stage.TABLES[1]]}
        for table in stage.TABLES[2:]: self.assertTrue(all(r['source_sha'] in sources for r in p['rows'][table]))
        self.assertEqual(84,len(sources))

    def test_duplicate_keywords_and_aliases_keep_raw_ordinals_and_forms(self):
        p=stage.projection(self.bundle)
        self.assertTrue(all(row['payload']['raw_ordinals']==[0,1] for row in p['rows']['catalog_content_keywords']))
        alternatives=[r for r in p['rows']['catalog_content_aliases'] if r['alias_kind']=='ALTERNATIVE_TITLE']
        self.assertEqual(28,len(alternatives)); self.assertTrue(all(len(r['payload']['raw_forms'])==2 for r in alternatives))
        self.assertTrue(all(r['payload']['language']=={'state':'UNKNOWN','value':None} for r in alternatives))
        self.assertEqual(28,len({r['row_sha'] for r in alternatives}))  # Same alias across works stays ambiguous.

    def test_missing_null_empty_keyword_observations_are_retained(self):
        for state,value in [('MISSING',None),('NULL',None),('EMPTY',[])]:
            b=copy.deepcopy(self.bundle); record=b['collection']['records'][1]
            record['data']['state']=state; record['data']['entries']=value; reseal(b,self.contract)
            p=stage.projection(b)
            self.assertEqual(27,len(p['rows']['catalog_content_keywords']))
            field=[r for r in p['rows']['catalog_metadata_field_reviews'] if r['content_id']==record['content_id'] and r['field_name']=='keywords'][0]
            self.assertEqual(state,field['decision']); self.assertEqual(state,field['payload']['source_value']['state'])

    def test_wrong_collection_pin_and_resealed_content_tampering_rejected(self):
        b=copy.deepcopy(self.bundle); b['expected_hash']='b'*64
        with self.assertRaises(CatalogError): stage.projection(b)
        b=copy.deepcopy(self.bundle); b['collection']['records'][0]['data']['fields']['overview']['value']='changed'
        dry.seal(b['collection'])
        with self.assertRaises(CatalogError): stage.projection(b)

    def test_changed_review_approval_baseline_and_synthetic_source_rejected(self):
        for mutation in ('review','baseline','synthetic','postcheck'):
            b=copy.deepcopy(self.bundle)
            if mutation=='review': b['review']['items'][0]['approved']=True
            if mutation=='baseline': b['baseline'][0]['rows'][0]['title']+=' changed'
            if mutation=='synthetic': b['collection']['source_kind']='SYNTHETIC_OFFLINE'; reseal(b,self.contract)
            if mutation=='postcheck': b['collection']['postcheck']='FAILED_OR_CHANGED'; reseal(b,self.contract)
            with self.subTest(mutation=mutation),self.assertRaises(CatalogError): stage.projection(b)

    def test_preexisting_durations_are_not_proposed_for_update_or_adopted(self):
        for known,expect_conflict in [(90,False),(80,True)]:
            b,contract=bundle_fixture(known)
            with patch.object(dry,'CONTRACT_SHA',digest(contract)),patch.object(dry,'contract',return_value=contract):
                p=stage.projection(b)
            self.assertEqual([],p['rows']['catalog_metadata_runtime_candidates'])
            reviews=p['rows']['catalog_metadata_field_reviews']
            conflict=[r for r in reviews if r['decision'].startswith('CONFLICT')]
            self.assertEqual(expect_conflict,bool(conflict)); self.assertEqual([],p['content_updates'])

    def test_pending_and_exact_idempotent_receipt_are_distinct(self):
        p=stage.projection(self.bundle)
        self.assertEqual('PENDING_DRAFT_STORAGE',stage.check_state(self.bundle,p,empty_state(self.bundle)))
        state=stored_state(self.bundle,p)
        self.assertEqual('ALREADY_STORED_UNREVIEWED',stage.check_state(self.bundle,p,state))
        sql=stage.transaction_sql(self.bundle,p,state,'ALREADY_STORED_UNREVIEWED')
        self.assertNotIn('SET @metadata_created_at',sql)
        self.assertNotIn('INSERT INTO `catalog_',sql)

    def test_partial_or_modified_previous_batch_is_not_repaired(self):
        p=stage.projection(self.bundle)
        for mutation in ('missing','label','approved_type','extra'):
            state=stored_state(self.bundle,p)
            if mutation=='missing': state['metadata']['catalog_content_keywords'].pop()
            if mutation=='label': state['metadata']['catalog_content_keywords'][0]['label']='changed'
            if mutation=='approved_type': state['metadata']['catalog_content_keywords'][0]['approved']=False
            if mutation=='extra': state['metadata']['catalog_content_keywords'].append(copy.deepcopy(state['metadata']['catalog_content_keywords'][0]))
            with self.subTest(mutation=mutation),self.assertRaises(CatalogError): stage.check_state(self.bundle,p,state)

    def test_catalog_time_text_and_mapping_drift_are_blocked(self):
        p=stage.projection(self.bundle)
        for mutation in ('time','text','mapping','delete'):
            state=empty_state(self.bundle)
            if mutation=='time': state['catalog'][0]['runtime_minutes']=90
            if mutation=='text': state['catalog'][0]['genres']='DRAMA'
            if mutation=='mapping': state['mappings'][0]['tmdb_id']+=1
            if mutation=='delete': state['catalog'].pop()
            with self.subTest(mutation=mutation),self.assertRaises(CatalogError): stage.check_state(self.bundle,p,state)

    def test_sql_uses_locks_exact_rows_and_precommit_checks_without_content_updates(self):
        p=stage.projection(self.bundle); sql=stage.transaction_sql(self.bundle,p,empty_state(self.bundle),'PENDING_DRAFT_STORAGE')
        self.assertIn('FOR UPDATE',sql); self.assertIn('e.payload <=>',sql)
        self.assertIn('created_at=@metadata_created_at',sql)
        self.assertLess(sql.index('FOR UPDATE'),sql.index('INSERT INTO `catalog_'))
        self.assertLess(sql.index('-- AFTER_catalog_metadata_runtime_candidates'),sql.index('COMMIT;'))
        self.assertNotIn('UPDATE contents',sql); self.assertNotIn('catalog_runtime_enrichments',sql)
        self.assertNotIn('DELETE FROM',sql)

    def test_untrusted_strings_are_encoded_as_data(self):
        b=copy.deepcopy(self.bundle); text='가족\"; DROP TABLE contents; -- 😀'
        for entry in b['collection']['records'][1]['data']['entries']: entry['name']=text
        reseal(b,self.contract); p=stage.projection(b)
        sql=stage.transaction_sql(b,p,empty_state(b),'PENDING_DRAFT_STORAGE')
        self.assertNotIn(text,sql); self.assertIn(text.encode().hex(),sql)
        self.assertIn(text,[r['label'] for r in p['rows']['catalog_content_keywords']])

    def test_application_adapter_and_unowned_schema_are_rejected(self):
        db=Mock()
        with self.assertRaises(CatalogError): stage.store_owned_scratch(db,self.bundle,Path('unused'))
        db.run.assert_not_called()
        with patch.dict(os.environ,{'METADATA_STAGE_MYSQL_TEST':''}):
            with self.assertRaises(CatalogError): stage.OwnedScratch(db)
        db.run.assert_not_called()
        fake=object.__new__(stage.OwnedScratch); fake.schema='myproject'; fake.owned=True; fake.admin=db
        with self.assertRaises(CatalogError): fake.run('SELECT 1;')
        with self.assertRaises(CatalogError): fake.close()
        db.run.assert_not_called()

    def test_reference_ddl_matches_generator_and_preserves_delete_cascade(self):
        self.assertEqual(stage.schema_sql(),(stage.ROOT/'docs/sql/catalog_metadata_drafts_v1.sql').read_text(encoding='utf-8'))
        sql=stage.schema_sql()
        self.assertEqual(6,sql.count('CREATE TABLE')); self.assertIn('ON DELETE CASCADE',sql)
        self.assertIn('CHECK (approved=0 AND active=0)',sql)
        self.assertNotIn('ALTER TABLE contents',sql)

    def test_cli_offline_and_existing_output_guard_do_not_construct_db(self):
        fixture_root=stage.ROOT/'target/metadata-storage-unit-tmp'; fixture_root.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=fixture_root) as tmp:
            base=Path(tmp); source=base/'input'; output=base/'plan'
            for name,value in [('collection',self.bundle['collection']),('review',self.bundle['review']),
                               ('public-snapshot',self.bundle['baseline'][0]),('mapping-snapshot',self.bundle['baseline'][1])]:
                write_json(source/(name+'.json'),value)
            args=['plan','--collection',str(source/'collection.json'),'--collection-sha256',self.bundle['expected_hash'],'--output',str(output)]
            with patch.object(stage,'LocalCatalog',side_effect=AssertionError('No DB')),redirect_stdout(io.StringIO()):
                self.assertEqual(0,stage.main(args))
                with redirect_stderr(io.StringIO()): self.assertEqual(1,stage.main(args+['--live']))
                with redirect_stderr(io.StringIO()),self.assertRaises(SystemExit): stage.main(['apply'])
            report=dry.load_json(output/'summary.json')
            self.assertFalse(report['actual_schema_applied']); self.assertEqual(0,report['content_updates'])


if __name__=='__main__': unittest.main()
