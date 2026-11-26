# 3단계 — 미승인 메타데이터 실제 저장 완료

2026-11-26 KST. 사용자 요청에 따라 고정 28개 수집 배치를 로컬 서비스 DB에 **미승인·비활성으로 실제 저장**했다. 6테이블/1,048행이며 Eclipse 실행본에서 같은 계획을 재실행해 추가 0행·생성시각 보존을 확인했다. **Content 값·기존 매핑/시간 출처·검색/추천/대화 기능은 변경하지 않았다.** 의미 승인과 제품 연결이 남아 있으므로 3단계 전체 완료나 품질 개선을 뜻하지 않는다.

## 실제 결과

| 저장 테이블 | 실제 행 수 |
|---|---:|
| catalog_metadata_batches | 1 |
| catalog_metadata_sources | 84 |
| catalog_content_keywords | 413 |
| catalog_content_aliases | 334 |
| catalog_metadata_field_reviews | 196 |
| catalog_metadata_runtime_candidates | 20 |
| 합계 | **1,048** |

- 최종 상태 STORED_UNREVIEWED, 사후 검사 MATCH. 관계의 approved=0/active=0, 키워드 언어 UNKNOWN/중심성 UNJUDGED를 유지했다.
- 실제 배치 생성시각: `2026-11-26T07:27:48.860929Z` (KST 16:27). 성공 CLI 8.70116초는 이번 단일 실행 관측값이며 성능 보장이 아니다.
- 기존 작품 468개·매핑 468개와 공개 8필드 전부 동일. 시간 등록 21개/미등록 447개를 유지했고 새 20개는 후보로만 저장했다.
- 기존 전체 공개 카탈로그(이미지·평점·조회수 등 기존 public snapshot 범위 포함)와 `catalog_runtime_enrichments`도 전후 동일했다. 보호 사본 해시: `c5bdc85b2cff5049254e44748f83288241b7ef3826bd2ac112d52469e47c50e0`.
- 작업본 성공 after와 Eclipse 재실행 before/after가 전체 메타데이터·타임스탬프까지 같다. 재실행 상태 ALREADY_STORED_UNREVIEWED/inserted=0.
- 새 TMDB/LLM 호출 0회, 작품 추가 0개, Content UPDATE 0건, 제품 Java/React 변경 0건. 공급자 토큰 입력은 필요하지 않았다.

## 최초 실패와 같은 요청 안에서의 복구

처음에는 [준비된 원래 계획](AI_METADATA_DRAFT_APPLY_2026-11-26.md)의 코드/입력/SQL과 현재 DB를 재검증해 일치를 확인했다. 최초 계획 해시는 `30cf93b0fd0f0853adc4fe6e6d444efb6af51e10b2d1da6304c55fc8301b31c6`이다.

첫 실제 apply는 FAILED_OR_UNACKNOWLEDGED로 종료했다. 자동 재시도 대신 읽기 검사를 수행했고 **6테이블은 생성됐지만 데이터는 모두 0행**, 카탈로그/매핑은 원래와 같음을 확인했다. DDL은 데이터 롤백과 별개라는 기존 정책대로 빈 테이블이 남았다.

원인은 JSON의 날짜 타입이었다. 서비스 `release_date`는 MySQL DATE인데 격리 fixture는 VARCHAR였다. SQL `JSON_OBJECT`의 DATE와 파일 JSON의 ISO 날짜 문자열은 출력 모양이 같아도 내부 타입이 다르다. 실제 공개 행의 읽기 전용 진단에서 JSON_TYPE=DATE, 원래 비교 false, CHAR 정규화 뒤 비교 true를 확인했다. 실제 DATE fixture로 바꾼 동일 정상 저장 검사가 수정 전 실패해 오류를 재현했다.

`metadata_storage_dry_run.py`에 공통 공개행 JSON 생성 함수를 추가해 읽기/트랜잭션 비교에서 `release_date`를 `CAST(... AS CHAR)`로 정규화했다. **DB의 날짜 값을 UPDATE한 것이 아니다.** MySQL fixture도 DATE로 수정했고, 공개 수집본 리허설이 서비스 메타데이터 테이블의 부재를 전제로 하지 않도록 바꿨다. 기존 서비스 자료 전후 보존 검사는 유지했다.

수정 후 격리 MySQL 13검사와 양쪽 오프라인 25검사를 통과하고 새 고정 계획을 생성했다. 대상·배치·투영·1,048행·미승인 범위는 같으며, 호환되는 빈 6테이블을 사용하므로 새 계획의 create_tables_if_absent는 []다. 실패 기록/원래 계획은 덮어쓰거나 삭제하지 않았다.

- 새 계획 해시: `0d541b1ff93afefbad2980ced503ff300cf80dda89876de720ee2b10dc020cdf`
- 고정 수집 해시: `9cb6bff327f7c73403bbbf66ac65c81fa6fea9dcc724d580e827e49e22818954`
- 고정 저장 투영 해시: `15d49dbc6f90487ceac359fa4a82adb2d05bf5eacf076593f7d10ca7efd9391c`

