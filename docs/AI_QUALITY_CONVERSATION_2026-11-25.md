# 개발 대화 DC01–DC03 — v1 부분 결과 검토

2026-11-25 23:19:19 KST 사용자 실행은 **11시도/8답변/검증오류2/예산차단1/NOT_RUN1**로중단됐다. 4추천턴에서5작품을반환했고4답변은추천0이다. 미완료JUnit1실패·exitCode1/STOPPED를그대로보존했다. 합성자료정리성공,8성공턴16메시지저장·실패0저장을확인했다. 아래준비시점의0호출/승인$1·한도$0.18은이력이며현재준비상태와혼동하지않는다.

**현재 후속:** 사용자 누적 승인 총$3으로 [동일12턴 v2 실제 결과 검토](AI_QUALITY_CONVERSATION_V2_2026-11-26.md)를 완료했다. v2는12시도/10답변/동일단계오류2·예산차단0·NOT_RUN0,19요청/19usage·예약$0.2004644다. 누적 예약$1.1838605/실청구UNKNOWN이며 추가 검토 호출0이다. v1의 수치/원본/마커는 그대로 남긴다. 다음은 추가 모델 호출 없이2단계 기준값 통합·독립 의미 판정용 후보 자료 준비다. 예산 확대를 품질 오류 해결로 판정하지 않는다.

## 실제부분결과와실패구분

- 실제17요청/17사용량응답,입력76,711/출력6,973토큰. 회차예약$0.1753495/당시한도$0.18,알려진사용량기준$0.0111576다. 기존포함100요청/92usage/8UNKNOWN,예약$0.9833961·알려진기준$0.0627077·실청구UNKNOWN이다.
- DC03-T3의영화100분해석은끝났으나추천생성호출전LimitReached,마지막T4는NOT_RUN이다. 가짜SDK로다음요청을재구성한최소16출력허용에서도예약추정$0.1847827이어서비용예약차단임을확인했다. 추가제공자/DB호출0이다.
- DC01-T3은INTERPRETATION/DC02-T3은RATIONALE의AI_INVALID_RESPONSE다. 원시응답이없어정확한JSON/인용/내용위반원인은UNKNOWN이다. DC01-T4의토이스토리53이유에모델작업지시같은영문이노출됐다. 우주라는문자일치와중심우주서사/설명품질은별개로평가한다.
- DC02-T2는코코41한작품만있으므로없는두번째를만들지않고확인했다. T3은직전확인답변의ID가없다는trace와달리referenceTurn2로앞선서버코코41/원래가족요청을해석했다. 가짜ID/소유권위반으로세지않고그뒤이유검증실패와분리한다. DC01-T3실패질문은이력에저장되지않아T4의제외/조건전이에파급영향이있다.
- 저장된10계획/5스키마·135인용/반환5작품의ID·파싱조건·원문을검사했지만독립의미점수는UNJUDGED다. CLARIFY/RATIONALE행의문자적격468을실제추천후보수로세지않는다.

원본:target/quality-conversation-20261125/pilot-20261125141919368863300.json,SHA-256`c173a2aa9188f6e3617056034558cf7bc6299b85063dd46e1824b6f55f06b7b2`. 상세관찰/비용/재생은target/quality-conversation-v2-20261125/{v1-review.json,v1-replay/summary.json,v1-input-verification.json}에기록했다. v1당시모든입력해시일치는v2의평가소스변경전에검증했다.

## 이전실행준비이력

다음은실제실행전의범위/도구/오프라인검증기록이다.

## 범위와 실행 경로

개발 quality-development-v1.json의 DC01–DC03 각4턴만 순서대로 실행한다. 단일턴D01–D24나 별도 검증24턴은 반복/실행하지 않는다. 개발 파일 SHA-256은 `812264884f994d24a32370b53baf8475b56ec7fa4e9f7246e187f5e0c830b687`이다. 공개468개 스냅샷 `94d936d1e3c5bfdeff41a8b9fcbdf9b913f49e7797dd65597020590c844d50a4`를 격리 H2에 복사하며 실제 회원·운영 대화·운영 DB는 사용하지 않는다. 별도 검증은 내용 대신 해시 보존만 확인한다.

| 대화 | 측정할 전이 |
|---|---|
| DC01 | 우주/영화/120분→그중 관계축 추가→시간만 제거·이전 공개 작품 제외→120분 복귀·공포 제외 |
| DC02 | 가족 영화→두 번째 제외(없으면 확인)→방금 추천의 당시 요청 근거→가족 제거·음악 도전으로 교체 |
| DC03 | 짧은 시리즈의 시간 단위 확인→회당30분→영화100분으로 변경→이전 시리즈 회당 조건으로 복귀 |

`ConversationService → ChatHistoryStore.prepare → ChatRouter/기존 분류·검색·선정·검증 → ChatHistoryStore.complete`의 실제 제품 경로를 사용한다. 세 대화는 각각 새 합성 방에서 시작하며 같은 가짜 소유자 계정만 쓴다. 서버에 저장된 실제 이전 응답의 AiMessage.contentIds와 추천 provenance를 사용하고,실패한 턴은 대화 이력에 임의로 추가하지 않는다. HTTP/JWT/화면/운영 MySQL의 수용 검사가 아니다.

