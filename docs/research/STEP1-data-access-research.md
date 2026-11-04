# STEP 1 보고서 — 바로잰 Fit 데이터 접근 경로 조사

- 작성 목적: 혈당 데이터를 외부 시스템으로 가져올 수 있는 경로를 평가하고 연동 경로를 결정한다.
- 작성 일자: 2026-11-04
- 관련 STEP: 1

## 1. 요구사항
- 마스터 프롬프트 2.3, 5.1: 경로별 접근 가능성·공식 지원·인증·정확성·지연·과거 조회·제한·비용·안정성·약관 평가
- 사용자 조건(2026-11-04): 실물 기기·계정 없음 → 조사 + 에뮬레이터로 대체. 연습 프로젝트. REA 연결 방식은 Claude가 판단.

## 2. 상세 내용

### 2.1 바로잰 Fit 기본 사양
| 항목 | 값 | 등급 / 출처 |
|---|---|---|
| 개발·제조 | 아이센스 개발·제조, 한독은 국내 판매 (2024-04 계약) | 확인 / 한국경제 |
| 측정·전송 주기 | 5분 | 확인 / barozenfit.com |
| 센서 사용 기간 | 최대 15일 | 확인 / barozenfit.com |
| 안정화(웜업) | 30분, 손끝 보정은 선택 (2025-04 업그레이드) | 확인 / 보도 |
| 앱 | 바로잰 Fit(수신) + 바로잰 Care(분석·공유) | 확인 |
| 아이센스 자체 브랜드 CareSens Air와의 관계 | 같은 제조사 CGM. 동일 플랫폼 여부는 공식 확인 없음 | **추론** |
| 표시 범위, 추세 기준 | 자료 없음 | UNKNOWN |

### 2.2 경로별 평가
| # | 경로 | 공식 | 접근 가능성 | 지연 | 과거 조회 | 비용 | 주요 위험 | 판정 |
|---|---|---|---|---|---|---|---|---|
| 1 | 한독/아이센스 공식 API·제휴 | ✅ | 공개 API **미발견**. 제휴 문의 필요 | UNKNOWN | UNKNOWN | UNKNOWN | 개인 프로젝트에 제휴 가능성 낮음 | 보류 (문의만) |
| 2 | **Health Connect** (Android) | ✅ | 바로잰 Fit Android 앱이 기록하는지 **UNKNOWN**. 삼성 헬스는 혈당을 Health Connect `BloodGlucoseRecord`로 매핑한다는 삼성 공식 블로그가 있으나, 연속혈당 스트림은 삼성 헬스 안에 머문다는 제3자 문서도 있음 → **상충** | 기록 앱의 쓰기 주기에 좌우 (UNKNOWN) | 권한 부여 이전 데이터는 제한됨 (공식 문서 재확인 필요) | 무료 | 원천 앱이 안 쓰면 경로 자체가 없음 | **1순위 후보** (실기기 확인 필요) |
| 3 | Samsung Health Data SDK | ✅ | 혈당 읽기 지원. **삼성 파트너 승인 필수** (개발자 모드로 테스트만 가능) | UNKNOWN | 지원 | 무료(승인 필요) | 개인·연습 프로젝트는 승인 어려움 | 제외 (조사만) |
| 4 | 공식 앱의 xDrip+ 연동 → xDrip+ → Nightscout 호환 업로드 | △ (제조사 앱의 공식 설정 + 오픈소스) | CareSens Air 공식 앱에 "xDrip 데이터 연결" 설정이 있음 (AndroidAPS 문서). 바로잰 Fit 앱에도 있는지 **UNKNOWN** | 거의 실시간 (추론) | xDrip+ 보관분 | 무료 | xDrip+는 인증 의료기기가 아님. 사용자가 앱 2개를 설치·설정해야 함 | **2순위 후보** |
| 5 | 바로잰 Care 비공개 API (REA로 분석) | ❌ | 본인 계정 없음 → 현재 분석 불가. 약관·권한 UNKNOWN | UNKNOWN | UNKNOWN | 무료 | 약관 위반·차단·API 변경 시 알림 서비스 중단 | **제외** (ADR-0001) |
| 6 | 센서 BLE 직접 수신 | ❌ | 독점·암호화 프로토콜 가능성, 공식 앱과 동시 연결 문제 | - | - | - | 법적·안전 위험 큼 | **제외** |

