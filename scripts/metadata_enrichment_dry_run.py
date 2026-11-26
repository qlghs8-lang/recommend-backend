"""Pinned 28-title TMDB metadata collector and review-only planner. No apply command.

prepare --snapshot-dir DIR: offline scope verification; no token, DB or network.
prepare: read-only current DB verification; no token or data API.
collect: current DB verification, bounded data GETs, current DB recheck.
plan --collection FILE [--snapshot-dir DIR]: replay validated evidence; never collect.
All output goes to a new folder. Saved snapshots/rehearsals cannot pass the live review gate.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import html
from http.client import HTTPSConnection, HTTPException
import json
import os
from pathlib import Path
import re
import sys
import time
from uuid import uuid4

from audit_metadata_enrichment import propose, read_consistent, title_key
from catalog_pool import CatalogError, LocalCatalog, ROOT, digest, write_json
from runtime_detail_dry_run import decision, strict_json, supported_scalar

POLICY = 'metadata-review-only-v1'
CONTRACT = ROOT / 'docs/evaluation/metadata-enrichment-proposal-v1.json'
CONTRACT_SHA = '06fd84e18b745ab100d9cb06756c24ce5db8861f5629777c863e8f54ed829492'
KINDS = ('detail', 'keywords', 'alternative_titles')
MAX_CALLS, MAX_SECONDS, MAX_BODY = 84, 900, 1048576
STOP_CODES = {'TOKEN_REQUIRED', 'AUTH_ERROR', 'RATE_LIMIT', 'SERVER_ERROR', 'HTTP_ERROR',
              'REDIRECT', 'NETWORK_ERROR', 'TIME_BUDGET', 'REQUEST_BUDGET',
              'RESPONSE_TOO_LARGE', 'INVALID_RESPONSE', 'IDENTITY_MISMATCH', 'FIELD_LIMIT', 'INTERRUPTED'}
STATUSES = {'NOT_RUN', 'RECEIVED', 'ERROR'}


def require(condition):
    if not condition:
        raise CatalogError('Metadata evidence/contract validation failed; no review produced.')


class Stopped(CatalogError):
    def __init__(self, code):
        self.code = code
        super().__init__('Metadata collection stopped: ' + code)


def now():
    return datetime.now(timezone.utc).isoformat()


def timestamp(value):
    require(isinstance(value, str) and len(value) <= 40)
    parsed = datetime.fromisoformat(value)
    require(parsed.tzinfo is not None and parsed.utcoffset().total_seconds() == 0)
    return parsed


def load_json(path, maximum=10_000_000):
    with Path(path).open('rb') as stream:
        raw = stream.read(maximum + 1)
    require(len(raw) <= maximum)
    return strict_json(raw)


def contract():
    result = load_json(CONTRACT, 100_000)
    require(digest(result) == CONTRACT_SHA)
    return result


def check_catalog(plan, public, mappings):
    require(digest(plan) == CONTRACT_SHA)
    # Reconstruct the exact previously proposed scope, not a caller-supplied endpoint list.
    require(propose(public, mappings)[1] == plan)


def tasks(plan):
    require(digest(plan) == CONTRACT_SHA)
    result = []
    for candidate in plan['pilot']:
        media = 'movie' if candidate['media_type'] == 'MOVIE' else 'tv'
        base = '/3/' + media + '/' + str(candidate['tmdb_id'])
        for kind in KINDS:
            path = base + ('?language=ko-KR' if kind == 'detail' else '/' + kind)
            result.append({k: candidate[k] for k in ('content_id', 'media_type', 'tmdb_id')}
                          | {'kind': kind, 'endpoint': path,
                             'requested_locale': 'ko-KR' if kind == 'detail' else None})
    require(len(result) == MAX_CALLS)
    return result


def field(payload, name, limit, pattern=None):
    if name not in payload:
        return {'state': 'MISSING', 'value': None}
    value = payload[name]
    if value is None:
        return {'state': 'NULL', 'value': None}
    if not isinstance(value, str):
        raise Stopped('INVALID_RESPONSE')
    if len(value) > limit:
        raise Stopped('FIELD_LIMIT')
    if value.strip() and pattern and not re.fullmatch(pattern, value):
        raise Stopped('INVALID_RESPONSE')
    return {'state': 'VALUE' if value.strip() else 'EMPTY', 'value': value}


def project(payload, task):
    """Keep only bounded public source fields; never execute text or store unknown keys."""
    if not isinstance(payload, dict) or type(payload.get('id')) is not int or payload['id'] != task['tmdb_id']:
        raise Stopped('IDENTITY_MISMATCH')
    movie = task['media_type'] == 'MOVIE'
    media = 'movie' if movie else 'tv'
    if 'media_type' in payload and payload['media_type'] != media:
        raise Stopped('IDENTITY_MISMATCH')
    if task['kind'] == 'detail':
        identity, original, duration = ('title', 'original_title', 'runtime') if movie else ('name', 'original_name', 'episode_run_time')
        if any(k in payload for k in (('name', 'episode_run_time') if movie else ('title', 'runtime'))):
            raise Stopped('IDENTITY_MISMATCH')
        fields = {identity: field(payload, identity, 500), original: field(payload, original, 500),
                  'original_language': field(payload, 'original_language', 3, '[a-z]{2,3}'),
                  'overview': field(payload, 'overview', 20000)}
        if fields[identity]['state'] != 'VALUE':
            raise Stopped('IDENTITY_MISMATCH')
        value = payload.get(duration)
        supported = supported_scalar(value) if movie else value is None or (
            isinstance(value, list) and len(value) <= 100 and all(supported_scalar(v) for v in value))
        fields[duration] = {'present': duration in payload, 'supported': supported,
                            'value': value if supported else None}
        return {'returned_id': payload['id'], 'fields': fields}
    root = ('keywords' if movie else 'results') if task['kind'] == 'keywords' else ('titles' if movie else 'results')
    if any(k in payload for k in ({'keywords', 'titles', 'results'} - {root})):
        raise Stopped('INVALID_RESPONSE')
    if root not in payload or payload[root] is None:
        return {'returned_id': payload['id'], 'source_field': root,
                'state': 'MISSING' if root not in payload else 'NULL', 'entries': None}
    entries = payload[root]
    if not isinstance(entries, list):
        raise Stopped('INVALID_RESPONSE')
    if len(entries) > (200 if task['kind'] == 'keywords' else 100):
        raise Stopped('FIELD_LIMIT')
    projected, names = [], {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise Stopped('INVALID_RESPONSE')
        if task['kind'] == 'keywords':
            ident, label = entry.get('id'), field(entry, 'name', 128)
            if type(ident) is not int or not 1 <= ident <= 2**31 - 1 or label['state'] != 'VALUE':
                raise Stopped('INVALID_RESPONSE')
            if ident in names and names[ident] != label['value']:
                raise Stopped('INVALID_RESPONSE')
            names[ident] = label['value']
            projected.append({'id': ident, 'name': label['value']})
        else:
            title = field(entry, 'title', 255)
            if title['state'] != 'VALUE':
                raise Stopped('INVALID_RESPONSE')
            projected.append({'title': title, 'country': field(entry, 'iso_3166_1', 2, '[A-Z]{2}'),
                              'type': field(entry, 'type', 100)})
    return {'returned_id': payload['id'], 'source_field': root,
            'state': 'VALUE' if entries else 'EMPTY', 'entries': projected}


def validate_projection(data, task):
    """Rebuild source shape from saved projection and run the same strict validators."""
    require(isinstance(data, dict))
    payload = {'id': data['returned_id']}
    if task['kind'] == 'detail':
        duration = 'runtime' if task['media_type'] == 'MOVIE' else 'episode_run_time'
        for name, value in data['fields'].items():
            if name == duration:
                require(set(value) == {'present', 'supported', 'value'} and type(value['present']) is bool
                        and type(value['supported']) is bool)
                require(value['present'] or value == {'present': False, 'supported': True, 'value': None})
                require(value['supported'] or value['value'] is None)
                if value['present']:
                    payload[name] = value['value'] if value['supported'] else {'unsupported': True}
            else:
                require(set(value) == {'state', 'value'})
                if value['state'] != 'MISSING':
                    payload[name] = value['value']
    else:
        if data['state'] != 'MISSING':
            payload[data['source_field']] = data['entries']
            if task['kind'] == 'alternative_titles' and data['entries'] is not None:
                payload[data['source_field']] = []
                for entry in data['entries']:
                    require(set(entry) == {'title', 'country', 'type'})
                    source = {}
                    for name, value in entry.items():
                        require(set(value) == {'state', 'value'})
                        if value['state'] != 'MISSING':
                            source['iso_3166_1' if name == 'country' else name] = value['value']
                    payload[data['source_field']].append(source)
    require(project(payload, task) == data)


class MetadataClient:
    def __init__(self, token=None, connection_factory=HTTPSConnection, clock=time.monotonic, sleep=time.sleep):
        self._token = os.environ.get('TMDB_READ_ACCESS_TOKEN', '').strip() if token is None else token.strip()
        if not self._token:
            raise Stopped('TOKEN_REQUIRED')
        if not re.fullmatch(r'[A-Za-z0-9._~+/-]+=*', self._token):
            raise Stopped('AUTH_ERROR')
        self.connection_factory, self.clock, self.sleep = connection_factory, clock, sleep
        self.started = self.clock()
        self.deadline = self.started + MAX_SECONDS
        self.last_start = None
        self.calls = 0

    def remaining(self, deadline):
        value = deadline - self.clock()
        if value <= 0:
            raise Stopped('TIME_BUDGET')
        return value

    def fetch(self, task):
        # Match the pinned schedule before constructing any authenticated request.
        require(task in tasks(contract()))
        if self.calls >= MAX_CALLS:
            raise Stopped('REQUEST_BUDGET')
        if self.last_start is not None:
            delay = max(0, 1 - (self.clock() - self.last_start))
            if delay:
                if delay >= self.remaining(self.deadline):
                    raise Stopped('TIME_BUDGET')
                self.sleep(delay)
        deadline = min(self.deadline, self.clock() + 10)
        remaining = self.remaining(deadline)
        connection = self.connection_factory('api.themoviedb.org', timeout=remaining)
        self.last_start = self.clock()
        self.calls += 1
        response = None
        try:
            connection.connect()
            sock = connection.sock
            sock.settimeout(self.remaining(deadline))
            connection.request('GET', task['endpoint'], headers={
                'Authorization': 'Bearer ' + self._token, 'Accept': 'application/json',
                'User-Agent': 'Recommend-Metadata-DryRun/1.0', 'Accept-Encoding': 'identity'})
            sock.settimeout(self.remaining(deadline))
            response = connection.getresponse()
            if response.status != 200:
                status = response.status
                raise Stopped('REDIRECT' if 300 <= status < 400 else 'AUTH_ERROR' if status in (401, 403)
                              else 'RATE_LIMIT' if status == 429 else 'SERVER_ERROR' if status >= 500 else 'HTTP_ERROR')
            if response.getheader('Content-Encoding', 'identity').lower() != 'identity':
                raise Stopped('INVALID_RESPONSE')
            size_header = response.getheader('Content-Length')
            if size_header is not None and (not size_header.isdigit() or int(size_header) > MAX_BODY):
                raise Stopped('RESPONSE_TOO_LARGE')
            chunks, size = [], 0
            while True:
                sock.settimeout(self.remaining(deadline))
                chunk = response.read1(min(65536, MAX_BODY - size + 1))
                self.remaining(deadline)
                if not chunk:
                    break
                size += len(chunk)
                if size > MAX_BODY:
                    raise Stopped('RESPONSE_TOO_LARGE')
                chunks.append(chunk)
            body = b''.join(chunks)
            if size_header is not None and len(body) != int(size_header):
                raise Stopped('INVALID_RESPONSE')
            payload = strict_json(body)
            data = project(payload, task)
            self.remaining(deadline)
            return {'fetched_at_utc': now(), 'response_sha256': hashlib.sha256(body).hexdigest(), 'data': data}
        except (TimeoutError, OSError, HTTPException):
            raise Stopped('NETWORK_ERROR') from None
        except (ValueError, UnicodeError, RecursionError):
            raise Stopped('INVALID_RESPONSE') from None
        finally:
            if response is not None:
                response.close()
            connection.close()


def seal(value):
    value['collection_sha256'] = digest({k: v for k, v in value.items() if k != 'collection_sha256'})
    return value


def run(plan, reader, collect=False, client_factory=MetadataClient, source_kind='TMDB_HTTP'):
    public, mappings = reader()
    check_catalog(plan, public, mappings)
    schedule = tasks(plan)
    result = {'version': 1, 'policy': POLICY, 'job_id': uuid4().hex, 'contract_sha256': digest(plan),
              'public_snapshot_sha256': plan['public_snapshot_sha256'],
              'mapping_snapshot_sha256': plan['mapping_snapshot_sha256'],
              'started_at_utc': now(), 'finished_at_utc': None,
              'source_kind': source_kind if collect else 'NONE', 'state': 'PREPARED',
              'stop_code': None, 'api_calls': 0, 'postcheck': 'NOT_CHECKED',
              'records': [task | {'status': 'NOT_RUN', 'error_code': None, 'fetched_at_utc': None,
                                  'response_sha256': None, 'data': None} for task in schedule]}
    client = None
    if collect:
        try:
            client = client_factory()
            for record in result['records']:
                before = client.calls
                try:
                    record.update(client.fetch({k: record[k] for k in schedule[0]}))
                    record['status'] = 'RECEIVED'
                except (Stopped, KeyboardInterrupt) as error:
                    code = error.code if isinstance(error, Stopped) else 'INTERRUPTED'
                    if client.calls > before:
                        record.update(status='ERROR', error_code=code)
                    raise Stopped(code) from None
            result['state'] = 'COMPLETE'
        except Stopped as error:
            result['state'] = 'TOKEN_REQUIRED' if error.code == 'TOKEN_REQUIRED' else 'ABORTED'
            result['stop_code'] = error.code
        result['api_calls'] = client.calls if client else 0
    try:
        current = reader()
        check_catalog(plan, *current)
        result['postcheck'] = 'MATCH'
    except (CatalogError, KeyError, TypeError, ValueError, OSError):
        current = None
        result['postcheck'] = 'FAILED_OR_CHANGED'
    result['finished_at_utc'] = now()
    return seal(result), (public, mappings), current


def validate_collection(saved, plan):
    expected = {'version', 'policy', 'job_id', 'contract_sha256', 'public_snapshot_sha256',
                'mapping_snapshot_sha256', 'started_at_utc', 'finished_at_utc', 'source_kind',
                'state', 'stop_code', 'api_calls', 'postcheck', 'records', 'collection_sha256'}
    require(isinstance(saved, dict) and set(saved) == expected)
    require(saved['collection_sha256'] == digest({k: v for k, v in saved.items() if k != 'collection_sha256'}))
    require(type(saved['version']) is int and saved['version'] == 1 and saved['policy'] == POLICY
            and saved['contract_sha256'] == CONTRACT_SHA == digest(plan))
    require(saved['public_snapshot_sha256'] == plan['public_snapshot_sha256']
            and saved['mapping_snapshot_sha256'] == plan['mapping_snapshot_sha256'])
    require(isinstance(saved['job_id'], str) and re.fullmatch('[a-f0-9]{32}', saved['job_id']))
    start, end = timestamp(saved['started_at_utc']), timestamp(saved['finished_at_utc'])
    require(start <= end and saved['source_kind'] in ('NONE', 'TMDB_HTTP', 'SYNTHETIC_OFFLINE'))
    require(saved['state'] in ('PREPARED', 'TOKEN_REQUIRED', 'COMPLETE', 'ABORTED')
            and saved['postcheck'] in ('MATCH', 'NOT_CHECKED', 'FAILED_OR_CHANGED'))
    schedule = tasks(plan)
    require(isinstance(saved['records'], list) and len(saved['records']) == len(schedule))
    statuses = []
    for task, record in zip(schedule, saved['records']):
        require(set(record) == set(task) | {'status', 'error_code', 'fetched_at_utc', 'response_sha256', 'data'})
        require(all(type(record[k]) is type(v) and record[k] == v for k, v in task.items()))
        status = record['status']
        require(status in STATUSES)
        statuses.append(status)
        if status == 'RECEIVED':
            require(record['error_code'] is None and start <= timestamp(record['fetched_at_utc']) <= end)
            require(isinstance(record['response_sha256'], str) and re.fullmatch('[a-f0-9]{64}', record['response_sha256']))
            validate_projection(record['data'], task)
        else:
            require(record['data'] is None and record['response_sha256'] is None and record['fetched_at_utc'] is None)
            require(record['error_code'] in STOP_CODES - {'TOKEN_REQUIRED'} if status == 'ERROR' else record['error_code'] is None)
    received, errors, not_run = (statuses.count(s) for s in ('RECEIVED', 'ERROR', 'NOT_RUN'))
    require(errors <= 1 and statuses == ['RECEIVED'] * received + ['ERROR'] * errors + ['NOT_RUN'] * not_run)
    require(type(saved['api_calls']) is int and saved['api_calls'] == received + errors <= MAX_CALLS)
    if saved['state'] == 'COMPLETE':
        require(received == MAX_CALLS and saved['stop_code'] is None and saved['source_kind'] != 'NONE')
    elif saved['state'] in ('PREPARED', 'TOKEN_REQUIRED'):
        require(saved['api_calls'] == 0 and not_run == MAX_CALLS)
        require(saved['stop_code'] == ('TOKEN_REQUIRED' if saved['state'] == 'TOKEN_REQUIRED' else None))
        require((saved['source_kind'] == 'NONE') == (saved['state'] == 'PREPARED'))
    else:
        require(saved['stop_code'] in STOP_CODES - {'TOKEN_REQUIRED'} and saved['source_kind'] != 'NONE')
        require(received < MAX_CALLS)
        if errors:
            require(saved['records'][received]['error_code'] == saved['stop_code'])
        else:
            require(saved['stop_code'] in ('TIME_BUDGET', 'REQUEST_BUDGET', 'AUTH_ERROR', 'INTERRUPTED'))


def duration_review(data, media, existing):
    evidence = {'source_field_present': data['present'], 'source_value_supported': data['supported'], 'source_value': data['value']}
    minutes, reason = decision(media, evidence)
    status = 'SKIP' if minutes is None else 'PROPOSE_NULL_FILL' if existing is None else 'UNCHANGED' if minutes == existing else 'CONFLICT_PRESERVE_EXISTING'
    return {'state': status, 'reason': reason, 'existing': existing, 'proposed': minutes,
            'source': data, 'approved': False}


def review(saved, plan, baseline, current=None, live_check=False):
    check_catalog(plan, *baseline)
    validate_collection(saved, plan)
    blockers = []
    if saved['state'] != 'COMPLETE': blockers.append(saved['state'])
    if saved['source_kind'] != 'TMDB_HTTP': blockers.append('NOT_LIVE_TMDB_EVIDENCE')
    if saved['postcheck'] != 'MATCH': blockers.append('COLLECTION_POSTCHECK_NOT_MATCHED')
    if not live_check: blockers.append('CURRENT_DB_NOT_CHECKED')
    if current is None:
        blockers.append('CURRENT_CATALOG_UNAVAILABLE')
    else:
        try:
            check_catalog(plan, *current)
        except (CatalogError, KeyError, TypeError, ValueError):
            blockers.append('CURRENT_CATALOG_CHANGED')
    rows = {r['id']: r for r in baseline[0]['rows']}
    alias_ids = defaultdict(set)
    for row in rows.values(): alias_ids[title_key(row['title'])].add(row['id'])
    items = []
    for record in saved['records']:
        item = {k: record[k] for k in ('content_id', 'media_type', 'tmdb_id', 'kind', 'endpoint',
                                      'requested_locale', 'status', 'error_code', 'response_sha256', 'fetched_at_utc')}
        item.update(provider='TMDB' if saved['source_kind'] == 'TMDB_HTTP' else saved['source_kind'],
                    review_status='UNREVIEWED', approved=False, fields={})
        if record['status'] == 'RECEIVED':
            data = record['data']
            if record['kind'] == 'detail':
                fields = data['fields']
                duration = 'runtime' if record['media_type'] == 'MOVIE' else 'episode_run_time'
                old = rows[record['content_id']]
                for name, value in fields.items():
                    if name == duration:
                        existing = old['runtime_minutes' if duration == 'runtime' else 'episode_runtime_minutes']
                        item['fields'][name] = duration_review(value, record['media_type'], existing)
                    else:
                        result = dict(value)
                        if name == 'overview':
                            result['existing'] = old['overview']
                            result['review_action'] = value['state'] if value['state'] != 'VALUE' else (
                                'UNCHANGED' if value['value'] == old['overview'] else 'HOLD_STORAGE_LIMIT' if len(value['value']) > 2000
                                else 'HOLD_LANGUAGE_UNCONFIRMED' if not re.search('[가-힣]', value['value']) else 'NEEDS_REVIEW')
                        elif name in ('title', 'name', 'original_title', 'original_name') and value['state'] == 'VALUE':
                            result['normalized'] = title_key(value['value'])
                            alias_ids[result['normalized']].add(record['content_id'])
                            result['review_action'] = 'ALIAS_CANDIDATE_ONLY_PRESERVE_TITLE'
                        item['fields'][name] = result
            else:
                item['fields'] = {'source_field': data['source_field'], 'state': data['state'], 'entries': data['entries'],
                                  'language': 'UNKNOWN', 'translated_labels': [], 'semantic_centrality': 'UNJUDGED'}
                entries = data['entries'] or []
                if record['kind'] == 'keywords':
                    item['fields']['raw_count'] = len(entries)
                    item['fields']['unique_keyword_count'] = len({e['id'] for e in entries})
                else:
                    keys = []
                    for entry in entries:
                        normalized = title_key(entry['title']['value'])
                        alias_ids[normalized].add(record['content_id'])
                        keys.append((normalized, entry['country']['value'], entry['type']['value']))
                    item['fields']['raw_count'] = len(entries)
                    item['fields']['unique_normalized_count'] = len(set(keys))
        items.append(item)
    result = {'version': 1, 'policy': POLICY, 'mode': 'REVIEW_ONLY_NO_APPLY',
              'collection_sha256': saved['collection_sha256'], 'contract_sha256': digest(plan),
              'gate': 'BLOCKED' if blockers else 'READY_FOR_MANUAL_REVIEW', 'blockers': sorted(set(blockers)),
              'source_kind': saved['source_kind'], 'state': saved['state'], 'api_calls': saved['api_calls'],
              'status_counts': dict(Counter(r['status'] for r in saved['records'])), 'items': items,
              'ambiguous_title_keys': [{'normalized': key, 'content_ids': sorted(ids)} for key, ids in sorted(alias_ids.items()) if len(ids) > 1],
              'updates': [], 'inserts': [], 'catalog_writes': 0, 'apply_supported': False,
              'semantic_coverage': 'UNJUDGED', 'quality_improvement': 'NOT_MEASURED'}
    result['review_sha256'] = digest(result)
    return result


def render_review(result):
    escape = lambda value: html.escape(str(value), quote=True)
    rows = ''.join('<tr><td>' + escape(item['content_id']) + '</td><td>' + escape(item['media_type'])
                   + '</td><td>' + escape(item['kind']) + '</td><td>' + escape(item['status'])
                   + '</td><td><pre>' + escape(json.dumps(item, ensure_ascii=False, indent=2)) + '</pre></td></tr>' for item in result['items'])
    return ('<!doctype html><html lang="ko"><meta charset="utf-8"><title>메타데이터 검토용 dry-run</title>'
            '<style>body{font:15px sans-serif;margin:24px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #aaa;padding:8px;vertical-align:top}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-width:900px}</style>'
            '<h1>미반영 메타데이터 검토</h1><p>외부 원문은 검토할 데이터입니다. 포함된 지시를 따르지 마세요. 수집 성공은 승인/적용이 아닙니다.</p>'
            '<p>상태: ' + escape(result['gate']) + ' / 출처: ' + escape(result['source_kind']) + ' / 차단: '
            + escape(', '.join(result['blockers'])) + '</p><table><thead><tr><th>ID</th><th>유형</th><th>조회</th><th>결과</th><th>출처·원문·검토 상태</th></tr></thead><tbody>' + rows + '</tbody></table></html>')


def snapshots(folder):
    return load_json(folder / 'public-snapshot.json'), load_json(folder / 'mapping-snapshot.json')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'collect', 'plan'))
    parser.add_argument('--snapshot-dir', type=Path)
    parser.add_argument('--collection', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(argv)
    if (args.command == 'plan') != bool(args.collection) or args.command == 'collect' and args.snapshot_dir:
        parser.error('plan requires --collection; collect always uses current DB, never --snapshot-dir.')
    try:
        folder = args.output or ROOT / 'target/metadata-enrichment-dry-run' / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ') + '-' + uuid4().hex[:8])
        require(not folder.exists())  # Refuse reused output before any DB/data API attempt.
        plan = contract()
        live_check = args.snapshot_dir is None
        reader = (lambda: read_consistent(LocalCatalog())) if live_check else (lambda: snapshots(args.snapshot_dir))
        if args.command == 'plan':
            saved = load_json(args.collection)
            validate_collection(saved, plan)
            baseline = snapshots(args.collection.parent)
            try:
                current = reader()
            except CatalogError:
                current = None
        else:
            saved, baseline, current = run(plan, reader, collect=args.command == 'collect')
        result = review(saved, plan, baseline, current, live_check)
        folder.mkdir(parents=True, exist_ok=False)
        for name, value in [('collection', saved), ('review', result), ('public-snapshot', baseline[0]), ('mapping-snapshot', baseline[1])]:
            write_json(folder / (name + '.json'), value)
        (folder / 'review.html').write_text(render_review(result), encoding='utf-8')
        print(json.dumps({'output': str(folder), 'state': saved['state'], 'gate': result['gate'],
                          'api_calls': saved['api_calls'], 'status_counts': result['status_counts'], 'blockers': result['blockers']}))
        return 0 if args.command == 'prepare' and saved['postcheck'] == 'MATCH' or result['gate'] == 'READY_FOR_MANUAL_REVIEW' else 2
    except (CatalogError, KeyError, TypeError, ValueError, OSError, RecursionError):
        print('Metadata dry-run failed; credentials, payloads and database details withheld.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
