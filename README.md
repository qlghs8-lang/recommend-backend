# Recommend Backend

영화·TV 콘텐츠 개인화 추천 API입니다. Java 17, Spring Boot 4.0.0, Spring Security/JWT, JPA, MySQL을 사용하며 휴대폰 인증 상태 저장에 Redis를 선택할 수 있습니다.

## 추가 작업 정리 (2026-11-16)

| 영역 | 반영 내용 |
|---|---|
| 인증 | JWT 서명 키 환경변수화, 존재하는 회원·토큰 버전 검사, 가입 응답 DTO 분리, HTTP 오류 구분 |
| 토큰 폐기 | 비밀번호 변경 및 모든 기기 로그아웃 시 DB 토큰 버전 증가, 기본 로그아웃 리다이렉트 비활성화 |
| 휴대폰 인증 | 공개 인증번호 제거, 요청자에 연결된 일회성 티켓, 만료·재발송·오입력 제한, 메모리/Redis 저장소 |
| 프로필 이미지 | JPEG/PNG 실제 형식·크기·해상도 검증, PNG 재인코딩, 저장 경로 설정 |
| 추천 품질 | 협업 후보의 사용자 수 집계, 콜드스타트 싫어요 제외, 조회수 0인 경우 점수 계산 보정 |
| 추천 통계 | 후보 생성과 실제 노출 분리, 최초 노출 시각 보존, 고유 클릭 CTR과 반복 클릭 수 분리 |
| 동시성·삭제 | 회원 → 콘텐츠 → 추천 로그 순서의 잠금, 조회수·반응 경합 처리, 관련 데이터 트랜잭션 삭제 |
| 로그 정리 | 관리자 미노출 후보 정리 API, 기본 미리보기, 클릭 연결 기록 보존, 배치 제한 |
| 검증 | 보안·회원·업로드·추천·삭제·통계 회귀 테스트, 선택형 MySQL/Redis 통합 테스트와 실행 스크립트 |

## 실행 설정

Java 17과 MySQL이 필요합니다. IDE 또는 실행 프로세스에 다음 환경변수를 설정한 뒤 실행합니다. Spring Boot는 이 저장소의 `.env`를 자동으로 읽지 않습니다.

| 변수 | 설정 |
|---|---|
| `JWT_SECRET` | 필수. 무작위로 생성한 최소 32바이트 UTF-8 서명 키 |
| `SPRING_DATASOURCE_URL` | 사용할 MySQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | DB 접속 계정 및 비밀번호 |
| `PROFILE_UPLOAD_DIR` | 업로드 루트, 기본 `./uploads`; 아래 `profile` 디렉터리 사용 |
| `PHONE_VERIFICATION_STORE` | 기본 `memory`, 공유 저장소 사용 시 `redis` |
| `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` | Redis 모드의 접속 주소/포트 |

```powershell
.\mvnw.cmd spring-boot:run
```

기본 API 주소는 `http://localhost:8080`, 허용된 프런트 CORS 주소는 `http://localhost:3000`입니다. DB 기본 설정은 로컬 개발용이며 실제 환경에서는 접속 설정과 스키마 관리 정책을 확인해야 합니다. Swagger 생성 의존성은 현재 포함되어 있지 않습니다.

기존 DB에는 `users.token_version`, `recommend_logs.exposed_at` 및 노출 시각 인덱스가 필요합니다. [토큰 폐기 정책](docs/token-revocation.md), [추천 통계 계약](docs/recommendation-tracking.md), [로컬 DB 변경 기록](docs/database-migration-20261111.md)을 참고하세요. 새 토큰 형식 적용 후에는 재로그인이 필요합니다.

## 주요 동작과 API

- 선호 장르 기반 콜드스타트, 장르·협업·조회수 기반 추천과 탐색 후보를 제공합니다.
- `GET /api/recommend/for-you/reason`: 추천 사유와 `recommendLogId`를 반환합니다.
- `POST /api/recommend/impression/{recommendLogId}`: 인증된 소유자의 실제 노출을 기록합니다.
- `POST /api/recommend/click/{recommendLogId}`: 클릭을 기록합니다. CTR은 클릭된 노출 수 / 전체 노출 수입니다.
- `POST /logout`: 해당 회원의 모든 기기 JWT를 폐기합니다.
- `POST /api/admin/recommend-logs/cleanup`: 기본 `retentionDays=90`, `limit=100`, `dryRun=true`. 미노출·클릭 미연결 후보만 대상으로 하며 실제 삭제에는 `dryRun=false`가 필요합니다.

가입과 비밀번호 변경에는 영문·숫자를 모두 포함한 8~72자리 영숫자 정책을 적용합니다. 이미지 업로드는 JPEG/PNG 5MiB 이하, 각 변 4096px 이하·1600만 화소 이하를 허용합니다.

휴대폰 인증 로직은 SMS 테스트 대역으로 검증합니다. 실제 SMS 공급자 연동은 프로젝트 범위에서 제외했으며 기본 발송 요청은 503을 반환합니다. 공개 코드 조회나 고정 인증번호는 제공하지 않습니다. Redis 장애 시 메모리로 우회하지 않습니다. [휴대폰 인증 계약과 제한](docs/phone-verification.md)을 참고하세요.

## 검증 결과

2026-11-16 실행 결과:

| 검사 | 결과 |
|---|---|
| 백엔드 `mvnw.cmd test` | 일반 테스트 60개 통과, 선택형 MySQL/Redis 35개 건너뜀 |
| 프런트 `npm.cmd test -- --watchAll=false --runInBand` | 6개 스위트, 27개 테스트 통과 |
| 프런트 `npm.cmd run build` | 성공; Browserslist 데이터 및 Node API 사용 중단 경고 |

일반 백엔드 테스트는 외부 DB 없이 H2·MockMvc·테스트 대역으로 실행합니다. 과거 기록에는 격리 MySQL 24개 및 Redis 11개 통과 결과가 있으며, 위 날짜에 재실행한 결과와 구분합니다.

```powershell
.\mvnw.cmd test
# 선택 실행: 전용 로컬 MySQL 스키마 / 임시 Docker Redis 사용
python scripts/test_mysql_concurrency.py
powershell -File scripts/test_phone_redis.ps1
```

DB 스크립트는 지정된 로컬 MySQL 환경을 전제로 합니다. `audit_local_db.py`는 읽기 전용 검사이며 `migrate_local_db.py`는 백업·복원 사전 검증과 명시적 적용용입니다. 백업, 업로드, 임시 자료 및 비밀 환경 파일은 Git에서 제외합니다.

## 남은 범위와 작업 기록

브라우저 전체 E2E, 운영 배포, 대규모 부하·복잡한 교착 경합, Redis Cluster/failover는 검증하지 않았습니다. AI 채팅은 설계 단계이며 구현되지 않았습니다.

- [전체 분석 및 AI 계획](docs/project-review-and-ai-plan.md)
- [상세 작업 이력·인수인계](docs/next-chat-handoff.md)
- [프런트엔드 저장소](https://github.com/qlghs8-lang/recommend-frontend)

Eclipse에서 별도 프로젝트 경로를 실행하는 경우 이 Git 저장소와 실행 소스가 일치하는지 확인해야 합니다. 이번 커밋 준비는 `recommend-backend` 저장소 기준입니다.
