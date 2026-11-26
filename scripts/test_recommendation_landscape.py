import unittest

from audit_recommendation_landscape import analyze, boundary, read_snapshot, SNAPSHOT_SQL
from catalog_pool import CatalogError


class LandscapeTest(unittest.TestCase):
    def row(self, ident, overview, **kwargs):
        return dict(id=ident, type='MOVIE', title='가상 작품', overview=overview, genres='drama',
                    release_date=None, runtime_minutes=None, episode_runtime_minutes=None, **kwargs)

    def test_false_positive_is_not_topic_evidence(self):
        result = analyze({'rows': [self.row(1, '모조리 사라진다')], 'columns': []})['probes']['cooking']
        self.assertEqual([1], result['substring_visible_ids'])
        self.assertEqual([], result['boundary_visible_ids'])
        self.assertEqual('NOT_MEASURED', result['semantic_coverage'])

    def test_full_plot_and_visible_plot_are_separate(self):
        result = analyze({'rows': [self.row(1, '가' * 401 + ' 요리한다')], 'columns': []})
        self.assertEqual([1], result['probes']['cooking']['full_only_ids'])
        self.assertEqual(1, result['overview_over_400'])

    def test_unknown_and_invalid_duration_are_not_known(self):
        rows = [self.row(1, ''), self.row(2, '')]
        rows[1]['runtime_minutes'] = 0
        counts = analyze({'rows': rows, 'columns': []})['metadata_by_type']['MOVIE']
        self.assertEqual((0, 1, 1), (counts['valid_time'], counts['missing_time'], counts['invalid_time']))

    def test_compound_word_limit_is_explicit(self):
        self.assertFalse(boundary('프랑스요리', '요리'))
        self.assertTrue(boundary('요리한다', '요리'))

    def test_sql_is_bounded_public_and_read_only(self):
        self.assertIn('READ ONLY', SNAPSHOT_SQL)
        self.assertIn('LIMIT 5001', SNAPSHOT_SQL)
        self.assertIn('ROLLBACK;', SNAPSHOT_SQL)
        self.assertNotIn('users', SNAPSHOT_SQL)

    def test_missing_schema_refuses_data_read(self):
        class Fake:
            calls = 0
            def run(self, sql):
                self.calls += 1
                return '{"table":"contents","engine":"InnoDB"}\n__COLUMNS__\n'
        db = Fake()
        with self.assertRaises(CatalogError):
            read_snapshot(db)
        self.assertEqual(1, db.calls)


if __name__ == '__main__':
    unittest.main()
