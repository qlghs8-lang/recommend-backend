# Phase 6.4 — 다양한 표현의 대화 품질 평가

**추가 주제 기능 — 2026-11-24:** 사용자 요리/흑백요리사 예시 요청을 별도 TOPIC 조건으로 표현했다. 새 H2서비스9개·고정6질문의분류계약1개·SDK인용스키마1개통과,실제새분류18회선택검사는미실행이다. 조리/모조리 오탐은 실제공개자료관찰과합성검사수정전실패/후통과로확인했다. 최종작업본480통과/62건너뜀,Eclipse420통과/27건너뜀. 기존14개JSON·기존정답은보존,새자료를과거점수에합산하지않는다. 실제모델/생성/화면수용은별도이며제공자0회다. [주제 기능 보고서](AI_TOPIC_RECOMMENDATION_2026-11-24.md). 아래완료묶음/평가점수는그이전측정이력이다.

**최신 마감 — 2026-11-24:** 핵심 품질 개선 묶음의 최종 회귀/인계 완료. 작업본469통과/61조건부 건너뜀,Eclipse409통과/26건너뜀,React전체57통과,Python87통과/11선택건너뜀. 이번 신규 제공자·앱DB 호출0. 이전 실제 모델의 원본59/61·수정정책61/61·저장28/28·개인화4/4·시간8/8은 원본 해시와 판정을 재확인한 과거 측정이며 현재 코드 전체의 새 점수가 아니다. 상세·잔여·실행법은 [최종 인계](AI_QUALITY_CLOSURE_2026-11-24.md)를 우선 읽는다. 아래 항목은 시점별 기록으로, 이전 FAIL/미검증 문구를 이후 통과로 소급 수정하지 않았다. 원래47턴 수동 품질 감사와 실제 사용자+제공자+MySQL/화면의 동시 수용은 재실행하지 않았다.

최신 배포 수용(2026-11-24): 실제 실행 DB의 nullable 출처 컬럼 추가 완료. 별도 합성 MySQL/실제 JWT·HTTP/React 브라우저에서 저장·권한·이유 사슬·반응 변경·새로고침·오류 초안 유지 통과. AI는 고정 제공자,유료 호출0회다. [검사 범위와 한계](AI_DEPLOYMENT_ACCEPTANCE_2026-11-24.md)를 참고한다. 작업본463통과/57조건부 건너뜀,Eclipse403통과/22조건부 건너뜀,관련 프런트28통과. 실제 제공자와 운영 사용자 데이터를 연결한 통합 정확도 점수로 쓰지 않는다.

최신 구현 검증(2026-11-24): 기존 개인화 후속 고정 자료/실행기/채점기를 유지한 실제 모델/H2 4턴에서 계약4/4 및 비공개 제외·당시 근거·최초 요청 유지 게이트가 모두PASS다. 이전 FAIL 보고서는 보존한다. 작업본463통과/54조건부 건너뜀,Eclipse403통과/유료19건너뜀. 전체61문항은 재측정하지 않았다. [추천 출처 보존 보고서](AI_RECOMMENDATION_PROVENANCE_2026-11-24.md)에 범위·미검증 MySQL/화면을 기록했다.

최신 별도 검사(2026-11-24): 개인화 후속의 보안·저장 계약은 오프라인8개 및 실제 모델/H2 고정4턴에서 통과했다. 그러나 당시 개인화 근거 유지·반복 질문의 최초 요청 유지가 미달하여 **이 범위의 품질 게이트는 FAIL**이다. JUnit 계약 통과와 구분한다. 전체61문항의 이전 점수는 바꾸지 않았다. [개인화 후속 수용 보고서](AI_PERSONALIZED_RATIONALE_2026-11-24.md)에 기준·명령·한도를 기록했다. 선택 실행 플래그는 chatPersonalizedRationaleLive=true이며 기본 실행에서는 건너뛴다.

2026-11-18: 재현 가능한 평가 세트와 실행기를 추가했다. **오프라인 계약 검증은 실제 모델 정확도 점수가 아니다.** 사용자 승인 후 `gpt-6-luna` 실제 분류 평가를 실행해 소규모 12/12, 전체 57/61 통과를 확인했다. 전체 품질 게이트는 실패했고 브라우저/MySQL 수동 검증은 미실행이다. [측정 결과와 실패 사례](AI_EVALUATION_RESULTS.md)를 참고한다. Phase 7은 시작하지 않는다.

