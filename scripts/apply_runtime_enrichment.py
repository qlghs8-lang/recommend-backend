"""Apply only a hash-pinned reviewed runtime batch. No TMDB/LLM/network provider.

check/apply --collection PATH --reviewed-sha256 SHA --expected-proposals N
All SQL identifiers are static. Only original NULL durations can be filled.
"""
import argparse
import copy
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys
from uuid import uuid4

from audit_runtime_coverage import analyze, read_snapshot, snapshot_hash
from catalog_pool import CatalogError, LocalCatalog, ROOT, digest, sql_value, write_json
from runtime_detail_dry_run import derive_changes, POLICY, strict_json


TABLE = 'catalog_runtime_enrichments'
DDL = f"""SET SESSION lock_wait_timeout=5;
CREATE TABLE {TABLE} (
 content_id BIGINT NOT NULL, target_field VARCHAR(32) NOT NULL, minutes INT NOT NULL,
 collection_sha256 CHAR(64) NOT NULL, proposal_sha256 CHAR(64) NOT NULL,
 evidence JSON NOT NULL, applied_at DATETIME(6) NOT NULL,
 PRIMARY KEY(content_id,target_field),
 CONSTRAINT fk_runtime_enrichment_content FOREIGN KEY(content_id) REFERENCES contents(id) ON DELETE CASCADE,
 CHECK(minutes BETWEEN 1 AND 1440),
 CHECK(target_field IN ('runtime_minutes','episode_runtime_minutes'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"""
COLS = {'content_id': ('bigint', None), 'target_field': ('varchar', 32), 'minutes': ('int', None),
        'collection_sha256': ('char', 64), 'proposal_sha256': ('char', 64),
        'evidence': ('json', None), 'applied_at': ('datetime', None)}


def require(condition, message='Runtime apply validation failed; no batch authorized.'):
    if not condition:
        raise CatalogError(message)


def schema_valid_sql():
    table = f"TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{TABLE}'"
    specs = []
    for name, (kind, size) in COLS.items():
        item = f"(COLUMN_NAME='{name}' AND DATA_TYPE='{kind}' AND IS_NULLABLE='NO'"
        if size:
            item += f' AND CHARACTER_MAXIMUM_LENGTH={size}'
        if kind == 'datetime':
            item += ' AND DATETIME_PRECISION=6'
        specs.append(item + ')')
    return (f"(SELECT COUNT(*) FROM information_schema.TABLES WHERE {table} AND ENGINE='InnoDB')=1"
            f' AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE {table})={len(COLS)}'
            f" AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE {table} AND ({' OR '.join(specs)}))={len(COLS)}"
            f" AND (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS "
            f"WHERE {table} AND INDEX_NAME='PRIMARY')='content_id,target_field'"
            f" AND (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
            'ON k.CONSTRAINT_SCHEMA=r.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME=r.CONSTRAINT_NAME AND k.TABLE_NAME=r.TABLE_NAME '
            f"WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME='{TABLE}' AND k.COLUMN_NAME='content_id' "
            "AND k.REFERENCED_TABLE_SCHEMA=DATABASE() AND k.REFERENCED_TABLE_NAME='contents' "
            "AND k.REFERENCED_COLUMN_NAME='id' AND r.DELETE_RULE='CASCADE')=1")


def schema_status(db):
    row = strict_json(db.run("SELECT JSON_OBJECT('present',(SELECT COUNT(*) FROM information_schema.TABLES "
                             f"WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{TABLE}'),'valid',({schema_valid_sql()}));").strip())
    require(row['present'] in (0, 1) and (not row['present'] or row['valid'] == 1),
            'Existing enrichment table is incompatible; no automatic alteration.')
    return bool(row['present'])


