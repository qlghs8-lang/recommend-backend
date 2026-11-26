# 시간 조건·후속 질문의 실행 환경 / React 수용

2026-11-24 21:12 KST 완료. 실제 공개 카탈로그를 복제한 **격리 MySQL + Eclipse 실행본 + 실제 React 화면**에서 시간 조건과 저장·실패 처리를 확인했다. AI는 고정 구현이며 이번 제공자 호출은 0회다. 실제 모델 언어 해석 점수와 합산하지 않는다.

## 범위와 데이터 보호

- 원본 앱 DB에서는 공개 작품 468개와 시간 출처 21개의 제한된 필드만 읽었다. 실제 회원·반응·채팅 행은 읽거나 쓰지 않았다.
- 공개 지문 `5f5559a3eea157209d4e114198086ac6e4233a6d146b5c2628421a845c7e4f27`이 검사 전후 일치한다. 알려진 시간은 영화 20개·TV 1개이며 나머지 447개는 미등록이다.
- 새로 소유한 임시 스키마에 합성 회원·대화를 생성했다. 앱은 루프백 임의 포트, 실제 JWT/HTTP와 OSIV를 사용했다. React는 기존 CORS에 맞춰 localhost:3000에서 임시 실행했다.
- 생성 중 줄거리 변경은 임시 스키마에만 주입했다. 검사 종료 후 앱/풀 종료와 임시 스키마 삭제를 확인했다. 합성 UI 계정 로그아웃, 임시 탭 닫기, 생성한 React 프로세스의 경로/시작 시각 확인 후 종료와 포트 폐쇄도 확인했다. 사용자 앱은 재시작하지 않았다.

## 결과

| 검사 | 관찰 / 판정 |
|---|---|
| 2시간 이내 영화 → 그중 90분 이하 → 가장 짧은 작품 | 81분·117분 → 81분 → 81분. 후속 조건과 참조 유지 |
| 회당 25분 이하 TV | 빈란드 사가 ID104, 대표값 25분·회차별 차이 안내 |
| 회당 24분 이하 TV | 확인 가능한 작품 없음. 미등록 시간을 조건 충족으로 간주하지 않음 |
| TV 전체 시간 / 모든 회차 상한 | 확인·보장 불가 안내. 대표값을 전체/모든 회차의 증거로 사용하지 않음 |
| 짧은 TV | 회당/전체 시간 단위 확인 질문 |
| 이전 조건 초기화 후 2시간 미만 영화 | 영화 조건으로 교체, 서버 상한 119분, 화면 81분 작품 |
| 추천 이유 / 상세+일반 설명 | 정상 생성·저장. 재시도 상세에 변경된 최신 줄거리 표시 |
| 생성 중 변경: 단일·묶음 이유 / 상세 복합 요청 | HTTP 모두 502·AI_INVALID_RESPONSE·추가 저장 0. 같은 질문 재시도 200·메시지 +2 |
| 화면에서 단일 이유·복합 상세 실패 | 오류 안내, 전송 전 초안 유지, 새로고침 후 실패한 턴 없음. 재입력·재시도 성공 |
| 정상 대화 복원 | UI 성공 12턴·실패 2회. 새로고침 후 24개 메시지, 성공 후 입력 비움. 서버도 해당 합성 회원의 저장 24개를 독립 확인 |
| 인증 / 소유권 | 미인증 401, 다른 합성 회원의 방 404 |

HTTP 자동 검사와 UI 확인을 구분한다. UI 실패 2회는 방금 구현한 최신성 거절을 의도적으로 유발한 502이며, 이전 간헐적 503의 재현이나 원인 해결을 뜻하지 않는다.

## 변경과 검증 명령

새 `RuntimeBrowserAcceptanceTest`는 기존 제한된 DB 복사·HTTP 도우미를 재사용한다. 기존 `RuntimeRecommendationAcceptanceTest`의 `copyPublic`, `Api`만 같은 패키지에서 접근 가능하게 바꿨다. 기존 정답과 검사 내용, 제품 Java 42개, 평가 JSON 14개와 프런트 소스는 보존했다.