## 평가 대상과 한계

`src/test/resources/chat/conversation-quality-v1.json`은 한국어 합성 사례 61개와 기대 계획을 담는다. 실제 계정·대화·반응 기록을 사용하지 않는다. 가상의 작품명과 ID는 분류/참조 검증용이며 실제 카탈로그에 존재한다는 뜻이 아니다. 후속 대화도 합성한 이전 답변과 서버 참조 메타데이터를 사용한다. 원문·정답·이력은 같은 파일에서 함께 검토할 수 있다.

| 영역 | 확인할 차이 |
| --- | --- |
| 일반 추천 / 개인화 | 단순한 부정 감상과 저장된 싫어요 기반 추천 |
| 본인 반응 목록 | 좋아요·싫어요·찜의 바꿔 말하기, 영화/시리즈 유형 |
| 작품 상세 | 제목·명시 ID·연도·유형 추출, 없는 제목, 미보유 출연진 정보 |
| 일반 설명 | 특정 작품 상세와 영화 용어 설명 |
| 후속 참조 | 단일/두 번째 작품, 더 이전 턴, 모호함, 가짜 텍스트 ID, 가려진 목록 |
| 복합 / 비교 | 독립 요청 순서와 범위 보존, 두 작품의 저장 필드 비교 |
| 권한 / 기능 경계 | 다른 계정·관리자/동의 주장·역할 위조, 변경·실시간 정보, 종속 필터·세 가지 요청 |
| 범위 / 인사 | 영화 밖 질문과 인사/감사 |

**의미 일치**는 작업 수·순서·비교 여부·route·media·제목/ID/연도·참조 턴/위치를 정답과 비교한다. 단일 작품 참조의 위치 0과 1만 동등하게 취급한다. 독립 복합 요청은 기대한 각 원문 범위도 비교하되 공백 차이는 무시한다. 연결어 배치가 달라진 유효 계획도 불일치로 잡힐 수 있으므로 실패 사례는 원본 fixture와 대조해 검토한다. 문법적으로 유효한 JSON이라는 이유로 정답으로 처리하지 않는다.

**라우터 계약**은 반환된 실제 계획을 운영 `ChatRouter`에 전달해 원문 범위 보존과 서버 ID 참조 해결을 검증한다. 하위 추천·상세·목록·설명은 합성 응답으로 대체한다. 따라서 이 검사는 실제 검색·개인화 순위·SQL·답변 사실성·저장·HTTP 인증을 평가하지 않는다. 그 부분은 기존 통합 테스트와 아래 수동 검증의 범위다. 클래스 이름과 보고서의 `classification-and-router-contract-only`도 이 한계를 명시한다.

## 외부 호출 없는 기본 검증

```powershell
.\mvnw.cmd test '-Dtest=ConversationQualityTest,ConversationQualityLiveTest'
```

기대 계획 61개가 서버 계약을 통과하는지, 모든 기능/12개 영역/권한 경계가 포함되는지, 잘못된 분류·연도 누락·참조·범위 누락을 채점기가 실패로 처리하는지 검사한다. 실행기의 토큰 집계와 오류 후 호출 중단도 모의 제공자로 검사한다. `ConversationQualityLiveTest`는 명시적인 opt-in이 없으면 건너뛰고 키도 읽지 않는다. 전체 `test` 명령도 기본적으로 유료 호출하지 않는다.

## 실제 모델 분류 평가 — 명시적으로 선택 실행

이 실행은 **유료 OpenAI 요청**을 보낸다. 운영 설정 파일이나 실행 중인 Eclipse의 비밀 값을 읽지 않는다. 실행할 PowerShell 프로세스에 별도 평가용 `CHAT_EVAL_OPENAI_API_KEY`와 `CHAT_EVAL_MODEL`을 비공개로 설정한 뒤 실행한다. 키를 명령 인자·채팅·Git에 넣지 말고 환경 변수의 값을 출력하지 않는다. 키가 없어도 성공/skip으로 처리하지 않고 설정 오류로 실패한다.

