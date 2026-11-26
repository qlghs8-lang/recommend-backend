"""Prepare and explicitly apply an additive TMDB catalog import to local myproject.

Standard library + installed MySQL 8 CLI only. No API key in arguments/files/logs.
Commands: inventory; collect; plan; apply. See docs/catalog-pool.md.
"""
import argparse
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import unicodedata
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, build_opener, HTTPRedirectHandler

ROOT = Path(__file__).resolve().parents[1]
MYSQL_BIN = Path('C:/Program Files/MySQL/MySQL Server 8.0/bin')
OUTPUT = ROOT / 'target/catalog'
SOURCE_TABLE = 'catalog_import_sources'
GENRES = {
    28: ('action',), 12: ('adventure',), 16: ('animation',), 35: ('comedy',),
    80: ('crime',), 99: ('documentary',), 18: ('drama',), 10751: ('family',),
    14: ('fantasy',), 36: ('history',), 27: ('horror',), 10402: ('music',),
    9648: ('mystery',), 10749: ('romance',), 878: ('sci-fi',), 53: ('thriller',),
    10752: ('war',), 37: ('western',), 10770: ('tv_movie',),
    10759: ('action', 'adventure'), 10762: ('kids',), 10763: ('news',),
    10764: ('reality',), 10765: ('sci-fi', 'fantasy'), 10766: ('soap',),
    10767: ('talk',), 10768: ('war', 'politics'),
}
BUCKETS = {
    'MOVIE': [28, 35, 18, 878, 53, 16, 10749, 27, 9648, 99, 12, 10751],
    'TV': [18, 35, 10759, 10765, 9648, 16, 80, 99, 10751, 10768],
}
FIELDS = ['id', 'type', 'title', 'overview', 'genres', 'release_date', 'poster_url',
          'backdrop_url', 'rating', 'rating_count', 'view_count']
ROW_JSON = 'JSON_OBJECT(' + ','.join("'" + f + "',`" + f + '`' for f in FIELDS) + ')'
SOURCE_JSON = "JSON_OBJECT('type',media_type,'tmdb_id',tmdb_id,'content_id',content_id)"


class CatalogError(Exception):
    """Only sanitized, operator-safe messages may be raised here."""


def today():
    return datetime.now(timezone(timedelta(hours=9))).date()


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(',', ':')).encode()).hexdigest()


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = path.with_suffix(path.suffix + '.pending')
    pending.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    pending.replace(path)


def read_json(path):
    path = Path(path)
    if path.stat().st_size > 20_000_000:
        raise CatalogError('Input file exceeds 20 MB.')
    return json.loads(path.read_text(encoding='utf-8'))


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None  # Never forward the Authorization header to another host.


class TmdbClient:
    def __init__(self):
        self._token = os.environ.get('TMDB_READ_ACCESS_TOKEN', '').strip()
        if not self._token:
            raise CatalogError('Set TMDB_READ_ACCESS_TOKEN privately in this process; never paste it into chat.')
        self.calls = 0
        self.started = time.monotonic()
        self.opener = build_opener(NoRedirect())

    def get(self, endpoint, **params):
        if endpoint != '/configuration' and not re.fullmatch(r'/discover/(movie|tv)', endpoint):
            raise CatalogError('TMDB endpoint is not allowlisted.')
        if self.calls >= 200 or time.monotonic() - self.started > 300:
            raise CatalogError('Collection reached its 200-request / five-minute budget; reduce the scope.')
        self.calls += 1
        request = Request('https://api.themoviedb.org/3' + endpoint + '?' + urlencode(params), headers={
            'Authorization': 'Bearer ' + self._token, 'Accept': 'application/json',
            'User-Agent': 'Recommend-Catalog-Importer/1.0'})
        try:
            with self.opener.open(request, timeout=20) as response:
                body = response.read(4_000_001)
                if len(body) > 4_000_000:
                    raise CatalogError('TMDB response exceeds its size budget.')
                result = json.loads(body)
        except HTTPError as error:
            if error.code == 429:
                raise CatalogError('TMDB rate limit reached. Stop and retry later; no automatic retry.') from None
            raise CatalogError('TMDB request failed (HTTP ' + str(error.code) + '); response withheld.') from None
        except (URLError, TimeoutError, OSError, ValueError):
            raise CatalogError('TMDB request failed; network/provider details withheld.') from None
        time.sleep(0.3)
        return result


