# 비교 계획 구조 안정화 — 2026-11-18

## 확보한 실패 근거

기존 대표 평가의 질문 12개를 같은 순서로 정확히 3회 실행했다. 수정 전 결과는 **35/36**이며, 세 번째 `compare-ids`에서 AI_INVALID_RESPONSE가 재현되었다. 비교 질문만 따로 반복하던 진단과 달리, 앞선 8개 질문도 유지했다. 순서가 원인이라는 증거는 아니다.

- 보고서: `target/chat-quality/classifier-diagnostic-20261118125228412745600.json`.
- 합성 질문: `ID 42와 ID 73의 저장된 장르와 줄거리를 비교해줘`.
- 제공자 상태 completed, 입력/출력 3612/269, 출력 320자, 3562ms. 출력 한도 544 미만이며 불완전 응답이 아니다.
- 두 DETAIL의 ID 42/73 및 comparison=true는 맞았지만, splitAfter에 null 대신 `42와`를 반환했다. 기존 서버가 이를 거부한 동작은 올바르다.
- 이 원문은 `src/test/resources/chat/comparison-invalid-provider-v1.json`에 별도로 보관한다. 이전 성공 재생 자료와 기존 정답 파일은 변경하지 않는다. 과거 원문 없는 compare-polite/compare-ids 실패까지 같은 원인이라고 단정하지 않는다.

## 구현

제공자 전용 JSON의 comparison과 splitAfter를 composition 객체로 묶었다. nested anyOf의 비교 분기는 comparison=true와 splitAfter의 null 타입만 허용한다. 독립 요청 분기는 comparison=false와 기존의 제한된 정확한 분리 문자열을 사용한다. 루트는 requests → composition → constraints 순서, 각 operation의 route 우선 순서도 유지한다.

비교일 때 composition 값은 `{"comparison":true,"splitAfter":null}`이다. API가 요구하는 루트 object와 nested anyOf 및 키 순서에 대한 [공식 OpenAI 문서](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses)를 확인했다.

라이브 진입점 ClassifierPlan.parseProvider는 정확한 새 필드만 받는다. 값은 고치지 않고 기존 내부 형태로 옮겨 기존 파서·ChatIntent·ChatPlan 검증을 실행한다. 잘못된 비교 분리 지점, 독립 복합 요청의 분리 누락, 중복/추가 필드, 원문 외 ID, 잘못된 참조, 권한 및 예산 검증을 완화하지 않는다. flat parse는 과거 합성 원문 재생을 위해 유지하며 라이브 제공자 경로에서 사용하지 않는다. 공개 HTTP 응답·DB·프런트엔드·설정에는 변경이 없다.

## 검증

- 진단기 변경 전후 순서·오류 기록 오프라인 76/76.
- 새 스키마·SDK·파서·복합 요청·H2 저장/소유권 관련 87/87.
- 최종 작업본 전체: 419개 발견,374개 통과,45개 조건부 건너뜀,실패/오류0. 유료 평가 종료 후 단독 실행한 `target/comparison-stabilization/final-full-offline.log` 기준이다.
- 수정 후 대표 **36/36**: `classifier-diagnostic-20261118125821876122600.json`.
- 수정 후 비교 3종 x3 **9/9**: `classifier-diagnostic-20261118130426051517800.json`. ID·제목·정중한 비교 표현을 기존 정답 및 라우터와 대조했다.
- 원본 전체 **58/61**, 비교 3개 모두 통과, AI_INVALID_RESPONSE 없음: `live-20261118130025138562500.json`. 아래 의미 불일치 3개 때문에 전체 품질 게이트는 여전히 실패다. 이번 수정이 전체 품질 해결을 뜻하지 않는다.

| 남은 사례 | 원본 정답 | 실제 결과 |
|---|---|---|
| rec-negative-opinion: 어제 본 영화는 별로였어. 다른 SF 영화 추천해줘 | RECOMMEND | CLARIFY |
| detail-missing: 없는 작품 XYZ의 줄거리 알려줘 | DETAIL 제목 `없는 작품 XYZ` | DETAIL 제목 `XYZ` |
| clarify-ranking: 두 작품 중 더 뛰어난 연출 | CLARIFY | UNSUPPORTED |