QualityConversationTrace는 의미 명세를 제품 계획으로 강제하지 않는다. expectedState와 actualPlan·실제 후보/선택/답변을 따로 기록한다. 사용자/답변 문자열의 ‘ID’ 표기는 신뢰 참조로 읽지 않는다. 두 번째 공개ID가 없으면 MISSING_PREVIOUS_POSITION_2,이전 공개 작품이 없으면 MISSING_PREVIOUS_PUBLIC_IDS로 남긴다. 확인 응답이 최신 턴이면 과거 추천을 건너뛰어 최근 추천인 것처럼 만들지 않는다. 이전 오류 턴ID도 후속 턴에 기록한다. 앞선 실패·빈 결과 때문에 생긴 참조 부재를 숨기지 않는다.

제외 관찰은 고정 의미 명세의 EXCLUDE_PREVIOUS_PUBLIC_IDS에 대해 현재 서버 이력의 이전 공개ID 합집합을 기록하고 KEEP_EXCLUSIONS에 이월한다. 특정 두 번째 제외는 실제 직전 답변의 위치2만 기록한다. 이 집합은 평가 관찰이며 제품 조건/모델 입력에 주입하지 않는다. 조건 제거·복귀의 정답 문구와 실제 계획을 비교할 수 있게 보존하고,자동 사실 검사는 선택 ID/유형/등록 시간/null/공포 제외·참조 포함/제외를 구분한다. 추천0은 NO_SELECTION_NOT_A_PASS,의미 지표는 독립 사람 라벨 전 UNJUDGED다.

## 비용·실패·개인정보 경계

모델은 기존 승인 **gpt-6-luna**,승인 누적 **$1**이다. 과거8개 실제 결과의83요청/75사용량응답/8UNKNOWN을 해시 고정하며 예약 추정 **$0.8080466**,알려진 사용량 기준 추정 **$0.0515501**,실청구 UNKNOWN을 유지한다. 이번 회차 예약 추정 한도는 **$0.18**,기존 포함 추정 상한은 **$0.9880466**이다. 기존 CHAT_EVAL_MAX_USD가 더 낮으면 준비·실행·Java 검증에서 더 낮은 값을 유지하고 인상을 거절한다.

현재 공통 QualityPilotBudget의 제한을 유지한다: 회차 최대60제공자 요청,요청별120,000/전체600,000 UTF-16 문자,예약 출력122,880토큰. 턴 전체 공유 출력2,048토큰/20초,SDK 재시도0이다. 회차5분 경과 후 새 요청 예약을 거절하며 진행 중 요청은 해당 턴 시간 상한을 따른다. 복합 요청이나 이유 설명도 같은 실제 SDK 요청 가로채기에서 비용을 예약하므로 ‘12×분류1회’로 축소 계산하지 않는다. 구조적 최악 예약 추정은$0.18보다 커 **12턴 전에 예산이 소진될 수 있다**. 그 경우 부분 결과/NOT_RUN을 보존하고 자동 재실행·실패 예약 환급·한도 인상을 하지 않는다.

