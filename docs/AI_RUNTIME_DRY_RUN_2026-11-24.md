# TMDB A — 시간 보강 상세 수집·dry-run 도구

후속 상태: 검토된21개는별도적용도구로실제DB에반영됐다(영화등록률8.70%/TV0.42%). 아래0%/미반영기록은dry-run당시결과다. 현재상태/출처/검증은[실제적용결과](AI_RUNTIME_APPLY_2026-11-24.md)를참조한다. 기존collection의plan은적용전스냅샷과달라차단되므로적용확인은새도구의check를사용한다.

2026-11-24. **도구 구현·실제40개 수집·현재 DB 기준 변경안 검토 완료.** 사용자가 개인 터미널에서 수집했으며 영화20개·TV1개 시간 보강안을 확인했다. TV19개는 원본 회당 시간 배열이 비어 있어 건너뛰었다. DB 반영 기능은 없으며 등록률 개선·시간 조건 추천 수용은 아직이다.

## 이번에 확인한 결과

- 실제 로컬 MySQL의 기존468개/정상 매핑468개와 시간 등록률0%를 이전 조사와 동일한 좁은 스냅샷 해시로 재확인했다. DB 쓰기/DDL/회원·반응·대화 조회는 없다.
- 현재 NULL+검증된 매핑의 작품 중 내부 ID순 **영화20개·TV20개,합계40개**를 선택했다. 작업본과 Eclipse 실행본의 대상 목록/스냅샷 해시가 일치한다.
- 도구 구현 당시 Codex의 `collect`는 토큰 미설정으로 `TOKEN_REQUIRED`,종료2,요청0회였다. 이후 사용자가 개인 터미널에서 실제40회 수집을 완료했다. Codex는 토큰을 조회/전달받지 않고 결과 파일만 검토했다. 이전 인증 대기 기록은 보존하며 현재 수집 차단 요인은 없다.
- 작업본 **신규28개+기존 감사22개=50/50**,실행본 **50/50** 테스트 통과. 양쪽 신규2파일 `py_compile` 통과. 기존 제품41개/챗봇 Java 테스트59개/평가 JSON13개/Python8개 해시 유지.

초기 인증 대기 결과: 작업본 `target/runtime-dry-run/20261124T084659980235Z-8af948b2/{collection,proposal}.json`. 실행본의 인증 없는 대상 확인: `target/runtime-dry-run/20261124T084740181638Z-4b5a8be7/collection.json`. 도구 구현 시 검증 근거는 작업본 `target/runtime-dry-run/{live-attempt,verification,final-manifest}.json`, `source-tests.log`;실행본 `target/runtime-dry-run/tests.log`다.

### 실제 수집 수용 — 18:11 KST 후속 검토

사용자 결과: `target/runtime-dry-run/20261124T091137616067Z-c2cc4422/{collection,proposal}.json`. 수집 시각18:11:07~18:11:37 KST,약30.13초. `COMPLETE`/`READY_FOR_REVIEW`,40요청/40응답/차단 요인0이다.

| 결과 | 영화 | TV | 합계 |
|---|---:|---:|---:|
| 조회 완료 | 20 | 20 | 40 |
| 검증된 시간 보강안 | 20 | 1 | 21 |
| 빈 episode_run_time 배열로 건너뜀 | 0 | 19 | 19 |

영화 시간은81~142분,TV1개는대표 회당25분이다. 모두 정수1~1440분,기존 예상값NULL,반환 ID/유형별 식별 구조/출처/수집 시각/작업ID/원본 필드 검사를 통과했다. 19개는 `EMPTY_EPISODE_RUNTIME`으로,인증/통신/ID 오류나 다중 회당 값 때문에 제외된 것이 아니다. 이 표본의 TV 시간 확보율은1/20이며 전체TV의 등록 가능 비율을 보장하지 않는다. 비어 있는 회당 시간을 줄거리/모델/임의 평균으로 채우지 않았다.

작업본·Eclipse 실행본에서 `plan --collection`으로 현재 DB와 재검증한 결과가 원래 proposal과 전체 JSON 기준으로 동일했다(각 종료0). **추가 TMDB 요청0회,DB 쓰기0회**다. 출력 요약의 `api_calls=40`은 원래 수집 횟수를 이어받은 값이며 재검토 중 새로 발생한 횟수가 아니다. 현재 좁은 스냅샷은 이전 조사와 같아 기존468개/대상 시간값NULL/등록률0%를 유지한다.

- collection SHA256(논리값): `b236180e209f67ee200b0ac31b39f7828a9e2e7e5a50ddb67ea308278d376f52`
- proposal SHA256(논리값): `280a9a49f6145a2e2af40d4bcbf6f88885264544be7d50feaad061842f4e43cd`
- 후속 근거: 작업본 `target/runtime-dry-run-acceptance/{collection-review,source-plan,final-manifest}.json`;실행본 `target/runtime-dry-run-acceptance/runtime-plan.json`. 전후 문서/보존 해시도 같은 작업 폴더에 기록했다. 제품/테스트/스크립트 수정이 없어 이 후속 검토에서는50개 테스트·Maven·브라우저를 재실행하지 않았다. 50/50은 위 도구 구현 시 실행한 결과다.

