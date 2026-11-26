# 추천 출처의 MySQL·HTTP·화면 수용 — 2026-11-24

**실제 로컬 MySQL 컬럼 적용과 합성 환경의 JWT·HTTP·브라우저 검증 완료.** 제품 코드/프런트엔드 코드는 바꾸지 않았다. AI만 고정 합성 구현으로 대체했고 실제 MySQL, Spring 서비스/컨트롤러, JWT 필터, React 화면을 사용했다. 앞 단계 실제 모델/H2 결과와 이번 전송·저장·화면 결과를 분리한다. 이번 유료 호출은0회다.

## 실제 실행 DB 적용

실행본 설정의 로컬 MySQL은 실행 중이었으나 백엔드/프런트엔드는 시작 시 실행 중이 아니었다. 최초 읽기 전용 information_schema 조사에서 chat_messages.text는 LONGTEXT,content_references는 VARCHAR였고 recommendation_provenance는 없었다.

기존 채팅 테이블/컬럼이 예상과 일치함을 확인한 뒤 `ALTER TABLE chat_messages ADD COLUMN recommendation_provenance LONGTEXT NULL, ALGORITHM=INSTANT`만 실행했다. 세션 metadata lock 대기는5초,쿼리 제한8초이며 테이블 복사 방식으로 자동 대체하지 않는다. 성공 후 nullable LONGTEXT와 기존 두 컬럼의 정의 유지를 재확인했다. 기존 회원·반응·대화 행/본문은 조회하거나 갱신하지 않았고 backfill도 하지 않았다. 명시 별도 플래그 없이 이 DDL은 실행되지 않는다.

설정 값은 기존 실행본의 서버 설정을 테스트 프로세스 내부에서만 사용했다. URL/비밀번호/토큰은 보고서에 담지 않는다. 실제 앱 DB에는 이 nullable 컬럼 추가 이외 쓰기가 없다. 기존 DB 전체나 개인 기록을 덤프하지 않았다.

## 격리와 검사 범위

매 실행마다 `recommend_provenance_acceptance_<12hex>`라는 새 합성 스키마를 생성한다. 기존 스키마 존재/이름/앱 DB와의 불일치를 검사한 뒤 생성하며 자기 실행이 만든 스키마만 삭제한다. Hibernate create는 이 새 스키마에만 적용한다. 별도 합성 기존 행을 넣고 컬럼 추가 후 본문과 NULL이 유지되는지 검사한 뒤 JPA를 시작했다.

테스트 서버는127.0.0.1 임의 포트에만 바인딩한다. JWT 서명키·로그인 계정·비밀번호는 이 환경 전용 합성 값이다. 인증을 우회하지 않고 실제 /login 및 보안 필터를 통과한다. 모델 대체는 테스트 클래스의 Primary AiClient로 한정되며 제품 프롬프트·기본 모델·토큰/시간 한도를 바꾸지 않는다. 브라우저는 기존 프런트엔드를 임시 프로세스로 띄워 이 서버에 연결했다. 설정 파일이나 제품 API는 수정하지 않았다.

| 경로 | 확인한 결과 |
| --- | --- |
| JWT·소유권 | 토큰 없는 요청401,다른 합성 계정의 같은 방 GET/POST404 |
| HTTP·MySQL | 새 방 생성201,추천→전체 이유→첫 작품 반복 이유 저장,메타데이터 LONGTEXT/nullable 확인 |
| 반응 변경 | 실제 DISLIKE API/화면으로 LIKE를 대체. 기존 추천 점수3 유지,새 추천은 긍정 기록 부족으로 재계산 |
| 비노출 | 응답 JSON에 내부 provenance/positiveGenres/positiveHistory 없음. 다음 분류 문맥에 당시 점수·반응 기준 작품명 없음 |
| 오류 원자성 | 합성504 응답에서 DB 메시지 수 유지,부분 턴 미저장 |
| 실제 브라우저 | 로그인,새 대화,추천,전체·순서 이유,새로고침 완전 복원,화면 반응 변경,과거 점수/새 기준 구분 통과 |
| 오류 화면 | 시간 초과 안내,초안 유지,기록 불변 및 새로고침 후 실패 턴 미저장 확인 |

UI 흐름은 새 방의 성공5턴과 실패1회를 검사했다. HTTP 자동 경로도 별도 방에서 성공5턴 및 실패/권한 요청을 검사했다. 테스트 종료 전 합성 계정을 정상 로그아웃하고 브라우저 탭을 닫았다. 임시 서버/프런트엔드는 종료됐으며 생성한 합성 스키마도 제거했다. 최초 실패 실행과 브라우저 실행 스키마 두 개의 제거는 information_schema count0으로 다시 확인했다. 마지막 HTTP 단독 실행도 context 종료 후 자신이 만든 스키마의 DROP 성공을 포함해 BUILD SUCCESS로 종료했다. 사용자 Eclipse 앱은 시작/재시작하지 않았다.

