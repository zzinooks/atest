# ADR-0003: Backend 스택과 인증 방식

- 상태: 승인
- 작성 일자: 2026-11-04

## 결정
- Java 21, Spring Boot 3.5.x, Gradle(Kotlin DSL), PostgreSQL 16, Flyway, **Spring JDBC(NamedParameterJdbcTemplate)**
  - 마스터 프롬프트 기본안(Spring Data JPA)에서 변경: 핵심 규칙이 SQL 기능에 있음(`ON CONFLICT DO NOTHING` 중복 제거, 부분 유니크 인덱스로 "열린 알림 1개" 보장, `FOR UPDATE SKIP LOCKED` 전송 큐). JPA로 감싸면 이 동작이 가려지고 네이티브 쿼리가 대부분이 된다.
- 인증: 자체 JWT. `spring-boot-starter-oauth2-resource-server`의 Nimbus HMAC 디코더 사용 (추가 JWT 라이브러리 없음)
- 비밀값: `FGC_JWT_SECRET`, `FGC_ENCRYPTION_KEY`(데이터 소스 자격증명 AES-256-GCM 암호화), `NOTION_TOKEN`, Firebase 서비스 계정 경로 — 모두 환경변수
- 테스트: Testcontainers 대신 로컬 PostgreSQL(`FGC_TEST_DB_URL`) — 이 개발 환경에 Docker 데몬이 없음

## 대안
- Firebase Auth: 무료이고 편하지만 Backend 권한 모델과 이중 관리가 되고, Android 외 클라이언트·관리자 기능에서 의존이 커진다 → 보류
- Keycloak: 무료 호스팅에서 메모리 부담 → 제외

## 결과
- 권한을 토큰에 넣지 않고 매 요청 DB에서 확인 → 공유 해제가 즉시 반영된다 (요청마다 조회 1회 비용)
