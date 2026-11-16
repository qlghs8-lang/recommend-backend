# 휴대폰 인증 계약 및 검증 (2026-11-11)

2026-11-12 사용자 결정으로 **실제 SMS 공급자 연동은 프로젝트 범위에서 제외**한다. 사용자는 Postman 수준의 인증 코드 확인으로 충분하다고 판단했다. 실제 발송 연동을 필수 후속 작업으로 관리하지 않는다.
실제 문자 발송 어댑터는 없으며 기본 SmsSender는 503을 반환한다. 기존 자동화 테스트는 발송 대역을 사용하며, 사용자 설명의 Postman 확인은 이번 문서 갱신에서 재검증하지 않았다.
운영 코드에는 고정 테스트 코드, 코드 반환, 코드 콘솔 출력 경로가 없다.
테스트에서만 발송 대역이 생성된 코드를 캡처한다.

## 요청 계약

전화번호는 국내 010 번호 11자리만 받는다. 공백·하이픈은 정규화하고 다른 문자는 거절한다.
휴대폰은 가입 시 선택 사항이지만, 번호를 입력하면 인증 티켓이 필수다.

| 경로 | 요청 JSON | 성공 응답 |
|---|---|---|
| POST /public/phone/request | phone, requesterToken | challengeId, expiresInSeconds=180, resendAfterSeconds=60 |
| POST /public/phone/verify | phone, requesterToken, challengeId, code | verificationTicket, expiresInSeconds=300 |
| POST /users | 기존 가입 필드 + phoneRequesterToken, phoneVerificationTicket | 기존 공개 가입 응답 |
| POST /user/phone/request | phone + Authorization JWT | challengeId, expiresInSeconds=180, resendAfterSeconds=60 |
| POST /user/phone/verify | phone, challengeId, code + Authorization JWT | 휴대폰 인증 완료 |

requesterToken은 프런트 Web Crypto로 생성한 32바이트의 소문자 hex 문자열이다.
프런트 컴포넌트 메모리에만 보관하며 localStorage/sessionStorage에 저장하지 않는다.
가입 challenge와 ticket 모두 해당 요청자와 번호에 연결된다. 로그인 후 인증은 JWT에서 조회한 사용자 ID에 연결된다.
공개 인증을 로그인 후 인증에 사용할 수 없고 반대도 불가능하다.
코드는 SecureRandom 6자리, challengeId와 ticket은 각각 32바이트 난수다.

## 상태와 제한

- 인증 코드는 3분, 확인된 가입 티켓은 확인 시점부터 5분 유효하다. 만료 시각과 동일한 시각부터 거절한다.
- challenge는 확인 성공 즉시 제거되고, 티켓은 가입 시 원자적으로 한 번 소비된다.
- 코드 5회 오입력 시 challenge를 폐기한다. 재발송 성공 시 동일 요청자의 이전 challenge/티켓을 폐기한다.
- 동일 번호 또는 요청자는 60초 이내 재발송 불가, 1시간당 최대 5회다. IP는 시간당 20회, 저장소 전체는 시간당 200회다(Redis 모드는 같은 prefix를 사용하는 모든 인스턴스 합계).
- 발송 실패도 요청 제한에 포함한다. 제한은 429, 공급자 미설정은 503, 인증 실패·만료·누락은 400, 중복 인증 번호는 409다.
- IP는 HttpServletRequest.getRemoteAddr()를 사용한다. 앱 코드에서 X-Forwarded-For를 직접 신뢰하지 않는다.
- 마이페이지의 인증 요청/실패는 기존 사용자 번호·인증 상태를 변경하지 않는다. 코드 확인 성공 후 반영한다.
- 선택정보 저장 시 정규화 후 같은 번호면 인증을 유지하고, 번호 변경/삭제 시 인증을 해제한다.
- 프런트는 요청 중 번호 변경을 막고, 번호 편집 시 challenge/티켓을 지우며, 만료·재전송 대기를 처리한다.
- 가입 실패는 서버에서 티켓 소비 후 실패했을 수 있으므로 프런트에서 증빙을 폐기하고 재인증을 안내한다.

## 저장 및 운영 제약

2026-11-12부터 `PhoneVerificationStore`를 통해 메모리/Redis 방식을 선택한다. 기본값 `memory`는 기존 개발 동작을 유지하며 다중 서버에는 적합하지 않다. 기존 PhoneVerification 테이블과 User legacy 코드 컬럼은 어느 모드에서도 인증 판단에 사용하지 않는다.

### Redis 설정

