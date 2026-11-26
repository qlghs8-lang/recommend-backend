"""Plan immutable UNREVIEWED metadata storage; CLI never writes to application DB.

plan --collection FILE --collection-sha256 SHA [--live] [--output NEW_DIR]
Writes are implemented only for a freshly owned scratch schema in opt-in tests.
Durations remain candidates: this module never updates contents or legacy provenance.
"""
import argparse
from collections import defaultdict
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys
from uuid import uuid4

import metadata_enrichment_dry_run as collect
from audit_recommendation_landscape import FIELDS
from catalog_pool import CatalogError, LocalCatalog, ROOT, digest, sql_value, write_json

POLICY = 'metadata-unreviewed-storage-v1'
PREFIX = 'recommend_metadata_stage_test_'
COLS = {
    'catalog_metadata_batches': {'batch_sha': 'CHAR(64)', 'projection_sha': 'CHAR(64)', 'manifest': 'JSON'},
    'catalog_metadata_sources': {'row_sha': 'CHAR(64)', 'batch_sha': 'CHAR(64)', 'content_id': 'BIGINT',
                                 'source_kind': 'VARCHAR(32)', 'payload': 'JSON'},
}
COMMON = {'row_sha': 'CHAR(64)', 'batch_sha': 'CHAR(64)', 'source_sha': 'CHAR(64)', 'content_id': 'BIGINT'}
for table, extra in (
        ('catalog_content_keywords', {'keyword_id': 'BIGINT', 'label': 'VARCHAR(128)'}),
        ('catalog_content_aliases', {'alias_kind': 'VARCHAR(32)', 'normalized_title': 'VARCHAR(500)'}),
        ('catalog_metadata_field_reviews', {'field_name': 'VARCHAR(32)', 'decision': 'VARCHAR(48)'}),
        ('catalog_metadata_runtime_candidates', {'target_field': 'VARCHAR(32)', 'minutes': 'INT'})):
    COLS[table] = COMMON | extra | {'approved': 'TINYINT', 'active': 'TINYINT', 'payload': 'JSON'}
TABLES = tuple(COLS)
for columns in COLS.values(): columns['created_at'] = 'DATETIME(6)'


def require(condition, message='Metadata storage validation failed; no write authorized.'):
    if not condition: raise CatalogError(message)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def pk(table): return 'batch_sha' if table == TABLES[0] else 'row_sha'


def foreign_keys(table):
    if table == TABLES[0]: return []
    result = [('batch_sha', TABLES[0], 'batch_sha'), ('content_id', 'contents', 'id')]
    if table != TABLES[1]: result.append(('source_sha', TABLES[1], 'row_sha'))
    return result


def schema_sql():
    statements = ['-- UNAPPLIED DRAFT SCHEMA. CLI does not execute DDL. DDL is separate from the data transaction.',
                  'SET SESSION lock_wait_timeout=5;']
    for number, table in enumerate(TABLES):
        columns = [f' `{name}` {kind} NOT NULL' for name, kind in COLS[table].items()]
        columns.append(f' PRIMARY KEY (`{pk(table)}`)')
        for index, (field, parent, parent_field) in enumerate(foreign_keys(table)):
            columns.append(f' CONSTRAINT fk_md_{number}_{index} FOREIGN KEY (`{field}`) REFERENCES `{parent}` (`{parent_field}`) ON DELETE CASCADE')
        if table == 'catalog_content_keywords': columns.append(' UNIQUE KEY uq_md_keyword_source (`source_sha`,`keyword_id`)')
        if table == 'catalog_metadata_field_reviews': columns.append(' UNIQUE KEY uq_md_field_source (`source_sha`,`field_name`)')
        if table == 'catalog_metadata_runtime_candidates':
            columns += [' UNIQUE KEY uq_md_time_source (`source_sha`,`target_field`)',
                        " CHECK (minutes BETWEEN 1 AND 1440 AND target_field IN ('runtime_minutes','episode_runtime_minutes'))"]
        if 'approved' in COLS[table]: columns.append(' CHECK (approved=0 AND active=0)')
        statements.append(f'CREATE TABLE `{table}` (\n' + ',\n'.join(columns) + '\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;')
    return '\n'.join(statements) + '\n'


