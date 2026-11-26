"""Explicit, hash-pinned discovery activation/withdrawal; immutable raw/review data.

view --output NEW_FILE (offline)
prepare --action enable|disable --reason TEXT --output NEW_DIR (read-only DB)
apply --plan FILE --plan-sha256 SHA --output NEW_DIR
No Content writes, provider calls, automatic retry or duration activation.
"""
import argparse
from datetime import datetime, timezone
import hashlib
from pathlib import Path
import re
import sys

import apply_metadata_review_release as app
from catalog_pool import CatalogError, LocalCatalog, ROOT, digest, sql_value, write_json

stage = app.stage
require = stage.require
TABLE = 'catalog_metadata_search_events'
POLICY = 'reviewed-metadata-discovery-v1'
BASE_PLAN_SHA = '19ceccd10f74029f18dbfd9afad7da1bc8eb8c058fe5d7292d145030595ff10e'
RESOURCE = ROOT/'src/main/resources/catalog/metadata-search-v1.json'
BASE_PLAN = ROOT/'target/metadata-review-store-20261126/live-plan/plan.json'
COLS = {'event_id': 'bigint', 'event_sha': 'char(64)', 'previous_sha': 'char(64)', 'enabled': 'tinyint',
        'release_sha': 'char(64)', 'search_sha': 'char(64)', 'payload': 'longtext', 'event_body': 'longtext', 'created_at': 'datetime(6)'}
DDL = f'''CREATE TABLE `{TABLE}` (
 event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_sha CHAR(64) NOT NULL UNIQUE,
 previous_sha CHAR(64) NULL UNIQUE,
 enabled TINYINT NOT NULL,
 release_sha CHAR(64) NOT NULL,
 search_sha CHAR(64) NOT NULL,
 payload LONGTEXT NOT NULL,
 event_body LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(previous_sha) REFERENCES `{TABLE}`(event_sha),
 CHECK(enabled IN (0,1)), CHECK(OCTET_LENGTH(payload)<=200000), CHECK(OCTET_LENGTH(event_body)<=4096)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
'''


def fixed_context():
    _, ctx, compiled, expected, _, _ = app.load_plan(BASE_PLAN, BASE_PLAN_SHA)
    return ctx, compiled, expected


def build_view(ctx, compiled):
    works = {}
    for entry in compiled['candidate']['entries']:
        if entry['kind'] == 'RUNTIME': continue
        ident = entry['content_id']; public = ctx['public'][ident]
        work = works.setdefault(ident, {'content_id': ident, **{k: public[k] for k in ('type', 'title', 'overview')}, 'keywords': [], 'aliases': []})
        raw = ctx['rows'][entry['source_row_sha']][1]
        value = entry['reviewed_value']
        if entry['kind'] == 'KEYWORD':
            require(entry['use'] == 'DISCOVERY_ONLY' and value['centrality'] in ('CENTRAL', 'SUPPORTING'))
            work['keywords'].append({'row_sha': entry['source_row_sha'], 'label': raw['label'], 'label_ko': value['label_ko']})
        else:
            require(entry['use'] == 'EXACT_ALIAS_CANDIDATE' and raw['alias_kind'] in ('OFFICIAL_TITLE', 'ORIGINAL_TITLE') and value['scope'] == 'WHOLE_WORK')
            work['aliases'].append({'row_sha': entry['source_row_sha'], 'title': value['source_form']})
    for work in works.values():
        require(all(isinstance(work[f], str) for f in ('type', 'title', 'overview')))
        for f in ('keywords', 'aliases'): work[f].sort(key=lambda r: r['row_sha'])
    require(sum(len(w['keywords']) for w in works.values()) == 26 and sum(len(w['aliases']) for w in works.values()) == 55)
    return {'policy': POLICY, 'release_sha': app.PINS['release'], 'version_sha': app.PINS['version'],
            'keyword_count': 26, 'alias_count': 55, 'works': [works[i] for i in sorted(works)]}


def view_payload(ctx, compiled):
    payload = stage.canonical(build_view(ctx, compiled))
    require(len(payload.encode()) <= 200000)
    return payload


