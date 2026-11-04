# CLAUDE.md — Family Glucose Care

바로잰 Fit(한독/아이센스, CGM) 사용 환자의 혈당 데이터를 수집해 환자와 가족이 함께 확인하고
알림을 받는 Android + Spring Boot 서비스. 목표는 데모가 아니라 **실사용 가능한 제품**이다.

## 작업 시작 전 반드시 확인
1. 이 파일
2. `docs/requirements/`, `docs/architecture/`, `docs/decision/` (ADR)
3. 현재 구현 코드와 직전 STEP 완료 보고서 (`docs/research/STEP*-*.md` 등)
기존 설계와 충돌하는 변경은 이유와 영향을 설명하고 ADR을 새로 작성하거나 갱신한다.

## 진행 상태
| STEP | 내용 | 상태 |
|---|---|---|
| 0 | 환경 · REA MCP 점검, CLAUDE.md, 문서 구조 | 완료 (보고서: docs/research/STEP0-environment-and-rea.md) |
| 1 | REA MCP 및 데이터 접근 경로 조사 | 완료 (docs/research/STEP1-*.md, ADR-0002) |
| 2 | 데이터 수집 PoC (에뮬레이터 대체) | 완료 — 실제 바로잰 데이터는 미검증 (STEP2-poc-report) |
| 3 | 아키텍처·DB·API 설계 | 완료 (ARCH-001, DB-001, API-001, ADR-0003/0004) |
| 4·6·7 | Backend · 알람 엔진 · Notion | 완료 — 테스트 32개 통과 (BACKEND-001). Notion 실연동 미검증 |
| 5 | Android | :core 완료(14 테스트, 실 Backend 계약 통과) / :app 작성했으나 빌드 미검증 (ANDROID-001) |
| 8 | 통합 테스트·운영 준비 | 완료 범위·미완료 사유: STEP8-final-report, TEST-001, SEC-001, DEPLOY-001, ADR-0005 |

2026-11-04 사용자 지시: "결정을 믿고 될 때까지 모든 단계를 진행" → 단계별 승인 없이 진행함.

사용자가 승인하지 않은 다음 STEP의 대규모 구현은 하지 않는다.

## 저장소 구조 (목표)
```
/
├── CLAUDE.md
├── docs/            # 요구사항·조사·설계·ADR·테스트·배포 문서 (docs/README.md 참고)
├── backend/         # Spring Boot 3.5 (Java 21, Gradle, Spring JDBC, Flyway) — docs/backend/BACKEND-001
├── android/         # :core(Kotlin/JVM, 테스트됨) + :app(Compose, SDK 있을 때만 포함) — docs/android/ANDROID-001
├── deploy/          # docker-compose(Backend+PostgreSQL+Caddy), backup/restore 스크립트
├── poc/
│   ├── cgm-emulator/  # 가상 CGM 에뮬레이터 (Java 21, Gradle) — 실기기 대체, ADR-0002
│   └── rea-practice/  # HAR 기록·분석 연습 도구
└── TestGit/         # 기존 학습용 Spring 예제. 이 프로젝트와 무관 — 수정하지 않는다
```

## 프로젝트 성격 (2026-11-04 사용자 결정)
- **연습 프로젝트**. 공식 앱(바로잰 Care) 기능도 함께 구현한다 (docs/requirements/REQ-001).
- 실물 기기·계정 없음 → 데이터는 `poc/cgm-emulator`. 화면에 "가상 데이터" 표시. 실기기 검증 전까지 "실제 데이터 수집"은 미완료.
- 바로잰 비공개 API·APK 역분석은 하지 않는다 (권한 없음, ADR-0001/0002).