수정 코드로 원래 계획을 실행하면 코드 해시 검사에서 거절한다. 현재 사용 가능한 계획은 새 recovery-plan이다. 계획 파일의 PREPARED_NOT_APPLIED는 생성 당시의 불변 표식이며 실행 후 수정하지 않는다. 실제 저장 상태는 아래 result/verification 영수증으로 판단한다.

## 실행·증거 경로

작업본 `target/metadata-live-store-20261126`:

- `preflight.json`, `live-before.json`, `protected-before.json`: 첫 실행 전 읽기 검증/좁은 공개 사본.
- `application-apply-01`, `source-apply.log`, `failed-attempt-diagnosis.json`, `date-guard-diagnosis.json`: 최초 실패/DDL 6개·데이터 0행/날짜 원인.
- `date-regression-red.log`: 실제 DATE fixture로 수정 전 실패 재현.
- `source-date-mysql-tests.log`, `source-offline-tests.log`, `eclipse-offline-tests.log`: 수정 후 검사.
- `recovery-plan`: 새 고정 계획 10산출물. Eclipse의 같은 경로에 생성한 계획도 동일하다.
- `application-apply-02/{before,environment,backup-manifest,result,after}.json`, `execution.sql`, `source-apply-02-timing.json`: 실제 저장/사본/성공 영수증.
- `live-after.json`, `protected-after.json`, `live-verification.json`: 실제 행/상태·보호 자료 전후 일치 검사.

Eclipse 실행본 `target/metadata-live-store-20261126/application-replay-01`에는 같은 실제 DB의 재실행 영수증이 있다. 코드/문서 백업은 실행본 `target/metadata-live-store-sync-20261126`과 작업본 `target/metadata-live-store-20261126/before`에 보존한다. 좁은 JSON 사본은 전체 DB 백업/전체 운영 복구 도구가 아니다.

실제 성공 명령(이미 실행 완료):

```powershell
python -X utf8 scripts/apply_metadata_drafts.py apply --plan target/metadata-live-store-20261126/recovery-plan/plan.json --plan-sha256 0d541b1ff93afefbad2980ced503ff300cf80dda89876de720ee2b10dc020cdf --output target/metadata-live-store-20261126/application-apply-02
```

Eclipse에서는 같은 명령/해시에 새 출력 폴더 `application-replay-01`을 사용했다. 기존 출력은 덮어쓰지 않는다. 반복 실행이 필요하면 현재 상태를 먼저 확인해야 하며 부분/충돌 자료를 자동 수선하지 않는다.

## 검사와 동기화

- 수정 전 DATE 회귀 1검사: 예상한 오류 재현/임시 스키마 삭제 확인.
- 작업본 격리 MySQL 13검사 통과: 기존 저장 11개 전체와 새 적용 경로 정상 저장/재실행·실제 공개 수집본 2개. 실제 DATE 컬럼에서 출처/멱등성/롤백/경합/부분 상태 거절/SQL 문자열/삭제 cascade/응답 유실을 검증했다. 소유 임시 스키마는 삭제 후 부재를 확인했다.
- 작업본/Eclipse 각각 오프라인 25개 통과: 새 적용 도구 10+기존 저장 계획 15.
- 서비스 DB 실제 저장 성공 + Eclipse 실제 재실행 추가 0행 + 독립 읽기 사후 검사 PASS.
- Java/React/실제 모델 회귀는 실행하지 않았다. 기존 제품/질문/정답/프롬프트는 변경하지 않았다.

관련7파일을 선택 동기화하고 원래6파일을 먼저 백업했다. 소스/실행본 해시·새 계획10산출물 일치·기존 파일 작업본367/373/실행본361/367개 보존을 확인해 작업본 `target/metadata-live-store-20261126/verification.json`에 기록했다. Eclipse F5만 필요하며 백엔드/IDE 재시작은 필요 없다.

## 다음 단일 작업

저장된 고정 배치의 **필드별 검토·승인 계획**을 작성한다. 출처/시간 후보/한국어 라벨·키워드 중심성/별칭의 불확실성을 구분해 승인 가능한 범위와 보류 이유를 남기고, 미승인 원문을 자동으로 검색 근거로 활성화하지 않는다. Content 시간 반영·검색/순위/대화 변경·작품 추가는 새 범위로 별도 진행한다.

사람 독립 의미검토·외부 사실 대조·운영 복구·다중 작업자 부하·검색/품질 개선은 미검증이다. 2단계 사람 0/2·의미 UNJUDGED·별도 검증 24턴 미실행·사전 독립풀/반복/실환경·과거 오류 3/36, 승인 총 $3·누적 예약 추정 $1.1838605·알려진 사용량 추정 $0.0767765·실청구 UNKNOWN을 유지한다.
