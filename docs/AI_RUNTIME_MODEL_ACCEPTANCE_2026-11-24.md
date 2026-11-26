# 실제 모델 시간 조건·후속 대화 수용 — 2026-11-24

현재 상태는 **실제 모델 고정8턴 수용 PASS(8/8)**다. 사용자가 올바른 OpenAI API 키로 재실행한 gpt-6-luna 결과와 Maven 정상 종료를 확인했다. 이전401 실패 두 번은 아래 이력으로 보존한다. 이번 결과 검토에서 추가 제공자 호출은 하지 않았다. 이 작은 범위의 통과를 전체 문장 품질이나 반복 안정성으로 확대하지 않는다.

## 범위와 판정

독립된 `runtime-model-v1.json`의 5개 대화/8턴을 고정했다. 기존 13개 평가 JSON과 제품 Java는 변경하지 않았다. 각 대화는 합성 계정의 새 방에서 시작하고, 같은 대화의 후속 질문은 실제 저장 이력을 사용한다.

| 검사 | 기대 동작 |
|---|---|
| 90분 이하 영화 | MOVIE + MINUTES_MAX 90, 시간 미등록 제외 |
| 그중 가장 짧은 영화 | 직전 공개 ID 범위·90분 조건 유지, 최단값 선택 |
| 이전 조건 초기화, 2시간 미만 | 119분 상한으로 교체, 이전 참조·정렬 제거 |
| 한 회 25분 이하 시리즈 | TV + EPISODE_MINUTES_MAX 25, 대표값·회차별 차이 표시 |
| 회당 제한 24분으로 변경 | 25→24 교체, 현 카탈로그에서는 결과 없음·생성 없음 |
| 전체 시청 시간 두 시간 이하 | UNVERIFIABLE, 전체 분량 추정·작품 추천 없음 |
| 모든 회차 25분 이하 보장 | UNVERIFIABLE, 대표값으로 전체 회차 보장 금지 |
| 짧게 볼 시리즈 | TV_DURATION_UNIT, 회당/전체 분량 재질문 |

실제 모델 경로에서는 분류와 필요한 선택·근거 생성/도구 호출 모두 기존 OpenAiClient를 사용한다. 모델 결과를 정답으로 치환하지 않는다. 요청 종류·조건 종류/값을 독립된 기대 결과와 비교하며, 근거 원문은 기존 서버 검증을 통과해야 한다. 수치·출처·참조가 추가되거나 누락되면 실패한다. 실제 후보 ID는 공개 원본 목록을 직접 필터/정렬한 결과와 비교하고 최종 ID·대표값 표시·저장 메시지 증가를 확인한다. 첫 실패 뒤 나머지는 NOT_RUN이며 통과로 계산하지 않는다.

이 작은 평가의 PASS는 전체 품질 게이트나 임의 문장·다회 반복 안정성의 보장이 아니다. 원본59/61·수정 정책61/61·기존 저장 대화28/28 점수와 합산하지 않는다. 실제 모델 결과가 실패하면 이 질문·정답을 보존하고 진단한다.

## 데이터·예산

- 설정된 로컬 MySQL의 공개 작품10필드와 시간 출처4필드만 읽는다. 기존 읽기 전용 Database 도우미의 패키지 가시성만 변경해 재사용한다.
- 카탈로그468개, 알려진 영화20개/TV1개와 고정 검토 해시를 확인한다. 원본 회원·반응·대화 조회 및 DB 쓰기는 없다.
- 쓰기는 DataJpaTest가 생성한 고유 H2 메모리 DB로 제한한다. JDBC 주소가 `jdbc:h2:mem:`인지 확인한 뒤 복사한다. 합성 회원·방을 정리하며 원본 공개 지문을 전후 비교한다.
- 앱DB/제공자 키 설정을 복사하거나 저장된 제공자 키를 검색하지 않는다. 모델은 `CHAT_EVAL_MODEL`로 명시 선택하며 자동 대체하지 않는다.
- 8턴 × 턴 출력800토큰, 턴20초의 제품 기본 한도를 유지한다. 실제 SDK 요청 전 최대28회·요청별120,000문자·누적600,000문자·보수적 출력 예약22,400토큰을 검사한다. 실패/사용량 없는 요청도 예약을 소모한다. 정상 응답의 턴 출력 합계 상한은6,400토큰이다.
- SDK 재시도0,180초 이후 새 요청 시작 금지. 이미 시작한 요청은 기존 턴 deadline의 적용을 받으므로 전체 실행 시간이 정확히180초에 종료된다는 의미는 아니다. 문자 상한은 SDK 요청 객체 표현 기준이며 전송 바이트/토큰 한도와 같지는 않다. 금액 상한을 직접 계산하는 도구는 아니며 운영자가 선택한 모델의 요금이 적용된다.
- SDK 로깅 OFF/store(false)를 유지한다. 보고서는 질문 묶음 해시·파싱된 계획·공개 ID·사용량·고정 오류 코드만 기록한다. 헤더·키·원시 SDK 오류·기존 개인 대화는 기록하지 않는다.

