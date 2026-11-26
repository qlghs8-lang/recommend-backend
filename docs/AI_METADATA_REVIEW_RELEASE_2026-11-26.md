# 3단계 — 버전별 검토 결과·비활성 릴리스 dry-run

2026-11-26 KST. 원문과 분리한 검토 버전/릴리스 후보 경로를 구현하고 오프라인·격리 MySQL에서 검증했다. **서비스 DB 승인/활성화, Content 시간 반영, 검색 연결은 하지 않았다.** 기존 미승인 원문과 CHECK 제약은 변경하지 않는다. 3단계 전체는 계속 진행 중이다.

## 동작과 입력 계약

신규 `scripts/metadata_review_release.py`의 CLI는 `template`, `plan` 두 명령만 제공한다. DB 접속 옵션이나 application apply 명령은 없다. 실제 고정 수집본과 원래 review를 기존 검증기로 재생하고 고정 collection/projection/필드 검토 계획 SHA를 대조한다. 검토 계획의 모든 원본 행 해시도 재확인한다.

- 고정 collection: `9cb6bff327f7c73403bbbf66ac65c81fa6fea9dcc724d580e827e49e22818954`
- 고정 projection: `15d49dbc6f90487ceac359fa4a82adb2d05bf5eacf076593f7d10ca7efd9391c`
- 필드 검토 계획: `88811fc8632bae1fa15c336335db37a578908ec7be01e62eb5fceef33e9cf35a`

검토 대상은 키워드413 + 별칭334 + 시간후보20 = **767관계**다. 원문 출처/일반 필드 기록을 관계 승인 대상으로 가장할 수 없다. 이전 한국어 라벨26개 제안을 자동 복사/승인하지 않는다.

`review-template.json`은 각 관계의 row SHA·원본 값 SHA와 `UNREVIEWED`, 빈 value/rationale, reviewer null을 제공한다. 실제 작업 시 같은 row SHA를 [필드 검토 계획](evaluation/metadata-field-review-plan-v1.json)에서 찾아 원문·작품·보류 이유를 확인한다. 검토자는 명시적인 기록만 작성한다. 누락된 관계는 미검토로 남는다.

검토 파일 최상위 필드:

```json
{
  "policy": "metadata-review-decisions-v1",
  "review_plan_sha256": "88811fc8632bae1fa15c336335db37a578908ec7be01e62eb5fceef33e9cf35a",
  "revision": "review-001",
  "parent_version_sha": null,
  "records": []
}
```

각 record의 필드는 `row_sha`, `source_value_sha256`, `decision`, `reviewer`, `rationale`, `value`로 제한한다. decision은 UNREVIEWED/APPROVE/HOLD/REJECT다. UNREVIEWED를 제외하면 검토자 kind(CODEX/HUMAN)·id·UTC 시각과 이유가 필요하다. HOLD/REJECT는 빈 value를 요구한다. 중복 행·없는 행·변경된 원본·알 수 없는 필드는 실패한다. 문자열/관계 수/JSON 파일 크기를 제한하고 오류 본문에 입력/SQL/접속 정보를 출력하지 않는다.

**검토자 정보는 파일 작성자가 선언한 메타데이터이며 신원 인증이 아니다.** 산출물에 DECLARED_NOT_AUTHENTICATED를 명시한다. HUMAN 표기가 사람 독립 검토를 증명하지 않으며 코드가 이름/시각/판정을 만들어 주지 않는다. 인용 존재 검사는 의미 정확성 검사를 대신하지 않는다. 실제 배포 승인·신뢰할 검토자 인증은 이 도구 범위 밖이다.

## 릴리스 후보의 선택 기준

| 관계 | 명시적 검토 필드 | 후보 포함 조건 / 제외 기준 |
|---|---|---|
| 키워드 | label_ko, label_version, category, centrality, overview_quote | 한국어 라벨·버전과 현재 고정 원문에 존재하는 인용을 요구한다. TOPIC/SETTING/FORM + CENTRAL/SUPPORTING만 후보 발견용 DISCOVERY_ONLY로 내보낸다. MOOD/OTHER, INCIDENTAL/UNSUPPORTED/UNJUDGED는 제외한다. 중심 소재의 진실이나 감상 안전성을 인증하지 않는다. |
| 별칭 | language, scope, source_form | 원문에 실제 있는 form과 명시한 언어·WHOLE_WORK 범위를 요구한다. 시즌·판본·가제·오타 provider 표기, 다른 범위, 정규화 제목의 다중 작품 충돌은 제외한다. 국가를 언어로 간주하지 않는다. UNKNOWN/und 등의 언어 미확정 값은 승인 입력으로 받지 않는다. |
| 시간 | minutes, scope, expected_existing | 원문 후보와 정확히 같은 정수 분·고정 null 기준만 허용한다. 영화는 MOVIE_TOTAL, TV는 REPRESENTATIVE_EPISODE로 구분한다. SERIES_TOTAL/추정값/기존 값 덮어쓰기를 허용하지 않는다. 실제 현재 null과 출처는 이후 적용 시 별도 확인해야 한다. |

