# 3단계 — 미승인 메타데이터 저장 계획·격리 검증

2026-11-26 KST. [실제 제한수집 결과](AI_METADATA_COLLECTION_2026-11-26.md)를 입력으로 저장 계획을 만들고, 새 UUID 소유 임시 MySQL 스키마에서만 저장을 검증했다. **서비스 DB DDL/DML·작품 추가·Content 갱신·검색/순위/대화 변경은 0건**이다. 3단계 전체 완료나 추천 품질 개선을 뜻하지 않는다.

## 범위와 실행 경계

- `scripts/metadata_storage_dry_run.py`의 CLI는 `plan`만 제공한다. 기본은 오프라인이며 `--live`도 현재 공개 카탈로그·매핑·대상 테이블의 읽기 검증만 한다. `apply` 명령은 없다.
- 실제 쓰기 함수는 `METADATA_STAGE_MYSQL_TEST=true`로 새로 생성한 `recommend_metadata_stage_test_<UUID12>` 스키마의 `OwnedScratch` 어댑터만 받는다. 이미 존재하는 스키마는 소유하지 않으며 정확한 소유 이름만 삭제하고 부재를 확인한다. 애플리케이션 어댑터는 쓰기 함수에서 거절한다.
- `docs/sql/catalog_metadata_drafts_v1.sql`은 **미적용 참고 DDL**이다. 서비스 DB의 실제 적용 경로·승인/활성화 경로·마이그레이션은 아직 없다. Java 엔티티/API와 React는 변경하지 않았다.
- 새로운 TMDB/LLM 호출은 0회다. 기존 84회 수집을 재사용했으며 합성 테스트·읽기 재생과 합산하지 않는다. 개인 대화·회원 자료·검증질문/정답은 읽지 않았다.

메타데이터는 나중에 검색 후보를 찾는 단서로 사용할 수 있지만, 저장만으로 RAG(검색한 실제 작품을 답변 근거로 제공하는 방식)가 개선되지는 않는다. 이번에는 검색에 연결하지 않고 공급자 원문과 해석/승인 상태를 분리했다.

## 고정 입력과 저장 계획

입력은 `target/metadata-enrichment-live/20261126T062616162Z-12846dde`의 수집본·원래 미승인 review·공개/매핑 스냅샷이다.

- Collection SHA256: `9cb6bff327f7c73403bbbf66ac65c81fa6fea9dcc724d580e827e49e22818954`
- 저장 projection SHA256: `15d49dbc6f90487ceac359fa4a82adb2d05bf5eacf076593f7d10ca7efd9391c`
- 원래 제안 계약, COMPLETE/TMDB_HTTP, 전후 MATCH, 28개 ID/유형/84개 endpoint, 수집 해시, 기준 카탈로그·매핑, 원래 review의 재계산 결과를 모두 검사한다. review 파일을 승인 상태로 바꾸면 거절한다.

| 제안 테이블 | 행 수 | 용도 |
|---|---:|---|
| catalog_metadata_batches | 1 | 수집·계약·스냅샷·projection 해시와 정책/개수 |
| catalog_metadata_sources | 84 | detail/keywords/alternative_titles별 출처 |
| catalog_content_keywords | 413 | 공급자 keyword ID와 원문 관계 |
| catalog_content_aliases | 334 | 다른 제목 278 + 공식 제목 28 + 원제 28 |
| catalog_metadata_field_reviews | 196 | 필드별 원문 상태와 검토 보류/후보 판정 |
| catalog_metadata_runtime_candidates | 20 | 기존 NULL에 대한 시간 후보만 저장 |
| 합계 | **1,048** | 모두 미승인·비활성 |

출처에는 고정 TMDB ID/유형, endpoint, 요청 locale, UTC 수집시각, 응답 본문 해시, 기준 공개행 해시와 **허용 필드로 투영한 공급자 데이터**를 보존한다. 전체 HTTP 본문을 저장한 것으로 주장하지 않는다. 필드 리뷰·키워드·별칭·시간 후보는 해당 source 행을 참조한다. 공급자 문자열은 신뢰하지 않는 데이터로 취급한다.

키워드의 같은 출처/ID 중복은 1관계로 저장하되 원래 순서를 남긴다. 키워드 언어는 UNKNOWN, 중심성은 UNJUDGED이며 번역 라벨은 없다. 별칭은 정규화 제목+국가 상태+제목 유형 상태로 묶고 모든 원형/순서를 보존한다. 다른 제목 279행 중 정규화 중복 1개가 있어 278관계이며, 공식/원제도 자동 승인하지 않는다. 국가 KR을 한국어로 간주하지 않는다. 다른 작품의 같은 별칭은 병합하거나 전역 unique로 제한하지 않는다.

