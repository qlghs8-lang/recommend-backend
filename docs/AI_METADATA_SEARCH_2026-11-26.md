# 검토 메타데이터의 추천 후보 검색 연결 — 2026-11-26

검토된 키워드26개·공식/원제 별칭55개(총81관계)를 서버의 추천 후보 검색에 연결하고 실제 DB의 검색 이용을 활성화했다. 검색용 자료는28개 기존 작품에 한정된다. 시간 제안20개는 포함하지 않았다. 기존 비활성 후보/판정·원문과 Content는 그대로이며 별도의 append-only 활성화 이력1행만 추가했다. Eclipse에서 같은 계획을 명시적으로 재실행해 추가0행과 모든 행/타임스탬프 일치를 확인했다.

## 검색 동작과 제한

- `ReviewedMetadataSearch`는 SHA로 고정한 classpath 자료와 DB의 최신 활성화 이력을 확인한다. 없는 테이블·회수·손상된 JSON/해시/다른 payload는 이전 이벤트로 돌아가지 않고 기존 검색으로 폴백한다. SELECT는 최신1행·payload/event body 크기 제한과2초 query timeout을 사용한다. 활성화를 캐시하지 않아 다음 조회에서 회수가 반영된다.
- `CatalogRetriever`는 원문 영문/검토 한글 키워드와 전체 작품 별칭으로 후보·점수를 보강하고 기존12개 제한 **전에** SQL에 반영한다. 키워드 가중치는1,별칭6이다. 등록된ID라도 유형·제목·전체 줄거리가 검토 당시와 바이트 단위로 다르면 해당 검색 단서를 쓰지 않는다. HEX를 양쪽 SQL 값에 적용해 DB별 표현/콜레이션 차이를 피한다.
- 유형·장르·시간·기존 제외/참조ID·개인화 반응 제외와 명시적 TOPIC/SETTING 필터는 유지한다. 원제로 제외한 작품은 서버가 신뢰된 제외ID로 고정해 도구와 최종 검증에도 전달한다. 모호한 조건은 자료 조회/개인화 전에 확인한다.
- 키워드는 DISCOVERY_ONLY다. 모델에 새로운 키워드/별칭을 사실 근거로 전달하거나 TOPIC·줄거리 증명을 대체하지 않는다. 모델 출력ID와 공개 제목/줄거리 근거의 기존 검증을 유지한다. 임의 동의어 목록/질문별 분기나 새 작품은 추가하지 않았다.

이 변경은 RAG의 검색 부분을 보강한다. 더 찾은 후보도 실제 Content 정보로 답변 근거를 검증해야 한다. 고정28개에 한정된 자료이므로 자유로운 모든 주제에 답할 수 있다는 뜻이 아니다. 실제 모델 답변 품질 개선은 측정하지 않았다.

## 활성화·회수 경로

`scripts/metadata_search_activation.py`는 고정 부모/판정/후보 계획을 다시 검증해 검색 자료를 생성하고, prepare/apply로 검색 이용 상태만 전환한다. 모델 도구에는 활성화/쓰기 기능을 추가하지 않았다. 기존 원문의 approved/active와 검토 후보의 active=0 제약은 유지한다. 새 `catalog_metadata_search_events`에 이전 이벤트SHA,켜짐/꺼짐,고정 release/search SHA,payload,선언된 사유/시각/작업자 및 created_at을 기록한다. CODEX 선언은 인증된 운영자 신원이 아니다.

원래 자료·코드12파일·검색 resource·DB명/서버 UUID/버전/공개열 정의·기준 상태·생성 SQL을 고정한다. 원문 저장과 같은 advisory lock을 같은 연결에서 유지하며 공개 카탈로그/매핑·원문6·검토3·검색이력을 잠그고 재검증한다. 새 이력만 원자적으로 추가하고 백업 JSON/manifest·실행SQL·시작/최종 영수증·사후자료를 남긴다. DDL은 데이터 트랜잭션 밖이며 전체DB 백업은 아니다. 부분/손상 이력·기준 변경은 자동 수리/채택하지 않고 자동 재시도하지 않는다. 응답 유실은 FAILED_OR_UNACKNOWLEDGED 후 같은 계획의 명시적 재실행으로 확인한다.

회수는 현재 이력을 읽어 새 계획을 만든 후 동일 apply를 실행한다. 이전 enable 계획은 회수 이후 재채택하지 않으며 다시 켜려면 현재 상태를 기준으로 새 계획이 필요하다.

```powershell
python scripts/metadata_search_activation.py prepare --action disable --reason "Operator withdrawal" --output target/NEW-withdraw-plan
python scripts/metadata_search_activation.py apply --plan target/NEW-withdraw-plan/plan.json --plan-sha256 <새계획SHA> --output target/NEW-withdraw-attempt
```

공식/원제 관계55개는 고유 작품/별칭키49개다. 다른제목278개와 보류된 BEASTARS원제는 사용하지 않는다. 검토자·중심성은 기존 CODEX 선언 범위이며 독립 사람 검증/새 의미 정답으로 바꾸지 않는다. 원문 UNKNOWN/UNJUDGED는 그대로다.