미검토·보류·거절·범위 보류 행은 후보에 포함하지 않는다. 일부 관계만 유효하면 그 선택 행만 후보에 들어가며 누락/제외 수를 함께 기록한다. 선택0은 BLOCKED_NO_ELIGIBLE_RECORDS이고 release DB 행도 생성하지 않는다. 선택이 있어도 INACTIVE_CANDIDATE이며 active=false, application_apply_supported=false, content_updates=[]다.

검토 버전은 batch/projection/검토계획·정렬된 기록·이전 버전·revision·6개 코드 파일 해시를 묶어 content hash로 식별한다. revision은 사람이 읽는 이름이며 식별자는 version SHA다. 순서만 바뀐 동일 기록은 같은 버전이 된다. 릴리스 후보는 선택 행·버전·사용 범위에 별도 해시를 갖는다.

## 원문과 분리된 저장 설계와 격리 경로

신규 [SQL 참조](sql/catalog_metadata_review_release_v1.sql)는 **미적용**이다:

- catalog_metadata_review_versions: 버전/부모 버전/전체 검토 manifest
- catalog_metadata_review_records: 버전별 원본 row SHA와 명시적 검토 기록
- catalog_metadata_release_candidates: 비활성 후보 manifest, UNIQUE(version), CHECK(active=0)

원래 draft의 approved=0/active=0 제약을 제거하거나 플래그를 바꾸지 않는다. 현재 CLI는 이 스키마를 실제 DB에 설치하지 않는다.

`store_owned_scratch`는 테스트 전용이며 정확한 OwnedScratch 타입·소유 상태·새 UUID 스키마 이름을 요구한다. application adapter/기존 임의 스키마를 받지 않는다. 새 테스트 스키마에서만 DDL을 만들며 DDL과 데이터 트랜잭션은 별도다. 부분 스키마는 자동 수선하지 않는다.

저장 시 기존 raw 저장 검증기의 catalog/매핑/원문 전체 잠금·행 일치 guard를 재사용하고 새 검토 이력도 잠근 뒤 비교한다. 기존 버전 self-hash·원본/기록 연결·릴리스 완전성을 검사해 변조/부분 상태를 거절한다. INSERT로 새 버전만 추가하고 기존 버전/타임스탬프를 보존한다. 중간 실패는 데이터 전체 롤백, 동일 버전 재실행은 추가0이다. 커밋 응답 유실은 자동 재시도하지 않고 동일 버전을 명시적으로 재실행해야 한다.

테스트 전용 함수에는 운영용 영수증/백업·대상 환경 고정·승인자 인증·릴리스 활성 포인터·운영 복구 기능이 없다. 따라서 실제 DB 적용 도구로 사용하면 안 된다. DDL reference나 파일 hash 역시 외부 권한자의 변조를 막는 서명/접근 통제는 아니다.

## 실행한 검사와 실제 배치 결과

작업본과 Eclipse 실행본에서 각각:

```powershell
$env:PYTHONPATH='scripts'
python -X utf8 -m unittest test_metadata_review_release test_metadata_storage_dry_run test_metadata_draft_apply -v
$env:METADATA_STAGE_MYSQL_TEST='true'
# 실제 공개 수집본 경로는 각 checkout의 recovery-plan/input/collection.json
$env:METADATA_REVIEW_PUBLIC_COLLECTION='<checkout>/target/metadata-live-store-20261126/recovery-plan/input/collection.json'
python -X utf8 -m unittest discover -s scripts -p test_metadata_review_release_mysql.py -v
```

