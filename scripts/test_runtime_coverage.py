"""Offline contracts for the read-only runtime audit; no DB or TMDB required."""
import copy
import io
import json
import unittest
from unittest.mock import Mock, patch

import audit_runtime_coverage as audit
from catalog_pool import CatalogError


def fixture():
    return {'schema': {
        'tables': [{'table': t, 'engine': 'InnoDB'} for t in ('contents', 'catalog_import_sources')],
        'columns': [{'table': t, 'name': name, 'data_type': 'int', 'nullable': 'YES'}
                    for t, names in [('contents', sorted(audit.CONTENT_COLUMNS)),
                                     ('catalog_import_sources', sorted(audit.SOURCE_COLUMNS))]
                    for name in names]},
        'contents': [{'id': 1, 'type': 'MOVIE', 'runtime_minutes': None, 'episode_runtime_minutes': None},
                     {'id': 2, 'type': 'TV', 'runtime_minutes': None, 'episode_runtime_minutes': None}],
        'sources': [{'media_type': 'MOVIE', 'tmdb_id': 10, 'content_id': 1},
                    {'media_type': 'TV', 'tmdb_id': 10, 'content_id': 2}]}


def analyze(data):
    return audit.analyze(data, expected_count=2)


class RuntimeCoverageTest(unittest.TestCase):
    def test_separate_movie_tv_ids_and_null_only_fetch_scope_with_no_updates(self):
        report = analyze(fixture())
        self.assertEqual('READY_FOR_DETAIL_DRY_RUN', report['gate'])
        self.assertEqual(2, len(report['detail_fetch_candidates']))
        self.assertEqual('episode_runtime_minutes', report['detail_fetch_candidates'][1]['target_field'])
        self.assertEqual([], report['updates'])
        self.assertEqual(0, report['tmdb_data_api_calls'])
        self.assertEqual(0, report['catalog_writes'])

    def test_existing_valid_values_never_proposed_for_overwrite(self):
        data = fixture()
        for value in (1, 90, 1440):
            data['contents'][0]['runtime_minutes'] = value
            report = analyze(data)
            self.assertEqual(100, report['coverage']['MOVIE']['valid_percent'])
            self.assertEqual([2], [r['content_id'] for r in report['detail_fetch_candidates']])

    def test_invalid_values_are_reviewed_not_normalized_or_overwritten(self):
        for value in (0, -1, 1441, True, 1.5, '90'):
            with self.subTest(value=value):
                data = fixture()
                data['contents'][0]['runtime_minutes'] = value
                report = analyze(data)
                self.assertEqual(1, report['coverage']['MOVIE']['invalid'])
                self.assertEqual(1, len(report['manual_review']))
                self.assertEqual(1, report['potential_fetch_count'])

    def test_wrong_media_field_is_reviewed_and_not_repurposed(self):
        data = fixture()
        data['contents'][1]['runtime_minutes'] = 700
        report = analyze(data)
        self.assertEqual(1, report['coverage']['TV']['wrong_media_field_populated'])
        self.assertEqual([1], [r['content_id'] for r in report['detail_fetch_candidates']])

    def test_missing_column_is_unavailable_instead_of_null(self):
        data = fixture()
        data['schema']['columns'] = [c for c in data['schema']['columns'] if c['name'] != 'runtime_minutes']
        report = analyze(data)
        self.assertEqual(1, report['coverage']['MOVIE']['unavailable'])
        self.assertEqual(0, report['coverage']['MOVIE']['null'])
        self.assertIn('runtime_schema_unavailable', report['blockers'])
        self.assertEqual([], report['detail_fetch_candidates'])

    def test_unsupported_time_column_type_blocks_scope(self):
        data = fixture()
        next(c for c in data['schema']['columns'] if c['name'] == 'runtime_minutes')['data_type'] = 'varchar'
        self.assertIn('runtime_schema_unavailable', analyze(data)['blockers'])

    def test_missing_mapping_schema_blocks_and_never_guesses_ids(self):
        data = fixture()
        data['schema']['columns'] = [c for c in data['schema']['columns'] if c['table'] != 'catalog_import_sources']
        data['sources'] = []
        report = analyze(data)
        self.assertIn('source_mapping_schema_unavailable', report['blockers'])
        self.assertEqual(0, report['potential_fetch_count'])

    def test_missing_mapping_excludes_only_unmapped_row(self):
        data = fixture()
        data['sources'].pop()
        report = analyze(data)
        self.assertEqual(1, report['coverage']['TV']['unmapped'])
        self.assertEqual([1], [r['content_id'] for r in report['detail_fetch_candidates']])

    def test_wrong_type_or_orphan_blocks_batch(self):
        for change, issue in [({'media_type': 'TV'}, 'type_mismatch'),
                              ({'content_id': 999}, 'orphan_mapping')]:
            with self.subTest(issue=issue):
                data = fixture()
                data['sources'][0].update(change)
                report = analyze(data)
                self.assertEqual(1, report['mapping_issues'][issue])
                self.assertEqual([], report['detail_fetch_candidates'])

    def test_duplicate_content_or_external_mapping_blocks_batch(self):
        for content_id, tmdb_id, issue in [(1, 11, 'multiple_mappings_per_content'),
                                           (2, 10, 'duplicate_external_key')]:
            with self.subTest(issue=issue):
                data = fixture()
                data['sources'].append({'content_id': content_id, 'tmdb_id': tmdb_id, 'media_type': 'MOVIE'})
                self.assertEqual(1, analyze(data)['mapping_issues'][issue])

    def test_invalid_mapping_ids_or_media_block_scope(self):
        for change in [{'tmdb_id': 0}, {'tmdb_id': 2**31}, {'tmdb_id': True},
                       {'content_id': -1}, {'media_type': 'movie'}]:
            with self.subTest(change=change):
                data = fixture()
                data['sources'][0].update(change)
                self.assertEqual(1, analyze(data)['mapping_issues']['invalid_mapping'])

    def test_unknown_content_type_or_changed_count_blocks(self):
        data = fixture()
        data['contents'][0]['type'] = 'OTHER'
        self.assertIn('unknown_content_type', analyze(data)['blockers'])
        self.assertIn('catalog_count_changed', audit.analyze(fixture())['blockers'])

    def test_duplicate_invalid_content_ids_and_budget_are_rejected(self):
        for value in (2, True, 0):
            data = fixture()
            data['contents'][0]['id'] = value
            with self.assertRaises(CatalogError):
                analyze(data)
        data = fixture()
        data['sources'] *= 2501
        with self.assertRaises(CatalogError):
            analyze(data)

    def test_narrow_hash_covers_time_mapping_schema_and_is_order_independent(self):
        data = fixture()
        original = audit.snapshot_hash(data)
        reordered = copy.deepcopy(data)
        reordered['contents'].reverse()
        reordered['sources'].reverse()
        reordered['schema']['columns'].reverse()
        self.assertEqual(original, audit.snapshot_hash(reordered))
        for key, change in [('contents', {'runtime_minutes': 90}), ('sources', {'tmdb_id': 11})]:
            altered = copy.deepcopy(data)
            altered[key][0].update(change)
            self.assertNotEqual(original, audit.snapshot_hash(altered))

    def test_sql_is_read_only_bounded_and_contains_no_private_data(self):
        sql = audit.METADATA_SQL + audit.snapshot_sql(fixture()['schema'])
        self.assertIn('READ ONLY', sql)
        self.assertIn('ROLLBACK', sql)
        self.assertEqual(2, sql.count('LIMIT 5001'))
        self.assertIn('MAX_EXECUTION_TIME=8000', sql)
        for forbidden in ('INSERT ', 'UPDATE ', 'DELETE ', 'ALTER ', 'CREATE ', 'COMMIT',
                          'users', 'chat_messages', 'reactions', 'title', 'overview'):
            self.assertNotIn(forbidden, sql)

    def test_missing_column_is_not_selected_and_missing_source_is_not_queried(self):
        schema = fixture()['schema']
        schema['columns'] = [c for c in schema['columns'] if c['name'] != 'runtime_minutes'
                             and c['table'] != 'catalog_import_sources']
        sql = audit.snapshot_sql(schema)
        self.assertNotIn('`runtime_minutes`', sql)
        self.assertNotIn('FROM catalog_import_sources', sql)

    def test_snapshot_parser_and_row_limit(self):
        data = fixture()
        db = Mock()
        db.run.return_value = '\n'.join(json.dumps(r) for r in data['contents']) + '\n__SOURCES__\n' + '\n'.join(json.dumps(r) for r in data['sources']) + '\n'
        with patch.object(audit, 'read_schema', return_value=data['schema']):
            self.assertEqual(data, audit.read_snapshot(db))
            db.run.return_value = '\n'.join(json.dumps(data['contents'][0]) for _ in range(5001)) + '\n__SOURCES__\n'
            with self.assertRaises(CatalogError):
                audit.read_snapshot(db)

    def test_schema_missing_contents_or_non_innodb_is_rejected(self):
        for tables in ([], [{'table': 'contents', 'engine': 'MyISAM'}]):
            db = Mock()
            db.run.return_value = '\n'.join(json.dumps(r) for r in tables) + '\n__COLUMNS__\n'
            with self.assertRaises(CatalogError):
                audit.read_schema(db)

    def test_schema_change_and_malformed_output_do_not_produce_snapshot(self):
        data = fixture()
        db = Mock()
        db.run.return_value = '__SOURCES__\n'
        with patch.object(audit, 'read_schema', side_effect=[data['schema'], {}]):
            with self.assertRaises(CatalogError):
                audit.read_snapshot(db)
        db.run.return_value = 'unparseable\n__SOURCES__\n'
        with patch.object(audit, 'read_schema', return_value=data['schema']):
            with self.assertRaises(CatalogError):
                audit.read_snapshot(db)

    def test_cli_failure_is_sanitized_and_does_not_write_an_artifact(self):
        with patch.object(audit, 'LocalCatalog', side_effect=CatalogError('synthetic-private-value')), \
                patch.object(audit, 'write_json') as write, patch('sys.stderr', new_callable=io.StringIO) as error:
            self.assertEqual(1, audit.main([]))
            write.assert_not_called()
            self.assertNotIn('synthetic-private-value', error.getvalue())

    def test_two_matching_snapshots_succeed_without_provider_creation(self):
        with patch.object(audit, 'read_snapshot', side_effect=[fixture(), fixture()]), \
                patch('catalog_pool.TmdbClient', side_effect=AssertionError('No provider allowed')):
            report = audit.audit(Mock(), 2)
            self.assertTrue(report['repeated_snapshot_match'])

    def test_changed_snapshot_does_not_produce_plan(self):
        changed = fixture()
        changed['contents'][0]['runtime_minutes'] = 80
        with patch.object(audit, 'read_snapshot', side_effect=[fixture(), changed]):
            with self.assertRaises(CatalogError):
                audit.audit(Mock(), 2)


if __name__ == '__main__':
    unittest.main()
