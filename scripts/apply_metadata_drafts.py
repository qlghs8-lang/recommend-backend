"""Prepare a pinned execution plan; apply stores only UNREVIEWED/inactive metadata.

prepare --collection PATH --output NEW_DIR
apply --plan PLAN_JSON --plan-sha256 SHA --output NEW_DIR
No provider calls, activation, Content updates, deletion, or automatic retry.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys

import metadata_storage_dry_run as stage
from catalog_pool import CatalogError, LocalCatalog, ROOT, digest, sql_value, write_json

POLICY = 'metadata-draft-apply-v1'
COLLECTION_SHA = '9cb6bff327f7c73403bbbf66ac65c81fa6fea9dcc724d580e827e49e22818954'
PROJECTION_SHA = '15d49dbc6f90487ceac359fa4a82adb2d05bf5eacf076593f7d10ca7efd9391c'
LOCK = 'recommend.metadata_drafts.v1'
CODE_FILES = ('apply_metadata_drafts.py','metadata_storage_dry_run.py','metadata_enrichment_dry_run.py',
              'catalog_pool.py','audit_metadata_enrichment.py','audit_recommendation_landscape.py')
INPUT_FILES = ('collection.json','review.json','public-snapshot.json','mapping-snapshot.json')
ARTIFACTS = ('before.json','environment.json','storage-plan.json','migration.sql','transaction.sql')
require = stage.require


def file_sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def code_hashes(): return {name:file_sha(ROOT/'scripts'/name) for name in CODE_FILES}


def checked_projection(bundle):
    require(bundle['expected_hash'] == COLLECTION_SHA, 'Only the pinned collected batch is supported.')
    projected = stage.projection(bundle)
    require(projected['projection_sha256'] == PROJECTION_SHA, 'Pinned projection differs.')
    return projected


def identity(db):
    require(type(db) is LocalCatalog or isinstance(db,stage.OwnedScratch), 'Unsupported database adapter.')
    result = stage.collect.strict_json(db.run("SELECT JSON_OBJECT('database',DATABASE(),'server_uuid',@@server_uuid,'server_version',@@version);").strip())
    if isinstance(db,stage.OwnedScratch):
        require(db.owned and result['database'] == db.schema and re.fullmatch(stage.PREFIX+'[a-f0-9]{12}',db.schema))
    else: require(result['database'] == 'myproject', 'Only the fixed local application target is supported.')
    return result


BASE_WHERE = "TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='contents' AND COLUMN_NAME IN (" + ','.join(sql_value(f) for f in stage.FIELDS) + ")) OR (TABLE_NAME='catalog_import_sources' AND COLUMN_NAME IN ('media_type','tmdb_id','content_id')))"
BASE_JSON = "JSON_OBJECT('table',TABLE_NAME,'name',COLUMN_NAME,'column_type',COLUMN_TYPE,'nullable',IS_NULLABLE,'collation',COLLATION_NAME,'default',COLUMN_DEFAULT,'extra',EXTRA,'generation',GENERATION_EXPRESSION)"


def environment(db):
    output=db.run('SELECT '+BASE_JSON+' FROM information_schema.COLUMNS WHERE '+BASE_WHERE+' ORDER BY TABLE_NAME,COLUMN_NAME LIMIT 12;')
    rows=[stage.collect.strict_json(line) for line in output.splitlines() if line]
    require(len(rows)==11, 'Public catalog/mapping column schema is incomplete.')
    return {'identity':identity(db),'base_columns':rows}


def environment_guard(env):
    target=env['identity']
    condition=' AND '.join('BINARY '+expr+'=BINARY '+sql_value(target[field]) for expr,field in
                           [('DATABASE()','database'),('@@server_uuid','server_uuid'),('@@version','server_version')])
    condition += ' AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE '+BASE_WHERE+')=11'
    for row in env['base_columns']:
        condition += ' AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE '+BASE_WHERE+' AND TABLE_NAME='+sql_value(row['table'])+' AND COLUMN_NAME='+sql_value(row['name'])+' AND '+BASE_JSON+'=CAST('+sql_value(stage.canonical(row))+' AS JSON))=1'
    return condition


def read_current(db,bundle,projected):
    env=environment(db); before=stage.read_state(db)
    require(digest(env)==digest(environment(db)) and digest(before)==digest(stage.read_state(db)), 'Target changed during preflight.')
    status=stage.check_state(bundle,projected,before)
    return env,before,status


def migration_sql():
    return '-- Draft-only migration. DDL is outside the data transaction; no automatic DROP/repair.\n'+'\n'.join(stage.schema_sql().splitlines()[1:])+'\n'


def seal(plan):
    plan['plan_sha256']=digest({k:v for k,v in plan.items() if k!='plan_sha256'})
    return plan


def prepare_plan(db,collection,output):
    collection=Path(collection)
    bundle=stage.load_bundle(collection,COLLECTION_SHA); projected=checked_projection(bundle)
    env,before,status=read_current(db,bundle,projected)
    output=Path(output); output.mkdir(parents=True,exist_ok=False)
    input_dir=output/'input'; input_dir.mkdir()
    for name in INPUT_FILES: shutil.copyfile(collection.with_name(name),input_dir/name)
    # Re-validate the private plan's immutable input copies before publishing a plan.
    copied=stage.load_bundle(input_dir/'collection.json',COLLECTION_SHA)
    require(digest(checked_projection(copied))==digest(projected),'Input copies differ.')
    write_json(output/'before.json',before); write_json(output/'environment.json',env)
    write_json(output/'storage-plan.json',projected)
    (output/'migration.sql').write_text(migration_sql(),encoding='utf-8')
    (output/'transaction.sql').write_text(stage.transaction_sql(bundle,projected,before,status),encoding='utf-8')
    plan=seal({'policy':POLICY,'target':env['identity'],'collection_sha256':COLLECTION_SHA,'projection_sha256':PROJECTION_SHA,
        'code_sha256':code_hashes(),'inputs':{n:file_sha(input_dir/n) for n in INPUT_FILES},
        'artifacts':{n:file_sha(output/n) for n in ARTIFACTS},'before_sha256':digest(before),
        'environment_sha256':digest(env),'prepared_status':status,
        'create_tables_if_absent':list(stage.TABLES) if not before['schema_present'] else [],
        'counts':{t:len(r) for t,r in projected['rows'].items()},'approved':False,'active':False,
        'content_updates':0,'provider_calls':0,'automatic_retry':False,'mode':'PREPARED_NOT_APPLIED'})
    write_json(output/'plan.json',plan)
    return plan


def load_plan(path,expected_sha):
    require(isinstance(expected_sha,str) and re.fullmatch('[a-f0-9]{64}',expected_sha),'Explicit plan hash required.')
    path=Path(path); folder=path.parent; plan=stage.collect.load_json(path)
    require(plan.get('plan_sha256')==expected_sha==digest({k:v for k,v in plan.items() if k!='plan_sha256'}),'Execution plan hash differs.')
    require(plan['policy']==POLICY and plan['mode']=='PREPARED_NOT_APPLIED' and plan['code_sha256']==code_hashes(), 'Plan policy/code changed; prepare again.')
    require(set(plan['inputs'])==set(INPUT_FILES) and set(plan['artifacts'])==set(ARTIFACTS))
    for name in INPUT_FILES: require(file_sha(folder/'input'/name)==plan['inputs'][name],'Pinned input changed.')
    for name in ARTIFACTS: require(file_sha(folder/name)==plan['artifacts'][name],'Prepared artifact changed.')
    bundle=stage.load_bundle(folder/'input/collection.json',COLLECTION_SHA); projected=checked_projection(bundle)
    before=stage.collect.load_json(folder/'before.json'); env=stage.collect.load_json(folder/'environment.json')
    require(plan['collection_sha256']==COLLECTION_SHA and plan['projection_sha256']==PROJECTION_SHA)
    require(plan['target']==env['identity'] and plan['environment_sha256']==digest(env) and plan['before_sha256']==digest(before))
    status=stage.check_state(bundle,projected,before)
    require(plan['prepared_status']==status and digest(stage.collect.load_json(folder/'storage-plan.json'))==digest(projected))
    require((folder/'migration.sql').read_text(encoding='utf-8')==migration_sql())
    require((folder/'transaction.sql').read_text(encoding='utf-8')==stage.transaction_sql(bundle,projected,before,status))
    require(plan['counts']=={t:len(r) for t,r in projected['rows'].items()} and plan['approved'] is False and plan['active'] is False
            and plan['content_updates']==0 and plan['provider_calls']==0 and plan['automatic_retry'] is False)
    require(plan['create_tables_if_absent']==(list(stage.TABLES) if not before['schema_present'] else []))
    return plan,bundle,projected,before,env


def allowed_transition(bundle,projected,prepared,current,status):
    require(digest(prepared['catalog'])==digest(current['catalog']) and digest(prepared['mappings'])==digest(current['mappings']))
    if digest(prepared)==digest(current): return
    require(not prepared['schema_present'] or current['schema_present'], 'Prepared schema disappeared.')
    # A complete compatible empty migration can survive DDL failure/connection loss.
    migrated=dict(current,schema_present=prepared['schema_present'])
    if digest(prepared)==digest(migrated): return
    # Only an exact new batch may appear after a pending plan (including lost ACK).
    require(stage.check_state(bundle,projected,prepared)=='PENDING_DRAFT_STORAGE' and status=='ALREADY_STORED_UNREVIEWED', 'Prepared draft state changed.')
    for table in stage.TABLES:
        others=[row for row in current['metadata'][table] if row['batch_sha']!=projected['batch_sha']]
        require(digest(others)==digest(prepared['metadata'][table]),'Unrelated metadata changed since preparation.')


def execution_sql(bundle,projected,current,status,env):
    # The advisory lock spans all DDL/DML on one CLI connection; it is not a row lock.
    prefix="""-- PINNED_DRAFT_APPLICATION