```powershell
# 기본: 12개 영역에서 한 사례씩, 12회 이하 호출
.\mvnw.cmd test '-Dtest=ConversationQualityLiveTest' '-DchatQualityLive=true'

# 전체 세트 61개, 각 1회 (유료 호출 최대 61회)
.\mvnw.cmd test '-Dtest=ConversationQualityLiveTest' '-DchatQualityLive=true' '-DchatQualityLimit=61' '-DchatQualityRecordSyntheticPlans=true' '-DchatQualityRecordSyntheticResponses=true'

# 특정 영역만 확인. 아래는 privacy 6개, 각각 2회 = 최대 12회
.\mvnw.cmd test '-Dtest=ConversationQualityLiveTest' '-DchatQualityLive=true' '-DchatQualityCategory=privacy' '-DchatQualityLimit=6' '-DchatQualityRepeats=2'
```

실행기는 운영 `AiConfig`/`OpenAiClient.classify`와 고정 제공자 URL·strict schema·로그 OFF·재시도 0을 그대로 사용한다. 이 평가기의 기본 예산은 턴 20초/800 출력 토큰이며 현재 분류 상한은 544다. 이전 감사의 분류 상한은 512였고, 현재 실제 서비스 환경의 전체 2,048 예산에서는 분류 상한이 1,024다. 설정된 전체 예산 안에서 배분하며 두 종류의 결과를 같은 예산으로 합치지 않는다. 1초 간격으로 순차 호출한다. `chatQualityLimit`은 1–61(기본 12), `chatQualityRepeats`는 1–3(기본 1)이며 최대 183회 요청이다. 의미 불일치는 계속 수집하고, 429/503/504에서는 추가 호출을 중단해 남은 사례를 `NOT_RUN`으로 남긴다. 잘못된 제공자 출력(502)은 실패로 기록하고 다음 사례를 진행한다. 미실행·오류·불일치는 모두 품질 게이트 실패이며, 전체 사례에서 두 판정이 모두 통과해야 실행이 성공한다. 일부 영역/12개 smoke run 통과를 전체 평가 완료로 취급하지 않는다.

실행기는 애플리케이션의 요청 할당량 계층을 통과하지 않는다. 평가용 프로젝트의 지출/속도 한도를 별도로 설정한다. 분류 이후 추천/설명 제공자 호출은 하지 않으므로 이 결과로 전체 턴의 비용·지연·답변 품질을 주장할 수 없다.

보고서는 Git에서 제외된 `target/chat-quality/live-<UTC timestamp>.json`에 기록한다. 모델명·corpus SHA-256·예산·선택 범위·반복 수·각 합성 사례 ID의 두 판정/고정 오류 코드·입출력 토큰·분류와 로컬 검증을 합친 지연을 저장한다. 영역별 선택 수·통과 수·critical 실패 수·p95 지연·토큰 합계를 제공한다. `NOT_RUN`은 p95에서 제외하고 통과 분모에는 포함한다. 키·HTTP 헤더·원문 응답·모델 계획·질문·개인 기록은 저장하지 않는다. 실패한 제공자 호출의 토큰 사용량은 알 수 없어 0으로 남을 수 있으며 실제 청구량이 아니다. 이 보고서만으로 연결어 불일치 원인을 진단할 수 없으면 fixture ID로 합성 질문을 별도 재현한다. SDK 원문 로그를 켜지 않는다.

현재 정답은 구현된 기능 경계에 따른 사전 기대값이다. 실패하면 의미 오분류/추출 오류/원문 범위/참조/제공자 오류를 먼저 나눈다. 통과율을 높이려고 정답만 바꾸거나 키워드 라우팅을 추가하지 않는다. 유효한 대안 해석을 발견한 경우 경계를 검토하고 corpus 버전을 올린다. 같은 모델·corpus·기본 예산으로 전체 세트를 반복 실행해 차이를 비교한다. 초기 게이트는 전체 선택 사례 100%이며 이것이 임의의 실사용 질문에 대한 정확도를 보장하지는 않는다.

### 고정 합성 응답의 파싱 전 기록 (2026-11-19)