def image_url(path, size, required=False):
    if path is None and not required:
        return None
    if not isinstance(path, str) or not re.fullmatch(r'/[A-Za-z0-9_-]+\.(jpg|png|webp)', path):
        raise ValueError('Invalid image path')
    return 'https://image.tmdb.org/t/p/' + size + path


def normalize(raw, kind, bucket):
    """Skip incomplete translations instead of inventing titles, plots, dates or ratings."""
    try:
        media = 'movie' if kind == 'MOVIE' else 'tv'
        title = raw['title' if kind == 'MOVIE' else 'name'].strip()
        original = raw.get('original_title' if kind == 'MOVIE' else 'original_name', title).strip()
        overview = raw['overview'].strip()
        released = date.fromisoformat(raw['release_date' if kind == 'MOVIE' else 'first_air_date'])
        genre_names = sorted({name for g in raw['genre_ids'] for name in GENRES.get(g, ())})
        external_id, votes, rating = raw['id'], raw['vote_count'], raw['vote_average']
        if (raw.get('adult', False) or not isinstance(external_id, int) or isinstance(external_id, bool)
                or external_id < 1 or external_id > 2**63 - 1 or not title or len(title) > 255
                or not original or len(original) > 500 or not re.search('[가-힣]', title)
                or not re.search('[가-힣]', overview) or len(overview) < 20
                or not genre_names or released > today() or released.year < 1888
                or not isinstance(votes, int) or isinstance(votes, bool) or not 0 <= votes < 2**63
                or isinstance(rating, bool) or not isinstance(rating, (int, float))
                or not math.isfinite(rating) or not 0 <= rating <= 10):
            return None
        return {'type': kind, 'tmdb_id': external_id, 'title': title, 'original_title': original,
                'overview': overview[:2000], 'genres': ','.join(genre_names),
                'release_date': released.isoformat(), 'poster_url': image_url(raw['poster_path'], 'w500', True),
                'backdrop_url': image_url(raw.get('backdrop_path'), 'w1280'),
                'rating': rating, 'rating_count': votes, 'view_count': 0,
                'source_url': f'https://www.themoviedb.org/{media}/{external_id}',
                'bucket': GENRES[bucket][0] if GENRES[bucket][0] in genre_names else genre_names[0]}
    except (KeyError, TypeError, ValueError, AttributeError):
        return None


def collect(client, target=500, progress=None):
    if progress:
        progress('[수집 시작] TMDB 이미지 설정 확인 중…')
    config = client.get('/configuration')['images']
    if (config['secure_base_url'] != 'https://image.tmdb.org/t/p/'
            or 'w500' not in config['poster_sizes'] or 'w1280' not in config['backdrop_sizes']):
        raise CatalogError('TMDB image configuration changed; review before collection.')
    records, seen = [], set()
    requests = 1
    # Each bucket gets a turn before deeper pages. Korean originals and global works are mixed.
    for page in range(1, 5):
        for kind, buckets in BUCKETS.items():
            if sum(r['type'] == kind for r in records) >= target:
                continue
            for bucket in buckets:
                for korean_original in (True, False):
                    params = {'language': 'ko-KR', 'include_adult': 'false', 'page': page,
                              'sort_by': 'popularity.desc', 'with_genres': bucket, 'vote_count.gte': 50}
                    if kind == 'MOVIE':
                        params.update({'include_video': 'false', 'primary_release_date.lte': today().isoformat()})
                    else:
                        params.update({'include_null_first_air_dates': 'false', 'first_air_date.lte': today().isoformat()})
                    if korean_original:
                        params['with_original_language'] = 'ko'
                    requests += 1
                    if progress:
                        counts = Counter(row['type'] for row in records)
                        scope = '한국어 원작' if korean_original else '전체 지역'
                        progress(f'[요청 {requests}/200] {kind} · {GENRES[bucket][0]} · {page}페이지 · {scope}'
                                 f" | 후보: 영화 {counts['MOVIE']}개 / TV {counts['TV']}개")
                    response = client.get('/discover/' + ('movie' if kind == 'MOVIE' else 'tv'), **params)
                    for raw in response['results']:
                        row = normalize(raw, kind, bucket)
                        if row and (kind, row['tmdb_id']) not in seen:
                            seen.add((kind, row['tmdb_id']))
                            records.append(row)
                if sum(r['type'] == kind for r in records) >= target:
                    break
    if progress:
        progress(f'[수집 완료] 후보 {len(records)}개. 파일 검증 및 저장 중…')
    return {'version': 1, 'provider': 'TMDB', 'language': 'ko-KR',
            'collected_at': datetime.now(timezone.utc).isoformat(), 'records': records}


