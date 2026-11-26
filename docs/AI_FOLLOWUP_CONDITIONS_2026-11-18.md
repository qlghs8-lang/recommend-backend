# 후속 추천 조건 유지 — 2026-11-18

## 이번 범위

“그중 더 짧은 걸로 골라줘”에서 기존 사용자 조건을 유지하고 영화/회차 길이 순서와 공개 참조 범위만 추가하는 분류 안내를 보강한다. 기존 API, 파서·원문 출처 검증, 신뢰할 ID, SQL 조건, 소유권, 토큰/시간 한도와 재시도 0은 유지한다. DB/프런트엔드/설정은 변경하지 않는다. 간헐 503 사건은 재발 로그 대기 상태로 남긴다.

MOOD는 기분 전환·휴식·감동처럼 감정 목적, SITUATION은 출퇴근·부모님과 시청·주말 저녁 같은 시청 상황으로 구분한다. 두 의도가 별개로 명시되면 각각 보존한다. 기존 서버에서는 둘 다 주관적 선호로 다루므로, 이전 MOOD/SITUATION 불일치만으로 사실 조건 필터가 깨졌다고 해석하지 않는다.

## 수정 전 근거

기존 `stability-20261118094056831272200.json`의 audit-shorter는 1회 PASS, 1회 MOOD 대신 SITUATION, 1회 AI_INVALID_RESPONSE였다. 마지막 실패 원문은 없어 그 원인은 확정하지 못한다.

이번 고정 진단은 원래 audit-shorter, 신규 fresh-mood, commute-tv-shorter를 각 3회 실행했다. 총 9회 중 7회 PASS, 1회 상황 조건 누락, 1회 불완전 응답이다. 원본: `target/chat-quality/followup-20261118102752951648700.json`.

- audit-shorter는 이번 수정 전 3/3 통과했다. 따라서 과거 실패가 모든 실행에서 재현된다고 주장하지 않는다.
- commute-tv-shorter 반복 2는 나머지 조건은 맞지만 출퇴근 SITUATION을 누락했다. 완성된 원문 JSON을 회귀 테스트에 고정했다.
- 같은 사례 반복 3은 status=incomplete, reason=max_output_tokens, outputTokens=1024, outputChars=0이었다. 이번 실패에서는 분류 상한 도달을 확인했으나 과거 audit-shorter 실패의 원인까지 확정하는 근거는 아니다.

