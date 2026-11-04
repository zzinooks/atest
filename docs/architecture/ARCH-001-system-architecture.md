# ARCH-001 전체 시스템 아키텍처

- 작성 목적: STEP 3 — 구성요소, 데이터 흐름, 인증, 권한, 알람, 외부 연동의 기준 설계
- 작성 일자: 2026-11-04
- 관련 STEP: 3 (ADR-0002, ADR-0003, ADR-0004)

## 1. 요구사항
REQ-001 기능, 마스터 프롬프트 4~11장, STEP1 설계 요구사항 D-1~D-6.

## 2. 상세 내용

### 2.1 구성도
```
 [가상 CGM 에뮬레이터]      [Android 앱: 환자]            [xDrip+] (실기기 시)
  (poc/cgm-emulator)         Health Connect 읽기            Nightscout 업로드
        │ ① 서버가 폴링             │ ② 배치 업로드                 │ ③ 푸시
        ▼                           ▼                              ▼
 ┌──────────────────────── Spring Boot Backend ─────────────────────────┐
 │ ingest: EmulatorCloudAdapter / HealthConnect batch API / Nightscout API │
 │   → GlucoseDataNormalizer → GlucoseIngestService (upsert, 중복 제거)     │
 │   → AlertEngine (저·고혈당, 급변동, 미수신, 동기화 오류)                 │
 │   → NotificationDispatcher (재시도) → NotificationSender (FCM | dev)    │
 │ API: 인증 · 가족 공유 · 혈당 조회 · 통계 · 기록 · 관리자                   │
 │ NotionSyncService (비동기, 실패 격리)                                    │
 └───────────────┬──────────────────────────────┬───────────────────────┘
                 ▼                              ▼
           [PostgreSQL]                [FCM]   [Notion API]
                 ▲
     [Android 앱: 가족] — 조회 API · FCM 수신 · 알림 확인
```
운영 흐름에 REA는 없다 (ADR-0001).

### 2.2 수집 파이프라인 규칙
| 규칙 | 내용 |
|---|---|
| 정규화 | 모든 시각을 UTC `timestamptz`로 저장, 원본 오프셋은 `measured_offset`에 보존 |
| 미래 시각 | `measuredAt > now + 5분` → 저장하지 않고 `sync_history`에 거부 건수 기록 (시간대 오류 방어) |
| 범위 | 값 20~600 mg/dL 밖이면 거부. LO/HI는 `value=null` + `flag` |
| 중복 | 유니크 키 `(patient_id, source, external_id)` + `ON CONFLICT DO NOTHING` |
| backfill | 폴링 시 `마지막 측정 시각 − 3시간`부터 다시 조회 (D-1) |
| 재시도 | 429는 `Retry-After`, 5xx·네트워크 오류는 지수 백오프 (최대 3회/동기화) |

### 2.3 인증 · 권한
- 이메일+비밀번호(BCrypt) → Access JWT(HS256, 15분) + Refresh 토큰(30일, 회전, DB에는 SHA-256 해시만)
- 한 사용자가 환자이면서 다른 환자의 가족일 수 있다. 앱은 `/me`의 `isPatient`, `familyOf[]`로 화면을 나눈다.
- 권한 판단은 **Backend `AccessPolicy` 한 곳**에서 한다.
  | 동작 | 허용 |
  |---|---|
  | 혈당·통계·알림 조회 | 환자 본인, `can_view_glucose`인 ACTIVE 가족 |
  | 알림 수신 | 환자 본인, `can_receive_alerts`인 ACTIVE 가족 |
  | 메모·식사·운동 기록 작성 | 환자 본인, `can_write_notes`인 ACTIVE 가족 |
  | 초대·권한 변경·공유 해제·알림 기준·데이터 소스 | 환자 본인만 |
- 공유 해제는 같은 트랜잭션에서 `status=REVOKED` → 다음 요청부터 즉시 403 (토큰 재발급 불필요: 권한을 토큰에 넣지 않음)
- 가족의 조회는 `audit_logs`에 남긴다 (혈당값은 기록하지 않음)

### 2.4 알람 (ADR-0004)
- 판단 대상은 **측정 시각이 `stale_minutes`(기본 15분) 이내인 값**만. backfill로 들어온 과거 값으로 알림을 내지 않는다.
- 같은 환자·같은 종류의 알림은 동시에 하나만 열린다 (부분 유니크 인덱스).
- 열린 알림은 확인(ack) 전까지 `repeat_minutes`마다 재알림, 해소 후 `cooldown_minutes` 안에는 같은 종류를 새로 열지 않는다.
- 알림 문구에 혈당 숫자를 넣지 않는다 (잠금화면 노출 방지). 앱에서 상세 조회.
- 전송 상태(`notification_deliveries.status`)와 사용자 확인(`alerts.acknowledged_at`)은 별개로 관리한다.

### 2.5 외부 연동 실패 격리
| 연동 | 실패 시 |
|---|---|
| 데이터 소스 | `sync_history` 기록, 연속 3회 실패 시 SYNC_ERROR 알림. 다른 환자 동기화는 계속 |
| FCM | 전송 행 단위 재시도 (1·2·4·8·16분), 5회 실패 시 FAILED. 알림 자체는 열린 상태 유지 |
| Notion | 별도 스케줄러·트랜잭션. 실패해도 저장·알림에 영향 없음. `notion_sync`에 상태 기록 |

### 2.6 데이터 보관 · 삭제
- 혈당 원본 기본 보관 730일 (설정값), 매일 만료분 삭제
- 회원 탈퇴: 환자 데이터(혈당·알림·기록·소스·가족 그룹) 삭제, 계정은 식별정보 제거 후 비활성화
- 감사 로그는 1년 보관 후 삭제

## 3. 기술적 결정
ADR-0003 (Backend 스택·인증), ADR-0004 (알람 의미 규칙)

## 4. 확인된 사실
설계 문서. 검증은 STEP 4·6·8 테스트로 한다.

## 5. 미확인 사항
- FCM 실제 전송 (Firebase 프로젝트·서비스 계정 필요)
- Notion 실제 연동 (api.notion.com 네트워크 차단, 통합 토큰 필요)

## 6. 테스트 결과
STEP 4 이후 문서 참조.

## 7. 향후 작업
구현과 다르면 이 문서를 갱신한다.

## 변경 이력
| 일자 | 변경 내용 |
|---|---|
| 2026-11-04 | 최초 작성 |
