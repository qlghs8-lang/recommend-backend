"""Offline tests: python -m unittest discover -s scripts -p test_catalog_pool.py -v"""
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
import catalog_pool as pool


def raw(external_id=1, **changes):
    result = {'id': external_id, 'title': '테스트 영화', 'original_title': 'Test Movie',
              'name': '테스트 시리즈', 'original_name': 'Test Show', 'adult': False,
              'overview': '서로 다른 사람들이 만나 사건을 해결하는 이야기를 담은 테스트 소개입니다.',
              'release_date': '2020-01-02', 'first_air_date': '2021-03-04',
              'genre_ids': [878, 28], 'vote_average': 8.2, 'vote_count': 500,
              'poster_path': '/poster.jpg', 'backdrop_path': '/backdrop.jpg'}
    result.update(changes)
    return result


def record(external_id=1, kind='MOVIE', **changes):
    row = pool.normalize(raw(external_id), kind, 28)
    row.update(changes)
    return row


def dataset(*rows):
    return {'version': 1, 'provider': 'TMDB', 'language': 'ko-KR', 'records': list(rows)}


def snapshot(*rows, sources=None):
    return {'digest': 'a' * 64, 'contents': list(rows), 'sources': sources or [], 'source_table_exists': False}


def existing(title='테스트 영화', kind='MOVIE', released='2020-01-02', genres='action'):
    row = {f: None for f in pool.FIELDS}
    row.update(id=7, title=title, type=kind, release_date=released, genres=genres, view_count=42)
    return row


class NormalizeTest(unittest.TestCase):
    def test_preserves_real_scores_images_and_legacy_genre_keys_without_fake_views(self):
        row = record()
        self.assertEqual('action,sci-fi', row['genres'])
        self.assertEqual(0, row['view_count'])
        self.assertEqual(500, row['rating_count'])
        self.assertEqual(8.2, row['rating'])
        self.assertEqual('https://image.tmdb.org/t/p/w500/poster.jpg', row['poster_url'])
        self.assertEqual('https://www.themoviedb.org/movie/1', row['source_url'])
        self.assertEqual(2000, len(pool.normalize(raw(overview='한국어' * 1000), 'MOVIE', 28)['overview']))

    def test_skips_incomplete_untranslated_adult_future_and_unsafe_metadata(self):
        for changes in [{'title': 'English only'}, {'overview': 'English overview is not Korean.'},
                        {'overview': '짧음'}, {'poster_path': None}, {'poster_path': '//evil.test/file.jpg'},
                        {'poster_path': '/../../secret.jpg'}, {'release_date': '2099-01-01'},
                        {'release_date': '2020-02-31'}, {'adult': True}, {'vote_average': float('nan')},
                        {'id': True}, {'id': 2**63}, {'vote_count': -1}, {'genre_ids': []}]:
            with self.subTest(changes=changes):
                self.assertIsNone(pool.normalize(raw(**changes), 'MOVIE', 28))

    def test_tv_combined_genres_validate_and_missing_backdrop_is_allowed(self):
        row = pool.normalize(raw(genre_ids=[10759, 10765, 10768], backdrop_path=None), 'TV', 10759)
        self.assertIsNone(row['backdrop_url'])
        self.assertEqual([row], pool.validate_dataset(dataset(row)))

    def test_revalidates_staged_files_instead_of_trusting_manual_edits(self):
        for change in [{'poster_url': 'https://evil.test/poster.jpg'}, {'view_count': 9999},
                       {'source_url': 'https://evil.test/1'}, {'genres': 'made-up'}, {'tmdb_id': 0}]:
            row = record(**change)
            with self.subTest(change=change), self.assertRaises(pool.CatalogError):
                pool.validate_dataset(dataset(row))
        with self.assertRaises(pool.CatalogError):
            pool.validate_dataset(dataset(record(), record()))
        for invalid in [None, [], {'version': 1, 'provider': 'TMDB', 'language': 'ko-KR', 'records': [None]}]:
            with self.assertRaises(pool.CatalogError):
                pool.validate_dataset(invalid)