비용은 기존과 같은 진단용 보수적 문자/출력 대리 추정이며 청구액 보장이 아니다. 공식 단가/평가 경계는 [OpenAI Docs 가격](https://developers.openai.com/api/docs/pricing)과 [평가 지침](https://developers.openai.com/api/docs/guides/evaluation-best-practices)을 확인했다. 기존 입력$0.10/출력$0.50 per1M 대리 계수/캐시 무할인 기준을 변경하거나 이전 원장을 재계산하지 않았다.

모든 성공/오류/미실행을 기록한다. 제공자429/503/504나 예약 한도 도달은 전체 나머지 호출을 중단한다. 비치명적 파싱 오류는 원래 제품처럼 해당 턴을 저장하지 않고 다음 고정 질문을 진행하며 선행 오류를 표시한다. 원시 분류 응답은 별도 저장하지 않고 검증된 계획만 기록하므로 정확한 잘못된 JSON 원인은 UNKNOWN일 수 있다. JUnit 성공은 범위 시도/기록/정리 완료이며 의미 품질 합격이 아니다.

가짜 계정2명과 소유자 방3개·공개 카탈로그 복사는 H2 JDBC URL을 확인한 뒤 생성한다. finally에서 방/메시지→계정→카탈로그를 정리하고 정리 여부를 보고서에 쓴다. 가짜 이메일/비밀번호 표식이 제공자 요청에 들어가면 호출 전에 차단한다. 실제 사용자 데이터나 비밀값을 평가 파일/로그/모델에 복사하지 않는다. 추천 provenance는 기존 서버 전용 경계를 따르고 평가에는 합성 원래 질문/공개ID만 관찰한다.

## 새 파일과 검증

신규 QualityConversationPlan/Trace/Test/PlanTest와 prepare_quality_conversation.py/test_quality_conversation.py/run_quality_conversation.ps1를 추가했다. 기존 실행기·제품·평가 검사·JSON 코퍼스는 보존했다. 새 프로토콜은 **FIXED_DC01_DC03_CONVERSATION_V1**,출력은 **target/quality-conversation-20261125**다. 자기/상대 작업본의 launch-started.json 또는 pilot-숫자.json 발견 시 준비부터 차단한다. 여덟 과거 결과의 추가/누락·해시/모델/누적 예약 불일치도 거절한다.

작업본 명령:

```powershell
.\mvnw.cmd -o -Dtest=QualityConversationTest,QualityConversationPlanTest,QualityPilotBudgetTest -DqualityConversationPrepared=true test
python -m unittest discover -s scripts -p test_quality_conversation.py -v
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run_quality_conversation.ps1
```

작업본 Java18검사 중 **17통과·실제 모델1선택미실행**,실패/오류0,BUILD SUCCESS다. 새 Python6와 기존15를 합쳐21검사 통과했다. 낮은$0.05 계획의 Java8/8과 PS 준비,PS 파서 오류0도 확인했다. 현재 키 구성=false다. 이 준비 중 실제 제공자/운영 DB/TMDB 호출0이다. 전체 Java/React 회귀는 재실행하지 않았다.

고정12턴 합성 재생은 기록/저장/참조 경계를 검사할 스크립트 분류·선정으로 실행했다. 가족/우주 적합성 정답을 대신하지 않으며 실제 모델 점수로 합산하지 않는다. 별도 검사는 실패 턴0저장·다음 턴에 가짜 이력 없음,타인 방404/제공자 미호출,추천 이유의 저장된 원래 요청/ID 복원,가짜 두 번째 ID 무시,제외 집합 이월,시간null/잘못된 유형/참조 위반·빈 선택 비합격을 확인했다. 합성 사용자/방/카탈로그 정리도 통과했다.

준비 중 초기 SDK 종료 구문의 컴파일 오류를 기존 SDK의 명시적 close 방식으로 수정했다. 제한 환경의 H2 초기화 정체는 이번 Surefire JVM만 식별해 종료/로그 보존 후 권한확장 오프라인 검사로 통과했다. Python 기본 임시 경로의 권한 오류는 작업본 target 내부 임시 폴더로 변경했다. 이어 합성 재생에서 앱 기본 요청 제한이 평가 bean에 덮여 AI_BUSY가 난 것을 테스트 전용 app.ai 속성으로 고정해 같은 검사를 통과했다. 제품/정답은 바꾸지 않았고 실패 로그를 보존한다.

Eclipse 추가지침/초기 문서 차이0을 확인하고 새8파일/갱신4문서,총12파일만 선택 동기화했다. 덮어쓴4문서는 실행본 target/quality-conversation-sync-20261125에 초기 백업했다. 실행본도 같은 Java17통과/1실제모델미실행·Python21통과·PS준비·합성자료 정리를 확인했다. 원본/대상12파일·백업4개 해시 일치,기존273파일 중 의도한4문서만 변경·269개/제품/기존검사/스크립트/고정코퍼스/과거 결과 보존과 문서 링크/공백 검사를 확인했다. Content/User의 기존 주석 차이는 보존하고 각 작업본의 로컬 도메인 해시를 해당 계획에 따로 고정했다. 제품/설정/비밀파일 복사0이다. 근거는 target/quality-conversation-20261125/{run-plan.json,readiness.json,source-offline-final.log,source-python-all.log,source-lower-cap.log,source-lower-cap-readiness.log,rehearsal-*.json,eclipse-before.json,eclipse-after.json,verification.json}이다. rehearsal 파일은 실제 pilot 결과가 아니다.

## 이전 로컬 실행 절차 — 재실행 금지

아래는 v1의 보존용 명령이다. 현재는 위 v2 문서의 새 실행기를 사용하며 이 v1 명령을 다시 실행하지 않는다.

```powershell
& 'C:\Users\jaehyeon\recommend-backend\scripts\run_quality_conversation.ps1' -Run
```

시작 안내에 FIXED_DC01_DC03_CONVERSATION_V1,검사 로그에 QualityConversationTest가 보여야 한다. -Run은 actualModelConversationBaseline 메서드만 실행하며 준비용 합성 재생을 함께 돌리지 않는다. 임시 키/모델/한도는 finally에서 제거/복원한다. 실패하면 재실행하지 않고 오류/결과부터 검토한다.

다음 단일 작업은 **현재 기능의 DC01–DC03 실제 결과·참조 부재/조건 전이/선행 실패·누적 비용 검토**다. 독립 의미 라벨/별도 검증24턴/반복/실제 모델+MySQL+React 수용은 미완료다. 같은 단일턴 반복이나 제품 패치/3단계 수집은 시작하지 않았다. 이번은 평가 도구/문서만 변경하므로 Eclipse F5 갱신만 필요하고 백엔드/IDE 재시작은 필요하지 않다.
