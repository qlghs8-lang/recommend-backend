# 확인 사유 누락의 진단·안정화

범위는 제목 경계 확인 사유가 빠지는 경로 한 가지다. 현재 기능의 기본 자동 평가를 마무리하고 DB 확장·스트리밍을 별도로 진행한다. 공개 API/DB/프런트엔드·운영 한도·모델은 변경하지 않는다.

## 관측과 변경 기준

이전 통합 평가에서 “없는 작품 XYZ의 줄거리 알려줘”에 정상 completed JSON의 단일 CLARIFY만 반환됐다. 수정 정책60/61의 유일한 실패였으며 실제 저장 대화의 같은 질문2회는 전용 TITLE_BOUNDARY로 통과했다. 기존 원본 v1 59/61·수정60/61·저장 대화28/28은 별도 역사적 결과로 보존한다.

기존 단일 요청 스키마의 일반 route 분기에는 CLARIFY가 포함되고,별도의 route/reason/source 분기에도 CLARIFY가 포함된다. 따라서 route를 CLARIFY로 정한 뒤 사유 없이 닫는 형식이 스키마상 유효하다. 기록된 실패도 이 형식이었다. 이 구조가 의미 판단 누락의 유일한 원인이라고 단정하지 않는다.

새 provider 스키마에서는 일반 route 분기에서 CLARIFY를 제외하고 하나의 CLARIFY 분기에서 reason과 source를 필수로 결정하도록 한다. 전용 확인은 기존3개 사유와 최신 원문 근거,일반 확인은 명시적인 null/null이다. 둘 중 하나만 null인 쌍은 서버에서 거절한다. null/null을 내부 일반 CLARIFY로 정규화하므로 기존 응답·저장 계약은 유지한다.

기존 합성 기록·내부 호출의 route만 있는 CLARIFY 파싱은 호환성을 위해 유지한다. 최신 provider 스키마에서는 생성할 수 없다. 서버가 질문의 특정 단어만 보고 누락된 의미 사유를 추측하거나,누락 사유를 자동 성공 처리하지 않는다. 명시적 null을 잘못 선택할 가능성은 남아 있으므로 의미 평가를 계속한다.

OpenAI [구조화 출력 공식 문서](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses)의 required 필드·nullable 값·중첩 anyOf·출력 키 순서 규칙을 확인했다. 지원되는 형태를 지키더라도 업무 의미 규칙은 별도로 검증해야 한다. route 우선 순서,복합 요청 스키마,현재 원문·길이 검증은 유지한다.

## 사전 고정한 검사

`clarification-reason-v1.json`9문항×3회를 변경 전후 동일하게 평가한다. 모호한 원본 제목을 단독/인사 뒤/다른 작품 상세 뒤에 물으며,인용된 전체 제목·이전 문맥 없는 일반 확인·숨겨진 목록 참조·시간단위·우열·일반 설명을 대조한다. 이 자료와 실제 실행기는 제품 변경 전에 고정하고,새 자료를 원본 정답에 덮어쓰지 않는다.

변경 후에는 CLARIFY 원시 출력의 reason/source 필드 존재도 검사한다. 의미 기준은 변경 전후 동일하다. 일반 확인의 null/null 정규화,혼합 null·잘못된 자료형·추가 필드·원문에 없는 근거 거절,legacy 형식 호환,복합 요청에서의 확인 사유 거절을 오프라인으로 검사한다. 전체61문항은 원본과 수정 정책을 함께 채점하고 실제 저장 대화28턴도 다시 확인한다.

```powershell
.\mvnw.cmd test '-Dtest=ClarificationReasonTest'
.\mvnw.cmd test '-Dtest=ClarificationReasonLiveTest' '-DchatReasonLive=true' '-DchatReasonStage=before'
.\mvnw.cmd test '-Dtest=ClarificationReasonLiveTest' '-DchatReasonLive=true' '-DchatReasonStage=after' '-DchatReasonRequireWireFields=true'
.\mvnw.cmd test '-Dtest=ConversationAcceptanceLiveTest' '-DchatAcceptanceLive=true'
.\mvnw.cmd test '-Dtest=IntegratedConversationAcceptanceTest#actualModelTraversesTheSameStoredConversationsTwice' '-DchatAcceptanceServicesLive=true'
.\mvnw.cmd test
```

고정 합성 데이터만 사용한다. 유료 호출은 CHAT_EVAL_* 별칭을 자식 셸에서만 설정·제거하며 실제 사용자 DB·대화·비밀을 기록하지 않는다. 전체800/분류544·20초·SDK 재시도0을 유지한다. 이번 계획은 전후27+27,전체61,실제 대화 최대36으로 **provider 최대151요청**이다. 429/503/504면 해당 실행의 남은 호출을 중단한다. 실패에 맞춰 정답·상한을 바꾸거나 통과할 때까지 반복하지 않는다.

## 실행 결과

변경 전 고정9문항×3회는 **27/27 통과**해 이번 반복에서 과거 누락이 재현되지 않았다. `target/chat-quality/clarification-reason-before-20261124055129999499700.json`,SHA256 `afe8a7a03ea0164495186d84947ee07293f54163ffbd49913a4c11ac373188c8`. 보고 입력130230/출력2746토큰이다.

