# TMDB A — 검토된21개 시간값과 출처 적용

2026-11-24. **실제 로컬 DB에 영화20개·TV1개 시간값과 출처21개를 적용했다.** 기존468개를 유지했고 추가 수집/유료 모델 호출은 없다. 시간 조건 추천의 HTTP/화면/실제 모델 수용은 다음 기능이다.

## 실제 적용 결과

| 유형 | 기존 작품 | 적용 전 유효 시간 | 적용 후 | 등록률 | 남은 NULL |
|---|---:|---:|---:|---:|---:|
| MOVIE | 230 | 0 | 20 | 8.70% | 210 |
| TV | 238 | 0 | 1 | 0.42% | 237 |
| 합계 | 468 | 0 | 21 | 4.49% | 447 |

입력은 사용자가 수집/검토한 `target/runtime-dry-run/20261124T091137616067Z-c2cc4422/{collection,proposal}.json`이며 검토된 논리proposal SHA256 `280a9a49f6145a2e2af40d4bcbf6f88885264544be7d50feaad061842f4e43cd`와예상21개를 명시해 고정했다. 영화81~142분,TV1개 대표회당25분이다. 원본 TV19개의 빈 배열을 포함해 나머지 시간값은NULL 그대로다.

실제 MySQL8.0.44에서 관련 트리거0개를 확인한 후 작업본 적용 `APPLIED/updated=21/provenance=21` 통과. Eclipse 실행본의 동일 작업 재실행은 `ALREADY_APPLIED/updated=0/provenance=21`로 끝났다. 최초 적용 후와 재실행 후의 전체 좁은 스냅샷·출처 JSON·적용 시각이 동일하다. 변경 시간 필드는정확히21개다.

기존 수집기의 시간 외 공개 카탈로그 전체/작품별 해시,기존 TMDB 매핑/최초 `fetched_at` 해시를 전후 비교해 보존했다. 회원·반응·대화 데이터는 읽거나 변경하지 않았다. 작품 추가/삭제·기존 유효 시간 덮어쓰기·API/프런트/Java/모델/설정 변경은 없다. 보강용 테이블1개 생성은 데이터 트랜잭션과 구분해 기록했다.

## 저장 구조와 적용 도구

`scripts/apply_runtime_enrichment.py`의 신규 `catalog_runtime_enrichments`:

| 필드 | 역할 |
|---|---|
| content_id,target_field | 기본키. 작품/시간 필드별 초기 보강1개 |
| minutes | 검증된1~1440분 |
| collection_sha256,proposal_sha256 | 수집/검토 배치 연결 |
| evidence JSON | 제공자,영화/TV,TMDB ID,endpoint,언어,원본 필드·시간값,수집 시각,작업ID,응답 해시,판정,정책,예상NULL을 보존 |
| applied_at DATETIME(6) | 서버 UTC 적용 시각 |

InnoDB,기존 `contents(id)`에 대한 외래키/삭제CASCADE,시간범위/필드 CHECK를 사용한다. 기존 `catalog_import_sources.fetched_at`을 보강 시각으로 재사용하지 않는다. JSON에는 공개 수집 근거만 있으며 제목/줄거리/키/사용자 대화는 없다. 이 테이블을 새로운 AI 도구/API로 노출하지 않았다.

기존 dry-run의 `derive_changes()` 순수 검증 함수를 분리해 수집값 판정을 재사용했다. 기존 dry-run 출력 계약은 유지하고,그 JSON을 그대로 SQL로 실행하는 기능은 만들지 않았다. 적용 도구는 별도의 검토 해시/예상 개수,파일 크기/수집 상태/무결성,재계산된 제안 목록·제외 사유의 일치를 검증한다.

보호 절차:

1. 현재 좁은 카탈로그/매핑/시간 스키마를 독립적으로2회 확인한다. NULL인 신규 보강 상태 또는 값·출처가 모두 일치하는 동일 배치 재실행만 허용한다. 기존 값이 같더라도 출처가 없으면 임의 채택하지 않는다. 일부만 적용된 상태나 다른 출처는 중단한다.
2. 이미 적용된21개만 검증된 원래NULL로 정규화해 원래 검토 스냅샷 해시와 비교한다. 다른 작품의 시간/매핑/유형/관련 스키마 변경은 차단한다. 전체 DB의 모든 필드를 고정하는 해시가 아니다.
3. 쓰기 전에 `before.json`과 논리 해시가 있는 `backup-manifest.json`을 저장/재확인한다. 이는 시간/ID/유형/매핑/관련 스키마/대상 출처만의 좁은 백업이며 전체DB 덤프가 아니다.
4. 출처 테이블이 없을 때만 생성하고 기존 구조가 다르면 ALTER/삭제하지 않는다. **DDL은 암묵커밋 때문에 데이터 트랜잭션 밖**이다. 이후 데이터 실패 시에도 빈 출처 테이블이 남을 수 있으며 `schema.json`에 별도 기록한다.
5. [InnoDB 잠금 읽기](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)를 사용해 REPEATABLE READ 트랜잭션 안에서 현재 contents/매핑/출처 행을 잠그고,전체 좁은 예상 행·개수·컬럼/테이블 구조·대상 출처를 다시 검증한다. 테이블별 잠금 읽기는최대5001행,사전 감사는5000행까지다. 원본468개를 지키기 위해 대상21개보다 넓게 잠근다.
6. 검증 게이트는 임시 테이블의 중복 기본키 오류로 차단한다. NULL 조건도 실패로 취급하며 CHECK 무시 여부에 의존하지 않는다. 각 `UPDATE ... WHERE ... IS NULL`이1행을 바꿨는지 확인하고 해당 출처를 삽입한다. 시간값·출처21개를 같은 COMMIT으로 확정한다. batch CLI는오류시중단하며 연결 종료로 미커밋 변경이 롤백되는 것을 실제 MySQL에서 검사했다.
7. 잠금 대기5초,SELECT 실행 제한8초,CLI 프로세스60초이며 자동 재시도는 없다. 실패/커밋 응답 유실은 `FAILED_OR_UNACKNOWLEDGED`로 남긴다. 사후 상태 확인 전 롤백됐다고 단정하지 않는다. 성공 후 전체 좁은 상태/출처를 다시 검증한다. 재실행은 잠금/검증 후 시간·출처 DML 없이 종료해 적용 시각도 보존한다.