기존 시간 21개와 미등록 447개를 그대로 유지한다. 파일에는 새 시간 후보 20개가 있지만 `contents`와 기존 `catalog_runtime_enrichments`에는 쓰지 않는다. 기존 동일 시간 3개·TV 빈 배열 5개도 필드 리뷰에 남긴다. 누락/NULL/빈 배열은 서로 다른 관측 상태다.

## 검증·트랜잭션 정책

1. 공개 8필드 전체 468행과 매핑 3필드 전체 468행을 기준 해시/내용으로 비교한다. 텍스트 대소문자·시간·매핑·삭제의 차이를 자동 채택하지 않는다.
2. 대상 6테이블은 전부 없거나 검사한 구조여야 한다. 부분 테이블·컬럼/유형/길이·정렬/엔진·unique/FK·트리거 불일치는 거절하며 자동 수선하지 않는다. 이는 미래의 임의 스키마 변경까지 보장하는 범용 마이그레이션 검사기는 아니다.
3. 좁은 공개행·매핑·기존 메타데이터와 타임스탬프의 JSON 사본을 저장하고 재읽기 체크섬을 확인한다. 전체 DB 백업이나 실제 운영 복구 절차를 대체하지 않는다.
4. DDL은 데이터 트랜잭션 밖에서 처리한다. MySQL의 일반 CREATE TABLE은 [암시적 COMMIT](https://dev.mysql.com/doc/refman/8.0/en/implicit-commit.html)을 일으키므로, 실패 시 빈 테이블까지 롤백된다고 주장하지 않는다.
5. 데이터는 단일 트랜잭션에서 고정 순서로 공개행·매핑·6테이블을 `FOR UPDATE`로 잠근 뒤, 기준/기존 행/스키마를 다시 검사한다. 첫 일반 일관 읽기 전에 잠금을 잡아 사전 검사 뒤의 변경을 거절한다. [locking reads](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html), [consistent reads](https://dev.mysql.com/doc/refman/8.0/en/innodb-consistent-read.html)의 차이를 반영했다.
6. 행 키는 정책/배치/테이블/내용으로 결정한다. COMMIT 전 전체 개수와 기존 행·새 행·JSON·시간을 확인한다. 중간 SQL 오류는 연결 종료에 따라 데이터 전체를 롤백한다. 같은 배치의 완전 일치는 추가 저장 0건이며 생성시각도 보존한다. 부분/충돌 배치는 고치거나 덮어쓰지 않는다.
7. 성공 응답을 잃으면 FAILED_OR_UNACKNOWLEDGED로 기록하고 자동 재시도하지 않는다. 명시적인 재실행에서 DB의 완전 일치를 확인해 중복을 막는다. FK의 content 삭제 cascade는 기존 삭제 흐름을 막지 않도록 설계했고 임시 DB에서 확인했다.

신규 드라이버를 추가하지 않고 기존 `LocalCatalog`/MySQL CLI와 `sql_value()`를 재사용했다. 식별자는 정적 허용 목록이며 문자열은 UTF-8 hex 리터럴로 인코딩한다. **드라이버 prepared statement를 사용한 구현은 아니다.** 한글·일본어·이모지·따옴표·역슬래시·SQL처럼 보이는 문자열의 실제 왕복과 테이블 보존을 검사했다. 입력은 기존 수집 상한과 5,000행/테이블, 트랜잭션 SQL 10MB, 잠금 대기 5초, SELECT 8초, 기존 CLI 프로세스 60초 제한을 받는다.

## 실행 결과와 재현

```powershell
python -X utf8 scripts/metadata_storage_dry_run.py plan --collection target/metadata-enrichment-live/20261126T062616162Z-12846dde/collection.json --collection-sha256 9cb6bff327f7c73403bbbf66ac65c81fa6fea9dcc724d580e827e49e22818954 --output <새 폴더>
# --live를 추가해도 읽기 검증만 수행한다.
```

작업본의 offline-plan/live-plan은 projection/참고 DDL이 같고 live 상태는 PENDING_DRAFT_STORAGE였다. 서비스 DB에 대상 6테이블이 없고 현재 공개 카탈로그/매핑이 원래 기준과 일치함을 확인했다. 계획 파일의 존재는 실제 저장 영수증이 아니다.

`scripts`에서 다음 오프라인 검사를 실행한다.

```powershell
python -X utf8 -m unittest test_metadata_storage_dry_run test_metadata_enrichment_dry_run test_metadata_enrichment_audit test_runtime_detail_dry_run -v
```

작업본/Eclipse 각각 새 저장 검사 15개와 기존 관련 검사 69개, 총 **84개 통과**. 새 검사는 출처/행 수/중복/빈값/고정 해시/리뷰 변조/기존 시간/멱등성/부분 충돌/기준 변동/SQL 데이터 처리/CLI 쓰기 경계를 다룬다. MySQL 통합 검사는 명시적 opt-in 후 실행한다.

```powershell
$env:METADATA_STAGE_MYSQL_TEST='true'
$env:METADATA_STAGE_PUBLIC_COLLECTION=(Resolve-Path target/metadata-enrichment-live/20261126T062616162Z-12846dde/collection.json).Path
python -X utf8 -m unittest discover -s scripts -p test_metadata_storage_mysql.py -v
Remove-Item Env:METADATA_STAGE_MYSQL_TEST,Env:METADATA_STAGE_PUBLIC_COLLECTION
```

작업본/Eclipse 각각 합성 fixture 11검사와 실제 수집본 1검사, 총 **12개 통과**. 작업본은 11개와 공개 1개를 나눠 실행했고 Eclipse는 12개를 함께 실행했다. 합성 자료의 TMDB_HTTP 표시는 테스트 내부용이며 실제 API 호출 증거가 아니다. 정상 저장/재실행, 레거시·조회수 보존, 사전검사 뒤 카탈로그/매핑 변경 거절, 중간 실패 전체 롤백과 빈 DDL 잔존, 부분 배치/부분 스키마/트리거/원치 않은 unique 거절, SQL 문자열 왕복, 삭제 cascade, COMMIT 응답 유실 후 명시적 재실행을 검사한다. 변경 경합 검사는 사전검사와 실제 트랜잭션 사이에 별도 연결로 변경을 커밋한 경우이며, 다중 작업자 부하/교착 스트레스 시험은 아니다.

실제 공개 수집본도 **임시 스키마에서만** 1,048행 저장→재실행 0행·타임스탬프 일치가 통과했다. 시간 등록 수 21개 그대로, 후보 20개 미승인, 서비스 공개 상태 전후 동일·대상 테이블 부재·임시 스키마 삭제 확인을 기록했다. 작업본 로그/계획/영수증은 `target/metadata-storage-dry-run-20261126`에 있다. Java/React/실제 모델 회귀는 변경 범위상 실행하지 않았다.

## Eclipse 동기화와 남은 작업

관련 소스·검사·참고 DDL·문서 **8파일을 선택 동기화**했고 덮어쓴 기존 3문서는 실행본 `target/metadata-storage-dry-run-sync-20261126`에 먼저 보존했다. 실행본에서도 동일 84+12검사와 읽기 전용 실제 계획을 통과했다. 작업본 offline/live 및 Eclipse live의 저장 projection/참고 DDL은 바이트 해시까지 같고 양쪽 공개 리허설 verification도 같다. 기존 파일 작업본 361/364·실행본 355/358개를 보존했고 기존 3문서만 변경했다. 최종 해시·보존 개수·로그는 작업본 `target/metadata-storage-dry-run-20261126/verification.json`에 기록했다. 실행본의 로컬 설정은 복사하지 않았다. Eclipse F5만 필요하며 백엔드/IDE 재시작은 필요 없다.

**다음 단일 기능:** 이번 검증된 계획을 바탕으로 고정 배치의 **미승인·비활성 저장에 한정한 실제 DB 적용 경로와 검토 가능한 실행 계획**을 구현한다. 서비스 DB 적용 자체는 해당 새 작업의 범위/통제를 확인해 진행한다. 의미 승인·시간 Content 반영·검색 연결·작품 추가는 각각 별도이며 자동으로 시작하지 않는다.

사람 독립 의미검토·한국어 라벨·중심성·외부 사실 대조·실제 DB 적용/운영 복구·장애/동시성 부하·검색/품질 개선은 미검증이다. 2단계의 사람 0/2, 의미 UNJUDGED, 별도 검증 24턴 미실행, 사전 독립풀/반복/실환경 미완료와 과거 오류 3/36을 유지한다. 누적 승인 총 $3·예약 추정 $1.1838605·알려진 사용량 추정 $0.0767765·실청구 UNKNOWN은 변하지 않는다.