def load_bundle(path, expected_hash):
    require(isinstance(expected_hash, str) and re.fullmatch('[a-f0-9]{64}', expected_hash))
    path = Path(path)
    return {'collection': collect.load_json(path), 'review': collect.load_json(path.with_name('review.json')),
            'baseline': collect.snapshots(path.parent), 'expected_hash': expected_hash}


def validated(bundle):
    saved, baseline, contract = bundle['collection'], bundle['baseline'], collect.contract()
    collect.check_catalog(contract, *baseline)
    collect.validate_collection(saved, contract)
    require(saved['collection_sha256'] == bundle['expected_hash'] and saved['source_kind'] == 'TMDB_HTTP'
            and saved['state'] == 'COMPLETE' and saved['postcheck'] == 'MATCH')
    replay = collect.review(saved, contract, baseline, baseline, True)
    require(replay['gate'] == 'READY_FOR_MANUAL_REVIEW' and digest(replay) == digest(bundle['review']),
            'Original unapproved review/collection pin differs; no implicit approval.')
    return saved, baseline, replay


def projection(bundle):
    saved, baseline, review = validated(bundle)
    batch = saved['collection_sha256']
    result = {table: [] for table in TABLES}
    catalog = {r['id']: r for r in baseline[0]['rows']}
    review_items = {(r['content_id'], r['kind']): r for r in review['items']}
    def add(table, values):
        values = {'batch_sha': batch} | values
        if table != TABLES[1]: values = values | {'approved': 0, 'active': 0}
        row = {'row_sha': digest({'policy': POLICY, 'table': table, 'values': values})} | values
        require(set(row) == set(COLS[table]) - {'created_at'})
        result[table].append(row)
        return row['row_sha']
    for record in saved['records']:
        ident, media, kind, data = record['content_id'], record['media_type'], record['kind'], record['data']
        source = {key: record[key] for key in ('tmdb_id','media_type','endpoint','requested_locale','fetched_at_utc','response_sha256','data')}
        source.update(provider='TMDB', expected_public_row_sha256=digest(catalog[ident]), interpretation='UNTRUSTED_PROVIDER_DATA')
        source_sha = add(TABLES[1], {'content_id': ident, 'source_kind': kind, 'payload': source})
        common = {'source_sha': source_sha, 'content_id': ident}
        item = review_items[(ident, kind)]
        def field_review(name, decision, value):
            add('catalog_metadata_field_reviews', common | {'field_name': name, 'decision': decision,
                'payload': {'source_field': name, 'source_value': value, 'review_status': 'UNREVIEWED',
                            'approved': False, 'semantic_centrality': 'UNJUDGED'}})
        def alias(alias_kind, normalized, forms, country, title_type, ordinals, language):
            add('catalog_content_aliases', common | {'alias_kind': alias_kind, 'normalized_title': normalized,
                'payload': {'raw_forms': forms, 'raw_ordinals': ordinals, 'country': country, 'title_type': title_type,
                            'language': language, 'review_status': 'UNREVIEWED', 'approved': False}})
        if kind == 'detail':
            fields = data['fields']
            duration = 'runtime' if media == 'MOVIE' else 'episode_run_time'
            for name, value in fields.items():
                if name == duration:
                    duration_result = item['fields'][name]
                    field_review(name, duration_result['state'] + ':' + duration_result['reason']
                                 if len(duration_result['state'] + ':' + duration_result['reason']) <= 48 else duration_result['state'],
                                 duration_result)
                    if duration_result['state'] == 'PROPOSE_NULL_FILL':
                        require(duration_result['existing'] is None and type(duration_result['proposed']) is int)
                        add('catalog_metadata_runtime_candidates', common | {
                            'target_field': 'runtime_minutes' if media == 'MOVIE' else 'episode_runtime_minutes',
                            'minutes': duration_result['proposed'],
                            'payload': {'expected_existing': None, 'source_field': name, 'reason': duration_result['reason'],
                                        'source_value': value, 'review_status': 'UNREVIEWED', 'approved': False}})
                else:
                    action = item['fields'][name].get('review_action', value['state'])
                    field_review(name, action, value)
                    if name in ('title','name','original_title','original_name') and value['state'] == 'VALUE':
                        original = name.startswith('original_')
                        language = fields['original_language'] if original else {'state': 'UNKNOWN', 'value': None}
                        alias('ORIGINAL_TITLE' if original else 'OFFICIAL_TITLE', collect.title_key(value['value']),
                              [value['value']], {'state': 'UNKNOWN', 'value': None}, {'state': 'UNKNOWN','value': None},
                              [name], {'provider_original_language': language, 'independently_verified': False})
        elif kind == 'keywords':
            field_review(data['source_field'], 'CANDIDATE_RAW_RELATIONS' if data['state'] == 'VALUE' else data['state'], data)
            grouped = defaultdict(list)
            for ordinal, entry in enumerate(data['entries'] or []): grouped[entry['id']].append((ordinal, entry['name']))
            for keyword, entries in sorted(grouped.items()):
                require(len({name for _, name in entries}) == 1)
                add('catalog_content_keywords', common | {'keyword_id': keyword, 'label': entries[0][1],
                    'payload': {'raw_ordinals': [index for index,_ in entries], 'source_field': data['source_field'],
                                'language': 'UNKNOWN', 'translated_labels': [], 'semantic_centrality': 'UNJUDGED',
                                'review_status': 'UNREVIEWED', 'approved': False}})
        else:
            field_review(data['source_field'], 'CANDIDATE_ALIASES' if data['state'] == 'VALUE' else data['state'], data)
            grouped = defaultdict(list)
            for ordinal, entry in enumerate(data['entries'] or []):
                key = canonical({'normalized': collect.title_key(entry['title']['value']),
                                 'country': entry['country'], 'title_type': entry['type']})
                grouped[key].append((ordinal, entry['title']['value']))
            for key, entries in sorted(grouped.items()):
                values = json.loads(key)
                alias('ALTERNATIVE_TITLE', values['normalized'], [name for _,name in entries], values['country'],
                      values['title_type'], [i for i,_ in entries], {'state':'UNKNOWN','value':None})
    for rows in result.values(): rows.sort(key=lambda r: r.get('row_sha', ''))
    children_hash = digest(result)
    manifest = {'policy': POLICY, 'contract_sha256': saved['contract_sha256'],
                'public_snapshot_sha256': saved['public_snapshot_sha256'], 'mapping_snapshot_sha256': saved['mapping_snapshot_sha256'],
                'children_sha256': children_hash, 'counts': {table: len(rows) for table,rows in result.items() if table != TABLES[0]},
                'review_status':'UNREVIEWED','approved':False,'active':False,'content_updates':0,'translations':[]}
    result[TABLES[0]] = [{'batch_sha':batch,'projection_sha':children_hash,'manifest':manifest}]
    return {'policy':POLICY,'batch_sha':batch,'schema_sha256':digest(schema_sql()),'rows':result,
            'projection_sha256':digest({'policy':POLICY,'rows':result}),'content_updates':[],'application_apply_supported':False}