## 절대 규칙
- **데이터 출처 구분**: 측정 시각(`measuredAt`)과 수신 시각(`receivedAt`)을 항상 분리한다. 오래된 값으로 현재 상태를 판단하지 않는다.
- **검증되지 않은 것은 성공이라 하지 않는다.** 미확인 항목은 `UNKNOWN`으로 기록한다. 빌드 성공 ≠ 기능 완료.
- **REA MCP는 개발 도구일 뿐**: 운영 코드가 REA에 의존하지 않는다. 존재하지 않는 MCP Tool·파라미터를 지어내지 않는다.
- **접근 권한**: 사용자 본인 계정·기기, 명시적으로 허가된 테스트 계정만 사용. 인증 우회, 타인 계정 접근, 권한 없는 개인정보 추출 금지.
  비공식 API를 운영에 쓰기 전 이용약관·법적 검토를 먼저 한다 (ADR 필수).
- **비밀정보**: Token·Cookie·API Key·Notion Key·Firebase 서비스 계정 키를 코드·저장소에 넣지 않는다. 환경변수/시크릿 저장소 사용.
  Notion Key는 Backend에만 둔다.
- **로그**: 실제 혈당값·사용자 식별정보·토큰을 로그에 남기지 않는다 (마스킹).
- **의료 안전**: 알림은 환자/의료진이 정한 기준만 사용. 진단·치료 권고를 생성하지 않는다. 측정값과 예측값을 구분한다.
- **Notion은 원본 저장소가 아니다.** 원본은 PostgreSQL. Notion 연동 실패가 저장·알림에 영향을 주면 안 된다(비동기).
- **실패를 숨기지 않는다.** 실패한 테스트와 미검증 항목은 보고서에 그대로 적는다.

## 기술 스택 (기본안, 변경 시 ADR)
- Android: Kotlin, Jetpack Compose, ViewModel, Coroutines, Room, Retrofit/OkHttp, WorkManager, FCM, Health Connect
- Backend: Java 21, Spring Boot 3.x, Spring Security, Spring Data JPA, PostgreSQL, Flyway, Firebase Admin SDK, Notion API
- 빌드: Gradle (Backend/Android 공통)

## 문서 규칙
모든 문서는 `docs/README.md`의 공통 템플릿(제목·목적·작성일·요구사항·상세·기술적 결정·확인된 사실·미확인 사항·테스트 결과·향후 작업)을 따른다.
기존 문서를 고칠 때는 하단 변경 이력에 기록한다.

## 작업 후 체크리스트
빌드 → 테스트 → 오류 확인 → 변경 파일 목록 → 테스트 결과 기록 → 문서 갱신 → 남은 이슈와 다음 STEP 의사결정 사항 보고.

## 개발 환경 메모 (2026-11-04 기준, 클라우드 컨테이너)
- 있음: OpenJDK 21, Maven 3.9.11, Gradle 8.14.3, Node 22, PostgreSQL 16 클라이언트, Docker
- 없음: Android SDK, adb, Kotlin CLI, 실물 기기 → Android 빌드·실기기 검증은 사용자 PC 필요
- REA MCP: 미연결. `npx rea-agents` 실행이 자동 권한 검사로 차단됨 → HAR 기록·분석 도구로 대체 (STEP1-rea-practice)
- Android 에뮬레이터: `/dev/kvm` 없음 → 이 컨테이너에서 실행 불가
- Maven Central 이 429 를 돌려준다 → `~/.gradle/init.d/maven-mirror.gradle` 로 Google 미러 우선 사용 (컨테이너 전용 설정)
- 네트워크 정책 차단: `dl.google.com`(Android SDK·Google Maven), `api.notion.com`
- Docker 데몬 없음 → 테스트 DB 는 로컬 PostgreSQL 16 (`/var/lib/postgresql/fgc-dev`, 포트 5433, DB fgc / fgc_test, user fgc)

## 자주 쓰는 명령
- 에뮬레이터 테스트: `cd poc/cgm-emulator && gradle test`
- Backend 테스트: `cd backend && gradle test` (PostgreSQL 필요, FGC_TEST_DB_URL)
- Android core 테스트: `cd android && gradle :core:test` (실 Backend 계약: `FGC_LIVE_BASE_URL=http://127.0.0.1:8080`)
