"""Explicit local MySQL migration: prepare (backup + restore rehearsal), then apply.

Uses installed MySQL CLI; credentials stay in the child process environment.
Never runs application startup or Hibernate schema updates.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BACKUP = ROOT / '.local-db-backups' / '20261111-token-exposure'
BIN = Path('C:/Program Files/MySQL/MySQL Server 8.0/bin')
SOURCE = 'myproject'
CLONE = 'recommend_validation_20261111'
SQL = [
    'ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0, ALGORITHM=INSTANT',
    'ALTER TABLE recommend_logs ADD COLUMN exposed_at DATETIME(6) NULL, ALGORITHM=INSTANT',
    'ALTER TABLE recommend_logs ADD INDEX idx_rl_exposed_at (exposed_at), ALGORITHM=INPLACE, LOCK=NONE',
]
settings = dict(line.split('=', 1) for line in
                (ROOT / 'src/main/resources/application.properties').read_text().splitlines()
                if '=' in line and not line.startswith('#'))
assert settings['spring.datasource.url'].startswith('jdbc:mysql://localhost:3306/myproject?')
ENV = {**os.environ, 'MYSQL_PWD': settings['spring.datasource.password']}
ARGS = ['--protocol=TCP', '--host=127.0.0.1', '--port=3306',
        '--user=' + settings['spring.datasource.username'], '--default-character-set=utf8mb4']

def run(binary, args, data=None):
    result = subprocess.run([str(BIN / binary), *ARGS, *args], input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=ENV)
    if result.returncode:
        # Server errors may contain private values: retain locally, never print them.
        BACKUP.mkdir(parents=True, exist_ok=True)
        (BACKUP / 'last-error.txt').write_bytes(result.stderr)
        raise RuntimeError('MySQL command failed; inspect private last-error.txt locally')
    return result.stdout

def query(sql, db=None):
    return run('mysql.exe', ['--batch', '--raw', '--skip-column-names',
                            *(['--database=' + db] if db else []), '--execute=' + sql]).decode('utf-8').replace('\r', '')

def guard():
    assert query('SELECT VERSION()').strip().startswith('8.0.')
    active = query("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID <> CONNECTION_ID() "
                   "AND USER NOT IN ('event_scheduler','system user')").strip()
    assert active == '0', 'Other DB clients exist; stop application writers before migration'
    assert query('SELECT COUNT(*) FROM information_schema.INNODB_TRX').strip() == '0'

def schema_columns(db):
    rows = query(f"SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='{db}' "
                 'ORDER BY TABLE_NAME,ORDINAL_POSITION').splitlines()
    columns = {}
    for row in rows:
        table, col = row.split('\t')
        assert '`' not in table + col
        columns.setdefault(table, []).append(col)
    return columns

def snapshot(db, columns):
    result = {}
    for table, cols in columns.items():
        select = ','.join('`' + col + '`' for col in cols)
        # Sort serialized rows, independent of query execution order.
        rows = sorted(query(f'SELECT {select} FROM `{table}`', db).splitlines())
        result[table] = {'rows': int(query(f'SELECT COUNT(*) FROM `{table}`', db)),
                         'sha256': hashlib.sha256('\n'.join(rows).encode()).hexdigest()}
    return result

def migrate(db):
    for sql in SQL:
        query('SET SESSION lock_wait_timeout=5; SET SESSION innodb_lock_wait_timeout=5; ' + sql, db)

def verify(db):
    cols = query(f"SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,IFNULL(COLUMN_DEFAULT,'<NULL>') "
                 f"FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='{db}' AND "
                 "((TABLE_NAME='users' AND COLUMN_NAME='token_version') OR "
                 "(TABLE_NAME='recommend_logs' AND COLUMN_NAME='exposed_at')) ORDER BY TABLE_NAME").strip()
    assert cols == 'recommend_logs\texposed_at\tdatetime(6)\tYES\t<NULL>\nusers\ttoken_version\tbigint\tNO\t0', cols
    assert query(f"SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='{db}' "
                 "AND TABLE_NAME='recommend_logs' AND INDEX_NAME='idx_rl_exposed_at'").strip() == 'exposed_at'
    assert query('SELECT COUNT(*) FROM users WHERE token_version <> 0', db).strip() == '0'
    assert query('SELECT COUNT(*) FROM recommend_logs WHERE exposed_at IS NOT NULL', db).strip() == '0'

def prepare():
    guard()
    assert not BACKUP.exists(), 'Backup run already exists; do not overwrite'
    assert not query(f"SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{CLONE}'").strip()
    columns = schema_columns(SOURCE)
    assert 'token_version' not in columns['users'] and 'exposed_at' not in columns['recommend_logs']
    for kind in ['TRIGGERS', 'ROUTINES', 'EVENTS']:
        field = {'TRIGGERS': 'TRIGGER_SCHEMA', 'ROUTINES': 'ROUTINE_SCHEMA', 'EVENTS': 'EVENT_SCHEMA'}[kind]
        assert query(f"SELECT COUNT(*) FROM information_schema.{kind} WHERE {field}='{SOURCE}'").strip() == '0', 'Review programmable objects before cloning'
    assert query(f"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='{SOURCE}' AND ENGINE <> 'InnoDB'").strip() == '0'
    BACKUP.mkdir(parents=True)
    before = snapshot(SOURCE, columns)
    dump = BACKUP / 'myproject.sql'
    run('mysqldump.exe', ['--single-transaction', '--routines', '--events', '--triggers', '--hex-blob',
                         '--set-gtid-purged=OFF', '--no-tablespaces', '--result-file=' + str(dump), SOURCE])
    assert snapshot(SOURCE, columns) == before, 'Source changed during backup'
    query(f'CREATE DATABASE `{CLONE}` CHARACTER SET utf8mb4')
    run('mysql.exe', ['--database=' + CLONE], dump.read_bytes())
    assert snapshot(CLONE, columns) == before, 'Restore differs from source'
    migrate(CLONE)
    finish_rehearsal(columns, before, dump)

def finish_rehearsal(columns, before, dump):
    verify(CLONE)
    assert snapshot(CLONE, columns) == before, 'Migration changed legacy data'
    # Smoke test new writes on the clone only, rolled back in one connection.
    smoke = query('START TRANSACTION; UPDATE users SET token_version=token_version+1 ORDER BY id LIMIT 1; '
                  'SELECT SUM(token_version) FROM users; '
                  'UPDATE recommend_logs SET exposed_at=NOW(6) ORDER BY id LIMIT 1; '
                  'SELECT COUNT(*) FROM recommend_logs WHERE exposed_at IS NOT NULL; ROLLBACK;', CLONE)
    assert smoke.strip() == '1\n1', smoke
    verify(CLONE)
    manifest = {'source': SOURCE, 'clone': CLONE, 'columns': columns, 'before': before,
                'dump_sha256': hashlib.sha256(dump.read_bytes()).hexdigest(), 'sql': SQL, 'rehearsal': 'passed'}
    (BACKUP / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print(json.dumps({'rehearsal': 'passed', 'tables': {k: v['rows'] for k, v in before.items()},
                      'backup': str(dump), 'clone': CLONE}))

def resume_rehearsal():
    guard()
    columns = schema_columns(SOURCE)
    assert 'token_version' not in columns['users'] and 'exposed_at' not in columns['recommend_logs']
    finish_rehearsal(columns, snapshot(SOURCE, columns), BACKUP / 'myproject.sql')

def apply():
    guard()
    manifest = json.loads((BACKUP / 'manifest.json').read_text())
    assert manifest['rehearsal'] == 'passed' and manifest['sql'] == SQL
    assert hashlib.sha256((BACKUP / 'myproject.sql').read_bytes()).hexdigest() == manifest['dump_sha256']
    assert snapshot(SOURCE, manifest['columns']) == manifest['before'], 'Source changed; fresh rehearsal required'
    assert schema_columns(SOURCE) == manifest['columns'], 'Schema changed; do not repeat DDL'
    migrate(SOURCE)
    verify(SOURCE)
    assert snapshot(SOURCE, manifest['columns']) == manifest['before'], 'Legacy data differs'
    manifest['applied'] = 'passed; schema/default/index and all legacy data verified'
    (BACKUP / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print('APPLIED: schema, defaults, index, all legacy row counts and data hashes verified')

if __name__ == '__main__':
    {'prepare': prepare, 'apply': apply, 'resume-rehearsal': resume_rehearsal}[sys.argv[1]]()
