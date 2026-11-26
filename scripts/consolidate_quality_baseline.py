"""Offline development baseline and UNJUDGED review packet; no credentials, DB or API.

Reads only allowlisted public fixtures/reports. Reserved validation is hashed, never parsed.
Pools are post-run review preparation, not pre-output independent human gold.
"""
import argparse
import ast
from collections import Counter
from decimal import Decimal
import hashlib
import html
import json
import math
from pathlib import Path
import re
import statistics

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = Path('target/quality-baseline-consolidation-20261126')
PINS = {
    'docs/evaluation/quality-development-v1.json': '812264884f994d24a32370b53baf8475b56ec7fa4e9f7246e187f5e0c830b687',
    'docs/evaluation/quality-validation-v1.json': '741f7ca6b176c19920ce0ea509f1e0ff58f4724601f27ccf8de89d9a0efb64ed',
    'target/quality-baseline-20261125/public-snapshot.json': '94d936d1e3c5bfdeff41a8b9fcbdf9b913f49e7797dd65597020590c844d50a4',
    'target/quality-remeasurement-v2-20261125/pilot-20261125131458782277600.json': 'bef8e286be4c6c65028692b0b9a37b3c98d2ded4d578b6c7be7f39bfa6246b79',
    'target/quality-remaining-20261125/pilot-20261125134737482110200.json': '3b4062e06d1a37910b0dc400f3eaaeed9295b19147c1191ef512912c1b600462',
    'target/quality-conversation-v2-20261125/pilot-20261126051043033663300.json': 'f8a545b54c0c8cdee97768c91ed3b7086beeca8b7b1fbb3e502c422f0d26b64d',
    'docs/evaluation/quality-phase2-scope-decision-v1.json': '0676fc62befac72aade9f6e9977e5aeb06a84dcd3e5d48438d82e79b4f3fe763',
}
REPORTS = [path for path in PINS if '/pilot-' in path]
REVIEWS = [
    'target/quality-remeasurement-v2-review-20261125/comparison.json',
    'target/quality-remaining-review-20261125/review.json',
    'target/quality-conversation-v2-review-20261126/comparison.json',
]
FAMILY_PROBE = dict(cooking='cooking', sports='sports', music='music_stage',
    medicine='medicine', law='law_investigation', science='science_space',
    work='work_creation', nature='travel_nature', family='family_care',
    friendship='friendship_growth', loss='loss_recovery', warmth='warmth_humor',
    fear='fear_tension', history='history_society', example_explicit='cooking',
    condition_change='science_space', feedback_reason='family_care')
PUBLIC_FIELDS = ('id', 'type', 'title', 'genres', 'release_date', 'runtime_minutes',
                 'episode_runtime_minutes', 'overview')


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_pins(root):
    for path, expected in PINS.items():
        if digest(root/path) != expected:
            raise ValueError('Frozen input changed: ' + path)


def probes(root):
    # Static literal extraction cannot execute the audit's DB entry point.
    tree = ast.parse((root/'scripts/audit_recommendation_landscape.py').read_text(encoding='utf-8'))
    return next(ast.literal_eval(n.value) for n in tree.body
                if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'PROBES' for t in n.targets))


def hard_observation(row, hard):
    """Single-record facts only; references/personalized seen state are NOT evaluated here."""
    failures = []
    if hard.get('media') and row['type'] != hard['media']:
        failures.append('media')
    for key, field in [('movie_minutes_max', 'runtime_minutes'),
                       ('episode_minutes_max', 'episode_runtime_minutes')]:
        if key in hard:
            value = row.get(field)
            if type(value) is not int or not 0 < value <= hard[key]:
                failures.append(key)
    genres = set((row.get('genres') or '').split(','))
    if genres.intersection(hard.get('genre_exclude', [])):
        failures.append('genre_exclude')
    return dict(supportedFailures=failures, fullHardStateEvaluated=False)


