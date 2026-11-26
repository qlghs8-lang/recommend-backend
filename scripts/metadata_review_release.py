"""Offline, pinned review-version/release-candidate planner. No application apply command.

template/plan --collection FILE --output NEW_DIR [--decisions FILE]
Reviewer identities are declared file metadata, not authenticated identities.
Only opt-in tests can persist inactive candidates to a freshly owned MySQL schema.
"""
import argparse
import hashlib
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import re
import sys

import metadata_storage_dry_run as stage
from catalog_pool import CatalogError, ROOT, digest, sql_value, write_json

POLICY = 'metadata-review-release-dry-run-v1'
DECISIONS_POLICY = 'metadata-review-decisions-v1'
COLLECTION_SHA = '9cb6bff327f7c73403bbbf66ac65c81fa6fea9dcc724d580e827e49e22818954'
PROJECTION_SHA = '15d49dbc6f90487ceac359fa4a82adb2d05bf5eacf076593f7d10ca7efd9391c'
REVIEW_SHA = '88811fc8632bae1fa15c336335db37a578908ec7be01e62eb5fceef33e9cf35a'
REVIEW_PLAN = ROOT/'docs/evaluation/metadata-field-review-plan-v1.json'
KINDS = {'catalog_content_keywords':'KEYWORD', 'catalog_content_aliases':'ALIAS',
         'catalog_metadata_runtime_candidates':'RUNTIME'}
TABLES = ('catalog_metadata_review_versions','catalog_metadata_review_records','catalog_metadata_release_candidates')
KEYS = ('version_sha','record_sha','release_sha')
COLS = ({'version_sha':'CHAR(64)','parent_sha':'CHAR(64)','payload':'JSON'},
        {'record_sha':'CHAR(64)','version_sha':'CHAR(64)','row_sha':'CHAR(64)','payload':'JSON'},
        {'release_sha':'CHAR(64)','version_sha':'CHAR(64)','active':'TINYINT','payload':'JSON'})
require = stage.require


def exact(value, keys):
    require(isinstance(value,dict) and set(value)==set(keys))


def text(value, maximum=512):
    require(isinstance(value,str) and value.strip() and len(value)<=maximum and not any(ord(c)<32 and c not in '\n\t' for c in value))


def hashed(value, key):
    return value | {key:digest(value)}


def code_hashes():
    names=('metadata_review_release.py','metadata_storage_dry_run.py','metadata_enrichment_dry_run.py',
           'catalog_pool.py','audit_metadata_enrichment.py','audit_recommendation_landscape.py')
    return {name:hashlib.sha256((ROOT/'scripts'/name).read_bytes()).hexdigest() for name in names}


def load_context(collection, plan_path=REVIEW_PLAN):
    bundle=stage.load_bundle(collection,COLLECTION_SHA)
    projected=stage.projection(bundle)
    require(projected['batch_sha']==COLLECTION_SHA and projected['projection_sha256']==PROJECTION_SHA)
    plan=stage.collect.load_json(plan_path)
    require(plan['plan_sha256']==REVIEW_SHA and digest({k:v for k,v in plan.items() if k!='plan_sha256'})==REVIEW_SHA)
    require(plan['collection_sha256']==COLLECTION_SHA and plan['projection_sha256']==PROJECTION_SHA
            and plan['approved'] is False and plan['active'] is False and plan['apply_supported'] is False)
    original={(t,r.get('row_sha',r.get('batch_sha'))):r for t,rows in projected['rows'].items() for r in rows}
    reviews=plan['row_reviews']
    require(len(reviews)==len(original) and len({(r['table'],r['row_sha']) for r in reviews})==len(original))
    require(all((r['table'],r['row_sha']) in original and digest(original[r['table'],r['row_sha']])==r['source_value_sha256']
                and r['approved'] is False and r['active'] is False for r in reviews))
    return {'bundle':bundle,'projected':projected,'plan':plan,
            'rows':{r['row_sha']:(t,r) for t,rows in projected['rows'].items() if t in KINDS for r in rows},
            'public':{r['id']:r for r in bundle['baseline'][0]['rows']}}


