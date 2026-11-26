# 일반 설명 분류 응답 안정화 — 2026-11-19

## 이전 실패와 기록 공백

이전 전체 평가 `target/chat-quality/live-20261119044730785656700.json`의 explain-format (`영화와 시리즈는 어떤 차이가 있어?`,정답 EXPLAIN)에서 AI_INVALID_RESPONSE가 발생했다. 입력/출력3756/182,4679ms이나 파싱 전 원문이 없어 원인을 확정할 수 없었다.

## 합성 평가 기록 보완

ConversationQualityLiveTest에 별도 opt-in `chatQualityRecordSyntheticResponses=true`를 추가했다. 소스에 고정된 v1 가상 질문을 사용하며 실제 DB/사용자 대화를 받지 않는다. test 소스의 SyntheticClassifierTrace가 SDK 응답을 파싱 전에 기록하고, 기존 runner가 각 시도의 trace만 해당 결과에 연결한다. 기본값은 꺼짐이며 기존 의미 정답·채점·회로 차단을 유지한다.

- 완료 상태,불완전 사유,입출력 사용량,출력 문자 수와 16000자 이하의 합성 output_text만 보관한다.
- SDK 메시지·원인·헤더·키·입력 대화는 보관하지 않는다. SDK가 응답을 반환하지 않으면 고정 상태 no_response만 남긴다. 큰 출력은 원문을 생략하고 문자 수/생략 여부만 기록한다.
- 파싱 실패, 제공자 호출 전 실패, 후속 회로 차단의 trace가 앞뒤 요청에 섞이지 않아야 한다.
- 실제 로컬 SDK와 원래 평가기 회귀104/104 통과: `target/explanation-stability/trace-offline.log`.

## 수정 전 실행

제품 코드 수정 없이 기존 설명 질문4개(noir/format/macguffin/shot)를 각3회,총12회 실행했고 모두 통과했다. 보고서 `live-20261119081015299770500.json`에서 explain-format3회 모두 단일 EXPLAIN,comparison=false,splitAfter=null이었다. 이 결과가 과거 실패의 해결을 뜻하지 않는다.

고정 전체61개를 원래 순서로 1회 실행한 결과58/61이었다(`live-20261119081237699925900.json`). explain-format에서 같은 AI_INVALID_RESPONSE를 재현했고 이제 원문을 확보했다. 모델은 단일 EXPLAIN을 선택하고도 comparison=true를 반환했다. 제공자 상태completed,입력3778/출력129,출력103자,2826ms였다. 출력 한도 초과가 아니며 기존 서버의 비교 검증이 올바르게 거부했다.

기존 detail-missing은 제목XYZ,clarify-ranking은 이번에 DETAIL2개/비교true로 불일치했다. 이는 각각 원본 기대 제목과 CLARIFY에 맞지 않으며 안전한 대안이라는 이유로 성공 처리하지 않는다. 이전 원문 없는 오류까지 같은 내부 원인이라고 확정하지 않는다. 확보한 이번 설명 실패 원문은 `resources/chat/explanation-invalid-provider-v1.json`에 별도로 보관했다.

## 요청 조합 구조 보완

제공자 내부 JSON에서 requests를 composition 안으로 옮기고, nested anyOf로 아래 세 가지 기본 조합을 연결했다. 공개 HTTP 요청/응답 및 저장 데이터 형식은 바꾸지 않았다.

| 조합 | 요청 수/유형 | comparison | splitAfter |
|---|---|---|---|
| 단일 | 1개,기존 모든 기능 | false | null |
| 카탈로그 비교 | 정확히2개 DETAIL | true | null |
| 독립 복합 | 정확히2개,기존 조합 가능한 기능 | false | 제한된 원문 분리 문자열 |

단일 EXPLAIN을 생성한 뒤 비교 플래그를 true로 붙이는 조합은 새 제공자 스키마에 없다. 독립 복합에서는 null을 분리 문자열로 허용하지 않고, 분리 후보가 없으면 그 분기를 제공하지 않는다. requests를 먼저 출력하고 operation의 route를 먼저 결정하는 키 순서를 유지했다. 한 번에 생성 기능 하나,제목/ID/참조의 유효성,원문 보존,권한 등 추가 의미 제약은 기존 서버 검증을 계속 사용한다.

새 루트는 composition/constraints만 받고 composition은 requests/comparison/splitAfter만 받는다. parseProvider는 값 수정 없이 기존 flat 내부 형태로 옮겨 기존 검증을 실행한다. 이전 제공자 형식을 운영 경로의 fallback으로 수용하지 않는다. 과거 합성 원문은 test에서 구조만 변환하거나 기존 flat 재생 경로를 사용해 값과 원본 정답을 유지했다.

프롬프트에도 comparison=true를 두 개의 식별된 카탈로그 작품 비교로 한정하고,영화/시리즈 같은 일반 개념의 차이는 단일 EXPLAIN이라고 명시했다. [공식 OpenAI Structured Outputs 문서](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses)의 루트 object/nested anyOf 및 키 순서를 확인했다. 파서의 거부를 무시하거나 토큰 예산을 늘려 해결하지 않았다.