def independent_discovery(rows, seeds, terms, expected, key):
    """Deterministic bounded pool, independent of current run candidate/selected IDs.

    Includes same-data prior seeds, broad lexical probes and a hash-ordered sample.
    No relevance label is transferred from a seed. No full negative/recall claim.
    """
    found = {}
    def add(wid, reason):
        if len(found) < 32 or wid in found:
            found.setdefault(wid, []).append(reason)
    relevant_seeds = [s for s in seeds if s['family'] == FAMILY_PROBE.get(key)]
    for s in relevant_seeds:
        add(s['content_id'], 'PRIOR_PROVISIONAL_SEED_WITHOUT_LABEL')
    visible, tail = [], []
    for row in rows:
        full = (row['title'] or '') + '\n' + (row.get('overview') or '')
        shown = (row['title'] or '') + '\n' + java_visible(row.get('overview') or '')
        matches = lambda text: sum(bool(re.search(r'(?<!\w)' + re.escape(t), text, re.I)) for t in terms)
        v, f = matches(shown), matches(full)
        if v:
            visible.append((-v, row['id']))
        elif f:
            tail.append((-f, row['id']))
    for _, wid in sorted(visible)[:12]:
        add(wid, 'STEP1_BROAD_VISIBLE_CUE')
    for _, wid in sorted(tail)[:4]:
        add(wid, 'FULL_PLOT_ONLY_CUE')
    hard = expected['hard']
    if 'movie_minutes_max' in hard or 'episode_minutes_max' in hard:
        for row in rows:
            if not hard_observation(row, hard)['supportedFailures']:
                add(row['id'], 'TIME_METADATA_ELIGIBLE_NOT_SEMANTIC_GOLD')
    # Stable coverage sample, not claimed to be negative or related.
    ordered = sorted(rows, key=lambda r: hashlib.sha256((key + ':' + str(r['id'])).encode()).hexdigest())
    for row in ordered:
        if len(found) >= 24:
            break
        add(row['id'], 'HASH_ORDERED_UNLABELLED_COVERAGE_SAMPLE')
    return found


def java_visible(value):
    # Java substring(0,400) counts UTF-16 code units; preserve a split surrogate explicitly.
    return value.encode('utf-16-le', errors='surrogatepass')[:800].decode('utf-16-le', errors='surrogatepass')


def summary(rows):
    times = sorted(r['latencyMs'] for r in rows if r['status'] != 'NOT_RUN')
    errors = sum(r['status'] == 'AI_INVALID_RESPONSE' for r in rows)
    return dict(planned=len(rows), attempted=len(times), answered=sum('answer' in r for r in rows),
        recommendationTurns=sum(bool(r.get('selectedIds')) for r in rows),
        returnedWorkOccurrences=sum(len(r.get('selectedIds', [])) for r in rows),
        noRecommendationAnswers=sum('answer' in r and not r.get('selectedIds') for r in rows),
        invalidResponseTurns=errors, notRun=sum(r['status'] == 'NOT_RUN' for r in rows),
        invalidResponseRate=dict(numerator=errors, denominator=len(times), fraction=errors/len(times)),
        latencyMs=dict(scope='ISOLATED_H2_MODEL_ROUTER_NOT_HTTP_MYSQL_BROWSER', samples=len(times),
            p50=statistics.median(times), nearestRankP95=times[math.ceil(.95*len(times))-1], maximum=max(times)))


def validate_cases(specs, actual):
    if len(specs) != len(actual) or len({r['turnId'] for r in actual}) != len(actual):
        raise ValueError('Missing or duplicate actual development turn')
    if [r['turnId'] for r in specs] != [r['turnId'] for r in actual]:
        raise ValueError('Development scope/order mismatch')