앞선 전체 평가도 58/61이었지만 실패 항목은 다르다. rec-negative-opinion은 이번에 새로 관찰된 불일치이며, 단일 표본만으로 스키마 변경 때문이라고 단정하지 않는다. 제목·우열 질문의 기존 불일치와 함께 그대로 남긴다. 원본 stability 63/63은 직전 세션의 결과이고 이번 수정 후 재실행하지 않았다.

수정 전 36회와 수정 후 106회, 총 **142회**의 고정 합성 제공자 호출을 실행했다. 보고된 입력/출력 토큰은 전체515541/15922, 수정 후386277/12308이며 과금 총액이 아니다. 모든 최종 보고서 해시는 `target/comparison-stabilization/summary.json`, 평가 중 제품 코드·원본 정답 해시 고정은 `acceptance-manifest.json`에 기록한다.

```powershell
# 수정 전/후 각각 12개 x3, synthetic SDK trace 포함
.\mvnw.cmd test '-Dtest=ClassifierDiagnosticLiveTest' '-DchatClassifierDiagnostics=true' '-DchatClassifierDiagnosticScope=representative'
# 수정 후 원본 전체 — 실제로는 위 진단기와 같은 Maven 실행에서 실행
.\mvnw.cmd test '-Dtest=ConversationQualityLiveTest' '-DchatQualityLive=true' '-DchatQualityLimit=61' '-DchatQualityRecordSyntheticPlans=true'
# 수정 후 비교 3개 x3
.\mvnw.cmd test '-Dtest=ClassifierDiagnosticLiveTest' '-DchatClassifierDiagnostics=true' '-DchatClassifierDiagnosticScope=comparison'
# 유료 호출 종료 후 작업본 전체
.\mvnw.cmd test
# Eclipse 실행본에서
.\mvnw.cmd test '-Dtest=com.example.recommend.chat.**.*Test'
```

실행 명령과 최초 실패 로그는 `target/comparison-stabilization`에 보관한다. 최초 점수를 정상 재실행으로 덮어쓰지 않는다. 모델 gpt-6-luna, 전체800/분류544,20초,재시도0을 유지한다. CHAT_EVAL 별칭은 하위 테스트 실행에만 전달하며 실제 사용자 DB나 대화는 사용하지 않는다.

## 동기화 및 남은 작업

Eclipse 실행본 지침·Git 상태·변경 전 해시를 확인했다. 실행본에 별도 AGENTS.md는 없었다. 덮어쓸 기존 파일은 `target/comparison-stabilization-sync-20261118`에 백업하고, 변경 소스·테스트·새 재현 자료 6개와 관련 문서 3개를 복사해 SHA-256 일치를 검증했다. 설정·비밀 값과 관계없는 파일은 복사하지 않았다. 실행 중인 앱은 재시작하지 않았다. 적용 시 Eclipse F5 후 백엔드를 중지/재실행해야 한다.

Eclipse 전체 챗봇 회귀는 324개 발견,314개 통과, 유료10개 건너뜀,실패/오류0이다. 실행본 로그는 `target/comparison-stabilization-offline-verification.log`다. 작업 경로를 잘못 지정한 작업본 예비 로그 `target/comparison-stabilization-runtime-check.log`는 실행본 검증 근거로 사용하지 않았고, 실제 실행본 경로에서 다시 검증했다. 유료 실행과 겹친 예비 작업본 결과 역시 최종 작업본 검증으로 사용하지 않는다.

간헐 503은 이번 비교 파싱 오류와 별개이며 AI_PROVIDER_FAILURE 이벤트가 아직 없다. DB/TMDB 확장·개인화 근거 추적·Phase7은 이번 작업 범위에 포함하지 않는다.

다음 단일 기능은 rec-negative-opinion 같은 부정적 감상 뒤의 명확한 새 추천 요청을 불필요한 확인 질문으로 바꾸는 문제의 진단·안정화다. 나머지 두 불일치도 별도 미해결 항목으로 유지하며, 정답이나 검증을 바꿔 점수를 올리지 않는다.