class PlanTest(unittest.TestCase):
    def test_reaches_total_not_additional_target_and_balances_movie_tv(self):
        before = snapshot(existing())
        rows = [record(i, title='영화 ' + str(i), original_title='Movie ' + str(i)) for i in range(2, 8)]
        rows += [record(i, 'TV', title='시리즈 ' + str(i), original_title='Show ' + str(i)) for i in range(1, 8)]
        plan = pool.plan_import(dataset(*rows), before, 6)
        self.assertEqual(5, len(plan['additions']))
        self.assertEqual({'MOVIE': 3, 'TV': 3}, plan['after_types'])
        self.assertTrue(plan['target_reached'])
        self.assertEqual(42, before['contents'][0]['view_count'])

    def test_same_tmdb_number_in_movie_and_tv_is_distinct(self):
        plan = pool.plan_import(dataset(record(1), record(1, 'TV')), snapshot(), 2)
        self.assertEqual(2, len(plan['additions']))

    def test_skips_existing_translated_titles_and_mapped_ids_but_retains_remakes(self):
        before = snapshot(existing('Test Movie'), sources=[{'type': 'MOVIE', 'tmdb_id': 4, 'content_id': 7}])
        rows = [record(1), record(2, release_date='1990-01-01'), record(4)]
        plan = pool.plan_import(dataset(*rows), before, 4)
        self.assertEqual([2], [r['tmdb_id'] for r in plan['additions']])
        before['contents'][0]['release_date'] = None
        self.assertFalse(pool.plan_import(dataset(record(2)), before, 2)['target_reached'])

    def test_prefers_underrepresented_genres_and_preserves_oversized_existing_type(self):
        before = snapshot(existing(genres='action'))
        action = record(2, title='액션 작품', original_title='Action')
        comedy = record(3, title='코미디 작품', original_title='Comedy', genres='comedy', bucket='comedy')
        tv = record(4, 'TV')
        plan = pool.plan_import(dataset(action, comedy, tv), before, 3)
        self.assertEqual([3, 4], [r['tmdb_id'] for r in plan['additions']])
        bigger = snapshot(*[dict(existing(), id=i, title='기존 작품 ' + str(i)) for i in range(4)])
        self.assertEqual({'MOVIE': 4, 'TV': 1}, pool.plan_import(dataset(tv), bigger, 5)['after_types'])

    def test_reports_shortfall_without_filling_with_unrelated_or_duplicate_work(self):
        plan = pool.plan_import(dataset(record()), snapshot(), 500)
        self.assertFalse(plan['target_reached'])
        self.assertEqual(1, plan['after_total'])
        self.assertEqual([], pool.plan_import(dataset(record()), snapshot(existing()), 1)['additions'])

    def test_reexecution_skips_existing_provider_mapping(self):
        before = snapshot(existing('다른 표시 제목'), sources=[{'type': 'MOVIE', 'tmdb_id': 1, 'content_id': 7}])
        plan = pool.plan_import(dataset(record()), before, 2)
        self.assertEqual([], plan['additions'])
        self.assertEqual(1, plan['skipped']['already_imported'])