## 동작과 보호 조건

새 스크립트는 `scripts/runtime_detail_dry_run.py`,테스트는 `scripts/test_runtime_detail_dry_run.py`다. 기존 감사 도구와 수집기의 설정 읽기/해시/파일 저장/리디렉션 차단만 재사용하며 기존 도구는 수정하지 않았다.

| 명령 | 실제 DB 읽기 | TMDB 요청 | 결과 |
|---|---|---|---|
| `prepare` | 전후 감사 | 0 | 대상40개 이하,PREPARED 변경안 차단 |
| `collect` | 전후 감사 | 최대40 | 검증된 수집 증거와 검토용 변경안 |
| `plan --collection PATH` | 현재 상태 재감사 | 0 | 저장된 증거를 검증해 결정적으로 재계산 |

각 감사는 이전 도구의 독립 읽기 전용 스냅샷2회/스키마 재확인을 수행한다. 기본468개 유지와 매핑/유형/시간 스키마 게이트를 통과해야 수집한다. 감사 해시는 ID·유형·시간·매핑·관련 스키마만 다루며 전체 DB 불변을 증명하지 않는다.

- [영화 상세](https://developer.themoviedb.org/reference/movie-details) `/3/movie/{id}`와 [TV 상세](https://developer.themoviedb.org/reference/tv-series-details) `/3/tv/{id}`만 고정 호스트에 GET한다. 검색/Discover/시즌·회차 상세/append 조회는 없다. 외부 주소/SQL/키를 인수로 받지 않는다.
- 요청당 양의 int32 TMDB ID,반환 ID 일치,영화 title/TV name의 식별 구조와 반대 유형 필드 부재를 확인한다. 이는 고정 endpoint와 응답 구조를 확인하는 검사이며 원본 응답에 별도 media_type 보장이 있다고 가정하지 않는다.
- 인증은 프로세스 환경에서 읽어 헤더로만 전달한다. 리디렉션 거절,응답 최대4MB,회당 연결/소켓 최대20초,요청 간0.3초,작업 최대40회/120초 예산이다. 응답을 작은 청크로 읽어 청크 사이에도 전체 시간을 검사한다. 진행 중 소켓 읽기 때문에 전체 시간 한도를 최대20초 넘을 수 있다. 자동 재시도는 없다.
- HTTP401/403/429/5xx/리디렉션·네트워크·불완전 응답·크기/시간 초과·잘못된 JSON/ID/유형이면 즉시 중단한다. 처리한 부분 증거는 관리된 중단 결과로 보존하되 **전체 변경안을 차단**한다. 강제 프로세스 종료/디스크 오류까지 중간 파일 보존을 보장하지 않는다.
- [공식 스키마](https://developer.themoviedb.org/openapi/tmdb-api.json)의 영화 `runtime`은 정수1~1440만 제안한다. TV `episode_run_time`은100개 이하 배열의 모든 항목이 정수1~1440이고 서로 다른 값이 하나일 때만 그 값을 대표 회당 시간으로 제안한다. `[45,45]`는45,`[40,60]`은건너뜀이다. bool/실수/문자열/0/음수/초과/누락/null/빈 배열은 사유를 남겨 건너뛴다. 임의 평균이나 전체 시리즈 시간은 만들지 않는다. TV 대표값 규칙은 프로젝트 정책이다.
- 기존 유효 시간은 감사 단계에서 대상에서 제외한다. 수집 전후 좁은 스냅샷/현재 대상 목록이 달라지면 변경안을 차단한다. 이후 DB 적용 시점의 경쟁 조건은 이 도구가 해결하지 않으므로 후속 적용 도구에서 다시 트랜잭션/예상 NULL을 검증해야 한다.

`collection.json`에는 대상·작업ID·조회 시각·제공자·언어·endpoint·원본 필드·검증된 식별 구조·정제된 시간값·응답 바이트SHA256을 보존한다. 제목/줄거리/전체 응답/인증 헤더는 저장하지 않는다. 잘못된 형태의 문자열/객체는 내용 대신 unsupported 판정만 저장한다. 기존 매핑의 `fetched_at`은 읽거나 새 시간 수집 시각으로 재사용하지 않는다.

`proposal.json`의 `proposed_changes`는 예상 기존값NULL,제안 시간,원본 시간값,판정 사유와 출처/작업/수집 시각을 가진 검토용 목록이다. `mode=REVIEW_ONLY_NO_APPLY`, `apply_supported=false`, `catalog_writes=0`이다. 모든 선택 작품의 요청이 끝나야 `READY_FOR_REVIEW`가 된다. 시간값이 없는 정상 응답은 건너뛴 수로 분리한다. `validated_value_count`는 중단된 수집에서도 있을 수 있으므로 적용 가능한 수가 아니다. 실제 검토 대상 수는 차단 여부와 `proposed_changes`를 함께 본다.

저장 증거는 크기1MB/버전/정책/해시/ID/유형/중복/요청 수/원본 필드/endpoint/UTC시각/시간값 형태를 다시 검증한다. 저장된 결정은 신뢰하지 않고 재계산하며 같은 입력/현재 스냅샷이면 같은 제안을 만든다. 해시는 파일 혼동/변경 감지용이며 사용자 편집 JSON을 TMDB 서명 증거로 만드는 것은 아니다. 향후 apply는 이 JSON을 SQL이나 무검증 명령으로 실행해서는 안 된다.

## 개인 터미널에서 실제 수집 마무리

아래는 후속 수집이 필요할 때의 재현 절차다. **현재40개는 이미 수집/검토했으므로 다시 collect할 필요가 없다.** 키를 채팅이나 파일/명령행 인수에 넣지 않는다. 별도의 개인 PowerShell에서 실행하면 입력은 가려지며 이 전용 터미널의 임시 환경변수는 끝나면 지운다.

```powershell
Set-Location C:\Users\jaehyeon\recommend-backend
$tmdbDryRunToken = Read-Host 'TMDB API Read Access Token' -AsSecureString
try {
    $env:TMDB_READ_ACCESS_TOKEN = [System.Net.NetworkCredential]::new('', $tmdbDryRunToken).Password
    python scripts/runtime_detail_dry_run.py collect
    $runtimeDryRunExit = $LASTEXITCODE
} finally {
    Remove-Item Env:\TMDB_READ_ACCESS_TOKEN -ErrorAction SilentlyContinue
    $tmdbDryRunToken.Dispose()
    Remove-Variable tmdbDryRunToken -ErrorAction SilentlyContinue
}
```

출력의 `output` 폴더에 결과2개가 생성된다. **키가 아닌 결과 폴더 경로/요약**만 공유하면 다음 검토를 이어갈 수 있다. 종료0=prepare 준비 성공 또는 검토 가능 수집/재계산,2=인증 대기·관리된 중단·현재 DB 변경 등 검토 차단,1=입력/DB/파일 등 치명적 실패다. prepare 종료0도 실제 수집 성공은 아니다.

인증 없이 대상만 확인하려면 `python scripts/runtime_detail_dry_run.py prepare`. 수집 완료 증거의 재계산은 `python scripts/runtime_detail_dry_run.py plan --collection "출력폴더\collection.json"`. 재계산에는 토큰이 필요 없다. 단순 재검토를 위해 collect를 반복하면 같은 NULL 작품을 다시 조회하므로 기존 증거가 있으면 plan을 사용한다. 실행마다 새 작업 폴더를 만들며 과거 결과를 덮어쓰지 않는다.

## 검증·동기화와 남은 범위

도구 구현 시 실행한 테스트 명령: `python -m unittest discover -s scripts -p 'test_runtime*.py' -v` — 양쪽50통과/실패0. 정상40건,유효 범위,누락/여러 회당 시간,초과/잘못된 자료형,ID/유형 거절,기존 값 제외,현재 매핑 변경,부분 실패,인증/429/시간·크기 제한,불완전 HTTP,리디렉션 차단,증거 재검증/재계산,오류 비노출을 합성 응답으로 확인했다. 후속 실제 표본은 위40응답/21제안/19빈 배열 결과이며 장기 네트워크 성공률이나 전체TV의 분포는 미검증이다. 초기 HTTPError mock 자원 경고를 계기로 오류 응답도 닫도록 했고 최종 로그에는 경고가 없다. Java/프런트/API/모델/설정 변경이 없어 Maven·브라우저·유료 모델은 재실행하지 않았다.

Eclipse 새 스크립트/테스트2개와 문서4개 총6파일 선택 동기화 및 해시 확인. 기존 문서3개와 테스트 후 HTTP 중단 처리 보완의 실행본 이전2파일을 실행본 `target/runtime-dry-run-sync-20261124`(revision1 포함)에 백업하고 해시 확인했다. 설정/기존 제품/무관한 변경은 보존했다. **F5로 파일을 반영하면 되며 백엔드 재시작은 필요하지 않다.** 앱을 시작/재시작하지 않았다.

실제 수집 검토 후에는 문서4개만 추가 동기화했다. 실행본 `target/runtime-dry-run-acceptance-sync-20261124`에 기존 문서4개를 백업하고 원본/복사본 해시를 검증했다. 기존 소스·설정·수집 증거는 보존했다. F5로 문서를 반영하면 되며 앱 재시작은 필요 없다.

다음 단일 기능은 **검토된21개에 한정한 시간값 적용 도구와 보강 출처 저장**이다. 실제 적용 전 현재 매핑/NULL 재검증,원자성,재실행 보호,기존 값 보존,격리 MySQL 검증이 필요하다. 시간 조건 추천 수용은 그 후 별도다. TV19개의 빈 배열은 그대로 두고 추가 시즌/회차 조회 필요성을 별도 검토한다. 468개 전체 보강,TMDB B~D,간헐503 진단,Phase7은 이번 범위에 포함하지 않았다.
