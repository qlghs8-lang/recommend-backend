# 공개 인용 스키마 문자 안전성 보강

> 최신 보강: [큰따옴표 경계 처리와 원문 검증](AI_QUOTE_LITERAL_SAFETY_2026-11-25.md). D09 실제 진단 이후 U+0022와 보수적 U+005C 경계를 추가했다. 아래의 따옴표/역슬래시 보존 기대와123검사 결과는 이전 LF 보강 시점의 이력이며, 현 제품은 새129회귀 기준을 사용한다. 원문 치환 없이 연속 구간을 선택한다. 수정본 실제 수용은 미확인이다.


2026-11-25 KST. [D10 원인확정](AI_SCHEMA_D10_DIAGNOSIS_2026-11-25.md)에 이어, 제품의 공통 인용 선택 경로를 수정했다. **줄바꿈을 공백으로 바꾸지 않고, 원문에서 제어문자를 넘지 않는 연속 구간을 선택한다.** 실제 원문·ID·필수조건 검증과 기존 응답 형식은 유지한다. 특정 작품/질문 분기나 검색·프롬프트·DB 변경은 없다.

## 변경 동작과 범위

기존에는 TOPIC 단서부터 최대 40 UTF-16 글자를 취하거나 공개 필드를 40자 단위로 나누면서 LF를 enum에 넣을 수 있었다. 예를 들어 원문 `동료들과 모험한다.\n\n다시 만난다.`에서 이제 `동료들과 모험한다.`를 선택한다. `동료들과 모험한다.  다시 만난다.`처럼 공백으로 합친 문장은 원문 부분문자열이 아니므로 계속 거절된다.

- `OpenAiClient.topicQuotes`: 단서 시작부터 안전한 원문 구간을 고른다. 단서 전체가 구간 안에 있어야 하며, 첫 일치가 부적합하면 같은 단서의 다음 일치를 확인한다. 단서가 줄거리 뒤쪽에 있어도 기존처럼 검색한다. 필드별 최대 4개다.
- `OpenAiClient.publicQuotes`: LF/CR/탭 등 ISO 제어문자, Unicode 줄/문단 구분자, 잘못된 UTF-16 대리문자를 경계로 나눈다. 경계 뒤 구간도 검사하되 제목/장르는 기존 40자 범위에서 최대 1개, 줄거리는 기존 앞 400 UTF-16 글자에서 최대 11개다.
- 공통 `publicQuoteEnd`: 정상 이모지 대리쌍을 보존하며 40 UTF-16 글자를 넘지 않는다. 원문을 정규화/치환하지 않는다. 따옴표·역슬래시 등 일반 문자는 그대로 둔다.
- 안전한 근거 선택지가 없는 필드/작품은 해당 스키마 대안을 만들지 않는다. 모든 작품에 대안이 없으면 `recommendations.maxItems=0`이다. 근거를 꾸미거나 조건을 완화하지 않는다.

이전 실제 공급자 진단이 직접 확인한 제한은 LF다. 다른 제어문자/줄 구분자/손상된 대리문자 처리는 보수적인 로컬 설계이며 모든 문자가 공급자에서 실제 거절됐다는 주장은 아니다. [공식 Structured Outputs 문서](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses)는 지원 스키마 부분집합과 enum/문자열 크기 한도를 설명한다. 문서 자체를 LF 제한의 직접 근거로 삼지 않는다.

기존 `CatalogRationale.validate`의 필드별 원문 부분문자열 검사, `parseRecommendations`의 후보 ID·중복·근거 파싱, `CatalogRecommendations`의 최신 DB 값/필수조건/TOPIC 근거 검증은 수정하지 않았다. 인증·소유권·비공개 반응·공유 출력/시간 예산·재시도 정책도 그대로다. 줄바꿈 뒤의 단서가 사라지는 방식이나 특정 작품을 제외하는 목록을 추가하지 않았다.

## 실행한 검증