def code_hashes():
    files = ['scripts/'+n for n in app.CODE_FILES] + ['scripts/metadata_search_activation.py',
        'src/main/java/com/example/recommend/chat/service/ReviewedMetadataSearch.java',
        'src/main/java/com/example/recommend/chat/service/CatalogRecommendations.java',
        'src/main/java/com/example/recommend/chat/service/CatalogRetriever.java']
    return {n: app.draft.file_sha(ROOT/n) for n in files}


def schema_guard():
    where = 'TABLE_SCHEMA=DATABASE() AND TABLE_NAME='+sql_value(TABLE)
    clauses = [f"(SELECT COUNT(*) FROM information_schema.TABLES WHERE {where} AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB' AND TABLE_COLLATION='utf8mb4_bin')=1",
               f'(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE {where})={len(COLS)}']
    for f, typ in COLS.items():
        test = where+' AND COLUMN_NAME='+sql_value(f)+' AND COLUMN_TYPE='+sql_value(typ)
        test += ' AND IS_NULLABLE='+sql_value('YES' if f == 'previous_sha' else 'NO')
        test += ' AND COLUMN_DEFAULT IS NULL AND GENERATION_EXPRESSION=\'\' AND EXTRA='+sql_value('auto_increment' if f == 'event_id' else '')
        if typ.startswith(('char', 'longtext')): test += " AND COLLATION_NAME='utf8mb4_bin'"
        clauses.append(f'(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE {test})=1')
    clauses += [f'(SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE {where} AND NON_UNIQUE=0)=3',
                f'(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE {where} AND NON_UNIQUE=0)=3',
                f'(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE {where} AND (SUB_PART IS NOT NULL OR EXPRESSION IS NOT NULL))=0']
    for f in ('event_id', 'event_sha', 'previous_sha'):
        clauses += [f'(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE {where} AND NON_UNIQUE=0 AND COLUMN_NAME={sql_value(f)}'+(" AND INDEX_NAME='PRIMARY'" if f == 'event_id' else '')+')=1']
    fk = ('FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON k.CONSTRAINT_SCHEMA=r.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME=r.CONSTRAINT_NAME AND k.TABLE_NAME=r.TABLE_NAME '
          'WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME='+sql_value(TABLE)+' AND k.REFERENCED_TABLE_NAME IS NOT NULL')
    clauses += [f'(SELECT COUNT(*) {fk})=1', f"(SELECT COUNT(*) {fk} AND k.COLUMN_NAME='previous_sha' AND k.REFERENCED_TABLE_SCHEMA=DATABASE() AND k.REFERENCED_TABLE_NAME={sql_value(TABLE)} AND k.REFERENCED_COLUMN_NAME='event_sha' AND r.DELETE_RULE IN ('NO ACTION','RESTRICT') AND r.UPDATE_RULE IN ('NO ACTION','RESTRICT'))=1"]
    checks = ('FROM information_schema.TABLE_CONSTRAINTS t JOIN information_schema.CHECK_CONSTRAINTS c ON t.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=c.CONSTRAINT_NAME '
              f"WHERE t.TABLE_SCHEMA=DATABASE() AND t.TABLE_NAME={sql_value(TABLE)} AND t.CONSTRAINT_TYPE='CHECK'")
    clauses += [f'(SELECT COUNT(*) {checks})=3']
    # MySQL canonicalizes the OCTET_LENGTH alias to LENGTH in CHECK_CLAUSE.
    for expression in ['enabledin0,1', 'lengthpayload<=200000', 'lengthevent_body<=4096']:
        clauses += [f"(SELECT COUNT(*) {checks} AND t.ENFORCED='YES' AND LOWER(REPLACE(REPLACE(REPLACE(REPLACE(c.CHECK_CLAUSE,'`',''),' ',''),'(',''),')',''))={sql_value(expression)})=1"]
    clauses += ['(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='+sql_value(TABLE)+')=0']
    return ' AND '.join('('+c+')' for c in clauses)


def row_json():
    return 'JSON_OBJECT('+','.join(sql_value(f)+','+("DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ')" if f == 'created_at' else '`'+f+'`') for f in COLS)+')'


