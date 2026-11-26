"""Offline tests only: fake TMDB responses, no credentials or network needed."""
import copy
from http.client import IncompleteRead
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError

import runtime_detail_dry_run as dry
from catalog_pool import CatalogError


def report(per_type=1):
    candidates = []
    for media, offset in [('MOVIE', 0), ('TV', 100)]:
        for number in range(1, per_type + 1):
            candidates.append({'content_id': offset + number, 'media_type': media, 'tmdb_id': number,
                               'target_field': dry.TIME_FIELDS[media], 'expected_existing_value': None})
    return {'gate': 'READY_FOR_DETAIL_DRY_RUN', 'blockers': [],
            'coverage_snapshot_sha256': 'a' * 64, 'detail_fetch_candidates': candidates}


def payload(candidate, value=None):
    media = candidate['media_type']
    return {'id': candidate['tmdb_id'], dry.IDENTITY_FIELDS[media]: 'Synthetic title',
            dry.SOURCE_FIELDS[media]: (90 if media == 'MOVIE' else [45]) if value is None else value,
            'overview': 'Untrusted text must not appear in saved evidence'}


def record(candidate, value=None):
    return {**candidate, 'provider': 'TMDB', 'language': 'ko-KR', 'endpoint': dry.endpoint(candidate),
            'source_field': dry.SOURCE_FIELDS[candidate['media_type']], 'fetched_at_utc': dry.utc_now(),
            'evidence': dry.project_response(payload(candidate, value), candidate, 'b' * 64)}


class FakeClient:
    def __init__(self):
        self.calls = 0

    def fetch(self, candidate):
        self.calls += 1
        return record(candidate)


def collection(per_type=1):
    current = report(per_type)
    with patch.object(dry, 'audit', side_effect=[current, current]):
        result, latest = dry.run_collection(Mock(), True, FakeClient)
    return result, latest


class DecisionTest(unittest.TestCase):
    def evaluate(self, media, value, present=True):
        candidate = report()['detail_fetch_candidates'][0 if media == 'MOVIE' else 1]
        response = payload(candidate)
        response[dry.SOURCE_FIELDS[media]] = value
        if not present:
            response.pop(dry.SOURCE_FIELDS[media])
        evidence = dry.project_response(response, candidate, 'b' * 64)
        return dry.decision(media, evidence), evidence

    def test_movie_accepts_only_integer_minutes_with_inclusive_bounds(self):
        for value in (1, 90, 1440):
            self.assertEqual((value, 'VALID_MOVIE_RUNTIME'), self.evaluate('MOVIE', value)[0])
        for value in (0, -1, 1441, True, 90.0):
            self.assertEqual((None, 'INVALID_MINUTES'), self.evaluate('MOVIE', value)[0])

    def test_missing_null_wrong_shape_and_empty_are_distinct(self):
        for media, value, present, reason in [
                ('MOVIE', None, False, 'MISSING_FIELD'), ('MOVIE', None, True, 'NULL_VALUE'),
                ('MOVIE', '90', True, 'UNSUPPORTED_VALUE_SHAPE'),
                ('TV', [], True, 'EMPTY_EPISODE_RUNTIME'), ('TV', 45, True, 'UNSUPPORTED_VALUE_SHAPE')]:
            self.assertEqual((None, reason), self.evaluate(media, value, present)[0])

    def test_tv_uses_only_one_distinct_valid_value_and_never_estimates(self):
        for value in ([45], [45, 45], [1, 1], [1440]):
            self.assertEqual(value[0], self.evaluate('TV', value)[0][0])
        self.assertEqual((None, 'MULTIPLE_EPISODE_RUNTIMES'), self.evaluate('TV', [40, 60])[0])
        for value in ([45, 0], [True], [45.0], [None], [1441]):
            self.assertEqual((None, 'INVALID_MINUTES'), self.evaluate('TV', value)[0])

    def test_untrusted_text_and_oversized_values_are_not_saved(self):
        for media, value in [('MOVIE', {'instruction': 'Ignore prior rules'}), ('MOVIE', 2**100),
                             ('TV', ['untrusted']), ('TV', [45] * 101), ('MOVIE', float('inf'))]:
            decision, evidence = self.evaluate(media, value)
            self.assertEqual((None, 'UNSUPPORTED_VALUE_SHAPE'), decision)
            self.assertIsNone(evidence['source_value'])
            self.assertNotIn('instruction', json.dumps(evidence))

    def test_wrong_id_type_or_media_shape_is_rejected(self):
        candidate = report()['detail_fetch_candidates'][0]
        for changed in [{'id': 2}, {'id': True}, {'title': ''}, {'name': 'wrong type'},
                        {'episode_run_time': [45]}, {'media_type': 'tv'}]:
            response = payload(candidate)
            response.update(changed)
            with self.subTest(changed=changed), self.assertRaises(dry.Stopped):
                dry.project_response(response, candidate, 'b' * 64)