def schema_valid_sql():
    clauses = []
    for table, columns in COLS.items():
        condition = "TABLE_SCHEMA=DATABASE() AND TABLE_NAME=" + sql_value(table)
        clauses.append(f"(SELECT COUNT(*) FROM information_schema.TABLES WHERE {condition} AND ENGINE='InnoDB' AND TABLE_COLLATION='utf8mb4_bin')=1")
        clauses.append(f'(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE {condition})={len(columns)}')
        for name, kind in columns.items():
            base = kind.split('(')[0].lower()
            check = condition + ' AND COLUMN_NAME=' + sql_value(name) + ' AND DATA_TYPE=' + sql_value(base) + " AND IS_NULLABLE='NO'"
            if base in ('char','varchar'):
                check += f" AND CHARACTER_MAXIMUM_LENGTH={int(kind.split('(')[1][:-1])} AND COLLATION_NAME='utf8mb4_bin'"
            if base == 'datetime': check += ' AND DATETIME_PRECISION=6'
            clauses.append(f'(SELECT COUNT(*) FROM information_schema.COLUMNS WHERE {check})=1')
        unique = [pk(table)]
        if table == 'catalog_content_keywords': unique.append('source_sha,keyword_id')
        if table == 'catalog_metadata_field_reviews': unique.append('source_sha,field_name')
        if table == 'catalog_metadata_runtime_candidates': unique.append('source_sha,target_field')
        clauses.append(f"(SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE {condition} AND NON_UNIQUE=0)={len(unique)}")
        for fields in unique:
            clauses.append('(SELECT COUNT(*) FROM (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS fields '
                           f'FROM information_schema.STATISTICS WHERE {condition} AND NON_UNIQUE=0 GROUP BY INDEX_NAME) md_indexes '
                           'WHERE BINARY fields=' + sql_value(fields) + ')=1')
        fk_query = ("FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
                    'ON k.CONSTRAINT_SCHEMA=r.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME=r.CONSTRAINT_NAME AND k.TABLE_NAME=r.TABLE_NAME '
                    'WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME=' + sql_value(table) + ' AND k.REFERENCED_TABLE_NAME IS NOT NULL')
        clauses.append(f'(SELECT COUNT(*) {fk_query})={len(foreign_keys(table))}')
        for field, parent, parent_field in foreign_keys(table):
            clauses.append(f'(SELECT COUNT(*) {fk_query} AND k.COLUMN_NAME=' + sql_value(field)
                           + ' AND k.REFERENCED_TABLE_SCHEMA=DATABASE() AND k.REFERENCED_TABLE_NAME=' + sql_value(parent)
                           + ' AND k.REFERENCED_COLUMN_NAME=' + sql_value(parent_field) + " AND r.DELETE_RULE='CASCADE')=1")
        clauses.append('(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE=' + sql_value(table) + ')=0')
    return ' AND '.join('(' + clause + ')' for clause in clauses)


