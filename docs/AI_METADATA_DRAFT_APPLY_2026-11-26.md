# 3단계 — 미승인 메타데이터 실제 저장 경로

2026-11-26 KST. [저장 dry-run·격리 검증](AI_METADATA_STORAGE_DRY_RUN_2026-11-26.md)에 이어 **실제 DB 저장용 prepare/apply 경로와 검토 가능한 고정 실행 계획**을 구현했다. 이 세션은 경로 구현·격리 검증·서비스 DB 읽기 전용 계획 준비까지다. **서비스 DB apply는 실행하지 않았으며 실제 대상 6테이블은 아직 없다.** 의미 승인·시간 Content 반영·검색/추천 연결·작품 추가도 시작하지 않았다. 3단계 전체 완료가 아니다.

## 이번 실행 계획

작업본 계획: `target/metadata-apply-path-20261126/application-plan/plan.json`.

- 상태: PREPARED_NOT_APPLIED. 계획 해시: `30cf93b0fd0f0853adc4fe6e6d444efb6af51e10b2d1da6304c55fc8301b31c6`.
- 대상: 기존 `LocalCatalog`가 지원하는 고정 로컬 `localhost:3306/myproject`. DB 이름/서버 UUID/서버 버전도 계획에 고정한다. 임의 대상 URL/SQL/다른 배치는 CLI로 받지 않는다.
- 고정 Collection SHA: `9cb6bff327f7c73403bbbf66ac65c81fa6fea9dcc724d580e827e49e22818954`.
- 고정 Projection SHA: `15d49dbc6f90487ceac359fa4a82adb2d05bf5eacf076593f7d10ca7efd9391c`.
- 공개 카탈로그 468행·매핑 468행은 원래 수집 기준과 같고, 시간 등록 21개/미등록 447개를 유지한다. 새 시간 20개는 후보로만 저장한다.

| 만들 대상 | 저장 행 수 |
|---|---:|
| catalog_metadata_batches | 1 |
| catalog_metadata_sources | 84 |
| catalog_content_keywords | 413 |
| catalog_content_aliases | 334 |
| catalog_metadata_field_reviews | 196 |
| catalog_metadata_runtime_candidates | 20 |
| 합계 | **1,048** |

관계의 approved/active는 모두 0이다. 키워드 언어 UNKNOWN·중심성 UNJUDGED·별칭 국가/언어 불확실성을 유지한다. 기존 `contents`, `catalog_import_sources`, `catalog_runtime_enrichments`에는 INSERT/UPDATE/DELETE하지 않는다. 원문·출처 구조와 중복/빈값 정책은 이전 투영을 그대로 사용했다. 이전 `storage-plan.json`의 `application_apply_supported=false`는 plan-only 모듈의 표식이며, 새 실행 경로는 별도 `apply_metadata_drafts.py`다.

## 도구와 실행 경계

신규 파일은 `scripts/apply_metadata_drafts.py`, 오프라인/격리 MySQL 검사 2개와 이 문서다. 기존 dry-run 모듈·DDL·수집 계약·제품 Java/React는 변경하지 않았다.

```powershell
python -X utf8 scripts/apply_metadata_drafts.py prepare --collection target/metadata-enrichment-live/20261126T062616162Z-12846dde/collection.json --output <새 계획 폴더>
```

`prepare`는 DB 읽기만 한다. 원래 collection/review/공개·매핑 스냅샷 4개를 계획 폴더에 복사해 다시 검증하고, 사전 상태·기준 컬럼·저장 투영·미적용 migration/transaction SQL을 만든다. 계획에는 입력 4개·산출물 5개·핵심 코드 6개의 SHA256과 적용 전 상태/대상 식별 정보가 들어간다. 경로와 생성시각을 계획 해시에 섞지 않아 같은 DB에서 양쪽 체크아웃의 결과를 비교할 수 있다. 기존 출력은 덮어쓰지 않는다.

다음은 **아직 실행하지 않은 서비스 DB 저장 명령**이다. 준비된 계획의 범위는 위 6테이블/1,048행에 한정된다.

