# TMDB A — 시간 메타데이터 읽기 전용 조사

2026-11-24. **등록률 조사와 상세 조회 후보 범위만 완료했다.** TMDB 작품 API 호출, DB 쓰기/DDL, 시간 보강 적용은 하지 않았다. 추천 답변의 시간 조건 정확도가 개선됐다는 결과는 아직 없다.

## 실제 결과

작업본 최종 조사: 2026-11-24 08:35:30 UTC / 17:35:30 KST. 설정된 로컬 MySQL `myproject`에서 공개 작품의 ID·유형·시간값, TMDB 매핑의 유형·외부/내부 ID만 읽었다. 회원·반응·대화·제목·줄거리는 조회하지 않았다.

| 항목 | 영화 | TV | 합계 |
|---|---:|---:|---:|
| 기존 작품 | 230 | 238 | 468 |
| 유형과 일치하는 유일한 TMDB 매핑 | 230 | 238 | 468 |
| 대상 시간값 1~1440분 | 0 | 0 | 0 |
| 대상 시간값 NULL | 230 | 238 | 468 |
| 기존 비정상 시간값 | 0 | 0 | 0 |
| 반대 유형 시간 필드에 값 존재 | 0 | 0 | 0 |
| 상세 조회 후보 | 230 | 238 | 468 |

영화는 `runtime_minutes`, TV는 `episode_runtime_minutes`를 검사했다. 두 컬럼 모두 nullable INT로 존재하며 두 테이블 모두 InnoDB다. 컬럼 추가는 필요하지 않다. 매핑 누락·잘못된 ID·유형 불일치·중복 외부 키·한 작품의 복수 매핑·고아 매핑·알 수 없는 작품 유형은 모두 0이다. 현재 시간 등록률은 영화/TV 각각 **0%**다.

조사 게이트: `READY_FOR_DETAIL_DRY_RUN`. 이는 **조회 대상을 만들 수 있다는 뜻**이며 시간값 확보/적용/추천 수용 완료를 뜻하지 않는다. 결과 JSON의 `updates=[]`, 실제 작품 API 호출0, 카탈로그 쓰기0이다.

## 도구와 보호 조건

- `scripts/audit_runtime_coverage.py`: 기존 `catalog_pool.LocalCatalog`의 설정 읽기/CLI 실행만 재사용한다. 수집·백업·apply 메서드나 TMDB 클라이언트를 호출하지 않는다. 비밀 값은 기존 방식으로 자식 프로세스 환경에서만 사용하며 출력하지 않는다.
- 정적 허용 목록의 SELECT, 세션 제한 설정, `START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY`, `ROLLBACK`만 사용한다. 명령행으로 SQL이나 외부 API 주소를 받지 않는다. 연결5초/SQL8초/각 CLI 프로세스60초 제한, 테이블별5000행 제한이다.
- 컬럼 부재/지원하지 않는 자료형은 `unavailable`로 보고한다. NULL로 오인하지 않는다. 매핑 스키마 부재, 매핑 무결성 오류, 알 수 없는 유형, 기본468개와 다른 작품 수는 상세 조회 후보 출력을 차단한다.
- 양의 내부 ID와 TMDB int32 ID, 영화/TV 일치, 외부 키와 내부 매핑의 유일성을 검증한다. 같은 TMDB 숫자라도 MOVIE와 TV는 별개의 키다. 누락 매핑을 제목 검색으로 추정하지 않는다.
- 대상 컬럼이 NULL이고 매핑이 검증됐으며 반대 유형 필드가 비어 있는 작품만 후보로 삼는다. 기존 유효 값은 보존한다. 기존0·음수·1440초과/잘못된 자료형과 반대 유형 필드 값은 수동 검토 대상으로 분리하고 자동 정리/이동하지 않는다. `unmapped`는 검증된 유일 매핑이 없는 작품 수다.
- 독립적인 읽기 전용 스냅샷2회의 좁은 범위 해시가 같아야 결과를 생성한다. Eclipse 실행본 조사도 동일한 해시를 확인했다. 이는 조사한 스키마/ID/유형/시간/매핑만의 해시이며 전체 DB/제목/평점/수집 시점의 불변 증명은 아니다.

최종 coverage snapshot SHA256: `f6ecf58f3648e1870daf679432e403ca93e1ef6afa9890c660aed9b71ea77fd9`.

실행: `python scripts/audit_runtime_coverage.py`. 기본 결과는 `target/runtime-coverage/audit.json`. 종료0=후속 상세 dry-run 준비,2=조사는 끝났으나 게이트 차단,1=조사 실패다. 실패 시 예외/SQL/서버 출력은 숨기며 새 결과를 쓰지 않는다. 이전 결과 파일은 남을 수 있으므로 후속 도구는 종료 상태·새 조사 시점·현재 DB 재검증을 요구해야 한다. `--expected-count`는 운영자 명시 옵션이며 이번에는 기본468을 사용했다. 이 JSON은 apply 입력 파일이 아니다.