def template(context):
    return {'policy':DECISIONS_POLICY,'review_plan_sha256':REVIEW_SHA,'revision':'review-001','parent_version_sha':None,
            'records':[{'row_sha':sha,'source_value_sha256':digest(row),'decision':'UNREVIEWED',
                        'reviewer':None,'rationale':'','value':{}} for sha,(table,row) in sorted(context['rows'].items())]}


def validate_record(context, record):
    exact(record,('row_sha','source_value_sha256','decision','reviewer','rationale','value'))
    require(record['row_sha'] in context['rows'])
    table,row=context['rows'][record['row_sha']]
    require(record['source_value_sha256']==digest(row))
    decision=record['decision']; require(decision in ('UNREVIEWED','APPROVE','HOLD','REJECT'))
    if decision=='UNREVIEWED':
        require(record['reviewer'] is None and record['rationale']=='' and record['value']=={})
        return 'UNREVIEWED',None
    reviewer=record['reviewer']; exact(reviewer,('kind','id','at_utc'))
    require(reviewer['kind'] in ('CODEX','HUMAN')); text(reviewer['id'],80); text(record['rationale'],1000)
    require(isinstance(reviewer['at_utc'],str) and len(reviewer['at_utc'])<=32)
    timestamp=datetime.fromisoformat(reviewer['at_utc'].replace('Z','+00:00'))
    require(timestamp.utcoffset() is not None and timestamp.utcoffset().total_seconds()==0)
    if decision in ('HOLD','REJECT'):
        require(record['value']=={})
        return decision,None
    value=record['value']; kind=KINDS[table]; reason='ELIGIBLE'
    if kind=='KEYWORD':
        exact(value,('label_ko','label_version','category','centrality','overview_quote'))
        text(value['label_ko'],128); require(re.search('[가-힣]',value['label_ko'])); text(value['label_version'],64)
        require(value['category'] in ('TOPIC','SETTING','FORM','MOOD','OTHER'))
        require(value['centrality'] in ('CENTRAL','SUPPORTING','INCIDENTAL','UNSUPPORTED','UNJUDGED'))
        text(value['overview_quote'],2000)
        require(value['overview_quote'] in context['public'][row['content_id']]['overview'])
        if value['category'] in ('MOOD','OTHER') or value['centrality'] not in ('CENTRAL','SUPPORTING'): reason='SEMANTIC_SCOPE_HOLD'
    elif kind=='ALIAS':
        exact(value,('language','scope','source_form'))
        require(isinstance(value['language'],str) and re.fullmatch('[a-z]{2,3}',value['language']) and value['language'] not in ('und','mul','zxx'))
        require(value['scope'] in ('WHOLE_WORK','SEASON','VERSION','WORKING_TITLE','UNKNOWN'))
        require(value['source_form'] in row['payload']['raw_forms'])
        normalized=row['normalized_title']
        ids={r['id'] for r in context['public'].values() if stage.collect.title_key(r['title'])==normalized}
        ids.update(r['content_id'] for t,r in context['rows'].values() if t=='catalog_content_aliases' and r['normalized_title']==normalized)
        typ=(row['payload']['title_type'].get('value') or '').lower()
        risk=any(w in typ for w in ('season','working','version','edition','remaster','misspelling'))
        if value['scope']!='WHOLE_WORK' or risk or len(ids)>1: reason='ALIAS_SCOPE_HOLD'
    else:
        exact(value,('minutes','scope','expected_existing'))
        field=row['target_field']; scope='MOVIE_TOTAL' if field=='runtime_minutes' else 'REPRESENTATIVE_EPISODE'
        require(type(value['minutes']) is int and value['minutes']==row['minutes'] and 1<=value['minutes']<=1440
                and value['scope']==scope and value['expected_existing'] is None
                and context['public'][row['content_id']][field] is None)
    entry={'kind':kind,'source_table':table,'source_row_sha':row['row_sha'],'source_sha':row['source_sha'],
           'source_value_sha256':digest(row),'content_id':row['content_id'],'record_sha':digest(record),
           'reviewed_value':value,'use':'DISCOVERY_ONLY' if kind=='KEYWORD' else 'EXACT_ALIAS_CANDIDATE' if kind=='ALIAS' else 'NULL_ONLY_DURATION_CANDIDATE'}
    return reason,entry if reason=='ELIGIBLE' else None