SET SESSION lock_wait_timeout=5;
CREATE TEMPORARY TABLE metadata_apply_gate(ok INT PRIMARY KEY);
INSERT INTO metadata_apply_gate VALUES(1);
"""
    gate=lambda condition:'INSERT INTO metadata_apply_gate SELECT 1 WHERE COALESCE(('+condition+'),0)=0;\n'
    prefix+=gate('GET_LOCK('+sql_value(LOCK)+',5)=1')+gate(environment_guard(env))
    if not current['schema_present']:
        names=','.join(sql_value(t) for t in stage.TABLES)
        prefix+=gate('(SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('+names+'))=0')
        prefix+=migration_sql()
    else: prefix+=gate(stage.schema_valid_sql())
    transaction=stage.transaction_sql(bundle,projected,current,status)
    # Recheck the bound target/base column definitions while the data locks are held.
    transaction=transaction.replace('SET @metadata_created_at=UTC_TIMESTAMP(6);',stage.guard(environment_guard(env))+'SET @metadata_created_at=UTC_TIMESTAMP(6);',1) if status=='PENDING_DRAFT_STORAGE' else transaction.replace("COMMIT; SELECT '__METADATA_DRAFT_COMMITTED__';",stage.guard(environment_guard(env))+"COMMIT; SELECT '__METADATA_DRAFT_COMMITTED__';",1)
    sql=prefix+transaction+'SELECT RELEASE_LOCK('+sql_value(LOCK)+"); SELECT '__METADATA_APPLICATION_ACK__';\n"
    require(len(sql.encode('utf-8'))<=10_000_000,'Application transaction exceeds size budget.')
    return sql


def apply_plan(db,path,expected_sha,output):
    # Validate ALL offline inputs/code/SQL before connecting or creating an attempt directory.
    plan,bundle,projected,prepared,expected_env=load_plan(path,expected_sha)
    env,current,status=read_current(db,bundle,projected)
    require(digest(env)==digest(expected_env),'Target identity/base schema changed; prepare again.')
    allowed_transition(bundle,projected,prepared,current,status)
    output=Path(output); output.mkdir(parents=True,exist_ok=False)
    write_json(output/'before.json',current); write_json(output/'environment.json',env)
    backup={'before_sha256':digest(current),'environment_sha256':digest(env),'plan_sha256':expected_sha,
            'scope':'PUBLIC_8_FIELDS_MAPPING_3_FIELDS_AND_6_DRAFT_TABLES_ONLY','full_database_backup':False}
    write_json(output/'backup-manifest.json',backup)
    require(digest(stage.collect.load_json(output/'before.json'))==backup['before_sha256']
            and digest(stage.collect.load_json(output/'environment.json'))==backup['environment_sha256'],'Attempt backup readback failed.')
    sql=execution_sql(bundle,projected,current,status,env)
    (output/'execution.sql').write_text(sql,encoding='utf-8')
    result={'state':'STARTED_NOT_ACKNOWLEDGED','plan_sha256':expected_sha,'automatic_retry':False,
            'ddl_outside_data_transaction':True,'schema_may_exist':True,'content_updates':0,'approved':False,'active':False}
    write_json(output/'result.json',result)  # durable status before first DDL/DML
    try:
        ack=db.run(sql)
        require('__METADATA_DRAFT_COMMITTED__' in ack and '__METADATA_APPLICATION_ACK__' in ack,'Commit acknowledgement missing; inspect explicitly before retry.')
        after_env,after,after_status=read_current(db,bundle,projected)
        require(digest(after_env)==digest(env) and after_status=='ALREADY_STORED_UNREVIEWED')
        allowed_transition(bundle,projected,current,after,after_status)
        if status=='ALREADY_STORED_UNREVIEWED': require(digest(current)==digest(after),'No-op replay changed rows or timestamps.')
        write_json(output/'after.json',after)
        result.update(state='STORED_UNREVIEWED' if status=='PENDING_DRAFT_STORAGE' else status,
            inserted=sum(plan['counts'].values()) if status=='PENDING_DRAFT_STORAGE' else 0,counts=plan['counts'],
            created_tables=not current['schema_present'],projection_sha256=PROJECTION_SHA,postcheck='MATCH',
            application_db_writes=isinstance(db,LocalCatalog) and status=='PENDING_DRAFT_STORAGE')
        write_json(output/'result.json',result)
        return result
    except (CatalogError,OSError):
        result['state']='FAILED_OR_UNACKNOWLEDGED'
        write_json(output/'result.json',result)
        raise


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__); sub=parser.add_subparsers(dest='command',required=True)
    prepare=sub.add_parser('prepare'); prepare.add_argument('--collection',type=Path,required=True)
    apply=sub.add_parser('apply'); apply.add_argument('--plan',type=Path,required=True); apply.add_argument('--plan-sha256',required=True)
    for command in (prepare,apply): command.add_argument('--output',type=Path,required=True)
    args=parser.parse_args(argv)
    try:
        require(not args.output.exists(),'Existing output will not be overwritten.')
        if args.command=='apply':
            load_plan(args.plan,args.plan_sha256)  # invalid plan must not even initialize the connection adapter
            report=apply_plan(LocalCatalog(),args.plan,args.plan_sha256,args.output)
        else:
            checked_projection(stage.load_bundle(args.collection,COLLECTION_SHA))
            report=prepare_plan(LocalCatalog(),args.collection,args.output)
        print(json.dumps({'output':str(args.output),'state':report.get('state',report.get('mode')),
            'plan_sha256':report['plan_sha256'],'counts':report['counts'],'content_updates':0,'approved':False,'active':False},ensure_ascii=False))
        return 0
    except (CatalogError,ValueError,KeyError,TypeError,OSError,RecursionError):
        print('Metadata draft operation failed; no automatic retry. Database/SQL/credential details withheld.',file=sys.stderr)
        return 1


if __name__=='__main__': raise SystemExit(main())
