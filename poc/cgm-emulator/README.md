# 가상 CGM 에뮬레이터 (Virtual CGM Emulator)

실물 바로잰 Fit 기기·계정 없이 Family Glucose Care의 수집·저장·알림 흐름을 개발·시험하기 위한 에뮬레이터.
**실제 바로잰 API를 흉내 낸 것이 아니다.** 엔드포인트·필드명은 연습용으로 새로 정의했다 (ADR-0002).

## 사양
| 항목 | 값 | 근거 |
|---|---|---|
| 측정 주기 | 5분 | 바로잰 Fit 공개 사양 |
| 센서 수명 | 15일 | 공개 사양 |
| 안정화 | 30분 (값 없음) | 공개 사양 |
| 표시 범위 | 40–400 mg/dL, 밖이면 value=null + flag | [가정] |
| 추세 화살표 | 15분 변화율 ±1/2/3 mg/dL/min | [가정] |
| 업로드 지연 | 60초 + 0~120초 | [가정] |
| Health Connect 기록 지연 | 10분 | [가정] |

## 시나리오
`NORMAL`, `NIGHT_HYPO`(02:00~03:30 저혈당), `POST_MEAL_HYPER`, `RAPID_DROP`(15:00 급강하), `SIGNAL_LOSS`(10:00~10:40 값 없음), `UPLOAD_OUTAGE`(13:00~14:00 업로드 중단 후 backfill). 시각은 Asia/Seoul.

## 실행
```bash
cd poc/cgm-emulator
export EMU_USERNAME=patient01 EMU_PASSWORD='<직접 정한 값>'   # 없으면 임의 비밀번호를 만들어 출력
gradle run --args="--scenario NIGHT_HYPO --speed 60 --port 8090 --duplicate-rate 0.05 --error-rate 0.02"
```
옵션: `--scenario`, `--speed`(배속), `--port`, `--seed`, `--host`(기본 127.0.0.1), `--duplicate-rate`, `--error-rate`, `--sensor-age-hours`(기본 24)

## API (가상)
| 메서드 · 경로 | 설명 |
|---|---|
| `POST /api/v1/auth/token` | `{username,password}` → access/refresh 토큰 (TTL 1시간, 에뮬레이터 시간) |
| `POST /api/v1/auth/refresh` | refresh 토큰 회전 |
| `GET /api/v1/me` | 환자 정보 |
| `GET /api/v1/sensors/current` | 센서 상태 (WARMUP/ACTIVE/SIGNAL_LOSS/EXPIRED) |
| `GET /api/v1/glucose?from&to&limit&cursor` | 측정 시각 기준 조회. **업로드된 값만** 보임 |
| `GET /api/v1/glucose/latest` | 최근 값 |
| `GET /hc/v1/blood-glucose-records?from&to` | Health Connect `BloodGlucoseRecord` 모양 |
| `GET /emulator/state`, `POST /emulator/advance?minutes=N` | 에뮬레이터 제어 (로컬 전용, 인증 없음) |

장애: 토큰당 분당 120회 초과 시 429(`Retry-After`), `--error-rate` 비율로 503, `--duplicate-rate` 비율로 응답 내 중복.

## 테스트
```bash
gradle test   # 18개
```
