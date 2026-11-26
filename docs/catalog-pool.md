# TMDB 카탈로그 확장 및 개발용 자료 정리

2026-11-17 최신 상태: 사용자 요청으로 기존 개발용 32개를 삭제하고 **TMDB 작품 468개(영화 230개·TV 238개)만 유지**했다. 사용자는 500개를 다시 채우지 않고 468개 유지하기를 선택했다. TMDB 출처 연결도 468개이며 가져온 작품과 연결 값은 바꾸지 않았다. 아래의 500개 확장 명령은 최초 작업의 재현 절차이며 현재 다시 실행할 필요는 없다.

최초 확장에서는 사용자 터미널이 수집한 실제 TMDB 후보 1,033개를 검증하고 기존 32개에 468개를 추가해 500개로 만들었다. 그 단계에서는 기존 32개를 보존했으며, 이후 이번 명시적 삭제 요청에 따라 제거했다. 토큰 값은 채팅/도구 출력으로 조회하지 않았다.

적용 전 백업: `.local-db-backups/catalog-20261117T040052402838Z/catalog.sql`. 결과: `target/catalog/apply-result.json`. 최초 검토 계획은 `target/catalog/import-plan.json`에 보존했고, 사후 계획 `target/catalog/post-import-plan.json`의 추가 대상은 0개다. `catalog_import_sources`에 새 작품 468개의 출처 연결을 기록했다. 브라우저 표시·실제 AI 추천 품질/성능은 별도 확인이 필요하다.

## 개발용 32개 삭제 결과

수입 전 백업의 해시를 검증하고 원래 작품 ID 32개를 추출한 뒤, TMDB 출처가 없는 바로 그 작품만 삭제했다. 무조건 낮은 ID나 모든 미분류 작품을 삭제하는 방식은 사용하지 않았다. 기존 관리자 삭제 코드의 참조 정리 순서에 맞춰 해당 작품의 반응 229건, 추천 로그 2,378건, 클릭 로그 64건도 삭제했다. TMDB 작품·출처 연결과 그 밖의 행은 해시 비교로 보존을 확인했다. 회원과 채팅 테이블에는 삭제/갱신을 수행하지 않았다. 과거 채팅 답변에 언급된 작품명은 기록으로 남을 수 있다.

삭제 전 5개 관련 테이블을 `.local-db-backups/legacy-cleanup-20261117T041842910301Z/affected-tables.sql`로 백업했다. 이 백업에는 회원 ID가 연결된 반응/추천 기록이 포함되므로 공개·커밋하지 않는다. 백업 해시 검증 후 실제 DB에서 삭제→검증→ROLLBACK 사전 실행을 했고, 원래 상태와 동일함을 확인한 다음 같은 삭제를 한 트랜잭션으로 COMMIT했다. 클릭/추천/반응 참조 오류는 전후 모두 0건이다. 관련 없는 행도 삭제 전 보존 해시와 동일했다.

일회성 도구: `tmp/prune_legacy_catalog.py` (Git 제외). 검토 계획: `target/catalog/legacy-removal-plan.json`; 결과: `target/catalog/legacy-removal-result.json`. 백업 폴더에 검토 계획·덤프 해시·롤백 확인·최종 결과를 보존한다. 기존 import/post-import 계획의 500개 수량은 삭제 전 역사적 기록이며, 현재 상태는 `target/catalog-inventory.json`의 468개다. 데이터 작업만 수행했으므로 Eclipse 새로고침/재시작은 필요하지 않으며 화면의 최신 목록은 새로고침해 확인한다.

## 준비된 기능

`scripts/catalog_pool.py`는 Python 표준 라이브러리와 기존 MySQL 8 CLI를 사용한다. Spring 애플리케이션을 시작하거나 Hibernate DDL을 실행하지 않는다. 이 저장소에서 실행하며 Eclipse 소스 수정은 필요 없다. DB 접속 설정은 기존 로컬 감사 스크립트와 같은 서버 측 설정을 내부적으로 사용하고 Spring 표준 환경변수의 우선순위를 반영한다. 외부 DB 주소를 받지 않으며, 접속 정보와 공급자 오류 본문을 출력하지 않는다.

