"""Phase 3 first task: public audit and UNAPPLIED proposal; no data API or write SQL.

Live: python scripts/audit_metadata_enrichment.py --read-local
Replay: use --public-snapshot and --mapping-snapshot with the saved JSON files.
Lexical discovery groups are not semantic labels, a product taxonomy, or test answers.
"""
import argparse
from collections import defaultdict
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import unicodedata

from audit_recommendation_landscape import analyze as landscape, read_snapshot as read_public, FIELDS
from audit_runtime_coverage import analyze as coverage, read_snapshot as read_mappings
from catalog_pool import CatalogError, LocalCatalog, ROOT, digest, write_json

OUTPUT = ROOT / 'target/metadata-enrichment-audit-20261126'
BASELINE = 'd8a241483873f940f68b29ab4d6090758ed85e3bd5bd7a2ac3f82e2a1d219a68'
PER_MEDIA = 14


def validate(public, mappings, expected_count=468):
    rows = public['rows']
    if len(rows) != expected_count or not 1 <= expected_count <= 5000:
        raise CatalogError('Catalog count changed; review the scope before planning.')
    seen = set()
    for row in rows:
        if (set(row) != set(FIELDS) or type(row['id']) is not int or row['id'] <= 0
                or row['id'] in seen or row['type'] not in ('MOVIE', 'TV')
                or any(not isinstance(row[f], str) for f in ('title', 'overview', 'genres'))
                or not row['title'].strip()):
            raise CatalogError('Invalid public identity/text; no partial proposal.')
        seen.add(row['id'])
    report = coverage(mappings, expected_count)
    if (report['blockers'] or report['manual_review']
            or any(c['unmapped'] for c in report['coverage'].values())):
        raise CatalogError('Mapping/runtime integrity gate blocked the proposal.')
    narrow = [{key: row[key] for key in ('id', 'type', 'runtime_minutes', 'episode_runtime_minutes')}
              for row in rows]
    if sorted(narrow, key=lambda r: r['id']) != sorted(mappings['contents'], key=lambda r: r['id']):
        raise CatalogError('Public and mapping snapshots disagree; refresh both.')
    return report


def title_key(value):
    # Preserve punctuation and suffixes: do not silently merge remakes or quoted titles.
    return ' '.join(unicodedata.normalize('NFKC', value).casefold().split())