`chatQualityRecordSyntheticResponses=true`는 원본61개 고정 가상 자료의 제공자 output_text를 파싱 전에 기록한다. 기본값은 false다. 파싱 실패에도 해당 시도의 `providerTrace`에 완료 상태·불완전 사유·토큰·출력 문자 수·16000자 이하 합성 원문이 남는다. 긴 출력은 생략 표시를 남기며 SDK 오류 메시지·헤더·키·입력 대화는 기록하지 않는다. `chatQualityRecordSyntheticPlans=true`는 파싱에 성공한 내부 계획 기록이며 두 옵션의 목적이 다르다.

전체 회귀를 실행할 때는 위 명령처럼 두 옵션을 함께 사용해 실패 원문 수집 공백을 방지한다. 기본 평가의 채점·예산·회로 차단 규칙은 유지한다. 이 test 전용 기록기를 실제 사용자 대화에 연결하거나 SDK 전체 로그를 켜지 않는다. 세부 검증은 [일반 설명 진단 기록](AI_EXPLANATION_STABILITY_2026-11-19.md)에 남긴다.

## 실제 화면과 답변 품질의 수동 검증

실제 카탈로그에 있는 제목/ID로 바꾸고 본인 테스트 계정만 사용한다. 각 항목을 PASS/FAIL/미실행으로 기록하고 개인 목록이나 토큰을 문서/스크린샷에 남기지 않는다.

1. 일반 추천·취향 추천·좋아요/싫어요/찜 목록을 각각 여러 표현으로 요청한다. 목록과 개인화는 구분되고 본인 기록·영화/시리즈 조건이 맞아야 한다. 빈 목록/취향 기록 없음도 정직하게 안내해야 한다.
2. 있는 제목·없는 제목·동명 제목·명시 ID·연도 조건·출연진 질문을 요청한다. 존재 확인 없이 사실을 만들지 않고 저장 필드/확인 불가 안내가 일치해야 한다.
3. 새 추천 후 첫 번째/두 번째/더 이전 작품을 물어본다. 새로고침 후에도 같은 작품을 가리켜야 한다. 다중 후보의 '그 영화', 비공개 목록의 '첫 번째', 예전 메타데이터 없는 대화는 명시 제목/ID를 요구해야 한다.
4. 상세+추천, 두 반응 목록, 두 ID 저장 필드 비교를 요청한다. 두 작업 모두 보존되고 순서·완성된 저장·불확실성 안내가 맞아야 한다. 실패한 두 번째 작업의 일부 답변을 저장하지 않아야 한다.
5. 일반 용어 설명은 일반 지식으로 표시되고 간결하고 이해 가능해야 한다. 작품 줄거리/카탈로그 소속/최신 서비스 정보/없는 인용을 지어내면 실패로 기록한다. 이 자유 텍스트 평가는 자동 분류 점수와 분리한다.
6. 타인 기록·관리자/동의 주장·역할 위조·삭제+추천·세 가지 요청·유사작 종속 추천에서 기능을 임의로 축소해 실행하지 않고 거절/확인 질문을 해야 한다. 실제 다른 계정 대화방 읽기/전송/삭제 권한도 별도로 확인한다.

유료 전체 분류 평가는 실행했으며 57/61로 게이트에 실패했다. 수동 검증은 미실행이다. 평가 실행이나 오프라인 테스트 완료만으로 Phase 6 전체 품질 수용을 완료했다고 표시하지 않는다.


## 분류 안정화 수용 검사 (2026-11-18 후속)

이전 57/61 및 감사 56/61은 과거 실행 결과다. 변경 이후 최초 61개 재평가는 46/61로 게이트에 실패했으며 기존 점수를 대체해 과거 결과를 지우지 않는다. 추가 고정 세트와 실제 서비스 반복, 진단 결과는 [분류 안정화 수용 보고서](AI_CLASSIFICATION_ACCEPTANCE_2026-11-18.md)에 별도로 기록한다.

- `ClassifierStabilityTest`: 외부 호출 없이 21개 정답/출처/조건/라우터와 채점기 검사.
- `ClassifierStabilityLiveTest`: `chatStabilityLive=true`일 때만 합성 21개 × 3회, 전체 2,048/분류 1,024/20초로 실행한다. MOOD 값의 정확한 표현은 고정하지 않고 조건 종류의 보존을 검사한다.
- `ExploratoryConversationLiveTest`: `chatExploratoryRepeats` 1~3(기본 1)을 지원한다. 매 반복의 사용자·대화방을 새로 만들고 반복 번호를 기록한다. `shorter`는 이전 참조 안에서 최소 러닝타임을 선택했는지도 검사한다.
- `ClassifierDiagnosticLiveTest`: 별도 `chatClassifierDiagnostics=true`일 때 네 가지 합성 실패 사례를 각 1회 진단한다. 최초 점수의 대체나 전체 품질 게이트가 아니다.