변경 후 같은 자료는 **27/27 통과**했다. CLARIFY21개 중 전용 사유15개·명시 null/null6개였고 누락/혼합 쌍은 없었다. `target/chat-quality/clarification-reason-after-20261124055618759380900.json`,SHA256 `7d2b2681af77cca233b876442d7789790c48db04ab2996906a02dbc6cd5e28d2`. 보고 입력132174/출력2596토큰이다. 모두 기존 gpt-6-luna·800/544·20초·재시도0이다.

전후 정확도 개선이나 단일 근본 원인을 이27/27→27/27 결과로 입증하지 않는다. 입증된 변화는 route만으로 CLARIFY를 종료하는 provider 형식이 사라진 점이다. null 선택의 의미적 정확성은 여전히 별도 수용 기준이다.

관련 오프라인37개 중36통과/유료1조건부 건너뜀. Eclipse 챗봇396개 중378통과/유료18건너뜀,실패·오류0이다. 전체61문항의 새 실제 측정은 **수정 정책61/61 통과**,원본 v1 **59/61**이다. 원본의 rec-tv/detail-missing은 의도한 정책 변경과 다르므로 원본 성공으로 바꾸지 않는다. 이전 수정60/61 실패·같은 질문의 정상 일반 CLARIFY 출력은 그대로 보존한다. 이번 측정의 detail-missing은 TITLE_BOUNDARY를 포함했다.

보고서 `target/chat-quality/acceptance-baseline-20261124055840221133400.json`,SHA256 `36433efbc017e7494db1250916513d86adae55bcf2a862198b1c7911857bd49c`. 보고 입력297655/출력6890토큰이다. 분류와 mock 하위 서비스의 라우터 계약 점수이며 실제 SQL·저장 전체 점수는 아니다. 한 번의 성공으로 항상 올바른 사유를 고른다는 보장은 하지 않는다.

실제 모델+H2 저장 대화는 **28/28턴 통과**했다. 분류28+추천 생성2로 provider30응답 모두 completed였고,추가 함수 호출은 없었다. 이번 점수를 도구 실행의 새로운 실제 수용 점수로 사용하지 않는다. 보고 입력145012/출력4147토큰이다. 보고서 `target/chat-quality/acceptance-services-live-20261124060353047626700.json`,SHA256 `749340057c322d7a3bb51ce929f2dfed2c05fbb329f301537c37bd4c59142997`.

최종 작업본 `./mvnw.cmd test`:491개 중438통과/53조건부 건너뜀,실패·오류0. Eclipse `./mvnw.cmd test '-Dtest=com.example.recommend.chat.**.*Test'`:396개 중378통과/유료18건너뜀,실패·오류0. 실행 로그는 작업본 `target/clarification-reason/full-offline.log`와 실행본 `target/clarification-reason-offline-verification.log`다.

이번 고정 합성 provider 요청145회,보고 사용량 합계 입력705071/출력16379토큰이다. 과금 총액은 아니다. 고정9개 진단 자료 SHA256은 `153396f2476837a27e1f34639f13b7e22d9d3e0b01ffb990af70963803f3716a`. 변경 전 실행기/자료2개,변경 후 수용 파일6개,기존 자료11개 해시는 유지했다. 제품40파일 중 예정된2개만 변경했고 나머지38개는 유지했다.

기본 세 정책의 이번 자동 수용은 통과했다. MySQL·HTTP/브라우저·실제 트래픽 수용이나 모든 자연어 표현의 정확성을 입증하지는 않는다. 503 사고도 해결된 것으로 표시하지 않는다.

## 남은 범위

이번 작업을 마친 뒤 현재 기본 기능의 품질 마무리는 **약2~3개 작업 묶음**으로 예상한다. 남은 묶음은 개인화 후속 질문 수용 확대,MySQL·HTTP/화면 수용,간헐503 확인이다. 503은 안전한 이벤트가 있어야 원인을 좁힐 수 있고 추가 문제에 따라 작업이 늘어날 수 있다. 이는 일정이나 완료 보장이 아니다.

다음 단일 기능은 **개인화 추천의 후속 근거 대화 수용 검증**이다. 현재 개인화 추천/이유 설명 경로를 먼저 확인하고 실제 저장 대화에서 공개 카탈로그 근거·서버 취향 근거·반응 변경·소유권·기록 비노출을 검사할 좁은 범위를 고정한다. MySQL 수동 수용과 DB 확장을 동시에 시작하지 않는다.

TMDB/DB 보강은 별도 A(검증된 시간 데이터)~D(국가별 등급·OTT 갱신)4단계다. 스트리밍·취소·운영 보강과 선택적 벡터 검색도 별도 로드맵이다. 자동61문항의 통과율을 제품 전체의 완료율로 환산하지 않는다. 이번 단일 기능 뒤 다른 기능을 자동 시작하지 않는다.

## Eclipse 동기화

최초 지침·차이·해시를 확인하고 소스/테스트/자료6개와 문서4개만 동기화했다. 덮어쓰는6개는 실행본 `target/clarification-reason-sync-20261124`에 원본 SHA256대로 백업하고 최종10개 파일 일치를 검증한다. 관련 없는 변경·설정·비밀은 복사하지 않는다. 근거는 `target/clarification-reason/{eclipse-before,eclipse-after-tests,eclipse-after-docs,final-manifest}.json`과 각 검사 로그다.

제품 변경이므로 Eclipse F5 후 백엔드 중지·재실행이 필요하다. 앱은 이 작업에서 재시작하지 않았으며 Eclipse 자체 재시작은 필요 없다.
