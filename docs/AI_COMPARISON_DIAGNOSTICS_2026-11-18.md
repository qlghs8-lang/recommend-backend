# 비교 계획 진단 및 회귀 검증 — 2026-11-18

## 결과와 한계

이전 baseline의 `compare-polite` AI_INVALID_RESPONSE를 조사했다. 같은 서비스 코드·모델의 고정 비교 문장 3개를 각 3회 호출했지만 오류는 재현되지 않았다. **원인을 확정하거나 오류를 수정했다고 주장하지 않는다.** 검증을 완화하거나 추정에 따라 프롬프트를 바꾸지 않았다. 이번 단계는 비교 진단과 회귀 검증 보강으로 마무리한다.

- 최초 실패: `target/chat-quality/live-20261118093625526908200.json`. compare-polite의 입력/출력 사용량 3385/377, 지연 4387ms. 실패 원문은 없어 잘림·계획 필드 위반 등을 구분할 수 없다.
- 신규 진단: `target/chat-quality/classifier-diagnostic-20261118095440586623200.json`. compare-ids / compare-titles / compare-polite 각 3회, 총 9회 모두 PARSED. 성공 합성 원문 9개를 고정한 뒤 기존 정답과 의미·라우터 실행을 오프라인에서 모두 확인했다.
- 모델 gpt-6-luna, 전체 출력 800 / 분류 상한 544, 제한 20초, 재시도 0. 추가 제공자 호출은 9회뿐이며 보고된 입력/출력 토큰은 30480/1420이다. 과금 총액으로 해석하지 않는다.
- 기존 전체 baseline 58/61, stability 61/63의 **이전 실패 점수는 유지**한다. 이번에는 전체 유료 평가를 다시 실행하지 않았다. 9회 정상 진단이 전체 품질 게이트나 간헐 오류 해결을 뜻하지 않는다.

## 변경

1. `ClassifierDiagnosticLiveTest`에 제한된 comparison 범위를 추가했다. 기존 61개 자료에서 비교 3개만 골라 정확히 3회 실행한다. 기본 baseline 범위 4개는 유지한다. 임의 사용자 문장이나 실제 DB를 입력받지 않는다.
2. 단순 JSON 파싱 여부와 의미 정확성을 구분한다. 기존 정답과 의미가 다르거나 실제 라우터 검증을 통과하지 못하면 MISMATCH다. comparison 범위의 새 게이트는 9개 모두 PASS일 때만 통과한다. 오류·회로 차단 이후 NOT_RUN을 성공으로 집계하지 않는다.
3. 합성 진단 응답에 상태·사용량·불완전 응답 사유·출력 길이를 보관하고 16000자 이내의 합성 출력만 기록한다. 실제 서비스에는 응답 로깅을 추가하지 않았다. 이번 실제 9회는 이 추가 메타데이터/의미 게이트를 넣기 전의 진단 실행이며, 보강된 드라이버는 오프라인에서 검증했다.
4. `comparison-provider-v1.json`에 관측한 **성공** 응답 9개를 보관했다. 실패 원문을 재현한 자료로 표시하지 않았다. `ComparisonPlanRegressionTest`는 이 응답의 두 작품 식별자·전체 질문·기존 정답을 검증하며, 잘못된 split, 누락/충돌 식별자, ID 소수, 계정 필드, 부적절한 추천 조건을 거부한다. 정상 구조의 틀린 ID도 정답과 대조해 실패한다.
5. H2 실제 서비스 경로에 비교 테스트 2개를 추가했다. 영화/시리즈의 공개일·유형, 원자적인 두 메시지 저장, 신뢰할 ID 순서, 타인의 방 요청 차단, 잘못된 계획/근거 없는 ID에서 부분 저장 없음, 카탈로그에 없는 두 번째 작품의 확인 요청을 검증한다.

