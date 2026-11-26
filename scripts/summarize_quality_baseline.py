"""Summarize a specified offline diagnostic run without inventing semantic scores."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path


def latency(values):
    values = sorted(values)
    if not values:
        return {'samples': 0, 'p50_ms': None, 'p95_ms': None, 'max_ms': None}
    return {'samples': len(values), 'p50_ms': round(values[math.ceil(.50 * len(values)) - 1] / 1000, 3),
            'p95_ms': round(values[math.ceil(.95 * len(values)) - 1] / 1000, 3), 'max_ms': round(values[-1] / 1000, 3)}


def summarize(report):
    if report.get('mode') != 'PUBLIC_H2_REPLAY_NO_MODEL':
        raise ValueError('Only an explicitly labelled offline report is supported.')
    rows = report['results']
    if len(rows) != 24 or len({r['caseId'] for r in rows}) != 24:
        raise ValueError('Incomplete or duplicate diagnostic cases.')
    controlled = [r for r in rows if 'controlledRetrieval' in r]
    witnesses = [r for r in controlled if 'provisionalWitnessId' in r]
    times = lambda mode: [t for r in rows if mode in r for t in r[mode]['latencyMicros']]
    return {
        'mode': report['mode'], 'public_catalog_rows': report['publicCatalogRows'],
        'raw_cases': len(rows), 'raw_empty': sum(not r['rawQuestionRetrieval']['candidateIds'] for r in rows),
        'controlled_cases': len(controlled),
        'controlled_empty': sum(not r['controlledRetrieval']['candidateIds'] for r in controlled),
        'controlled_empty_ids': [r['caseId'] for r in controlled if not r['controlledRetrieval']['candidateIds']],
        'witness_cases': len(witnesses),
        'raw_witness_visible': sum(r['provisionalWitnessId'] in r['rawQuestionRetrieval']['candidateIds'] for r in witnesses),
        'controlled_witness_visible': sum(r['witnessInInitialCandidates'] for r in witnesses),
        'witness_diagnostics': dict(Counter(r['witnessDiagnostic'] for r in witnesses)),
        'candidate_validation_failures': sum(not r['candidateValidationPassed'] for r in controlled),
        'sql_java_eligibility_disagreements': sum(len(r['sqlVsJavaEligibilityDisagreements']) for r in controlled),
        'unstable_modes': sum(not r[m]['stableAcrossThreeQueries'] for r in rows
                              for m in ('rawQuestionRetrieval', 'controlledRetrieval') if m in r),
        'raw_h2_query_latency': latency(times('rawQuestionRetrieval')),
        'controlled_h2_query_latency': latency(times('controlledRetrieval')),
        'non_retrieval_control_states': dict(Counter(r['probeStatus'] for r in rows if 'controlledRetrieval' not in r)),
        'provider_calls': 0, 'measured_provider_cost_usd': 0,
        'pipeline_recommendation_quality': 'NOT_MEASURED', 'semantic_precision': 'UNJUDGED',
        'recall': 'NOT_MEASURED_NO_INDEPENDENT_GOLD_POOL',
        'warning': 'Witness visibility is not recall. H2 query timings are not whole-turn or MySQL performance.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = summarize(json.loads(args.report.read_text(encoding='utf-8')))
    result['source_report'] = args.report.as_posix()
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
