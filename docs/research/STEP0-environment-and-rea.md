# STEP 0 보고서 — 프로젝트 환경 및 REA MCP 연결 점검

- 작성 목적: 개발 환경과 REA MCP 사용 가능 여부를 확인하고 STEP 1 착수 조건을 정리한다.
- 작성 일자: 2026-11-04
- 관련 STEP: 0

## 1. 요구사항
마스터 프롬프트 19장: 작업 디렉터리·기존 파일·개발 환경·MCP 연결·REA Tool 확인, 문서 구조 제안, CLAUDE.md 초안, STEP 1 준비.
API 역분석과 운영 코드 구현은 이 단계에서 하지 않는다.

## 2. 상세 내용

### 2.1 작업 디렉터리 · 기존 파일
| 항목 | 결과 |
|---|---|
| 경로 | `/home/user/atest` (GitHub `zzinooks/atest`, 브랜치 `ccr-ac5d9c38-9bi9je`) |
| 기존 코드 | `TestGit/` — 2010년대식 Spring MVC 학습 예제(Controller 2개, JSP). 본 프로젝트와 무관하므로 재사용하지 않고 손대지 않는다 |
| 기존 설정 | `.claude/skills/rea/` — 이전 대화에서 만든 범용 역설계 방법론 스킬 (공식 REA와 별개) |
| CLAUDE.md, docs | 없었음 → 이번에 생성 |

### 2.2 개발 환경 (클라우드 컨테이너)
| 도구 | 상태 |
|---|---|
| OpenJDK | 21.0.12 ✅ |
| Gradle / Maven | 8.14.3 / 3.9.11 ✅ |
| Node / npm | 22.23.4 / 10.9.9 ✅ (REA CLI 실행 조건 충족) |
| PostgreSQL | psql 16.15 클라이언트 ✅, Docker 29.9 ✅ (DB 컨테이너 기동 가능) |
| Android SDK / adb / Kotlin CLI | ❌ 없음 |
| 실물 Android 기기 · 바로잰 Fit 센서 | ❌ 연결 불가 (클라우드 환경) |
| 자원 | 4 CPU, 15 GB RAM, 디스크 여유 약 30 GB |

→ Backend 개발·테스트는 이 환경에서 가능. **Android 빌드와 실기기 검증(STEP 2, 5, 8)은 사용자 PC + 휴대폰이 필요하다.**
Android SDK는 설치 가능하지만, 기기 없는 상태에서는 컴파일·단위 테스트까지만 검증된다.

### 2.3 Claude Code MCP 연결 상태
| 확인 항목 | 결과 |
|---|---|
| 사용자 설정 MCP 서버(`~/.claude.json`) | 없음 |
| 프로젝트 `.mcp.json` | 없음 |
| 세션에 연결된 MCP | GitHub, Notion, Google Drive, Claude Docs, claude-code-remote |
| **REA MCP** | **미연결** — `rea` 이름의 서버·도구(`open_binary` 등) 없음 |

따라서 REA Tool 목록·입력 스키마는 **실측하지 못했다**. 아래 2.4는 공개 문서 기준이며 연결 후 `tools/list`로 재확인해야 한다.

