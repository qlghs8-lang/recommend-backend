"""Synthetic/offline contracts only. Never load a provider key or contact DB/TMDB."""
import copy
from contextlib import redirect_stderr, redirect_stdout
from http.client import IncompleteRead
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import metadata_enrichment_dry_run as dry
from audit_metadata_enrichment import propose
from catalog_pool import CatalogError, digest, write_json
from test_metadata_enrichment_audit import fixtures


def payload(task):
    ident = task['tmdb_id']
    movie = task['media_type'] == 'MOVIE'
    if task['kind'] == 'detail':
        return {'id': ident, 'title' if movie else 'name': '합성 작품',
                'original_title' if movie else 'original_name': 'Synthetic title',
                'original_language': 'en', 'overview': '합성 가족의 이야기',
                'runtime' if movie else 'episode_run_time': 90 if movie else [45, 45]}
    if task['kind'] == 'keywords':
        return {'id': ident, 'keywords' if movie else 'results': [{'id': 7, 'name': 'family'}, {'id': 7, 'name': 'family'}]}
    return {'id': ident, 'titles' if movie else 'results': [
        {'iso_3166_1': 'KR', 'title': '합성 공통 별칭', 'type': ''},
        {'iso_3166_1': 'KR', 'title': '합성 공통  별칭', 'type': ''}]}


class FakeClient:
    def __init__(self): self.calls = 0

    def fetch(self, task):
        self.calls += 1
        body = payload(task)
        return {'fetched_at_utc': dry.now(), 'response_sha256': digest(body), 'data': dry.project(body, task)}


class FakeClock:
    def __init__(self): self.value = 0.; self.sleeps = []
    def __call__(self): return self.value
    def sleep(self, value): self.sleeps.append(value); self.value += value


class Response:
    def __init__(self, body, status=200, headers=None, clock=None, read_seconds=0):
        self.stream = io.BytesIO(body); self.status = status; self.headers = headers or {}
        self.closed = False; self.clock = clock; self.read_seconds = read_seconds; self.reads = 0
    def getheader(self, key, default=None): return self.headers.get(key, default)
    def read1(self, size):
        self.reads += 1
        if self.clock: self.clock.value += self.read_seconds
        return self.stream.read(size)
    def close(self): self.closed = True


class Connection:
    def __init__(self, response, failure=None):
        self.response = response; self.sock = Mock(); self.failure = failure; self.closed = False; self.requests = []
    def connect(self):
        if self.failure: raise self.failure
    def request(self, method, path, headers): self.requests.append((method, path, headers))
    def getresponse(self): return self.response
    def close(self): self.closed = True


class ScopeTest(unittest.TestCase):
    def test_pinned_contract_has_28_titles_and_84_allowlisted_paths(self):
        plan = dry.contract(); tasks = dry.tasks(plan)
        self.assertEqual(28, len({r['content_id'] for r in tasks}))
        self.assertEqual(84, len(tasks))
        self.assertEqual(42, sum(r['media_type'] == 'MOVIE' for r in tasks))
        self.assertTrue(all(r['endpoint'].startswith('/3/') and 'api_key' not in r['endpoint'] for r in tasks))
        altered = copy.deepcopy(plan); altered['pilot'][0]['proposed_requests'][0] = 'https://untrusted.invalid'
        with self.assertRaises(CatalogError): dry.tasks(altered)

    def test_source_array_roots_match_frozen_official_schema(self):
        evidence = dry.load_json(dry.ROOT / 'docs/evaluation/metadata-source-shapes-v1.json')
        self.assertEqual('https://developer.themoviedb.org/openapi/tmdb-api.json', evidence['source'])
        self.assertRegex(evidence['source_sha256'], '^[a-f0-9]{64}$')
        for task in dry.tasks(dry.contract()):
            media, ident = ('movie', 'movie_id') if task['media_type'] == 'MOVIE' else ('tv', 'series_id')
            path = '/3/' + media + '/{' + ident + '}' + ('' if task['kind'] == 'detail' else '/' + task['kind'])
            properties = evidence['endpoints'][path]['properties']
            source = payload(task)
            self.assertTrue(set(source) <= set(properties))
            if task['kind'] != 'detail':
                result = dry.project(source, task)
                self.assertEqual('array', properties[result['source_field']]['type'])


