"""Pinned, append-only INACTIVE review storage. No activation or Content writes.

prepare --collection PATH --decisions PATH --output NEW_DIR
apply --plan PATH --plan-sha256 SHA --output NEW_DIR
DDL is separate from the atomic data transaction. Failed attempts are never retried automatically.
"""
import argparse
from pathlib import Path
import re
import shutil
import sys

import apply_metadata_drafts as draft
import metadata_review_release as review
from catalog_pool import CatalogError, LocalCatalog, ROOT, digest, sql_value, write_json

stage = review.stage
require = stage.require
POLICY = 'metadata-inactive-review-apply-v1'
PINS = {
    'decisions': 'e0ea225a5516fb7c313245e272b2a0ea5530042bec53974d7fc497e7ce84168d',
    'parent': 'e05c086a56a9f414271e764f584038c38cae531f43bf7fb77cad3644fd04b6c2',
    'version': 'a98e2fafdf7c5bea09a9a90758815eceae01eab4cce789ff5766fca004ed8b21',
    'release': 'fa075eb4a7d34cf575eb7013d46bce3ad32cfaed3fcc5f8c3cd0c3d37a648bfb',
    'selected': 101,
}
CODE_FILES = ('apply_metadata_review_release.py', 'metadata_review_release.py', *draft.CODE_FILES)
INPUTS = (*draft.INPUT_FILES, 'field-review-plan.json', 'decisions.json')
ARTIFACTS = ('before.json', 'environment.json', 'migration.sql', 'execution.sql')


def code_hashes():
    return {n: draft.file_sha(ROOT/'scripts'/n) for n in CODE_FILES}


def compile_fixed(collection, decisions, field_plan=review.REVIEW_PLAN):
    context = review.load_context(collection, field_plan)
    values = stage.collect.load_json(decisions, 8_000_000)
    parent = review.compile_review(context, review.template(context))
    compiled = review.compile_review(context, values)
    require(digest(values) == PINS['decisions'] and parent['version']['version_sha'] == PINS['parent']
            and compiled['version']['parent_version_sha'] == PINS['parent']
            and compiled['version']['version_sha'] == PINS['version']
            and compiled['candidate']['release_sha'] == PINS['release']
            and len(compiled['candidate']['entries']) == PINS['selected'], 'Only the fixed reviewed candidate is supported.')
    a, b = review.db_rows(parent), review.db_rows(compiled)
    return context, compiled, [a[i]+b[i] for i in range(3)]


