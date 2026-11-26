"""Pin the authorized public pilot inputs and cumulative estimate; never read credentials."""
import hashlib
import json
from pathlib import Path

OUT = Path('target/quality-remeasurement-20261125')
PINS = {
    'target/quality-baseline-20261125/pilot-20261125082720913747800.json': 'd32f339ab407bd7736705ea17dc429e8736925df1fb3dd5f00264204a731122c',
    'target/schema-d10-diagnosis-20261125/live-20261125084417230961500.json': 'eddbf74bdbfafe540f44a29e97d0edcbd61ff84faafbe933c644b0a0a174151f',
    'target/schema-d10-diagnosis-20261125/live-20261125091022637208400.json': '2fc2315aca164710a8a5c2e9443b593f4f22faab5a2aee4c65b08a570f3162d8',
    'target/quality-baseline-20261125/public-snapshot.json': '94d936d1e3c5bfdeff41a8b9fcbdf9b913f49e7797dd65597020590c844d50a4',
    'docs/evaluation/quality-development-v1.json': '812264884f994d24a32370b53baf8475b56ec7fa4e9f7246e187f5e0c830b687',
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ceiling(prior, existing=None):
    import math
    values = [.40, 1 - prior]
    if not math.isfinite(prior) or prior < 0 or prior >= 1:
        raise ValueError('Invalid cumulative estimate')
    if existing is not None:
        if not math.isfinite(existing) or existing <= 0 or existing > 1:
            raise ValueError('Existing ceiling must be (0,1]')
        values.append(existing)
    return min(values)


def prepare(root):
    out = root / OUT
    if (out / 'launch-started.json').exists() or list(out.glob('pilot-[0-9]*.json')):
        raise ValueError('Remeasurement already launched; preserve and review, never retry automatically')
    for relative, expected in PINS.items():
        if sha(root / relative) != expected:
            raise ValueError(f'Frozen input changed: {relative}')
    old = root / 'target/quality-baseline-20261125'
    diagnosis = root / 'target/schema-d10-diagnosis-20261125'
    expected_reports = {root / p for p in PINS if '/pilot-' in p or '/live-' in p}
    discovered = set(old.glob('pilot-[0-9]*.json')) | set(diagnosis.glob('live-[0-9]*.json'))
    if discovered != expected_reports:
        raise ValueError('Additional prior run found; review cumulative estimate before proceeding')
    reports = [json.loads(p.read_text(encoding='utf-8')) for p in expected_reports]
    prior = round(sum(r['reservationEstimateUsd'] for r in reports), 7)
    paths = set(PINS)
    paths.update(p.relative_to(root).as_posix() for p in (root / 'src/main/java/com/example/recommend/chat').rglob('*.java'))
    paths.update(['src/test/java/com/example/recommend/chat/config/' + n for n in
                  ('QualityBaselineTest.java', 'QualityBaselineLiveTest.java', 'QualityPilotBudget.java')])
    paths.update(['src/test/resources/chat/quality-baseline-probes-v1.json',
                  'scripts/run_quality_remeasurement.ps1', 'scripts/prepare_quality_remeasurement.py'])
    plan = dict(protocol='FIXED_D01_D12_REMEASUREMENT_V1', approvedModel='gpt-6-luna', approvedCumulativeUsd=1,
                priorReservationEstimateUsd=prior, runMaxEstimatedUsd=ceiling(prior),
                cumulativeMaxEstimatedUsd=round(prior + ceiling(prior), 7), billingTotalKnown=False,
                selectedCaseIds=[f'D{i:02}' for i in range(1, 13)], maxProviderRequests=60,
                sharedTurnOutputTokens=2048, timeoutSecondsPerTurn=20, retryRequests=0,
                pinnedInputs=[dict(path=p, sha256=sha(root / p)) for p in sorted(paths)])
    out.mkdir(parents=True, exist_ok=True)
    (out / 'run-plan.json').write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding='utf-8')
    return plan


if __name__ == '__main__':
    plan = prepare(Path(__file__).resolve().parents[1])
    print(json.dumps({k: plan[k] for k in ('protocol', 'approvedModel', 'priorReservationEstimateUsd',
                                          'runMaxEstimatedUsd', 'cumulativeMaxEstimatedUsd', 'billingTotalKnown')}))