class ProjectionTest(unittest.TestCase):
    def setUp(self):
        all_tasks = dry.tasks(dry.contract())
        self.tasks = [next(t for t in all_tasks if t['media_type'] == media and t['kind'] == kind)
                      for media in ('MOVIE', 'TV') for kind in dry.KINDS]

    def test_all_six_shapes_round_trip_without_unknown_keys(self):
        for task in self.tasks:
            source = payload(task); source['private_instruction'] = 'synthetic secret sentinel'
            result = dry.project(source, task)
            dry.validate_projection(result, task)
            self.assertNotIn('sentinel', json.dumps(result))

    def test_missing_null_and_empty_array_remain_distinct(self):
        for task in self.tasks:
            if task['kind'] == 'detail': continue
            root = next(k for k in payload(task) if k != 'id')
            for state, value in [('MISSING', None), ('NULL', None), ('EMPTY', [])]:
                source = {'id': task['tmdb_id']}
                if state != 'MISSING': source[root] = value
                result = dry.project(source, task)
                self.assertEqual(state, result['state']); dry.validate_projection(result, task)

    def test_optional_detail_fields_distinguish_missing_null_empty(self):
        task = self.tasks[0]
        for state, value in [('MISSING', None), ('NULL', None), ('EMPTY', ''), ('VALUE', '줄거리')]:
            source = payload(task)
            if state == 'MISSING': source.pop('overview')
            else: source['overview'] = value
            result = dry.project(source, task)
            self.assertEqual(state, result['fields']['overview']['state']); dry.validate_projection(result, task)

    def test_identity_wrong_media_and_wrong_array_roots_are_rejected(self):
        for task in self.tasks:
            changes = [{'id': True}, {'id': task['tmdb_id'] + 1}, {'media_type': 'wrong'}]
            changes.append({'name' if task['media_type'] == 'MOVIE' else 'title': 'wrong'} if task['kind'] == 'detail'
                           else {'results' if task['media_type'] == 'MOVIE' else 'keywords': []})
            for change in changes:
                with self.subTest(task=task['kind'], change=change), self.assertRaises(dry.Stopped):
                    dry.project(payload(task) | change, task)

    def test_item_and_string_budgets_stop_instead_of_truncating(self):
        movie_detail, movie_keywords, movie_alias = self.tasks[:3]
        samples = [(movie_detail, 'overview', '가' * 20001),
                   (movie_keywords, 'keywords', [{'id': 1, 'name': 'a'}] * 201),
                   (movie_keywords, 'keywords', [{'id': 1, 'name': 'a' * 129}]),
                   (movie_alias, 'titles', [{'title': 'a', 'iso_3166_1': 'US', 'type': ''}] * 101),
                   (movie_alias, 'titles', [{'title': 'a' * 256}])]
        for task, field, value in samples:
            with self.subTest(field=field), self.assertRaises(dry.Stopped) as error:
                dry.project(payload(task) | {field: value}, task)
            self.assertEqual('FIELD_LIMIT', error.exception.code)

    def test_keyword_duplicates_preserved_but_conflicting_names_rejected(self):
        task = self.tasks[1]; source = payload(task)
        self.assertEqual(2, len(dry.project(source, task)['entries']))
        source['keywords'][1]['name'] = 'different'
        with self.assertRaises(dry.Stopped): dry.project(source, task)

    def test_bad_item_shapes_country_and_names_rejected(self):
        for task, field, values in [(self.tasks[1], 'keywords', [False, {}, {'id': True, 'name': 'a'}, {'id': 1, 'name': ''}]),
                                    (self.tasks[2], 'titles', [False, {}, {'title': 'a', 'iso_3166_1': 'korean'}, {'title': 'a', 'type': False}])]:
            for value in values:
                with self.subTest(value=value), self.assertRaises(dry.Stopped):
                    dry.project(payload(task) | {field: [value]}, task)

    def test_invalid_runtime_shape_is_a_field_issue_not_fake_null(self):
        for task in (self.tasks[0], self.tasks[3]):
            name = 'runtime' if task['media_type'] == 'MOVIE' else 'episode_run_time'
            result = dry.project(payload(task) | {name: {'untrusted': 'data'}}, task)
            self.assertEqual({'present': True, 'supported': False, 'value': None}, result['fields'][name])
            dry.validate_projection(result, task)
            self.assertEqual('UNSUPPORTED_VALUE_SHAPE', dry.duration_review(result['fields'][name], task['media_type'], None)['reason'])