def compile_review(context, decisions):
    exact(decisions,('policy','review_plan_sha256','revision','parent_version_sha','records'))
    require(decisions['policy']==DECISIONS_POLICY and decisions['review_plan_sha256']==REVIEW_SHA)
    require(isinstance(decisions['revision'],str) and re.fullmatch('[A-Za-z0-9_.-]{1,64}',decisions['revision']))
    parent=decisions['parent_version_sha']; require(parent is None or isinstance(parent,str) and re.fullmatch('[a-f0-9]{64}',parent))
    records=decisions['records']; require(isinstance(records,list) and len(records)<=len(context['rows']))
    seen=set(); selected=[]; outcomes=[]
    for r in records:
        require(isinstance(r,dict) and isinstance(r.get('row_sha'),str) and r['row_sha'] not in seen)
        seen.add(r['row_sha']); reason,entry=validate_record(context,r)
        outcomes.append({'row_sha':r['row_sha'],'outcome':reason})
        if entry is not None: selected.append(entry)
    records=sorted(records,key=lambda r:r['row_sha']); selected.sort(key=lambda r:r['source_row_sha'])
    version=hashed({'policy':POLICY,'batch_sha':COLLECTION_SHA,'projection_sha':PROJECTION_SHA,'review_plan_sha':REVIEW_SHA,
                    'revision':decisions['revision'],'parent_version_sha':parent,'reviewer_identity':'DECLARED_NOT_AUTHENTICATED',
                    'code_sha256':code_hashes(),
                    'decisions_sha256':digest(decisions | {'records':records}),'records':records},'version_sha')
    candidate=hashed({'policy':POLICY,'version_sha':version['version_sha'],'batch_sha':COLLECTION_SHA,
                      'state':'INACTIVE_CANDIDATE' if selected else 'BLOCKED_NO_ELIGIBLE_RECORDS','entries':selected,
                      'active':False,'application_apply_supported':False,'content_updates':[]},'release_sha')
    return {'version':version,'candidate':candidate,'summary':{
        'mode':'OFFLINE_DRY_RUN','database_state':'NOT_CHECKED','record_count':len(records),
        'missing_records':len(context['rows'])-len(records),'outcomes':dict(sorted(Counter(r['outcome'] for r in outcomes).items())),
        'selected_count':len(selected),'selected_by_kind':dict(Counter(r['kind'] for r in selected)),
        'application_db_writes':0,'provider_calls':0,'active':False,'content_updates':0}}


def validate_history(context, history):
    """Reject damaged previous versions instead of adopting their current bytes."""
    versions={r['version_sha']:r for r in history[0]}
    for r in history[0]:
        value=r['payload']
        require(value['version_sha']==r['version_sha'] and digest({k:v for k,v in value.items() if k!='version_sha'})==r['version_sha'])
        require(value['batch_sha']==COLLECTION_SHA and value['projection_sha']==PROJECTION_SHA and value['review_plan_sha']==REVIEW_SHA
                and value['policy']==POLICY and r['parent_sha']==value['parent_version_sha'])
        require(r['parent_sha'] is None or r['parent_sha'] in versions)
        stored=[e['payload'] for e in history[1] if e['version_sha']==r['version_sha']]
        require(sorted(stored,key=lambda e:e['row_sha'])==value['records'],'Incomplete prior review records.')
        for record in stored: validate_record(context,record)
    for r in history[1]:
        require(r['version_sha'] in versions and r['row_sha']==r['payload']['row_sha']
                and digest({'version_sha':r['version_sha'],'record':r['payload']})==r['record_sha'])
    releases={r['version_sha']:r for r in history[2]}
    require(len(releases)==len(history[2]))
    for version,row in versions.items():
        selected=[entry for record in row['payload']['records'] for reason,entry in [validate_record(context,record)] if entry is not None]
        selected.sort(key=lambda e:e['source_row_sha'])
        release=releases.get(version)
        require(bool(release)==bool(selected),'Prior release completeness differs.')
        if release:
            expected=hashed({'policy':POLICY,'version_sha':version,'batch_sha':COLLECTION_SHA,'state':'INACTIVE_CANDIDATE',
                             'entries':selected,'active':False,'application_apply_supported':False,'content_updates':[]},'release_sha')
            require(release['payload']==expected and release['release_sha']==expected['release_sha'] and release['active']==0)
    require(all(r['version_sha'] in versions for r in history[2]))


