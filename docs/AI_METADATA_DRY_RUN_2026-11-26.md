# 3단계 — 제한 수집기·검토용 dry-run 구현

2026-11-26 KST. [미반영 보강안](AI_METADATA_ENRICHMENT_PLAN_2026-11-26.md)의 다음 단일 기능을 구현했다. **오프라인 구현/검증 완료, 실제 수집·사람 검토·DB 적용은 미착수**다. 3단계 전체 완료나 추천 품질 향상을 뜻하지 않는다. 제품 Java/React·검색·순위·대화 상태·DB 스키마·설정은 변경하지 않았다.

## 구현한 동작

`scripts/metadata_enrichment_dry_run.py`에 세 명령을 추가했다. apply 명령은 없다.

| 명령 | DB/외부 호출 | 결과 |
|---|---|---|
| `prepare --snapshot-dir DIR` | 없음, 키도 읽지 않음 | 저장된 공개/매핑 스냅샷과 고정 제안의 일치 확인.84개 모두NOT_RUN. 실제 DB 미확인 표시 |
| `prepare` | 기존 읽기 전용 감사만 사용 | 현재 카탈로그/매핑 전후 재확인. 데이터 API/키 접근 없음 |
| `collect` | 현재 DB 읽기 검증 후 제한된TMDB GET,마지막DB 재확인 | 출처/원문 필드·정상 빈값·오류·나머지NOT_RUN 저장. 이번에는 실행하지 않음 |
| `plan --collection FILE [--snapshot-dir DIR]` | 새 데이터 API 호출 없음. snapshot-dir이 없으면 현재 DB 읽기 | 저장 자료 구조/체크섬·범위·필드·상태 재검증 후 검토안 재생 |

고정 [28개 제안](evaluation/metadata-enrichment-proposal-v1.json)의 canonical SHA-256 `06fd84e18b745ab100d9cb06756c24ce5db8861f5629777c863e8f54ed829492`를 코드에서 확인한다. 공개468개/매핑 스냅샷으로 이전 제안을 재구성해 동일해야 진행한다. ID/유형/기준행/전체 공개·매핑 해시/조회목록 중 하나라도 바뀌면 수집 전에 중단한다. 제목으로TMDB ID를 추측하거나 현재 데이터에 맞춰 표본을 조용히 교체하지 않는다. 원래 제안/평가 질문·정답은 수정하지 않았다.

인증 요청은 고정 `api.themoviedb.org` HTTPS 호스트와 위 제안에서 재구성한 경로만 사용한다. redirect·다른 호스트·임의 URL·추가 endpoint·재시도·append·영문fallback·회차 조회는 지원하지 않는다. 기존 `TMDB_READ_ACCESS_TOKEN` 환경 경로만 사용하며 토큰을 CLI인수/출력/결과파일에 넣지 않는다. 준비/저장 자료 재생에서는 키를 읽지 않는다.

## 응답 구조와 제한