def read_events(db, payload):
    count = int(db.run('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='+sql_value(TABLE)+';').strip())
    if not count: return {'schema_present': False, 'rows': []}
    require(count == 1 and db.run('SELECT ('+schema_guard()+');').strip() == '1', 'Incompatible activation schema; no alteration.')
    output = db.run('SET SESSION MAX_EXECUTION_TIME=2000; SELECT '+row_json()+f' FROM `{TABLE}` ORDER BY event_id LIMIT 101;')
    rows = [stage.collect.strict_json(line) for line in output.splitlines() if line]
    require(len(rows) <= 100, 'Activation history inspection limit reached.')
    prior = []
    for row in rows:
        try: body = stage.collect.strict_json(row['event_body'])
        except (ValueError, TypeError): raise CatalogError('Invalid activation history; no repair.') from None
        require(isinstance(body, dict) and set(body) == {'policy', 'previous_sha', 'enabled', 'release_sha', 'search_sha', 'reason', 'actor', 'declared_at'})
        expected = make_event({'rows': prior}, row['enabled'], payload, body['reason'], body['declared_at'])
        require({k: v for k, v in row.items() if k not in ('event_id', 'created_at')} == expected
                and type(row['event_id']) is int and row['event_id'] > (prior[-1]['event_id'] if prior else 0), 'Invalid activation history; no repair.')
        prior.append(row)
    return {'schema_present': True, 'rows': rows}


def current(db, ctx, expected, payload):
    env, state, status = app.read_current(db, ctx, expected)
    require(status == 'ALREADY_STORED_INACTIVE')
    events = read_events(db, payload)
    require(events == read_events(db, payload), 'Activation history changed during read.')
    return env, {'metadata': state, 'events': events}


def make_event(events, enabled, payload, reason, declared_at):
    rows = events['rows']
    require(type(enabled) is int and enabled in (0, 1) and (rows[-1]['enabled'] if rows else 0) != enabled, 'Requested search state already effective.')
    require(isinstance(reason, str) and 1 <= len(reason.strip()) <= 300 and not any(ord(c) < 32 for c in reason))
    require(isinstance(declared_at, str) and len(declared_at) <= 40)
    try: stamp = datetime.fromisoformat(declared_at.replace('Z', '+00:00'))
    except ValueError: raise CatalogError('Invalid declared timestamp.') from None
    require(stamp.utcoffset() is not None and stamp.utcoffset().total_seconds() == 0)
    body = {'policy': POLICY, 'previous_sha': rows[-1]['event_sha'] if rows else None, 'enabled': enabled,
            'release_sha': app.PINS['release'], 'search_sha': hashlib.sha256(payload.encode()).hexdigest(),
            'reason': reason, 'actor': 'CODEX_DECLARED_NOT_AUTHENTICATED', 'declared_at': declared_at}
    text = stage.canonical(body)
    return {**{k: body[k] for k in ['previous_sha', 'enabled', 'release_sha', 'search_sha']},
            'event_sha': hashlib.sha256(text.encode()).hexdigest(), 'event_body': text, 'payload': payload}


def transition(before, after, event):
    require(before['metadata'] == after['metadata'], 'Raw/review/catalog state changed.')
    a, b = before['events'], after['events']
    require(not a['schema_present'] or b['schema_present'])
    if a['rows'] == b['rows']: return False
    require(len(b['rows']) == len(a['rows'])+1 and b['rows'][:-1] == a['rows']
            and {k: v for k, v in b['rows'][-1].items() if k not in ('event_id', 'created_at')} == event, 'Activation state changed; no adoption.')
    return True