```powershell
# 기본은 모두 오프라인이며 유료 클래스는 건너뛴다.
.\mvnw.cmd test '-Dtest=ClassifierStabilityTest,ClassifierStabilityLiveTest,ClassifierDiagnosticLiveTest'

# 사전 고정한 유료 검사. 실행 프로세스의 CHAT_EVAL_* 환경 변수만 사용한다.
.\mvnw.cmd test '-Dtest=ClassifierStabilityLiveTest' '-DchatStabilityLive=true'
.\mvnw.cmd test '-Dtest=ExploratoryConversationLiveTest' '-DchatExploratoryLive=true' '-DchatExploratoryCases=mood-and-change,compound' '-DchatExploratoryRepeats=3'
```

추가 보고서는 Git에서 제외된 `target/chat-quality/stability-*.json`과 `classifier-diagnostic-*.json`이다. 고정 합성 세트에 한해 모델 계획을 기록하며, 진단은 합성 제공자 출력도 제한 길이로 기록한다. 이 기록 방식을 실제 사용자의 대화에 적용하지 않는다. 원본61개 실행기는 기본적으로 사례ID·판정·토큰·지연을 저장하며, 위 두 opt-in으로 합성 계획과 파싱 전 원문을 추가할 수 있다. 기존 보고서의 계획이 없는 불일치 사례는 후속 진단 성공만으로 원인을 확정할 수 없다.

## 정책·정답·실제 서비스 응답 감사 (2026-11-19)

`QualityPolicyAuditTest`는 남은 rec-tv/detail-missing/clarify-ranking의 기록된 합성 제공자 원문과 **변경하지 않은 원본 정답**을 운영 서비스/H2에 재생한다. 별도 fixture `quality-policy-audit-v1.json`은 추가 모델 정답 세트가 아니며 과거 모델 호출의 출처/원문/계획을 보존하는 자료다.

```powershell
.\mvnw.cmd test '-Dtest=QualityPolicyAuditTest'
```

15개 검사(14개 서비스 시나리오+원본 해시/계획 검사),유료 호출0. 생성 보고서는 `target/quality-policy-audit/service-replay.json`이다. 원본 채점기는 추천 constraints 전체를 검사하지 않으므로, 기대 계획 일치와 실제 시간 조건 충족을 따로 검증해야 한다. 전체 모델58/61과 안정성63/63은 마지막 실제 측정으로 유지하며 감사15/15를 합산하지 않는다. [정책·응답 감사와 다음 수정 범위](AI_QUALITY_POLICY_AUDIT_2026-11-19.md)를 참고한다.

## 시리즈 시간 단위 확인 정책 (2026-11-19)

`duration-clarification-v1.json`은 원본61개와 분리한12개 정책 사례다. 모호한 시리즈 길이에 TV_DURATION_UNIT 사유와 최신 원문 근거를 요구하고, 명시 회당 길이/전체 시간/일반 추천/일반 확인/확인 후 후속 응답을 구분한다. 전체 시간 사례는 기존 UNVERIFIABLE 조건과 검색 전 확인 불가 안내를 요구한다. 원본 정답이나 기존 안정성 채점기를 완화하지 않는다.

```powershell
.\mvnw.cmd test '-Dtest=DurationClarificationTest,GroundedConversationTest'
# CHAT_EVAL_* 환경 별칭을 사용하는 opt-in. 고정12개×3회,전체800/분류544/20초.
.\mvnw.cmd test '-Dtest=DurationClarificationLiveTest' '-DchatDurationLive=true'
```

새 정책의 실제 모델 분류/라우터 계약은36/36 통과했다. 이 실행의 추천/상세 하위 서비스는 mock이므로 실제 SQL 결과·답변 사실성·저장을 검사한 점수가 아니다. 그 부분은 별도 H2 회귀로 검증한다. 기존 전체58/61은 **이번 제품 변경 전** 측정이며 재측정/재채점하지 않았다. 정확한 원문·명령·회귀와 동기화 결과는 [시간 단위 확인 보고서](AI_DURATION_CLARIFICATION_2026-11-19.md)를 참고한다.