작업본 HTTP 검사:

```powershell
.\mvnw.cmd -o '-Dtest=RuntimeBrowserAcceptanceTest' '-DchatRuntimeBrowser=true' test
```

최초 실행은 복합 변경 주입이 앞선 검사와 같은 줄거리를 다시 써 실제 변경이 없어서 실패했다. 질문별 다른 값을 쓰도록 **검사 자료만** 수정했으며 최초 로그/보고서를 보존했다. 수정 후 JUnit 1개 통과, 브라우저는 이 실행에서 미실행이다.

Eclipse 실행본 HTTP + UI 및 기존 시간 수용:

```powershell
.\mvnw.cmd -o '-Dtest=RuntimeBrowserAcceptanceTest,RuntimeRecommendationAcceptanceTest' '-DchatRuntimeBrowser=true' '-DchatRuntimeBrowserUi=true' '-DchatRuntimeAcceptance=true' test
```

JUnit **2개 통과 / 실패·오류·건너뜀 0**. UI 모드는 기존 handshake 파일을 보존 이동한 뒤 실행하며, 900초 내 실제 화면 확인 후 완료 파일을 전달한다. 완료 파일만으로 화면 자동 검사를 했다고 주장하지 않는다. 이번 실제 UI 관찰은 별도 JSON과 캡처에 보존했다. 기존 시간 수용의 내부 21개 검사는 JUnit 수에 더하지 않는다.

React `ChatPage.test.js`, `chatApi.test.js`: **2개 suite, 28개 통과**. 전체 Java 회귀는 이번에 재실행하지 않았다. 직전 전체 469통과/60건너뜀은 과거 결과로 유지한다.

## 증거와 동기화

- 작업본 `target/runtime-browser-acceptance/`: `mysql-http.log`(최초 실패), `mysql-http-fixed.log`, `report-20261124120516471710400.json`(HTTP 통과), `frontend-tests.log`, `ui-observations.json`, `ui-tv-runtime.jpg`, `ui-rejected-stale-rationale.jpg`, `ui-success-after-retry.jpg`, `cleanup.json`.
- Eclipse `target/runtime-browser-acceptance/`: `mysql-browser.log`, `report-20261124121201323444600.json`(HTTP/UI PASS, 저장24개·원본 보존·스키마 정리 확인).
- 선택 6파일: 테스트 2개·문서 4개. 초기 지침/차이/해시를 확인하고 덮어쓴 4파일은 실행본 `target/runtime-browser-acceptance-sync-20261124`에 백업한다. 복사본/원본 및 백업 초기 해시는 `eclipse-before.json`, `eclipse-after-*.json`, `final-manifest.json`으로 확인한다. 설정과 무관한 실행본 차이는 보존한다.
- 이번 제품/프런트 코드 변경은 없다. Eclipse F5로 테스트·문서만 갱신하면 되며 이번 변경 때문에 사용자 백엔드를 재시작할 필요는 없다. 직전 Java 수정 반영을 위한 재실행 여부는 사용자가 확인해야 한다.

## 남은 범위 / 다음 단일 작업

다음 요청에서는 **최종 회귀·평가 결과 통합 정리** 한 단계를 진행한다. 이번 고정 AI의 실제 DB/브라우저 통과와 이전 실제 모델 시간 8/8을 구분하고, 완료 조건·이전 실패·남은 한계를 정리한다. 현재 품질 개선 묶음의 예상 잔여는 약 1단계이며 새 결함 발견 시 달라질 수 있다.

실제 사용자 + 실제 제공자 + MySQL/브라우저를 한 번에 연결한 수용, 임의 표현/반복의 일반화, 간헐 503 원인 규명은 미완료다. 추가 시간 447개·TV 빈 배열 처리·TMDB B~D 확장·Phase7/선택 벡터 검색도 별도 범위다. 이 문서의 통과를 전체 로드맵 완료로 확대하지 않는다.