def schema_status(db):
    names = ','.join(sql_value(t) for t in TABLES)
    count = int(db.run('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN (' + names + ');').strip())
    if count == 0: return False
    require(count == len(TABLES), 'Partial draft schema; no automatic repair.')
    require(db.run('SELECT (' + schema_valid_sql() + ');').strip() == '1', 'Incompatible draft schema/triggers; no alteration.')
    return True


def row_json(table, alias=''):
    prefix = alias + '.' if alias else ''
    parts = []
    for field in COLS[table]:
        value = prefix + '`' + field + '`'
        if field == 'created_at': value = "DATE_FORMAT(" + value + ",'%Y-%m-%dT%H:%i:%s.%fZ')"
        parts += [sql_value(field), value]
    return 'JSON_OBJECT(' + ','.join(parts) + ')'


def public_row_json(alias=''):
    prefix = alias + '.' if alias else ''
    parts = []
    for field in FIELDS:
        value = prefix + '`' + field + '`'
        # MySQL JSON stores a native DATE as an opaque date, whereas persisted
        # snapshots contain an ISO string. Normalize before SQL JSON equality.
        if field == 'release_date': value = 'CAST(' + value + ' AS CHAR)'
        parts += [sql_value(field), value]
    return 'JSON_OBJECT(' + ','.join(parts) + ')'


def read_state(db):
    require(db.run("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('contents','catalog_import_sources') AND ENGINE='InnoDB';").strip() == '2',
            'Transactional catalog tables required.')
    present = schema_status(db)
    sql = 'SET SESSION MAX_EXECUTION_TIME=8000; SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;\nSTART TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;\n'
    base_json = public_row_json()
    sql += 'SELECT ' + base_json + ' FROM contents ORDER BY id LIMIT 5001;\nSELECT \'__MAPPINGS__\';\n'
    sql += "SELECT JSON_OBJECT('media_type',media_type,'tmdb_id',tmdb_id,'content_id',content_id) FROM catalog_import_sources ORDER BY media_type,tmdb_id LIMIT 5001;\n"
    for table in TABLES:
        sql += "SELECT '___" + table + "___';\n"
        if present: sql += f'SELECT {row_json(table)} FROM `{table}` ORDER BY `{pk(table)}` LIMIT 5001;\n'
    output = db.run(sql + 'ROLLBACK;\n')
    parts = output.split('__MAPPINGS__\n')
    require(len(parts) == 2)
    parse = lambda value: [collect.strict_json(line) for line in value.splitlines() if line]
    contents = parse(parts[0]); remaining = parts[1]; mappings = None; metadata = {}
    for index,table in enumerate(TABLES):
        prefix,remaining = remaining.split('___' + table + '___\n',1)
        if index == 0: mappings = parse(prefix)
        else: metadata[TABLES[index-1]] = parse(prefix)
    metadata[TABLES[-1]] = parse(remaining)
    require(0 < len(contents) <= 5000 and len(mappings) <= 5000 and all(len(r) <= 5000 for r in metadata.values()))
    require(schema_status(db) == present, 'Draft schema changed during read.')
    return {'catalog':contents,'mappings':mappings,'metadata':metadata,'schema_present':present}