## 재현과 결과

`RecommendationDeploymentAcceptanceTest`는 기본 실행에서 세 메서드 모두 건너뛴다. 서버 설정/DB 접근도 opt-in 실행에서만 수행한다.

- 컬럼·기록된 스키마 정리 조사: `mvnw.cmd test -Dtest=RecommendationDeploymentAcceptanceTest#applicationSchemaReadOnly -DchatDeploymentAcceptance=true`
- nullable 컬럼 하나 추가(없을 때만): `mvnw.cmd test -Dtest=RecommendationDeploymentAcceptanceTest#applyOnlyMissingNullableApplicationColumn -DchatDeploymentAcceptance=true -DchatApplyProvenanceColumn=true`
- 격리 MySQL·JWT·HTTP: `mvnw.cmd test -Dtest=RecommendationDeploymentAcceptanceTest#actualMySqlJwtHttpAndOptionalBrowser -DchatDeploymentAcceptance=true`
- 위 HTTP 실행에 `-DchatDeploymentBrowser=true`를 추가하면 target/deployment-acceptance/browser-ready.json의 임의 포트에 프런트엔드를 연결하고 최대15분 동안 화면 검증 완료 파일을 기다린다. 기존 handshake 파일이 있으면 거절한다. 별도 실행 전에 이전 파일을 보존/정리해야 하며 완료 기록은 실제 UI 관찰 후 작성한다. 기본 HTTP 모드에는 브라우저 대기가 없다.

작업본 전체520개:463통과/57조건부 건너뜀/실패·오류0. Eclipse 챗봇425개:403통과/22조건부 건너뜀/실패·오류0. 기존 프런트엔드 ChatPage/chatApi 두 suite28개 통과. 제품41파일·기존 테스트58개·평가 JSON13개는 해시 그대로다.

최초 HTTP 작성에서는 LIKE를 토글로 잘못 가정해 새 추천 기준 검사가 실패했다. 실제 계약이 LIKE upsert이고 DISLIKE가 LIKE를 대체함을 확인해 테스트를 고쳤다. 제품 버그 수정이나 정답 완화가 아니다. 첫 실패 보고서는 deployment-initial-failed.json/초기 실행 로그에 보존한다. 브라우저 실행은 JUnit1통과와 정제 보고서/화면 증거를 확보했고,재개 후 HTTP 단독 실행으로 Maven 정상 종료도 별도로 확보했다.

현재 보고서/로그는 target/deployment-acceptance에 있다. application-schema-before-migration.json,application-column-migration.json,application-schema.json이 실제 컬럼 전후/정리 증거다. browser-deployment-report.json과 browser-complete.json은 실제 화면 관찰, deployment-report.json과 mysql-http-final.log는 마지막 HTTP 단독 통과다. 화면 증거는 [당시 근거](../target/deployment-acceptance/browser-provenance.png)와 [오류 시 초안](../target/deployment-acceptance/browser-error.png). 파일 해시는 evidence-hashes.json/final-manifest.json에 기록한다.

## 실행본과 남은 범위

새 테스트1개·문서5개만 Eclipse 실행본에 동기화했다. 덮어쓴 문서4개는 실행본 target/deployment-acceptance-sync-20261124에 백업하고 원본/복사본 SHA256을 검증했다. 설정·기존 제품 소스·무관한 변경은 보존했다. 앱은 처음부터 꺼져 있었고 여기서 시작하지 않았으므로 Eclipse F5 후 평소처럼 백엔드를 실행하면 된다. 기존 null 기록의 당시 근거는 역추출하지 않으므로 새 추천부터 확인한다.

실제 사용자 데이터/기존 Eclipse 프로세스와 실제 유료 모델을 한 번에 연결한 운영 수용,다양한 장기 대화/복합 요청 전체,해석 문장 전체의 사실성은 이번 검사 범위가 아니다. 간헐503은 이번 고정 제공자 검사로 진단하거나 해결하지 않았으며 안전한 AI_PROVIDER_FAILURE 이벤트가 있으면 우선 조사한다.

기본 추천 출처의 자동/모델/H2/MySQL/HTTP/화면 검사 묶음은 끝났다. 다음 단일 작업은 TMDB A의 기존 시간 메타데이터·출처 매핑 등록률을 읽기 전용으로 조사하고 보강 dry-run 범위를 정하는 것이다. 카탈로그468개 유지 결정을 존중하고 이번에 수집·추가·삭제·메타데이터 갱신을 시작하지 않았다. TMDB B~D,스트리밍·선택 벡터 검색은 별도이며 Phase7은 자동 시작하지 않는다.