def build(root):
    check_pins(root)
    decision = read(root/'docs/evaluation/quality-phase2-scope-decision-v1.json')
    if decision['authorizationSource'] != 'EXPLICIT_USER_REPLY_IN_THIS_CHAT' or decision['phase2ClosedWithinAdjustedScope'] is not True:
        raise ValueError('No explicit user authorization for limited phase2 closure')
    if digest(root/decision['originalCriteria']['path']) != decision['originalCriteria']['sha256']:
        raise ValueError('Original quality criteria changed; preserve the prior criteria')
    snapshot = read(root/'target/quality-baseline-20261125/public-snapshot.json')
    catalog = snapshot['rows']; byid = {r['id']: r for r in catalog}
    if len(byid) != len(catalog) or len(catalog) != 468:
        raise ValueError('Invalid public catalog identity/scope')
    specs = []
    for case in read(root/'docs/evaluation/quality-development-v1.json')['cases']:
        for i, turn in enumerate(case['turns'], 1):
            specs.append(dict(caseId=case['id'], turnId=case['id'] if len(case['turns']) == 1 else f"{case['id']}-T{i}",
                family=case['family'], question=turn['user'], expected=turn['expected']))
    reports = [read(root/p) for p in REPORTS]
    actual = [dict(r, turnId=r.get('turnId', r['caseId'])) for report in reports for r in report['results']]
    validate_cases(specs, actual)
    reviews = [read(root/p) for p in REVIEWS]
    observations = {r.get('turnId', r.get('caseId')): r for review in reviews for r in review['cases']}
    facts = [f for review in reviews for f in review.get('selectedEvidenceChecks', review.get('selectedFactChecks', []))]
    if len(facts) != 22:
        raise ValueError('Returned-occurrence fact review missing')
    actual_pairs = Counter((r['turnId'], wid) for r in actual for wid in r.get('selectedIds', []))
    reviewed_pairs = Counter((f.get('turnId', f.get('caseId')), f['contentId']) for f in facts)
    if actual_pairs != reviewed_pairs:
        raise ValueError('Fact review does not match actual returned occurrences')
    ledger = reviews[-1]['ledger']
    for r in ledger:
        if digest(root/r['path']) != r['sha256']:
            raise ValueError('Historical ledger changed')
    history = [read(root/r['path']) for r in ledger]
    total = lambda key: str(sum(Decimal(str(r[key])) for r in history).quantize(Decimal('.0000001')))
    budget = dict(totalReports=len(history), providerRequests=sum(r['providerRequests'] for r in history),
        usageResponses=sum(r['responsesWithUsage'] for r in history),
        unknownUsageRequests=sum(r['providerRequests']-r['responsesWithUsage'] for r in history),
        reservationEstimateUsd=total('reservationEstimateUsd'),
        knownUsageListPriceEstimateUsd=total('observedUncachedListPriceEstimateUsd'),
        approvedCumulativeUsd='3.00', exactBillingTotal='UNKNOWN')
    probe = probes(root);seeds = read(root/'docs/evaluation/quality-evidence-seeds-v1.json')['seeds']
    review_cases, records = [], []
    for spec, row in zip(specs, actual):
        key = spec['turnId'];family = spec['family']
        # Music replaces family in the last feedback turn; this is the fixed question's axis.
        discovery_family = 'music' if key == 'DC02-T4' else family
        policy_only = spec['expected']['policy'] in {'CLARIFY_TIME_UNIT','UNVERIFIABLE_EXPLAIN','CLARIFY_EXAMPLE_AXIS','CONFLICT_EXPLAIN'}
        found = {} if policy_only else independent_discovery(catalog, seeds, probe.get(FAMILY_PROBE.get(discovery_family), []), spec['expected'], discovery_family)
        baseline_ids = list(found)
        for wid in row.get('selectedIds', []):
            if wid not in found:
                found[wid] = ['POST_RUN_RETURNED_WORK_SUPPLEMENT']
        if len(found) > 36:
            raise ValueError('Review pool exceeds bounded scope')
        candidates = []
        for wid, reasons in sorted(found.items()):
            work = {f: byid[wid].get(f) for f in PUBLIC_FIELDS}
            work['visible_overview_utf16_400'] = java_visible(work['overview'] or '')
            candidates.append(dict(content=work, relevance=None,
                rationale=None, evidenceQuote=None, cannotJudgeReason=None,
                supportedHardFactObservation=hard_observation(work, spec['expected']['hard'])))
        review_cases.append(dict(**spec, syntheticUserQuestionHistory=[s['question'] for s in specs if s['caseId'] == spec['caseId'] and specs.index(s) <= specs.index(spec)],
            candidates=candidates, poolSize=len(found), notReviewedCatalogRows=468-len(found),
            policyOnlyNoCandidateScoring=policy_only,
            expectedReferencePolicy=spec['expected']['reference'], actualPublicReferenceReview='SEPARATE_PASS_AFTER_RELEVANCE_LABELS'))
        obs = observations[key]
        diagnostic = obs.get('provisionalStage', row['stage'])
        note = obs.get('provisionalReview')
        records.append(dict(**spec, sourceReport=next(p for p,r in zip(REPORTS,reports) if row['caseId'] in [v['caseId'] for v in r['results']]),
            actualStatus=row['status'], actualStage=row['stage'], actualPlan=row.get('actualPlan'),
            initialCandidateIds=row.get('initialCandidateIds', []), finalCandidateIds=row.get('finalCandidateIds', []),
            toolEvents=row.get('toolEvents', []), selectedIds=row.get('selectedIds', []), answer=row.get('answer'),
            latencyMs=row['latencyMs'], providerRequests=row['providerRequests'],
            errorType=row.get('errorType'), errorCode=row.get('errorCode'), rawErrorCause='UNKNOWN' if row['status']=='AI_INVALID_RESPONSE' else None,
            contextBefore=row.get('contextBefore'), referenceObservation=row.get('referenceObservation'),
            earlierStoryErrorTurnIds=row.get('earlierStoryErrorTurnIds', []), hardFactObservation=row.get('hardFactObservation'),
            provisionalDiagnostic=diagnostic, provisionalNote=note,
            semanticQuality='UNJUDGED', baselineDiscoveryIds=baseline_ids,
            reviewPoolSources=[dict(contentId=wid,sources=reasons) for wid,reasons in sorted(found.items())],
            returnedWorkSupplementIds=[wid for wid,reason in found.items() if reason == ['POST_RUN_RETURNED_WORK_SUPPLEMENT']]))
    input_paths = list(PINS) + REVIEWS + ['docs/evaluation/quality-evidence-seeds-v1.json', 'scripts/audit_recommendation_landscape.py', decision['originalCriteria']['path']]
    packet = dict(version=1, split='DEVELOPMENT_ONLY', status='UNLABELLED_REQUIRES_TWO_INDEPENDENT_HUMAN_REVIEWS',
        postRunPoolNotPreOutputGold=True, catalogExhaustivelyReviewed=False, modelOutputHiddenInThisPacket=True,
        validationQuestionsRead=False, labelScale={'0':'무관/충돌','1':'부분 연관/정보 불충분','2':'모든 핵심 요청축을 지지하는 줄거리','UNKNOWN':'판단 불가'},
        reviewerInstructions='먼저 각자 독립 판정. A는 전체, B는 모든0/1/UNKNOWN과2점의20% 이상 재검토. 불일치는 합의 또는UNKNOWN. 출력·기존잠정점수는 판정 전에 보지 않음. 공개참조/답변 설명력은 그 뒤 실제 이력과 별도 검토.',
        knownLimitations=['후보 풀은 실행 후 생성됐으며 준비자는 기존 출력을 봤음; 사전 독립 풀 요건 충족 아님.',
            '원문400자 경계는 UTF-16단위. 전체와 가시 범위를 별도 판정.',
            '대화 패킷에는 사용자 질문만 제공. 실제 공개참조/선행 오류/답변은 별도 기록으로 후속 판정.',
            '후보 밖 작품은 미판정이며 전체 카탈로그 부재/Recall을 주장하지 않음.'],
        cases=review_cases)
    result = dict(version=1, state='PHASE2_SCOPED_PROVISIONAL_BASELINE_CLOSED_QUALITY_UNJUDGED',
        phase2Complete=True, phase2CompletionScope=decision['closureScope'], originalV1CriteriaSatisfied=False,
        userApprovedScopeDecision=decision, latestScopeRule='Latest complete attempted run for each disjoint fixed development scope; historical failures retained separately',
        scopes=[dict(path=p, sha256=digest(root/p), model=r['model'], finishedAtUtc=r['finishedAtUtc'],
            summary=summary(r['results']), providerRequests=r['providerRequests'], reservationEstimateUsd=r['reservationEstimateUsd']) for p,r in zip(REPORTS,reports)],
        latestDevelopmentObservation=summary(actual), latestIsOneIntegratedRun=False,
        observedErrorTarget=dict(targetMaximumFraction=.05, observedNumerator=3, observedDenominator=36, status='FAIL_IN_THIS_OBSERVATION_NOT_REPEAT_ESTIMATE'),
        returnedFactChecks=dict(occurrences=22, parsedCriteriaAndVerbatimQuoteChecksPassed=22,
            fullUserIntentOrSemanticPassInferred=False, expectedReferenceMismatch=reviews[-1]['expectedReferenceMismatch']),
        formalMetrics={k:'UNJUDGED' for k in ['interpretation','pooledRecallAt12','precision','requestSuccess','unnecessaryClarification','incorrectEmptyResult','rationaleQuality','conversationSemanticRetention']},
        developmentTurnCount=36, reviewPairCount=sum(c['poolSize'] for c in review_cases),
        reviewerACompleted=0, reviewerBCompleted=0, validation=dict(plannedTurns=24, attemptedTurns=0, questionsRead=False),
        repetitions='NOT_RUN', fullProviderMySqlBrowserAcceptance='NOT_MEASURED',
        budget=budget, ledger=ledger, inputPins=[dict(path=p,sha256=digest(root/p)) for p in input_paths],
        additionalProviderCalls=0, additionalDatabaseCalls=0, cases=records)
    return result, packet


