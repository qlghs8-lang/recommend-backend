# 채팅 추천 작품 상세 모달 — 2026-11-26

AI 답변 아래 작품 카드를 누르면 메인 화면과 같은 상세 모달이 열리며 최신 작품 정보와 좋아요·싫어요·찜을 확인/변경할 수 있다. 사용자 추가 요청인 독립 UI 기능이며 메타데이터 승인/활성화 작업은 시작하지 않았다.

## 구현과 계약

- ConversationDtos.Message에 `contents` 배열을 추가했다. 카드 필드는 `id`, `title`, `type`, `posterUrl`이다. 기존 메시지 필드와 페이징, stateless ChatResponse의 공개 계약은 유지한다.
- ChatHistoryStore는 방 소유권 확인 후 ChatMessage에 이미 저장된 서버 검증 참조만 사용한다. 답변 텍스트나 비공개 provenance를 파싱하지 않는다. 한 페이지 최대 40개 메시지/각 최대 5개 ID를 한 번의 카탈로그 조회로 처리하고 추천 순서를 보존한다. 사용자 메시지·손상된 참조·없는 작품·빈 제목은 제외한다. 원문 답변은 보존한다.
- 저장된 대화와 새 전송 응답 모두 현재 Content에서 카드를 구성한다. 유효한 저장 ID가 없는 과거 답변에는 카드를 만들지 않는다. 제목 변경 시 카드/상세는 현재 이름을 사용하며 과거 답변 문구는 그대로다.
- ContentDetailModal을 추출해 HomePage와 ChatPage가 공유한다. 메인의 기존 정보/추천 이유와 노출·클릭 기록 경로를 유지한다. Portal과 전용 CSS로 채팅 스크롤 영역 잘림 및 generic modal CSS 충돌을 피한다. 닫기 버튼·배경 클릭·Escape·Tab 초점 순환/복원·본문 스크롤 잠금을 제공한다.
- ChatContentDetail은 클릭 시 `/contents/{id}`에서 최신 상세를 읽고 기존 interaction API로 조회 기록/반응 상태/토글을 처리한다. 상세 GET과 반응 요청에 15초 timeout과 선택적 AbortSignal을 적용했다. 기존 호출 인자 호환성은 유지한다.
- 상세 로딩/안전한 오류/404/명시적 재시도, 반응 상태 실패 후 GET 재확인을 지원한다. 반응 중 중복 토글을 막고 POST 결과가 불확실하면 자동 POST 재시도를 하지 않는다. 조회 기록 실패에도 상세는 표시한다.
- 닫기/언마운트/다른 방·작품 선택 시 요청을 취소하고 현재 요청·토큰을 대조해 늦은 응답을 무시한다. 취소는 서버에 이미 반영된 조회/반응을 롤백하지 않는다. 로그인/권한은 기존 서버 경로를 따른다.

## 변경 파일

Backend:

- src/main/java/com/example/recommend/chat/dto/ConversationDtos.java
- src/main/java/com/example/recommend/chat/service/ChatHistoryStore.java
- src/test/java/com/example/recommend/chat/service/ChatContentLinksTest.java (신규)
- docs/AI_PROGRESS.md, docs/AI_ROADMAP.md, 본 문서

Frontend:

- src/components/ChatPage.js, ChatPage.css, ChatPage.test.js
- src/components/HomePage.js
- src/components/ContentDetailModal.js, ContentDetailModal.css, ContentDetailModal.test.js (신규)
- src/components/ChatContentDetail.js, ChatContentDetail.test.js (신규)
- src/components/HomePage.test.js (신규)
- src/api/contentApi.js, interactionApi.js

## 실행한 검사

작업본 및 Eclipse 실행본에서 각각 실행했다.

```powershell
.\mvnw.cmd -o '-Dtest=ChatContentLinksTest,ConversationPersistenceTest,GroundedConversationTest,RecommendationProvenanceTest,ChatSchemaTest' test
```

양쪽 각각 **61개 통과, 실패/오류/생략 0**. 신규 5개는 저장/재조회 카드 순서·현재 카탈로그 조회·삭제/제목 변경·소유권·텍스트만으로 카드 생성 금지·손상된 참조·개인 추천 근거 비공개·40개 페이징/3 SQL을 검증한다. 기존 저장/근거/스키마 회귀도 통과했다. 최초 및 재개 후 샌드박스 시도는 시작 후 진행이 멈춰 중단했으며 성공으로 계산하지 않았다. 일반 프로세스 권한의 동일 격리 H2 검사로 성공을 확인했다.

Frontend 작업 디렉터리에서 실행했다(로그/캐시는 backend target 아래).

```powershell
$env:CI='true'
npm.cmd test -- --watchAll=false --runInBand --cacheDirectory=C:\Users\jaehyeon\recommend-backend\target\chat-content-modal-20261126\jest-cache
npm.cmd run build
```

**11개 suite/71개 검사 통과**(기존 57 + 신규 14), 최종 프로덕션 빌드 **Compiled successfully**. 새/저장 답변 카드·초안 유지·기존 텍스트 호환·삭제/조회 재시도·최신 정보/세 반응·중복 POST 방지·불확실한 반응 후 GET 복구·방 전환/언마운트/토큰 변경 뒤 늦은 응답 무시·키보드/닫기/초점 복원·메인 추천 기록과 공유 모달을 검증했다. Browserslist 데이터 노후 및 Node fs.F_OK 도구 경고는 남아 있으며 의존성 업데이트는 하지 않았다.

증거:

- 작업본 target/chat-content-modal-20261126/source-backend-tests-verified.log
- 작업본 target/chat-content-modal-20261126/frontend-tests-final.log
- 작업본 target/chat-content-modal-20261126/frontend-build-final.log
- 실행본 target/chat-content-modal-sync-20261126/eclipse-backend-tests.log
- 작업본 target/chat-content-modal-20261126/verification.json (최종 해시/보존/동기화)

## Eclipse 동기화와 범위

작업본 C:/Users/jaehyeon/recommend-backend에서 실행본 C:/Users/jaehyeon/eclipse-workspace/recommend로 위 backend 6파일만 선택 동기화했다. 초기 차이/대상 지침을 확인했고 덮어쓴 소스 2개·문서 2개는 실행본 target/chat-content-modal-sync-20261126/before에 먼저 백업했다. 새 테스트/보고서 2개는 기존 파일이 없었다. 6파일 SHA-256 일치 및 초기 파일 보존을 검증했다. 다른 설정/자격증명/실행 구성은 복사하지 않았다.

실행 중인 서버 재시작은 하지 않았다. **Eclipse F5 → 백엔드 중지/재실행 → 프런트 페이지 새로고침** 후 기능을 사용할 수 있다. IDE 자체 재시작은 보통 필요 없다.

서비스 DB 조회/변경·마이그레이션·새 TMDB/LLM 호출은 이 기능 작업에서 0회다. 검사는 H2와 모의 프런트 API를 사용했다. 실제 로그인 브라우저/모바일 레이아웃 및 서비스 HTTP 통합은 이번에 실행하지 않았으므로 별도 확인 대상이다.

이전 품질 3단계는 미승인·비활성 6테이블/1,048행 저장 상태 그대로다. Content 468개/매핑/시간 정보·평가 코퍼스·판정 기준은 수정하지 않았다. 다음 단일 작업은 새 요청 시 저장된 메타데이터의 필드별 검토·승인 계획이다. 상세는 AI_METADATA_LIVE_STORE_2026-11-26.md에 유지한다.