- `inventory`: 작품 수, 유형, 장르 분포만 읽는다. 회원·반응·채팅은 조회하지 않는다.
- `collect`: TMDB 공식 Discover API에서 `ko-KR` 제목·소개와 포스터가 있는 실제 작품을 수집한다. 장르별 순회로 후보를 모으고 한국어 원작·전 세계 작품을 섞는다. 성인 콘텐츠와 미래 공개일을 제외한다. 한국어 소개가 없으면 지어내거나 자동 번역해서 채우지 않는다. 영화·TV별 ID를 구분해 중복을 제거한다.
- `plan`: 현재 DB와 수집 파일을 비교해 추가할 작품 전체와 수량·장르 분포를 JSON으로 만든다. API 호출이나 DB 쓰기는 없다. 유형 균형과 적게 들어 있는 장르를 우선한다. 기존 DB 유형이 이미 250개를 초과하면 삭제하지 않고 전체 수량 내에서 나머지 유형을 채운다.
- `apply`: 검토한 계획과 현재 데이터의 지문이 같을 때만 적용한다. 후보가 부족해 목표에 도달하지 못하면 실제 적재를 거부한다. 기존 카탈로그를 먼저 백업하고 새 작품만 한 트랜잭션으로 추가한다. 기존 작품의 ID·제목·점수·조회수·관련 기록을 갱신/삭제하지 않는다.

장르는 기존 추천 코드에 맞는 `action`, `comedy`, `sci-fi` 등의 CSV 키로 변환한다. TMDB의 평점과 투표 수는 그대로 사용하며, 앱의 새 작품 조회수는 0이다. 실제 앱 이용자가 본 것처럼 조회수를 만들지 않는다. 배경 이미지는 없을 수 있고 기존 화면의 대체 이미지가 사용된다. 장르가 복수인 작품이 있으므로 장르별 작품 수는 균등하지 않을 수 있다.

각 소개는 DB 제한 2,000자 이내다. 제목·장르·날짜·숫자·이미지 URL을 파일 저장 후와 적재 계획 생성 때 다시 검사한다. SQL 문자열은 UTF-8 hex 값으로 전달한다. 클라이언트의 SQL/주소 입력을 받는 공개 API는 추가하지 않는다.

## 1. TMDB 토큰을 개인 터미널에 설정

