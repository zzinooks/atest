# BACKEND-001 Backend 구조와 실행

- 작성 일자: 2026-11-04 · 관련 STEP: 4, 6, 7

## 모듈 (`backend/src/main/java/com/fgc/backend`)
| 패키지 | 책임 |
|---|---|
| auth | 가입·로그인·JWT·refresh 회전·로그인 제한 |
| user | /me, 환자 프로필, 기기 등록, 회원 탈퇴 |
| family | AccessPolicy(권한 단일 판단), 초대·수락·권한 변경·해제·동의 이력 |
| glucose | 정규화, 저장(중복 무시), 조회, 신선도, 통계, HC 업로드 API |
| source | 데이터 소스 관리, EmulatorCloudAdapter, SyncService, Nightscout 수신 |
| alert | 알림 기준, AlertEngine (ADR-0004), 알림 조회·확인 |
| notify | 전송 큐, Dispatcher(재시도), FcmSender / DevOutboxSender |
| event | 식사·운동·복약·메모 |
| notion | NotionClient(429 재시도), 일별·주간 요약, 동의 설정 |
| admin | 운영 상태 (건강정보 미포함) |
| ops | 스케줄 작업: 동기화 1분, 미수신·재알림 1분, 전송 10초, Notion 00:30/월 01:00, 보관 정리 03:15 |

## 실행 (로컬)
```bash
export FGC_JWT_SECRET=$(python3 -c "import secrets;print(secrets.token_urlsafe(48))")
export FGC_ENCRYPTION_KEY=$(python3 -c "import secrets,base64;print(base64.b64encode(secrets.token_bytes(32)).decode())")
export FGC_DB_URL=jdbc:postgresql://127.0.0.1:5432/fgc FGC_DB_PASSWORD=...
cd backend && gradle bootRun        # 또는 gradle bootJar && java -jar build/libs/fgc-backend-0.1.0.jar
```
선택: `FGC_FCM_CREDENTIALS`(Firebase 서비스 계정 JSON 경로), `NOTION_TOKEN`, `NOTION_DAILY_DB_ID`, `NOTION_WEEKLY_DB_ID`.
FCM 미설정 시 dev-outbox 로 동작하며 **실제 기기로 알림이 가지 않는다** (시작 로그에 WARN).

## 테스트
```bash
cd backend && FGC_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5433/fgc_test gradle test   # PostgreSQL 필요 (user fgc)
```

## Notion 설정
1. Notion 에서 내부 통합(Integration) 생성 → 토큰을 `NOTION_TOKEN` 에
2. 일별·주간 데이터베이스 생성, 통합에 공유, ID 를 환경변수에
3. 데이터베이스 속성: `Name`(제목), `Date`(날짜), `Patient`(텍스트), `Readings`·`Coverage`·`Mean`·`Min`·`Max`·`SD`·`CV`·`TIR`·`TBR`·`TAR`(숫자)
4. 환자가 앱 설정에서 "Notion 요약 기록" 동의
실제 Notion 기록은 **미검증** (이 개발 환경에서 api.notion.com 네트워크 차단). 요청 형식·재시도·실패 격리는 스텁 서버로 검증.
