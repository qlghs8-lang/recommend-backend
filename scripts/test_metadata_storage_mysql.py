"""Opt-in MySQL integration tests; writes only to fresh UUID-owned disposable schemas.

METADATA_STAGE_MYSQL_TEST=true python -m unittest discover -s scripts -p test_metadata_storage_mysql.py -v
Synthetic fixtures never call TMDB. No application table DDL/DML is executed.
"""
import copy
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from uuid import uuid4

import metadata_storage_dry_run as stage
import metadata_enrichment_dry_run as dry
from catalog_pool import CatalogError, LocalCatalog, ROOT, digest, sql_value, write_json
from test_metadata_storage_dry_run import bundle_fixture, reseal


def seed(db, bundle):
    """Only the owned adapter can seed the narrow public test catalog."""
    stage.require(isinstance(db, stage.OwnedScratch) and db.owned)
    sql = """CREATE TABLE contents (
 id BIGINT PRIMARY KEY, type VARCHAR(10), title VARCHAR(500), overview TEXT,
 genres VARCHAR(500), release_date DATE, runtime_minutes INT NULL,
 episode_runtime_minutes INT NULL, view_count BIGINT NOT NULL DEFAULT 77
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE catalog_import_sources (
 media_type VARCHAR(10) NOT NULL, tmdb_id BIGINT NOT NULL, content_id BIGINT NOT NULL,
 fetched_at DATETIME NOT NULL DEFAULT '2026-01-01 00:00:00',
 PRIMARY KEY(media_type,tmdb_id), UNIQUE(content_id),
 FOREIGN KEY(content_id) REFERENCES contents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE catalog_runtime_enrichments (content_id BIGINT PRIMARY KEY, sentinel VARCHAR(100)) ENGINE=InnoDB;
INSERT INTO catalog_runtime_enrichments VALUES(1,'preserve-existing-provenance');
"""
    fields = tuple(bundle['baseline'][0]['rows'][0])
    sql += 'INSERT INTO contents (' + ','.join('`'+f+'`' for f in fields) + ') VALUES ' + ','.join(
        '(' + ','.join(sql_value(row[f]) for f in fields) + ')' for row in bundle['baseline'][0]['rows']) + ';\n'
    fields = ('media_type','tmdb_id','content_id')
    sql += 'INSERT INTO catalog_import_sources (' + ','.join(fields) + ') VALUES ' + ','.join(
        '(' + ','.join(sql_value(row[f]) for f in fields) + ')' for row in bundle['baseline'][1]['sources']) + ';\n'
    db.run(sql)