- `PHONE_VERIFICATION_STORE=redis`
- `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` (기본 localhost:6379)
- 필요 시 `SPRING_DATA_REDIS_USERNAME`, `SPRING_DATA_REDIS_PASSWORD`, `SPRING_DATA_REDIS_SSL_ENABLED`
- `PHONE_VERIFICATION_REDIS_PREFIX` 기본 `recommend:phone:v1`. 같은 서비스의 모든 인스턴스는 동일 값을 사용한다. 다른 환경은 별도 prefix/Redis를 사용한다. 중괄호는 허용하지 않으며 내부에서 hash tag를 붙인다.
- 연결/명령 timeout은 기본 2초다. `SPRING_DATA_REDIS_CONNECT_TIMEOUT`, `SPRING_DATA_REDIS_TIMEOUT`으로 변경할 수 있다.
- 모드 오타는 시작 실패다. Redis 장애 시 요청/검증/소비는 503이며 메모리 fallback과 애플리케이션 자동 재시도는 없다. 응답을 못 받았어도 작업이 적용됐을 수 있으므로 소비 실패 후 재인증한다.

Redis Lua 스크립트가 발송 예약, 활성화, 코드 검증 및 일회성 소비를 처리한다. 시간은 Redis TIME을 사용하므로 앱 서버 시계 차이에 의존하지 않는다. rate limit은 최근 1시간의 sliding window이고, 모든 관련 키는 같은 Redis Cluster hash slot을 사용한다. 구현 근거: [Redis 원자적 스크립트](https://redis.io/docs/latest/develop/programmability/eval-intro/), [Spring Data Redis scripting](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html).

SMS를 호출하기 전에 제한을 확인하고 발송을 예약한다. 외부 발송은 Redis 작업 밖에서 실행한다. 발송 성공 후 아직 최신이고 3분 내인 예약만 활성화한다. 최신 예약이 바뀌거나 지연이 3분 이상이면 활성화를 거절한다. 성공한 challenge의 유효기간은 활성화 시점부터 3분이다. 실패한 발송은 제한에 남지만 이전 유효 challenge/티켓은 보존한다. 실제 공급자가 전송했으나 응답에 실패한 경우에도 새 challenge는 활성화하지 않는다. 성공한 재발송은 이전 증빙을 폐기한다.

키에 원문 전화번호·요청자·IP를 쓰지 않으며 값에 원문 코드·challenge ID·티켓을 저장하지 않는다. SHA-256 해시를 사용하며 코드는 challenge ID와 함께 해시한다. 해시는 개인정보 암호화의 대체가 아니므로 Redis 접근 제어와 TLS를 적용해야 한다. 모든 키에 TTL이 있고 필드 만료도 매번 검사한다. 발송 기록/예약이 포함된 키는 최대 1시간, 활성 challenge는 3분, 티켓은 5분 뒤 만료한다. 예약이 있는 키에는 이전 만료 필드가 TTL까지 남을 수 있지만 인증에는 사용할 수 없다. 전역 200회/시간 제한으로 생성되는 키 수를 제한한다. 개발용 메모리는 기존 2,000개 증빙/5,000개 제한 키 상한을 유지한다.

### 운영 조건 및 한계

- 실제 SMS 어댑터는 아직 없으며 기본값은 503이다. 공급자 선택 후 타임아웃·발송 결과·재시도 정책을 별도로 검증한다.
- 메모리 모드는 재시작 시 모든 증빙과 제한이 소실되고 SMS 호출이 JVM 잠금 안에서 실행된다. Redis 모드는 앱 객체/인스턴스 재생성과 무관하게 공유 Redis에 상태를 둔다.
- `noeviction`, 접근 제한, 지속성 및 복구 정책을 운영 환경에서 설정한다. 데이터 삭제/eviction/Redis 재시작 시 제한과 증빙이 소실될 수 있다. 비동기 복제 failover에서는 최근 소비 기록 손실·재등장 가능성이 있으므로 장애까지 포함한 exactly-once 보장이 아니다. 이 변경은 Redis 인프라를 자동 배포하거나 설정하지 않는다.
- 티켓 소비는 회원 DB 트랜잭션과 별개다. 가입/번호 저장이 롤백되어도 소비된 증빙은 복구하지 않는다. 재인증한다.
- 번호 중복은 서비스 사전 검사 및 User.verifiedPhone unique 제약에 의존한다. 기존 로컬 DB의 해당 제약은 후속 읽기 전용 점검에서 확인했지만 번호 저장의 실제 동시 경합은 별도 검증 대상이다.
- 신뢰 프록시, 공유 IP 제한 및 전역 예산을 배포 환경에 맞게 검토한다. 앱은 X-Forwarded-For를 직접 신뢰하지 않는다.
- Redis 모드 도입/메모리 모드 전환 시 기존 메모리 증빙과 제한은 이관하지 않는다. 전환 시 재인증이 필요하며 두 모드를 섞어 운영하지 않는다.
- 모든 전이가 한 hash slot에서 처리된다. 고부하 처리량, Redis Cluster/failover 및 실제 SMS 공급자 연동은 미검증이다.

### 격리 Redis 테스트 실행

Docker Desktop이 실행된 환경에서 PowerShell로 `./scripts/test_phone_redis.ps1`을 실행한다. 스크립트는 localhost:16379에 임시 Redis 7 컨테이너를 만들고 가상 번호·테스트 발송 대역만 사용한다. 기존 Redis/DB/compose는 변경하지 않는다. 테스트는 고유 prefix만 지우며 FLUSHDB/FLUSHALL을 사용하지 않는다. 종료 시 자신이 만든 컨테이너만 중지·자동 제거한다. 이미지가 없으면 Docker가 다운로드한다. 결과는 `target/redis-phone-reports`에 남는다.

일반 `mvnw.cmd test`에서는 Redis 통합 테스트를 건너뛴다. 개별 실행은 격리 Redis를 먼저 준비한 뒤 `RUN_PHONE_REDIS_TESTS=true`와 `-Dtest=RedisPhoneVerificationTest`를 사용한다. 일반 회귀는 메모리 계약, 모드 선택 및 Redis 장애 시 503/미발송/무재시도를 확인한다.

## 최신 검증 — 2026-11-12

- 일반 백엔드 테스트 60개 통과(실패/오류 0). 이 실행에서는 MySQL 24개와 Redis 11개를 건너뛰었다.
- Docker의 임시 소켓 오류를 복구한 뒤 별도 실행한 **실제 Redis 통합 테스트 11개 모두 통과**(실패/오류/건너뜀 0). 인스턴스 간 공유, 동시 요청/확인/소비, 만료, 오입력, 공통 발송 제한, 실패·지연 발송, TTL·원문 비저장을 검증했다.
- 실행: `./scripts/test_phone_redis.ps1`. 보고서 `target/redis-phone-reports`, 로그 `target/redis-phone-run.log`. 임시 컨테이너는 종료 시 중지·자동 제거하며 Redis 이미지는 캐시에 남는다.
- 최초에는 Docker 서버 조회가 응답하지 않아 엔진 준비 확인 20초 제한을 스크립트에 추가했다. 이후 임시 통신 폴더 두 곳을 삭제 없이 백업·재생성해 Docker를 기동했다. 영구 데이터 초기화는 하지 않았다. 재기동 후 소켓 오류 재발 여부는 보장하지 않는다.
- PowerShell 스크립트 구문 및 Git 공백 검사를 통과했다. 일반 회귀 로그는 `target/phone-shared-tests.log`다.
- 실제 SMS, 서비스 DB에 대한 직접 변경, 프런트 변경, 커밋/푸시/배포 없음. 기본 저장소는 memory를 유지한다.

## 기존 검증 — 2026-11-11

- backend mvnw.cmd test: 30개 통과. 인증 서비스의 만료/오입력/횟수/재발송/동시 소비, MockMvc의 실제 서비스 연결을 통한 공개 인증→가입 및 로그인 사용자 인증, 중복 번호, 기본 공급자 503, 실제 SecurityFilterChain 공개 허용/보호 경로 401 포함.
- front npm.cmd test -- --watchAll=false --runInBand: 17개 통과. 기존 회귀 10개 + 가입/마이페이지 인증 UI 7개.
- front npm.cmd run build: 성공. 기존 Browserslist 노후 및 Node deprecation 경고 존재.
- 저장소는 Mockito 대역이다. 운영 DB, 외부 SMS, 유료 API를 호출하지 않았다.
- 실제 DB 트랜잭션/unique 경쟁과 실제 브라우저 양 서버 연결 E2E는 미검증이다. 프런트 테스트는 Axios 대역, 백엔드는 MockMvc 요청으로 API 계약을 검증했다.
- Maven은 샌드박스에서 Mockito 초기화가 정체되어 중단 후 승인된 권한으로 실행했다.
- .env와 .env.*는 읽거나 변경하지 않았다. 기존 작업 트리의 인증 수정 및 사용자 변경을 유지했다. 커밋·푸시·배포는 하지 않았다.