def validate_dataset(dataset):
    if not isinstance(dataset, dict):
        raise CatalogError('Unsupported dataset format.')
    if dataset.get('version') != 1 or dataset.get('provider') != 'TMDB' or dataset.get('language') != 'ko-KR':
        raise CatalogError('Unsupported dataset format.')
    rows = dataset.get('records')
    if not isinstance(rows, list) or len(rows) > 3000:
        raise CatalogError('Invalid dataset size.')
    seen = set()
    for row in rows:
        try:
            if not isinstance(row, dict):
                raise ValueError()
            kind = row['type']
            if kind not in BUCKETS or (kind, row['tmdb_id']) in seen:
                raise ValueError()
            inverse = {name: g for g, names in sorted(GENRES.items(), key=lambda item: len(item[1]), reverse=True)
                       for name in names}
            genres = row['genres'].split(',')
            if any(g not in inverse for g in genres):
                raise ValueError()
            synthetic = {'id': row['tmdb_id'], 'title': row['title'], 'name': row['title'],
                         'original_title': row['original_title'], 'original_name': row['original_title'],
                         'overview': row['overview'], 'release_date': row['release_date'],
                         'first_air_date': row['release_date'], 'genre_ids': [inverse[g] for g in genres],
                         'vote_average': row['rating'], 'vote_count': row['rating_count'],
                         'poster_path': row['poster_url'].removeprefix('https://image.tmdb.org/t/p/w500'),
                         'backdrop_path': None if row['backdrop_url'] is None else
                         row['backdrop_url'].removeprefix('https://image.tmdb.org/t/p/w1280')}
            clean = normalize(synthetic, kind, 18)
            if not clean or any(clean[f] != row[f] for f in clean if f != 'bucket'):
                raise ValueError()
            if row['bucket'] not in genres:
                raise ValueError()
            seen.add((kind, row['tmdb_id']))
        except (KeyError, TypeError, ValueError, AttributeError):
            raise CatalogError('Dataset contains invalid, duplicate or modified metadata.') from None
    return rows


def title_key(value):
    value = unicodedata.normalize('NFKC', value).casefold()
    return ''.join(c for c in value if c.isalnum())


