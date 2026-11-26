# 3단계 — 실제 제한 수집 실행과 인증 준비 상태

2026-11-26 KST. [수집기 구현](AI_METADATA_DRY_RUN_2026-11-26.md) 다음 작업인 **고정28개 실제 수집과 Codex의 미반영 기술 검토를 완료**했다. 최초 TOKEN_REQUIRED는 아래에 실패/준비 이력으로 보존한다. 사용자가 개인 터미널에서 인증 수집을 완료하고 결과 경로를 전달했으며84요청/84수신/오류0이다. 사람의 독립 의미 검토·필드승인·DB 적용·제품 연결을 완료한 것은 아니다.

## 실제 수집 결과

사용자가 전달한 폴더는 `target/metadata-enrichment-live/20261126T062616162Z-12846dde`다. 수집기 기록은 2026-11-26 **15:26:32.941–15:27:58.043 KST**,started→finished85.102524초다. 이 구간은 수집기 시작부터 마지막DB재확인까지이며 토큰입력/명령전체/화면 지연의 측정값은 아니다. 한 번의 제한 배치 관찰로 반복 안정성을 주장하지 않는다. 에이전트는 결과 검토 중 추가TMDB호출을 하지 않았다.

| 실제 배치 항목 | 결과 |
|---|---:|
| 기존 작품/제안 표본 | 468개 유지 / 영화14·TV14 |
| 수집 상태/출처 | COMPLETE / TMDB_HTTP |
| 시도/수신/ERROR/NOT_RUN | 84 / 84 / 0 / 0 |
| 수집 후 기준/현재DB재생 | MATCH / READY_FOR_MANUAL_REVIEW |
| 키워드 | 413작품-키워드 관계 / 고유provider ID348개 |
| 키워드 정상 빈 결과 | 1작품,헬로우 고스트156 |
| 다른 제목 | 원문279행 / 작품·정규화제목·국가·유형 기준278행 |
| 시간 | 새null보강후보20개(영화11/TV9),기존동일3개,TV빈배열5개 |
| 제목/줄거리 | 각각28개 모두기존과동일 |
| 필드승인/DB쓰기/작품추가/제품변경/LLM호출 | 모두0 |

수집자료 체크섬은 `9cb6bff327f7c73403bbbf66ac65c81fa6fea9dcc724d580e827e49e22818954`다. 고정제안의28개와84개경로,응답ID/유형/필드구조/타임스탬프/상태/예산을 검증했다. 현재DB에 다시 읽기 전용으로 대조하는 아래명령으로 원본의5산출물과 같은 바이트 해시를 얻었다.

```powershell
python -X utf8 scripts/metadata_enrichment_dry_run.py plan --collection target/metadata-enrichment-live/20261126T062616162Z-12846dde/collection.json --output target/metadata-enrichment-live-20261126/source-live-replay
```

공개468개/매핑468과 최초고정해시는 유지됐다. 실제등록시간은 **21개/447개미등록 그대로**다. 새20개는 제안이며 아직DB에쓰지않았다. 프로세스의실제API시도는사용자가실행한84회이고,최초토큰부재 실행은0회다. 모의호출이나재생을이84회에더하지않는다.

## 미반영 기술 검토와 적용 전 경계

작품별 원문 출처와 기술 검토는 `target/metadata-enrichment-live-20261126/{live-summary,title-review-summary,technical-review}.json`에 남겼다. 검토자는Codex이며 사람의독립판정은NOT_DONE이다. 원래collection/review파일은수정하지않고 모든필드를미승인으로유지했다. 원문키워드/다른제목이제공자응답에있다는것과 사실성·중심소재·사용자적합성을독립검증했다는것은다르다.

- **키워드:** 작품내중복관계0,총413관계/고유ID348이다. 정상빈배열156은데이터오류로채우지않는다. 언어필드가없어모두UNKNOWN,한국어번역라벨[]·의미중심성UNJUDGED다. 프리솔로47의extreme sports/rock climbing,드라이브359의racing/formula one (f1) 등은범용후보발견을돕는실제단서다. 반대로스파이더맨199의키워드에는스포츠태그가없고굿보이322는police/police drama만있다. 이번단어목록을장르/중심성정답으로바꾸거나한질문용제품사전으로사용하지않는다.
- **감상/안전 해석:** 가족관계·child protection·kids·hopeful 같은태그는가족과편히보기/아동적합/따뜻함의보장이아니다. 실제태그에는온슬로트268의child protection과brutal violence,86에이티식스188의kids와war,트윈픽스441의hopeful과psychological horror가함께있다. 후보줄거리비교·별도등급/장면근거와구분해야한다. 이는원문검토에서발견한해석주의사례이며사람관련성점수가아니다.
- **별칭:** 모두정상비어있지않은배열이다. 원문279행중156의일본어표기2개가NFKC정규화후같은국가/유형으로겹친다. 원문/출처는보존하고관계중복정책을적용단계에서명시한다. 156에는KR국가의로마자표기Hellowoo goseuteu도있어국가KR을한국어라벨로단정할수없다. 기존동명4쌍(괴물·그린치·미녀와야수·하트랜드)은그대로모호하며새별칭으로작품을병합하지않는다.
- **시간:** 새후보20개는 `59,76,117,136,156,177,188,190,199,205,257,258,268,309,322,332,359,410,428,456`이다. 영화는78–176분,TV대표값은11–69분이다. 기존45/47/53은100/100/81분으로동일해덮어쓸필요가없다. TV `78,405,441,466,476`의빈배열은추정하지않는다. TV대표값은모든회차나전체시리즈시간의보장이아니다.
- **줄거리/제목:** 28개모두동일하므로교체후보가없다. 짧은줄거리4개(47/205/257/258)와앞400코드포인트를넘는3개(136/177/309)가그대로다. detail재조회만으로짧은줄거리나모델가시범위를보강했다고볼수없다. 원제는별칭검토자료이며기존공식제목을대체하지않는다.

