"""Capture public ranking fields for step-2 replay; no provider or application writes."""
from datetime import datetime, timezone
import json
import sys

from audit_recommendation_landscape import SCHEMA_SQL, lines
from catalog_pool import CatalogError, LocalCatalog, ROOT, digest, write_json

OUTPUT = ROOT / 'target/quality-baseline-20261125'
FIELDS = ('id', 'type', 'title', 'overview', 'genres', 'release_date', 'rating', 'view_count',
          'runtime_minutes', 'episode_runtime_minutes')
SQL = ("SET SESSION MAX_EXECUTION_TIME=8000;\n"
       "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;\n"
       "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;\nSELECT JSON_OBJECT("
       + ','.join("'" + f + "',`" + f + '`' for f in FIELDS)
       + ") FROM contents ORDER BY id LIMIT 5001;\nROLLBACK;\n")


def read(db):
    parts = db.run(SCHEMA_SQL).split('__COLUMNS__\n')
    if len(parts) != 2:
        raise CatalogError('Unsupported public schema.')
    tables, columns = map(lines, parts)
    if tables != [{'table': 'contents', 'engine': 'InnoDB'}] or not set(FIELDS) <= {c['name'] for c in columns}:
        raise CatalogError('Unsupported public schema.')
    rows = lines(db.run(SQL))
    if not 1 <= len(rows) <= 5000 or len({r['id'] for r in rows}) != len(rows):
        raise CatalogError('Invalid or over-budget public snapshot.')
    return {'columns': columns, 'rows': rows}


def main():
    try:
        path = OUTPUT / 'public-snapshot.json'
        if path.exists():
            raise CatalogError('Existing baseline snapshot preserved; choose a new version before rerunning.')
        db = LocalCatalog()
        before = read(db)
        if digest(before) != digest(read(db)):
            raise CatalogError('Public fields changed during capture.')
        report = {**before, 'snapshot_sha256': digest(before), 'captured_at_utc': datetime.now(timezone.utc).isoformat(),
                  'application_writes': 0, 'private_rows_read': 0, 'provider_calls': 0,
                  'repeated_snapshot_match': True}
        write_json(path, report)
        print(json.dumps({k: v for k, v in report.items() if k not in ('columns', 'rows')}, ensure_ascii=False))
        print('Public catalog rows:', len(before['rows']))
        return 0
    except (CatalogError, ValueError, TypeError, KeyError, OSError):
        print('Baseline capture failed or output already exists; connection details withheld.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