## 다음 단일 기능: 소규모 상세 수집과 변경안 dry-run

다음 요청에서 별도 구현할 범위다. 이번에는 아래 기능을 구현하거나 실행하지 않았다.

1. 매번 현재 등록률/매핑을 재검증한 뒤 내부 ID순 영화20개·TV20개, 최대40개를 선택한다. 대상·결과 해시와 출처를 보존한다. 468개 전체 일괄 수집이나 카탈로그 증가는 하지 않는다.
2. [영화 상세](https://developer.themoviedb.org/reference/movie-details)의 `/3/movie/{movie_id}`와 [TV 상세](https://developer.themoviedb.org/reference/tv-series-details)의 `/3/tv/{series_id}`만 허용한다. 키는 서버/프로세스 환경에 두며 리디렉션 거절,응답 크기/요청 수/전체 시간 제한,429 중단을 구현한다. ID와 요청 유형에 맞는 응답 구조를 검증한다.
3. [공식 응답 스키마](https://developer.themoviedb.org/openapi/tmdb-api.json)의 영화 `runtime`, TV `episode_run_time`을 각각 검증한다. 보수적인 초기 정책으로 영화는 정수1~1440만 후보로 삼고, TV는 유효한 분 값만으로 이루어진 배열에서 서로 다른 값이 하나일 때만 대표 회당 시간 후보로 삼는다. 빈 배열·누락·비정상·여러 다른 값은 사유와 함께 건너뛴다. 임의 평균/최솟값/전체 시리즈 시간 추정은 하지 않는다. 이 대표값 정책은 프로젝트의 후속 제안이며 TMDB 보장사항이 아니다.
4. 예상 기존 값NULL,수집 값,제공자,외부 ID,조회 endpoint/원본 필드,언어,수집 작업/시점,판정 사유를 가진 **검토용 변경안**을 만든다. 출처 매핑의 기존 `fetched_at`을 새 시간 데이터의 수집 시점으로 재사용하지 않는다. 동일 입력의 결정적 판정/재실행 시 기존 값 보호/매핑 변경 차단을 검증한다.
5. **DB 미반영** 상태로 결과와 성공·건너뜀·실패 수를 보고한다. 실제 적용 도구·트랜잭션/감사 출처 저장 설계·전체 보강·변경 전후 시간 조건 추천 수용은 이후 별도 기능이다.

## 검증과 동기화

- 작업본: `python -m unittest discover -s scripts -p test_runtime_coverage.py -v` — **22/22 통과**. NULL/기존 값 보호,범위/자료형,영화·TV 분리,누락 스키마,무결성,읽기 전용 SQL,행 제한,동시 변경,해시,오류 비노출을 검사했다.
- 기존 `test_catalog_pool.py` — **19/19 통과**. 최초 기본 임시 폴더 경로의 샌드박스 권한 오류1건은 테스트 임시 폴더를 작업본 `target/runtime-coverage/tmp`로 지정해 해소했다. 기존 수집기/테스트 수정은 없다. 테스트는 합성 subprocess mock이며 실제 덤프/수집/DB 쓰기를 하지 않는다.
- 작업본·실행본3개 Python 파일 `py_compile` 통과. Eclipse 실행본 새 테스트 **22/22 통과**,실제 읽기 전용 조사 통과. 작업본/실행본의 좁은 스냅샷 해시 일치.
- 기존 제품 Java41개/챗봇 테스트59개/평가 JSON13개/기존 Python6개 해시 유지. Java·프런트·프롬프트·모델·설정·API 변경이 없어 Maven/브라우저/유료 모델 검사는 이번에 재실행하지 않았다. 과거 결과는 이전 인계에 보존한다.
- Eclipse에 새 조사/테스트2개,실행 의존인 **변경하지 않은** `catalog_pool.py`1개,문서4개 총7개 선택 동기화. 의존 파일은 실행본에 없었던 파일이며 서버 설정 파일은 복사하지 않았다. 덮어쓴 문서3개는 실행본 `target/runtime-coverage-sync-20261124`에 백업하고 원본/복사본 해시를 검증했다. 실행본 Python 검증 완료. **Eclipse F5로 새 파일을 반영하면 되며 백엔드 재시작은 필요하지 않다.**

근거: 작업본 `target/runtime-coverage/{audit, preservation, eclipse-before, eclipse-after-tests, eclipse-after-docs, final-manifest}.json`, `source-tests.log`, `catalog-tests.log`. 실행본 `target/runtime-coverage/audit.json`, `target/runtime-coverage-tests.log`. 원래 문서는 작업본 `target/runtime-coverage/before/docs`에도 보존했다.

남은 과제: TMDB A 수집/dry-run→출처를 보존하는 적용→시간 조건 추천 수용,이후 B인물/소재·C국가/언어/TV구성·D등급/OTT. 간헐503은 별도 안전한 진단 이벤트 대기이며 이번 조사로 해결됐다고 판단하지 않는다. Phase7/선택 벡터 검색도 별도다.