def execution_sql(ctx, compiled, expected, before, env, event, replay):
    base = app.execution_sql(ctx, compiled, expected, before['metadata'], env)
    prefix, transaction = base.split('-- DRAFT_METADATA_TRANSACTION', 1)
    gate = lambda c: 'INSERT INTO review_apply_gate SELECT 1 WHERE COALESCE(('+c+'),0)=0;\n'
    if not before['events']['schema_present']:
        prefix += gate('(SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='+sql_value(TABLE)+')=0')+DDL
    prefix += gate(schema_guard())
    ending = "COMMIT; SELECT '__REVIEW_CANDIDATE_COMMITTED__';\n"
    transaction = '-- DRAFT_METADATA_TRANSACTION'+transaction.split(ending, 1)[0]
    transaction += f'SELECT event_id FROM `{TABLE}` ORDER BY event_id LIMIT 101 FOR UPDATE;\n'
    transaction += stage.guard(schema_guard())+stage.guard(f'(SELECT COUNT(*) FROM `{TABLE}`)={len(before["events"]["rows"])}')
    for row in before['events']['rows']:
        transaction += stage.guard(f'(SELECT COUNT(*) FROM `{TABLE}` WHERE event_id={row["event_id"]} AND {row_json()}=CAST({sql_value(stage.canonical(row))} AS JSON))=1')
    if not replay:
        fields = list(event)
        transaction += f'INSERT INTO `{TABLE}` ('+','.join('`'+f+'`' for f in fields)+',created_at) VALUES ('+','.join(sql_value(event[f]) for f in fields)+',UTC_TIMESTAMP(6));\n'
    transaction += stage.guard(f'(SELECT COUNT(*) FROM `{TABLE}` WHERE event_sha={sql_value(event["event_sha"])} AND payload={sql_value(event["payload"])} AND event_body={sql_value(event["event_body"])} AND enabled={event["enabled"]})=1')
    sql = prefix+transaction+"COMMIT; SELECT '__SEARCH_EVENT_COMMITTED__'; SELECT RELEASE_LOCK("+sql_value(app.draft.LOCK)+"); SELECT '__SEARCH_EVENT_ACK__';\n"
    require(len(sql.encode()) <= 10_000_000, 'Activation SQL exceeds size budget.')
    return sql


def prepare(db, enabled, reason, output):
    ctx, compiled, expected = fixed_context(); payload = view_payload(ctx, compiled)
    require(RESOURCE.read_bytes() == payload.encode(), 'Runtime search resource differs from reviewed inputs.')
    env, before = current(db, ctx, expected, payload)
    event = make_event(before['events'], enabled, payload, reason, datetime.now(timezone.utc).isoformat())
    output = Path(output); output.mkdir(parents=True, exist_ok=False)
    write_json(output/'before.json', before); write_json(output/'environment.json', env); write_json(output/'event.json', event)
    (output/'execution.sql').write_text(execution_sql(ctx, compiled, expected, before, env, event, False), encoding='utf-8')
    plan = app.draft.seal({'policy': POLICY, 'base_plan_sha': BASE_PLAN_SHA, 'code_sha256': code_hashes(),
        'resource_sha256': app.draft.file_sha(RESOURCE), 'artifacts': {n: app.draft.file_sha(output/n) for n in ['before.json', 'environment.json', 'event.json', 'execution.sql']},
        'before_sha256': digest(before), 'environment_sha256': digest(env), 'event_sha': event['event_sha'], 'enabled': enabled,
        'content_updates': 0, 'runtime_entries': 0, 'provider_calls': 0, 'automatic_retry': False, 'mode': 'PREPARED_NOT_APPLIED'})
    write_json(output/'plan.json', plan)
    return plan


def load_plan(path, pin):
    require(isinstance(pin, str) and re.fullmatch('[a-f0-9]{64}', pin))
    path = Path(path); folder = path.parent; plan = stage.collect.load_json(path)
    require(plan['plan_sha256'] == pin == digest({k: v for k, v in plan.items() if k != 'plan_sha256'})
            and plan['policy'] == POLICY and plan['base_plan_sha'] == BASE_PLAN_SHA and plan['code_sha256'] == code_hashes()
            and plan['resource_sha256'] == app.draft.file_sha(RESOURCE) and plan['mode'] == 'PREPARED_NOT_APPLIED')
    require(set(plan['artifacts']) == {'before.json', 'environment.json', 'event.json', 'execution.sql'})
    for n, sha in plan['artifacts'].items(): require(app.draft.file_sha(folder/n) == sha, 'Pinned artifact changed.')
    ctx, compiled, expected = fixed_context(); payload = view_payload(ctx, compiled)
    require(RESOURCE.read_bytes() == payload.encode())
    before = stage.collect.load_json(folder/'before.json'); env = stage.collect.load_json(folder/'environment.json'); event = stage.collect.load_json(folder/'event.json')
    body = stage.collect.strict_json(event['event_body'])
    require(event == make_event(before['events'], plan['enabled'], payload, body['reason'], body['declared_at'])
            and plan['event_sha'] == event['event_sha'] and plan['before_sha256'] == digest(before) and plan['environment_sha256'] == digest(env))
    require((folder/'execution.sql').read_text(encoding='utf-8') == execution_sql(ctx, compiled, expected, before, env, event, False))
    require(plan['content_updates'] == plan['runtime_entries'] == plan['provider_calls'] == 0 and plan['automatic_retry'] is False)
    return plan, ctx, compiled, expected, payload, before, env, event


