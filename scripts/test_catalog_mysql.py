"""Opt-in synthetic MySQL importer tests, never application catalog writes.

PowerShell: $env:CATALOG_MYSQL_TEST='true'; python -m unittest discover -s scripts -p test_catalog_mysql.py -v
Creates/drops only a new uniquely named recommend_catalog_import_test_* schema.
"""
import json
import os
import re
import unittest
import uuid
import catalog_pool as pool
from test_catalog_pool import dataset, record


@unittest.skipUnless(os.environ.get('CATALOG_MYSQL_TEST') == 'true', 'Explicit local MySQL opt-in required')
class MySqlImportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.db = pool.LocalCatalog()
        cls.schema = 'recommend_catalog_import_test_' + uuid.uuid4().hex[:12]
        if not re.fullmatch('recommend_catalog_import_test_[0-9a-f]{12}', cls.schema):
            raise RuntimeError('Unexpected scratch schema')
        cls.db.run(f'CREATE DATABASE `{cls.schema}` CHARACTER SET utf8mb4;')
        cls.created = True
        cls.db.run(f'CREATE TABLE `{cls.schema}`.contents LIKE myproject.contents;')

    @classmethod
    def tearDownClass(cls):
        if getattr(cls, 'created', False) and re.fullmatch('recommend_catalog_import_test_[0-9a-f]{12}', cls.schema):
            cls.db.run(f'DROP DATABASE `{cls.schema}`;')

    def run_sql(self, sql):
        return self.db.run(f'USE `{self.schema}`;\n' + sql)

    def setUp(self):
        self.run_sql("DELETE FROM contents; INSERT INTO contents(type,title,overview,genres,release_date,view_count) "
                     "VALUES('MOVIE','Legacy fixture','Existing overview','Action','2000-01-01',42);")
        self.staged = dataset(record(101, title='새 영화', original_title='New movie'),
                              record(102, 'TV', title='새 시리즈', original_title='New show'))

    def snapshot(self):
        exists = self.db.run("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA="
                             f"'{self.schema}' AND TABLE_NAME='{pool.SOURCE_TABLE}';").strip() == '1'
        sql = 'SET SESSION group_concat_max_len=2000000;\n'
        sql += 'SELECT ' + pool.db_digest_sql(exists) + ';\n'
        sql += 'SELECT ' + pool.ROW_JSON + ' FROM contents ORDER BY id;\n'
        sql += "SELECT '__SOURCES__';\n"
        if exists:
            sql += f'SELECT {pool.SOURCE_JSON} FROM {pool.SOURCE_TABLE} ORDER BY media_type,tmdb_id;'
        lines = self.run_sql(sql).splitlines()
        marker = lines.index('__SOURCES__')
        return {'digest': lines[0], 'contents': [json.loads(s) for s in lines[1:marker]],
                'sources': [json.loads(s) for s in lines[marker + 1:]], 'source_table_exists': exists}

    def test_real_insert_preserves_existing_rows_and_reexecution_adds_nothing(self):
        before = self.snapshot()
        plan = pool.plan_import(self.staged, before, 3)
        self.run_sql(pool.import_sql(plan))
        after = self.snapshot()
        self.assertEqual(3, len(after['contents']))
        self.assertEqual(before['contents'][0], after['contents'][0])
        self.assertEqual(2, len(after['sources']))
        self.assertEqual([], pool.plan_import(self.staged, after, 3)['additions'])
        self.assertTrue(all(row['view_count'] == 0 for row in after['contents'][1:]))

    def test_mapping_constraint_failure_rolls_back_entire_content_and_mapping_batch(self):
        before = self.snapshot()
        plan = pool.plan_import(self.staged, before, 3)
        plan['additions'].append(plan['additions'][0])
        with self.assertRaises(pool.CatalogError):
            self.run_sql(pool.import_sql(plan))
        after = self.snapshot()
        self.assertEqual(before['contents'], after['contents'])
        self.assertEqual([], after['sources'])

    def test_concurrent_catalog_change_is_rejected_before_any_import_row_is_written(self):
        plan = pool.plan_import(self.staged, self.snapshot(), 3)
        self.run_sql('UPDATE contents SET view_count=43;')
        with self.assertRaises(pool.CatalogError):
            self.run_sql(pool.import_sql(plan))
        after = self.snapshot()
        self.assertEqual(1, len(after['contents']))
        self.assertEqual(43, after['contents'][0]['view_count'])
        self.assertEqual([], after['sources'])


if __name__ == '__main__':
    unittest.main()
