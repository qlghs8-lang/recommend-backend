# 부정적 감상 뒤의 추천 요청 — 2026-11-19

## 범위와 수정 전 근거

이 단계는 `어제 본 영화는 별로였어. 다른 SF 영화 추천해줘` 같은 명확한 새 추천을 불필요한 확인 질문으로 바꾸는 문제를 다룬다. 이전 원본 전체 평가 `live-20261118130025138562500.json`에서 해당 질문이 CLARIFY로 분류되었다. 원본 v1의 정답은 RECOMMEND다.

제품 수정 전에 한국어 원문·한국어 시리즈 변형·영어 변형·저장된 싫어요 목록·반응 기반 개인화·반응 변경 요청·식별 불가능한 작품의 필수 제외·타인 반응 조회의 8개 사례와 정답을 고정했다. 각3회,총24회에서 **21/24**가 통과했다.

- 보고서: `target/chat-quality/negative-opinion-20261119044004935878700.json`.
- 원래 한국어 문장은 이번에는 3/3이었다. 영어 변형에서 completed 상태지만 CLARIFY인 응답이 관찰되었다(출력534/한도544,JSON104자). 이 응답은 스키마/라우터 실행은 유효해도 의미 정답과 조건을 만족하지 못한다.
- 별도로 영어 1회·한국어 TV 1회가 max_output_tokens544로 incomplete였고 JSON 출력은 없었다. 그 두 응답과 의미 불일치를 같은 원인으로 단정하지 않는다.
- 관찰된 completed 실패 원문은 `src/test/resources/chat/negative-opinion-provider-v1.json`에 보관한다. 이전 한국어 실패 원문은 없으므로 동일한 내부 원인이라고 주장하지 않는다.

## 변경

LLM은 질문의 목적을 구조화하고 서버가 실제 검색·권한·저장을 실행한다. 이번에는 그 목적을 결정하는 프롬프트의 RECOMMEND 설명에 다음 경계를 명시했다.

- 요청하지 않은 과거 감상은 배경이다. 별도의 작업이나 저장된 반응으로 취급하지 않는다.
- 명확한 새 장르·영화/시리즈 추천에는 과거에 본 작품의 식별이 필요하지 않다.
- 감상 뒤의 단순한 another/different/다른은 그 자체로 식별 불가능한 작품의 필수 제외 조건이 아니다.
- 반면 특정 작품의 제외를 명시적으로 요구했는데 식별할 수 없다면 그 조건을 버리지 않고 CLARIFY를 유지한다.

등록된 싫어요 목록, 반응 기반 개인화, 반응 변경 요청, 타인 기록의 거부 규칙은 유지한다. 파서·제공자 스키마·검색·DB·프런트엔드·모델·토큰 및 시간 예산은 변경하지 않았다. 앱의 설정과 비밀 값도 건드리지 않았다.

## 검증 기준