서비스 코드, 공용 API, 원문 범위/소유권 규칙, 총 예산, DB 스키마, TMDB importer, 프런트엔드는 변경하지 않았다. TMDB 데이터 확장 방향은 `TMDB_METADATA_FOR_AI_CHAT.md`의 기존 단계안을 유지한다.

## 검증

```powershell
# 이번에 실제 실행한 고정 진단: 3개 x 3회
.\mvnw.cmd test '-Dtest=ClassifierDiagnosticLiveTest' '-DchatClassifierDiagnostics=true' '-DchatClassifierDiagnosticScope=comparison'

# 외부 호출 없는 전체 회귀
.\mvnw.cmd test

# Eclipse 실행본의 전체 챗봇 회귀
.\mvnw.cmd test '-Dtest=com.example.recommend.chat.**.*Test'
```

키는 기존 프로세스 환경의 값을 하위 실행의 CHAT_EVAL_* 별칭으로 전달했으며 문서·명령 인자·로그에 쓰지 않았다. 새 comparison 의미 게이트의 실제 제공자 실행은 아직 하지 않았다. 위 유료 명령을 재실행하면 보강된 게이트를 사용하며, 이전 원본 보고서는 덮어쓰지 않는다.

- 작업본 전체: 404개 중 361개 통과, 43개 조건부 건너뜀, 실패/오류 0. `target/comparison-plan/full-offline.log`.
- 관련 파서/라우터/SDK/신규 회귀: 64/64 통과. `target/comparison-plan/scoped-offline.log`.
- H2 서비스 최초 실행에서는 테스트의 Mockito 재설정 방식으로 오류 1개가 발생했다. doAnswer 방식으로 수정한 후 전체 실행에서 H2 21개를 포함해 통과했다. 최초 실패 로그 `service-offline.log`도 유지한다.
- Eclipse 전체 챗봇: 309개 중 301개 통과, 유료 8개 건너뜀, 실패/오류 0. 실행본 `target/comparison-plan-offline-verification.log`. MySQL/브라우저·실사용 대화의 동작은 이번에 실행하지 않았다.

기존 정답 파일 SHA-256을 유지했다:

- conversation-quality-v1.json: `44f9cb9533dfb6bf6fe74a16069a1bceedfcc67f305b6a29e9a71c77467b1926`
- classifier-stability-v1.json: `310a21016c28504e7a3e14ae9ebc3aba1d6ec1d21bbd6efa8c0d5f2073f6a9c7`

작업 전 manifest `target/comparison-plan/before-manifest.json`의 서비스 코드 5개와 최종 파일 해시를 비교해 동일함을 확인했다. 진단·변경 파일의 해시는 `target/comparison-plan/final-manifest.json`에 보관한다.

## 동기화와 다음 단계

Eclipse 대상 지침·Git 상태·변경 파일 차이를 먼저 확인했다. 설정 및 관계없는 변경을 유지하고, 대상의 검사 당시 해시가 달라지면 복사를 중단하도록 했다. 변경 테스트 3개·합성 자료 1개 및 관련 문서 3개만 동기화한다. 덮어쓴 원본은 실행본 `target/comparison-plan-sync-20261118-1901`에 백업한다. 소스/대상 SHA-256 기록은 작업본 `target/comparison-plan/eclipse-before.json`, `eclipse-after.json`이다. 실행본의 전체 챗봇 테스트도 통과했다. 서비스 소스 변경은 없어 이번 테스트 변경을 위해 실행 중인 백엔드를 다시 시작할 필요는 없으며 Eclipse에서 F5로 갱신하면 된다.

다음 단일 기능은 `audit-shorter`의 조건 유지 오류(MOOD/SITUATION 의미 구분 및 AI_INVALID_RESPONSE)를 합성 응답으로 진단하는 것이다. 비교의 간헐 오류는 미해결 상태로 유지하고 재발 시 새 진단 근거로 조사한다. 자동 재시도, 검증 완화, 정답 교체는 하지 않는다. Phase 7이나 DB 확장을 이번 작업에서 시작하지 않는다.