## 실행한 검증

작업본/Eclipse 각각 Java70개(H2관련69+실제MySQL검색1),Python오프라인5개,소유MySQL시나리오3개 PASS다.

- 고정 키워드/전체 별칭 검색,12개 제한 전 순위,실제원문/시간값 유지.
- 비활성/회수/재활성화,잘못된 최신payload/JSON·없는 테이블의 기존 검색 폴백.
- 바뀐 유형·제목·줄거리/삭제ID의 단서 무효화,영문 경계·SQL문자 취급.
- 유형·시간·장르·신뢰된ID·원제제외·필수TOPIC·줄거리근거/개인화/추천도구/기존 모달링크 회귀.
- 실제468개 공개자료를 새 소유 MySQL에 복제한 Java/Hibernate 검사에서 검색·회수·변경 감지·필터·원제 제외를 확인하고 서비스 공개정보 전후 일치를 확인.
- 실제1,048원문/1,537검토자료를 각 새 소유 MySQL에 재생해 enable→withdraw→enable과0재실행,커밋 전 실패 롤백,응답 유실 후0재실행,사후카탈로그 변경/손상 이력 거부를 확인.

모든 쓰기 검사는 새 UUID 소유 스키마에 한정했으며 정확한 스키마 삭제/부재를 확인했다. Java실행초기 테스트 에이전트 연결이 멈춰 이번 검사 JVM만 정리한 뒤 실행 권한을 맞춰 재실행했다. 초기 HEX 표현·CHECK의 OCTET_LENGTH→LENGTH 정규화·별도테스트의 컬럼 이름 규칙·잘못된 검사 기대값/손상 이력 예외·조기확인 순서를 수정한 뒤 위 최종 검사를 통과했다. 새 실제TMDB/LLM호출0,브라우저/실제모델/실행중앱 HTTP 검증은 하지 않았다.

Java: mvnw.cmd -q -Dtest=ReviewedMetadataSearchTest,CatalogRetrieverTest,CatalogToolServiceTest,TopicRecommendationTest,ChatPersonalizationTest,CatalogRecommendationsTest,CatalogRationaleTest,ChatContentLinksTest,ReviewedMetadataMysqlTest -DchatReviewedMetadataMysql=true test. Python: PYTHONPATH=scripts로 test_metadata_search_activation 실행; METADATA_STAGE_MYSQL_TEST=true로 test_metadata_search_activation_mysql 실행.

## 실제 결과와 증거

| 항목 | 실제 확인 |
|---|---|
| 검색 이벤트 | enable1행, Eclipse 재실행 추가0 |
| 검색 이용 | 키워드26/별칭55,시간0 |
| 기존 원문/검토 | 1,048/1,537행 전체·타임스탬프 보존,기존 후보active0 |
| Content/매핑/기존 시간 | 468/468/21,미등록447 유지 |
| 전체 공개정보/기존 시간출처 | 전후 해시 일치 |
| Content 갱신/제공자 호출 | 0/0 |

- 계획SHA: `eb977a694c3d345ebac49c7d2c3d4f09eaaad868b6746fc00e445d88a5df625c`.
- 검색SHA: `438b0d950237932c6054677373c3346c31d92bd623f8790cc90d91a18bdfc889`.
- 이벤트SHA: `26c66915691d901ef0ddc43ad909692637df1179101a7d95a818562c80f3083f`.
- 공개정보/기존 시간출처SHA: `8a4c6297fc5a1758d3984339912e9405b1bcb36c83d7ac51d9d8fdb192b65087`.

증거는 target/metadata-search-20261126의 live-plan,live-apply,live-before/after,protected-before/after,live-verification,source/eclipse검사로그,java-mysql-verification,최종verification.json이다. Eclipse에는 같은 live-plan과 별도의 live-replay가 있다. 원본 판정/수집/비활성 릴리스 자료는 변경하지 않았다.

코드·테스트·검색자료·스키마10파일과 보고서/진행문서4파일을 선택 동기화하고 기존 Java2/문서3,총5원본을 양쪽 target에 백업했다. 계획5파일도 같은 해시로 복사했다. 보존 검사는 최초 scripts/docs/src manifest와 프런트 src55파일 범위다. 설정/비밀/무관 파일은 복사하지 않았다.

**Eclipse F5 후 백엔드를 중지·재실행해야 새 검색 코드와 resource가 로드된다. IDE 자체 재시작은 필요 없다.** 실행 중 앱은 이 작업에서 재시작하지 않았다. DB 검색 이용은 켜졌지만 기존 실행 프로세스의 새 코드 로드는 사용자 재실행 전까지 미확인이다.

3단계 전체는 진행 중이다. **다음 단일 기능은 시간 제안20개의 별도 검증과 기존 NULL 시간 보강 경로 검토**다. 현재 검색 연결에 시간20개를 적용하거나 새 작품/평가 정답을 추가하지 않았다. 독립 의미검토와 실제검색/모델 품질 비교는 후속 미완료다. 기존 사람0/2·의미UNJUDGED·별도검증24턴 미실행·과거오류3/36·승인총$3/누적예약$1.1838605/실청구UNKNOWN을 유지한다.