### 2.3 법적·규제 검토 (요약, 운영 전 전문가 검토 필수)
| 항목 | 내용 |
|---|---|
| 개인정보보호법 | 건강정보는 **민감정보**(제23조) → 별도 동의, 안전조치 의무. 가족 공유도 환자의 별도 동의 필요 |
| 저작권법 제101조의4 (프로그램 역분석) | 정당한 권한으로 프로그램을 쓰는 자가 **호환에 필요한 정보를 쉽게 얻을 수 없을 때** 필요한 부분만 허용. 현재 권한(본인 계정·라이선스)이 없으므로 경로 5 분석 근거 없음 |
| 의료기기 해당성 | 다른 사람의 혈당을 받아 저혈당 알림을 보내는 소프트웨어는 의료기기 소프트웨어로 분류될 가능성이 있음 → 운영 전 식약처 기준 검토. 연습 단계에서는 "의료 판단용 아님" 표시 |
| 이용약관 | 바로잰 Care 약관의 자동화 접근·제3자 제공 조항 미확인 |

### 2.4 REA MCP 사용 결과
- 이 컨테이너에서 `npx rea-agents` 실행이 **자동 권한 검사로 두 차례 차단**됨 (외부 패키지 실행). 우회하지 않음.
- 대신 REA 방법론을 **우리 에뮬레이터의 기록된 트래픽(HAR)**에 적용해 연습함 → `docs/research/STEP1-rea-practice.md`.
  REA의 `inspect_web_network_capture`가 같은 HAR 형식을 읽으므로, REA 연결 후 그대로 재실행할 수 있다.
- 바로잰 앱(APK)은 분석하지 않음: 정당한 사용 권한(본인 기기·계정)이 없고, 비공식 미러에서 받은 APK는 무결성도 보장되지 않기 때문.

### 2.5 실기기 대체: 가상 CGM 에뮬레이터
- Android 에뮬레이터: 이 컨테이너에 `/dev/kvm`이 없어 **Android Emulator 실행 불가** (확인). Health Connect 실기 검증은 사용자 PC의 Android Studio 에뮬레이터(API 34+, Health Connect 내장)에서 STEP 5에 수행.
- 대신 **가상 CGM 에뮬레이터** (`poc/cgm-emulator`)를 만들었다. 바로잰 Fit 공개 사양(5분, 15일, 30분 안정화)을 따르고, 수집 경로 2·4·(클라우드형) 어댑터를 시험할 수 있는 형태로 데이터를 낸다.
  - 시나리오 6종: 정상, 야간 저혈당, 식후 고혈당, 급강하, 신호 끊김, 업로드 장애(backfill)
  - 장애 주입: 중복 응답, 5xx, 429 rate limit, 토큰 만료·회전
  - Health Connect `BloodGlucoseRecord` 모양의 JSON 출력 (기록 지연 포함)