공식 문서와 [OpenAPI](https://developer.themoviedb.org/openapi/tmdb-api.json)를 읽고 필요한 공개 필드의 타입/배열 구조만 [고정 자료](evaluation/metadata-source-shapes-v1.json)에 보관했다. 인증된 실제 작품 응답을 조회한 것은 아니다. 처음 직접 문서 다운로드는 접속 실패/HTTP403이었으며 표준User-Agent로 공개 스키마 파일만 다시 읽어 성공했다. 키·작품 데이터 API·우회 인증은 사용하지 않았다. 스키마 원본 바이트의 해시와 출처/확인일을 기록했다.

- 영화 키워드는 `keywords`,TV키워드는 `results` 배열이다. [영화 문서](https://developer.themoviedb.org/reference/movie-keywords),[TV 문서](https://developer.themoviedb.org/reference/tv-series-keywords).
- 영화 다른 제목은 `titles`,TV다른 제목은 `results` 배열이다. 국가/다른 제목 유형은 보존하고 국가를 언어로 해석하지 않는다. [영화 문서](https://developer.themoviedb.org/reference/movie-alternative-titles),[TV 문서](https://developer.themoviedb.org/reference/tv-series-alternative-titles).
- detail은 영화 `title/original_title/runtime`,TV `name/original_name/episode_run_time`을 구분하고 반대 유형의 루트 필드를 거절한다. 반환ID는 정확한 정수이며 제안의TMDB ID와 같아야 한다.
- 최대28작품×3=84시도,동시1,요청 시작간격 최소1초,자동 재시도0. 수집 클라이언트의 전체예산900초·요청예산10초·응답1MiB를 사용한다. 연결/헤더/본문 읽기 사이마다 남은 예산을 확인하고 소켓timeout을 갱신한다. 제한된본문읽기(`read1`)와Content-Length 검사를 병행하며 압축 응답은 거절한다.
- HTTP401/403,429,5xx,redirect,통신오류,시간/요청/본문/필드상한 초과,중복JSON키/비유한상수,ID/응답구조 불일치에서 중단한다. 실패 응답 본문/인증헤더/서버오류 원문은 저장하지 않는다. 이미 받은자료와 실패지점/이후NOT_RUN은 남긴다. 실제 DNS/TLS/Windows 소켓 동작과 지연은 미검증이다. OS 이름해석 등 소켓timeout 밖의 동작까지 강제 종료하는 별도 프로세스 격리는 없으므로900/10초를 실환경 종단 지연 보장으로 발표하지 않는다.
- 키워드 최대200개·이름128자,다른제목 최대100개·제목255자·유형100자,detail제목/원제500자·줄거리20,000자·TV시간배열100개다. 개수/길이 초과를 잘라서 정상 승인하지 않고FIELD_LIMIT으로 중단한다. 줄거리저장한도2,000자는 아래 검토단계에서 별도보류한다.

## 검토 자료·보존 규칙

새 폴더에 `collection.json`,`review.json`,`review.html`,`public-snapshot.json`,`mapping-snapshot.json`을 저장한다. 기존출력경로를 지정하면 DB/API작업 전에 거절한다. 원래 결과를 덮어써 재시도 성공처럼 보이게 하지 않는다.

수집 자료는 고정84개 요청 각각에 `RECEIVED/ERROR/NOT_RUN`,유형·내부/TMDB ID·endpoint·요청locale·조회UTC·응답바이트해시·선택된공개원문필드를 남긴다. 알 수 없는응답키는 버린다. detail의누락/NULL/빈문자열과키워드/별칭의누락/NULL/정상빈배열은구분한다. 전체 응답 본문 대신 필요한 원문필드만 보관하며 응답해시는 그 원본바이트의지문이다. 자료의 체크섬은 변조/누락 발견용이지 제공자서명이나출처인증은 아니다. 자료를 전부 다시 작성할 수 있는 사람이 재봉인하는 것까지 막는 보안 경계로 사용하지 않는다.

검토안의 모든필드는 `approved=false/UNREVIEWED`이며 `updates=[]`,`inserts=[]`,`apply_supported=false`다. 다음을 보여준다.

- 키워드 원문/ID·반복수/고유수·언어UNKNOWN·번역라벨[]·중심성UNJUDGED. 중복동일ID/이름은보존해집계하고같은ID에충돌이름이오면거절한다. 한국어 번역·중심소재·장르·분위기를 자동생성하지 않는다.
- 공식제목/원제/다른제목과 국가·유형의원문/누락상태. NFKC/공백/대소문자 정규화는 별도로표시한다. 기존468개제목과새별칭사이의다중ID 충돌목록을 남기며자동병합하지 않는다. 기존제목을덮어쓰지 않는다.
- 줄거리의기존/새값과UNCHANGED/NEEDS_REVIEW. 2,000자초과는HOLD_STORAGE_LIMIT,한국어확인단서가없는새줄거리는HOLD_LANGUAGE_UNCONFIRMED다. 한글존재만으로내용/번역품질을인증하지않는다. 누락/NULL/빈값은기존값을지우는명령이아니다.
- 시간은기존정책을재사용한다. 정수1–1440분만유효,TV는하나의서로다른대표값만허용한다. 빈배열/복수값/무효값/잘못된형태는각각사유와SKIP을표시한다. null의유효값만PROPOSE_NULL_FILL,일치기존값은UNCHANGED,다른기존값은CONFLICT_PRESERVE_EXISTING이다. 기존21개출처를변경하거나TV대표값으로전체분량을추정하지않는다.

READY_FOR_MANUAL_REVIEW는실제HTTP출처·84개수신완료·수집직후기준일치·이번재생의현재DB검증까지충족해야한다. 부분수집/키누락/준비상태/현재DB변경·조회실패는차단한다. 저장스냅샷이나SYNTHETIC_OFFLINE자료는현재DB/실제수집수용으로승격하지않는다. 이게이트도필드승인/DB적용이아니다. 검토HTML은모든외부문자열을이스케이프하며스크립트/폼/원격요청없이정적표로표시한다. 포함된지시는평가할데이터다.

## 이번 실행과 검사

**실제TMDB데이터0회·LLM0회·애플리케이션DB조회/쓰기0회**다. 공식스키마문서열람을작품데이터수집으로세지않는다. 새수집기의collect/실DBprepare는실행하지않았다. 저장된공개468개/매핑자료만재검증했다.

```powershell
python -X utf8 -m unittest discover -s scripts -p test_metadata_enrichment_dry_run.py -v
python -X utf8 scripts/metadata_enrichment_dry_run.py prepare --snapshot-dir target/metadata-enrichment-audit-20261126 --output target/metadata-enrichment-dry-run-20261126/prepared
```

신규33검사가통과했다. 고정스키마6종,누락/NULL/빈배열,ID/타입/배열불일치,문자열/목록/본문상한,중복JSON/키워드,고정호스트/토큰비노출,redirect/HTTP/통신오류,시간/요청예산/속도,중도실패/미실행,전후카탈로그변경,저장구조재봉인변조,별칭모호성,기존시간보존,HTML이스케이프,CLI오프라인재생/기존출력거절을확인했다. READY게이트의양성검사도모의HTTP·모의DB이며실제수집합격이아니다.

기존 `test_metadata_enrichment_audit.py`8개+`test_runtime_detail_dry_run.py`28개=관련36검사도통과했다. 합계69개이며기존검사는제품/원래정답변경없이실행했다. 기존검사임시경로는작업본target내부로제한했다. 신규33개상세결과는이번도구실행기록,관련36개는 `target/metadata-enrichment-dry-run-20261126/source-related-tests.log`에있다. Java/React/실제모델 회귀는이번변경범위에서실행하지않았다.

저장공개468개/고정28개를이용한통합합성리허설도만들었다.

| 자료 | 모의시도 | 수신/오류/NOT_RUN | 검토게이트 | 실제데이터호출 |
|---|---:|---|---|---:|
| prepared | 0 | 0/0/84 | BLOCKED,PREPARED/실DB미확인 | 0 |
| synthetic-complete | 84 | 84/0/0 | BLOCKED,합성출처/실DB미확인 | 0 |
| synthetic-partial | 4 | 3/1/80 | BLOCKED,ABORTED/RATE_LIMIT | 0 |

원본경로는 `target/metadata-enrichment-dry-run-20261126/{prepared,synthetic-complete,synthetic-partial}`이다. synthetic-complete의모의시간90/45분은기존값충돌3개/null제안25개를표시했으나실제제공자값이아니며적용대상이아니다. 합성폴더의rehearsal.json에모의시도와실제호출0을구분했다. 각폴더의collection/review를읽기전용plan으로재생할수있고,모든값은계속미승인이다.

## Eclipse 동기화·최종 확인

실행본 추가 지침/초기 차이를 확인하고 새 코드·검사2개,문서/공식구조 자료5개,준비/합성자료17개 등 총24파일을 선택 동기화했다. 덮어쓴 기존3문서는 실행본 `target/metadata-enrichment-dry-run-sync-20261126`에 초기 백업했으며 작업본의 초기3문서도 작업본 `target/metadata-enrichment-dry-run-20261126/before`에 보존했다. 동기화24개/백업3개 해시가 일치한다.

실행본에서도 동일69검사가 통과했다. 준비/전체합성/부분합성의 세 수집 자료를 실행본 `plan --snapshot-dir`로 재생해 각5개,총15개 산출물(collection/review JSON·HTML/공개·매핑 스냅샷)이 작업본과 바이트 해시까지 같음을 확인했다. 세 명령은 모두 차단된 검토 결과를 뜻하는 종료코드2였으며 실제 API/DB에 접근하지 않았다. 실행본 테스트는 source 새33개/관련36개와 같은 코드·고정자료를 사용했다. 문서의 최종 검사 기록을 반영할 때는 변경 없는 검사를 반복하지 않고 해시/보존만 다시 확인했다.

기존 파일은 작업본357개 중354개,실행본351개 중348개가 그대로다. 기존 변경은 진행/로드맵/고도화계획3문서뿐이며 새 파일4개를 추가했다. 제품·설정·기존수집기/검사·고정평가질문/정답·원래기준·과거결과·고정28개제안을 보존했다. 실행본의 Content/User 주석 등 무관한 차이를 덮어쓰지 않았다. 작업본 `target/metadata-enrichment-dry-run-20261126/{source-before,source-after,eclipse-before,eclipse-after,sync,verification}.json`과 `eclipse-tests.log`가 증거다.

**Eclipse F5만 필요하며 백엔드/IDE 재시작은 필요 없다.** 파일 동기화와 실제 앱 재시작을 구분하고 이번에는 앱을 재시작하지 않았다.

## 다음 단일 기능과 한계

다음은 **고정28개 실제제한수집 및미반영결과검토**다. 새요청에서현재DB/매핑해시를재확인하고기존비공개환경에유효한TMDB토큰이있을때만collect를실행한다. 키를채팅에붙여넣지않는다. 토큰이없으면TOKEN_REQUIRED/0시도/84NOT_RUN을남긴다. 현재카탈로그가기준과다르면수집하지말고보강안버전/범위를먼저검토한다. 이번구현완료를그실행이나필드승인으로갈음하지않는다.

실제응답의빈키워드/TV빈시간배열빈도·한국어라벨·출처데이터품질·네트워크시간/장애수용·사람검토·DB적용/멱등성·검색연결·추천품질은미검증이다. 적용도구/스키마는별도기능이며수집결과를제품이자동읽지않는다. 작품추가/검색·순위/대화개선은시작하지않았다.

2단계조정마감과사람0/2·의미UNJUDGED·별도검증24턴미실행·사전독립풀/반복/실환경·오류3/36을보존한다. 누적승인총$3·예약추정$1.1838605·알려진사용량추정$0.0767765·실청구UNKNOWN은변하지않는다.