@unittest.skipUnless(os.environ.get('METADATA_STAGE_MYSQL_TEST')=='true', 'Owned-schema opt-in required')
class MetadataStorageMysqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.template,cls.contract=bundle_fixture()

    def setUp(self):
        self.bundle=copy.deepcopy(self.template)
        for p in (patch.object(dry,'CONTRACT_SHA',digest(self.contract)),patch.object(dry,'contract',return_value=self.contract)):
            p.start(); self.addCleanup(p.stop)
        self.db=stage.OwnedScratch(LocalCatalog()); self.addCleanup(self.db.close)
        seed(self.db,self.bundle)
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)

    def store(self, name='first'):
        return stage.store_owned_scratch(self.db,self.bundle,self.path/name)

    def assert_empty(self):
        state=stage.read_state(self.db)
        self.assertTrue(all(not rows for rows in state['metadata'].values()))
        return state

    def test_atomic_store_replay_preserves_catalog_mapping_legacy_and_timestamps(self):
        before=stage.read_state(self.db)
        result=self.store()
        self.assertEqual('STORED_UNREVIEWED',result['state']); self.assertEqual(421,result['inserted'])
        after=stage.read_state(self.db)
        self.assertEqual(before['catalog'],after['catalog']); self.assertEqual(before['mappings'],after['mappings'])
        for table in stage.TABLES[2:]:
            self.assertTrue(all(r['approved']==0 and r['active']==0 for r in after['metadata'][table]))
        self.assertEqual('preserve-existing-provenance',self.db.run('SELECT sentinel FROM catalog_runtime_enrichments;').strip())
        self.assertEqual('77\t77',self.db.run('SELECT MIN(view_count),MAX(view_count) FROM contents;').strip())
        again=self.store('replay'); self.assertEqual(0,again['inserted'])
        self.assertEqual('ALREADY_STORED_UNREVIEWED',again['state'])
        self.assertEqual(after,stage.read_state(self.db))

    def test_existing_catalog_or_mapping_drift_rejects_before_draft_ddl(self):
        for index,sql in enumerate(("UPDATE contents SET runtime_minutes=121 WHERE id=1;",
                                    "UPDATE contents SET title=CONCAT(title,' Changed') WHERE id=1;",
                                    "UPDATE catalog_import_sources SET tmdb_id=90001 WHERE content_id=1;")):
            self.db.run(sql)
            with self.subTest(index=index),self.assertRaises(CatalogError): self.store('stale'+str(index))
            self.assertFalse(stage.schema_status(self.db))

    def test_mid_transaction_error_rolls_back_every_metadata_table(self):
        original=stage.transaction_sql
        def broken(*args):
            return original(*args).replace('-- AFTER_catalog_content_aliases',
                'INSERT INTO metadata_gate VALUES(1);\n-- AFTER_catalog_content_aliases',1)
        with patch.object(stage,'transaction_sql',side_effect=broken),self.assertRaises(CatalogError): self.store()
        self.assertTrue(self.assert_empty()['schema_present'])  # DDL is explicitly outside data atomicity.
        self.assertEqual('FAILED_OR_UNACKNOWLEDGED',dry.load_json(self.path/'first/result.json')['state'])
        self.assertEqual(421,self.store('explicit-retry')['inserted'])

    def test_concurrent_case_sensitive_catalog_drift_rejects_under_lock(self):
        original=self.db.run; raced=False
        def race(sql):
            nonlocal raced
            if '-- DRAFT_METADATA_TRANSACTION' in sql and not raced:
                raced=True; original("UPDATE contents SET genres='DRAMA' WHERE id=1;")
            return original(sql)
        with patch.object(self.db,'run',side_effect=race),self.assertRaises(CatalogError): self.store()
        self.assertTrue(raced); self.assert_empty()
        self.assertEqual('DRAMA',original('SELECT genres FROM contents WHERE id=1;').strip())

    def test_concurrent_mapping_drift_rejects_without_adopting_it(self):
        original=self.db.run; raced=False
        def race(sql):
            nonlocal raced
            if '-- DRAFT_METADATA_TRANSACTION' in sql and not raced:
                raced=True; original('UPDATE catalog_import_sources SET tmdb_id=90001 WHERE content_id=1;')
            return original(sql)
        with patch.object(self.db,'run',side_effect=race),self.assertRaises(CatalogError): self.store()
        self.assertTrue(raced); self.assert_empty()
        self.assertEqual('90001',original('SELECT tmdb_id FROM catalog_import_sources WHERE content_id=1;').strip())

    def test_partial_or_conflicting_saved_batch_is_preserved_and_rejected(self):
        self.store(); self.db.run('DELETE FROM catalog_content_keywords LIMIT 1;')
        before=stage.read_state(self.db)
        with self.assertRaises(CatalogError): self.store('partial')
        self.assertEqual(before,stage.read_state(self.db))

    def test_partial_schema_is_preserved_without_repair(self):
        self.db.run('CREATE TABLE catalog_content_keywords (sentinel INT PRIMARY KEY) ENGINE=InnoDB; INSERT INTO catalog_content_keywords VALUES(123);')
        with self.assertRaises(CatalogError): self.store()
        self.assertEqual('123',self.db.run('SELECT sentinel FROM catalog_content_keywords;').strip())

    def test_trigger_or_extra_unique_index_schema_is_rejected(self):
        self.db.run(stage.schema_sql())
        self.db.run('CREATE TRIGGER metadata_test_trigger BEFORE INSERT ON catalog_content_keywords FOR EACH ROW SET NEW.approved=0;')
        with self.assertRaises(CatalogError): self.store('trigger')
        self.db.run('DROP TRIGGER metadata_test_trigger; CREATE UNIQUE INDEX unwanted_unique ON catalog_content_aliases(normalized_title);')
        with self.assertRaises(CatalogError): self.store('extra-index')
        self.assertEqual('0',self.db.run('SELECT COUNT(*) FROM catalog_metadata_batches;').strip())

    def test_unicode_quotes_sql_text_roundtrips_without_execution(self):
        text="한국어 雪 😀 '); DROP TABLE contents; -- \\ \""
        for entry in self.bundle['collection']['records'][1]['data']['entries']: entry['name']=text
        reseal(self.bundle,self.contract)
        self.store()
        labels=[r['label'] for r in stage.read_state(self.db)['metadata']['catalog_content_keywords']]
        self.assertIn(text,labels)
        self.assertEqual('468',self.db.run('SELECT COUNT(*) FROM contents;').strip())

    def test_content_deletion_cascades_drafts_and_retry_rejects_stale_baseline(self):
        self.store()
        ident=self.bundle['collection']['records'][0]['content_id']
        self.db.run('DELETE FROM contents WHERE id='+str(ident)+';')
        for table in stage.TABLES[1:]:
            self.assertEqual('0',self.db.run('SELECT COUNT(*) FROM `'+table+'` WHERE content_id='+str(ident)+';').strip())
        self.assertEqual('1',self.db.run('SELECT COUNT(*) FROM catalog_metadata_batches;').strip())
        with self.assertRaises(CatalogError): self.store('stale-retry')

    def test_lost_commit_ack_is_reported_and_explicit_replay_has_no_duplicate_writes(self):
        original=self.db.run
        def no_ack(sql):
            output=original(sql)
            return '' if '-- DRAFT_METADATA_TRANSACTION' in sql else output
        with patch.object(self.db,'run',side_effect=no_ack),self.assertRaises(CatalogError): self.store()
        before=stage.read_state(self.db)
        self.assertEqual('FAILED_OR_UNACKNOWLEDGED',dry.load_json(self.path/'first/result.json')['state'])
        self.assertEqual(0,self.store('explicit-replay')['inserted'])
        self.assertEqual(before,stage.read_state(self.db))


