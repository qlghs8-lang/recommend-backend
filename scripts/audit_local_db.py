"""Read-only local schema inventory and aggregate integrity checks. No row data output.

Run: python scripts/audit_local_db.py
Uses the existing properties credentials internally; never loads .env or starts JPA.
"""
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MYSQL = 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'


def audit():
    settings = dict(line.split('=', 1) for line in
                    (ROOT / 'src/main/resources/application.properties').read_text().splitlines()
                    if '=' in line and not line.lstrip().startswith('#'))
    url = settings['spring.datasource.url']
    if url.split('?', 1)[0] != 'jdbc:mysql://localhost:3306/myproject':
        raise RuntimeError('Expected the fixed local myproject database; refusing another target.')

    queries = {
        'columns': "SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='myproject' ORDER BY TABLE_NAME,ORDINAL_POSITION",
        'indexes': "SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='myproject' ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX",
        'foreign_keys': "SELECT TABLE_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='myproject' AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY TABLE_NAME,COLUMN_NAME",
        'engines': "SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA='myproject' ORDER BY TABLE_NAME",
        'duplicate_state_groups': "SELECT COUNT(*) FROM (SELECT user_id,content_id,event_type FROM user_content_interactions WHERE event_type IN ('LIKE','DISLIKE','BOOKMARK') GROUP BY user_id,content_id,event_type HAVING COUNT(*)>1) d",
        'conflicting_reaction_pairs': "SELECT COUNT(*) FROM (SELECT user_id,content_id FROM user_content_interactions WHERE event_type IN ('LIKE','DISLIKE') GROUP BY user_id,content_id HAVING COUNT(DISTINCT event_type)>1) d",
        'orphan_interactions': "SELECT COUNT(*) FROM user_content_interactions i LEFT JOIN users u ON u.id=i.user_id LEFT JOIN contents c ON c.id=i.content_id WHERE u.id IS NULL OR c.id IS NULL",
        'orphan_terms': "SELECT COUNT(*) FROM user_terms_agreements t LEFT JOIN users u ON u.id=t.user_id WHERE u.id IS NULL",
        'orphan_recommendations': "SELECT COUNT(*) FROM recommend_logs r LEFT JOIN users u ON u.id=r.user_id LEFT JOIN contents c ON c.id=r.content_id WHERE u.id IS NULL OR c.id IS NULL",
        'invalid_click_references': "SELECT COUNT(*) FROM recommend_click_logs k LEFT JOIN recommend_logs r ON r.id=k.recommend_log_id LEFT JOIN users u ON u.id=k.user_id LEFT JOIN contents c ON c.id=k.content_id WHERE r.id IS NULL OR u.id IS NULL OR c.id IS NULL OR r.user_id<>k.user_id OR r.content_id<>k.content_id",
        'negative_or_null_views': "SELECT COUNT(*) FROM contents WHERE view_count IS NULL OR view_count<0",
        'invalid_token_versions': "SELECT COUNT(*) FROM users WHERE token_version IS NULL OR token_version<0",
        'unexposed_recommendations': "SELECT COUNT(*) FROM recommend_logs WHERE exposed_at IS NULL",
        'clicks_without_exposure': "SELECT COUNT(*) FROM recommend_click_logs k JOIN recommend_logs r ON r.id=k.recommend_log_id WHERE r.exposed_at IS NULL",
    }
    # One consistent read-only transaction for all data counts. Metadata can still
    # change during concurrent DDL; this is an audit snapshot, not a migration gate.
    statements = ['SET SESSION MAX_EXECUTION_TIME=10000',
                  'SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ',
                  'START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY']
    for name, sql in queries.items():
        statements.extend([f"SELECT '__SECTION__{name}'", sql])
    statements.append('ROLLBACK')
    result = subprocess.run([
        MYSQL, '--protocol=TCP', '--host=127.0.0.1', '--port=3306',
        '--connect-timeout=5', '--user=' + settings['spring.datasource.username'],
        '--database=myproject', '--batch', '--raw', '--skip-column-names',
    ], input=';\n'.join(statements) + ';\n', text=True, encoding='utf-8',
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180,
        env={**os.environ, 'MYSQL_PWD': settings['spring.datasource.password']})
    if result.returncode:
        raise RuntimeError('Read-only audit failed; server output withheld to protect credentials.')
    report = {}
    section = None
    for line in result.stdout.splitlines():
        if line.startswith('__SECTION__'):
            section = line.removeprefix('__SECTION__')
            report[section] = []
        elif section:
            report[section].append(line.split('\t'))
    if set(report) != set(queries):
        raise RuntimeError('Incomplete audit output.')
    return report


if __name__ == '__main__':
    print(json.dumps(audit(), ensure_ascii=False, indent=2))