새 `RecommendationQuoteSchemaTest` 7개는 공개 동결 D10 인용, LF/CRLF/탭/NUL/VT/DEL/NEL/줄·문단 구분자, 제목/장르/줄거리, 줄바꿈 뒤 TOPIC 단서, 정상/잘못된 대리문자와 40/400자 경계, 따옴표·역슬래시, 48개 후보의 선택지/enum 한도, 안전한 근거 부재를 검사한다. 새 HTTP 모의 공급자 검사는 안전한 원문 수용과 공백 치환/다른 작품 인용 거절을 확인한다. 기존 평가 질문·정답·실패 결과는 바꾸지 않았다.

```powershell
.\mvnw.cmd -o '-Dtest=RecommendationQuoteSchemaTest,OpenAiClientTest' test
.\mvnw.cmd -o '-Dtest=RecommendationQuoteSchemaTest,OpenAiClientTest,CatalogRationaleTest,TopicRecommendationTest,GroundedConversationTest,RecommendationCriteriaTest,CatalogRecommendationsTest,CatalogToolServiceTest' test
```

첫 제한 환경 실행은 새 순수 스키마 검사 7개 통과, HTTP 모의 공급자 40개는 `Unable to establish loopback connection`으로 setup 오류였다. 제품 동작 실패와 구분하고 `focused.log`를 보존했다. localhost 연결이 가능한 권한으로 두 번째 범위 명령을 실행해 **123개 전부 통과(BUILD SUCCESS)**했다. H2 합성 카탈로그/대화와 모의 공급자만 사용했고 실제 제공자·운영 DB·TMDB 호출은 0이다. 전체 Java/React 회귀와 실제 모델의 수정 스키마 수용·추천 적합성은 이번에 측정하지 않았다.

증거: `target/quote-schema-safety-20261125/{focused,source-regression}.log`, `before-hashes.json`, `before/`, `OpenAiClient.diff`, `eclipse-before/eclipse-after/verification.json`. 이전 파일럿과 네 요청 진단 원본은 해당 원래 디렉터리에 유지한다. Eclipse 동기화/검사 결과는 [최신 인계](AI_PROGRESS.md)를 따른다.

## 남은 일과 다음 단일 기능

이번 구현/회귀 작업은 완료했지만 **합의한 2단계 전체는 진행 중**이다. 원래 파일럿 D10 실패와 D11/D12 `NOT_RUN`을 변경하거나 성공 결과로 덮어쓰지 않았다. 이전의 검색 구절 불일치·과도한 UNVERIFIABLE·가족 조건 누락도 이번 변경으로 해결했다고 판단하지 않는다.

다음 단일 기능은 **수정 후 제한 실제모델 파일럿 재측정과 실패 단계 비교**다. 기존 gpt-6-luna·승인 총 $1의 누적 사용 추정과 남은 한도를 먼저 확인하고, 원래 개발 입력을 유지한 별도 결과로 기록해야 한다. 평가 키는 필요한 시점에 로컬 숨김 입력만 사용한다. 별도 검증 질문을 튜닝용으로 사용하지 않고, 3단계 수집/검색 개선/다음 기능은 새 요청 전 시작하지 않는다.

Eclipse 추가지침/기존대상 차이0 확인 후 관련7파일만 선택동기화·원본/대상 해시일치 확인. 덮어쓴5파일은 실행본 `target/quote-schema-safety-sync-20261125/`에 초기백업·해시보존했다. 실행본에서 위 관련8클래스도 **123/123통과(BUILD SUCCESS)**했다. 무관한설정/비밀파일 복사0. 작업본/실행본로그와 동기화·보존 검증은 `target/quote-schema-safety-20261125/`에 있다. 기존248파일중 의도한 제품1/테스트1/문서3 외243파일과 고정질문/원본파일럿/원본진단 해시를 보존했다. 제품 Java가 바뀌었으므로 **F5 후 실행 중인 백엔드를 중지·재실행**해야 반영된다. Eclipse 자체 재시작은 보통 필요 없다. 사용자 앱은 이 작업에서 자동 재시작하지 않는다.
