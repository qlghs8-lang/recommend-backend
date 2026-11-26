"""Bounded TMDB runtime proposals only; no database writes or apply command.

prepare: recheck local DB and save a scope without authentication/network.
collect: recheck DB, fetch up to 20 movies + 20 TV series, recheck and propose.
plan --collection PATH: revalidate saved evidence against the current local DB.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
from http.client import HTTPException
import json
import math
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener
from uuid import uuid4

from audit_runtime_coverage import audit, positive_id, valid_minutes, TIME_FIELDS
from catalog_pool import CatalogError, LocalCatalog, NoRedirect, ROOT, digest, write_json


SOURCE_FIELDS = {'MOVIE': 'runtime', 'TV': 'episode_run_time'}
IDENTITY_FIELDS = {'MOVIE': 'title', 'TV': 'name'}
POLICY = 'runtime-null-only-v1-tv-single-distinct-value'
MAX_CALLS, MAX_SECONDS, MAX_BODY = 40, 120, 4_000_000
STATES = {'PREPARED', 'TOKEN_REQUIRED', 'COMPLETE', 'ABORTED', 'DB_RECHECK_FAILED'}
STOP_CODES = {'TOKEN_REQUIRED', 'HTTP_ERROR', 'RATE_LIMIT', 'NETWORK_ERROR', 'RESPONSE_TOO_LARGE',
              'INVALID_RESPONSE', 'IDENTITY_MISMATCH', 'REQUEST_BUDGET', 'TIME_BUDGET', 'DB_RECHECK_FAILED'}


class Stopped(CatalogError):
    def __init__(self, code):
        self.code = code
        super().__init__('Runtime collection stopped: ' + code)


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def reject_constant(_):
    raise ValueError('Non-finite JSON number')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key')
        result[key] = value
    return result


def strict_json(value):
    return json.loads(value, parse_constant=reject_constant, object_pairs_hook=unique_object)


def endpoint(candidate):
    if (candidate.get('media_type') not in TIME_FIELDS
            or not positive_id(candidate.get('tmdb_id'), 2**31 - 1)):
        raise Stopped('IDENTITY_MISMATCH')
    media = 'movie' if candidate['media_type'] == 'MOVIE' else 'tv'
    return '/3/' + media + '/' + str(candidate['tmdb_id'])


def scope(report):
    if report['gate'] != 'READY_FOR_DETAIL_DRY_RUN' or report['blockers']:
        raise CatalogError('Current coverage gate blocks runtime collection.')
    result = []
    for media in TIME_FIELDS:
        eligible = sorted((r for r in report['detail_fetch_candidates'] if r['media_type'] == media),
                          key=lambda r: r['content_id'])
        result.extend(eligible[:20])
    return result


def supported_scalar(value):
    return (value is None or type(value) in (int, bool)
            and abs(value) <= 2**31 - 1
            or type(value) is float and math.isfinite(value) and abs(value) <= 2**31 - 1)


def project_response(payload, candidate, body_hash):
    media = candidate['media_type']
    identity = IDENTITY_FIELDS[media]
    opposite = 'TV' if media == 'MOVIE' else 'MOVIE'
    if (not isinstance(payload, dict) or type(payload.get('id')) is not int
            or payload['id'] != candidate['tmdb_id']
            or not isinstance(payload.get(identity), str) or not payload[identity].strip()
            or len(payload[identity]) > 500
            or IDENTITY_FIELDS[opposite] in payload or SOURCE_FIELDS[opposite] in payload
            or ('media_type' in payload and payload['media_type'] != ('movie' if media == 'MOVIE' else 'tv'))):
        raise Stopped('IDENTITY_MISMATCH')
    field = SOURCE_FIELDS[media]
    value = payload.get(field)
    supported = (supported_scalar(value) if media == 'MOVIE' else value is None
                 or isinstance(value, list) and len(value) <= 100 and all(supported_scalar(v) for v in value))
    return {'returned_id': payload['id'], 'identity_field': identity,
            'source_field_present': field in payload, 'source_value_supported': supported,
            'source_value': value if supported else None, 'response_sha256': body_hash}


def decision(media, evidence):
    value = evidence['source_value']
    if not evidence['source_field_present']:
        return None, 'MISSING_FIELD'
    if not evidence['source_value_supported']:
        return None, 'UNSUPPORTED_VALUE_SHAPE'
    if value is None:
        return None, 'NULL_VALUE'
    if media == 'MOVIE':
        return (value, 'VALID_MOVIE_RUNTIME') if valid_minutes(value) else (None, 'INVALID_MINUTES')
    if not isinstance(value, list):
        return None, 'UNSUPPORTED_VALUE_SHAPE'
    if not value:
        return None, 'EMPTY_EPISODE_RUNTIME'
    if not all(valid_minutes(v) for v in value):
        return None, 'INVALID_MINUTES'
    if len(set(value)) != 1:
        return None, 'MULTIPLE_EPISODE_RUNTIMES'
    return value[0], 'SINGLE_DISTINCT_EPISODE_RUNTIME'


class DetailClient:
    def __init__(self):
        self._token = os.environ.get('TMDB_READ_ACCESS_TOKEN', '').strip()
        if not self._token:
            raise Stopped('TOKEN_REQUIRED')
        self.opener = build_opener(NoRedirect())
        self.calls = 0
        self.started = time.monotonic()

    def read_body(self, response):
        # read1 returns available bytes instead of waiting to fill a large buffer.
        # Check the wall budget between reads; one socket read can take up to 20s.
        chunks, size = [], 0
        while True:
            if time.monotonic() - self.started > MAX_SECONDS:
                raise Stopped('TIME_BUDGET')
            chunk = response.read1(min(65536, MAX_BODY - size + 1))
            if not chunk:
                return b''.join(chunks)
            size += len(chunk)
            if size > MAX_BODY:
                raise Stopped('RESPONSE_TOO_LARGE')
            chunks.append(chunk)

    def fetch(self, candidate):
        path = endpoint(candidate)
        if self.calls >= MAX_CALLS:
            raise Stopped('REQUEST_BUDGET')
        remaining = MAX_SECONDS - (time.monotonic() - self.started)
        if remaining <= 0:
            raise Stopped('TIME_BUDGET')
        request = Request('https://api.themoviedb.org' + path + '?language=ko-KR', headers={
            'Authorization': 'Bearer ' + self._token, 'Accept': 'application/json',
            'User-Agent': 'Recommend-Runtime-DryRun/1.0'})
        self.calls += 1
        try:
            with self.opener.open(request, timeout=min(20, remaining)) as response:
                body = self.read_body(response)
            if time.monotonic() - self.started > MAX_SECONDS:
                raise Stopped('TIME_BUDGET')
            payload = strict_json(body)
        except HTTPError as error:
            code = 'RATE_LIMIT' if error.code == 429 else 'HTTP_ERROR'
            error.close()
            raise Stopped(code) from None
        except (URLError, TimeoutError, OSError, HTTPException):
            raise Stopped('NETWORK_ERROR') from None
        except (ValueError, UnicodeError, RecursionError):
            raise Stopped('INVALID_RESPONSE') from None
        evidence = project_response(payload, candidate, hashlib.sha256(body).hexdigest())
        time.sleep(0.3)
        return {**candidate, 'endpoint': path, 'provider': 'TMDB', 'language': 'ko-KR',
                'source_field': SOURCE_FIELDS[candidate['media_type']], 'fetched_at_utc': utc_now(),
                'evidence': evidence}


def seal(collection):
    collection['collection_sha256'] = digest({k: v for k, v in collection.items() if k != 'collection_sha256'})
    return collection


def run_collection(db, collect=False, client_factory=DetailClient):
    before = audit(db)
    selected = scope(before)
    collection = {'version': 1, 'policy': POLICY, 'job_id': uuid4().hex,
                  'started_at_utc': utc_now(), 'finished_at_utc': None,
                  'coverage_snapshot_sha256': before['coverage_snapshot_sha256'],
                  'scope': selected, 'state': 'PREPARED', 'stop_code': None, 'api_calls': 0, 'records': []}
    client = None
    if collect and selected:
        try:
            client = client_factory()
            for candidate in selected:
                collection['records'].append(client.fetch(candidate))
            collection['state'] = 'COMPLETE'
        except Stopped as error:
            collection['state'] = 'TOKEN_REQUIRED' if error.code == 'TOKEN_REQUIRED' else 'ABORTED'
            collection['stop_code'] = error.code
        collection['api_calls'] = client.calls if client else 0
    elif collect:
        collection['state'] = 'COMPLETE'
    try:
        current = audit(db)
    except CatalogError:
        current = None
        collection['state'] = 'DB_RECHECK_FAILED'
        collection['stop_code'] = 'DB_RECHECK_FAILED'
    collection['finished_at_utc'] = utc_now()
    return seal(collection), current


def require(condition):
    if not condition:
        raise CatalogError('Saved collection failed validation; no proposal produced.')


def timestamp(value):
    require(isinstance(value, str) and len(value) <= 40)
    parsed = datetime.fromisoformat(value)
    require(parsed.tzinfo is not None and parsed.utcoffset().total_seconds() == 0)
    return parsed


def validate_collection(collection):
    require(isinstance(collection, dict) and collection.get('version') == 1 and collection.get('policy') == POLICY)
    require(collection.get('collection_sha256') == digest({k: v for k, v in collection.items() if k != 'collection_sha256'}))
    require(isinstance(collection.get('job_id'), str) and re.fullmatch('[a-f0-9]{32}', collection['job_id']))
    require(collection.get('state') in STATES and collection.get('stop_code') in STOP_CODES | {None})
    require(isinstance(collection.get('coverage_snapshot_sha256'), str)
            and re.fullmatch('[a-f0-9]{64}', collection['coverage_snapshot_sha256']))
    start, end = timestamp(collection['started_at_utc']), timestamp(collection['finished_at_utc'])
    require(start <= end)
    selected, records = collection.get('scope'), collection.get('records')
    require(isinstance(selected, list) and isinstance(records, list) and len(selected) <= MAX_CALLS)
    counts, identities = Counter(), set()
    for candidate in selected:
        require(isinstance(candidate, dict) and positive_id(candidate.get('content_id')))
        endpoint(candidate)
        media = candidate['media_type']
        require(candidate.get('target_field') == TIME_FIELDS[media] and 'expected_existing_value' in candidate
                and candidate['expected_existing_value'] is None and candidate['content_id'] not in identities)
        identities.add(candidate['content_id'])
        counts[media] += 1
    require(all(n <= 20 for n in counts.values()))
    require(type(collection.get('api_calls')) is int and 0 <= collection['api_calls'] <= len(selected))
    require(len(records) <= collection['api_calls'] and len(records) <= len(selected))
    if collection['state'] == 'COMPLETE':
        require(len(records) == len(selected) == collection['api_calls'] and collection['stop_code'] is None)
    if collection['state'] in ('PREPARED', 'TOKEN_REQUIRED'):
        require(not records and collection['api_calls'] == 0)
        require(collection['stop_code'] == (None if collection['state'] == 'PREPARED' else 'TOKEN_REQUIRED'))
    if collection['state'] == 'ABORTED':
        require(collection['stop_code'] in STOP_CODES - {'TOKEN_REQUIRED', 'DB_RECHECK_FAILED'})
    if collection['state'] == 'DB_RECHECK_FAILED':
        require(collection['stop_code'] == 'DB_RECHECK_FAILED')
    for candidate, record in zip(selected, records):
        require(isinstance(record, dict) and all(record.get(k) == v for k, v in candidate.items()))
        media = candidate['media_type']
        require(record.get('endpoint') == endpoint(candidate) and record.get('provider') == 'TMDB'
                and record.get('language') == 'ko-KR' and record.get('source_field') == SOURCE_FIELDS[media])
        require(start <= timestamp(record['fetched_at_utc']) <= end)
        evidence = record['evidence']
        require(isinstance(evidence, dict) and type(evidence.get('returned_id')) is int
                and evidence['returned_id'] == candidate['tmdb_id']
                and evidence.get('identity_field') == IDENTITY_FIELDS[media])
        require(isinstance(evidence.get('response_sha256'), str)
                and re.fullmatch('[a-f0-9]{64}', evidence['response_sha256']))
        require(type(evidence.get('source_field_present')) is bool
                and type(evidence.get('source_value_supported')) is bool and 'source_value' in evidence)
        value = evidence['source_value']
        require(supported_scalar(value) or isinstance(value, list) and len(value) <= 100
                and all(supported_scalar(v) for v in value))
        require(evidence['source_field_present'] or value is None)
        require(evidence['source_value_supported'] or value is None)
        require(media != 'MOVIE' or not isinstance(value, list))


def derive_changes(collection):
    """Recompute public duration proposals from validated evidence, without DB I/O."""
    validate_collection(collection)
    proposals, skipped = [], []
    for record in collection['records']:
        value, reason = decision(record['media_type'], record['evidence'])
        if value is None:
            skipped.append({'content_id': record['content_id'], 'media_type': record['media_type'], 'reason': reason})
        else:
            proposals.append({key: record[key] for key in ('content_id', 'media_type', 'tmdb_id', 'target_field',
                              'expected_existing_value', 'provider', 'endpoint', 'language', 'source_field', 'fetched_at_utc')}
                             | {'proposed_minutes': value, 'source_value': record['evidence']['source_value'],
                                'response_sha256': record['evidence']['response_sha256'], 'reason': reason,
                                'job_id': collection['job_id']})
    return proposals, skipped


def make_plan(collection, current):
    proposals, skipped = derive_changes(collection)
    blockers = []
    if collection['state'] != 'COMPLETE':
        blockers.append(collection['state'])
    if current is None:
        blockers.append('DB_RECHECK_FAILED')
    elif (current['gate'] != 'READY_FOR_DETAIL_DRY_RUN' or current['blockers']
          or current['coverage_snapshot_sha256'] != collection['coverage_snapshot_sha256']
          or scope(current) != collection['scope']):
        blockers.append('CURRENT_CATALOG_CHANGED')
    result = {'version': 1, 'mode': 'REVIEW_ONLY_NO_APPLY', 'policy': POLICY,
              'collection_sha256': collection['collection_sha256'], 'job_id': collection['job_id'],
              'coverage_snapshot_sha256': collection['coverage_snapshot_sha256'],
              'gate': 'BLOCKED' if blockers else 'READY_FOR_REVIEW', 'blockers': sorted(set(blockers)),
              'selected_count': len(collection['scope']), 'api_calls': collection['api_calls'],
              'received_count': len(collection['records']), 'validated_value_count': len(proposals),
              'proposed_changes': [] if blockers else proposals, 'skipped': skipped,
              'skip_reasons': dict(Counter(row['reason'] for row in skipped)),
              'catalog_writes': 0, 'apply_supported': False}
    result['proposal_sha256'] = digest(result)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'collect', 'plan'))
    parser.add_argument('--collection', type=Path)
    args = parser.parse_args(argv)
    if (args.command == 'plan') != bool(args.collection):
        parser.error('--collection is required only for plan')
    try:
        if args.command == 'plan':
            if args.collection.stat().st_size > 1_000_000:
                raise CatalogError('Saved collection exceeds its size budget.')
            collection = strict_json(args.collection.read_bytes())
            validate_collection(collection)
            current = audit(LocalCatalog())
        else:
            collection, current = run_collection(LocalCatalog(), collect=args.command == 'collect')
        plan = make_plan(collection, current)
        folder = ROOT / 'target/runtime-dry-run' / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
                                                  + '-' + uuid4().hex[:8])
        write_json(folder / 'collection.json', collection)
        write_json(folder / 'proposal.json', plan)
        print(json.dumps({'output': str(folder), 'state': collection['state'], 'gate': plan['gate'],
                          'selected': plan['selected_count'], 'api_calls': plan['api_calls'],
                          'proposed': len(plan['proposed_changes']), 'skipped': len(plan['skipped']),
                          'blockers': plan['blockers']}))
        return 0 if args.command == 'prepare' and collection['state'] == 'PREPARED' and plan['blockers'] == ['PREPARED'] \
            or plan['gate'] == 'READY_FOR_REVIEW' else 2
    except (CatalogError, KeyError, TypeError, ValueError, OSError, RecursionError):
        print('Runtime dry-run failed; credentials, payloads and database details withheld.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