[TMDB API 인증 안내](https://developer.themoviedb.org/docs/authentication-application)에 따라 **API Read Access Token**을 발급받는다. OpenAI 키나 TMDB 계정 비밀번호와 다르다. 키를 채팅·Git·React·명령행 인수에 넣지 않는다.

저장소의 PowerShell 터미널에서 다음을 실행한다. 입력 내용은 가려지며 셸 명령 기록에 값이 남지 않는다. 환경변수는 현재 터미널과 자식 수집 프로세스에만 설정한다.

```powershell
$tmdbSecureToken = Read-Host 'TMDB API Read Access Token' -AsSecureString
$env:TMDB_READ_ACCESS_TOKEN = [System.Net.NetworkCredential]::new('', $tmdbSecureToken).Password
```

## 2. 수집하고 추가 계획 검토

```powershell
python scripts/catalog_pool.py inventory
python scripts/catalog_pool.py collect --target-total 500
Remove-Item Env:TMDB_READ_ACCESS_TOKEN
Remove-Variable tmdbSecureToken
python scripts/catalog_pool.py plan --target-total 500
```

수집 시 최대 200회 요청, 회당 20초 타임아웃, 약 5분 실행 예산과 요청 간 0.3초 간격을 둔다. 429나 네트워크 오류는 자동 재시도하지 않는다. 토큰이 없으면 네트워크/DB 쓰기 전에 중단한다. 외부 서버의 인증 토큰 리다이렉트를 막는다.

터미널에는 시작 안내와 요청 횟수·유형·장르·페이지·수집 후보 수를 즉시 표시한다. 토큰·헤더·원문 응답은 출력하지 않는다. 진행 표시는 stderr, 최종 결과 JSON은 stdout으로 분리한다. 이전 버전은 마지막에만 출력하므로 실행 중 잠시 빈 화면처럼 보일 수 있다. 이미 실행 중인 Python에는 수정한 진행 표시가 소급 적용되지 않는다. 실행 예산 확인 직전에 시작한 요청은 최대 20초 더 걸릴 수 있다. 6분 이상 결과나 오류 없이 대기한다면 Ctrl+C로 중단 후 원인을 확인한다. 같은 터미널에서 다시 실행하면 이미 설정한 토큰을 사용한다.

- 원본 정규화 후보: `target/catalog/tmdb-ko.json` (목표보다 넓게 수집하므로 총 500개를 넘을 수 있음)
- 추가 계획: `target/catalog/import-plan.json`
- 검토할 항목: `before_total`, `after_total`, `after_types`, `target_reached`, `after_genres`, `additions`, `skipped`

500개에 도달하지 못하면 계획에 부족함을 표시하고 실제 적용을 막는다. 번역/포스터 보유율에 따라 후보가 부족할 수 있으므로 수집 조건 또는 더 큰 후보 요청을 검토한다. 데이터가 부족하다고 가짜 작품을 추가하지 않는다. 수집 파일과 계획은 `target/` 아래에 두며 Git에서 제외된다.

## 3. 검토한 계획만 적용

작품을 조회/수정하는 앱 동작을 잠시 멈춘 상태에서 수행하는 편이 좋다. 계획 뒤 조회수라도 변경되면 재검토를 위해 적용을 거부한다.

```powershell
python scripts/catalog_pool.py apply
python scripts/catalog_pool.py inventory
```

이 단계는 명시적 DB 변경이다. 적용 전 `.local-db-backups/catalog-<UTC시각>/catalog.sql`과 백업 해시를 남긴다. 백업에는 카탈로그와 이전 수입 출처 연결만 들어가며 회원·반응·채팅은 덤프하지 않는다. 기존 백업을 덮어쓰지 않는다. 적재 결과는 같은 폴더의 `result.json`에 기록한다.

첫 실제 적용에서 `catalog_import_sources` 보조 테이블이 추가된다. `(media_type, tmdb_id)` 기본키와 `content_id` 유일 키로 동일 공급자 작품의 재적재를 막고, 작품 삭제 시 연결은 FK cascade로 제거된다. 기존 작품은 한국어 제목/원제의 정규화와 공개 연도를 함께 비교한다. 동명 리메이크는 연도가 다르면 유지하고, 기존 날짜가 없으면 중복을 보수적으로 건너뛴다. 원제/연도가 모두 다른 기존 별칭은 자동 중복 식별의 한계이므로 계획을 검토해야 한다.

적재는 importer 간 잠금과 기존 카탈로그 행 잠금 후 지문을 재확인한다. 계획이 오래됐거나 데이터셋이 바뀌면 쓰기 전에 중단한다. 중간 제약조건 오류는 작품·출처 연결을 모두 롤백한다. 첫 보조 테이블 생성 DDL은 트랜잭션과 별개라 실패 후 빈 테이블이 남을 수 있다. 시간초과 등으로 결과가 불명확하면 먼저 `inventory`와 기록을 확인하고 새 계획을 만들며, 이전 계획을 무작정 재실행하지 않는다.

기존 소스 연결이 있으면 다시 수집해도 그 작품을 추가하지 않는다. 500개가 이미 있으면 계획의 추가 수는 0이며 적용은 백업/테이블 생성 없이 종료한다. 이 스크립트는 데이터 갱신/동기화 기능이 아니다.

## 출처 표시와 검증 범위

홈과 AI 채팅 하단에 TMDB 로고·출처·필수 안내를 추가했다. [TMDB 사용/출처 표시 안내](https://developer.themoviedb.org/docs/faq)를 확인하고, 서비스의 실제 사용 목적에 맞는 공급자 조건을 유지한다. 로고는 공식 호스트의 원본 URL을 참조하며 실브라우저 로딩은 미확인이다. 공급자 평점은 앱 내 반응 수가 아니고 외부 시청 가능 여부도 아니다.

공식 구현 참고: [영화 Discover](https://developer.themoviedb.org/reference/discover-movie), [TV Discover](https://developer.themoviedb.org/reference/discover-tv), [이미지 구성](https://developer.themoviedb.org/docs/image-basics).

2026-11-17 검증:

- `python -m unittest discover -s scripts -p test_catalog_pool.py -v`: 최종 오프라인 19개 통과 (`target/catalog-progress-tests.log`). 기존 검사에 즉시 진행 표시·백업 도구 옵션·백업 파일 해시·백업 실패 시 쓰기 금지 회귀 검증을 추가했다. 임시 폴더 정리의 샌드박스 권한 오류는 승인 후 재실행했다.
- `$env:CATALOG_MYSQL_TEST='true'; python -m unittest discover -s scripts -p test_catalog_mysql.py -v`: 별도 무작위 이름의 `recommend_catalog_import_test_*` 스키마에서 3개 통과. 기존 행 보존·실제 INSERT·재실행 중복 방지·중간 오류 전체 롤백·오래된 계획 거부 검증. 가짜 자료만 사용했고 테스트 DB는 삭제했다. 앱의 `myproject`에는 쓰지 않았다.
- 사용자 실행의 실제 수집 결과 1,033개를 재검증했다. 검토 계획 적용 후 500개와 기존 행 보존을 검증했으며, 재계획의 추가 대상은 0개다. 첫 백업 시도는 `mysql.exe` 전용 `--connect-timeout`을 `mysqldump.exe`에 재사용해 실패했고 적재 전에 중단됐다. 해당 옵션을 분리한 뒤 실제 백업·적재가 성공했다. 초기 실패 폴더는 덮어쓰거나 삭제하지 않았다. 브라우저 표시·데이터 확대 후 실제 AI 추천 품질/성능은 아직 미검증이다.
- 프런트 `npm.cmd test -- --watchAll=false --runInBand`: 8개 스위트 / 57개 통과. `npm.cmd run build`: 성공. 기존 Browserslist/Node 경고는 남아 있다. Java 코드를 변경하지 않아 Maven 테스트는 이번 작업에서 재실행하지 않았다.