class TransportTest(unittest.TestCase):
    def setUp(self): self.task = dry.tasks(dry.contract())[0]

    def client(self, response, clock=None, failure=None):
        self.clock = clock or FakeClock(); self.connection = Connection(response, failure)
        self.factory = Mock(return_value=self.connection)
        return dry.MetadataClient('SYNTHETIC_TOKEN', self.factory, self.clock, self.clock.sleep)

    def test_fixed_https_host_get_bearer_and_allowlisted_fields(self):
        source = payload(self.task); body = json.dumps(source).encode()
        response = Response(body, headers={'Content-Length': str(len(body))})
        client = self.client(response); result = client.fetch(self.task)
        self.factory.assert_called_once_with('api.themoviedb.org', timeout=10)
        self.assertEqual('GET', self.connection.requests[0][0])
        self.assertEqual(self.task['endpoint'], self.connection.requests[0][1])
        self.assertEqual('Bearer SYNTHETIC_TOKEN', self.connection.requests[0][2]['Authorization'])
        self.assertEqual(1, client.calls); self.assertTrue(response.closed and self.connection.closed)
        self.assertNotIn('SYNTHETIC_TOKEN', json.dumps(result))

    def test_stop_statuses_do_not_retry_and_always_close(self):
        for status, code in [(301, 'REDIRECT'), (307, 'REDIRECT'), (401, 'AUTH_ERROR'), (403, 'AUTH_ERROR'),
                             (429, 'RATE_LIMIT'), (500, 'SERVER_ERROR'), (503, 'SERVER_ERROR'), (404, 'HTTP_ERROR')]:
            with self.subTest(status=status):
                response = Response(b'synthetic server details must be withheld', status)
                client = self.client(response)
                with self.assertRaises(dry.Stopped) as error: client.fetch(self.task)
                self.assertEqual(code, error.exception.code); self.assertEqual(1, client.calls)
                self.assertNotIn('details', str(error.exception)); self.assertTrue(response.closed and self.connection.closed)

    def test_stream_and_content_length_budgets(self):
        for response in (Response(b'a', headers={'Content-Length': str(dry.MAX_BODY + 1)}), Response(b'a' * (dry.MAX_BODY + 1))):
            client = self.client(response)
            with self.assertRaises(dry.Stopped) as error: client.fetch(self.task)
            self.assertEqual('RESPONSE_TOO_LARGE', error.exception.code)
            self.assertTrue(response.closed)

    def test_compression_incomplete_and_duplicate_json_are_rejected(self):
        samples = [Response(b'{}', headers={'Content-Encoding': 'gzip'}),
                   Response(b'{}', headers={'Content-Length': '10'}),
                   Response(b'{"id":1,"id":1}'), Response(b'{"id":NaN}'), Response(b'not JSON')]
        for response in samples:
            client = self.client(response)
            with self.assertRaises(dry.Stopped) as error: client.fetch(self.task)
            self.assertEqual('INVALID_RESPONSE', error.exception.code)

    def test_read_deadline_updates_socket_timeout_and_stops(self):
        clock = FakeClock(); response = Response(json.dumps(payload(self.task)).encode(), clock=clock, read_seconds=6)
        client = self.client(response, clock)
        with self.assertRaises(dry.Stopped) as error: client.fetch(self.task)
        self.assertEqual('TIME_BUDGET', error.exception.code)
        timeouts = [c.args[0] for c in self.connection.sock.settimeout.call_args_list]
        self.assertIn(4, timeouts); self.assertTrue(response.closed)

    def test_global_and_request_budgets_stop_before_connection(self):
        for kind in ('wall', 'requests'):
            client = self.client(Response(b''))
            if kind == 'wall': self.clock.value = dry.MAX_SECONDS
            else: client.calls = dry.MAX_CALLS
            with self.assertRaises(dry.Stopped) as error: client.fetch(self.task)
            self.assertEqual('TIME_BUDGET' if kind == 'wall' else 'REQUEST_BUDGET', error.exception.code)
            self.factory.assert_not_called()

    def test_rate_spacing_is_at_least_one_second(self):
        body = json.dumps(payload(self.task)).encode(); clock = FakeClock()
        responses = [Response(body), Response(body)]
        connections = [Connection(r) for r in responses]
        factory = Mock(side_effect=connections)
        client = dry.MetadataClient('SYNTHETIC_TOKEN', factory, clock, clock.sleep)
        client.fetch(self.task); client.fetch(self.task)
        self.assertEqual([1], clock.sleeps); self.assertEqual(2, client.calls)

    def test_unplanned_endpoint_is_rejected_before_authentication(self):
        client = self.client(Response(b''))
        with self.assertRaises(CatalogError): client.fetch(self.task | {'endpoint': 'https://untrusted.invalid'})
        self.assertEqual(0, client.calls); self.factory.assert_not_called()

    def test_missing_or_unsafe_token_stops_without_connection(self):
        for token, code in [('', 'TOKEN_REQUIRED'), ('bad\nheader', 'AUTH_ERROR')]:
            factory = Mock()
            with self.assertRaises(dry.Stopped) as error: dry.MetadataClient(token, factory)
            self.assertEqual(code, error.exception.code); factory.assert_not_called()

    def test_connection_failures_are_sanitized(self):
        for failure in (OSError('synthetic sensitive sentinel'), IncompleteRead(b'private')):
            client = self.client(Response(b''), failure=failure)
            with self.assertRaises(dry.Stopped) as error: client.fetch(self.task)
            self.assertEqual('NETWORK_ERROR', error.exception.code); self.assertNotIn('sentinel', str(error.exception))
            self.assertTrue(self.connection.closed)


class CollectionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.public, cls.mappings = fixtures(468)
        cls.plan = propose(cls.public, cls.mappings)[1]

    def setUp(self):
        self.pin = patch.object(dry, 'CONTRACT_SHA', digest(self.plan)); self.pin.start(); self.addCleanup(self.pin.stop)
        self.baseline = (copy.deepcopy(self.public), copy.deepcopy(self.mappings))

    def run_fake(self, factory=FakeClient, reader=None, source='SYNTHETIC_OFFLINE'):
        return dry.run(self.plan, reader or (lambda: self.baseline), True, factory, source)

    def test_prepare_never_initializes_client_or_reads_token(self):
        factory = Mock(side_effect=AssertionError('must not initialize'))
        with patch.object(dry.os.environ, 'get', side_effect=AssertionError('must not read a key')):
            saved, baseline, current = dry.run(self.plan, lambda: self.baseline, False, factory)
        factory.assert_not_called(); dry.validate_collection(saved, self.plan)
        self.assertEqual(0, saved['api_calls']); self.assertEqual('PREPARED', saved['state'])

    def test_complete_rehearsal_has_provenance_and_stays_blocked(self):
        saved, baseline, current = self.run_fake(); dry.validate_collection(saved, self.plan)
        result = dry.review(saved, self.plan, baseline, current)
        self.assertEqual(84, saved['api_calls']); self.assertEqual({'RECEIVED': 84}, result['status_counts'])
        self.assertEqual('BLOCKED', result['gate']); self.assertIn('NOT_LIVE_TMDB_EVIDENCE', result['blockers'])
        self.assertIn('CURRENT_DB_NOT_CHECKED', result['blockers']); self.assertFalse(result['apply_supported'])
        self.assertEqual([], result['updates']); self.assertEqual([], result['inserts'])
        self.assertEqual(result, dry.review(saved, self.plan, baseline, current))

    def test_live_gate_contract_with_fakes_still_never_approves_fields(self):
        saved, baseline, current = self.run_fake(source='TMDB_HTTP')
        result = dry.review(saved, self.plan, baseline, current, True)
        self.assertEqual('READY_FOR_MANUAL_REVIEW', result['gate'])
        self.assertTrue(all(not item['approved'] for item in result['items']))
        self.assertEqual([], result['updates'])

    def test_midway_error_and_remaining_not_run_are_preserved(self):
        class Partial(FakeClient):
            def fetch(self, task):
                if self.calls == 3:
                    self.calls += 1; raise dry.Stopped('RATE_LIMIT')
                return super().fetch(task)
        saved, baseline, current = self.run_fake(Partial); dry.validate_collection(saved, self.plan)
        result = dry.review(saved, self.plan, baseline, current, True)
        self.assertEqual({'RECEIVED': 3, 'ERROR': 1, 'NOT_RUN': 80}, result['status_counts'])
        self.assertEqual('ABORTED', saved['state']); self.assertEqual(4, saved['api_calls'])
        self.assertTrue(result['items'][0]['fields']); self.assertEqual({}, result['items'][4]['fields'])

    def test_pre_send_budget_and_missing_token_keep_zero_attempts(self):
        class NoBudget(FakeClient):
            def fetch(self, task): raise dry.Stopped('TIME_BUDGET')
        for factory, state in [(NoBudget, 'ABORTED'), (Mock(side_effect=dry.Stopped('TOKEN_REQUIRED')), 'TOKEN_REQUIRED')]:
            saved, _, _ = self.run_fake(factory); dry.validate_collection(saved, self.plan)
            self.assertEqual(state, saved['state']); self.assertEqual(0, saved['api_calls'])
            self.assertTrue(all(r['status'] == 'NOT_RUN' for r in saved['records']))

    def test_stale_precheck_stops_before_client(self):
        changed = copy.deepcopy(self.baseline); changed[0]['rows'][0]['overview'] += ' 변경'
        factory = Mock()
        with self.assertRaises(CatalogError): self.run_fake(factory, lambda: changed)
        factory.assert_not_called()

    def test_postcheck_change_or_failure_preserves_received_evidence(self):
        changed = copy.deepcopy(self.baseline); changed[1]['sources'][0]['tmdb_id'] += 1
        for after in (changed, CatalogError('synthetic failure')):
            reader = Mock(side_effect=[self.baseline, after])
            saved, baseline, current = self.run_fake(reader=reader, source='TMDB_HTTP')
            self.assertEqual('COMPLETE', saved['state']); self.assertEqual(84, saved['api_calls'])
            self.assertEqual('FAILED_OR_CHANGED', saved['postcheck']); self.assertIsNone(current)
            result = dry.review(saved, self.plan, baseline, current, True)
            self.assertEqual('BLOCKED', result['gate']); self.assertEqual(84, len(result['items']))

    def test_resealed_identity_state_count_and_projection_tampering_rejected(self):
        saved, _, _ = self.run_fake()
        def change(value, mutation):
            if mutation == 'path': value['records'][0]['endpoint'] += '&unexpected=1'
            if mutation == 'id_type': value['records'][0]['content_id'] = float(value['records'][0]['content_id'])
            if mutation == 'calls': value['api_calls'] = True
            if mutation == 'omitted_record': value['records'].pop()
            if mutation == 'out_of_order': value['records'].reverse()
            if mutation == 'future': value['records'][0]['fetched_at_utc'] = '2099-01-01T00:00:00+00:00'
            if mutation == 'bad_field': value['records'][0]['data']['fields']['overview']['state'] = 'MISSING'
            if mutation == 'bad_source': value['records'][0]['data']['fields']['runtime']['present'] = False
            if mutation == 'extra': value['records'][0]['data']['private'] = 'sentinel'
            if mutation == 'failure': value['state'] = 'ABORTED'; value['stop_code'] = 'RATE_LIMIT'
        for mutation in ('path','id_type','calls','omitted_record','out_of_order','future','bad_field','bad_source','extra','failure'):
            altered = copy.deepcopy(saved); change(altered, mutation); dry.seal(altered)
            with self.subTest(mutation=mutation), self.assertRaises((CatalogError, dry.Stopped)):
                dry.validate_collection(altered, self.plan)
        altered = copy.deepcopy(saved); altered['api_calls'] = 1
        with self.assertRaises(CatalogError): dry.validate_collection(altered, self.plan)

    def test_duplicate_counts_unknown_language_and_ambiguity_are_visible(self):
        saved, baseline, current = self.run_fake(); result = dry.review(saved, self.plan, baseline, current)
        keyword = result['items'][1]['fields']; aliases = result['items'][2]['fields']
        self.assertEqual((2, 1), (keyword['raw_count'], keyword['unique_keyword_count']))
        self.assertEqual('UNKNOWN', keyword['language']); self.assertEqual([], keyword['translated_labels'])
        self.assertEqual((2, 1), (aliases['raw_count'], aliases['unique_normalized_count']))
        self.assertIn(28, [len(r['content_ids']) for r in result['ambiguous_title_keys']])

    def test_review_html_escapes_untrusted_catalog_markup(self):
        saved, baseline, current = self.run_fake()
        saved['records'][0]['data']['fields']['overview']['value'] = '<script>alert("x")</script> 합성'
        dry.seal(saved)
        rendered = dry.render_review(dry.review(saved, self.plan, baseline, current))
        self.assertNotIn('<script>', rendered); self.assertIn('&lt;script&gt;', rendered)

    def test_long_or_unconfirmed_language_overviews_are_held(self):
        saved, baseline, current = self.run_fake()
        for value, expected in [('가' * 2001, 'HOLD_STORAGE_LIMIT'), ('English only', 'HOLD_LANGUAGE_UNCONFIRMED')]:
            saved['records'][0]['data']['fields']['overview']['value'] = value; dry.seal(saved)
            result = dry.review(saved, self.plan, baseline, current)
            self.assertEqual(expected, result['items'][0]['fields']['overview']['review_action'])

    def test_runtime_null_fill_conflict_and_invalid_tv_are_separate(self):
        for existing, value, expected in [(None,90,'PROPOSE_NULL_FILL'),(90,90,'UNCHANGED'),(80,90,'CONFLICT_PRESERVE_EXISTING')]:
            result = dry.duration_review({'present':True,'supported':True,'value':value}, 'MOVIE', existing)
            self.assertEqual(expected, result['state']); self.assertFalse(result['approved'])
        for value in ([], [45,60], [True], [0], [45.0]):
            self.assertEqual('SKIP', dry.duration_review({'present':True,'supported':True,'value':value}, 'TV', None)['state'])

    def test_offline_cli_prepare_plan_and_existing_output_guard(self):
        temp_root = dry.ROOT / 'target/metadata-enrichment-test-tmp'; temp_root.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temp_root) as folder:
            base = Path(folder); snapshot = base/'input'; out = base/'prepared'
            write_json(snapshot/'public-snapshot.json', self.baseline[0]); write_json(snapshot/'mapping-snapshot.json', self.baseline[1])
            with patch.object(dry, 'contract', return_value=self.plan), patch.object(dry, 'LocalCatalog', side_effect=AssertionError('no DB')), patch.object(dry, 'MetadataClient', side_effect=AssertionError('no provider')), redirect_stdout(io.StringIO()):
                self.assertEqual(0, dry.main(['prepare','--snapshot-dir',str(snapshot),'--output',str(out)]))
                replay = base/'replay'
                self.assertEqual(2, dry.main(['plan','--collection',str(out/'collection.json'),'--snapshot-dir',str(snapshot),'--output',str(replay)]))
                self.assertEqual((out/'review.json').read_bytes(),(replay/'review.json').read_bytes())
                with redirect_stderr(io.StringIO()): self.assertEqual(1, dry.main(['collect','--output',str(out)]))
                with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit): dry.main(['collect','--snapshot-dir',str(snapshot)])
            self.assertEqual(0, dry.load_json(out/'collection.json')['api_calls'])


if __name__ == '__main__':
    unittest.main()