class NetworkAndApplyTest(unittest.TestCase):
    def test_backup_uses_supported_dump_arguments_and_keeps_a_hashed_catalog_dump(self):
        db = object.__new__(pool.LocalCatalog)
        db.args = ['--protocol=TCP', '--host=127.0.0.1', '--connect-timeout=5', '--user=test-user']
        db._env = {'MYSQL_PWD': 'synthetic-private-password'}
        def dump(command, **kwargs):
            if any(arg.startswith('--connect-timeout=') for arg in command):
                return subprocess.CompletedProcess(command, 1, b'', b'unsupported option')
            output = Path(next(arg.split('=', 1)[1] for arg in command if arg.startswith('--result-file=')))
            output.write_bytes(b'-- synthetic catalog backup\n')
            self.assertIn('--single-transaction', command)
            self.assertEqual(['myproject', 'contents'], command[-2:])
            self.assertNotIn('synthetic-private-password', ' '.join(command))
            return subprocess.CompletedProcess(command, 0, b'', b'')
        with tempfile.TemporaryDirectory() as folder, patch('catalog_pool.ROOT', Path(folder)), \
                patch('catalog_pool.subprocess.run', side_effect=dump):
            backup = db.backup(snapshot())
            manifest = pool.read_json(backup / 'manifest.json')
            self.assertEqual('a' * 64, manifest['before_digest'])
            self.assertEqual(pool.hashlib.sha256((backup / 'catalog.sql').read_bytes()).hexdigest(), manifest['dump_sha256'])

    def test_failed_backup_never_starts_import_sql(self):
        db = object.__new__(pool.LocalCatalog)
        db.snapshot = unittest.mock.Mock(return_value=snapshot())
        db.backup = unittest.mock.Mock(side_effect=pool.CatalogError('Catalog backup failed.'))
        db.run = unittest.mock.Mock()
        staged = dataset(record())
        with self.assertRaises(pool.CatalogError):
            db.apply(staged, pool.plan_import(staged, snapshot(), 1))
        db.run.assert_not_called()

    def test_request_budget_and_redirect_are_enforced_before_sending_a_token(self):
        with patch.dict(os.environ, {'TMDB_READ_ACCESS_TOKEN': 'synthetic-private-token'}):
            client = pool.TmdbClient()
        client.opener = unittest.mock.Mock()
        client.calls = 200
        with self.assertRaises(pool.CatalogError):
            client.get('/discover/movie')
        client.calls = 0
        client.started -= 301
        with self.assertRaises(pool.CatalogError):
            client.get('/discover/movie')
        client.opener.open.assert_not_called()
        self.assertIsNone(pool.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://evil.test'))

    def test_missing_token_stops_before_network_or_database(self):
        with patch.dict(os.environ, {}, clear=True), patch('catalog_pool.build_opener') as opener:
            with self.assertRaisesRegex(pool.CatalogError, 'TMDB_READ_ACCESS_TOKEN'):
                pool.TmdbClient()
            opener.assert_not_called()

    def test_rate_limit_is_sanitized_and_never_retried(self):
        with patch.dict(os.environ, {'TMDB_READ_ACCESS_TOKEN': 'synthetic-private-token'}):
            client = pool.TmdbClient()
        client.opener = unittest.mock.Mock()
        client.opener.open.side_effect = HTTPError('https://api.themoviedb.org/3/discover/movie', 429,
                                                  'synthetic-private-token', {}, io.BytesIO(b'private body'))
        with self.assertRaises(pool.CatalogError) as error:
            client.get('/discover/movie', language='ko-KR')
        self.assertNotIn('synthetic-private-token', str(error.exception))
        self.assertNotIn('private body', str(error.exception))
        self.assertEqual(1, client.opener.open.call_count)

    def test_collection_filters_and_deduplicates_per_type_with_bounded_requests(self):
        class Fake:
            def __init__(self): self.calls = []
            def get(self, endpoint, **params):
                self.calls.append((endpoint, params))
                if endpoint == '/configuration':
                    return {'images': {'secure_base_url': 'https://image.tmdb.org/t/p/',
                                       'poster_sizes': ['w500'], 'backdrop_sizes': ['w1280']}}
                return {'results': [raw(1), raw(1), raw(2, overview='English only')]}
        client = Fake()
        messages = []
        result = pool.collect(client, 1, progress=messages.append)
        self.assertEqual(2, len(result['records']))
        for endpoint, params in client.calls[1:]:
            self.assertEqual('ko-KR', params['language'])
            self.assertEqual('false', params['include_adult'])
            self.assertIn('vote_count.gte', params)
        self.assertLessEqual(len(client.calls), 200)
        self.assertTrue(messages[0].startswith('[수집 시작]'))
        self.assertTrue(any('[요청 2/200]' in message for message in messages))
        self.assertTrue(any('영화 1개 / TV 0개' in message for message in messages))
        self.assertTrue(messages[-1].startswith('[수집 완료] 후보 2개'))
        self.assertFalse(any('Authorization' in message or 'Bearer' in message or '테스트 영화' in message
                             for message in messages))
        pool.validate_dataset(result)

    def test_sql_uses_hex_values_and_atomic_gate_before_inserts(self):
        row = record(title="작품'; DROP TABLE users; --", original_title='Safe')
        sql = pool.import_sql(pool.plan_import(dataset(row), snapshot(), 1))
        self.assertNotIn('DROP TABLE users', sql)
        self.assertIn('FOR UPDATE', sql)
        self.assertIn('GET_LOCK', sql)
        self.assertIn('CHECK(ok=1)', sql)
        self.assertLess(sql.index("='" + 'a' * 64), sql.index('INSERT INTO contents'))
        self.assertLess(sql.index('INSERT INTO contents'), sql.index('COMMIT;'))
        self.assertNotIn('UPDATE contents', sql)
        self.assertNotIn('DELETE FROM', sql)

    def test_stale_plan_and_insufficient_pool_stop_before_backup_or_writes(self):
        db = object.__new__(pool.LocalCatalog)
        db.snapshot = unittest.mock.Mock(return_value=snapshot())
        db.backup = unittest.mock.Mock()
        db.run = unittest.mock.Mock()
        staged = dataset(record())
        reviewed = pool.plan_import(staged, snapshot(), 1)
        reviewed['before_digest'] = 'b' * 64
        with self.assertRaises(pool.CatalogError): db.apply(staged, reviewed)
        with self.assertRaises(pool.CatalogError): db.apply(staged, pool.plan_import(staged, snapshot(), 500))
        db.backup.assert_not_called()
        db.run.assert_not_called()

    def test_valid_existing_target_never_creates_schema_or_takes_backup(self):
        db = object.__new__(pool.LocalCatalog)
        db.snapshot = unittest.mock.Mock(return_value=snapshot(existing()))
        db.backup = unittest.mock.Mock()
        db.run = unittest.mock.Mock()
        staged = dataset(record())
        self.assertEqual({'added': 0, 'total': 1}, db.apply(staged, pool.plan_import(staged, snapshot(existing()), 1)))
        db.run.assert_not_called()
        db.backup.assert_not_called()


if __name__ == '__main__':
    unittest.main()
