# 실제 비활성 검토 릴리스 저장 — 2026-11-26

고정 28작품 배치의 부모 이력과 Codex 판정을 서비스 DB에 저장했다. 버전2개·기록1,534개·비활성 후보1개, 총1,537행이다. Eclipse 실행본에서 같은 계획을 명시적으로 재실행해 추가0건과 전체 행/타임스탬프 일치를 확인했다.

## 저장 범위와 확인 결과

| 항목 | 확인된 결과 |
|---|---|
| 검토 버전 | 미검토 부모1 + 새 판정1 |
| 판정 기록 | 부모767 + 새767(APPROVE101/HOLD666) |
| 후보 | active=0, 선택101(키워드26/별칭55/시간20) |
| 원문 초안 | 6테이블/1,048행, approved=0/active=0 보존 |
| 기존 Content/매핑 | 468/468행, 알려진 시간21개/미등록447개 보존 |
| 추가 보호 확인 | 전체 공개 카탈로그와 기존 시간출처의 전후 해시 일치 |
| 제공자 호출/Content 갱신 | 새 TMDB·LLM 호출0, Content 갱신0 |

입력 APPROVE는 선언된 CODEX의 한정된 로컬 판정이다. 인증된 검토자나 독립 사람 판정으로 바꾸지 않는다. 원문 UNKNOWN/UNJUDGED와 모든 원문 비활성 제약은 그대로다. 시간20개는 제안 자료이며 Content에 적용하지 않았다. 키워드 사용 범위는 DISCOVERY_ONLY이고, 검색/추천 API는 이 비활성 후보를 읽지 않는다. 따라서 이번 작업 자체로 챗봇 응답 품질이 개선되었다고 판단하지 않는다.

## 통제된 저장 경로

신규 scripts/apply_metadata_review_release.py의 prepare/apply 명령은 고정된 판정·부모·버전·후보만 허용한다. 기존 metadata_review_release.py와 해시 의존6파일의 바이트는 변경하지 않았다. 원문 수집4파일·필드검토계획·판정파일6개, 실행 코드8개, DB명/서버 UUID/버전/공개열 정의와 기준 상태, 생성 SQL/백업의 해시를 계획에 고정한다. 계획 검증은 DB 어댑터 초기화보다 먼저 실행한다.

원문 저장과 공유하는 advisory lock을 같은 연결에서 DDL/DML 동안 유지한다. 데이터 트랜잭션은 공개 Content/매핑·원문6테이블·검토3테이블을 잠그고 기준 행/타임스탬프와 대상 스키마를 재검증한다. 부모·새 판정·후보는 한 트랜잭션에 추가하고 삽입 payload와 created_at도 커밋 전에 확인한다. 검토 스키마의 열·엔진·고유키/FK·active=0 CHECK와 트리거 부재를 검사한다.

각 시도 폴더에는 before/environment JSON, 좁은 backup-manifest, 실제 execution.sql, STARTED 및 최종 result.json, 성공 후 after.json이 남는다. 전체 DB 백업은 아니다. DDL은 MySQL 특성상 데이터 트랜잭션 밖이다. 실패하면 완전한 빈 스키마가 남을 수 있으며 부분 스키마/부분 기록/다른 판정은 자동 수리하거나 채택하지 않는다. 커밋 응답이나 사후 조회가 유실되면 FAILED_OR_UNACKNOWLEDGED로 남기고 자동 재시도하지 않는다. 같은 계획의 명시적 재실행만 정확히 완성된 후보에 추가0건으로 수렴한다. 삭제·활성화·원문 변경·자동 복구 명령은 추가하지 않았다.

## 실행한 검증

작업본/Eclipse 각각 관련 오프라인46개와 소유 임시 MySQL12개 PASS다. MySQL12개에는 실제 고정 자료의 같은 prepare/apply 경로 리허설1회가 포함된다. 모든 쓰기 리허설은 새 UUID 소유 스키마에 한정하고 종료 후 정확한 소유 스키마의 삭제/부재를 확인했다.

