# ANDROID-001 Android 앱 구조와 빌드

- 작성 일자: 2026-11-04 · 관련 STEP: 5

## 구조
| 모듈 | 내용 | 검증 |
|---|---|---|
| `:core` (Kotlin/JVM) | API 클라이언트(토큰 자동 갱신), 표시 규칙(신선도·추세·수준·경고 문구), 그래프 구간 분리, Health Connect 변환·읽기 구간 | 테스트 14개 통과 + **실제 Backend 대상 계약 테스트 통과** |
| `:app` (Android) | Compose 화면, Health Connect 읽기 + WorkManager 업로드, FCM 수신, 암호화 토큰 저장 | **빌드·실행 미검증** (아래) |

화면: 로그인/가입 → 홈(역할별: 내 혈당 카드, 가족 목록, 초대 코드 입력) → 환자 상세(현재 혈당·측정 시각·신선도 경고, 3/6/24시간 그래프, 진행 중 알림·확인, 오늘 통계) → 가족 공유 / 알림 기준 / 기록·메모 / 설정(Health Connect 연결·원천 앱 선택, 에뮬레이터 연결, Notion 동의, 로그아웃, 탈퇴).

## 왜 :app 이 미검증인가
이 개발 환경의 네트워크 정책이 `dl.google.com`(Android SDK, Google Maven 의 실제 파일 위치)을 차단한다. 그래서 Android Gradle Plugin·AndroidX 를 내려받을 수 없어 컴파일 자체를 하지 못했다. 컴파일 오류가 있을 수 있다.

## 사용자 PC 에서 빌드·실행
1. Android Studio 설치 → `android/` 폴더 열기 (SDK 경로가 `local.properties` 에 생기면 `:app` 이 자동 포함)
2. 에뮬레이터: API 34 이상 Google APIs 이미지 (Health Connect 내장)
3. Backend 를 PC 에서 실행 → 앱은 `http://10.0.2.2:8080` 으로 접속 (디버그 빌드만 평문 허용)
4. FCM: Firebase 프로젝트 생성 → `app/google-services.json` 배치(커밋 금지) → Backend 에 서비스 계정 키 설정
5. 연습 데이터: `poc/cgm-emulator` 실행 → 앱 설정의 "에뮬레이터 연결"에 `http://127.0.0.1:8090`(Backend 기준 주소) 입력

## 알려진 한계
- Health Connect 경로는 WorkManager 최소 주기 15분 → 저혈당 실시간성 낮음. 미수신 알림(30분)이 안전망.
- 바로잰 Fit 의 Health Connect 기록 여부·패키지명 UNKNOWN → 설정에서 혈당을 기록한 앱 목록을 보여주고 사용자가 고르게 했다.
- 사용 라이브러리 버전(connect-client 1.1.0-alpha10, security-crypto 1.1.0-alpha06 등)은 빌드 시 최신 안정판으로 재확인 필요.