## 실행과 증거

현재21개는 이미 적용됐으므로 다시 수집할 필요가 없다. 상태를 읽기 전용으로 확인하는 명령:

```powershell
python scripts/apply_runtime_enrichment.py check --collection target/runtime-dry-run/20261124T091137616067Z-c2cc4422/collection.json --reviewed-sha256 280a9a49f6145a2e2af40d4bcbf6f88885264544be7d50feaad061842f4e43cd --expected-proposals 21
```

`apply`는동일인수로실행하며이번에작업본적용/실행본재실행을실제로완료했다. 각 실행은새 `target/runtime-apply/<시각-작업ID>` 폴더에좁은백업·SQL·스키마변경여부·전후상태·결과를 남긴다. SQL은검토용공개데이터만포함하며 키/접속정보를담지않는다. 검토된 기존 dry-run의 `plan`은 적용 전NULL 스냅샷을 요구하므로 적용 후에는 `CURRENT_CATALOG_CHANGED`로 차단되는 것이 정상이다. 적용 확인에는 위 `check`를 쓴다.

- 최초 적용: 작업본 `target/runtime-apply/20261124T093641323225Z-54612bd6`.
- 실제 재실행: 실행본 `target/runtime-apply/20261124T093758506594Z-9f7be9fc`, `target/runtime-apply/application-noop.json`.
- 전후 보존: 작업본 `application-preflight.json`, `application-verification.json`, `noop-verification.json`.
- 동기화/해시: 작업본 `eclipse-before.json`, `eclipse-after-tests.json`, `eclipse-after-docs.json`, `final-manifest.json`, `before/docs`.

## 검증 결과와 다음 기능

- 작업본: 관련 unittest73개 발견,오프라인65통과/합성MySQL8조건부건너뜀. 이어서 명시적으로 실제 MySQL8개를 실행해 **8/8 통과**. 전체73개가 각각 실행/통과한 결과이며 건너뜀을 통과로 세지 않는다.
- 실행본: MySQL opt-in 상태의 관련 unittest **73/73 통과**,실패/오류/건너뜀0. 양쪽 변경Python4파일 `py_compile` 통과.
- 신규 오프라인15개: 검토해시/재계산,현재값·매핑·부분적용/출처 충돌,전체게이트·백업선행,실패시자동재시도없음,오류비노출. 기존감사/수집50개도 통과했다.
- 신규 실제MySQL8개: 원자적혼합유형적용/출처·다른필드보존/재실행,기존값/매핑차단,출처INSERT강제오류롤백,사전검사후시간·매핑경쟁조건차단,부분적용거절,호환되지않는테이블보존. 각검사프로세스는기존앱DB와다른새 `recommend_runtime_apply_test_<랜덤>` 스키마만생성/사용/삭제했고양쪽검사후잔여0을확인했다.
- 실제 적용/출처21개/재실행0개/시간 외 데이터 보존도 별도로 확인했다. 실제 추천 HTTP/브라우저/모델을 검증한 결과는 아니다. Java/프런트 수정이 없어 Maven·브라우저·유료 모델은 이번에 재실행하지 않았다.

Eclipse: 지침/최초차이 확인 후신규도구·테스트3개/기존dry-run1개/문서5개 총9파일만동기화했다. 기존스크립트1개/기존문서4개는실행본 `target/runtime-apply-sync-20261124`에백업/해시확인. 소스/실행본해시일치,기존챗봇제품41개/Java테스트59개/평가JSON13개·무관한Python9개보존. 설정·프런트·무관한변경은복사하지않았다. **Eclipse F5로 새 파일을 반영하면 되며 이번 변경에 백엔드 재시작은 필요하지 않다.** 데이터는이미같은로컬DB에적용됐다. 앱을시작/재시작하지않았다.

다음 단일 기능은 **실제 시간값을 사용하는 조건 추천 수용**이다. 기존 SQL 검색→후보→응답 검증 경로에서 영화/회당 시간 제한 준수,시간 미등록 작품 제외,후속 조건 변경,TV 전체 분량 추정 금지를 확인한다. 전체447개NULL 보강/TV19개빈배열의추가시즌·회차조회,TMDB B~D,간헐503진단,Phase7은별도다.