```powershell
python -X utf8 scripts/apply_metadata_drafts.py apply --plan target/metadata-apply-path-20261126/application-plan/plan.json --plan-sha256 30cf93b0fd0f0853adc4fe6e6d444efb6af51e10b2d1da6304c55fc8301b31c6 --output target/metadata-apply-path-20261126/application-apply-01
```

새 코드 변경이나 기준 DB 변경으로 계획이 무효화되면 기존 계획을 수정하지 않고 새 `prepare`부터 수행한다. 이후 요청에서 실제 저장을 진행할 때 이 고정 계획과 현재 상태를 먼저 재확인한다. 공급자 키/토큰을 입력할 필요가 없고 추가 TMDB/LLM 호출은 없다.

## 적용 시 검증과 실패 처리

1. 계획 해시·정책·핵심 코드·복사 입력·모든 산출물 해시를 확인하고 투영/SQL을 다시 생성해 대조한다. SQL 파일을 바꾸고 그 체크섬만 새로 적어도 거절한다. CLI는 잘못된 계획을 DB 어댑터 초기화 전에 거절한다.
2. 대상 UUID/DB/버전, 공개 8필드/매핑 3필드, 해당 11컬럼의 타입·nullable·collation·기본값·generated/extra와 기존 메타데이터/생성시각을 다시 읽는다. 임의 기준 변경·다른 배치 추가·부분 배치는 자동 채택하지 않는다.
3. 한 MySQL CLI 연결에서 `GET_LOCK('recommend.metadata_drafts.v1',5)`을 잡고 전체 DDL/DML 동안 유지한다. 이는 이 도구 실행끼리 협조하는 잠금이며 외부 SQL을 막는 권한 경계는 아니다. [MySQL named locks](https://dev.mysql.com/doc/refman/8.0/en/locking-functions.html)는 세션에 속하므로 트랜잭션 COMMIT과 별개다. 기존 CLI는 호출마다 새 연결이므로 DDL/DML을 여러 `run()`으로 나누지 않았다.
4. 6테이블이 전부 없을 때만 생성한다. 부분/비호환 테이블·원치 않은 unique/FK/트리거는 중단하며 DROP/ALTER/자동 수선은 하지 않는다. 일반 DDL의 [암시적 COMMIT](https://dev.mysql.com/doc/refman/8.0/en/implicit-commit.html) 때문에 데이터 롤백 뒤에도 빈 테이블 또는 일부 DDL이 남을 수 있다.
5. 데이터는 기존 검증된 단일 트랜잭션으로 저장한다. 카탈로그·매핑·6테이블을 고정 순서로 잠근 뒤 기준 값·기존 행·스키마·대상 컬럼을 다시 검사하고, COMMIT 전에 개수/내용/시간을 검사한다. 공개 값은 변경하지 않는다. 사전검사 뒤 값이 바뀌면 데이터 전체를 롤백한다.
6. 첫 쓰기 전에 공개 8필드·매핑 3필드·기존 6테이블과 환경의 좁은 JSON 사본을 저장하고 재읽기 해시를 확인한다. 이것은 전체 DB 백업/전체 복구 도구가 아니다. `STARTED_NOT_ACKNOWLEDGED` 영수증을 먼저 쓴 뒤 실행하며, SQL 오류/응답 유실/사후 검사 실패는 FAILED_OR_UNACKNOWLEDGED로 남긴다. 자동 재시도는 없다.
7. 완전 호환되는 빈 6테이블만 남았다면 같은 계획으로 명시적 재실행할 수 있다. 이미 같은 배치가 완전히 저장됐으면 새 저장 0건·생성시각 동일을 검증한다. 일부 DDL/배치·다른 데이터 변경은 사람이 상태를 조사해야 하며 같은 명령으로 수선하지 않는다. COMMIT 응답 유실과 실제 롤백을 영수증만 보고 혼동하지 않는다.

이 경로도 기존 MySQL CLI/정적 식별자/UTF-8 hex 리터럴을 사용한다. prepared statement 구현은 아니다. 기존 5,000행/테이블·SQL 10MB·잠금 대기 5초·SELECT 8초·CLI 프로세스 60초 한도를 유지한다. 전체 실행시간이나 다중 작업자 부하 성능을 보장한 것은 아니다.

## 검증 기록

오프라인: 신규 10검사+기존 저장 15검사=25개. 계획/입력/코드/산출물/SQL 변조, 대상 변경, 허용 상태 전이, 잠금 순서, 백업, 성공/불확실 영수증과 CLI 경계를 검사한다.

```powershell
# scripts 디렉터리에서
python -X utf8 -m unittest test_metadata_draft_apply test_metadata_storage_dry_run -v
```

격리 MySQL: 신규 합성 10검사+실제 수집본 1검사=11개. 실제 prepare/apply 함수를 **새 UUID 소유 스키마에서만** 실행한다. 초기 DDL/저장·같은 계획 재실행·저장 후 새 no-op 계획, 카탈로그/매핑/기준 스키마 독립 변경, 사전검사 뒤 변경 경합, 중간 데이터 실패와 전체 롤백, 일부 DDL 잔존 거절, 다른 배치 추가 거절, COMMIT 응답 유실/사후 검사 불가, 다른 연결의 잠금 보유를 검사한다. 모든 소유 임시 스키마는 삭제 후 부재를 확인한다.

```powershell
$env:METADATA_STAGE_MYSQL_TEST='true'
$env:METADATA_STAGE_PUBLIC_COLLECTION=(Resolve-Path target/metadata-enrichment-live/20261126T062616162Z-12846dde/collection.json).Path
python -X utf8 -m unittest discover -s scripts -p test_metadata_draft_apply_mysql.py -v
Remove-Item Env:METADATA_STAGE_MYSQL_TEST,Env:METADATA_STAGE_PUBLIC_COLLECTION
```

실제 수집본 임시 저장은 1,048행→같은 계획 재실행 0행, 시간 등록 21개/비활성 상태/타임스탬프 보존, 서비스 공개 상태 전후 일치·대상 테이블 부재를 확인했다. 합성 테스트의 TMDB_HTTP 표시는 테스트 내부용이며 실제 호출이 아니다. 새 TMDB/LLM 호출 0, 서비스 DB DDL/DML 0, Java/React/실제 모델 회귀 실행 0이다.

초기 오프라인 실행은 시스템 TEMP의 샌드박스 권한으로 새 10검사의 setup/cleanup에 오류 20개가 났다. 테스트 임시 경로를 프로젝트 target으로 제한해 25개 통과했다. MySQL 초기 실행은 10개 통과/잠금 검사 1개 실패였다. 8초 잠금이 계획 검증 중 풀리는 테스트 시간 조건을 25초로 조정했고, 카탈로그/매핑/컬럼 변경도 각 상태를 원복해 독립 검사하도록 보강했다. 초기 MySQL 로그와 수정 후 검사 로그를 함께 보존한다. 초기 오프라인 로그 사본은 상대경로 실수로 남기지 못했으며 실행 결과 요약은 `initial-test-notes.json`과 도구 실행기록에 보존했다.

작업본 오프라인25개와 수정 후 고유 MySQL11개, Eclipse 오프라인25개/MySQL11개 모두 검증을 완료했다. 양쪽 실제 계획 10산출물과 공개 리허설 verification이 바이트/내용까지 같다. 관련 **7파일 동기화·초기3문서 백업 해시 일치**, 기존 파일 작업본366/369·실행본360/363개 보존(기존3문서만 변경)을 확인했다. 최종 결과는 `target/metadata-apply-path-20261126/verification.json`에 기록했다. 서비스 적용은 여전히 미실행이다. Eclipse **F5만 필요**하며 백엔드/IDE 재시작은 필요 없다.

## 다음 단일 작업

준비한 계획을 재검증한 후 **고정 배치의 미승인·비활성 실제 저장과 영수증 확인**을 진행한다. 이는 Content 시간 갱신이나 검색 연결 승인이 아니다. 사람 독립 의미검토·한국어 라벨·중심성/외부 사실 확인·운영 복구·다중 작업자 부하·추천 품질 개선은 여전히 후속이다. 2단계 사람 0/2·의미 UNJUDGED·검증 24턴 미실행·사전 독립풀/반복/실환경·과거 오류 3/36, 승인 총 $3·예약 추정 $1.1838605·알려진 사용량 추정 $0.0767765·실청구 UNKNOWN을 유지한다.