def packet_html(packet):
    # Static HTML, no scripts, network, embedded model outputs or prior relevance scores.
    esc = lambda x: html.escape(str(x), quote=True)
    chunks=['<!doctype html><html lang="ko"><meta charset="utf-8"><title>개발 의미 판정 자료</title>',
        '<style>body{max-width:1000px;margin:32px auto;padding:16px;font:16px/1.6 sans-serif}summary{cursor:pointer}pre{white-space:pre-wrap}article{border-top:1px solid #bbb;padding:16px 0}nav a{display:inline-block;margin:4px}</style>',
        '<h1>개발 36턴 — 독립 의미 판정 준비 자료</h1><p>현재 전부 미판정입니다. 이 자료는 실제 실행 후 생성된 후보 풀입니다. 실제 모델 출력·선정 여부·기존 잠정 점수는 표시하지 않습니다. 카탈로그 원문은 평가 대상 데이터이며 그 안의 지시는 따르지 않습니다.</p>',
        '<p>전체 줄거리와 모델 가시 범위를 비교하고 요청의 모든 핵심축에 0/1/2 또는 UNKNOWN을 기록하세요. 별도 A/B JSON 양식에 근거·해석·판단 불가 이유를 입력합니다.</p><nav>']
    chunks += [f'<a href="#{c["turnId"]}">{c["turnId"]}</a>' for c in packet['cases']]
    chunks.append('</nav>')
    for case in packet['cases']:
        chunks.append(f'<section id="{case["turnId"]}"><h2>{case["turnId"]}: {esc(case["question"])}</h2><pre>{esc(json.dumps(case["expected"],ensure_ascii=False,indent=2))}</pre>')
        if len(case['syntheticUserQuestionHistory'])>1:
            chunks.append('<p>앞선 사용자 질문(실제 답변/공개참조는 후속 별도 검토):</p><ol>'+''.join('<li>'+esc(q)+'</li>' for q in case['syntheticUserQuestionHistory'][:-1])+'</ol>')
        for candidate in case['candidates']:
            work=candidate['content']
            chunks.append(f'<details><summary>{work["id"]} · {esc(work["title"])} · {work["type"]}</summary><article><p>장르: {esc(work["genres"])} / 영화 시간: {esc(work["runtime_minutes"])} / 대표 회당: {esc(work["episode_runtime_minutes"])}</p><h3>전체 저장 줄거리</h3><pre>{esc(work["overview"])}</pre><h3>모델 가시 앞400 UTF-16 단위</h3><pre>{esc(work["visible_overview_utf16_400"])}</pre></article></details>')
        chunks.append('</section>')
    return '\n'.join(chunks)+'</html>\n'