def schema_sql():
    statements=['-- UNAPPLIED REFERENCE ONLY. Separate review versions; raw draft CHECK constraints remain unchanged.']
    for index,(table,cols,key) in enumerate(zip(TABLES,COLS,KEYS)):
        parts=[f' `{name}` {kind} '+('NULL' if name=='parent_sha' else 'NOT NULL') for name,kind in cols.items()]
        parts+=[' `created_at` DATETIME(6) NOT NULL',f' PRIMARY KEY (`{key}`)']
        if index==0: parts+=[f' FOREIGN KEY (`parent_sha`) REFERENCES `{TABLES[0]}` (`version_sha`)']
        else: parts+=[f' FOREIGN KEY (`version_sha`) REFERENCES `{TABLES[0]}` (`version_sha`)']
        if index==1: parts+=[' UNIQUE KEY uq_review_version_row (`version_sha`,`row_sha`)']
        if index==2: parts+=[' UNIQUE KEY uq_release_version (`version_sha`)',' CHECK (active=0)']
        statements.append(f'CREATE TABLE `{table}` (\n'+',\n'.join(parts)+'\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;')
    return '\n'.join(statements)+'\n'


def db_rows(compiled):
    version=compiled['version']; candidate=compiled['candidate']; key=version['version_sha']
    return [[{'version_sha':key,'parent_sha':version['parent_version_sha'],'payload':version}],
            [{'record_sha':digest({'version_sha':key,'record':r}),'version_sha':key,'row_sha':r['row_sha'],'payload':r} for r in version['records']],
            [{'release_sha':candidate['release_sha'],'version_sha':key,'active':0,'payload':candidate}] if candidate['entries'] else []]


def row_json(index):
    return 'JSON_OBJECT('+','.join(sql_value(f)+',`'+f+'`' for f in COLS[index])+",'created_at',DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ'))"


def read_reviews(db):
    result=[]
    for index,table in enumerate(TABLES):
        lines=db.run(f'SELECT {row_json(index)} FROM `{table}` ORDER BY `{KEYS[index]}` LIMIT 5001;').splitlines()
        rows=[stage.collect.strict_json(line) for line in lines if line]; require(len(rows)<=5000)
        result.append(rows)
    return result


def scratch_transaction(context, compiled, before_raw, before_reviews, new_rows):
    sql=stage.transaction_sql(context['bundle'],context['projected'],before_raw,'ALREADY_STORED_UNREVIEWED')
    ending="COMMIT; SELECT '__METADATA_DRAFT_COMMITTED__';\n"; require(sql.endswith(ending)); sql=sql[:-len(ending)]
    for i,table in enumerate(TABLES):
        sql+=f'SELECT `{KEYS[i]}` FROM `{table}` ORDER BY `{KEYS[i]}` LIMIT 5001 FOR UPDATE;\n'
        sql+=stage.guard(f'(SELECT COUNT(*) FROM `{table}`)={len(before_reviews[i])}')
        for r in before_reviews[i]:
            sql+=stage.guard(f'(SELECT COUNT(*) FROM `{table}` WHERE `{KEYS[i]}`={sql_value(r[KEYS[i]])} AND {row_json(i)}=CAST({sql_value(stage.canonical(r))} AS JSON))=1')
    sql+='SET @review_created=UTC_TIMESTAMP(6);\n'
    for i,(table,rows) in enumerate(zip(TABLES,new_rows)):
        fields=list(COLS[i])
        for r in rows:
            values=[f'CAST({sql_value(stage.canonical(r[f]))} AS JSON)' if f=='payload' else sql_value(r[f]) for f in fields]
            sql+=f'INSERT INTO `{table}` ('+','.join('`'+f+'`' for f in fields)+',created_at) VALUES ('+','.join(values)+',@review_created);\n'
        sql+=f'-- AFTER_REVIEW_{i}\n'
        sql+=stage.guard(f'(SELECT COUNT(*) FROM `{table}`)={len(before_reviews[i])+len(rows)}')
    sql+="COMMIT; SELECT '__REVIEW_CANDIDATE_COMMITTED__';\n"
    require(len(sql.encode())<=10_000_000)
    return sql