### 2.4 REA의 실체 — 마스터 프롬프트 정의와의 차이 (중요)
공개 프로젝트 [morluto/rea](https://github.com/morluto/rea) (MIT, npm `rea-agents` v6.8.0, 스킬 버전 59)를 확인했다.

| 마스터 프롬프트의 가정 | 실제 (공개 문서 기준) |
|---|---|
| "API 분석 및 **생성** 도구" | API를 생성하는 기능은 없다. **배포된 결과물(APK·실행파일·펌웨어)과 기록된 트래픽을 분석**하는 도구다 |
| 원격 서버 API를 분석 | REA는 **로컬 파일과 로컬에서 기록한 것만** 분석한다. 서버에 직접 질의하지 않는다 |
| 인증 구조 분석 기능 | 전용 기능은 없음. APK 코드·HAR 기록을 읽어 사람이 추론하는 수준 |
| 문서 생성 기능 | 없음. 근거(Evidence) 레코드를 돌려주고 문서는 에이전트가 작성 |

본 프로젝트에 쓸모 있는 REA 도구 (문서 기준, 연결 후 확인 필요):
| 도구 | 용도 | 비고 |
|---|---|---|
| `inspect_android_package` | 바로잰 Fit/Care APK의 클래스·메서드·참조 정적 분석 (JADX 사용) | Java + JADX 필요 |
| `inspect_apk_signature` | APK 서명·무결성 확인 | 엔진 불필요 |
| `capture_browser_scenario` | 브라우저 조작을 기록 (예: 바로잰 Care 웹 로그인 후 화면 이동) | 실행형 — 범위 명시 필요 |
| `inspect_web_network_capture` | HAR 기록 분석 (Endpoint·Header·응답 구조) | 기록된 URL을 다시 호출하지 않음 |
| `list_browser_targets` | 이미 열린 브라우저 탭 분석 | 사용자 브라우저 필요 |

REA 연결 방법 (택1, 모두 외부 npm 패키지 실행이므로 사용자 승인 필요):
- (권장) 사용자 PC에서 `npx rea-agents setup` → Claude Code 재시작. APK·브라우저·휴대폰이 모두 사용자 PC 쪽에 있기 때문.
- 이 클라우드 세션에 `.mcp.json`으로 등록 (`npx -y rea-agents@6.8.0 mcp`). 이전 대화에서 자동 권한 검사에 의해 차단된 바 있음 → 사용자의 명시적 허용 필요.

### 2.5 바로잰 Fit 사전 조사 (공개 자료, STEP 1 준비용)
| 항목 | 내용 | 등급 |
|---|---|---|
| 브랜드 / 제조 | 한독 브랜드, 제조 (주)아이센스 송도공장, 모델 CGM-ST-003 | 확인 (App Store 표기) |
| 공식 앱 | 센서 수신용 **바로잰 Fit** 앱 + 분석·공유용 **바로잰 Care** 앱/웹 | 확인 |
| **공식 가족 공유** | 바로잰 Care에서 가족·보호자·의료진에게 혈당 공유 기능 **이미 제공** | 확인 (한독 보도자료) |
| Samsung Health 연동 | 2024-10-22부터 삼성 헬스와 연동 | 확인 (언론 보도) |
| Apple Health 연동 | iOS 앱이 건강 앱으로 혈당 전송 | 확인 (App Store 설명) |
| Health Connect(Android) 기록 여부 | 자료 없음 | **UNKNOWN** |
| 공개 개발자 API | 찾지 못함 | **UNKNOWN** (한독 문의 필요) |
| 측정 주기, 클라우드 업로드 지연 | 자료 없음 | **UNKNOWN** |

## 3. 기술적 결정 (잠정 — STEP 1에서 확정)
- ADR-0001: REA는 개발 단계 분석 도구로만 사용하고, 수집 경로는 **공식 경로 우선**으로 평가한다.
- 저장소는 모노레포(`backend/`, `android/`, `docs/`, `poc/`)로 구성한다.

## 4. 확인된 사실
2.2~2.5의 "확인" 항목. 출처:
- https://github.com/morluto/rea
- https://handok.co.kr/board/en_pressView?idx=4281
- https://apps.apple.com/KR/app/id6479621095
- https://medigatenews.com/news/832049557 (삼성 헬스 연동 보도)

## 5. 미확인 사항
| 항목 | 확인 방법 |
|---|---|
| REA 실제 Tool 목록·스키마 | REA 연결 후 `tools/list` |
| 바로잰 Fit Android 앱의 Health Connect 기록 여부 | 휴대폰: 설정 → Health Connect → 앱 권한에 바로잰 Fit이 있는지, 혈당 데이터가 보이는지 |
| 삼성 헬스 → Health Connect 혈당 동기화 여부 | 휴대폰: 삼성 헬스 설정 → Health Connect 동기화 항목 확인 |
| Samsung Health Data SDK의 혈당 읽기·배포 조건 | Samsung 개발자 문서·파트너 승인 정책 확인 |
| 한독 공식 API·제휴 가능성 | 한독 고객센터(080-850-5001)/제휴 문의 |
| 바로잰 Care 이용약관의 자동화 접근 조항 | 약관 원문 확인 |
| 본 서비스의 의료기기 소프트웨어 해당 여부 | 식약처 디지털의료제품 분류 기준 검토 |

## 6. 테스트 결과
코드 구현이 없으므로 빌드·테스트는 미실행. 환경 도구 버전 확인만 실행함.

## 7. 향후 작업 (STEP 1 착수 전 결정 필요)
1. 공식 가족 공유(바로잰 Care)가 이미 있는 상황에서 본 서비스의 차별점 정의
2. REA 연결 위치 결정 (사용자 PC 권장)
3. 실물 기기·계정 제공 가능 여부
4. 휴대폰에서 Health Connect / 삼성 헬스 상태 확인 (5분 작업, 가장 빠른 판별)

## 변경 이력
| 일자 | 변경 내용 |
|---|---|
| 2026-11-04 | 최초 작성 |