@unittest.skipUnless(os.environ.get('METADATA_STAGE_MYSQL_TEST')=='true'
                     and os.environ.get('METADATA_STAGE_PUBLIC_COLLECTION'), 'Explicit saved-public fixture required')
class PublicMetadataStorageMysqlTest(unittest.TestCase):
    def test_pinned_collected_public_batch_in_owned_schema_only(self):
        bundle=stage.load_bundle(Path(os.environ['METADATA_STAGE_PUBLIC_COLLECTION']),
            '9cb6bff327f7c73403bbbf66ac65c81fa6fea9dcc724d580e827e49e22818954')
        admin=LocalCatalog()
        before=stage.read_state(admin)
        public_before=dry.read_consistent(admin)
        dry.check_catalog(dry.contract(),*public_before)
        # The saved-public rehearsal also runs after the real draft migration.
        # Existing application drafts must be preserved, not assumed absent.
        projected=stage.projection(bundle)
        self.assertEqual([1,84,413,334,196,20],[len(projected['rows'][t]) for t in stage.TABLES])
        output=ROOT/'target/metadata-storage-dry-run-20261126'/('public-rehearsal-'+uuid4().hex[:8])
        db=stage.OwnedScratch(admin)
        try:
            seed(db,bundle)
            first=stage.store_owned_scratch(db,bundle,output/'first')
            self.assertEqual(1048,first['inserted'])
            staged=stage.read_state(db)
            self.assertEqual(21,sum(r['runtime_minutes'] is not None or r['episode_runtime_minutes'] is not None for r in staged['catalog']))
            replay=stage.store_owned_scratch(db,bundle,output/'replay')
            self.assertEqual(0,replay['inserted'])
            self.assertEqual(staged,stage.read_state(db))
        finally:
            db.close()  # verifies that the exact freshly owned schema is absent
        after=stage.read_state(admin)
        public_after=dry.read_consistent(admin)
        self.assertEqual(before,after)
        self.assertEqual(public_before,public_after)
        write_json(output/'verification.json',{
            'state':'PASS','collection_sha256':projected['batch_sha'],
            'projection_sha256':projected['projection_sha256'],'counts':first['counts'],
            'first_inserted':1048,'replay_inserted':0,'application_public_state_unchanged':True,
            'application_metadata_tables_present':before['schema_present'],'application_db_writes':0,
            'scratch_schema_removed_and_verified':True,'known_durations_preserved':21,
            'runtime_candidates_unapproved':20,'provider_calls':0,'product_changes':0})


if __name__=='__main__': unittest.main()
