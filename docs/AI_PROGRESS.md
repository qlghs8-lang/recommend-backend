# Recommend AI — 최신 진행

- **Current phase / status:** 2026-11-26 KST. 품질 고도화3단계 진행 중. **검토 키워드26·별칭55의 추천 후보 검색 연결과 별도 DB 이용 활성화 완료**. 시간20개 반영·실제 모델 품질 비교는 미완료다.
- **Completed in this session:** SHA고정 검색자료/조회·후보확장/순위·바이트단위 작품변경 감지·활성화/회수 CLI를 추가했다. 원제 제외는 신뢰된ID로 고정해 도구/최종검증까지 유지한다. 실제 검색이력 enable1행 저장→Eclipse 동일계획 재실행0,전체 상태/타임스탬프 일치와 독립 읽기 검사 PASS.
- **Files changed:** CatalogRetriever/CatalogRecommendations;신규 ReviewedMetadataSearch,ReviewedMetadataSearchTest,ReviewedMetadataMysqlTest,검색resource,metadata_search_activation 및Python검사2개,스키마,AI_METADATA_SEARCH 보고서;기존 AI_PROGRESS/AI_ROADMAP/AI_QUALITY_UPGRADE_PLAN. 코드/자료/스키마10+문서4선택동기화,원본5백업/해시,계획5파일일치. 증거 target/metadata-search-20261126.
- **Tests run and results:** 양쪽 Java70(H2관련69+실제MySQL검색1)/Python오프라인5/소유MySQL3 PASS. 활성/회수·재실행0·원문/시간/근거 보존·손상/누락폴백·변경ID·하드필터·원제제외/도구·롤백/유실/변경거부 검증. 실제 원문1,048/검토1,537/Content468/매핑468/기존시간21/전체 공개정보·기존 시간출처 해시 보존. 소유 임시스키마 삭제/부재 확인. 새TMDB/LLM호출0,브라우저/실행중앱HTTP/실제모델 검증은 하지 않았다.
- **Decisions / assumptions verified:** 81관계는28작품의키워드26/공식·원제55(고유키49),시간0. 검색단서일 뿐 모델근거/필수TOPIC/시간조건을 대체하지 않는다. 원문 approved0/active0·UNKNOWN/UNJUDGED와 기존 후보active0 유지. 새검색이력만enabled1이며CODEX선언은인증된운영자/독립의미검토가아니다. 계획SHA eb977a694c3d345ebac49c7d2c3d4f09eaaad868b6746fc00e445d88a5df625c;검색SHA 438b0d950237932c6054677373c3346c31d92bd623f8790cc90d91a18bdfc889.
- **Open issues / blockers:** 실행중앱은 재시작하지 않아 새코드로드/실제브라우저/모델응답 미확인. 제한된28작품이며 전체주제 커버리지/품질개선을 측정하지 않았다. DDL은데이터트랜잭션밖,좁은백업/자동수리·재시도없음,회수는현재상태의새계획필요. 기존 사람0/2·의미UNJUDGED·별도검증24턴미실행·과거오류3/36·승인총$3/누적예약$1.1838605/실청구UNKNOWN 유지.
- **Next single feature:** 시간제안20개의 별도검증과 기존 NULL 시간보강 경로 검토. 현재검색연결에 시간20반영/작품추가/평가정답수정은 하지 않았다. 새요청전시작하지않는다.
- **First files to inspect next session:** AGENTS.md;docs/AI_METADATA_SEARCH_2026-11-26.md;docs/AI_METADATA_REVIEWED_CANDIDATE_2026-11-26.md;scripts/{metadata_search_activation,apply_runtime_enrichment}.py;검토후보의RUNTIME20자료;target/metadata-search-20261126/{live-verification,verification}.json. 별도검증질문내용은읽지않는다.

**Eclipse F5 후 백엔드 중지·재실행 필요**. IDE 자체 재시작은 필요 없다. 앱을 재시작하지 않았다. DB 검색이용은 활성화했으며 새코드가 실행중프로세스에 반영됐는지는 미확인이다. 상세 보고서에 회수 명령과 제한을 기록했다.

## 2026-11-26 커밋·푸시 준비 검증

- **Current phase / status:** 기존 작업의 Git 정리. 새 기능 단계는 시작하지 않았다.
- **Completed in this session:** 양쪽 origin 최신 이력 확인(각 HEAD와 원격 차이 0), 변경 파일 비밀값 패턴 검사(발견 0), 소스/테스트/문서 스테이징. 생성 PDF 2개는 제외했다. 삭제 작품의 현재 상세 카드까지 불변이라고 비교하던 기존 회귀 검사 1개를 수정해, 저장 메시지 ID/역할/본문/시각과 나머지 카드 전체를 보존하며 삭제 카드만 제외하는지 검증한다. 제품 코드는 수정하지 않았다.
- **Files changed:** PersonalizedRationaleAcceptanceTest.java, 이 기록. 기존 미커밋 작업은 함께 커밋 대상으로 정리했다.
- **Tests run and results:** 소스 `.\mvnw.cmd -q test`: 628개 중 553 PASS/75 조건부 SKIP, 실패·오류 0. 첫 실행의 카드 비교 실패 1개는 위 테스트 수정 후 전체 재실행으로 해소했다. `python -m unittest discover -s scripts -p 'test_*.py'`: 287개 중 228 PASS/59 opt-in SKIP. React 전체 71 PASS, `npm.cmd run build` PASS. 최초 샌드박스 로컬통신/캐시 권한 오류는 허용된 실행 및 저장소 내 Jest 캐시로 재검증했다. 로그: target/pre-push-java-final.log, target/pre-push-python.log, 프런트 node_modules/.cache/pre-push-{tests,build}.log.
- **Decisions / assumptions verified:** 유료 API/실제 DB 쓰기 검사는 활성화하지 않았다. Eclipse 기존 관련 215개 파일 해시 일치, Content.java 차이는 기존 주석 1줄뿐이라 보존, application.properties의 AI 설정 9개 일치 확인 후 로컬 설정은 보존했다.
- **Open issues / blockers:** Eclipse에 누락된 test_catalog_mysql.py/test_catalog_pool.py만 추가하고 수정 JUnit 1개는 target/commit-sync-20261126-234248 아래 원본 백업 후 동기화했다. 3파일 해시 일치. Eclipse 관련 Java 13 PASS/1 opt-in SKIP, 카탈로그 Python 19 PASS. 전체 Eclipse 회귀는 실행하지 않았고 앱도 재시작하지 않았다. 기존 문서/테스트의 EOF 공백 3곳과 Markdown 줄바꿈용 공백은 기능 변경 없이 보존했다.
- **Next single feature:** 기존과 동일: 시간제안 20개의 별도 검증과 기존 NULL 시간보강 경로 검토. 새 요청 전 시작하지 않는다.
- **First files to inspect next session:** AGENTS.md와 이 문서의 기존 최신 기능 기록, docs/AI_METADATA_SEARCH_2026-11-26.md.
