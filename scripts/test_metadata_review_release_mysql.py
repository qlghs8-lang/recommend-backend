"""Opt-in review-version persistence in newly UUID-owned schemas only; no provider calls."""
import copy
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import metadata_review_release as review
import metadata_storage_dry_run as stage
from catalog_pool import CatalogError, LocalCatalog
from test_metadata_review_release import approved, decisions, patch_context, review_fixture
from test_metadata_storage_mysql import seed


@unittest.skipUnless(os.environ.get('METADATA_STAGE_MYSQL_TEST')=='true','Owned-schema opt-in required')
class MetadataReviewReleaseMysqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.bundle,cls.contract=review_fixture()

    def setUp(self):
        self.context=patch_context(self,copy.deepcopy(self.bundle),self.contract)
        self.db=stage.OwnedScratch(LocalCatalog());self.addCleanup(self.db.close)
        seed(self.db,self.context['bundle'])
        folder=review.ROOT/'target/metadata-review-mysql-tmp';folder.mkdir(parents=True,exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(dir=folder);self.addCleanup(self.temp.cleanup)
        stage.store_owned_scratch(self.db,self.context['bundle'],Path(self.temp.name)/'raw')
        self.raw=stage.read_state(self.db)
        self.decisions=decisions(self.context,[approved(self.context,k) for k in ['KEYWORD','ALIAS','RUNTIME']])

    def store(self, value=None):return review.store_owned_scratch(self.db,self.context,value or self.decisions)

    def assert_empty(self):self.assertEqual([[],[],[]],review.read_reviews(self.db))

    def test_inactive_three_kind_candidate_replay_preserves_all_raw_and_public_values(self):
        first=self.store();self.assertEqual(5,first['inserted'])
        before=review.read_reviews(self.db)
        replay=self.store();self.assertEqual('REPLAY_UNCHANGED',replay['state']);self.assertEqual(0,replay['inserted'])
        self.assertEqual(before,review.read_reviews(self.db));self.assertEqual(self.raw,stage.read_state(self.db))


        self.assertEqual('77\t77',self.db.run('SELECT MIN(view_count),MAX(view_count) FROM contents;').strip())
        self.assertEqual('preserve-existing-provenance',self.db.run('SELECT sentinel FROM catalog_runtime_enrichments;').strip())
        self.assertEqual('0',self.db.run(f'SELECT SUM(active) FROM {review.TABLES[2]};').strip())
        with self.assertRaises(CatalogError):self.db.run(f'UPDATE {review.TABLES[2]} SET active=1;')

    def test_new_revision_and_parent_keep_previous_records_and_timestamps(self):
        first=self.store();before=review.read_reviews(self.db)
        second=self.store(self.decisions|{'revision':'review-002','parent_version_sha':first['version_sha']})
        self.assertNotEqual(first['version_sha'],second['version_sha'])
        after=review.read_reviews(self.db)
        self.assertEqual([2,6,2],[len(r) for r in after])
        for index,rows in enumerate(before):self.assertTrue(all(r in after[index] for r in rows))
        self.assertEqual(self.raw,stage.read_state(self.db))

    def test_unreviewed_results_store_a_review_version_but_no_release(self):
        self.store(review.template(self.context))
        rows=review.read_reviews(self.db)
        self.assertEqual(1,len(rows[0]));self.assertEqual([],rows[2])
        self.assertTrue(all(r['payload']['decision']=='UNREVIEWED' for r in rows[1]))

    def test_failure_after_records_rolls_back_all_review_data(self):
        original=review.scratch_transaction
        def broken(*args):return original(*args).replace('-- AFTER_REVIEW_1','INSERT INTO metadata_gate VALUES(1);\n-- AFTER_REVIEW_1',1)
        with patch.object(review,'scratch_transaction',side_effect=broken),self.assertRaises(CatalogError):self.store()
        self.assert_empty();self.assertEqual(self.raw,stage.read_state(self.db))
        self.assertEqual(5,self.store()['inserted'])

    def test_lost_commit_acknowledgement_requires_explicit_identical_replay(self):
        original=self.db.run;lost=False
        def lose(sql):
            nonlocal lost
            value=original(sql)
            if '__REVIEW_CANDIDATE_COMMITTED__' in sql and not lost:lost=True;return ''
            return value
        with patch.object(self.db,'run',side_effect=lose),self.assertRaises(CatalogError):self.store()
        self.assertTrue(lost);before=review.read_reviews(self.db)
        self.assertEqual(0,self.store()['inserted']);self.assertEqual(before,review.read_reviews(self.db))

    def test_raw_drift_after_preflight_is_rejected_by_transaction_guards(self):
        original=self.db.run;raced=False
        def race(sql):
            nonlocal raced
            if '-- DRAFT_METADATA_TRANSACTION' in sql and not raced:
                raced=True;original("UPDATE contents SET genres='DRAMA' WHERE id=1;")
            return original(sql)
        with patch.object(self.db,'run',side_effect=race),self.assertRaises(CatalogError):self.store()
        self.assertTrue(raced);self.assert_empty()

    def test_missing_parent_partial_version_and_partial_schema_are_not_repaired(self):
        with self.assertRaises(CatalogError):self.store(self.decisions|{'parent_version_sha':'a'*64})
        self.assert_empty();self.store()
        self.db.run(f'DELETE FROM {review.TABLES[1]} LIMIT 1;');before=review.read_reviews(self.db)
        with self.assertRaises(CatalogError):self.store()
        self.assertEqual(before,review.read_reviews(self.db))
        self.db.run(f'DROP TABLE {review.TABLES[2]};')
        with self.assertRaises(CatalogError):self.store()
        self.assertEqual('0',self.db.run(f"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{review.TABLES[2]}';").strip())

    def test_literal_unicode_and_sql_looking_text_remains_inert_data(self):
        value=copy.deepcopy(self.decisions)
        text="한국어 雪 😀 '); DROP TABLE contents; -- \\ \""
        value['records'][0]['rationale']=text
        value['records'][0]['value']['label_ko']=text
        self.store(value)
        stored=review.read_reviews(self.db)
        self.assertIn(text,[r['payload']['rationale'] for r in stored[1]])
        self.assertEqual(self.raw,stage.read_state(self.db))

    def test_tampered_prior_review_is_rejected_before_new_revision(self):
        first=self.store()
        self.db.run(f"UPDATE {review.TABLES[0]} SET payload=JSON_SET(payload,'$.revision','tampered');")
        before=review.read_reviews(self.db)
        with self.assertRaises(CatalogError):self.store(self.decisions|{'revision':'review-002','parent_version_sha':first['version_sha']})
        self.assertEqual(before,review.read_reviews(self.db));self.assertEqual(self.raw,stage.read_state(self.db))


@unittest.skipUnless(os.environ.get('METADATA_STAGE_MYSQL_TEST')=='true' and os.environ.get('METADATA_REVIEW_PUBLIC_COLLECTION'),
                     'Pinned public collection and owned-schema opt-in required')
class MetadataReviewReleasePublicMysqlTest(unittest.TestCase):
    def test_actual_unreviewed_batch_never_creates_a_release(self):
        context=review.load_context(Path(os.environ['METADATA_REVIEW_PUBLIC_COLLECTION']))
        db=stage.OwnedScratch(LocalCatalog());self.addCleanup(db.close)
        seed(db,context['bundle'])
        folder=review.ROOT/'target/metadata-review-mysql-tmp';folder.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as name:
            stage.store_owned_scratch(db,context['bundle'],Path(name)/'raw')
            before=stage.read_state(db)
            self.assertEqual(1048,sum(len(v) for v in before['metadata'].values()))
            value=review.template(context);self.assertEqual(767,len(value['records']))
            first=review.store_owned_scratch(db,context,value);self.assertEqual(768,first['inserted'])
            saved=review.read_reviews(db);self.assertEqual([1,767,0],[len(v) for v in saved])
            self.assertEqual(0,review.store_owned_scratch(db,context,value)['inserted'])
            self.assertEqual(saved,review.read_reviews(db));self.assertEqual(before,stage.read_state(db))
            self.assertEqual(21,sum(r['runtime_minutes'] is not None or r['episode_runtime_minutes'] is not None for r in before['catalog']))


if __name__=='__main__':unittest.main()
