"""Prepare the same D01-D12 pilot after both fixed schemas were accepted; no API or DB calls."""
import json
import os
from pathlib import Path
from prepare_quality_remeasurement import PINS as ORIGINAL_PINS, ceiling, sha

OUT = Path('target/quality-remeasurement-v2-20261125')
PROTOCOL = 'FIXED_D01_D12_REMEASUREMENT_V2'
PINS = {
    **ORIGINAL_PINS,
    'target/quality-remeasurement-20261125/pilot-20261125091500585131800.json': 'ed69c1e659941ac2fd397a14b5aa44c5462f34f83424ac800a9a90c678f797ec',
    'target/schema-d09-diagnosis-20261125/live-20261125122159875469300.json': '65690b6dc886bf948a3b99acac34c6976fb3476214a8d698f63f788e4a55bbc6',
    'target/quote-schema-acceptance-20261125/live-20261125125148178675400.json': 'b3237c771ab59aa27e33bed2b2b49e8fc9cbceb2de4c66e46d13b916fa042712',
}
REPORTS = {p: h for p, h in PINS.items() if '/pilot-' in p or '/live-' in p}


def prior_reports(root):
    expected = {root / p for p in REPORTS}
    discovered = set()
    for directory in {p.parent for p in expected}:
        pattern = 'pilot-[0-9]*.json' if 'quality-' in directory.name else 'live-[0-9]*.json'
        # quote-schema-acceptance uses live reports, as do the two diagnoses.
        discovered.update(directory.glob(pattern))
    if discovered != expected:
        raise ValueError('Additional or missing prior run; review the cumulative estimate')
    for relative, expected_hash in PINS.items():
        if sha(root / relative) != expected_hash:
            raise ValueError('Frozen public input or result changed: ' + relative)
    reports = [json.loads((root / p).read_text(encoding='utf-8-sig')) for p in REPORTS]
    if any(r.get('model') != 'gpt-6-luna' for r in reports):
        raise ValueError('Prior model differs from the approved ledger')
    accepted = json.loads((root / next(p for p in REPORTS if 'quote-schema-acceptance' in p)).read_text(encoding='utf-8-sig'))
    if accepted.get('state') != 'ACCEPTED_BOTH_SCHEMAS' or not accepted.get('allSchemasAccepted'):
        raise ValueError('Both corrected schemas must have been accepted first')
    return round(sum(r['reservationEstimateUsd'] for r in reports), 7)


def peer_checkout(root):
    source = Path('C:/Users/jaehyeon/recommend-backend').resolve()
    execution = Path('C:/Users/jaehyeon/eclipse-workspace/recommend').resolve()
    return execution if root.resolve() == source else source if root.resolve() == execution else None


def check_not_launched(root, peer=None):
    for checkout in (root, peer):
        if checkout is None:
            continue
        out = checkout / OUT
        if (out / 'launch-started.json').exists() or list(out.glob('pilot-[0-9]*.json')):
            raise ValueError('V2 already launched; preserve and review, never retry automatically')


def prepare(root, existing=None, peer=None):
    check_not_launched(root, peer if peer is not None else peer_checkout(root))
    prior = prior_reports(root)
    cap = ceiling(prior, existing)
    paths = set(PINS)
    paths.update(p.relative_to(root).as_posix() for p in (root / 'src/main/java/com/example/recommend/chat').rglob('*.java'))
    paths.update('src/test/java/com/example/recommend/chat/config/' + name + '.java' for name in (
        'QualityBaselineTest', 'QualityBaselineLiveTest', 'QualityPilotBudget', 'QualityRemeasurementV2Plan',
        'QualityRemeasurementV2PlanTest'))
    paths.update(('src/test/resources/chat/quality-baseline-probes-v1.json',
                  'scripts/run_quality_remeasurement_v2.ps1', 'scripts/prepare_quality_remeasurement_v2.py',
                  'scripts/prepare_quality_remeasurement.py'))
    plan = dict(protocol=PROTOCOL, approvedModel='gpt-6-luna', approvedCumulativeUsd=1,
                priorReservationEstimateUsd=prior, runMaxEstimatedUsd=cap,
                cumulativeMaxEstimatedUsd=round(prior + cap, 7), billingTotalKnown=False,
                selectedCaseIds=[f'D{i:02}' for i in range(1, 13)], maxProviderRequests=60,
                sharedTurnOutputTokens=2048, timeoutSecondsPerTurn=20, retryRequests=0,
                priorReports=sorted(REPORTS),
                pinnedInputs=[dict(path=p, sha256=sha(root / p)) for p in sorted(paths)])
    out = root / OUT
    out.mkdir(parents=True, exist_ok=True)
    (out / 'run-plan.json').write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding='utf-8')
    return plan


if __name__ == '__main__':
    try:
        existing = os.getenv('CHAT_EVAL_MAX_USD')
        plan = prepare(Path(__file__).resolve().parents[1], None if not existing else float(existing))
        print(json.dumps({k: plan[k] for k in ('protocol', 'approvedModel', 'priorReservationEstimateUsd',
                                              'runMaxEstimatedUsd', 'cumulativeMaxEstimatedUsd', 'billingTotalKnown')}))
    except (ValueError, OSError, KeyError, TypeError):
        raise SystemExit('V2 preflight failed: review prior results, fixed public inputs, ceiling and launch markers.')