- **오프라인 각40개 통과**: 신규15 + 기존 관련25. 자동 승인 차단, 중복/변경 출처·인용/종류/필드/검토자 형식 거절, 소재/별칭/시간 범위, 결정 순서 멱등성, 이전 버전 변조, 안전한 CLI 오류와 출력 덮어쓰기 금지를 검증했다.
- **격리 MySQL 각10개 통과**: 합성9 + 실제 고정 공개 배치1. 세 관계 비활성 후보·동일 버전 추가0, 부모/새 버전과 기존 타임스탬프 보존, 미검토 시 release0, 중간 실패 전체 롤백, 커밋 응답 유실 후 재실행, preflight 이후 원문 drift, 부분 schema/version 거절, SQL처럼 보이는 문자·한글/emoji 보존, 기존 이력 변조 거절을 검증했다. 임시 스키마는 각 검사 종료 시 삭제/부재 확인했다.
- 실제 고정 공개 배치도 **소유 임시 스키마에서만** 원문1,048행을 재생했다. 기존 시간21개를 보존하고 검토 버전1+미검토 기록767만 저장(768행), release0, 재실행 추가0, raw/catalog 전후 일치를 확인했다. 실제 의미 승인으로 계산하지 않는다.
- 실제 CLI 양쪽 재생은 모두 **767 UNREVIEWED / 선택0 / BLOCKED_NO_ELIGIBLE_RECORDS**였다. 5산출물 해시가 같다. 실제DB 상태는 NOT_CHECKED다.
- 초기 오프라인 fixture의 공통 제목 모호성/Windows 임시 폴더 권한 오류와 검사 추가 중 클래스 경계 오류는 수정했다. 실패 로그를 보존하며 최종 통과 로그로 별도 구분했다. 해당 초기 시도를 통과로 계산하지 않았다.

실제 미승인 자료 재생 명령:

```powershell
python -X utf8 scripts/metadata_review_release.py plan --collection target/metadata-live-store-20261126/recovery-plan/input/collection.json --output target/metadata-review-release-20261126/actual-unreviewed-final
```

검토 기록 파일이 준비되면 새 출력 폴더에 `--decisions FILE`을 추가한다. 기존 출력은 덮어쓰지 않는다. 실제 검토 기록을 자동 승인하거나 테스트의 합성 APPROVE 기록을 재사용하면 안 된다.

작업본 증거: target/metadata-review-release-20261126/{source-offline-final,source-related-offline,source-mysql-verified}.log, actual-unreviewed-final, verification.json. Eclipse 증거: 같은 target 경로의 eclipse-offline-tests.log/eclipse-mysql-tests.log 및 actual-unreviewed-final.

## 동기화·한계·다음 작업

새 도구/검사3 + SQL참조1 + 보고서1 + 진행/로드맵/고도화계획3, 총 **8파일**을 선택 동기화했다. 기존 3문서는 실행본 target/metadata-review-release-sync-20261126/before와 작업본 target/metadata-review-release-20261126/before에 먼저 백업했다. 최종 파일 해시·기존 파일/프런트 전체 보존·실제 배치 5산출물 일치는 verification.json에 기록했다. 추가 Eclipse 지침과 초기 차이를 확인했으며 설정/자격증명/실행 구성은 복사하지 않았다.

이번 변경은 **Eclipse F5만 필요**하며 백엔드/IDE 재시작은 필요 없다. 실행 중 앱을 재시작하지 않았다. 제품 Java/React·검색/순위/대화·프롬프트·평가 질문/정답은 변경하지 않았다. Java/React/실제 모델 회귀는 실행하지 않았다. 서비스 DB 테이블 조회/DDL/DML과 새 TMDB/LLM 호출0이다. MySQL 연결은 소유 임시 스키마와 그 생성/삭제 확인에만 사용했으며 서비스 전체 상태를 독립 사후 검사한 것은 아니다.

**다음 단일 작업:** 실제 고정 배치의 명시적 필드 검토 기록을 작성하고 **비활성 릴리스 후보 범위를 확정**한다. Codex 제안/판정과 사람 독립 판정을 구분하고, 근거가 부족한 관계는 보류한다. 실제DB 적용·활성화·Content 시간 반영·검색 연결은 별도 범위이며 다음 요청 전에는 시작하지 않는다.

이전 사람0/2·의미UNJUDGED·별도검증24턴 미실행·과거 오류3/36·사전 독립풀/반복/실환경 미완료, 승인총$3/누적예약$1.1838605/알려진 사용량추정$0.0767765/실청구UNKNOWN을 유지한다. 이번 도구 검사는 추천 품질 향상이나 실제 의미 승인 통과를 뜻하지 않는다.