## 개인 터미널 실행

백엔드 작업 폴더에서 PowerShell로 실행한다. 실행 중인 사용자 앱은 사용할 필요가 없다.

```powershell
Set-Location C:\Users\jaehyeon\recommend-backend
# 값 없이 설정 유무와 고정 예산만 확인; DB/모델 호출 없음
.\scripts\run_runtime_model_acceptance.ps1
# 실제 공개 카탈로그 + 고정 AI 예행 실행; 모델 호출 없음
.\scripts\run_runtime_model_acceptance.ps1 -Rehearse
# 실제 모델; 키가 없으면 숨김 입력, 모델명이 없으면 별도 입력
.\scripts\run_runtime_model_acceptance.ps1 -Run
```

키는 **개인 터미널의 숨김 입력에만** 넣고 채팅에 붙여넣지 않는다. 기존 비교 모델은 과거 보고서의 `gpt-6-luna`이며, 같은 모델을 사용할지 명시 선택한다. 새로 입력한 키/모델 환경변수는 실행 종료·실패 시 제거하고 SecureString을 Dispose한다. 이미 주입된 평가 환경변수는 그대로 보존한다. 키를 Maven 명령 인자나 파일에 쓰지 않는다.

`-Run`은 합성 회귀·예산 검사와 실제 모델8턴을 실행한다. 실제 모델 옵트인이 설정됐지만 환경이 없으면 SETUP_REQUIRED/요청0 보고서를 쓰고 테스트를 실패시킨다. 건너뛰거나 통과로 처리하지 않는다. 기본 오프라인 실행은 실제 모델 검사를 조건부로 건너뛴다.

결과는 `target/runtime-model-acceptance/live-*.json`과 실행 로그에서 확인한다. 8개 모두 PASS이고 전체 Maven 실행도 성공해야 이번 수용이 통과다. `launcher-last-run.json`의 qualityAccepted는 실제 Run 모드와 정상 종료에만 true다. Rehearse의 qualityGate는 항상 REAL_MODEL_REQUIRED다.

## 최초 도구 준비 시 실행 근거

- 도구 준비 당시 사전 점검: 평가 키/모델 모두 미설정, SETUP_REQUIRED. 이후 사용자 실제 실행의401 실패는 아래 후속 기록을 따른다.
- 합성 카탈로그8개/실제 서비스·저장 이력8턴 통과.
- 실제 공개468개/시간 출처21개 예행8턴 통과. 원본 전후 지문 `5f5559a3eea157209d4e114198086ac6e4233a6d146b5c2628421a845c7e4f27`로 동일.
- 새 전용 JUnit7개 중6통과/실제 모델1조건부 건너뜀. 포함된 오라클 검사는119/120 경계·직전 목록 누락·영화/회당 단위 혼동을 거절한다. 별도 예산3개는 실패 요청 예약·입력 크기·시간/토큰 한도를 검사한다. 내부8턴과 JUnit 수를 합산하지 않는다.
- 작업본 전체528개:468통과/60조건부 건너뜀/실패·오류0.
- Eclipse 챗봇433개:409통과/24조건부 건너뜀/실패·오류0. 실제 공개 자료 예행을 포함하며 양쪽 자료/질문 해시가 같다. 최종 동기화10파일의 해시 일치와 기존4파일 백업을 확인한다. 제품 변경이 없어 앱 재시작은 필요 없다.
- 초기 샌드박스 실행은 Java attach 권한 오류로 정지했다. 해당 검사 프로세스만 정리하고 정상 권한으로 재실행했다. 최초 정상 실행은 테스트용 요청 제한 설정 누락으로7번째턴 AI_BUSY, 다음 실제 자료 예행은 고정 응답의160자 초과 인용으로 실패했다. 테스트 설정과 고정 인용을 수정했으며 제품 코드·질문·정답은 변경하지 않았다. 최초 실패 보고서/로그도 보존한다.

작업본 근거: `target/runtime-model-acceptance/{readiness,launcher-last-run,eclipse-before,eclipse-after-tests,eclipse-after-docs,final-manifest}.json`, `rehearsal-fixed.log`, `full-offline.log`, `scripted-*.json`, `before/`. 실제 자료 통과 보고서는 `scripted-20261124112039956025900.json`이다.

## 도구 준비 당시 다음 작업