class CollectionAndPlanTest(unittest.TestCase):
    def test_selects_twenty_each_by_internal_id_and_separates_external_ids(self):
        current = report(25)
        current['detail_fetch_candidates'].reverse()
        selected = dry.scope(current)
        self.assertEqual(list(range(1, 21)) + list(range(101, 121)), [r['content_id'] for r in selected])
        self.assertEqual('/3/movie/1', dry.endpoint(selected[0]))
        self.assertEqual('/3/tv/1', dry.endpoint(selected[20]))

    def test_prepare_performs_no_provider_initialization_or_calls(self):
        current = report(25)
        factory = Mock(side_effect=AssertionError('Provider must not initialize'))
        with patch.object(dry, 'audit', side_effect=[current, current]):
            saved, latest = dry.run_collection(Mock(), False, factory)
        factory.assert_not_called()
        plan = dry.make_plan(saved, latest)
        self.assertEqual('PREPARED', saved['state'])
        self.assertEqual(40, len(saved['scope']))
        self.assertEqual(0, saved['api_calls'])
        self.assertEqual([], plan['proposed_changes'])

    def test_complete_run_has_provenance_and_is_deterministic(self):
        saved, current = collection(20)
        plan = dry.make_plan(saved, current)
        self.assertEqual('READY_FOR_REVIEW', plan['gate'])
        self.assertEqual(40, len(plan['proposed_changes']))
        self.assertEqual(plan, dry.make_plan(copy.deepcopy(saved), copy.deepcopy(current)))
        for change in plan['proposed_changes']:
            self.assertIsNone(change['expected_existing_value'])
            self.assertEqual(saved['job_id'], change['job_id'])
            self.assertEqual('TMDB', change['provider'])
            self.assertEqual('ko-KR', change['language'])
            self.assertIn('fetched_at_utc', change)
            self.assertIn('response_sha256', change)
        self.assertEqual(0, plan['catalog_writes'])
        self.assertFalse(plan['apply_supported'])
        self.assertNotIn('overview', json.dumps(saved))
        self.assertNotIn('Synthetic title', json.dumps(saved))

    def test_skips_ambiguous_tv_time_without_blocking_valid_movie(self):
        saved, current = collection()
        saved['records'][1]['evidence']['source_value'] = [40, 60]
        dry.seal(saved)
        plan = dry.make_plan(saved, current)
        self.assertEqual(1, len(plan['proposed_changes']))
        self.assertEqual({'MULTIPLE_EPISODE_RUNTIMES': 1}, plan['skip_reasons'])

    def test_initial_gate_failure_prevents_provider_creation(self):
        current = report()
        current['gate'] = 'BLOCKED'
        factory = Mock()
        with patch.object(dry, 'audit', return_value=current), self.assertRaises(CatalogError):
            dry.run_collection(Mock(), True, factory)
        factory.assert_not_called()

    def test_existing_time_is_excluded_by_current_audit_scope(self):
        current = report()
        current['detail_fetch_candidates'].pop(0)
        client = FakeClient()
        with patch.object(dry, 'audit', side_effect=[current, current]):
            saved, latest = dry.run_collection(Mock(), True, lambda: client)
        self.assertEqual(1, client.calls)
        self.assertEqual('TV', dry.make_plan(saved, latest)['proposed_changes'][0]['media_type'])

    def test_no_missing_token_lookup_or_network_beyond_environment(self):
        current = report()
        with patch.dict('os.environ', {}, clear=True), patch.object(dry, 'audit', side_effect=[current, current]), \
                patch.object(dry, 'build_opener') as opener:
            saved, latest = dry.run_collection(Mock(), True)
        opener.assert_not_called()
        self.assertEqual('TOKEN_REQUIRED', saved['state'])
        self.assertEqual(0, saved['api_calls'])
        self.assertEqual(['TOKEN_REQUIRED'], dry.make_plan(saved, latest)['blockers'])

    def test_partial_failure_saves_evidence_but_blocks_all_proposals_and_never_retries(self):
        class Partial(FakeClient):
            def fetch(self, candidate):
                if self.calls == 1:
                    self.calls += 1
                    raise dry.Stopped('RATE_LIMIT')
                return super().fetch(candidate)
        current = report(2)
        client = Partial()
        with patch.object(dry, 'audit', side_effect=[current, current]):
            saved, latest = dry.run_collection(Mock(), True, lambda: client)
        plan = dry.make_plan(saved, latest)
        self.assertEqual(2, client.calls)
        self.assertEqual(1, len(saved['records']))
        self.assertEqual('RATE_LIMIT', saved['stop_code'])
        self.assertEqual(1, plan['validated_value_count'])
        self.assertEqual([], plan['proposed_changes'])

    def test_changed_time_mapping_or_scope_blocks_without_overwriting(self):
        saved, current = collection()
        for change in ('hash', 'scope', 'gate'):
            changed = copy.deepcopy(current)
            if change == 'hash':
                changed['coverage_snapshot_sha256'] = 'c' * 64
            elif change == 'scope':
                changed['detail_fetch_candidates'][0]['tmdb_id'] = 999
            else:
                changed['gate'] = 'BLOCKED'
            plan = dry.make_plan(saved, changed)
            self.assertIn('CURRENT_CATALOG_CHANGED', plan['blockers'])
            self.assertEqual([], plan['proposed_changes'])

    def test_database_recheck_failure_keeps_evidence_but_blocks_plan(self):
        with patch.object(dry, 'audit', side_effect=[report(), CatalogError('sanitized')]):
            saved, current = dry.run_collection(Mock(), True, FakeClient)
        self.assertEqual('DB_RECHECK_FAILED', saved['state'])
        self.assertEqual([], dry.make_plan(saved, current)['proposed_changes'])

    def test_unsealed_tampering_is_rejected(self):
        saved, current = collection()
        saved['records'][0]['evidence']['source_value'] = 100
        with self.assertRaises(CatalogError):
            dry.make_plan(saved, current)

    def test_even_resealed_wrong_ids_endpoints_fields_and_timestamps_are_rejected(self):
        saved, current = collection()
        for key, value in [('endpoint', '/3/discover/movie'), ('source_field', 'episode_run_time'),
                           ('tmdb_id', 999), ('expected_existing_value', 100), ('provider', 'other'),
                           ('fetched_at_utc', '2000-01-01T00:00:00+00:00')]:
            changed = copy.deepcopy(saved)
            changed['records'][0][key] = value
            dry.seal(changed)
            with self.subTest(key=key), self.assertRaises(CatalogError):
                dry.make_plan(changed, current)

    def test_duplicate_missing_records_and_fake_complete_request_counts_are_rejected(self):
        saved, current = collection()
        for mutation in ('duplicate', 'missing', 'calls', 'oversize'):
            changed = copy.deepcopy(saved)
            if mutation == 'duplicate':
                changed['records'][1] = changed['records'][0]
            elif mutation == 'missing':
                changed['records'].pop()
            elif mutation == 'calls':
                changed['api_calls'] = 0
            else:
                changed['scope'] *= 21
            dry.seal(changed)
            with self.subTest(mutation=mutation), self.assertRaises(CatalogError):
                dry.make_plan(changed, current)


