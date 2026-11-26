"""Prepare the remaining fixed D13-D24 cases with synthetic D24 reactions; no API or DB calls."""
import json
import os
from pathlib import Path
from prepare_quality_remeasurement_v2 import PINS as ORIGINAL_PINS
from prepare_quality_remeasurement import ceiling, sha

OUT = Path('target/quality-remaining-20261125')
PROTOCOL = 'FIXED_D13_D24_BASELINE_V1'
PINS = {
    **ORIGINAL_PINS,
    'target/quality-remeasurement-v2-20261125/pilot-20261125131458782277600.json': 'bef8e286be4c6c65028692b0b9a37b3c98d2ded4d578b6c7be7f39bfa6246b79',
    'src/test/resources/chat/quality-remaining-reactions-v1.json': '7f678690253a30d4a47aacc1479e60f57f9893d6bafc7d7ad3f9c65947e6eacf',
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
            raise ValueError('Remaining baseline already launched; preserve and review, never retry automatically')


def run_ceiling(prior, existing=None):
    return min(.30, ceiling(prior, existing))


def prepare(root, existing=None, peer=None):
    check_not_launched(root, peer if peer is not None else peer_checkout(root))
    prior = prior_reports(root)
    cap = run_ceiling(prior, existing)
    paths = set(PINS)
    paths.update(p.relative_to(root).as_posix() for p in (root / 'src/main/java/com/example/recommend/chat').rglob('*.java'))
    paths.update('src/test/java/com/example/recommend/chat/config/' + name + '.java' for name in (
        'QualityBaselineTest', 'QualityBaselineLiveTest', 'QualityPilotBudget', 'QualityRemeasurementV2Plan',
        'QualityRemainingPlan', 'QualityRemainingPlanTest', 'QualityRemainingFixture', 'QualityRemainingFixtureTest'))
    paths.update(('src/test/resources/chat/quality-baseline-probes-v1.json',
                  'scripts/run_quality_remaining.ps1', 'scripts/prepare_quality_remaining.py',
                  'scripts/prepare_quality_remeasurement.py', 'scripts/prepare_quality_remeasurement_v2.py'))
    paths.update('src/main/java/com/example/recommend/domain/' + name + '.java'
                 for name in ('Content', 'User', 'UserContentInteraction', 'InteractionType'))
    plan = dict(protocol=PROTOCOL, approvedModel='gpt-6-luna', approvedCumulativeUsd=1,
                priorReservationEstimateUsd=prior, runMaxEstimatedUsd=cap,
                cumulativeMaxEstimatedUsd=round(prior + cap, 7), billingTotalKnown=False,
                selectedCaseIds=[f'D{i:02}' for i in range(13, 25)], maxProviderRequests=60,
                sharedTurnOutputTokens=2048, timeoutSecondsPerTurn=20, retryRequests=0,
                priorReports=sorted(REPORTS), syntheticReactionCase='D24', syntheticOwnerId=9100001,
                syntheticFixture='src/test/resources/chat/quality-remaining-reactions-v1.json',
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
        raise SystemExit('Remaining preflight failed: review prior results, fixed public inputs, ceiling and launch markers.')