### 2.6 조사·연습에서 나온 설계 요구사항 (STEP 3 입력)
| ID | 요구사항 | 근거 |
|---|---|---|
| D-1 | 측정 시각 기준 조회만 있는 API는 **업로드 지연·backfill 값을 놓친다** → 수집 시 겹치는 조회 구간(lookback, 예: 3시간) + `externalId` 기준 중복 제거(upsert) | 에뮬레이터 UPLOAD_OUTAGE 시나리오 |
| D-2 | 응답 안에서도 중복이 올 수 있다 → DB 유니크 키 `(source, externalId)` | 에뮬레이터 duplicateRate |
| D-3 | 시각 표현이 섞여 있다 (`measuredAt`은 +09:00 오프셋, `uploadedAt`은 UTC) → 저장은 UTC `timestamptz` + 원본 오프셋 별도 보존 | HAR 분석 |
| D-4 | LO/HI(범위 밖) 값은 숫자가 없다 → `value` nullable + `flag`. Health Connect는 LO/HI를 표현 못함 | 에뮬레이터 설계 |
| D-5 | 데이터 미수신 판단 기준: "마지막 **측정 시각**"으로 판단 (수신 시각 아님). 센서 안정화·만료 상태는 미수신 알림과 구분 | 마스터 프롬프트 7.2 |
| D-6 | 429/5xx 재시도는 지수 백오프 + `Retry-After` 준수 | 에뮬레이터 |

## 3. 기술적 결정
ADR-0002 참고. 요약:
- 실서비스 목표 경로: ① Health Connect ② xDrip+ 경유 Nightscout 호환 업로드 수신. ③ 공식 제휴는 문의만.
- 개발·연습 경로: 가상 CGM 에뮬레이터 + 어댑터 (`EmulatorCloudAdapter`, `HealthConnectAdapter`의 매핑 로직).

## 4. 확인된 사실 (출처)
- https://www.barozenfit.com/ (5분, 15일)
- https://www.hankyung.com/article/202405096755i (아이센스 개발, 한독 판매)
- https://handok.co.kr/board/en_pressView?idx=4281 (바로잰 Care 공유, TIR 등)
- https://medigatenews.com/news/832049557 (삼성 헬스 연동)
- https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect (삼성 헬스 ↔ Health Connect 혈당 매핑)
- https://docs.junction.com/wearables/guides/samsung-health (제3자: 연속혈당은 Health Connect로 안 나간다는 주장, 파트너 승인)
- https://androidaps.readthedocs.io/en/latest/CompatibleCgms/Caresens.html (CareSens 공식 앱 xDrip 연결 설정 — 검색 요약으로 확인, 원문은 네트워크 정책상 직접 열람 실패)
- https://caresensair.com/ko_KR/theme/caresensair/download_data/PCGAA-000013_REV1_CS_Air_Manual_KO_240315.pdf (사용설명서 목차)

## 5. 미확인 사항
| 항목 | 확인 방법 | 필요한 것 |
|---|---|---|
| 바로잰 Fit → Health Connect 기록 여부 | 휴대폰 Health Connect 앱 권한 목록 | 실기기 |
| 삼성 헬스 → Health Connect로 CGM 혈당이 넘어가는지 | Health Connect 데이터 보기 | 실기기 + 삼성 헬스 |
| 바로잰 Fit 앱의 xDrip 연동 설정 유무 | 앱 설정 메뉴 | 실기기 |
| Health Connect 과거 데이터·백그라운드 읽기 권한 조건 | 공식 문서 재확인 | 문서 |
| 실제 지연 시간 (측정 → 각 경로 도착) | STEP 2 PoC 측정 | 실기기 |

## 6. 테스트 결과
- `poc/cgm-emulator`: `gradle test` **18개 통과, 0개 실패** (2026-11-04). 첫 실행에서 1건 실패 → 실제 버그(오류 응답이 전송되지 않음) 수정 후 통과.
- HAR 기록·분석: 11개 요청, 7개 엔드포인트 복원. 분석 결과에 비밀번호·토큰 노출 0건 확인.
- 실제 바로잰 데이터 검증: **미실행** (기기·계정 없음).

## 7. 향후 작업
- STEP 2 (대체 PoC): 에뮬레이터 → 수집 어댑터 → PostgreSQL 저장까지 실제로 흘려 정확성·중복·지연·누락 탐지를 수치로 검증
- 실기기 확보 시: 5장 미확인 사항 확인 → ADR-0002 갱신

## 변경 이력
| 일자 | 변경 내용 |
|---|---|
| 2026-11-04 | 최초 작성 |