def plan_import(dataset, snapshot, target=500):
    rows = validate_dataset(dataset)
    if not 1 <= target <= 1000:
        raise CatalogError('Target must be 1..1000 total works.')
    existing = snapshot['contents']
    counts = Counter(row['type'] for row in existing)
    if set(counts) - set(BUCKETS):
        raise CatalogError('Existing catalog has unsupported content types; review first.')
    desired = counts.copy()
    for _ in range(max(0, target - len(existing))):
        desired[min(BUCKETS, key=lambda kind: desired[kind])] += 1
    titles = defaultdict(list)
    for row in existing:
        titles[(row['type'], title_key(row['title']))].append((row['release_date'] or '')[:4])
    mapped = {(r['type'], r['tmdb_id']) for r in snapshot['sources']}
    selected, skipped = [], Counter()
    genre_counts = Counter(g.strip().casefold() for row in existing for g in (row['genres'] or '').split(',') if g.strip())
    # Prefer underrepresented buckets among eligible candidates, independently of API result order.
    pending = list(rows)
    while pending and len(existing) + len(selected) < target:
        pending.sort(key=lambda r: (genre_counts[r['bucket']], r['type'], -r['rating_count'], r['tmdb_id']))
        row = pending.pop(0)
        kind = row['type']
        if (kind, row['tmdb_id']) in mapped:
            skipped['already_imported'] += 1
            continue
        if counts[kind] >= desired[kind]:
            skipped['type_quota'] += 1
            continue
        aliases = {title_key(row['title']), title_key(row['original_title'])}
        years = [year for alias in aliases for year in titles.get((kind, alias), [])]
        if any(not year or year == row['release_date'][:4] for year in years):
            skipped['existing_title_year'] += 1
            continue
        selected.append(row)
        counts[kind] += 1
        mapped.add((kind, row['tmdb_id']))
        for alias in aliases:
            titles[(kind, alias)].append(row['release_date'][:4])
        genre_counts.update(row['genres'].split(','))
    return {'version': 1, 'dataset_sha256': digest(dataset), 'before_digest': snapshot['digest'],
            'target_total': target, 'before_total': len(existing), 'after_total': len(existing) + len(selected),
            'after_types': dict(counts), 'additions': selected, 'skipped': dict(skipped),
            'target_reached': len(existing) + len(selected) >= target,
            'after_genres': dict(sorted(genre_counts.items()))}


def sql_value(value):
    if value is None:
        return 'NULL'
    if isinstance(value, str):
        return "CONVERT(0x" + value.encode('utf-8').hex() + " USING utf8mb4)" if value else "''"
    if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value):
        return str(value)
    raise CatalogError('Unsafe SQL value.')


def db_digest_sql(source_exists=True):
    contents = f"(SELECT COALESCE(SHA2(GROUP_CONCAT(SHA2(CAST({ROW_JSON} AS CHAR),256) ORDER BY id SEPARATOR ''),256),SHA2('',256)) FROM contents)"
    sources = (f"(SELECT COALESCE(SHA2(GROUP_CONCAT(SHA2(CAST({SOURCE_JSON} AS CHAR),256) ORDER BY media_type,tmdb_id SEPARATOR ''),256),SHA2('',256)) FROM {SOURCE_TABLE})"
               if source_exists else "SHA2('',256)")
    return f"SHA2(CONCAT({contents},{sources}),256)"