def schema_guard():
    """Exact columns, unique keys, FK rules, checks, engine and absence of triggers."""
    clauses = []
    for i, table in enumerate(review.TABLES):
        where = 'TABLE_SCHEMA=DATABASE() AND TABLE_NAME='+sql_value(table)
        clauses += [f"(SELECT COUNT(*) FROM information_schema.TABLES WHERE {where} AND ENGINE='InnoDB' AND TABLE_TYPE='BASE TABLE' AND TABLE_COLLATION='utf8mb4_bin')=1"]
        columns = review.COLS[i] | {'created_at': 'DATETIME(6)'}
        clauses += [f'(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE {where})={len(columns)}']
        for name, kind in columns.items():
            typ = kind.lower()
            test = where+' AND COLUMN_NAME='+sql_value(name)+' AND COLUMN_TYPE='+sql_value(typ)
            test += ' AND IS_NULLABLE='+sql_value('YES' if name == 'parent_sha' else 'NO')
            test += " AND COLUMN_DEFAULT IS NULL AND EXTRA='' AND GENERATION_EXPRESSION=''"
            if typ.startswith('char'): test += " AND COLLATION_NAME='utf8mb4_bin'"
            clauses += [f'(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE {test})=1']
        unique = [review.KEYS[i]] + (['version_sha,row_sha'] if i == 1 else ['version_sha'] if i == 2 else [])
        clauses += [f'(SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE {where} AND NON_UNIQUE=0)={len(unique)}']
        clauses += [f'(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE {where} AND NON_UNIQUE=0 AND (SUB_PART IS NOT NULL OR EXPRESSION IS NOT NULL))=0']
        clauses += [f"(SELECT COUNT(*) FROM information_schema.STATISTICS WHERE {where} AND INDEX_NAME='PRIMARY' AND COLUMN_NAME={sql_value(review.KEYS[i])})=1"]
        for fields in unique:
            clauses += ['(SELECT COUNT(*) FROM (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) fields '
                        f'FROM information_schema.STATISTICS WHERE {where} AND NON_UNIQUE=0 GROUP BY INDEX_NAME) review_indexes '
                        'WHERE BINARY fields='+sql_value(fields)+')=1']
        fk = ('FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r '
              'ON k.CONSTRAINT_SCHEMA=r.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME=r.CONSTRAINT_NAME AND k.TABLE_NAME=r.TABLE_NAME '
              'WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME='+sql_value(table)+' AND k.REFERENCED_TABLE_NAME IS NOT NULL')
        clauses += [f'(SELECT COUNT(*) {fk})=1', f'(SELECT COUNT(*) {fk} AND k.COLUMN_NAME='+sql_value('parent_sha' if i == 0 else 'version_sha')
                    +' AND k.REFERENCED_TABLE_SCHEMA=DATABASE() AND k.REFERENCED_TABLE_NAME='+sql_value(review.TABLES[0])
                    +" AND k.REFERENCED_COLUMN_NAME='version_sha' AND r.DELETE_RULE IN ('NO ACTION','RESTRICT') AND r.UPDATE_RULE IN ('NO ACTION','RESTRICT'))=1"]
        checks = ('FROM information_schema.TABLE_CONSTRAINTS t JOIN information_schema.CHECK_CONSTRAINTS c '
                  'ON t.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=c.CONSTRAINT_NAME '
                  f"WHERE t.TABLE_SCHEMA=DATABASE() AND t.TABLE_NAME={sql_value(table)} AND t.CONSTRAINT_TYPE='CHECK'")
        clauses += [f'(SELECT COUNT(*) {checks})={1 if i == 2 else 0}']
        if i == 2:
            clauses += [f"(SELECT COUNT(*) {checks} AND t.ENFORCED='YES' AND REPLACE(REPLACE(REPLACE(REPLACE(c.CHECK_CLAUSE,'`',''),' ',''),'(',''),')','')='active=0')=1"]
        clauses += ['(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='+sql_value(table)+')=0']
    return ' AND '.join('('+c+')' for c in clauses)


def schema_present(db):
    names = ','.join(map(sql_value, review.TABLES))
    count = int(db.run(f'SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ({names});').strip())
    require(count in (0, 3), 'Partial review schema; no repair.')
    if count: require(db.run('SELECT ('+schema_guard()+');').strip() == '1', 'Review schema incompatible; no alteration.')
    return bool(count)


def read_review_state(db):
    present = schema_present(db)
    rows = [[], [], []]
    if present:
        sql = 'SET SESSION MAX_EXECUTION_TIME=8000; SET TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;\n'
        for i, table in enumerate(review.TABLES):
            sql += f"SELECT '__REVIEW_{i}__'; SELECT {review.row_json(i)} FROM `{table}` ORDER BY `{review.KEYS[i]}` LIMIT 5001;\n"
        output = db.run(sql+'ROLLBACK;\n')
        require(output.startswith('__REVIEW_0__\n'))
        parts = re.split(r'__REVIEW_[012]__\n', output)[1:]
        require(len(parts) == 3)
        rows = [[stage.collect.strict_json(line) for line in part.splitlines() if line] for part in parts]
        require(all(len(r) <= 5000 for r in rows))
    require(schema_present(db) == present, 'Review schema changed during read.')
    return {'schema_present': present, 'rows': rows}