def load_review(collection_path, reviewed_hash, expected_proposals):
    require(isinstance(reviewed_hash, str) and re.fullmatch('[a-f0-9]{64}', reviewed_hash))
    require(type(expected_proposals) is int and 1 <= expected_proposals <= 40)
    collection_path = Path(collection_path)
    files = [collection_path, collection_path.with_name('proposal.json')]
    require(all(p.is_file() and p.stat().st_size <= 1_000_000 for p in files))
    collection, proposal = [strict_json(p.read_bytes()) for p in files]
    changes, skipped = derive_changes(collection)
    require(collection['state'] == 'COMPLETE' and collection['stop_code'] is None)
    require(proposal['proposal_sha256'] == reviewed_hash == digest({k: v for k, v in proposal.items() if k != 'proposal_sha256'}))
    require(proposal['gate'] == 'READY_FOR_REVIEW' and not proposal['blockers']
            and proposal['mode'] == 'REVIEW_ONLY_NO_APPLY' and proposal['policy'] == POLICY
            and proposal['collection_sha256'] == collection['collection_sha256']
            and proposal['coverage_snapshot_sha256'] == collection['coverage_snapshot_sha256']
            and proposal['job_id'] == collection['job_id']
            and proposal['proposed_changes'] == changes and proposal['skipped'] == skipped
            and len(changes) == expected_proposals)
    return {'collection': collection, 'proposal_sha256': reviewed_hash, 'changes': changes}


def expected_evidence(change):
    return {**change, 'policy': POLICY}


def read_provenance(db, review):
    if not schema_status(db):
        return []
    ids = ','.join(str(row['content_id']) for row in review['changes'])
    output = db.run(f"SELECT JSON_OBJECT('content_id',content_id,'target_field',target_field,'minutes',minutes,"
                    "'collection_sha256',collection_sha256,'proposal_sha256',proposal_sha256,'evidence',evidence,"
                    f"'applied_at',DATE_FORMAT(applied_at,'%Y-%m-%dT%H:%i:%s.%fZ')) FROM {TABLE} "
                    f'WHERE content_id IN ({ids}) ORDER BY content_id,target_field LIMIT 81;')
    rows = [strict_json(line) for line in output.splitlines() if line]
    require(len(rows) <= 80)
    return rows


def state(db, review, expected_catalog_count=468):
    snapshot = read_snapshot(db)
    require(snapshot_hash(snapshot) == snapshot_hash(read_snapshot(db)), 'Catalog changed during preflight.')
    coverage = analyze(snapshot, expected_catalog_count)
    require(not coverage['blockers'], 'Current catalog coverage gate blocks apply.')
    provenance = read_provenance(db, review)
    by_id = {row['id']: row for row in snapshot['contents']}
    stored = {(r['content_id'], r['target_field']): r for r in provenance}
    normalized = copy.deepcopy(snapshot)
    normalized_ids = {r['id']: r for r in normalized['contents']}
    statuses = []
    for change in review['changes']:
        content_id, field = change['content_id'], change['target_field']
        require(field in ('runtime_minutes', 'episode_runtime_minutes') and content_id in by_id)
        row = by_id[content_id]
        proof = stored.get((content_id, field))
        if row[field] is None and proof is None:
            statuses.append('PENDING')
        else:
            require(proof is not None and row[field] == change['proposed_minutes']
                    and proof['minutes'] == change['proposed_minutes']
                    and proof['collection_sha256'] == review['collection']['collection_sha256']
                    and proof['proposal_sha256'] == review['proposal_sha256']
                    and proof['evidence'] == expected_evidence(change),
                    'Existing duration/provenance conflicts; no overwrite or adoption.')
            statuses.append('ALREADY_APPLIED')
            normalized_ids[content_id][field] = None
    require(len(set(statuses)) == 1, 'Partial previous application requires review; no automatic repair.')
    require(len(provenance) == (0 if statuses[0] == 'PENDING' else len(review['changes'])),
            'Unexpected enrichment provenance requires review.')
    require(snapshot_hash(normalized) == review['collection']['coverage_snapshot_sha256'],
            'Catalog/mapping/schema differs from the reviewed baseline.')
    return {'status': statuses[0], 'snapshot': snapshot, 'provenance': provenance, 'coverage': coverage['coverage']}


def guard(condition):
    # A duplicate primary key is an error even without strict SQL mode/CHECK support.
    return 'INSERT INTO runtime_gate SELECT 1 WHERE COALESCE((' + condition + '),0)=0;\n'


