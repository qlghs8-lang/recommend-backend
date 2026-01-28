# Recommend Backend

개인화 콘텐츠 추천 시스템을 위한 백엔드 서버입니다.  
사용자 행동 로그를 기반으로 콜드스타트부터 협업 필터링까지 지원하는 추천 API를 제공합니다.

---

## Tech Stack

- Java 17
- Spring Boot
- Spring Security + JWT
- JPA / Hibernate
- MySQL
- Redis (휴대폰 인증)
- Docker

---

## Core Features

### Authentication
- JWT 기반 인증/인가
- Role 기반 접근 제어 (USER / ADMIN)
- 세션 만료 시 전역 처리

### User
- 회원가입 / 로그인
- 온보딩(선호 장르)
- 휴대폰 인증 (SMS)

### Content
- 콘텐츠 조회
- 조회수 / 평점 관리

### User Interaction
- VIEW / LIKE / DISLIKE / BOOKMARK 로그
- 상태 조회 API 제공

---

## Recommendation System

### Cold Start
- 온보딩 선호 장르 기반 추천
- 인기 콘텐츠 fallback

### Warm Start
- 콘텐츠 기반 추천 (장르 유사도)
- 협업 필터링 (유사 사용자 기반)
- 클릭/조회 로그 반영

### Explore / Exploit
- 80% Exploit / 20% Explore
- 희귀 장르 가중치
- 시간 window 기반 랜덤 시드

---

## Logging & Analytics

- 추천 노출 로그 (impression)
- 추천 클릭 로그
- 24시간 dedupe 처리
- CTR 계산 가능
- Admin 통계 API 제공

---

## Database Tables

- users
- contents
- user_content_interactions
- recommend_logs
- recommend_click_logs

---

## Run

```bash
git clone https://github.com/qlghs8-lang/recommend-backend.git
cd recommend-backend

Docker 실행
docker-compose up --build
docker-compose down

Environment Variables
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/recommend
SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=password

JWT_SECRET=your-secret-key

REDIS_HOST=localhost
REDIS_PORT=6379

Swagger (API 문서)
http://localhost:8080/swagger-ui.html

API Example
GET /api/recommend/for-you
GET /api/recommend/for-you/reason
POST /api/recommend/click/{recommendLogId}

Notes

추천 로직은 서비스 레이어에서 직접 구현

로그 테이블은 FK 없이 ID 기반 설계

Admin 통계 API 포함