def clean(rows):
    return [sorted(({k: v for k, v in r.items() if k != 'created_at'} for r in rows[i]), key=lambda r: r[review.KEYS[i]]) for i in range(3)]


def status_of(context, expected, state):
    review.validate_history(context, state['rows'])
    if not any(state['rows']): return 'PENDING_INACTIVE_STORAGE'
    require(clean(state['rows']) == clean(expected), 'Partial, foreign or conflicting review history; no adoption or repair.')
    return 'ALREADY_STORED_INACTIVE'


def read_current(db, context, expected):
    env, raw, status = draft.read_current(db, context['bundle'], context['projected'])
    require(status == 'ALREADY_STORED_UNREVIEWED', 'Pinned raw batch must already exist.')
    reviews = read_review_state(db)
    require(reviews == read_review_state(db) and raw == stage.read_state(db)
            and env == draft.environment(db), 'Target changed during preflight.')
    return env, {'raw': raw, 'reviews': reviews}, status_of(context, expected, reviews)


def migration_sql():
    return '-- Inactive review migration; DDL is outside the data transaction.\n'+'\n'.join(review.schema_sql().splitlines()[1:])+'\n'


def execution_sql(context, compiled, expected, current, env):
    status = status_of(context, expected, current['reviews'])
    gate = lambda c: 'INSERT INTO review_apply_gate SELECT 1 WHERE COALESCE(('+c+'),0)=0;\n'
    sql = ('-- PINNED_INACTIVE_REVIEW_APPLICATION\nSET SESSION lock_wait_timeout=5; SET SESSION innodb_lock_wait_timeout=5;\n'
           'CREATE TEMPORARY TABLE review_apply_gate(ok INT PRIMARY KEY); INSERT INTO review_apply_gate VALUES(1);\n')
    # Shares the raw-store advisory lock on the SAME connection across DDL/DML.
    sql += gate('GET_LOCK('+sql_value(draft.LOCK)+',5)=1')+gate(draft.environment_guard(env))
    if not current['reviews']['schema_present']:
        sql += gate('(SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('+','.join(map(sql_value, review.TABLES))+'))=0')
        sql += migration_sql()
    sql += gate(schema_guard())
    new_rows = expected if status == 'PENDING_INACTIVE_STORAGE' else [[], [], []]
    transaction = review.scratch_transaction(context, compiled, current['raw'], current['reviews']['rows'], new_rows)
    ending = "COMMIT; SELECT '__REVIEW_CANDIDATE_COMMITTED__';\n"
    require(transaction.endswith(ending))
    transaction = transaction[:-len(ending)]
    transaction += stage.guard(draft.environment_guard(env))+stage.guard(schema_guard())
    # Check every inserted payload, not just counts, before commit.
    for i, rows in enumerate(new_rows):
        for row in rows:
            match = ' AND '.join('`'+k+'`='+('CAST('+sql_value(stage.canonical(v))+' AS JSON)' if k == 'payload' else sql_value(v)) for k, v in row.items() if v is not None)
            if 'parent_sha' in row and row['parent_sha'] is None: match += ' AND parent_sha IS NULL'
            transaction += stage.guard(f'(SELECT COUNT(*) FROM `{review.TABLES[i]}` WHERE {match} AND created_at=@review_created)=1')
    sql += transaction+ending+'SELECT RELEASE_LOCK('+sql_value(draft.LOCK)+"); SELECT '__INACTIVE_REVIEW_APPLICATION_ACK__';\n"
    require(len(sql.encode()) <= 10_000_000, 'Execution exceeds SQL size budget.')
    return sql


def allowed_transition(context, expected, prepared, current):
    require(current['raw'] == prepared['raw'], 'Raw drafts/catalog/mappings changed since preparation.')
    before, after = prepared['reviews'], current['reviews']
    if before == after: return
    require(not before['schema_present'] or after['schema_present'], 'Prepared review schema disappeared.')
    if before['rows'] == after['rows']: return  # Complete empty DDL may survive a failed attempt.
    require(not any(before['rows']) and status_of(context, expected, after) == 'ALREADY_STORED_INACTIVE', 'Prepared review history changed.')