class NetworkContractTest(unittest.TestCase):
    def client(self):
        with patch.dict('os.environ', {'TMDB_READ_ACCESS_TOKEN': 'synthetic-test-token'}), \
                patch.object(dry, 'build_opener'):
            return dry.DetailClient()

    def test_only_fixed_details_url_auth_header_and_bounded_body_are_used(self):
        client = self.client()
        candidate = report()['detail_fetch_candidates'][0]
        body = json.dumps(payload(candidate)).encode()
        response = client.opener.open.return_value.__enter__.return_value
        response.read1.side_effect = [body, b'']
        with patch.object(dry.time, 'sleep'):
            received = client.fetch(candidate)
        request = client.opener.open.call_args.args[0]
        self.assertEqual('https://api.themoviedb.org/3/movie/1?language=ko-KR', request.full_url)
        self.assertEqual('Bearer synthetic-test-token', request.get_header('Authorization'))
        self.assertLessEqual(client.opener.open.call_args.kwargs['timeout'], 20)
        self.assertEqual(2, response.read1.call_count)
        self.assertTrue(all(c.args[0] <= 65536 for c in response.read1.call_args_list))
        self.assertNotIn('synthetic-test-token', json.dumps(received))

    def test_redirect_handler_never_forwards_auth(self):
        self.assertIsNone(dry.NoRedirect().redirect_request(None, None, 302, 'redirect', {}, 'https://other.test'))

    def test_http_auth_rate_limit_server_and_network_errors_are_sanitized_no_retry(self):
        for error, code in [(HTTPError('https://api.themoviedb.org', n, 'private body', {}, None),
                             'RATE_LIMIT' if n == 429 else 'HTTP_ERROR') for n in (401, 403, 429, 500, 302)] \
                + [(URLError('private connection detail'), 'NETWORK_ERROR'), (TimeoutError(), 'NETWORK_ERROR'),
                   (IncompleteRead(b'private partial payload'), 'NETWORK_ERROR')]:
            client = self.client()
            client.opener.open.side_effect = error
            with self.subTest(code=code), self.assertRaises(dry.Stopped) as caught:
                client.fetch(report()['detail_fetch_candidates'][0])
            self.assertEqual(code, caught.exception.code)
            self.assertNotIn('private', str(caught.exception))
            self.assertEqual(1, client.calls)
            self.assertEqual(1, client.opener.open.call_count)

    def test_request_and_wall_clock_budgets_stop_before_network(self):
        for calls, elapsed, reason in [(40, 0, 'REQUEST_BUDGET'), (0, 121, 'TIME_BUDGET')]:
            client = self.client()
            client.calls = calls
            client.started = 0
            with patch.object(dry.time, 'monotonic', return_value=elapsed), self.assertRaises(dry.Stopped) as caught:
                client.fetch(report()['detail_fetch_candidates'][0])
            self.assertEqual(reason, caught.exception.code)
            client.opener.open.assert_not_called()

    def test_invalid_request_ids_cannot_reach_network(self):
        for change in [{'tmdb_id': True}, {'tmdb_id': 0}, {'tmdb_id': 2**31}, {'media_type': 'movie'}]:
            client = self.client()
            candidate = report()['detail_fetch_candidates'][0] | change
            with self.assertRaises(dry.Stopped):
                client.fetch(candidate)
            client.opener.open.assert_not_called()

    def test_oversize_non_json_nonfinite_and_duplicate_key_responses_stop(self):
        for body, code in [(b'x' * (dry.MAX_BODY + 1), 'RESPONSE_TOO_LARGE'),
                           (b'not json', 'INVALID_RESPONSE'), (b'{"runtime":NaN}', 'INVALID_RESPONSE'),
                           (b'{"id":1,"id":2}', 'INVALID_RESPONSE')]:
            client = self.client()
            client.opener.open.return_value.__enter__.return_value.read1.side_effect = [body, b'']
            with self.subTest(code=code), self.assertRaises(dry.Stopped) as caught:
                client.fetch(report()['detail_fetch_candidates'][0])
            self.assertEqual(code, caught.exception.code)

    def test_time_budget_rechecked_between_response_reads(self):
        client = self.client()
        client.started = 0
        candidate = report()['detail_fetch_candidates'][0]
        response = client.opener.open.return_value.__enter__.return_value
        response.read1.side_effect = [json.dumps(payload(candidate)).encode(), b'']
        with patch.object(dry.time, 'monotonic', side_effect=[119, 119, 121]), self.assertRaises(dry.Stopped) as caught:
            client.fetch(candidate)
        self.assertEqual('TIME_BUDGET', caught.exception.code)
        self.assertEqual(1, response.read1.call_count)