[공식 구조화 출력 안내](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses)에 따라 불완전 응답을 성공 JSON처럼 처리하지 않는다. [공식 평가 지침](https://developers.openai.com/api/docs/guides/evaluation-best-practices)에 맞춰 신규 범위와 기존 정답의 점수는 따로 기록한다.

## 구현·검사

- `OpenAiClient` 분류 안내: 변하지 않은 조건의 의미/종류 유지, 감정과 시청 상황 구분, 더 짧은 요청에 ORDER/ONLY_LAST 추가, 숫자 시간 한도와 assistant 진술을 임의 조건으로 만들지 않기, 일부 삭제와 전체 초기화 구분. 주관적 값과 검색어는 간결하게 작성하도록 안내한다. 추론 노력 옵션·출력 상한·모델은 바꾸지 않았다.
- `FollowupConditionsLiveTest`: 수정 전에 6개 사례와 3회 반복을 정의했다. 원래 감사 사례, 새 감정 요청, TV 출퇴근 후속, 숫자 제한 없는 짧은 요청, 감정 삭제/상황 추가, 전체 초기화를 검사한다. `diagnostic`은 첫 3개뿐이며 전체는 18회다. 합성 응답의 상태·불완전 사유·사용량·제한된 원문을 요청별로 보관한다. 실제 사용자 대화는 사용하지 않는다.
- `FollowupConditionsTest`: 고정 정답의 실제 라우터 실행, MOOD/SITUATION 교환, 임의 숫자 제한, 초기화 뒤 이전 조건 잔존을 검출한다. 관측한 상황 누락 JSON이 문법과 출처 검사를 통과하더라도 의미 검사에서 계속 실패하는지 확인한다. 기존 정답을 느슨하게 만들지 않는다.
- H2 서비스 회귀: 90분/60분 작품을 추천한 뒤 원래 유형·분위기·상한·공포 제외를 유지해 앞서 추천한 60분 작품만 선택한다. 더 짧아도 이전 목록 밖의 30분 작품은 제외하고 대화와 서버 참조 ID를 저장한다.

실제 모델과 최종 로컬/Eclipse 검증 결과는 이 문서의 후속 기록과 `AI_PROGRESS.md`에 남긴다. 원문 검증은 조건의 출처를 확인하며 자연어 의미 추출의 완전성을 증명하지 않는다. 안내를 바꿨다는 이유만으로 간헐적인 불완전 응답이 모두 해결됐다고 간주하지 않는다.

## 수정 후 기록

수정본 해시와 6개 사례를 고정한 후 신규 범위 18회를 한 번 실행했다. `target/chat-quality/followup-20261118103154079182.json`: 18/18 PASS. 원래 audit-shorter, 출퇴근 TV 후속, 숫자 제한 없는 후속, 상황으로 교체, 전체 초기화가 각각 3/3 통과했다. 관측된 최대 출력 사용량은 576으로, 수정 전 진단 최대 1024보다 작았으나 이 표본만으로 토큰 상한 도달 가능성이 사라졌다고 보장하지 않는다. 수정 전/후는 작은 비결정적 표본이므로 통계적 개선율로 해석하지 않는다.

- 작업본 최종 전체 `mvn test`: 417개 중 372개 통과, 45개 조건부 건너뜀, 실패/오류 0. 로그 `target/followup-conditions/full-offline.log`.
- Eclipse 전체 챗봇: 322개 중 312개 통과, 유료 10개 건너뜀, 실패/오류 0. 실행본 `target/followup-conditions-offline-verification.log`.
- 관련 초기 오프라인: 90/90 통과. 이후 관측 누락 JSON 회귀 1개를 추가했고 최종 전체 실행에 포함됐다.
- 수정 후 기존 안정성 세트 21개 x3: **63/63 PASS**. 원래 audit-shorter도 3/3, 출력 사용량은 각각 680/659/710이었다. 원본 `target/chat-quality/stability-20261118103555295329800.json`. 기존 정답과 채점 방식은 그대로 유지했다. 반복 표본에서 개선을 확인했지만 모든 자연어 입력에 대한 조건 완전성을 보장하지 않는다.
- 기능별 대표 baseline 12개: **11/12 PASS**, compare-ids는 AI_INVALID_RESPONSE(입력/출력 3612/189, 2703ms)였다. 원본 `target/chat-quality/live-20261118104054420768200.json`. 실패 원문은 이 실행기에 없어 추정으로 수정하지 않는다. 이전에도 compare-polite의 간헐 오류가 있었지만 같은 원인인지 확정할 수 없다. 새 안내와의 인과관계도 미확정이다. 이 점수는 이후 진단 성공 여부로 교체하지 않는다.
- 실제 H2 서비스 mood-and-change 3턴: **3/3 자동 검사 통과**. 기분 전환/90분 이하/공포 제외 추천 → 그중 더 짧은 선택 → 기존 조건을 버리고 액션으로 초기화했다. 실제 제공자 분류 원문에서도 MOOD와 기존 사실 조건, ONLY_LAST/SHORTER_MOVIE 유지 및 초기화 뒤 이전 조건 제거를 확인했다. 원본 `target/chat-quality/exploratory-20261118104137453745700.json`, 전체2048/분류1024/20초, 제공자 6회. 최초 추천이 한 작품이어서 후속에서도 같은 90분 작품을 안내했으며, 실제 두 작품 사이의 60분 선택은 별도의 H2 회귀 테스트로 확인했다.

위 수용 Maven 실행은 대표 baseline 실패로 exit 1이다. 안정성/서비스 테스트와 컴파일은 통과했다. **전체 품질 수용은 미통과**로 남긴다. 관련 비교 오류를 기존 고정 진단기로 한 번 조사하되 이번 작업에서 별도 비교 기능 수정까지 시작하지 않는다.

추가 비교 진단(기존 3개 x3)은 9/9 PASS였으며 오류가 재현되지 않았다. 원본 `target/chat-quality/classifier-diagnostic-20261118104322161602300.json`. 대표 11/12를 12/12로 교체하지 않고, 실패 원문이 없는 compare-ids의 원인은 미확정 상태로 유지한다. 비교 진단 로그는 `target/followup-conditions/comparison-diagnostic.log`다.

실행 명령:

```powershell
# 수정 전 고정 진단: 3개 x3
.\mvnw.cmd test '-Dtest=FollowupConditionsLiveTest' '-DchatFollowupLive=true' '-DchatFollowupScope=diagnostic'

# 수정 후 신규 범위: 6개 x3
.\mvnw.cmd test '-Dtest=FollowupConditionsLiveTest' '-DchatFollowupLive=true'

# 기존 안정성 63회 + 기능별 대표 12개 + 실제 서비스 3턴
.\mvnw.cmd test '-Dtest=ClassifierStabilityLiveTest,ConversationQualityLiveTest,ExploratoryConversationLiveTest' '-DchatStabilityLive=true' '-DchatQualityLive=true' '-DchatQualityLimit=12' '-DchatQualityRecordSyntheticPlans=true' '-DchatExploratoryLive=true' '-DchatExploratoryCases=mood-and-change' '-DchatExploratoryRepeats=1'
```

실제 모델은 기존 gpt-6-luna를 유지한다. 신규/안정성은 전체2048·분류1024, 대표 baseline은 전체800·분류544, 모두 20초/재시도0이다. 실제 서비스는 보고서에 바인딩된 예산을 별도로 확인한다. 프로세스의 키를 하위 CHAT_EVAL_* 별칭으로만 전달하며 키를 출력/저장하거나 실행 설정에서 읽지 않는다.

전체 61개 baseline은 이번 범위에서 재실행하지 않는다. 이전 58/61 점수와 비교의 간헐 오류·제목/지원 범위 대안은 그대로 남는다. 현재 간헐 503의 원인은 이 조건 안내 변경으로 설명되지 않으며, 새 안전 진단 줄이 필요하다.

전체 추가 제공자 요청은 117회(수정 전9, 수정 후 신규18, 안정성63, 대표12, 서비스6, 비교 진단9)이며 보고된 입력/출력 토큰은 421692/34043이다. 이 중 수정 후는 108회·390942/27746이다. SDK가 보고한 사용량 합계이며 과금 총액으로 해석하지 않는다. 최초 실패와 모든 실행 원본을 보존했다. `target/followup-conditions/summary.json`에 보고서 해시/상태/사용량을 보관한다. 제품·테스트·정답의 평가 전 해시는 `prompt-manifest.json`이며 검증 종료까지 동일함을 확인했다.

## 동기화와 다음 단일 작업

Eclipse 대상 지침·Git 상태·관련 차이를 확인했다. 소스/테스트 4개와 관련 문서 3개만 검사 당시 대상 해시를 재확인하여 복사하고 최종 SHA-256을 검증한다. 덮어쓴 파일은 실행본 `target/followup-conditions-sync-20261118`에 보관한다. 해시 기록은 작업본 `target/followup-conditions/eclipse-before.json`, `eclipse-after.json`, `final-manifest.json`이다. 설정·키·실행 설정 및 관계없는 변경은 보존한다. 앱 재시작은 수행하지 않았다. **Eclipse F5 후 백엔드를 중지·재실행**해야 변경된 분류 안내를 로드한다.

다음 단일 작업은 재발한 compare-ids의 실패 원문을 합성 진단에서 확보하고 비교 계획을 안정화하는 것이다. 기존 원문/식별자/소유권 검증과 원래 정답을 유지하며, 실패 원문 없이 파서를 완화하지 않는다. 간헐 503은 재발 시 AI_PROVIDER_FAILURE 한 줄로 별도 조사한다. 개인화 근거 보관, TMDB 보강/DB 확장, Phase7은 이번에 시작하지 않는다.