def apply(db, path, pin, output):
    plan, ctx, compiled, expected, payload, prepared, expected_env, event = load_plan(path, pin)
    env, before = current(db, ctx, expected, payload)
    require(env == expected_env, 'Bound database identity/schema differs.')
    replay = transition(prepared, before, event)
    output = Path(output); output.mkdir(parents=True, exist_ok=False)
    write_json(output/'before.json', before); write_json(output/'environment.json', env)
    write_json(output/'backup-manifest.json', {'before_sha256': digest(before), 'environment_sha256': digest(env), 'plan_sha256': pin,
        'scope': 'PUBLIC_CATALOG_MAPPING_RAW_REVIEW_SEARCH_EVENTS', 'full_database_backup': False})
    require(digest(stage.collect.load_json(output/'before.json')) == digest(before) and digest(stage.collect.load_json(output/'environment.json')) == digest(env))
    sql = execution_sql(ctx, compiled, expected, before, env, event, replay)
    (output/'execution.sql').write_text(sql, encoding='utf-8')
    result = {'state': 'STARTED_NOT_ACKNOWLEDGED', 'plan_sha256': pin, 'event_sha': event['event_sha'], 'enabled': event['enabled'],
        'automatic_retry': False, 'ddl_outside_data_transaction': True, 'content_updates': 0, 'provider_calls': 0}
    write_json(output/'result.json', result)
    try:
        ack = db.run(sql)
        require('__SEARCH_EVENT_COMMITTED__' in ack and '__SEARCH_EVENT_ACK__' in ack, 'ACK missing; inspect before explicit replay.')
        after_env, after = current(db, ctx, expected, payload)
        require(after_env == env and transition(prepared, after, event))
        if replay: require(after == before, 'Replay changed state/timestamps.')
        write_json(output/'after.json', after)
        result.update(state='REPLAY_UNCHANGED' if replay else 'SEARCH_ENABLED' if event['enabled'] else 'SEARCH_WITHDRAWN', inserted=0 if replay else 1, postcheck='MATCH')
        write_json(output/'result.json', result); return result
    except (CatalogError, OSError, ValueError, KeyError, TypeError):
        result['state'] = 'FAILED_OR_UNACKNOWLEDGED'; write_json(output/'result.json', result); raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest='command', required=True)
    view = sub.add_parser('view')
    pre = sub.add_parser('prepare'); pre.add_argument('--action', choices=['enable', 'disable'], required=True); pre.add_argument('--reason', required=True)
    run = sub.add_parser('apply'); run.add_argument('--plan', type=Path, required=True); run.add_argument('--plan-sha256', required=True)
    for p in (view, pre, run): p.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        require(not args.output.exists(), 'Existing output is never overwritten.')
        if args.command == 'view':
            ctx, compiled, _ = fixed_context(); payload = view_payload(ctx, compiled)
            args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_bytes(payload.encode()); print(hashlib.sha256(payload.encode()).hexdigest()); return 0
        if args.command == 'prepare':
            fixed_context(); result = prepare(LocalCatalog(), int(args.action == 'enable'), args.reason, args.output)
        else:
            load_plan(args.plan, args.plan_sha256); result = apply(LocalCatalog(), args.plan, args.plan_sha256, args.output)
        print(stage.canonical({k: result[k] for k in ['plan_sha256', 'enabled']} | {'state': result.get('state', result.get('mode'))})); return 0
    except (CatalogError, ValueError, KeyError, TypeError, OSError, RecursionError):
        print('Search activation failed; no automatic retry. SQL/credentials/server output withheld.', file=sys.stderr); return 1


if __name__ == '__main__': raise SystemExit(main())