- 관련 SDK/파서/기존 비교/부정 감상/원문/라우터/H2 회귀168/168 통과: `scoped-offline.log`.
- 설명 H2 테스트를 새 제공자 형식부터 저장·일반 지식 표시·참조ID없음·소유권·실패 원자성까지 연결한 뒤24/24 통과: `service-offline.log`. 여기의 분류/설명 응답은 mock이며 실제 모델 평가는 별도다.
- 수정 후 설명4개 x3=12/12 통과(`live-20261119082640018615600.json`). 실제 제공자가 새 스키마를 수락했고 explain-format3회도 단일 EXPLAIN/비교false였다.
- 기존 원본 안정성21개 x3=63/63 통과(`stability-20261119082821271348500.json`). 전체2048/분류1024/20초의 원래 조건을 유지했고 기존 정답과 조건·원문·라우터 채점을 사용했다. 수정 후 새로 실행한 결과이며 과거63/63을 재사용한 것이 아니다.
- Eclipse 전체 챗봇341개 발견,330개 통과,유료11개 건너뜀,실패/오류0: 실행본 `target/explanation-stability-offline-verification.log`.

## 최종 결과와 한계

수정 후 전체는 **58/61**이었다(`live-20261119083452431595400.json`). explain-format을 포함한 설명4개와 비교3개 모두 통과했고 AI_INVALID_RESPONSE는 없었다. 다음 세 의미 불일치 때문에 전체 품질 게이트는 여전히 실패이며 결합 Maven 유료 실행은 exit1이었다.

| 남은 사례 | 원본 기대 | 실제 |
|---|---|---|
| rec-tv: 짧게 볼 만한 시리즈 있을까? | RECOMMEND | CLARIFY,completed,출력506/544 |
| detail-missing | 제목 `없는 작품 XYZ` | 제목 `XYZ` |
| clarify-ranking | CLARIFY | UNSUPPORTED |

rec-tv는 수정 전 전체에서 통과하고 이번에 새로 불일치했다. 스키마 변경 때문인지 확률적인 의미 판단 차이인지는 확인되지 않았다. 특히 '짧게'가 회당/전체 길이 중 무엇인지 모호한 표현이므로 현재 시간조건 정책과 과거 정답의 정합성을 검토해야 한다. 실제 사용자 품질 오류로 단정하거나 이 사례를 성공으로 바꾸지 않았다. 다음 단일 기능은 남은 세 사례의 요구 정책·원본 정답·실제 서비스 응답 정합성 감사다. 원본v1 점수와 정답은 보존하고 필요한 추가 평가는 별도로 구분한다.

유료 종료 후 작업본 전체436개 발견,390개 통과,46개 조건부 건너뜀,실패/오류0이다(`final-full-offline.log`). 브라우저/MySQL 실사용과 생성된 설명의 사실성은 이번 분류 평가 범위 밖이다. 간헐503은 안전한 AI_PROVIDER_FAILURE 이벤트가 아직 없어 별도 미해결이며,이번 파싱 오류 해결과 혼동하지 않는다. TMDB/DB 확장·Phase7도 이번에 시작하지 않았다.

## 재현 명령과 비용 범위

```powershell
# 수정 전/후 각각 일반 설명4개 x3
.\mvnw.cmd test '-Dtest=ConversationQualityLiveTest' '-DchatQualityLive=true' '-DchatQualityCategory=explanation' '-DchatQualityLimit=61' '-DchatQualityRepeats=3' '-DchatQualityRecordSyntheticPlans=true' '-DchatQualityRecordSyntheticResponses=true'
# 수정 전 전체61개 1회
.\mvnw.cmd test '-Dtest=ConversationQualityLiveTest' '-DchatQualityLive=true' '-DchatQualityLimit=61' '-DchatQualityRecordSyntheticPlans=true' '-DchatQualityRecordSyntheticResponses=true'
# 수정 후 전체61개 및 원본 안정성21개 x3
.\mvnw.cmd test '-Dtest=ConversationQualityLiveTest,ClassifierStabilityLiveTest' '-DchatQualityLive=true' '-DchatQualityLimit=61' '-DchatQualityRecordSyntheticPlans=true' '-DchatQualityRecordSyntheticResponses=true' '-DchatStabilityLive=true'
# 유료 실행 종료 후 작업본 전체
.\mvnw.cmd test
# 실제 Eclipse 실행본 디렉터리에서
.\mvnw.cmd test '-Dtest=com.example.recommend.chat.**.*Test'
```

평가 모델 gpt-6-luna,설명/전체의 전체800/분류544,안정성2048/1024,20초,재시도0을 유지했다. 총209회(수정전73/후136)의 합성 제공자 호출을 실행했다. 보고된 입력/출력은 전체849842/31728,수정후573660/24043이며 과금 총액이 아니다. 설명12회의 입력은45258→50178로 늘었고 고정된 입력 스키마 확대의 영향을 포함한다. 최초/성공/실패 보고서는 덮어쓰지 않았다.

명령 로그는 `target/explanation-stability/{trace-offline,before-explanation-live,before-full-live,scoped-offline,service-offline,after-explanation-live,after-acceptance-live,final-full-offline}.log`에 보관한다. 보고서 이름·해시는 `summary.json`,평가 중 제품 코드/원본 정답 고정은 `acceptance-manifest.json`에 기록한다.

## Eclipse 동기화

대상 지침/Git/파일 차이를 확인했고 검사 시점의 대상 해시가 바뀌면 복사를 중단하도록 했다. 변경 소스·테스트·합성 자료11개와 관련 문서4개만 동기화했다. 덮어쓴 기존 파일은 실행본 `target/explanation-stability-sync-20261119`에 백업하고 소스/대상 SHA-256을 검증했다. 변경 없는 ClassifierDiagnosticLiveTest 및 설정·비밀 값·관계없는 파일은 복사하지 않았다. 동기화 기록은 `eclipse-before.json`, `eclipse-after.json`, `final-manifest.json`이다.

실제 실행본 전체 챗봇 회귀도 통과했다. 앱은 재시작하지 않았으므로 Eclipse F5 후 백엔드를 중지·재실행해야 새 제공자 형식과 파서가 함께 적용된다. Eclipse 자체 재시작은 필요 없다.
