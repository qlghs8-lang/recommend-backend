import copy
import unittest
from unittest.mock import patch

import audit_metadata_enrichment as subject
from audit_recommendation_landscape import FIELDS
from catalog_pool import CatalogError


def fixtures(count=40):
    rows = [dict(id=i, type='MOVIE' if i % 2 else 'TV', title='가상 작품 ' + str(i),
                 overview=('가' * 401 + ' 가족 우주 음악') if i % 3 else '병원 이야기', genres='drama',
                 release_date='2020-01-01', runtime_minutes=None, episode_runtime_minutes=None)
            for i in range(1, count + 1)]
    columns = [{'table': 'contents', 'name': f, 'data_type': 'int' if 'minutes' in f or f == 'id' else 'varchar',
                'nullable': 'YES'} for f in ('id', 'type', 'runtime_minutes', 'episode_runtime_minutes')]
    columns += [{'table': 'catalog_import_sources', 'name': f, 'data_type': 'bigint', 'nullable': 'NO'}
                for f in ('media_type', 'tmdb_id', 'content_id')]
    mappings = {'schema': {'tables': [{'table': t, 'engine': 'InnoDB'} for t in ('contents', 'catalog_import_sources')],
                           'columns': columns},
                'contents': [{f: r[f] for f in ('id', 'type', 'runtime_minutes', 'episode_runtime_minutes')} for r in rows],
                'sources': [{'content_id': r['id'], 'media_type': r['type'], 'tmdb_id': r['id'] + 100} for r in rows]}
    return {'rows': rows, 'columns': [{'name': f, 'type': 'text'} for f in FIELDS]}, mappings


class MetadataAuditTest(unittest.TestCase):
    def test_pilot_is_bounded_existing_mapped_and_unapplied(self):
        public, mappings = fixtures()
        audit, plan = subject.propose(public, mappings, 40)
        self.assertEqual(28, len(plan['pilot']))
        self.assertEqual(14, sum(r['media_type'] == 'MOVIE' for r in plan['pilot']))
        self.assertEqual(84, plan['proposed_limits']['http_attempts_max'])
        self.assertEqual([], plan['updates'])
        self.assertEqual([], plan['inserts'])
        self.assertEqual(0, audit['tmdb_data_api_calls'])
        self.assertEqual('UNJUDGED', audit['semantic_coverage'])
        for row in plan['pilot']:
            self.assertTrue(all(str(row['tmdb_id']) in path for path in row['proposed_requests']))
            self.assertEqual('NOT_COLLECTED', row['review_status'])

    def test_public_order_does_not_change_plan(self):
        public, mappings = fixtures()
        before = subject.propose(public, mappings, 40)
        public['rows'].reverse()
        public['columns'].reverse()
        self.assertEqual(before, subject.propose(public, mappings, 40))

    def test_unicode_visible_plot_uses_codepoints(self):
        public, mappings = fixtures()
        public['rows'][0]['overview'] = '😀' * 400 + ' 요리'
        audit, _ = subject.propose(public, mappings, 40)
        self.assertIn(1, audit['lexical_discovery']['cooking']['full_only_ids'])
        self.assertIn(1, audit['overview_over_400_codepoints_ids'])
        self.assertEqual([], audit['empty_overview_ids'])

    def test_snapshot_identity_and_field_mismatches_block(self):
        for mutation in ('duplicate', 'type', 'time', 'text', 'count', 'private_field'):
            with self.subTest(mutation=mutation):
                public, mappings = fixtures()
                if mutation == 'duplicate': public['rows'][1]['id'] = 1
                if mutation == 'type': mappings['sources'][0]['media_type'] = 'TV'
                if mutation == 'time': mappings['contents'][0]['runtime_minutes'] = 100
                if mutation == 'text': public['rows'][0]['overview'] = None
                if mutation == 'count': public['rows'].pop()
                if mutation == 'private_field': public['rows'][0]['email'] = 'synthetic'
                with self.assertRaises(CatalogError): subject.propose(public, mappings, 40)

    def test_invalid_time_and_missing_duplicate_mapping_block(self):
        for mutation in ('boolean', 'negative', 'missing', 'duplicate'):
            with self.subTest(mutation=mutation):
                public, mappings = fixtures()
                if mutation in ('boolean', 'negative'):
                    public['rows'][0]['runtime_minutes'] = True if mutation == 'boolean' else -1
                    mappings['contents'][0]['runtime_minutes'] = public['rows'][0]['runtime_minutes']
                if mutation == 'missing': mappings['sources'].pop()
                if mutation == 'duplicate': mappings['sources'].append(copy.deepcopy(mappings['sources'][0]))
                with self.assertRaises(CatalogError): subject.propose(public, mappings, 40)

    def test_alias_normalization_preserves_ambiguity_and_suffixes(self):
        public, mappings = fixtures()
        public['rows'][0]['title'] = ' ＡＢＣ  '
        public['rows'][1]['title'] = 'abc'
        public['rows'][2]['title'] = 'abc 후속'
        audit, _ = subject.propose(public, mappings, 40)
        self.assertEqual([[1, 2]], audit['normalized_current_title_collisions'])

    def test_changed_live_snapshots_refuse_proposal(self):
        public, mappings = fixtures(468)
        changed = copy.deepcopy(public)
        changed['rows'][0]['title'] = '변경됨'
        with patch.object(subject, 'read_public', side_effect=[public, changed]), patch.object(subject, 'read_mappings', return_value=mappings):
            with self.assertRaises(CatalogError): subject.read_consistent(object())

    def test_stable_live_read_uses_only_read_adapters(self):
        public, mappings = fixtures(468)
        with patch.object(subject, 'read_public', return_value=public) as first, patch.object(subject, 'read_mappings', return_value=mappings) as second:
            self.assertEqual((public, mappings), subject.read_consistent(object()))
            self.assertEqual(2, first.call_count)
            self.assertEqual(2, second.call_count)


if __name__ == '__main__':
    unittest.main()