개인 터미널의 -Run 결과를 받아 같은8턴의 실제 모델 수용을 완료한다. 키 미설정 상태를 실제 모델 통과로 닫지 않는다. 새 결과가 오류/의미 불일치이면 제공자 안전 진단과 기존 정답을 유지해 원인을 먼저 조사한다. 브라우저/운영 통합·간헐503·다른 재조회 경로·447개 시간 미등록·TV 빈 배열·TMDB B~D·Phase7은 이번 범위 밖이다.

평가 설계 참고: [OpenAI 공식 평가 모범 사례](https://developers.openai.com/api/docs/guides/evaluation-best-practices)의 작업별 목표·고정 자료·명시 기준·실패 보존 원칙을 적용했다. 별도 Evals 플랫폼/API는 사용하지 않는다.

## Windows PowerShell 실행기 수정과 실제 실행 결과 — 20:37 후속

사용자 화면에서 OpenJDK class-sharing 경고가 NativeCommandError로 처리돼 실행기가 중단됐다. 기존 실행기는 PowerShell에서 stderr를 합친 뒤 Tee-Object로 넘겼고 ErrorActionPreference=Stop이었다. Windows PowerShell5.1에서 이 경고가 종료 오류가 됐다. 경고가 없는 성공과 실제 테스트 실패를 모두 구분하도록 고정 Maven 명령의 stderr를 cmd 내부에서 stdout으로 합친다. 작업 폴더를 먼저 선택해 공백·&가 있는 경로도 지원하며,키·모델은 명령 문자열에 넣지 않는다. PS7의 native 오류 선호 설정은 실행기 스코프 안에서만 끄고 Maven 종료코드로 결과를 판정한다.

가짜 Maven 회귀3개×5.1/7=6호스트 검사 각각 양쪽 체크아웃 통과. 경고 출력·실제 실패코드3·성공/실패 후 임시 키/모델 제거·호출자 경로/선호 설정 복원을 확인했다. 가짜 실행만 사용하며 실제 키/DB/네트워크는 없다. 공백과&를 포함한 임시 경로를 사용하고 검증된 fixture 하위만 정리한다. 테스트 초기의 모듈 경로/로그 인코딩 차이와 인용 오류는 실패 증거를 보존하고 하네스 및 명령 구성을 수정했다. Windows PowerShell의 실제 카탈로그 -Rehearse도 종료0/JUnit6통과·실제모델1건너뜀,고정8턴 통과다. Java 변경이 없어 전체 Java 회귀는 이번에 반복하지 않았다.

사용자 원래 실행이 남긴 live-20261124113058222544100.json은 **gpt-6-luna / 요청 시도1 / 첫 분류 AI_UNAVAILABLE /7턴 NOT_RUN/저장증가0**다. 당시 surefire 안전 로그는 다음과 같다(원시 SDK 응답이나 키는 저장하지 않았다).

```text
AI_PROVIDER_FAILURE stage=CLASSIFY category=AUTH_OR_ACCESS httpStatus=401 code=OTHER parameter=OTHER
```

401은 이번 평가 요청의 인증 실패 근거이며 예전 간헐503의 동일 원인까지 입증하지 않는다. code=OTHER이므로 만료·오타·프로젝트 제한 등 세부 원인은 확정하지 않는다. 토큰 사용량이 보고되지 않아0토큰 기록을 과금0의 증거로 해석하지 않는다. 모델은 gpt-6-luna 그대로,사용할 프로젝트의 유효한 OpenAI API 키를 개인 터미널 숨김 입력으로 넣어 같은 -Run을 재실행한다. 채팅으로 키를 보내지 않는다. 추가 유료 요청은 자동 실행하지 않았다.

근거는 target/runtime-launcher-compatibility/{verification,eclipse-before,eclipse-after-tests,eclipse-after-docs,final-manifest}.json,launcher-tests-pass.log,windows-powershell-rehearsal.log,before/다. 기존 사용자 실패 보고서는 보존했다. Windows PowerShell의 Tee 로그는 UTF-16,PS7은 UTF-8일 수 있으므로 도구는 BOM을 확인해 읽는다.

참고: [Microsoft 공식 선호 변수 설명](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_preference_variables?view=powershell-7)의 native stderr/7.2 이후 변경 및 스코프 규칙. 이번 차이는 두 실제 호스트의 회귀로 확인했다.

### 20:40 재실행 및 사용자 확인

수정된 실행기는 종료코드1과 qualityAccepted=false를 정상 기록했다. 새 live-20261124114027057444300.json도 요청시도1/첫 분류AI_UNAVAILABLE/7턴NOT_RUN/저장증가0이며 안전 로그 CLASSIFY/AUTH_OR_ACCESS/401/OTHER다. 키·모델 환경이 입력 전 모두 미설정이므로 기존 평가 환경변수를 재사용한 실행은 아니다.

사용자가 숨김 입력의 발급처가 **TMDB**라고 확인했다. 이번 평가의 OpenAI 인증 입력에 다른 서비스의 토큰을 넣은 것이 확인된 원인이다. 실제 값은 받거나 확인하지 않았다. TMDB 토큰은 메타데이터 수집용이고 이번 gpt-6-luna 호출에는 [OpenAI API 키 페이지](https://platform.openai.com/api-keys)에서 발급한 별도 키가 필요하다. 모델은 그대로 유지하고 개인 터미널에서 같은 -Run을 다시 실행한다. 키는 숨김 입력에만 넣는다. 인증 수정 후 모델 의미 품질은 여전히 미검증이다. 추가 호출이나 코드 변경은 하지 않았다.

이번 확인은 사용자 진술과 안전 진단에 근거하며 예전 간헐503까지 같은 원인으로 확정하지 않는다. 원래 실패 자료 두 개는 그대로 보존했다. target/runtime-auth-diagnostic-2040/verification.json에 새 보고서 해시와 고정 안전 진단을 기록했다.

## 20:43 실제 모델 수용 통과

사용자 실행 live-20261124114350324319400.json은 realModel=true/model=gpt-6-luna/qualityGate=PASS이며 고정5대화/8턴 모두 PASS다. 실행기 live/exitCode0/qualityAccepted=true와 JUnit 보고서를 함께 확인했다. corpus 해시0a73febb7e8bda47e69ec9757638f9c3d25e9cf9954b55285d61f4a69ed11b0a는 사전 고정 자료와 같다. 실패 후 질문·정답을 바꾸지 않았다.

| 검사 ID | 검증된 결과 |
|---|---|
| movie-90 | 공개 ID53,43 선택,90분 상한 |
| movie-shortest-followup | 직전 목록의 최단 ID53 선택,상한·참조 유지 |
| movie-reset-strict-119 | 초기화 후119분 상한,ID34,49,37 선택 |
| episode-25 | 대표 회당25분인 ID104,대표값/회차별 차이 표시 |
| episode-replace-24 | 결과 없음,추천 생성0 |
| series-total-unverifiable | 전체 분량 보장 불가,추천 생성0 |
| every-episode-unverifiable | 모든 회차 길이 보장 불가,추천 생성0 |
| ambiguous-tv-unit | 회당/전체 분량 재질문,추천 생성0 |

제공자 요청12회는 분류8+추천 생성4다. 보고된 입력63,516/출력3,006토큰,보수적 출력 예약6,745/요청객체 문자248,570이다. 각 턴 출력은800이하,최대 측정 응답9,981ms로20초 안이다. 이번 실행에서는 추가 도구 왕복 없이 선택/근거 생성이 끝났으므로 실제 모델의 모든 도구 선택 경로까지 통과한 결과로 해석하지 않는다. 이전 고정 서버 도구 계약 결과는 별도 유지한다.

전용 JUnit7개 중6통과/실패·오류0/1조건부 건너뜀이다. 건너뛴 메서드는 actualPublicCatalogRehearsalIsNotModelQualityAcceptance이며 실제 모델 검사는 실행/통과했다. 내부8턴과 JUnit 수를 합산하지 않는다. 공개468개/시간 출처21개와 전후 원본 지문은 이전 예행과 같다. 원본 앱DB 회원·반응·대화 조회/쓰기0,합성 H2 저장 이력으로 후속 대화/참조를 검사했다.

이번 마감은 보고서/종료코드/JUnit/해시를 읽어 검증하는 문서 작업이다. 추가 유료 호출·DB 호출·제품/테스트/정답 변경·회귀 재실행0이다. 이전 두401 보고서와 실행기 호환성 실패 근거를 보존했다. 전체 원본59/61·수정 정책61/61·기존 저장 대화28/28은 재측정/합산하지 않는다. 임의 표현·반복 안정성·브라우저·운영 통합·다른 재조회 경로·447개 시간 미등록·TV 빈 배열·TMDB B~D·Phase7은 미완료다.

다음 단일 작업은 상세·추천 이유 경로에서 생성 중 DB 변경을 재조회가 감지하는지 제한된 재현으로 점검하는 것이다. 이전 일반 추천 스냅샷 수정의 보장을 다른 경로로 확대하지 않는다. 새 요청 전에는 시작하지 않는다.

근거: target/runtime-model-live-closure/{verification,eclipse-before,eclipse-after-docs,final-manifest}.json,before/. 사용자 통과 보고서 SHA256은81b7e8b5ff4f8d9d9244a9a4e507abafc78418d7649cb9902a312d5b0b7a831b다.
