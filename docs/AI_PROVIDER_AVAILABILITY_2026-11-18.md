# 테스트 중 간헐적인 채팅 이용 불가 — 2026-11-18

## 관측과 결론

사용자 화면의 “지금은 AI 채팅을 이용할 수 없습니다”는 프런트 `chatErrorMessage`의 HTTP 503 분기다. 사용자는 새 대화 첫 질문은 성공하지만 인사 뒤 추천에서 오류가 났으며, 세 번째 대화에서는 같은 문제가 없었다고 보고했다. 실제 브라우저 네트워크 본문이나 외부 제공자 오류는 이번에 확보하지 못했다. 제공자/API/연결/요청 형식 중 어느 원인인지 **아직 확정하지 않았다**.

코드상 AI 비활성화와 다양한 SDK 오류가 같은 AI_UNAVAILABLE/503으로 변환된다. 따라서 화면의 503만으로 OpenAI가 HTTP 503을 반환했다거나 API 키가 잘못됐다고 단정할 수 없다. [공식 오류 안내](https://developers.openai.com/api/docs/guides/error-codes)를 참고했고, 기존 클라이언트 매핑을 유지했다.

- 로컬 백엔드는 8080에서 실행 중이었으며 JVM 시작 시각은 19:06:05였다. Eclipse 실행본과 작업본의 OpenAiClient/ClassifierPlan/AiConfig 소스 해시가 같았다. 실행 프로세스의 키·환경·개인 대화는 읽지 않았다.
- 고정 합성 문장 “안녕” → “애니메이션 추천 좀”, 합성 작품 12개의 분류/추천 호출은 정상 처리됐다.
- 추천 스키마의 interpretation 필드를 첫 순서로 고정한 별도 대조 실험도 정상 처리됐다. 필드 순서가 이번 503의 원인이라는 가설은 입증되지 않았고, 서비스 스키마를 바꾸지 않았다.
- 추가 실제 제공자 호출은 두 실행 합계 6회, 모두 completed. 보고된 입력/출력 토큰 합계 42737/713. 이는 과금 총액이 아니다. H2/MySQL·실제 계정·개인 대화는 사용하지 않았다.

## 변경 범위

`OpenAiClient.request`의 실패 처리에 안전한 WARN 진단만 추가했다. 사용자 응답·HTTP 상태·오류 코드·재시도 0·토큰/시간 제한·분류 및 추천 동작은 유지한다.

예시(실제 관측 오류가 아닌 형식 예시):

```text
AI_PROVIDER_FAILURE stage=RECOMMEND category=REQUEST_REJECTED httpStatus=400 code=invalid_json_schema parameter=text.format.schema
```

- stage: CLASSIFY / RECOMMEND / SELECT / RATIONALE / GENERATE. 서버가 만든 형식 이름을 고정 단계명으로 변환한다.
- category: 요청 거절, 인증/접근, 제공자 장애, 제한, TIMEOUT/TLS/DNS/CONNECTION 등 고정 분류다. httpStatus=0이면 외부 HTTP 상태를 확보하지 못한 SDK/연결 계열이다.
- code/parameter: 정해진 허용 목록에 정확히 일치하는 값만 표시하고 나머지는 OTHER로 치환한다. 외부 메타데이터 형식이 잘못돼도 기존 안전한 오류 응답을 유지한다.
- 예외 객체/스택/원인 메시지, 헤더, 키, 모델, 요청·응답 본문, 사용자 식별자, 대화, 카탈로그, 도구 인자는 기록하지 않는다. 기존 제한에 따라 한 요청의 제공자 호출 수도 제한돼 있다.

`OpenAiClientTest`에 원문/키·개행 주입 차단, throwable 미기록, 분류/추천 단계, HTTP 오류 구분, 잘못된 외부 메타데이터에서도 기존 오류 매핑과 재시도 0을 유지하는 검사 2개를 추가했다.

`RecommendationAvailabilityLiveTest`는 명시적으로 선택할 때만 실행되는 합성 진단이다. 기본은 서비스가 만든 순서를 유지하고, 고정된 대조값 contentId/evidence/interpretation만 별도로 선택할 수 있다. 운영 설정이나 DB를 읽지 않는다. 정상 원문 출력은 보관하지 않고 상태·사용량을 기록하며, 400일 때만 합성 요청의 제한된 오류 설명을 기록한다. 실제 사용자 대화에 이 방식을 적용하지 않는다.

## 검증과 기록

```powershell
# 이번 실제 실행: 기본 순서 1회, interpretation-first 대조 1회
.\mvnw.cmd test '-Dtest=RecommendationAvailabilityLiveTest' '-DchatAvailabilityLive=true'
.\mvnw.cmd test '-Dtest=RecommendationAvailabilityLiveTest' '-DchatAvailabilityLive=true' '-DchatAvailabilityForceOrder=interpretation'

# 진단 로그 추가 후 외부 호출 없는 최종 전체 검사
.\mvnw.cmd test
```

- 두 합성 실제 실행: PASS, 제공자 호출 각각 3회. 로그 추가 전 재현 시도이며, 간헐적인 실사용 오류가 해결됐다는 결과가 아니다.
- 관련 초기 회귀 133개 통과. 이후 외부 메타데이터 방어/추가 테스트를 포함한 최종 작업본 전체 407개 중 363개 통과, 44개 조건부 건너뜀, 실패/오류 0.
- Eclipse 전체 챗봇: 312개 중 303개 통과, 유료 9개 건너뜀, 실패/오류 0. 실행본 `target/provider-availability-offline-verification.log`. 현재 실행 중인 앱에 새 진단이 로드됐는지는 확인하지 않았다.
- 원래 전체 모델 품질 점수 baseline 58/61, stability 61/63은 유지한다. 이번에 재측정하거나 정답을 바꾸지 않았다.

실제 응답: `target/chat-quality/availability-20261118101208469273800.json`, `availability-20261118101430972262800.json`. 로그: `target/recommendation-availability/{before-live,interpretation-first-live,scoped-offline,full-offline}.log`. 이전 진행 문서는 `target/recommendation-availability/before/docs/AI_PROGRESS.md`에 보관했다.

## 실행본 적용과 다음 조사

Eclipse 대상 지침·Git·차이를 확인하고, 검사 당시 대상 해시가 달라지면 중단하는 방식으로 관련 소스/테스트 3개·문서 2개만 동기화한다. 원본 백업은 실행본 `target/provider-availability-sync-20261118-1918`, 해시 기록은 작업본 `target/recommendation-availability/eclipse-before.json`, `eclipse-after.json`이다. 설정·키·실행 설정·다른 사용자 변경은 보존한다.

**Eclipse F5 후 백엔드를 중지·재실행해야 새 진단이 적용된다.** 앱 재시작은 자동으로 수행하지 않았다. 오류가 다시 발생하면 그 시각의 Eclipse Console에서 `AI_PROVIDER_FAILURE` 한 줄로 분류/추천 단계와 제공자·연결 오류를 먼저 구분한다. 키나 전체 콘솔을 전달할 필요는 없다. 이 표시 없이 실패하면 비활성화/해당 SDK 외 경로/이전 실행본 여부를 다시 확인한다.

다음 단일 작업은 재발 시 안전한 진단 줄을 근거로 간헐 503을 특정하고 해당 원인만 수정하는 것이다. 원래 예정인 audit-shorter 조건 유지 진단은 이 실사용 문제 뒤에 이어서 진행한다. 다른 품질·DB·Phase 7 작업은 시작하지 않았다.
