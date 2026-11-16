# 로컬 MySQL 변경 결과 — 2026-11-11

사용자 요청에 따라 사전 검증 후 적용을 완료했다. 대상은 현재 application.properties에 설정된 `localhost:3306/myproject`, MySQL 8.0.44이다. Docker MySQL이나 외부 운영 서버에는 적용하지 않았다.

## 적용 내용

```sql
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0, ALGORITHM=INSTANT;
ALTER TABLE recommend_logs ADD COLUMN exposed_at DATETIME(6) NULL, ALGORITHM=INSTANT;
ALTER TABLE recommend_logs ADD INDEX idx_rl_exposed_at (exposed_at), ALGORITHM=INPLACE, LOCK=NONE;
```

각 DDL은 별도 커밋된다. 세 문장을 하나의 트랜잭션으로 롤백할 수 있는 것으로 취급하지 않았다. 컬럼은 INSTANT, 인덱스는 INPLACE/LOCK=NONE을 명시해 지원되지 않으면 테이블 복사 방식으로 조용히 전환하지 않고 실패하게 했다. 메타데이터/행 잠금 대기 제한은 5초로 설정했다.

## 사전·사후 검증

1. MySQL 서비스/버전/대상 스키마와 기존 컬럼·인덱스를 읽기 전용으로 확인했다. 변경 시점에 다른 클라이언트 세션이나 진행 중 InnoDB 트랜잭션이 없음을 확인했다.
2. 전체 DB를 single-transaction 방식으로 백업했다. 모든 테이블이 InnoDB이고 복원 시 외부 동작을 일으킬 트리거·루틴·이벤트가 없는 것을 확인했다.
3. 별도 `recommend_validation_20261111` DB에 백업을 복원하고, 모든 테이블의 기존 컬럼 값과 행 수를 비교했다.
4. 검증 DB에서 위 DDL을 실행했다. 컬럼 타입/NULL/default/인덱스, 기존 데이터 보존을 확인하고, 토큰 버전 증가와 노출 시각 저장을 트랜잭션 내에서 시험한 후 롤백했다.
5. 원본에 적용하기 직전 백업 파일 SHA-256, 원본 스키마 및 기존 데이터 해시를 재확인했다. 적용 후 전체 기존 컬럼의 데이터 해시와 행 수가 동일함을 확인했다.
6. 기존 회원 14명의 token_version은 모두 0, 기존 추천 로그 2,011개의 exposed_at은 모두 NULL이다. 실제 노출 증거 없는 과거 로그는 백필하지 않았다.

| 테이블 | 보존된 행 수 |
|---|---:|
| content | 0 |
| contents | 32 |
| nickname_blacklist | 7 |
| phone_verifications | 2 |
| recommend_click_logs | 64 |
| recommend_logs | 2011 |
| user_content_interactions | 221 |
| user_terms_agreements | 44 |
| users | 14 |

첫 검증 실행에서는 Windows 줄바꿈 차이로 컬럼 결과 비교가 중단됐다. 원본 적용 전 비교 처리를 수정하고 검증 DB의 타입·기존 데이터·신규 동작 검증을 모두 통과한 후 원본에 적용했다.

## 백업·복구 및 남은 범위

- 백업: 저장소 루트 `.local-db-backups/20261111-token-exposure/myproject.sql`
- 검증/적용 기록: 같은 폴더의 `manifest.json` (백업 해시, 기존 컬럼별 조회 결과 해시, 행 수, 적용 성공 기록).
- 백업과 검증 DB에는 원본 개인정보/비밀번호 해시가 있으므로 공개하거나 커밋하지 않는다. `.local-db-backups/`는 Git 제외를 확인했다. 백업과 검증 DB는 복구·추가 검증을 위해 보존했다.
- 복구가 필요하면 앱 쓰기를 멈춘 뒤 백업을 새 DB에 복원·검증하고 접속 대상을 전환한다. 변경 뒤 발생한 신규 데이터는 별도 보존·대조해야 한다. 이번 추가형 변경은 앱을 이전 버전으로 되돌릴 때 컬럼을 즉시 삭제할 필요가 없다. 원본 테이블 삭제나 백업 덮어쓰기 복원은 수행하지 않았다.
- 재현 스크립트: `scripts/migrate_local_db.py`. 현재 작업에 지정된 로컬 DB 전용이며, 완료 후 다시 적용하려 하면 기존 스키마 검사에서 중단한다.
- 애플리케이션 실행/배포와 양 서버 브라우저 E2E는 수행하지 않았다. 신규 백엔드 사용 시 구형 토큰은 거부되어 재로그인이 필요하며 프런트도 함께 사용해야 한다. MySQL의 경합 부하 테스트와 회원/콘텐츠 삭제 FK 검증은 별도 후속 작업이다.