def check_state(bundle, projected, state):
    expected_public, expected_mappings = bundle['baseline']
    require(digest(sorted(state['catalog'],key=lambda r:r['id'])) == digest(sorted(expected_public['rows'],key=lambda r:r['id'])),
            'Public catalog changed; no overwrite/adoption.')
    require(digest(sorted(state['mappings'],key=lambda r:(r['media_type'],r['tmdb_id'])))
            == digest(sorted(expected_mappings['sources'],key=lambda r:(r['media_type'],r['tmdb_id']))), 'Mapping changed.')
    own = {table:[{k:v for k,v in row.items() if k != 'created_at'} for row in state['metadata'][table]
                  if row['batch_sha'] == projected['batch_sha']] for table in TABLES}
    if not any(own.values()): return 'PENDING_DRAFT_STORAGE'
    for table in TABLES:
        require(digest(sorted(own[table],key=lambda r:r[pk(table)])) == digest(sorted(projected['rows'][table],key=lambda r:r[pk(table)])),
                'Partial/conflicting prior draft batch; no repair or adoption.')
    return 'ALREADY_STORED_UNREVIEWED'


def insert_rows(table, rows):
    if not rows: return ''
    fields = [field for field in COLS[table] if field != 'created_at']
    values = []
    for row in rows:
        require(set(row) == set(fields))
        values.append('(' + ','.join('CAST(' + sql_value(canonical(row[f])) + ' AS JSON)' if COLS[table][f] == 'JSON'
                                     else sql_value(row[f]) for f in fields) + ',@metadata_created_at)')
    return 'INSERT INTO `' + table + '` (' + ','.join('`' + f + '`' for f in fields) + ',created_at) VALUES\n' + ',\n'.join(values) + ';\n'


def guard(condition):
    return 'INSERT INTO metadata_gate SELECT 1 WHERE COALESCE((' + condition + '),0)=0;\n'