class LocalCatalog:
    def __init__(self):
        # Follow the existing local audit convention; credentials stay in child environment only.
        settings = dict(line.split('=', 1) for line in (ROOT / 'src/main/resources/application.properties')
                        .read_text(encoding='utf-8').splitlines() if '=' in line and not line.lstrip().startswith('#'))
        def setting(name, env):
            value = os.environ.get(env, settings.get(name, ''))
            match = re.fullmatch(r'\$\{([^:}]+)(?::(.*))?\}', value)
            return os.environ.get(match[1], match[2] or '') if match else value
        url = setting('spring.datasource.url', 'SPRING_DATASOURCE_URL')
        if url.split('?', 1)[0] != 'jdbc:mysql://localhost:3306/myproject':
            raise CatalogError('Only the fixed local localhost:3306/myproject database is supported.')
        self._user = setting('spring.datasource.username', 'SPRING_DATASOURCE_USERNAME')
        self._env = {**os.environ, 'MYSQL_PWD': setting('spring.datasource.password', 'SPRING_DATASOURCE_PASSWORD')}
        self.args = ['--protocol=TCP', '--host=127.0.0.1', '--port=3306', '--connect-timeout=5',
                     '--default-character-set=utf8mb4', '--user=' + self._user]

    def run(self, sql):
        try:
            result = subprocess.run([str(MYSQL_BIN / 'mysql.exe'), *self.args, '--database=myproject',
                                     '--batch', '--raw', '--skip-column-names'], input=sql, encoding='utf-8',
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self._env, timeout=60)
        except (OSError, subprocess.TimeoutExpired):
            raise CatalogError('Local catalog query failed or timed out; connection details withheld.') from None
        if result.returncode:
            raise CatalogError('Local catalog query failed; SQL/credentials/server output withheld.')
        return result.stdout

    def snapshot(self):
        count = int(self.run('SELECT COUNT(*) FROM contents;').strip())
        if count > 5000:
            raise CatalogError('Catalog exceeds the 5000-row inspection bound.')
        exists = self.run("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='myproject' "
                          f"AND TABLE_NAME='{SOURCE_TABLE}';").strip() == '1'
        engines = self.run("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA='myproject' "
                           f"AND TABLE_NAME IN ('contents','{SOURCE_TABLE}');").splitlines()
        if len(engines) != (2 if exists else 1) or any(engine != 'InnoDB' for engine in engines):
            raise CatalogError('Catalog tables must use InnoDB for atomic imports.')
        sql = 'SET SESSION group_concat_max_len=2000000; START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;\n'
        sql += f"SELECT JSON_OBJECT('digest',{db_digest_sql(exists)});\n"
        sql += 'SELECT ' + ROW_JSON + ' FROM contents ORDER BY id LIMIT 5001;\n'
        sql += "SELECT '__SOURCES__';\n"
        if exists:
            sql += f'SELECT {SOURCE_JSON} FROM {SOURCE_TABLE} ORDER BY media_type,tmdb_id LIMIT 5001;\n'
        lines = self.run(sql + 'ROLLBACK;').splitlines()
        marker = lines.index('__SOURCES__')
        if marker > 5001 or len(lines) - marker - 1 > 5000:
            raise CatalogError('Catalog exceeds the inspection bound.')
        return {'digest': json.loads(lines[0])['digest'],
                'contents': [json.loads(line) for line in lines[1:marker]],
                'sources': [json.loads(line) for line in lines[marker + 1:]], 'source_table_exists': exists}

    def backup(self, snapshot):
        folder = ROOT / '.local-db-backups' / ('catalog-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
        folder.mkdir(parents=True, exist_ok=False)
        path = folder / 'catalog.sql'
        tables = ['contents'] + ([SOURCE_TABLE] if snapshot['source_table_exists'] else [])
        # mysql.exe accepts connect-timeout; mysqldump.exe does not. The subprocess deadline still applies.
        dump_args = [arg for arg in self.args if not arg.startswith('--connect-timeout=')]
        result = subprocess.run([str(MYSQL_BIN / 'mysqldump.exe'), *dump_args, '--single-transaction',
                                 '--skip-add-drop-table', '--no-tablespaces', '--set-gtid-purged=OFF',
                                 '--result-file=' + str(path), 'myproject', *tables],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self._env, timeout=60)
        if result.returncode or not path.exists() or path.stat().st_size == 0:
            raise CatalogError('Catalog backup failed; import cancelled.')
        write_json(folder / 'manifest.json', {'before_digest': snapshot['digest'],
                   'dump_sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        return folder

    def apply(self, dataset, reviewed):
        before = self.snapshot()
        fresh = plan_import(dataset, before, reviewed['target_total'])
        if fresh != reviewed:
            raise CatalogError('Catalog/dataset/plan changed. Generate and review a new plan.')
        if not fresh['target_reached']:
            raise CatalogError('Candidate pool cannot reach the target; collect more before applying.')
        if not fresh['additions']:
            return {'added': 0, 'total': fresh['before_total']}
        backup = self.backup(before)
        self.run(import_sql(fresh))
        after = self.snapshot()
        original = {r['id']: r for r in before['contents']}
        if (any(original[r['id']] != r for r in after['contents'] if r['id'] in original)
                or not set(original).issubset(r['id'] for r in after['contents'])
                or len(after['contents']) != fresh['after_total']):
            raise CatalogError('Post-import verification needs review; backup retained. Do not reapply blindly.')
        report = {'added': len(fresh['additions']), 'total': len(after['contents']),
                  'types': dict(Counter(r['type'] for r in after['contents'])), 'backup': str(backup)}
        write_json(backup / 'result.json', report)
        return report


def import_sql(plan):
    if not re.fullmatch('[0-9a-f]{64}', plan['before_digest']):
        raise CatalogError('Invalid catalog fingerprint.')
    sql = f"""
CREATE TABLE IF NOT EXISTS {SOURCE_TABLE} (
 media_type VARCHAR(10) NOT NULL, tmdb_id BIGINT NOT NULL, content_id BIGINT NOT NULL,
 fetched_at DATETIME(6) NOT NULL, PRIMARY KEY(media_type,tmdb_id), UNIQUE KEY uq_catalog_import_content(content_id),
 CONSTRAINT fk_catalog_import_content FOREIGN KEY(content_id) REFERENCES contents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET SESSION group_concat_max_len=2000000;
SET SESSION innodb_lock_wait_timeout=5;
CREATE TEMPORARY TABLE catalog_import_gate (ok INT NOT NULL CHECK(ok=1));
INSERT INTO catalog_import_gate VALUES(IF(GET_LOCK('recommend.catalog.import',5)=1,1,0));
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION;
SELECT id FROM contents ORDER BY id FOR UPDATE;
SELECT content_id FROM {SOURCE_TABLE} ORDER BY media_type,tmdb_id FOR UPDATE;
INSERT INTO catalog_import_gate VALUES(IF({db_digest_sql()}='{plan['before_digest']}',1,0));
"""
    fields = FIELDS[1:]
    for row in plan['additions']:
        sql += 'INSERT INTO contents (' + ','.join('`' + f + '`' for f in fields) + ') VALUES ('
        sql += ','.join(sql_value(row[f]) for f in fields) + ');\n'
        sql += f'INSERT INTO {SOURCE_TABLE}(media_type,tmdb_id,content_id,fetched_at) VALUES ('
        sql += sql_value(row['type']) + ',' + sql_value(row['tmdb_id']) + ',LAST_INSERT_ID(),UTC_TIMESTAMP(6));\n'
    return sql + "COMMIT; SELECT RELEASE_LOCK('recommend.catalog.import');\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['inventory', 'collect', 'plan', 'apply'])
    parser.add_argument('--dataset', type=Path, default=OUTPUT / 'tmdb-ko.json')
    parser.add_argument('--plan', type=Path, default=OUTPUT / 'import-plan.json')
    parser.add_argument('--target-total', type=int, default=500)
    args = parser.parse_args()
    if not 1 <= args.target_total <= 1000:
        raise CatalogError('Target must be 1..1000 total works.')
    if args.command == 'collect':
        dataset = collect(TmdbClient(), args.target_total,
                          progress=lambda message: print(message, file=sys.stderr, flush=True))
        validate_dataset(dataset)
        write_json(args.dataset, dataset)
        print(json.dumps({'candidate_count': len(dataset['records']), 'dataset': str(args.dataset)}, ensure_ascii=False))
        return
    db = LocalCatalog()
    if args.command == 'inventory':
        snapshot = db.snapshot()
        print(json.dumps({'total': len(snapshot['contents']),
                          'types': dict(Counter(r['type'] for r in snapshot['contents'])),
                          'genres': dict(Counter(g for r in snapshot['contents'] for g in (r['genres'] or '').split(',') if g)),
                          'mapped_tmdb': len(snapshot['sources'])}, ensure_ascii=False, indent=2))
        return
    dataset = read_json(args.dataset)
    if args.command == 'plan':
        plan = plan_import(dataset, db.snapshot(), args.target_total)
        write_json(args.plan, plan)
        print(json.dumps({k: v for k, v in plan.items() if k != 'additions'}, ensure_ascii=False, indent=2))
    else:
        print(json.dumps(db.apply(dataset, read_json(args.plan)), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (CatalogError, OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        print(str(error) if isinstance(error, CatalogError) else 'Catalog command failed; private details withheld.', file=sys.stderr)
        sys.exit(1)
