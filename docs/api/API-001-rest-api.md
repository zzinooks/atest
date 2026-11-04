# API-001 REST API 명세 (v1)

- 작성 목적: Android 앱·외부 업로더가 쓰는 Backend API 목록
- 작성 일자: 2026-11-04

공통: JSON, 시각은 ISO-8601. 인증은 `Authorization: Bearer <access JWT>`(15분). 오류 형식 `{"error": "code", "message": "..."}`.
권한 열: 본인 = 환자 본인, 가족(V/A/N) = 보기/알림/기록 권한이 있는 ACTIVE 가족.

| 메서드 · 경로 | 권한 | 설명 |
|---|---|---|
| POST /api/v1/auth/signup | 공개 | 가입 → 토큰 |
| POST /api/v1/auth/login | 공개 | 로그인 (5회 실패/5분 → 429) |
| POST /api/v1/auth/refresh | 공개 | refresh 회전. 재사용 감지 시 전체 폐기 |
| POST /api/v1/auth/logout | 공개 | refresh 폐기 |
| GET /api/v1/me | 로그인 | 내 정보, `isPatient`, `patientId`, `familyOf[]` |
| POST /api/v1/me/patient-profile | 로그인 | 환자 프로필 생성 (기본 알림 기준 포함) |
| POST /api/v1/me/devices · DELETE /api/v1/me/devices/{id} | 로그인 | FCM 토큰 등록/삭제 |
| POST /api/v1/me/withdraw | 로그인 | 회원 탈퇴 (비밀번호 확인) |
| DELETE /api/v1/me/family/{patientId} | 가족 | 공유에서 나가기 |
| GET /api/v1/patients/{pid}/glucose?from&to | 본인·가족V | 측정값 (최대 31일) |
| GET /api/v1/patients/{pid}/glucose/latest | 본인·가족V | 최신값 + `freshness`(FRESH/STALE/NO_DATA), `ageMinutes`, `ratePerMinute` |
| POST /api/v1/patients/{pid}/glucose/batch | 본인 | Health Connect/수동 업로드 (최대 1000건) |
| GET /api/v1/patients/{pid}/stats?from&to | 본인·가족V | TIR/TBR/TAR, 평균, SD, CV, GMI, 충족률 |
| GET /api/v1/patients/{pid}/stats/daily?days | 본인·가족V | 일별 통계 (환자 시간대 기준) |
| GET · PUT /api/v1/patients/{pid}/alert-rules | 조회 본인·가족V / 변경 본인 | 알림 기준 |
| GET /api/v1/patients/{pid}/alerts?activeOnly | 본인·가족V | 알림 이력 |
| POST /api/v1/alerts/{id}/ack | 본인·가족A | 알림 확인 (재알림 중지) |
| POST /api/v1/patients/{pid}/invitations | 본인 | 초대 코드 (48시간, 1회용) |
| POST /api/v1/invitations/accept | 로그인 | 초대 수락 |
| GET /api/v1/patients/{pid}/members | 본인 | 가족 목록 |
| PUT /api/v1/patients/{pid}/members/{mid}/permissions | 본인 | 권한 변경 |
| DELETE /api/v1/patients/{pid}/members/{mid} | 본인 | 공유 해제 (즉시) |
| GET · POST /api/v1/patients/{pid}/events · DELETE …/{eid} | 조회 V / 작성 N / 삭제 작성자·본인 | 식사·운동·복약·메모 |
| GET · POST /api/v1/patients/{pid}/data-sources | 조회 V / 생성 본인 | EMULATOR / HEALTH_CONNECT / NIGHTSCOUT_UPLOAD |
| POST …/data-sources/{id}/sync · PUT …/status?value · DELETE | 본인 | 수동 동기화, 일시정지, 삭제 |
| PUT /api/v1/patients/{pid}/notion?enabled | 본인 | Notion 요약 기록 동의 |
| GET /api/v1/admin/summary · sync-status · deliveries/failed | 관리자 | 운영 상태 (건강정보 미포함) |
| POST /ns/{sourceId}/api/v1/entries · GET …/status.json | `api-secret: SHA1(secret)` | Nightscout 호환 업로드 (xDrip+) |
| GET /actuator/health | 공개 | 헬스체크 |

계약 검증: `android/core` LiveContractTest 가 실제 Backend 에 대해 주요 엔드포인트의 JSON 을 확인 (2026-11-04 통과).