## 작품 제목 경계 정책 (2026-11-19)

`title-boundary-v1.json`은13개 고정 사례로 모호한 제목 확인/전체 인용 제목/문장부호/아포스트로피/일반 제목·ID/비교/이전 참조와 기존 시간단위 사유를 검사한다. `TitleBoundaryPlanTest`는 축소된 제목이나 누락된 특정 사유를 실패로 잡는지도 확인한다. 원본 corpus/채점기와 분리하며 정답을 바꾸지 않는다.

```powershell
.\mvnw.cmd test '-Dtest=TitleBoundaryTest,TitleBoundaryPlanTest,GroundedConversationTest'
.\mvnw.cmd test '-Dtest=TitleBoundaryLiveTest#evaluatesFixedTitleBoundaryCasesWithoutChangingOriginalOracles' '-DchatTitleBoundaryLive=true'
```

최초 실행은3PASS 뒤400 invalid_json_schema로 중단했다. 고정 합성 큰따옴표 요청의 경계 enum에서 거절을 재현해, 큰따옴표 없는 원문 접미 경계로 수정했다. 동일13개×3회 재평가 **39/39** 통과. 최초 실패를 재채점하지 않는다. 이 역시 분류/라우터 계약 점수로 실제 SQL·저장·답변 사실성 검사는 H2와 구분한다. [제목 경계 보고서](AI_TITLE_BOUNDARY_2026-11-19.md)에 원문·원인·진단의 제한적 메시지 기록·모델/회귀 결과를 남긴다.

## 특정 작품의 우열 질문 정책 (2026-11-19)

`qualitative-comparison-v1.json`은 별도15개 고정 정책이다. 연출·연기·작품성 우열 요청에는 QUALITATIVE_COMPARISON 사유와 최신 원문 근거를 요구한다. 제목/ID 재요구나 실시간 순위 안내만으로 통과시키지 않으며, 일반 설명·저장 정보 비교·추천·권한·변경 요청 및 기존 확인 사유도 구분한다. 원본 정답과 채점기는 보존한다.

```powershell
.\mvnw.cmd test '-Dtest=QualitativeComparisonTest,GroundedConversationTest,ChatServiceTest'
.\mvnw.cmd test '-Dtest=QualitativeComparisonLiveTest' '-DchatQualitativeLive=true'
```

고정15개×3회 실제 모델 분류/라우터 검사와 실제 H2 서비스 검사는 분리한다. 후자는 소유권·저장·빈 안내 참조·명시적 후속 비교의 저장 행을 확인하며 모델을 mock한다. 실행 결과는 [우열 안내 보고서](AI_QUALITATIVE_COMPARISON_2026-11-19.md)와 AI_PROGRESS.md에 기록한다.

새 우열 안내 정책의 실제 gpt-6-luna 평가 **45/45**,변경하지 않은 기존 안정성 **63/63** 통과. 기존 전체58/61은 이전 측정이며 재실행/재채점하지 않았다. 전체 품질 수용 완료를 뜻하지 않는다.

## 수정 정책과 실제 저장 대화의 통합 수용 평가 (2026-11-19 시작)

`conversation-acceptance-v1.json`은 원본61문항을 변경하지 않고 세 정책의 별도 기준과3개 실제 대화14턴을 선언한다. `ConversationAcceptanceLiveTest`는 동일한 실제 분류 응답에 frozenV1Status와 revisedStatus를 함께 기록한다. 세 정책 외58문항은 기존 의미/라우터 기준을 유지한다. 이 점수의 하위 서비스는 mock이다.

`IntegratedConversationAcceptanceTest`는 H2의 실제 카탈로그·추천·상세·저장·다음 문맥을 연결한다. 오프라인14턴의 계획 재생과 유료 opt-in 실제 모델28턴을 분리한다. 후자는 분류와 필요한 추천 생성/도구도 실제 모델이며,사용자 DB나 애플리케이션 설정의 비밀 값은 사용하지 않는다. 소유권·위조 근거 원자성 검사도 포함한다.