def store_owned_scratch(db, context, decisions):
    """Test-only persistence; no application adapter or preexisting schema accepted."""
    require(type(db) is stage.OwnedScratch and db.owned and re.fullmatch(stage.PREFIX+'[a-f0-9]{12}',db.schema))
    compiled=compile_review(context,decisions)
    raw=stage.read_state(db)
    require(stage.check_state(context['bundle'],context['projected'],raw)=='ALREADY_STORED_UNREVIEWED')
    count=int(db.run('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('+','.join(map(sql_value,TABLES))+');').strip())
    require(count in (0,3),'Partial review schema; no automatic repair.')
    if count==0: db.run(schema_sql())  # DDL is intentionally separate from the data transaction.
    before=read_reviews(db); validate_history(context,before)
    expected=db_rows(compiled); version=compiled['version']['version_sha']
    parent=compiled['version']['parent_version_sha']
    require(parent is None or any(r['version_sha']==parent and r['payload']['batch_sha']==COLLECTION_SHA for r in before[0]),'Parent review version is absent or belongs to another batch.')
    own=[[{k:v for k,v in r.items() if k!='created_at'} for r in rows if r['version_sha']==version] for rows in before]
    replay=any(own)
    require(not replay or all(sorted(own[i],key=lambda r:r[KEYS[i]])==sorted(expected[i],key=lambda r:r[KEYS[i]]) for i in range(3)), 'Partial/conflicting version; no repair or adoption.')
    new_rows=[[],[],[]] if replay else expected
    ack=db.run(scratch_transaction(context,compiled,raw,before,new_rows))
    require('__REVIEW_CANDIDATE_COMMITTED__' in ack,'Commit acknowledgement absent; inspect and explicitly replay the same version.')
    after=read_reviews(db)
    require(stage.read_state(db)==raw,'Raw drafts/catalog changed.')
    for i in range(3):
        require(len(after[i])==len(before[i])+len(new_rows[i]))
        require(all(r in after[i] for r in before[i]),'Previous versions/timestamps changed.')
        require(sorted(({k:v for k,v in r.items() if k!='created_at'} for r in after[i] if r['version_sha']==version),key=lambda r:r[KEYS[i]])==sorted(expected[i],key=lambda r:r[KEYS[i]]))
    return {'state':'REPLAY_UNCHANGED' if replay else 'STORED_TEST_ONLY','inserted':sum(map(len,new_rows)),
            'version_sha':version,'release_sha':compiled['candidate']['release_sha'],'active':False,'application_db_writes':0}


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('template','plan'))
    parser.add_argument('--collection',required=True,type=Path)
    parser.add_argument('--decisions',type=Path)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args(argv)
    try:
        require(not args.output.exists(),'Existing output is never overwritten.')
        require(args.command!='template' or args.decisions is None)
        context=load_context(args.collection)
        decisions=stage.collect.load_json(args.decisions,8_000_000) if args.decisions else template(context)
        compiled=compile_review(context,decisions)
        args.output.mkdir(parents=True,exist_ok=False)
        write_json(args.output/'review-template.json',template(context))
        write_json(args.output/'review-version.json',compiled['version'])
        write_json(args.output/'release-candidate.json',compiled['candidate'])
        write_json(args.output/'summary.json',compiled['summary'])
        (args.output/'schema-reference.sql').write_text(schema_sql(),encoding='utf-8')
        print(json.dumps({'output':str(args.output)}|compiled['summary'],ensure_ascii=False))
        return 0
    except (CatalogError,ValueError,KeyError,TypeError,OSError,RecursionError):
        print('Review dry-run validation failed; no application write or approval was performed.',file=sys.stderr)
        return 1


if __name__=='__main__': raise SystemExit(main())