def transaction_sql(bundle, projected, before, status):
    """Static identifiers + existing CLI hex literals; caller cannot supply SQL identifiers."""
    sql = """-- DRAFT_METADATA_TRANSACTION
SET SESSION innodb_lock_wait_timeout=5;
SET SESSION lock_wait_timeout=5;
SET SESSION MAX_EXECUTION_TIME=8000;
CREATE TEMPORARY TABLE metadata_gate(ok INT PRIMARY KEY);
INSERT INTO metadata_gate VALUES(1);
CREATE TEMPORARY TABLE metadata_expected_catalog(id BIGINT PRIMARY KEY,payload JSON NOT NULL);
CREATE TEMPORARY TABLE metadata_expected_mappings(media_type VARCHAR(10),tmdb_id BIGINT,content_id BIGINT);
CREATE TEMPORARY TABLE metadata_expected_rows(table_name VARCHAR(64),row_key CHAR(64),payload JSON NOT NULL);
"""
    sql += 'INSERT INTO metadata_expected_catalog VALUES ' + ','.join('(' + str(row['id']) + ',CAST(' + sql_value(canonical(row)) + ' AS JSON))' for row in before['catalog']) + ';\n'
    sql += 'INSERT INTO metadata_expected_mappings VALUES ' + ','.join('(' + ','.join(sql_value(row[f]) for f in ('media_type','tmdb_id','content_id')) + ')' for row in before['mappings']) + ';\n'
    expected = [(t,row) for t in TABLES for row in before['metadata'][t]]
    if expected:
        sql += 'INSERT INTO metadata_expected_rows VALUES ' + ','.join('(' + sql_value(t) + ',' + sql_value(row[pk(t)]) + ',CAST(' + sql_value(canonical(row)) + ' AS JSON))' for t,row in expected) + ';\n'
    sql += 'SET TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION;\n'
    sql += 'SELECT id FROM contents ORDER BY id LIMIT 5001 FOR UPDATE;\nSELECT content_id FROM catalog_import_sources ORDER BY media_type,tmdb_id LIMIT 5001 FOR UPDATE;\n'
    for table in TABLES: sql += f'SELECT `{pk(table)}` FROM `{table}` ORDER BY `{pk(table)}` LIMIT 5001 FOR UPDATE;\n'
    sql += guard(schema_valid_sql())
    sql += guard("(SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('contents','catalog_import_sources') AND ENGINE='InnoDB')=2")
    current_json = public_row_json('c')
    sql += guard(f'(SELECT COUNT(*) FROM contents)={len(before["catalog"])} AND NOT EXISTS(SELECT 1 FROM contents c LEFT JOIN metadata_expected_catalog e ON c.id=e.id WHERE e.id IS NULL OR NOT (e.payload <=> {current_json}))')
    sql += guard(f'(SELECT COUNT(*) FROM catalog_import_sources)={len(before["mappings"])} AND NOT EXISTS(SELECT 1 FROM catalog_import_sources s LEFT JOIN metadata_expected_mappings e ON BINARY s.media_type=BINARY e.media_type AND s.tmdb_id=e.tmdb_id AND s.content_id=e.content_id WHERE e.content_id IS NULL)')
    for table in TABLES:
        sql += guard(f'(SELECT COUNT(*) FROM `{table}`)={len(before["metadata"][table])}')
        sql += existing_guard(table)
    if status == 'PENDING_DRAFT_STORAGE':
        sql += 'SET @metadata_created_at=UTC_TIMESTAMP(6);\n'
        for table in TABLES:
            sql += insert_rows(table, projected['rows'][table]) + '-- AFTER_' + table + '\n'
        for table in TABLES:
            sql += guard(f'(SELECT COUNT(*) FROM `{table}`)={len(before["metadata"][table])+len(projected["rows"][table])}')
            sql += existing_guard(table)
            for row in projected['rows'][table]:
                expressions = []
                for field,value in row.items():
                    if COLS[table][field] == 'JSON': expressions.append('`' + field + '`=CAST(' + sql_value(canonical(value)) + ' AS JSON)')
                    else: expressions.append('BINARY `' + field + '` <=> BINARY ' + sql_value(value))
                sql += guard(f'(SELECT COUNT(*) FROM `{table}` WHERE ' + ' AND '.join(expressions) + ' AND created_at=@metadata_created_at)=1')
    sql += "COMMIT; SELECT '__METADATA_DRAFT_COMMITTED__';\n"
    require(len(sql.encode('utf-8')) <= 10_000_000, 'Draft transaction exceeds size budget.')
    return sql


def existing_guard(table):
    return guard('NOT EXISTS(SELECT 1 FROM metadata_expected_rows e LEFT JOIN `' + table + '` c ON e.row_key=c.`' + pk(table)
                 + '` WHERE e.table_name=' + sql_value(table) + ' AND (c.`' + pk(table) + '` IS NULL OR NOT (e.payload <=> ' + row_json(table,'c') + ')))')


class OwnedScratch:
    """Only writes to a newly created UUID test schema. Never accepts an existing name."""
    def __init__(self, admin):
        require(os.environ.get('METADATA_STAGE_MYSQL_TEST') == 'true', 'Explicit owned-schema test opt-in required.')
        self.admin = admin
        self.schema = PREFIX + uuid4().hex[:12]
        self.owned = False
        require(re.fullmatch(PREFIX + '[a-f0-9]{12}', self.schema))
        require(admin.run('SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=' + sql_value(self.schema) + ';').strip() == '0')
        admin.run('CREATE DATABASE `' + self.schema + '` CHARACTER SET utf8mb4;')
        self.owned = True

    def run(self, sql):
        require(self.owned and re.fullmatch(PREFIX + '[a-f0-9]{12}', self.schema))
        return self.admin.run('USE `' + self.schema + '`;\n' + sql)

    def close(self):
        require(self.owned and re.fullmatch(PREFIX + '[a-f0-9]{12}', self.schema))
        self.admin.run('DROP DATABASE `' + self.schema + '`;')
        self.owned = False
        require(self.admin.run('SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=' + sql_value(self.schema) + ';').strip() == '0', 'Owned scratch cleanup unverified.')


