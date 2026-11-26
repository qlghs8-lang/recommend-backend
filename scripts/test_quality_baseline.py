import unittest

from prepare_quality_baseline import read, SQL
from summarize_quality_baseline import latency, summarize
from catalog_pool import CatalogError


class BaselineTest(unittest.TestCase):
    def test_partial_results_and_live_runs_cannot_be_reported_as_offline_baseline(self):
        with self.assertRaises(ValueError):
            summarize({'mode': 'LIVE_MODEL'})
        with self.assertRaises(ValueError):
            summarize({'mode': 'PUBLIC_H2_REPLAY_NO_MODEL', 'results': []})

    def test_nearest_rank_latency_preserves_small_sample_max(self):
        self.assertEqual(20, latency([1000 * i for i in range(1, 21)])['samples'])
        self.assertEqual(19, latency([1000 * i for i in range(1, 21)])['p95_ms'])
        self.assertIsNone(latency([])['p95_ms'])

    def test_unsupported_schema_stops_before_reading_catalog_rows(self):
        class Fake:
            calls = 0
            def run(self, query):
                self.calls += 1
                return '{"table":"contents","engine":"MyISAM"}\n__COLUMNS__\n'
        db = Fake()
        with self.assertRaises(CatalogError):
            read(db)
        self.assertEqual(1, db.calls)
        self.assertIn('READ ONLY', SQL)
        self.assertIn('LIMIT 5001', SQL)
        self.assertNotIn('users', SQL)


if __name__ == '__main__':
    unittest.main()