def propose(public, mappings, expected_count=468):
    runtime = validate(public, mappings, expected_count)
    public = {**public, 'rows': sorted(public['rows'], key=lambda r: r['id']),
              'columns': sorted(public['columns'], key=lambda c: c['name'])}
    stats = landscape(public)
    rows = public['rows']
    by_id = {r['id']: r for r in rows}
    sources = {r['content_id']: r for r in mappings['sources']}
    collisions = defaultdict(list)
    for row in rows:
        collisions[title_key(row['title'])].append(row['id'])
    missing = sorted(r['id'] for r in rows if r['id'] in {
        c['content_id'] for c in runtime['detail_fetch_candidates']})
    long_plot = sorted(r['id'] for r in rows if len(r['overview']) > 400)
    short_plot = sorted(r['id'] for r in rows if len(r['overview'].strip()) < 80)
    selected = {}
    absent_groups = []

    def choose(ids, media, reason):
        pool = [i for i in ids if i not in selected and by_id[i]['type'] == media]
        if not pool:
            return False
        ident = min(pool, key=lambda i: digest({'policy': 'metadata-pilot-v1', 'id': i}))
        selected[ident] = reason
        return True

    # Deterministic stratification across audit groups and metadata gaps, no model outputs/questions.
    for media in ('MOVIE', 'TV'):
        for name in ('missing_time', 'long_plot', 'short_plot'):
            choose({'missing_time': missing, 'long_plot': long_plot, 'short_plot': short_plot}[name], media, name)
        for name, probe in sorted(stats['probes'].items()):
            if sum(by_id[i]['type'] == media for i in selected) >= PER_MEDIA:
                break
            if not choose(probe['boundary_full_ids'], media, 'lexical_probe:' + name):
                absent_groups.append({'media_type': media, 'probe': name,
                                      'status': 'NO_UNSELECTED_LEXICAL_MATCH_NOT_SEMANTIC_ABSENCE'})
        while sum(by_id[i]['type'] == media for i in selected) < min(PER_MEDIA, sum(r['type'] == media for r in rows)):
            choose(by_id, media, 'stable_hash_fill')
    pilot = []
    for ident, reason in sorted(selected.items()):
        row, source = by_id[ident], sources[ident]
        media = 'movie' if row['type'] == 'MOVIE' else 'tv'
        base = '/3/' + media + '/' + str(source['tmdb_id'])
        pilot.append({'content_id': ident, 'media_type': row['type'], 'tmdb_id': source['tmdb_id'],
                      'title': row['title'], 'selection_reason': reason,
                      'lexical_probe_groups': sorted(k for k, p in stats['probes'].items()
                                                     if ident in p['boundary_full_ids']),
                      'expected_public_row_sha256': digest(row),
                      'proposed_requests': [base + '?language=ko-KR', base + '/keywords', base + '/alternative_titles'],
                      'review_status': 'NOT_COLLECTED', 'approved_changes': []})
    audit = {'version': 1, 'mode': 'PUBLIC_METADATA_AUDIT_ONLY',
             'public_snapshot_sha256': digest(public), 'historical_public_snapshot_sha256': BASELINE,
             'historical_public_snapshot_match': digest(public) == BASELINE,
             'mapping_snapshot_sha256': digest(mappings),
             'content_count': len(rows), 'coverage': runtime['coverage'],
             'mapping_issues': runtime['mapping_issues'], 'source_count': len(sources),
             'missing_time_ids': missing, 'overview_over_400_codepoints_ids': long_plot,
             'overview_under_80_codepoints_ids': short_plot,
             'empty_overview_ids': [r['id'] for r in rows if not r['overview'].strip()],
             'overview_at_storage_limit_ids': [r['id'] for r in rows if len(r['overview']) >= 2000],
             'normalized_current_title_collisions': [ids for _, ids in sorted(collisions.items()) if len(ids) > 1],
             'public_column_names': stats['public_column_names'], 'lexical_discovery': stats['probes'],
             'semantic_coverage': 'UNJUDGED',
             'warning': 'Missing independent keywords/aliases checked only in Content/contents, not all DB tables. Short plots are a review heuristic, not proven inadequate.',
             'catalog_writes': 0, 'tmdb_data_api_calls': 0, 'llm_calls': 0}
    plan = {'version': 1, 'status': 'UNAPPLIED_REVIEW_ONLY',
            'public_snapshot_sha256': digest(public), 'mapping_snapshot_sha256': digest(mappings),
            'existing_catalog_policy': 'PRESERVE_468_NO_INSERT_DELETE',
            'addition_track': 'DEFERRED_SEPARATE_DISCOVERY_AND_REVIEW_NOT_AUTHORIZED_BY_THIS_PLAN',
            'pilot_policy': 'metadata-pilot-v1-14-per-media-gaps-then-lexical-groups-then-hash',
            'pilot': pilot, 'probe_selection_gaps': absent_groups,
            'proposed_limits': {'titles': len(pilot), 'http_attempts_max': 3 * len(pilot),
                                'parallelism': 1, 'automatic_retries': 0, 'requests_per_second_max': 1,
                                'per_request_timeout_seconds': 10, 'wall_seconds_max': 900,
                                'response_bytes_max': 1048576, 'llm_calls': 0},
            'required_next_gates': ['new_collection_request', 'revalidate_public_and_mapping_hashes',
                                    'implement_and_test_bounded_collector', 'validate_official_response_shapes',
                                    'manual_field_review', 'separate_apply_feature_and_tests'],
            'updates': [], 'inserts': [], 'catalog_writes': 0, 'tmdb_data_api_calls': 0,
            'quality_improvement': 'NOT_MEASURED'}
    return audit, plan


def read_consistent(db):
    first, mappings = read_public(db), read_mappings(db)
    second, mapping_after = read_public(db), read_mappings(db)
    if digest(first) != digest(second) or digest(mappings) != digest(mapping_after):
        raise CatalogError('Public or mapping fields changed during audit; no output.')
    validate(first, mappings)
    return first, mappings


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--read-local', action='store_true')
    parser.add_argument('--public-snapshot', type=Path)
    parser.add_argument('--mapping-snapshot', type=Path)
    parser.add_argument('--output', type=Path, default=OUTPUT)
    args = parser.parse_args(argv)
    if args.read_local == bool(args.public_snapshot or args.mapping_snapshot) or (not args.read_local and not (args.public_snapshot and args.mapping_snapshot)):
        parser.error('Choose --read-local OR both snapshot paths.')
    try:
        if args.read_local:
            public, mappings = read_consistent(LocalCatalog())
        else:
            public, mappings = (json.loads(p.read_text(encoding='utf-8')) for p in
                                (args.public_snapshot, args.mapping_snapshot))
        audit, plan = propose(public, mappings)
        for name, value in [('public-snapshot', public), ('mapping-snapshot', mappings), ('audit', audit), ('proposal', plan)]:
            write_json(args.output / (name + '.json'), value)
        write_json(args.output / 'run.json', {'mode': 'READ_LOCAL' if args.read_local else 'OFFLINE_REPLAY',
                                            'created_at_utc': datetime.now(timezone.utc).isoformat(),
                                            'audit_sha256': digest(audit), 'proposal_sha256': digest(plan)})
        print(json.dumps({'status': plan['status'], 'content_count': audit['content_count'],
                          'source_count': audit['source_count'], 'missing_time': len(audit['missing_time_ids']),
                          'historical_snapshot_match': audit['historical_public_snapshot_match'],
                          'pilot_titles': len(plan['pilot']), 'executed_tmdb_data_calls': 0}, ensure_ascii=False))
        return 0
    except (CatalogError, KeyError, TypeError, ValueError, OSError):
        print('Metadata audit failed; credentials, SQL and server output withheld.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