def store_owned_scratch(db, bundle, output):
    require(isinstance(db, OwnedScratch) and db.owned, 'Application writes are unsupported; owned scratch required.')
    projected = projection(bundle)
    before = read_state(db)
    require(digest(before) == digest(read_state(db)), 'Draft state changed during preflight.')
    status = check_state(bundle, projected, before)
    output = Path(output); output.mkdir(parents=True, exist_ok=False)
    write_json(output/'before.json', before)
    require(digest(collect.load_json(output/'before.json')) == digest(before), 'Narrow pre-write backup failed.')
    if not before['schema_present']: db.run(schema_sql())  # DDL cannot be rolled back with data.
    require(schema_status(db))
    sql = transaction_sql(bundle, projected, before, status)
    (output/'owned-scratch-transaction.sql').write_text(sql,encoding='utf-8')
    try:
        ack = db.run(sql)
        require('__METADATA_DRAFT_COMMITTED__' in ack, 'Commit acknowledgement missing; inspect before retry.')
    except CatalogError:
        write_json(output/'result.json', {'state':'FAILED_OR_UNACKNOWLEDGED','automatic_retry':False,'schema_may_exist':True})
        raise
    after = read_state(db)
    require(check_state(bundle, projected, after) == 'ALREADY_STORED_UNREVIEWED')
    write_json(output/'after.json', after)
    report = {'state':'STORED_UNREVIEWED' if status == 'PENDING_DRAFT_STORAGE' else status,
              'inserted':sum(len(rows) for rows in projected['rows'].values()) if status == 'PENDING_DRAFT_STORAGE' else 0,
              'counts':{t:len(rows) for t,rows in projected['rows'].items()},'projection_sha256':projected['projection_sha256'],
              'content_updates':0,'approved':False,'active':False,'application_db_writes':0}
    write_json(output/'result.json', report)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('plan',))
    parser.add_argument('--collection', required=True, type=Path)
    parser.add_argument('--collection-sha256', required=True)
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(argv)
    try:
        folder = args.output or ROOT/'target/metadata-storage-plan'/(datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')+'-'+uuid4().hex[:8])
        require(not folder.exists(), 'Existing output will not be overwritten.')
        bundle = load_bundle(args.collection, args.collection_sha256)
        projected = projection(bundle)
        state = None
        if args.live:
            db = LocalCatalog()
            current = collect.read_consistent(db)
            collect.check_catalog(collect.contract(), *current)
            state = read_state(db)
            require(digest(state) == digest(read_state(db)), 'Live draft state changed during audit.')
            status = check_state(bundle, projected, state)
        else: status = 'OFFLINE_PLAN_CURRENT_DB_UNCHECKED'
        report = {'policy':POLICY,'mode':'DRY_RUN_NO_APPLICATION_WRITES','state':status,
                  'counts':{t:len(rows) for t,rows in projected['rows'].items()},'projection_sha256':projected['projection_sha256'],
                  'collection_sha256':projected['batch_sha'],'runtime_action':'STORE_UNAPPROVED_CANDIDATES_ONLY',
                  'actual_schema_applied':False,'content_updates':0,'provider_calls':0,'approved':False,'active':False}
        folder.mkdir(parents=True,exist_ok=False)
        write_json(folder/'storage-plan.json', projected); write_json(folder/'summary.json', report)
        if state is not None: write_json(folder/'live-before.json',state)
        (folder/'schema-reference.sql').write_text(schema_sql(),encoding='utf-8')
        print(json.dumps({'output':str(folder)} | report,ensure_ascii=False))
        return 0
    except (CatalogError, ValueError, KeyError, TypeError, OSError, RecursionError):
        print('Metadata storage dry-run failed; credentials, SQL and database output withheld.',file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