```powershell
.\mvnw.cmd test '-Dtest=ConversationAcceptanceTest,IntegratedConversationAcceptanceTest'
.\mvnw.cmd test '-Dtest=ConversationAcceptanceLiveTest' '-DchatAcceptanceLive=true'
.\mvnw.cmd test '-Dtest=IntegratedConversationAcceptanceTest#actualModelTraversesTheSameStoredConversationsTwice' '-DchatAcceptanceServicesLive=true'
```

고정 자료/테스트 해시를 실행 전에 보존한다. 실패 뒤 정답을 덮어쓰거나 예전 원본 점수를 수정하지 않는다. 실제 측정·한도·남은 검사는 [통합 대화 수용 보고서](AI_CONVERSATION_ACCEPTANCE_2026-11-19.md)에 기록한다.

2026-11-20 완료한 새 측정: 원본 v1 **59/61**,수정 정책 **60/61 — 실패**,실제 모델+H2 저장 대화 **28/28턴 통과**. 제목 경계의 일반 CLARIFY 사유 누락1회가 남았으며 별도 대화 통과로 상쇄하지 않는다. 과거58/61과 각 정책의 과거 점수는 보존한다.

## 확인 사유 필수 결정 (2026-11-24)

새 단일 CLARIFY provider 분기는 reason/source를 항상 출력한다. 일반 확인은 명시 null/null,전용 확인은 기존 사유/현재 원문 쌍이다. 서버는 혼합 null을 거절하고 null/null을 기존 내부 일반 CLARIFY로 정규화한다. 과거 기록용 route-only 파싱은 유지하며 실제 생성 스키마에서는 제외한다.

`clarification-reason-v1.json`은 사전 고정9문항×3회다. 변경 전27/27,후27/27로 이번 재현/정확도 상승은 입증하지 않는다. 후 실행의 CLARIFY21개는 전용15개·명시null6개,필드 누락0개다. 후 실행에는 `-DchatReasonRequireWireFields=true`로 원시 출력 필드 존재도 검사했다. 기존 정답11개와 수정 정책 채점기는 유지한다.

전체61문항 새 측정은 **원본59/61·수정 정책61/61 통과**. 이전 수정60/61 실패는 보존한다. 실제 저장 대화와 회귀·동기화 결과는 [확인 사유 안정화 보고서](AI_CLARIFICATION_REASON_2026-11-24.md)와 AI_PROGRESS.md를 확인한다.

같은2026-11-24 변경 후 실제 모델+H2 저장 대화 **28/28턴**,작업본 회귀 **438통과/53조건부 건너뜀**,Eclipse 챗봇 **378통과/유료18건너뜀**. 기본 정책의 이번 자동 수용 통과와 MySQL/화면 등 미검증 범위를 구분한다.

## 실제 시간 데이터의 서버 수용 — 2026-11-24

보강한 영화20개/TV1개를 포함한 실제 공개468개를 읽어 격리 MySQL에 복사하고 고정 AI로 SQL·도구·JWT/HTTP·저장 검사21개를 수행했다. 양쪽 통과,성공7턴/원자적 거절2턴,추가 제공자 호출0. 초기에는 생성 중81→121분 변경에도200/오래된81분/메시지2개 저장을 재현했고,최종 조회를 JPA 관리 객체 재사용을 피하는 생성자 스냅샷으로 바꾼 뒤 같은 검사에서502/저장0으로 수정됐다. 실패 증거와 기존 정답을 보존했다.

작업본 전체521개 중463통과/58조건부 건너뜀/실패0,새 실제 수용1개는 별도 실행 통과. 실행본 챗봇426개 중404통과/22조건부 건너뜀/실패0이며 새 수용이 포함된다. 이는 실제 모델의 분류·문장 품질 점수가 아니며 기존 원본59/61·수정 정책61/61·저장 대화28/28과 합산하지 않는다. 브라우저/운영 대화도 이번 범위 밖이다. [실행법·데이터·한계](AI_RUNTIME_RECOMMENDATION_ACCEPTANCE_2026-11-24.md).

## 실제 모델의 시간 조건 평가 도구 — 2026-11-24