이검토는원본구조/매핑/보존정책과활용한계를확인한것이다. 외부작품사실대조·한국어라벨승인·실제추천Precision/Recall·감상적합성은미검증이다. 모든updates/inserts는빈배열이고apply명령은없다. READY_FOR_MANUAL_REVIEW를DB적용승인으로표현하지않는다.

## 최초 실행 — 토큰 부재

현재 프로세스·사용자·시스템의 `TMDB_READ_ACCESS_TOKEN`은 모두 미설정이었다. 값은 조회 결과나 문서에 출력하지 않았고 설정 유무만 확인했다. 다른 파일이나 자격증명 저장소에서 키를 찾지 않았다.

```powershell
python -X utf8 scripts/metadata_enrichment_dry_run.py collect --output target/metadata-enrichment-live-20261126/token-required
```

첫 제한 실행은 로컬 DB 접속 실패로 종료했고, 같은 명령을 승인된 권한 확장으로 실행해 실제 DB 읽기 검증에 성공했다. 키가 없어 요청 전 중단했다. API 오류나 정상 빈 키워드로 해석하지 않는다.

| 확인 항목 | 결과 |
|---|---|
| 실행 상태/검토 게이트 | TOKEN_REQUIRED / BLOCKED |
| 현재 공개 카탈로그/정상 매핑 | 468개 / 468개 |
| 수집 전후 공개·매핑 기준 | MATCH, 고정 제안과 일치 |
| 실제 TMDB 데이터/LLM 호출 | 0 / 0 |
| 수신/ERROR/NOT_RUN | 0 / 0 / 84 |
| DB 쓰기/적용/작품 추가 | 모두0 |

공개 스냅샷 해시는 `d8a241483873f940f68b29ab4d6090758ed85e3bd5bd7a2ac3f82e2a1d219a68`, 매핑 해시는 `cb452f1c984c05ae1d20b7b293ce17010a4cf2c9361bb169d75b8dded3592d1f`다. 스냅샷 안의 시간은 기존21개 등록/447개 미등록이다. 조사 필드의 일치를 확인한 것이며 전체 DB의 무변경 증명은 아니다. 회원·반응·대화는 조회하지 않았다.

원본5개는 `target/metadata-enrichment-live-20261126/token-required/{collection.json,review.json,review.html,public-snapshot.json,mapping-snapshot.json}`이다. 저장 구조·체크섬·기준 범위를 기존 검증기로 다시 확인했다. 모든 필드는 미승인이고 적용 목록은 비어 있다. 이 실행을 실제84회 수집이나 빈 결과84개로 세지 않는다.

## 개인 터미널 실행 경로