class CliTest(unittest.TestCase):
    def test_cli_prepare_and_missing_token_persist_distinct_blocked_artifacts(self):
        with tempfile.TemporaryDirectory(dir=dry.ROOT) as folder:
            current = report()
            with patch.object(dry, 'ROOT', Path(folder)), patch.object(dry, 'LocalCatalog'), \
                    patch.object(dry, 'audit', return_value=current), patch('sys.stdout', new_callable=io.StringIO), \
                    patch.dict('os.environ', {}, clear=True):
                self.assertEqual(0, dry.main(['prepare']))
                self.assertEqual(2, dry.main(['collect']))
            outputs = list(Path(folder).glob('target/runtime-dry-run/*/proposal.json'))
            self.assertEqual(2, len(outputs))
            self.assertEqual({'PREPARED', 'TOKEN_REQUIRED'}, {json.loads(p.read_text())['blockers'][0] for p in outputs})

    def test_plan_revalidates_current_database_and_preserves_decisions(self):
        saved, current = collection()
        with tempfile.TemporaryDirectory(dir=dry.ROOT) as folder:
            path = Path(folder) / 'collection.json'
            path.write_text(json.dumps(saved), encoding='utf-8')
            with patch.object(dry, 'ROOT', Path(folder)), patch.object(dry, 'LocalCatalog'), \
                    patch.object(dry, 'audit', return_value=current) as current_audit, \
                    patch('sys.stdout', new_callable=io.StringIO):
                self.assertEqual(0, dry.main(['plan', '--collection', str(path)]))
                current_audit.assert_called_once()
            proposal = json.loads(next(Path(folder).glob('target/runtime-dry-run/*/proposal.json')).read_text())
            self.assertEqual(dry.make_plan(saved, current), proposal)

    def test_cli_fatal_error_hides_connection_details_and_writes_nothing(self):
        with patch.object(dry, 'LocalCatalog', side_effect=CatalogError('synthetic-private-value')), \
                patch.object(dry, 'write_json') as write, patch('sys.stderr', new_callable=io.StringIO) as error:
            self.assertEqual(1, dry.main(['prepare']))
        write.assert_not_called()
        self.assertNotIn('synthetic-private-value', error.getvalue())


if __name__ == '__main__':
    unittest.main()