독립 `runtime-model-v1.json`의5개 대화/8턴을 추가했다. 실제 공개468개/시간 출처21개를 읽기 전용으로 확인해 H2에 복사하고,합성 계정의 저장 대화로 시간 조건·최단 후속·조건 초기화·회당 제한 교체·검증 불가/단위 질문을 검사한다. 합성8턴과 실제 공개 자료 고정 AI8턴은 통과했으나 현재 평가 키/모델이 미설정이므로 **실제 모델 수용은 SETUP_REQUIRED**다. 기존13개 JSON과 원본 점수는 보존한다.

`scripts/run_runtime_model_acceptance.ps1`는 사전 확인,`-Rehearse`는 제공자0회 예행,`-Run`은 숨김 키/명시 모델 입력을 통한 실제 분류·생성 실행이다. 최대28SDK요청·턴800토큰/20초·입력/총시간 예약을 적용하고 첫 실패 뒤 NOT_RUN 처리한다. 고정 AI 통과는 REAL_MODEL_REQUIRED로 표기한다. [실행법·판정·한계](AI_RUNTIME_MODEL_ACCEPTANCE_2026-11-24.md).

### 실제 모델 시간 조건8턴 통과 — 2026-11-24 20:43

같은 runtime-model-v1 질문/정답을 유지한 사용자 gpt-6-luna 재실행이8/8턴 PASS,전용 JUnit6통과/1건너뜀/실패0으로 종료했다. 건너뜀은 고정 AI 공개자료 예행이며 실제 모델 검사는 통과했다. 분류8+추천생성4=12요청,입력63,516/출력3,006토큰. 원본 공개 지문/질문 해시는 이전 예행과 같다. 이전401 두 번은 TMDB 토큰 입력으로 확인되어 이력에 보존했고 올바른 OpenAI 키로 재실행했다. 추가 호출/코드/정답 변경0. 이 작은 실제모델 수용 통과를 전체 품질·임의 문장·반복/도구 선택/브라우저 수용으로 확대하거나 기존 점수에 합산하지 않는다. [통과 결과와 한계](AI_RUNTIME_MODEL_ACCEPTANCE_2026-11-24.md).

## 상세·추천 이유 최신성 수용 — 2026-11-24

새 CatalogFreshnessAcceptanceTest는 실제 루프백HTTP/JWT/OSIV와 합성 H2에서 독립 JDBC 변경/삭제를 주입한다. 단일 이유·묶음 이유·독립 상세·상세+일반 설명의각3경우=12개 중 수정전4실패(200/메시지+2),최종12/12통과. 변경된 생성 답변은502/저장0이며 정상/삭제 처리와 소유권도 확인했다. 중간수정의 복합 상세UPDATE 실패도 보존한다. 이 검사는 고정 AI의 서버 계약으로,기존 실제모델8/8이나 전체 자연어 점수와 합산하지 않는다. 추가 제공자/앱DB 호출0. [결과·실행법](AI_CATALOG_FRESHNESS_2026-11-24.md).


## 시간 조건의 MySQL / React 수용 — 2026-11-24

공개468개/시간21개를 격리 MySQL에 복사하여 Eclipse 실행본의 실제 OSIV/JWT/HTTP와 React 화면을 연결했다. 고정 AI의 시간9문항·추천 이유·단일/묶음 이유/복합 상세의 생성 중 변경 거절과 재시도·401/404 소유권을 확인했다. 화면 성공12턴·의도적 실패2회에서 초안 보존/실패 저장0/새로고침 복원24개를 관찰했고 서버도24개를 확인했다. 실행본 JUnit2통과/실패·오류·건너뜀0,작업본 HTTP1통과,프런트28통과. 최초 검사 자료가 같은 줄거리를 덮어쓴 실패는 질문별 실제 변경으로 수정하고 이력을 보존했다. 제품 코드·기존 정답 변경0,제공자0회,원본 공개 지문 보존·임시 스키마 정리 확인.

이는 언어 품질 점수가 아니며 이전 실제모델8/8과 합산하지 않는다. 실제 사용자+실제 제공자+MySQL/화면의 동시 수용,임의 표현/반복,간헐503은 남아 있다. 다음 단일 단계는 최종 회귀·평가 결과 통합 정리다. [검사 범위·명령·증거](AI_RUNTIME_BROWSER_ACCEPTANCE_2026-11-24.md).
