"""Pin development conversations and the eight-report ledger; never read credentials."""
import json
import os
from pathlib import Path
from prepare_quality_remaining import PINS as PREVIOUS_PINS, peer_checkout
from prepare_quality_remeasurement import ceiling, sha

OUT = Path('target/quality-conversation-20261125')
PROTOCOL = 'FIXED_DC01_DC03_CONVERSATION_V1'
PINS = {**PREVIOUS_PINS,
        'target/quality-remaining-20261125/pilot-20261125134737482110200.json':
        '3b4062e06d1a37910b0dc400f3eaaeed9295b19147c1191ef512912c1b600462'}
REPORTS = {p: h for p, h in PINS.items() if '/pilot-' in p or '/live-' in p}


def check_not_launched(root, peer=None):
    for checkout in (root, peer):
        if checkout is None:
            continue
        folder = checkout / OUT
        if (folder / 'launch-started.json').exists() or list(folder.glob('pilot-[0-9]*.json')):
            raise ValueError('Conversation baseline already launched; preserve results, never retry automatically')


def prior_reports(root):
    expected = {root / p for p in REPORTS}
    discovered = set()
    for parent in {p.parent for p in expected}:
        discovered.update(parent.glob('pilot-[0-9]*.json'))
        discovered.update(parent.glob('live-[0-9]*.json'))
    if discovered != expected:
        raise ValueError('Prior ledger scope changed; review cumulative reservations')
    for rel, pin in PINS.items():
        if sha(root / rel) != pin:
            raise ValueError('Frozen public input or result changed: ' + rel)
    reports = [json.loads((root / p).read_text(encoding='utf-8-sig')) for p in REPORTS]
    if any(r.get('model') != 'gpt-6-luna' for r in reports):
        raise ValueError('Approved model changed')
    accepted = next(r for p, r in zip(REPORTS, reports) if 'quote-schema-acceptance' in p)
    if accepted.get('state') != 'ACCEPTED_BOTH_SCHEMAS' or not accepted.get('allSchemasAccepted'):
        raise ValueError('Corrected schemas must have been accepted')
    return round(sum(r['reservationEstimateUsd'] for r in reports), 7)


def run_ceiling(prior, existing=None):
    return min(.18, ceiling(prior, existing))


def prepare(root, existing=None, peer=None):
    check_not_launched(root, peer if peer is not None else peer_checkout(root))
    prior = prior_reports(root)
    cap = run_ceiling(prior, existing)
    paths = set(PINS)
    paths.update(p.relative_to(root).as_posix() for p in (root/'src/main/java/com/example/recommend/chat').rglob('*.java'))
    paths.update('src/main/java/com/example/recommend/domain/' + n + '.java'
                 for n in ('Content', 'User', 'UserContentInteraction', 'InteractionType'))
    paths.update('src/test/java/com/example/recommend/chat/config/' + n + '.java'
                 for n in ('QualityBaselineTest', 'QualityBaselineLiveTest', 'QualityPilotBudget', 'QualityRemainingPlan',
                           'QualityRemainingFixture',
                           'QualityRemeasurementV2Plan', 'QualityConversationPlan',
                           'QualityConversationTrace', 'QualityConversationTest', 'QualityConversationPlanTest'))
    paths.update(('scripts/prepare_quality_conversation.py', 'scripts/test_quality_conversation.py',
                  'scripts/run_quality_conversation.ps1', 'scripts/prepare_quality_remaining.py',
                  'scripts/prepare_quality_remeasurement.py', 'scripts/prepare_quality_remeasurement_v2.py'))
    plan = dict(protocol=PROTOCOL, approvedModel='gpt-6-luna', approvedCumulativeUsd=1,
                priorReservationEstimateUsd=prior, runMaxEstimatedUsd=cap,
                cumulativeMaxEstimatedUsd=round(prior+cap, 7), billingTotalKnown=False,
                selectedCaseIds=['DC01', 'DC02', 'DC03'], selectedTurns=12,
                maxProviderRequests=60, sharedTurnOutputTokens=2048, timeoutSecondsPerTurn=20,
                retryRequests=0, maxRunSeconds=300, persistence='ISOLATED_H2_ACTUAL_HISTORY_SERVICE',
                priorReports=sorted(REPORTS),
                pinnedInputs=[dict(path=p, sha256=sha(root/p)) for p in sorted(paths)])
    folder = root/OUT
    folder.mkdir(parents=True, exist_ok=True)
    (folder/'run-plan.json').write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding='utf-8')
    return plan


if __name__ == '__main__':
    try:
        existing = os.getenv('CHAT_EVAL_MAX_USD')
        plan = prepare(Path(__file__).resolve().parents[1], None if not existing else float(existing))
        print(json.dumps({k: plan[k] for k in ('protocol', 'approvedModel', 'priorReservationEstimateUsd',
                                              'runMaxEstimatedUsd', 'cumulativeMaxEstimatedUsd', 'billingTotalKnown')}))
    except (ValueError, OSError, KeyError, TypeError):
        raise SystemExit('Conversation preflight failed: review ledger, public inputs, cap and launch markers.')