새 평가기는 기존 의미/라우터 평가와 조건 평가를 함께 사용한다. 단순히 JSON을 파싱하거나 안전한 확인 질문을 반환했다고 성공으로 집계하지 않는다. 영화/시리즈 및 SF/코미디 조건, 원문 근거, 정확한 작업 수와 반응 목록의 매체도 검증한다. 원본 61개 및 안정성21개 정답 자료는 변경하지 않았다. 고정된 사례로 수정 전후를 비교하는 방식은 [공식 OpenAI 평가 지침](https://developers.openai.com/api/docs/guides/evaluation-best-practices)을 참고했다.

오프라인 H2에서는 올바르게 분류된 실제 제공자 형식으로부터 검색·추천·대화 저장까지 확인한다. SF 영화만 후보가 되고, SF 시리즈/코미디 영화는 제외되며, 원문이 그대로 전달되고, 감상 때문에 반응이 저장되지 않아야 한다. 타인 방은 제공자 호출 전에 거부한다. 필수 제외 대상을 모를 때는 추천을 시작하지 않고 확인 질문만 저장한다. 이 H2 테스트의 분류 응답은 mock이며 LLM 의도 정확성은 별도의 실제 모델 평가가 검증한다.

- 평가 기준 오프라인 초기9/9.
- 최초 관련 회귀는 추가 테스트의 Java21 List.getLast를 Java17 프로젝트에서 사용해 컴파일에 실패했다. 기존 Java17 방식으로 수정했다. 최초 로그 `scoped-offline.log`를 보존한다.
- 수정 후 관련 회귀93/93: `target/negative-opinion/scoped-offline-fixed.log`.
- Eclipse 전체 챗봇337개 발견,326개 통과,유료11개 건너뜀,실패/오류0: 실행본 `target/negative-opinion-offline-verification.log`.
- 수정 후 고정 범위 **24/24**: `negative-opinion-20261119044516503494700.json`. 동일한 800/544 예산에서 관찰된 출력 최대129토큰,불완전 응답0이다. 작은 확률적 표본이므로 앞으로의 모든 오류가 사라졌다는 보장은 아니다.
- 기존 후속 조건6개 x3 **18/18**: `followup-20261119045139235696.json`. 원래 평가 조건인 전체2048/분류1024를 유지했다. 이는 원본 stability21개 x3 전체를 재실행한 결과가 아니다.
- 원본 전체 **58/61**: `live-20261119044730785656700.json`. rec-negative-opinion과 비교3개 모두 PASS. 아래 세 실패 때문에 전체 게이트는 여전히 실패이며 Maven도 exit1이었다. 기존 실패를 정상 재실행으로 덮어쓰지 않았다.
- 유료 실행 종료 후 작업본 전체432개 발견,386개 통과,46개 조건부 건너뜀,실패/오류0: `target/negative-opinion/final-full-offline.log`.

| 전체 평가의 남은 실패 | 이번 결과 |
|---|---|
| explain-format: 영화와 시리즈는 어떤 차이가 있어? | AI_INVALID_RESPONSE, 입력3756/출력182,4679ms. 새 관찰이며 실패 원문이 없어 원인 미확정 |
| detail-missing | 정답 제목 `없는 작품 XYZ` 대신 `XYZ` |
| clarify-ranking | 정답 CLARIFY 대신 UNSUPPORTED |

이전 전체 점수도58/61이었지만 항목이 다르다. 새 일반 설명 오류가 이번 프롬프트 수정 때문인지, 별도의 확률적 실패인지는 현재 자료로 구분할 수 없다. 자동 재시도, 검증 완화 또는 원본 정답 변경으로 점수를 올리지 않았다. 다음 단일 작업은 explain-format의 실제 실패 원문을 고정 합성 진단으로 확보하고 안정화하는 일이다. 앞으로 전체 평가에서도 파싱 전 합성 응답을 확보할 수 있도록 진단 경로를 보완할 필요가 있다.

| 고정 사례 | 수정 전 | 수정 후 |
|---|---:|---:|
| 원본 rec-negative-opinion | 3/3 | 3/3 |
| 한국어 코미디 시리즈 변형 | 2/3 | 3/3 |
| 영어 SF 영화 변형 | 1/3 | 3/3 |
| 저장된 싫어요 목록 | 3/3 | 3/3 |
| 싫어요 기록 기반 개인화 | 3/3 | 3/3 |
| 싫어요 변경 요청 포함 | 3/3 | 3/3 |
| 식별 불가능한 작품의 필수 제외 | 3/3 | 3/3 |
| 타인 반응 조회 포함 | 3/3 | 3/3 |

고정 평가 모델은 gpt-6-luna, 이 범위의 전체800/분류544,20초,재시도0이다. CHAT_EVAL 별칭은 하위 테스트에만 전달하며 실제 사용자 대화·DB는 사용하지 않는다. 합성 출력 기록은 opt-in 테스트에만 있고 운영 로그를 추가하지 않았다.

총127회(수정전24/후103)의 합성 제공자 호출을 실행했다. 보고된 입력/출력은 전체477102/22003,수정후388995/16030이며 과금 총액이 아니다. 수정 전 불완전 응답의 사용량도 SDK trace에서 집계했다. 보고서 이름·해시는 `target/negative-opinion/summary.json`, 평가 중 제품 코드·원본 정답 고정 검사는 `acceptance-manifest.json`에 기록했다.

```powershell
# 수정 전/후 각각 고정8개 x3
.\mvnw.cmd test '-Dtest=NegativeOpinionLiveTest' '-DchatNegativeOpinionLive=true'
# 수정 후 원본 전체61개 및 기존 후속 조건18회
.\mvnw.cmd test '-Dtest=ConversationQualityLiveTest,FollowupConditionsLiveTest' '-DchatQualityLive=true' '-DchatQualityLimit=61' '-DchatQualityRecordSyntheticPlans=true' '-DchatFollowupLive=true' '-DchatFollowupScope=all'
# 유료 실행 종료 후 작업본 전체
.\mvnw.cmd test
# Eclipse 실행본 디렉터리에서
.\mvnw.cmd test '-Dtest=com.example.recommend.chat.**.*Test'
```

초기/실패/최종 로그는 `target/negative-opinion/{initial-offline,before-live,scoped-offline,scoped-offline-fixed,after-scope-live,after-acceptance-live,final-full-offline}.log`에 각각 보존한다. 반복3회는 평가 반복이며 실서비스 재시도를 추가한 것이 아니다.

## Eclipse 적용과 미검증 항목

대상 지침·Git 상태·변경 전 파일 해시를 먼저 확인했다. 덮어쓰는 파일은 실행본 `target/negative-opinion-sync-20261119`에 백업하고 소스·테스트·합성 자료5개와 관련 문서3개만 복사했다. `target/negative-opinion/eclipse-before.json`, `eclipse-after.json`, `final-manifest.json`으로 소스/대상 SHA-256을 확인했다. 설정·비밀 값·관계없는 수정은 보존했다. 실행 중인 앱은 재시작하지 않았으므로 Eclipse F5 후 백엔드를 중지·재실행해야 한다.

브라우저/MySQL 실사용 검증, 원본 전체의 다른 불일치, AI_PROVIDER_FAILURE 이벤트가 아직 없는 간헐503, TMDB/DB 확장 및 Phase7은 이번 범위에 포함하지 않는다.
