"""Opt-in real MySQL atomicity tests. Own uniquely named scratch schema only."""
import json
import os
from pathlib import Path
import re
import unittest
from uuid import uuid4

import apply_runtime_enrichment as app
from audit_runtime_coverage import read_snapshot
from catalog_pool import CatalogError, LocalCatalog
from test_runtime_apply import reviewed


class Scratch:
    def __init__(self, db, schema):
        self.db, self.schema = db, schema
        self.before_transaction = None

    def run(self, sql):
        if 'RUNTIME_APPLY_TRANSACTION' in sql and self.before_transaction:
            callback, self.before_transaction = self.before_transaction, None
            callback()
        return self.db.run('USE `' + self.schema + '`;\n' + sql)


@unittest.skipUnless(os.environ.get('RUNTIME_APPLY_MYSQL_TEST') == 'true', 'Explicit synthetic MySQL opt-in required')
class RuntimeApplyMySqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.admin = LocalCatalog()
        cls.schema = 'recommend_runtime_apply_test_' + uuid4().hex[:12]
        if not re.fullmatch('recommend_runtime_apply_test_[a-f0-9]{12}', cls.schema):
            raise RuntimeError('Invalid scratch schema')
        exists = cls.admin.run("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='" + cls.schema + "';").strip()
        if exists != '0':
            raise RuntimeError('Scratch schema already exists; will not use or drop it')
        cls.admin.run('CREATE DATABASE `' + cls.schema + '` CHARACTER SET utf8mb4;')
        cls.created = True
        cls.db = Scratch(cls.admin, cls.schema)
        try:
            cls.db.run('CREATE TABLE contents(id BIGINT PRIMARY KEY,type VARCHAR(10) NOT NULL,runtime_minutes INT NULL,'
                       "episode_runtime_minutes INT NULL,title VARCHAR(100),view_count BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB;"
                       'CREATE TABLE catalog_import_sources(media_type VARCHAR(10) NOT NULL,tmdb_id BIGINT NOT NULL,'
                       'content_id BIGINT NOT NULL,fetched_at DATETIME(6) NOT NULL,PRIMARY KEY(media_type,tmdb_id),'
                       'UNIQUE KEY(content_id),FOREIGN KEY(content_id) REFERENCES contents(id) ON DELETE CASCADE) ENGINE=InnoDB;')
        except Exception:
            cls.tearDownClass()
            raise

    @classmethod
    def tearDownClass(cls):
        if getattr(cls, 'created', False):
            cls.admin.run('DROP DATABASE `' + cls.schema + '`;')
            cls.created = False
            count = cls.admin.run("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='" + cls.schema + "';").strip()
            if count != '0':
                raise RuntimeError('Scratch cleanup unverified')

    def setUp(self):
        self.db.before_transaction = None
        self.db.run('DROP TABLE IF EXISTS catalog_runtime_enrichments; DELETE FROM catalog_import_sources; DELETE FROM contents;'
                    "INSERT INTO contents VALUES(1,'MOVIE',NULL,NULL,'Unchanged movie',17),(2,'TV',NULL,NULL,'Unchanged TV',29);"
                    "INSERT INTO catalog_import_sources VALUES('MOVIE',10,1,'2020-01-01'),('TV',10,2,'2020-01-01');")
        self.review, _ = reviewed(read_snapshot(self.db))
        self.output = app.ROOT / 'target/runtime-apply/mysql-tests' / uuid4().hex

    def test_atomic_apply_and_noop_reexecution_preserve_unrelated_data(self):
        result = app.apply_review(self.db, self.review, self.output, 2)
        self.assertEqual(2, result['updated'])
        before = self.db.run('SELECT content_id,evidence,applied_at FROM catalog_runtime_enrichments ORDER BY content_id;')
        again = app.apply_review(self.db, self.review, self.output.with_name(self.output.name + '-again'), 2)
        self.assertEqual('ALREADY_APPLIED', again['status'])
        self.assertEqual(0, again['updated'])
        self.assertEqual(before, self.db.run('SELECT content_id,evidence,applied_at FROM catalog_runtime_enrichments ORDER BY content_id;'))
        self.assertEqual('Unchanged movie\t17\nUnchanged TV\t29\n', self.db.run('SELECT title,view_count FROM contents ORDER BY id;'))
        self.assertEqual('2020-01-01 00:00:00.000000\n2020-01-01 00:00:00.000000\n',
                         self.db.run('SELECT fetched_at FROM catalog_import_sources ORDER BY content_id;'))

    def test_existing_valid_value_is_never_overwritten_or_adopted(self):
        self.db.run('UPDATE contents SET runtime_minutes=77 WHERE id=1;')
        with self.assertRaises(CatalogError):
            app.apply_review(self.db, self.review, self.output, 2)
        self.assertEqual('77\n', self.db.run('SELECT runtime_minutes FROM contents WHERE id=1;'))
        self.assertFalse(app.schema_status(self.db))

    def test_changed_mapping_rejected_before_ddl(self):
        self.db.run("UPDATE catalog_import_sources SET tmdb_id=999 WHERE media_type='MOVIE';")
        with self.assertRaises(CatalogError):
            app.apply_review(self.db, self.review, self.output, 2)
        self.assertFalse(app.schema_status(self.db))

    def test_transaction_failure_rolls_back_both_times_and_provenance(self):
        def inject():
            self.db.run("CREATE TRIGGER runtime_abort BEFORE INSERT ON catalog_runtime_enrichments FOR EACH ROW "
                        "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic forced failure';")
        self.db.before_transaction = inject
        with self.assertRaises(CatalogError):
            app.apply_review(self.db, self.review, self.output, 2)
        self.assertEqual('0\n', self.db.run('SELECT COUNT(*) FROM contents WHERE runtime_minutes IS NOT NULL OR episode_runtime_minutes IS NOT NULL;'))
        self.assertEqual('0\n', self.db.run('SELECT COUNT(*) FROM catalog_runtime_enrichments;'))
        self.assertTrue(app.schema_status(self.db))  # DDL is deliberately separate.

    def test_time_race_after_preflight_is_caught_under_locks(self):
        self.db.before_transaction = lambda: self.db.run('UPDATE contents SET runtime_minutes=77 WHERE id=1;')
        with self.assertRaises(CatalogError):
            app.apply_review(self.db, self.review, self.output, 2)
        self.assertEqual('77\tNULL\nNULL\tNULL\n', self.db.run('SELECT runtime_minutes,episode_runtime_minutes FROM contents ORDER BY id;'))
        self.assertEqual('0\n', self.db.run('SELECT COUNT(*) FROM catalog_runtime_enrichments;'))

    def test_mapping_race_after_preflight_is_caught_under_locks(self):
        self.db.before_transaction = lambda: self.db.run("UPDATE catalog_import_sources SET tmdb_id=999 WHERE media_type='MOVIE';")
        with self.assertRaises(CatalogError):
            app.apply_review(self.db, self.review, self.output, 2)
        self.assertEqual('0\n', self.db.run('SELECT COUNT(*) FROM catalog_runtime_enrichments;'))
        self.assertEqual('0\n', self.db.run('SELECT COUNT(*) FROM contents WHERE runtime_minutes IS NOT NULL OR episode_runtime_minutes IS NOT NULL;'))

    def test_partial_or_conflicting_provenance_is_not_repaired(self):
        app.apply_review(self.db, self.review, self.output, 2)
        self.db.run('UPDATE contents SET runtime_minutes=NULL WHERE id=1;')
        with self.assertRaises(CatalogError):
            app.apply_review(self.db, self.review, self.output.with_name(self.output.name + '-partial'), 2)
        self.assertEqual('NULL\n', self.db.run('SELECT runtime_minutes FROM contents WHERE id=1;'))

    def test_incompatible_existing_table_is_preserved(self):
        self.db.run('CREATE TABLE catalog_runtime_enrichments(content_id BIGINT PRIMARY KEY) ENGINE=InnoDB;')
        with self.assertRaises(CatalogError):
            app.apply_review(self.db, self.review, self.output, 2)
        self.assertEqual('1\n', self.db.run("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='catalog_runtime_enrichments';"))


if __name__ == '__main__':
    unittest.main()