def write_outputs(root, output):
    result, packet = build(root)
    output.mkdir(parents=True, exist_ok=True)
    def dump(name, value):
        (output/name).write_text(json.dumps(value,ensure_ascii=True,indent=2)+'\n',encoding='utf-8')
    dump('baseline.json',result);dump('semantic-review-packet.json',packet)
    (output/'semantic-review.html').write_text(packet_html(packet),encoding='utf-8',errors='xmlcharrefreplace')
    empty=dict(packetSha256=digest(output/'semantic-review-packet.json'),status='UNFILLED',independentHumanReviewer=None,
        turnReviews=[dict(turnId=c['turnId'],interpretation=None,responsePolicy=None,retainedHardConditions=None,
            semanticAxes=None,rationaleQuality=None,actualReferenceReview=None,evidence=None) for c in packet['cases']],
        labels=[dict(turnId=c['turnId'],contentId=v['content']['id'],relevance=None,evidenceQuote=None,rationale=None,cannotJudgeReason=None) for c in packet['cases'] for v in c['candidates']])
    dump('reviewer-A-unfilled.json',empty);dump('reviewer-B-unfilled.json',empty)
    print(json.dumps(dict(state=result['state'],turns=36,reviewPairs=result['reviewPairCount'],
        errors=result['latestDevelopmentObservation']['invalidResponseTurns'],budget=result['budget'],additionalProviderCalls=0)))


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/OUTPUT)
    args=parser.parse_args()
    write_outputs(ROOT,args.output)