- 부모/새 버전/후보 저장과 재실행0, 원문/기존 시간/매핑/조회수/기존 시간출처/타임스탬프 보존.
- 기록 저장 중 오류 시 전체 검토 데이터 롤백, 남은 완전한 빈 DDL에서 명시적 재실행.
- 커밋 응답 유실 및 사후 조회 실패 후 재실행0.
- 사전/사후 기준 Content 변경, 사후 기본열 정의 변경 차단.
- 부분 스키마·약화된 active CHECK·부분 이력 거부, 파일/코드/입력 위조 시 DB 접근 전 거부.
- 원문과 공유하는 잠금 경합 시 DDL 없이 실패하고 자동 재시도하지 않음.

명령: `python -m unittest test_metadata_review_apply test_metadata_review_release test_metadata_draft_apply test_metadata_storage_dry_run`(PYTHONPATH=scripts). MySQL은 METADATA_STAGE_MYSQL_TEST=true 및 공개 고정 입력 경로를 지정해 `python -m unittest discover -s scripts -p test_metadata_review_apply_mysql.py`로 실행했다. 오프라인 로그의 Inactive review operation failed 문구는 잘못된 계획을 DB 접근 전에 거부하는 검사에서 의도한 출력이며 최종 결과는 OK다. 실제 서비스 저장에는 테스트용 어댑터를 사용하지 않았다. 신규 검사 준비 중 경로 반환값/예상 부모 행 수를 잘못 설정한 테스트 오류를 수정한 뒤 위 최종 검사를 통과했다. Java/React/브라우저/실제 모델 회귀는 실행하지 않았다.

## 실제 실행과 증거

- 계획 SHA256: `19ceccd10f74029f18dbfd9afad7da1bc8eb8c058fe5d7292d145030595ff10e`.
- 버전 SHA256: `a98e2fafdf7c5bea09a9a90758815eceae01eab4cce789ff5766fca004ed8b21`.
- 후보 SHA256: `fa075eb4a7d34cf575eb7013d46bce3ad32cfaed3fcc5f8c3cd0c3d37a648bfb`.
- 원문 전후 SHA256: `e8f2eb1c207cb42afc4b9e393b31a2b6b0590057b41da62f4e4d31a7e0e0f4b5`.
- 전체 공개 카탈로그/기존 시간출처 SHA256: `8a4c6297fc5a1758d3984339912e9405b1bcb36c83d7ac51d9d8fdb192b65087`.

target/metadata-review-store-20261126 아래 live-plan, live-apply, live-before/after, protected-before/after, live-verification, source/eclipse 검사 로그와 최종 verification.json을 보존한다. Eclipse에는 같은 live-plan과 별도 live-replay 영수증이 있다. SQL/JSON 자료에 공개 작품과 수집 원문이 포함되며 사용자 대화/토큰/설정 값은 포함하지 않는다.

신규 스크립트3개·보고서1개·기존 진행문서3개를 선택 동기화했다. 기존3문서는 양쪽 target 아래 먼저 백업하고 해시를 확인했다. 계획11파일도 동일 해시로 복사했고 무관 파일/프런트는 보존 검사했다. 실제 기존 판정/원문 해시도 유지한다. **Eclipse F5만 필요하며 백엔드/IDE 재시작은 필요 없다.** 앱을 재시작하지 않았다.

## 남은 작업과 다음 단일 기능

3단계 전체는 진행 중이다. 다음 단일 기능은 **검토된 키워드26개·별칭55개를 서버의 추천 후보 검색에 연결하는 활성화/회수 경로 구현과 검증**이다. 원문과 비활성 후보는 유지하고 별도의 명시적 활성화 이력/허용 범위를 검증해야 한다. 시간20개 Content 반영, 작품 추가, 독립 의미 평가와 검색 품질 비교는 별도다. 이번 요청에서는 시작하지 않았다.

인증된 검토자/독립 사람 판정, 실제 검색 품질 비교·별도검증24턴·실환경 검증은 미완료다. 기존 사람0/2·의미UNJUDGED·과거 오류3/36·승인총$3/누적예약$1.1838605/실청구UNKNOWN을 유지한다. 직전 모달의 사용자 브라우저 표시 확인은 이번 저장 검증과 별개다.
