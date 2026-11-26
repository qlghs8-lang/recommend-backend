# 복합 추천의 영화·시리즈 조건 보존 — 2026-11-18

## 이번 한 단계

이전 수용 검사에서 복합 요청 12회 중 3회에 명시된 영화 조건이 빠졌다. 추천 유형을 생략 가능한 constraints 배열에서 꺼내, 제공자용 RECOMMEND/PERSONALIZED 작업마다 필수 `media`와 `mediaSource`로 출력하도록 변경한다. 서버가 검증 후 기존 MEDIA 조건으로 변환하므로 공개 API·ChatIntent·DB·검색 서비스의 계약은 유지한다.

- `media`: ANY/MOVIE/TV 중 하나. 미지정이면 ANY이며 `mediaSource`는 null이어야 한다.
- MOVIE/TV는 1~100자의 실제 원문 근거가 있어야 한다. 근거는 해당 추천 작업의 원문 범위 또는 앞선 USER 메시지에 존재해야 한다. 인접 목록/상세 작업에만 있거나 assistant에만 있는 근거는 거절한다.
- 일반 조건 배열에서 MEDIA는 금지한다. 유형을 두 곳에서 출력하거나 충돌시키지 않는다. 전체 조건 상한 8개는 유지하고 유형도 한 조건으로 센다.
- 목록 작업의 media와 추천 작업의 media는 각각 처리한다. 영화 좋아요 목록 + 시리즈 추천은 목록 MOVIE, 추천 TV로 분리한다. 추천이 유형을 지정하지 않았다면 인접 영화 목록의 유형을 자동 상속하지 않는다.
- 추천 작업의 슬롯 누락, null 유형, 근거 없는 값, ANY+근거, 중복 MEDIA는 API 제공자 스키마와 서버 파서에서 거절한다. 파서가 모델이 ANY를 잘못 선택한 모든 의미 오류까지 판단한다는 뜻은 아니다.
- PERSONALIZED에서 ‘새 영화/new movies for me’만으로 NEWER를 추가하지 않고 이미 저장 반응이 없는 작품이라는 의미로 안내한다. 명시적인 최신 공개순 요청은 NEWER로 처리한다. 한국어 키워드로 경로를 강제하는 라우터나 추가 모델 재시도는 도입하지 않는다.
- 기존 원문 분할·소유권·조건 출처·카탈로그 ID 검증과 턴 예산은 유지한다. 모델/전체 출력/시간 제한을 변경하지 않는다.