def prepare_plan(db, collection, decisions, output):
    context, compiled, expected = compile_fixed(collection, decisions)
    env, before, status = read_current(db, context, expected)
    output = Path(output); output.mkdir(parents=True, exist_ok=False)
    inputs = output/'input'; inputs.mkdir()
    for name in draft.INPUT_FILES: shutil.copyfile(Path(collection).with_name(name), inputs/name)
    shutil.copyfile(review.REVIEW_PLAN, inputs/'field-review-plan.json')
    shutil.copyfile(decisions, inputs/'decisions.json')
    copied = compile_fixed(inputs/'collection.json', inputs/'decisions.json', inputs/'field-review-plan.json')
    require(copied[2] == expected, 'Copied input differs.')
    write_json(output/'before.json', before); write_json(output/'environment.json', env)
    (output/'migration.sql').write_text(migration_sql(), encoding='utf-8')
    (output/'execution.sql').write_text(execution_sql(context, compiled, expected, before, env), encoding='utf-8')
    plan = draft.seal({'policy': POLICY, 'mode': 'PREPARED_NOT_APPLIED', 'pins': PINS,
        'code_sha256': code_hashes(), 'inputs': {n: draft.file_sha(inputs/n) for n in INPUTS},
        'artifacts': {n: draft.file_sha(output/n) for n in ARTIFACTS}, 'target': env['identity'],
        'before_sha256': digest(before), 'environment_sha256': digest(env), 'prepared_status': status,
        'counts': dict(zip(review.TABLES, map(len, expected))), 'active': False, 'content_updates': 0,
        'provider_calls': 0, 'automatic_retry': False})
    write_json(output/'plan.json', plan)
    return plan


def load_plan(path, expected_sha):
    require(isinstance(expected_sha, str) and re.fullmatch('[a-f0-9]{64}', expected_sha), 'Explicit plan hash required.')
    path = Path(path); folder = path.parent; plan = stage.collect.load_json(path)
    require(plan['plan_sha256'] == expected_sha == digest({k: v for k, v in plan.items() if k != 'plan_sha256'}))
    require(plan['policy'] == POLICY and plan['mode'] == 'PREPARED_NOT_APPLIED' and plan['pins'] == PINS and plan['code_sha256'] == code_hashes(), 'Plan policy/code/pins changed.')
    require(set(plan['inputs']) == set(INPUTS) and set(plan['artifacts']) == set(ARTIFACTS))
    for n in INPUTS: require(draft.file_sha(folder/'input'/n) == plan['inputs'][n], 'Input changed.')
    for n in ARTIFACTS: require(draft.file_sha(folder/n) == plan['artifacts'][n], 'Artifact changed.')
    context, compiled, expected = compile_fixed(folder/'input/collection.json', folder/'input/decisions.json', folder/'input/field-review-plan.json')
    before = stage.collect.load_json(folder/'before.json'); env = stage.collect.load_json(folder/'environment.json')
    require(plan['target'] == env['identity'] and plan['before_sha256'] == digest(before) and plan['environment_sha256'] == digest(env))
    require(stage.check_state(context['bundle'], context['projected'], before['raw']) == 'ALREADY_STORED_UNREVIEWED')
    require(plan['prepared_status'] == status_of(context, expected, before['reviews']))
    require((folder/'migration.sql').read_text(encoding='utf-8') == migration_sql())
    require((folder/'execution.sql').read_text(encoding='utf-8') == execution_sql(context, compiled, expected, before, env))
    require(plan['counts'] == dict(zip(review.TABLES, map(len, expected))) and plan['active'] is False
            and plan['content_updates'] == 0 and plan['provider_calls'] == 0 and plan['automatic_retry'] is False)
    return plan, context, compiled, expected, before, env


