"""Read-only local runtime/mapping audit. Never fetch TMDB or apply updates.

Run: python scripts/audit_runtime_coverage.py
Uses the existing opaque local MySQL configuration reader, not its write methods.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

from catalog_pool import CatalogError, LocalCatalog, ROOT, digest, write_json


LIMIT = 5000
TIME_FIELDS = {'MOVIE': 'runtime_minutes', 'TV': 'episode_runtime_minutes'}
INTEGER_TYPES = {'tinyint', 'smallint', 'mediumint', 'int', 'bigint'}
CONTENT_COLUMNS = {'id', 'type', *TIME_FIELDS.values()}
SOURCE_COLUMNS = {'media_type', 'tmdb_id', 'content_id'}
METADATA_SQL = """
SET SESSION MAX_EXECUTION_TIME=8000;
SELECT JSON_OBJECT('table',TABLE_NAME,'engine',ENGINE)
FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
AND TABLE_NAME IN ('contents','catalog_import_sources') ORDER BY TABLE_NAME;
SELECT '__COLUMNS__';
SELECT JSON_OBJECT('table',TABLE_NAME,'name',COLUMN_NAME,'data_type',DATA_TYPE,
                  'nullable',IS_NULLABLE)
FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
AND ((TABLE_NAME='contents' AND COLUMN_NAME IN
('id','type','runtime_minutes','episode_runtime_minutes'))
OR (TABLE_NAME='catalog_import_sources' AND COLUMN_NAME IN
('media_type','tmdb_id','content_id')))
ORDER BY TABLE_NAME,COLUMN_NAME;
"""


def positive_id(value, maximum=2**63 - 1):
    return type(value) is int and 1 <= value <= maximum


def valid_minutes(value):
    return type(value) is int and 1 <= value <= 1440


def parse_lines(output):
    try:
        return [json.loads(line) for line in output.splitlines() if line]
    except (ValueError, TypeError):
        raise CatalogError('Invalid audit result; database output withheld.') from None


def read_schema(db):
    parts = db.run(METADATA_SQL).split('__COLUMNS__\n')
    if len(parts) != 2:
        raise CatalogError('Unexpected schema result; database output withheld.')
    schema = {'tables': parse_lines(parts[0]), 'columns': parse_lines(parts[1])}
    tables = {row['table']: row['engine'] for row in schema['tables']}
    if 'contents' not in tables:
        raise CatalogError('Required contents table is missing.')
    if any(engine != 'InnoDB' for engine in tables.values()):
        raise CatalogError('Audit requires InnoDB for a consistent read-only snapshot.')
    if not {'id', 'type'} <= columns_for(schema, 'contents').keys():
        raise CatalogError('Required content identity columns are missing.')
    return schema


def columns_for(schema, table):
    return {row['name']: row for row in schema['columns'] if row['table'] == table}


def snapshot_sql(schema):
    columns = columns_for(schema, 'contents')
    # All identifiers originate in fixed allowlists, never data or CLI arguments.
    fields = ["'id',id", "'type',type"]
    fields += ["'" + field + "',`" + field + '`' for field in TIME_FIELDS.values()
               if field in columns]
    sql = "SET SESSION MAX_EXECUTION_TIME=8000;\n"
    sql += 'START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;\n'
    sql += 'SELECT JSON_OBJECT(' + ','.join(fields) + ') FROM contents ORDER BY id LIMIT 5001;\n'
    sql += "SELECT '__SOURCES__';\n"
    if SOURCE_COLUMNS <= columns_for(schema, 'catalog_import_sources').keys():
        sql += ("SELECT JSON_OBJECT('media_type',media_type,'tmdb_id',tmdb_id,'content_id',content_id) "
                'FROM catalog_import_sources ORDER BY media_type,tmdb_id,content_id LIMIT 5001;\n')
    return sql + 'ROLLBACK;\n'


def read_snapshot(db):
    schema = read_schema(db)
    parts = db.run(snapshot_sql(schema)).split('__SOURCES__\n')
    if len(parts) != 2:
        raise CatalogError('Unexpected snapshot result; database output withheld.')
    contents, sources = map(parse_lines, parts)
    if len(contents) > LIMIT or len(sources) > LIMIT:
        raise CatalogError('Audit exceeded its 5000-row per-table budget; no plan produced.')
    if read_schema(db) != schema:
        raise CatalogError('Schema changed during audit; rerun after changes finish.')
    return {'schema': schema, 'contents': contents, 'sources': sources}


def snapshot_hash(snapshot):
    """Only these narrow fields are fingerprinted, not the whole catalog/database."""
    return digest({'schema': {key: sorted(rows, key=lambda row: json.dumps(row, sort_keys=True))
                              for key, rows in snapshot['schema'].items()},
                   'contents': sorted(snapshot['contents'], key=lambda row: row['id']),
                   'sources': sorted(snapshot['sources'], key=lambda row: json.dumps(row, sort_keys=True))})


def analyze(snapshot, expected_count=468):
    contents, sources, schema = snapshot['contents'], snapshot['sources'], snapshot['schema']
    if not 1 <= expected_count <= LIMIT or len(contents) > LIMIT or len(sources) > LIMIT:
        raise CatalogError('Invalid expected count or audit row budget exceeded.')
    by_id = {}
    for row in contents:
        if not positive_id(row.get('id')) or row['id'] in by_id:
            raise CatalogError('Invalid or duplicate content identity; no plan produced.')
        by_id[row['id']] = row
    columns = columns_for(schema, 'contents')
    issues = Counter({key: 0 for key in ('invalid_mapping', 'orphan_mapping', 'type_mismatch',
                                       'multiple_mappings_per_content', 'duplicate_external_key')})
    mappings, external = defaultdict(list), Counter()
    blocked_ids = set()
    mapping_available = SOURCE_COLUMNS <= columns_for(schema, 'catalog_import_sources').keys()
    for row in sources:
        content_id, tmdb_id, media = row.get('content_id'), row.get('tmdb_id'), row.get('media_type')
        if not positive_id(content_id) or not positive_id(tmdb_id, 2**31 - 1) or media not in TIME_FIELDS:
            issues['invalid_mapping'] += 1
            if positive_id(content_id):
                blocked_ids.add(content_id)
            continue
        mappings[content_id].append(row)
        external[(media, tmdb_id)] += 1
        if content_id not in by_id:
            issues['orphan_mapping'] += 1
        elif by_id[content_id].get('type') != media:
            issues['type_mismatch'] += 1
            blocked_ids.add(content_id)
    issues['multiple_mappings_per_content'] = sum(len(rows) > 1 for rows in mappings.values())
    issues['duplicate_external_key'] = sum(count > 1 for count in external.values())
    for content_id, rows in mappings.items():
        if len(rows) != 1 or any(external[(r['media_type'], r['tmdb_id'])] != 1 for r in rows):
            blocked_ids.add(content_id)
    summary = {}
    candidates, review = [], []
    for media, field in TIME_FIELDS.items():
        selected = [r for r in contents if r.get('type') == media]
        supported = field in columns and columns[field]['data_type'] in INTEGER_TYPES
        other = next(f for f in TIME_FIELDS.values() if f != field)
        counts = Counter(total=len(selected), valid=0, null=0, invalid=0, unavailable=0,
                         wrong_media_field_populated=0, mapped_unique=0, unmapped=0,
                         fetch_candidates=0)
        for row in selected:
            content_id = row['id']
            clean_mapping = content_id not in blocked_ids and len(mappings[content_id]) == 1
            counts['mapped_unique' if clean_mapping else 'unmapped'] += 1
            polluted = other in columns and row.get(other) is not None
            if polluted:
                counts['wrong_media_field_populated'] += 1
            value = row.get(field)
            status = ('unavailable' if not supported else 'null' if value is None
                      else 'valid' if valid_minutes(value) else 'invalid')
            counts[status] += 1
            if status == 'invalid' or polluted:
                review.append({'content_id': content_id, 'media_type': media,
                               'reasons': (['invalid_existing_time'] if status == 'invalid' else [])
                               + (['wrong_media_field_populated'] if polluted else [])})
            if status == 'null' and not polluted and clean_mapping:
                counts['fetch_candidates'] += 1
                candidates.append({'content_id': content_id, 'media_type': media,
                                   'tmdb_id': mappings[content_id][0]['tmdb_id'], 'target_field': field,
                                   'expected_existing_value': None})
        counts['valid_percent'] = round(100 * counts['valid'] / len(selected), 2) if selected else None
        summary[media] = dict(counts)
    blockers = []
    if len(contents) != expected_count:
        blockers.append('catalog_count_changed')
    if not mapping_available:
        blockers.append('source_mapping_schema_unavailable')
    if any(field not in columns or columns[field]['data_type'] not in INTEGER_TYPES
           for field in TIME_FIELDS.values()):
        blockers.append('runtime_schema_unavailable')
    unknown_types = sum(row.get('type') not in TIME_FIELDS for row in contents)
    if unknown_types:
        blockers.append('unknown_content_type')
    if any(issues.values()):
        blockers.append('mapping_integrity_issues')
    return {'version': 1, 'mode': 'READ_ONLY_DETAIL_FETCH_SCOPE',
            'gate': 'BLOCKED' if blockers else 'READY_FOR_DETAIL_DRY_RUN', 'blockers': blockers,
            'expected_content_count': expected_count, 'content_count': len(contents),
            'source_count': len(sources), 'unknown_content_types': unknown_types,
            'schema': schema, 'coverage': summary, 'mapping_issues': dict(issues),
            'manual_review': sorted(review, key=lambda row: row['content_id']),
            'potential_fetch_count': len(candidates),
            'detail_fetch_candidates': sorted(candidates, key=lambda row: row['content_id']) if not blockers else [],
            'coverage_snapshot_sha256': snapshot_hash(snapshot),
            'updates': [], 'tmdb_data_api_calls': 0, 'catalog_writes': 0,
            'policy': 'Fetch scope only; preserve existing values; no title-based ID guessing; no apply command.'}


def audit(db, expected_count=468):
    first = read_snapshot(db)
    report = analyze(first, expected_count)
    second = read_snapshot(db)
    if report['coverage_snapshot_sha256'] != snapshot_hash(second):
        raise CatalogError('Coverage fields changed between read-only snapshots; no plan produced.')
    report['repeated_snapshot_match'] = True
    report['audited_at_utc'] = datetime.now(timezone.utc).isoformat()
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expected-count', type=int, default=468)
    parser.add_argument('--output', type=Path, default=ROOT / 'target/runtime-coverage/audit.json')
    args = parser.parse_args(argv)
    try:
        report = audit(LocalCatalog(), args.expected_count)
        write_json(args.output, report)
        print(json.dumps({key: report[key] for key in
                          ('gate', 'content_count', 'source_count', 'coverage', 'blockers',
                           'potential_fetch_count', 'repeated_snapshot_match')}, ensure_ascii=False))
        return 0 if not report['blockers'] else 2
    except (CatalogError, KeyError, TypeError, ValueError, OSError):
        print('Runtime audit failed; credentials, SQL and database output withheld.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