[OpenAI 공식 구조화 출력 지침](https://developers.openai.com/api/docs/guides/structured-outputs)의 필수 필드/중첩 anyOf/additionalProperties=false 규칙을 적용한다. 구조적 누락 거절과 실제 의미 추출 정확도를 별도로 검증한다.

## 실행 전 고정한 수용 검사

1. 오프라인: 이전 누락 출력 거절, MOVIE/TV 정규화, 근거 위조/assistant 근거/타 작업 유형 차용/중복 조건 거절, 영화 목록+TV 추천의 실제 H2 검색·저장·비공개 문맥 격리. 전체 회귀 검사.
2. 기존 61개 v1: 정답 그대로 한 번, 전체 800/분류 544/20초. 이번에는 `chatQualityRecordSyntheticPlans=true`로 고정 합성 계획도 기록해 최초 불일치의 원인을 남긴다.
3. 기존 안정성 v1: 21개 × 3회(63회), 전체 2048/분류 1024/20초. 기존 정답과 SHA-256을 변경하지 않는다.
4. 신규 유형 범위: CompoundMediaLiveTest의 영화/TV·역순·영화 목록+TV 추천·유형 미지정·미반응 작품·명시적 최신순 여덟 사례 × 3회(24회). 계획과 목록 유형까지 검사한다. 추가 세트로 원래 61/21개의 결과를 소급 변경하지 않는다.
5. 실제 서비스: 기존 compound 시나리오 1턴을 새 합성 사용자/대화방에서 3회. 두 작업 보존·저장·추천 부분의 유용성을 직접 확인한다.

모델 gpt-6-luna, 재시도 0, 순차 호출/턴 사이 1초. 총 요청 상한 61+63+24+(3턴×5회)=163회다. 실패한 최초 결과를 유지하고 성공할 때까지 재실행하지 않는다. 429/503/504는 해당 평가의 후속 호출을 중단한다. 운영 계정·MySQL은 조회하지 않으며 키를 출력·저장하지 않는다.

## 결과

유형 보존에 대한 고정 검사는 통과했다. 전체 챗봇 품질 게이트는 아직 실패이며 아래에 범위를 구분한다.


## 초안 회귀와 수정 이력

첫 초안은 로컬 검사는 통과했지만 실제 안정성 세트에서 0/63(7 불일치, 56 AI_INVALID_RESPONSE)로 실패했다. 결과 `target/chat-quality/stability-20261118092527460808700.json`와 초기 `frozen-manifest.json`, `rejected-*.java`를 유지한다. 후속 CompoundMediaLiveTest 도중 배치를 중단해 기존 61개/서비스 검사는 그 초안으로 실행하지 않았다. 중단된 단계의 부분 요청 수·사용량은 확정할 수 없다.

고정 진단 4회에서 정상 DETAIL 대신 EXPLAIN/GREETING 등을 생성하는 출력을 확인했다. 코드의 Map.of 속성 반복 순서가 JVM마다 달라질 수 있었고, 분기별로 서로 다른 첫 필드를 갖고 있었다. 공식 문서상 구조화 출력은 스키마 키 순서대로 생성된다. 따라서 경로 결정 전에 특정 분기의 다른 필드를 출력하도록 하는 순서가 분기 선택에 영향을 주었을 가능성이 있다. 이를 최초 모든 실패의 확정 원인이라고 단정하지 않는다.

수정본은 루트 requests → comparison → splitAfter → constraints, 모든 작업 분기의 route 우선, 조건의 kind 우선 순서를 명시적으로 고정한다. 전송 JSON의 순서와 필수 유형 필드를 검사하는 로컬 테스트 47개가 통과했다. 새 JVM에서 고정 진단 4회도 올바른 상세/참조/비교/복합 계획을 생성했다. 수정본은 `ordered-manifest.json`으로 별도 고정해 원래 계획한 평가를 다시 실행하며, 초안 실패 점수와 구분한다. 정답·모델·전체 예산을 바꿔 통과시킨 것이 아니다.


### 수정본 중간 관측

기존 61개 v1은 58/61(2 의미 불일치, 1 AI_INVALID_RESPONSE)로 전체 게이트는 실패다. compare-polite가 잘못된 응답으로 끝났고, detail-missing은 XYZ만 제목으로 추출했으며, clarify-ranking은 UNSUPPORTED를 선택했다. 뒤 두 사례는 과거 감사에서도 지적한 정답 경계의 대안 해석일 수 있으므로 원래 점수를 유지하며 구분한다. 최초 비교 오류의 원문 제공자 출력은 없어 상세 원인을 확정하지 않는다.

실제 compound 서비스는 세 반복 모두 LIST_BOOKMARK/PERSONALIZED, MEDIA MOVIE 조건을 보존했고, 같은 세 실제 H2 후보를 개인화 점수에 따라 출력했다. 모두 저장됐고 추천 부분이 비어 있지 않았다. NEWER를 추가하지 않았다. 이는 합성 카탈로그와 해당 세 반복에서의 관측이며 일반 질문 전체의 정확도를 뜻하지 않는다.


## 최종 수정본 결과

| 검사 | 결과 | 의미 |
|---|---:|---|
| 기존 61개 | **58/61 (95.08%)** | 2 의미 불일치, 1 잘못된 응답. 전체 100% 게이트 실패 |
| 기존 안정성 21개 × 3 | **61/63 (96.83%)** | 반복별 21/21, 20/21, 20/21. 조건/변경 48회와 복합 12회 모두 통과 |
| 새 유형 범위 8개 × 3 | **24/24** | 반복별 8/8. 목록/추천의 다른 유형, ANY, 역순, 미반응 작품, 명시적 NEWER를 모두 검사 |
| 실제 복합 서비스 | **3/3** | 세 번 모두 MOVIE 조건, 두 작업 순서, 실제 추천 3편, 저장, 비공개 문맥 격리 확인 |
| 전체 오프라인 | **348 통과 / 43 건너뜀 / 실패·오류 0** | 391개. 외부 통합 35·유료 opt-in 8개 건너뜀 |
| Eclipse 최종 채팅 회귀 | **288 통과 / 8 건너뜀 / 실패·오류 0** | 296개, 순서 고정 수정까지 포함 |

신규 24개에는 정확히 다음 범위를 고정했다: 영화 추천/시리즈 추천, 영화 좋아요 목록+시리즈 취향 추천, 시리즈 찜 목록+영화 취향 추천의 역순, 영화 목록 옆 유형 미지정 추천, 최신 공개순 영화, 아직 반응하지 않은 영화, 최신 공개순 시리즈. 상세 질문이나 목록의 유형을 추천으로 잘못 옮기거나 ANY를 강제로 영화로 바꾸지 않았는지 검사했다. 실제 서비스는 영화 복합 요청만 세 번 검증했으며 TV/ANY 전체 서비스의 실제 모델 검증을 했다고 확대 해석하지 않는다. TV 검색/저장은 별도 오프라인 H2 회귀에서 확인했다.

| 최종 실행 | 중앙값 | p95 | 최대 |
|---|---:|---:|---:|
| 기존 61개 분류/모의 라우터 | 2.037초 | 5.083초 | 5.774초 |
| 안정성 63개 | 3.339초 | 6.790초 | 12.495초 |
| 새 유형 범위 24개 | 2.871초 | 7.260초 | 10.546초 |
| 실제 복합 서비스 3턴 | 3.302초 | 4.236초 | 4.236초 |

범위와 예산이 다르므로 하나의 정확도/지연으로 합치지 않는다. 최종 수정본의 요청은 **151회**, 보고된 입력/출력은 **512,468/28,201토큰**이다. 이 숫자에는 거절한 초안의 63개 시도, 중단된 유형 평가의 알 수 없는 부분 요청, 두 번의 고정 진단(각 4회)이 포함되지 않는다. 잘못된 응답 중 사용량을 알 수 없는 항목도 있어 청구 총량이 아니다. 최종 수정본만의 성적과 전체 작업 비용을 혼동하지 않는다.

남은 안정성 실패는 모두 audit-shorter다. 두 번째 반복은 MOOD 대신 같은 취지의 SITUATION으로 추출하여 고정 정답과 달랐고, 세 번째는 AI_INVALID_RESPONSE였다. 최초 출력이 없어 잘림·필드 위반 등의 원인은 확정하지 않는다. 정답을 바꿔 통과 처리하지 않았다. 새 유형 범위 및 기존 복합 12회에서는 이번 목표인 MEDIA 누락이나 원문 변조가 관찰되지 않았다. 임의의 질문에서 ANY를 잘못 선택하는 모든 의미 오류까지 막았다는 보장은 아니다.

## 재현·증거와 다음 작업

```powershell
# 최종 수정본의 기존 61개 및 실제 복합 서비스 3회
.\mvnw.cmd test '-Dtest=ConversationQualityLiveTest,ExploratoryConversationLiveTest' '-DchatQualityLive=true' '-DchatQualityLimit=61' '-DchatQualityRecordSyntheticPlans=true' '-DchatExploratoryLive=true' '-DchatExploratoryCases=compound' '-DchatExploratoryRepeats=3'

# 최종 수정본의 고정 반복 63회 + 신규 범위 24회
.\mvnw.cmd test '-Dtest=ClassifierStabilityLiveTest,CompoundMediaLiveTest' '-DchatStabilityLive=true' '-DchatCompoundMediaLive=true'

# 외부 호출 없는 전체 회귀
.\mvnw.cmd test
```

최종 유료 Maven 실행은 원래의 엄격한 품질 게이트가 실패해 exit 1이다. 신규 유형 범위 테스트와 실제 복합 서비스 테스트는 통과했다. 컴파일 또는 오프라인 회귀 실패와 구분한다. 모델/키는 기존 평가 프로세스의 CHAT_EVAL_* 별칭만 사용했고 키를 문서·로그·명령 인자에 기록하지 않았다.

원본/해시/판정은 `target/compound-media/summary.json`, 소스 고정은 `ordered-manifest.json`이다. 원래 두 정답 JSON의 SHA-256은 유지했다. 주요 원본 파일은 아래와 같으며 초안 결과도 삭제·덮어쓰지 않았다.

- `target\chat-quality\live-20261118093625526908200.json` · SHA-256 `40e785b426e66ae1fbb2ff58c9a88ab3c804efa11164c47bf52d1dba25f710f1`.
- `target\chat-quality\stability-20261118094056831272200.json` · SHA-256 `50bd39e4d68e98fc633e5c43377f728d8756eb0949b836cee7a602a5d7b1e896`.
- `target\chat-quality\compound-media-20261118094556171713600.json` · SHA-256 `50552272c903768bf0db44b67cf2741df7403d21eefb692f1e62718318da528a`.
- `target\chat-quality\exploratory-20261118094004486627400.json` · SHA-256 `5513975176c645b1fca327e2f16c5b360a108d5fe9117612fef6749500d301dd`.

로그: `target/compound-media/ordered-baseline-service.log`, `ordered-stability-media.log`, `final-full-offline.log`, `ordered-schema-offline.log`. 초안과 진단 로그도 같은 디렉터리에 남아 있다. Eclipse: `target/compound-media-ordered-offline-verification.log`.

다음 단일 작업은 **비교 요청 compare-polite의 AI_INVALID_RESPONSE 원인을 합성 출력으로 고정하고 계획 검증을 안정화**하는 것이다. audit-shorter의 나머지 오류, 개인화 후속 이유의 서버 근거 보관, TMDB 데이터 보강과 Phase 7은 별도 작업으로 남긴다.

Eclipse 실행본에는 변경 소스·테스트 9개와 관련 문서만 기존 해시 재검사·백업·복사 후 SHA-256을 확인했다. 백업: 실행 프로젝트 `target/compound-media-sync-20261118-182612`(수정 전 파일 및 rejected-draft 하위의 초안). 설정·다른 사용자 변경은 유지했다. 앱 재시작은 하지 않았다. 적용하려면 Eclipse F5 후 백엔드를 중지·재실행한다.
