"""Run opt-in JPA tests against a new empty local schema, never the application DB."""
import subprocess
import sys
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
settings = dict(line.split('=', 1) for line in
                (ROOT / 'src/main/resources/application.properties').read_text().splitlines()
                if '=' in line and not line.startswith('#'))


def query(sql):
    result = subprocess.run([
        'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe',
        '--protocol=TCP', '--host=127.0.0.1', '--port=3306',
        '--user=' + settings['spring.datasource.username'],
        '--batch', '--raw', '--skip-column-names', '--execute=' + sql,
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        env={**os.environ, 'MYSQL_PWD': settings['spring.datasource.password']})
    if result.returncode:
        raise RuntimeError('Local test schema access failed; credentials and server output withheld.')
    return result.stdout.decode('utf-8').replace('\r', '')

SCHEMA = 'recommend_concurrency_test'

if __name__ == '__main__':
    if query(f"SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{SCHEMA}'").strip():
        if query(f"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='{SCHEMA}'").strip() != '0':
            raise SystemExit('Dedicated test schema contains tables; inspect before a new run.')
    else:
        query(f'CREATE DATABASE `{SCHEMA}` CHARACTER SET utf8mb4')
    # Hibernate creates and drops only synthetic tables in the fixed dedicated schema.
    # Leave the empty schema available for subsequent runs; never erase existing tables here.
    result = subprocess.run(['cmd.exe', '/c', 'mvnw.cmd', 'test', '-DmysqlConcurrency=true',
                             '-Dtest=MySqlConcurrencyTest'], cwd=ROOT)
    sys.exit(result.returncode)