def apply_plan(db, path, expected_sha, output):
    plan, context, compiled, expected, prepared, expected_env = load_plan(path, expected_sha)
    env, current, status = read_current(db, context, expected)
    require(env == expected_env, 'Target identity/base schema changed.')
    allowed_transition(context, expected, prepared, current)
    output = Path(output); output.mkdir(parents=True, exist_ok=False)
    write_json(output/'before.json', current); write_json(output/'environment.json', env)
    backup = {'plan_sha256': expected_sha, 'before_sha256': digest(current), 'environment_sha256': digest(env),
              'scope': 'PUBLIC_8_MAPPING_3_RAW_6_REVIEW_3_TABLES', 'full_database_backup': False}
    write_json(output/'backup-manifest.json', backup)
    require(digest(stage.collect.load_json(output/'before.json')) == backup['before_sha256']
            and digest(stage.collect.load_json(output/'environment.json')) == backup['environment_sha256'])
    sql = execution_sql(context, compiled, expected, current, env)
    (output/'execution.sql').write_text(sql, encoding='utf-8')
    result = {'state': 'STARTED_NOT_ACKNOWLEDGED', 'plan_sha256': expected_sha, 'active': False,
              'content_updates': 0, 'automatic_retry': False, 'ddl_outside_data_transaction': True,
              'schema_may_exist': True, 'application_db_writes': 'UNKNOWN_UNTIL_POSTCHECK'}
    write_json(output/'result.json', result)
    try:
        ack = db.run(sql)
        require('__REVIEW_CANDIDATE_COMMITTED__' in ack and '__INACTIVE_REVIEW_APPLICATION_ACK__' in ack, 'Acknowledgement missing; inspect and explicitly replay.')
        after_env, after, after_status = read_current(db, context, expected)
        require(after_env == env and after_status == 'ALREADY_STORED_INACTIVE')
        allowed_transition(context, expected, current, after)
        if status == 'ALREADY_STORED_INACTIVE': require(current == after, 'Replay changed timestamps/data.')
        write_json(output/'after.json', after)
        inserted = sum(map(len, expected)) if status == 'PENDING_INACTIVE_STORAGE' else 0
        result.update(state='STORED_INACTIVE' if inserted else 'REPLAY_UNCHANGED', inserted=inserted,
                      counts=plan['counts'], release_sha=PINS['release'], selected=PINS['selected'],
                      postcheck='MATCH', application_db_writes=type(db) is LocalCatalog and bool(inserted))
        write_json(output/'result.json', result)
        return result
    except (CatalogError, OSError, ValueError, KeyError, TypeError):
        result['state'] = 'FAILED_OR_UNACKNOWLEDGED'; write_json(output/'result.json', result)
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest='command', required=True)
    prepare = sub.add_parser('prepare'); prepare.add_argument('--collection', type=Path, required=True); prepare.add_argument('--decisions', type=Path, required=True)
    apply = sub.add_parser('apply'); apply.add_argument('--plan', type=Path, required=True); apply.add_argument('--plan-sha256', required=True)
    for command in (prepare, apply): command.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        require(not args.output.exists(), 'Existing output will not be overwritten.')
        if args.command == 'prepare':
            compile_fixed(args.collection, args.decisions)
            result = prepare_plan(LocalCatalog(), args.collection, args.decisions, args.output)
        else:
            load_plan(args.plan, args.plan_sha256)  # Validate before even initializing adapter.
            result = apply_plan(LocalCatalog(), args.plan, args.plan_sha256, args.output)
        print(stage.canonical({k: result[k] for k in ('plan_sha256', 'counts')} | {'state': result.get('state', result.get('mode')), 'active': False}))
        return 0
    except (CatalogError, ValueError, KeyError, TypeError, OSError, RecursionError):
        print('Inactive review operation failed; no automatic retry. SQL/credentials/server output withheld.', file=sys.stderr)
        return 1


if __name__ == '__main__': raise SystemExit(main())