def insert_rows(table, fields, rows):
    if not rows:
        return ''
    return ('INSERT INTO ' + table + ' (' + ','.join('`' + f + '`' for f in fields) + ') VALUES\n'
            + ',\n'.join('(' + ','.join(sql_value(row.get(f)) for f in fields) + ')' for row in rows) + ';\n')


def transaction_sql(review, before):
    snapshot = before['snapshot']
    sql = """-- RUNTIME_APPLY_TRANSACTION
SET SESSION innodb_lock_wait_timeout=5;
SET SESSION lock_wait_timeout=5;
SET SESSION MAX_EXECUTION_TIME=8000;
CREATE TEMPORARY TABLE runtime_gate(ok INT PRIMARY KEY);
INSERT INTO runtime_gate VALUES(1);
CREATE TEMPORARY TABLE runtime_expected_contents(id BIGINT PRIMARY KEY,type VARCHAR(10),runtime_minutes INT,episode_runtime_minutes INT);
CREATE TEMPORARY TABLE runtime_expected_sources(media_type VARCHAR(10),tmdb_id BIGINT,content_id BIGINT);
"""
    sql += insert_rows('runtime_expected_contents', ['id', 'type', 'runtime_minutes', 'episode_runtime_minutes'], snapshot['contents'])
    sql += insert_rows('runtime_expected_sources', ['media_type', 'tmdb_id', 'content_id'], snapshot['sources'])
    sql += f"""SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION;
SELECT id FROM contents ORDER BY id LIMIT 5001 FOR UPDATE;
SELECT content_id FROM catalog_import_sources ORDER BY media_type,tmdb_id LIMIT 5001 FOR UPDATE;
SELECT content_id FROM {TABLE} ORDER BY content_id,target_field LIMIT 5001 FOR UPDATE;
"""
    sql += guard(schema_valid_sql())
    # Locking reads happen before the first consistent read, so this view includes
    # commits that raced with preflight, while next-key locks prevent later edits.
    sql += guard(f'(SELECT COUNT(*) FROM contents)={len(snapshot["contents"])} AND '
                 f'(SELECT COUNT(*) FROM catalog_import_sources)={len(snapshot["sources"])} AND '
                 'NOT EXISTS(SELECT 1 FROM contents c LEFT JOIN runtime_expected_contents e ON c.id=e.id '
                 'WHERE e.id IS NULL OR NOT (BINARY c.type <=> BINARY e.type) '
                 'OR NOT (c.runtime_minutes <=> e.runtime_minutes) OR NOT (c.episode_runtime_minutes <=> e.episode_runtime_minutes)) AND '
                 'NOT EXISTS(SELECT 1 FROM catalog_import_sources s LEFT JOIN runtime_expected_sources e '
                 'ON BINARY s.media_type=BINARY e.media_type AND s.tmdb_id=e.tmdb_id AND s.content_id=e.content_id '
                 'WHERE e.content_id IS NULL)')
    # Recheck column definitions after table metadata locks have been acquired.
    for col in snapshot['schema']['columns']:
        sql += guard("(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME="
                     + sql_value(col['table']) + ' AND COLUMN_NAME=' + sql_value(col['name'])
                     + ' AND DATA_TYPE=' + sql_value(col['data_type']) + ' AND IS_NULLABLE=' + sql_value(col['nullable']) + ')=1')
    for table in snapshot['schema']['tables']:
        sql += guard("(SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME="
                     + sql_value(table['table']) + " AND ENGINE='InnoDB')=1")
    ids = ','.join(str(c['content_id']) for c in review['changes'])
    sql += guard(f'(SELECT COUNT(*) FROM {TABLE} WHERE content_id IN ({ids}))={len(before["provenance"])}')
    for proof in before['provenance']:
        sql += guard(f'(SELECT COUNT(*) FROM {TABLE} WHERE content_id={proof["content_id"]} AND BINARY target_field='
                     + sql_value(proof['target_field']) + f' AND minutes={proof["minutes"]} AND BINARY collection_sha256='
                     + sql_value(proof['collection_sha256']) + ' AND BINARY proposal_sha256=' + sql_value(proof['proposal_sha256'])
                     + ' AND evidence=CAST(' + sql_value(json.dumps(proof['evidence'], sort_keys=True)) + ' AS JSON)'
                     + " AND DATE_FORMAT(applied_at,'%Y-%m-%dT%H:%i:%s.%fZ')=" + sql_value(proof['applied_at']) + ')=1')
    if before['status'] == 'PENDING':
        for change in review['changes']:
            field, content_id, minutes = change['target_field'], change['content_id'], change['proposed_minutes']
            sql += f'UPDATE contents SET `{field}`={minutes} WHERE id={content_id} AND `{field}` IS NULL;\n'
            sql += guard('ROW_COUNT()=1')
            proof = {'content_id': content_id, 'target_field': field, 'minutes': minutes,
                     'collection_sha256': review['collection']['collection_sha256'],
                     'proposal_sha256': review['proposal_sha256'],
                     'evidence': json.dumps(expected_evidence(change), sort_keys=True, separators=(',', ':'))}
            fields = list(proof)
            sql += ('INSERT INTO ' + TABLE + ' (' + ','.join(fields) + ',applied_at) VALUES ('
                    + ','.join(sql_value(proof[f]) for f in fields) + ',UTC_TIMESTAMP(6));\n')
    return sql + "COMMIT; SELECT '__RUNTIME_COMMITTED__';\n"