토큰이 현재 에이전트의 실행 환경에 없으므로 `scripts/run_metadata_enrichment_collect.ps1`을 추가했다. 별도의 개인 PowerShell에서 다음 명령을 실행하면 토큰 입력이 가려진다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\Users\jaehyeon\recommend-backend\scripts\run_metadata_enrichment_collect.ps1" -Collect
```

입력은 **TMDB API Read Access Token**이며 OpenAI 평가 키가 아니다. 토큰을 채팅·명령행 인수·파일에 붙여넣지 않는다. 이 명령은 이미 요청한 최대28개/84회 읽기 전용 수집만 실행한다. 키가 없던 프로세스에만 임시 환경변수를 설정하고, 성공/실패 시 finally에서 지우고 SecureString을 폐기한다. 기존 프로세스 토큰이 있으면 그대로 사용하고 값을 보존한다. 부모 터미널의 영구 환경 설정은 바꾸지 않는다.

`-Collect` 없이 실행하면 prepare만 실행하며 키를 요청하지 않는다. 작업 경로는 스크립트 위치로 결정하고 원래 경로로 복원한다. 매번 새 UTC/UUID 폴더를 사용하고 이전 결과를 덮어쓰지 않는다. Windows PowerShell의 native stderr 처리로 종료코드를 잃지 않도록 고정 명령만 cmd에서 병합한다. 인증값은 환경으로만 상속한다. stdout에는 기존 수집기의 비밀 비노출 요약과 결과 경로만 표시한다.

완료 후 출력된 `Result folder` 경로만 채팅에 전달하면 된다. 보조 기록은 `target/metadata-enrichment-live/launcher-last-run.json`에 있으며 mode/exitCode/output/outputPresent/databaseApplied=false만 담는다. 이 파일은 실행 진단용이고 실제 수집·필드승인 증거로 사용하지 않는다. 수집 실패 시 결과를 보존하고 자동 재실행하지 않는다. 실제 자료의 collection/review와 현재 DB 재검증으로 다음 판정을 해야 한다.

## 검사와 남은 작업

새 실행기 검사4개는 PowerShell5.1/7 양쪽에서 성공/비정상종료·가려진 입력·기존 토큰·빈 입력·환경 정리·원래 작업 경로/호출자 설정 보존·공백과&가 있는 경로를 확인한다. 실제 Python/DB/API 대신 합성 `python.cmd`만 사용한다. 첫 검사에서는 프롬프트 호출 기록용 테스트 변수의 스코프 오류로8개 하위 검사가 실패했다. 테스트 변수를 공유 객체로 고쳐4개가 통과했으며 실제 토큰이나 실제 수집을 실행한 오류가 아니다. 수집기 본체와 기존 고정 제안은 수정하지 않았다.

```powershell
python -X utf8 -m unittest discover -s scripts -p test_metadata_collect_launcher.py -v
```

작업본 로그는 `target/metadata-enrichment-live-20261126/source-launcher-tests.log`다. 이전69검사는 이전 기능의 결과로 보존하며 이번 새69검사로 표현하지 않는다. Java/React/실제모델 회귀는 실행하지 않았다.

위개인터미널인증수집/결과전달은완료됐으며초기TOKEN_REQUIRED를완료로덮어쓰지않는다. 다음단일기능은 **필드별출처/중복·null·충돌정책을보존하는메타데이터저장·적용도구의dry-run구현과격리검증**이다. 실제DB적용은별도단계로두고,현재고정자료를자동제품입력으로읽지않는다. 한국어키워드라벨/의미중심성검토,작품추가,검색/순위/대화변경과품질평가는계속별도다.

## 최종 동기화와 보존

Eclipse 추가 지침/초기 차이를 확인한 뒤 실행기/검사2개·문서4개·초기TOKEN_REQUIRED5자료·실제원본5자료·요약/기술검토3자료,총19파일을 선택 동기화했다. 기존3문서는 실행본 `target/metadata-enrichment-live-sync-20261126`에 초기 백업했다. 작업본의 같은 초기3문서는 `target/metadata-enrichment-live-20261126/before`에 보존했다. 19파일/초기3백업 해시가 일치한다.

새 실행기4검사는 양쪽에서 각각 통과했다. Eclipse에서 최초토큰부재자료를 실제DB로 재생한5자료도 TOKEN_REQUIRED로 같았다. 이후 사용자실제수집 원본을 Eclipse의현재DB로 다시대조한 `target/metadata-enrichment-live-20261126/eclipse-live-replay`는READY_FOR_MANUAL_REVIEW였고,원본/작업본재생/실행본재생의5산출물 해시가모두일치했다. 이재생들은추가TMDB호출을하지않았다. 문서최종결과반영에서는변경없는검사를반복하지않고해시/보존만다시확인했다.

기존파일은작업본361개중358개·실행본355개중352개가그대로이며,기존변경은진행/로드맵/고도화계획3문서뿐이다. 새파일은실행기·검사·이번보고서3개다. 제품/설정/기존수집기/기존검사/원래질문·정답/평가기준/과거결과/고정28개제안을보존했다. 실행본Content/User주석등무관한차이는덮어쓰지않았다. `target/metadata-enrichment-live-20261126/{source-before,source-after,eclipse-before,eclipse-after,sync,verification}.json`,양쪽실행기검사로그/재생로그가증거다. 실제수집84회는사용자개인터미널에서실행했으며에이전트추가데이터호출0/LLM0/DB쓰기0이다.

**Eclipse F5만 필요하며 백엔드/IDE 재시작은 필요 없다.** 앱을 재시작하지 않았고 파일 동기화와 데이터 적용을 구분했다.

2단계의 사람0/2·의미UNJUDGED·별도검증24턴 미실행·사전 독립풀/반복/실환경·오류3/36과 누적 예약$1.1838605/승인총$3·알려진 사용량 추정$0.0767765·실청구UNKNOWN은 그대로다. 이번에는 유료 모델 호출이 없다.
