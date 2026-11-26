"""Step 1 only: bounded public-catalog read, no provider/API calls or DB writes.

python scripts/audit_recommendation_landscape.py
Counts are lexical discovery probes, NEVER semantic coverage or retrieval recall.
"""
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys

from catalog_pool import CatalogError, LocalCatalog, ROOT, digest, write_json

LIMIT = 5000
OUTPUT = ROOT / 'target/recommendation-landscape-20261125'
# Audit probes only, not a product taxonomy, synonym dictionary, or expected answer list.
PROBES = {
    'cooking': ['요리', '조리', '셰프', '쉐프', '주방', '레스토랑'],
    'sports': ['스포츠', '운동선수', '축구', '야구', '농구', '권투', '복싱', '경기'],
    'music_stage': ['음악', '가수', '밴드', '연주', '공연', '무대'],
    'medicine': ['의사', '병원', '의료', '간호', '수술'],
    'law_investigation': ['변호사', '법정', '재판', '형사', '수사', '탐정'],
    'science_space': ['과학', '우주', '우주선', '연구', '실험'],
    'work_creation': ['직장', '회사', '사업', '작가', '예술', '창작'],
    'travel_nature': ['여행', '자연', '야생', '산악', '바다', '탐험'],
    'family_care': ['가족', '부모', '아버지', '어머니', '아이', '돌봄'],
    'friendship_growth': ['친구', '우정', '성장', '학교', '청춘'],
    'loss_recovery': ['상실', '죽음', '이별', '슬픔', '치유', '회복'],
    'warmth_humor': ['따뜻', '위로', '희망', '유쾌', '웃음', '행복'],
    'fear_tension': ['공포', '두려', '긴장', '위협', '살인', '추격'],
    'history_society': ['역사', '전쟁', '사회', '차별', '정치', '혁명'],
}
FIELDS = ('id', 'type', 'title', 'overview', 'genres', 'release_date',
          'runtime_minutes', 'episode_runtime_minutes')
SCHEMA_SQL = """SET SESSION MAX_EXECUTION_TIME=8000;
SELECT JSON_OBJECT('table',TABLE_NAME,'engine',ENGINE)
FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
AND TABLE_NAME='contents';
SELECT '__COLUMNS__';
SELECT JSON_OBJECT('name',COLUMN_NAME,'type',DATA_TYPE)
FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
AND TABLE_NAME='contents' ORDER BY COLUMN_NAME;
"""
SNAPSHOT_SQL = ("SET SESSION MAX_EXECUTION_TIME=8000;\n"
                "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;\n"
                "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;\n"
                "SELECT JSON_OBJECT(" + ','.join("'" + f + "',`" + f + '`' for f in FIELDS)
                + ") FROM contents ORDER BY id LIMIT 5001;\nROLLBACK;\n")


def lines(value):
    return [json.loads(line) for line in value.splitlines() if line]


def read_snapshot(db):
    parts = db.run(SCHEMA_SQL).split('__COLUMNS__\n')
    if len(parts) != 2:
        raise CatalogError('Unexpected public schema result; details withheld.')
    tables, columns = map(lines, parts)
    if tables != [{'table': 'contents', 'engine': 'InnoDB'}] or not set(FIELDS) <= {c['name'] for c in columns}:
        raise CatalogError('Public catalog schema is not supported; no partial audit.')
    rows = lines(db.run(SNAPSHOT_SQL))
    if not rows or len(rows) > LIMIT or any(type(r.get('id')) is not int or r['id'] <= 0 for r in rows):
        raise CatalogError('Public snapshot is empty, invalid or over budget.')
    if len({r['id'] for r in rows}) != len(rows):
        raise CatalogError('Duplicate public identity.')
    return {'columns': columns, 'rows': rows}


def boundary(text, term):
    # Python Unicode approximation for discovery only; not a replay of MySQL/JPA.
    return re.search(r'(?<!\w)' + re.escape(term), text, re.IGNORECASE) is not None


def analyze(snapshot):
    rows = snapshot['rows']
    metadata = {}
    for media, field in [('MOVIE', 'runtime_minutes'), ('TV', 'episode_runtime_minutes')]:
        selected = [r for r in rows if r['type'] == media]
        valid = lambda r: type(r.get(field)) is int and 1 <= r[field] <= 1440
        metadata[media] = {'total': len(selected), 'valid_time': sum(map(valid, selected)),
                           'missing_time': sum(r.get(field) is None for r in selected),
                           'invalid_time': sum(r.get(field) is not None and not valid(r) for r in selected)}
    genres = Counter(g.strip().lower() for r in rows for g in (r.get('genres') or '').split(',') if g.strip())
    probes = {}
    for name, terms in PROBES.items():
        partial, visible, full, tail = [], [], [], []
        for row in rows:
            title, overview = row.get('title') or '', row.get('overview') or ''
            short_text, full_text = title + '\n' + overview[:400], title + '\n' + overview
            if any(t.casefold() in short_text.casefold() for t in terms):
                partial.append(row['id'])
            if any(boundary(short_text, t) for t in terms):
                visible.append(row['id'])
            if any(boundary(full_text, t) for t in terms):
                full.append(row['id'])
                if row['id'] not in visible:
                    tail.append(row['id'])
        probes[name] = {'terms': terms, 'substring_visible_ids': partial, 'boundary_visible_ids': visible,
                        'boundary_full_ids': full, 'full_only_ids': tail,
                        'semantic_coverage': 'NOT_MEASURED', 'relevance_labels': 'UNJUDGED'}
    return {'version': 1, 'mode': 'PUBLIC_READ_ONLY_STEP1', 'snapshot_sha256': digest(snapshot),
            'content_count': len(rows), 'metadata_by_type': metadata, 'genres': dict(sorted(genres.items())),
            'empty_overview': sum(not (r.get('overview') or '').strip() for r in rows),
            'overview_over_400': sum(len(r.get('overview') or '') > 400 for r in rows),
            'empty_genres': sum(not (r.get('genres') or '').strip() for r in rows),
            'missing_release_date': sum(not r.get('release_date') for r in rows),
            'unknown_type': sum(r['type'] not in ('MOVIE', 'TV') for r in rows),
            'public_column_names': [c['name'] for c in snapshot['columns']], 'probes': probes,
            'catalog_writes': 0, 'provider_calls': 0, 'tmdb_calls': 0,
            'warning': 'Lexical probes, not topic registration rates, semantic recall, or product performance.'}


def main():
    try:
        db = LocalCatalog()  # Existing opaque configuration helper; never print settings.
        before = read_snapshot(db)
        report = analyze(before)
        after = read_snapshot(db)
        if digest(before) != digest(after):
            raise CatalogError('Public fields changed during audit; rerun before reporting.')
        report.update(audited_at_utc=datetime.now(timezone.utc).isoformat(), repeated_snapshot_match=True)
        write_json(OUTPUT / 'public-snapshot.json', before)
        write_json(OUTPUT / 'audit.json', report)
        print(json.dumps({k: v for k, v in report.items() if k != 'probes'}, ensure_ascii=False))
        return 0
    except (CatalogError, ValueError, TypeError, KeyError, OSError):
        print('Public audit failed; SQL, credentials and server output withheld.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