def apply_review(db, review, output, expected_catalog_count=468):
    before = state(db, review, expected_catalog_count)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    write_json(output / 'before.json', before)
    backup_hash = digest(strict_json((output / 'before.json').read_bytes()))
    write_json(output / 'backup-manifest.json', {'before_sha256': backup_hash,
               'collection_sha256': review['collection']['collection_sha256'], 'proposal_sha256': review['proposal_sha256']})
    require(backup_hash == digest(before), 'Pre-write backup verification failed.')
    created = False
    if not schema_status(db):
        db.run(DDL)
        created = True
    require(schema_status(db), 'Enrichment table creation not verified.')
    sql = transaction_sql(review, before)
    (output / 'transaction.sql').write_text(sql, encoding='utf-8')
    write_json(output / 'schema.json', {'created': created, 'ddl_separate_from_data_transaction': True})
    try:
        result = db.run(sql)
        require('__RUNTIME_COMMITTED__' in result, 'Commit acknowledgement missing; inspect retained evidence.')
    except CatalogError:
        write_json(output / 'result.json', {'status': 'FAILED_OR_UNACKNOWLEDGED', 'inspect_before_retry': True,
                   'schema_created': created, 'automatic_retry': False})
        raise
    after = state(db, review, expected_catalog_count)
    require(after['status'] == 'ALREADY_APPLIED', 'Post-commit verification needs review; do not overwrite.')
    write_json(output / 'after.json', after)
    report = {'status': 'APPLIED' if before['status'] == 'PENDING' else 'ALREADY_APPLIED',
              'updated': len(review['changes']) if before['status'] == 'PENDING' else 0,
              'verified_provenance': len(after['provenance']), 'schema_created': created,
              'catalog_total': len(after['snapshot']['contents']), 'coverage': after['coverage'],
              'proposal_sha256': review['proposal_sha256'], 'provider_calls': 0, 'backup': str(output)}
    write_json(output / 'result.json', report)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('check', 'apply'))
    parser.add_argument('--collection', required=True, type=Path)
    parser.add_argument('--reviewed-sha256', required=True)
    parser.add_argument('--expected-proposals', required=True, type=int)
    args = parser.parse_args(argv)
    try:
        review = load_review(args.collection, args.reviewed_sha256, args.expected_proposals)
        db = LocalCatalog()
        if args.command == 'check':
            current = state(db, review)
            print(json.dumps({'status': current['status'], 'proposals': len(review['changes']),
                              'coverage': current['coverage'], 'provider_calls': 0}))
        else:
            output = ROOT / 'target/runtime-apply' / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ') + '-' + uuid4().hex[:8])
            print(json.dumps(apply_review(db, review, output)))
        return 0
    except (CatalogError, ValueError, KeyError, TypeError, OSError, RecursionError):
        print('Runtime apply failed; SQL, credentials and database details withheld. Inspect retained task evidence.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
