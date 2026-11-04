# STEP 1 REA 연습 보고서 — 가상 클라우드 API 역설계

- 작성 목적: REA 방법론(관찰 → 분해 → 가설 → 검증)을 정당한 대상(우리 에뮬레이터)에 적용해 API 분석 절차를 연습하고, 실제 연동 시 쓸 도구를 준비한다.
- 작성 일자: 2026-11-04
- 관련 STEP: 1

## 1. 요구사항
REA MCP는 이 환경에서 실행이 차단되었으므로(STEP1 보고서 2.4), REA가 읽는 것과 같은 입력(HAR)을 만들어 같은 절차를 수행한다.

## 2. 상세 내용
### 2.1 절차
1. **관찰**: Playwright(Chromium)로 브라우저에서 API를 호출하며 HAR 기록 — `poc/rea-practice/capture_har.mjs`
2. **분해**: HAR에서 엔드포인트·메서드·인증 방식·파라미터·응답 스키마 추출 — `poc/rea-practice/har_analyze.py`
   - 민감정보(토큰·비밀번호·Authorization)는 값 대신 `<redacted>`, 혈당값은 타입만 남긴다.
3. **가설**: 아래 2.3
4. **검증**: 에뮬레이터 테스트(18개)로 가설 확인

HAR 파일은 토큰이 들어 있으므로 저장소에 올리지 않는다 (`.gitignore`).
REA 연결 후에는 같은 HAR을 `inspect_web_network_capture`(format: har)에 넣어 결과를 비교한다.

### 2.2 분석 결과 (har_analyze.py 출력, 2026-11-04 실행)
#### HAR 분석 결과 — 11개 요청, 7개 엔드포인트

| 엔드포인트 | 인증 | 쿼리 파라미터 | 관찰된 상태코드 |
|---|---|---|---|
| `POST /api/v1/auth/token` | none | - | 200 |
| `GET /api/v1/me` | Bearer, none | - | 200, 401 |
| `GET /api/v1/sensors/current` | Bearer | - | 200 |
| `GET /api/v1/glucose` | Bearer | cursor, from, limit | 200 |
| `GET /api/v1/glucose/latest` | Bearer | - | 200 |
| `GET /hc/v1/blood-glucose-records` | Bearer | from | 200 |
| `POST /api/v1/auth/refresh` | none | - | 200 |

#### `POST /api/v1/auth/token`

요청 본문:
```json
{
  "username": "string",
  "password": "<redacted>"
}
```
응답 200:
```json
{
  "expiresAt": "datetime(UTC)",
  "tokenType": "string",
  "accessToken": "<redacted>",
  "refreshToken": "<redacted>",
  "expiresIn": "number"
}
```

#### `GET /api/v1/me`

응답 200:
```json
{
  "unit": "string",
  "timeZone": "string",
  "patientId": "string",
  "displayName": "string"
}
```
응답 401:
```json
{
  "error": "string",
  "message": "string"
}
```

#### `GET /api/v1/sensors/current`

응답 200:
```json
{
  "serial": "string",
  "state": "string",
  "startedAt": "datetime(UTC)",
  "firstReadingAt": "datetime(UTC)",
  "expiresAt": "datetime(UTC)",
  "serverTime": "datetime(UTC)"
}
```

#### `GET /api/v1/glucose`

응답 200:
```json
{
  "items": [
    {
      "id": "string",
      "sensorSerial": "string",
      "seq": "number",
      "value": "number",
      "unit": "string",
      "flag": "string",
      "trend": "string",
      "trendRate": "number",
      "measuredAt": "datetime(offset)",
      "uploadedAt": "datetime(UTC)"
    }
  ],
  "nextCursor": "null|string",
  "serverTime": "datetime(UTC)"
}
```

#### `GET /api/v1/glucose/latest`

응답 200:
```json
{
  "serverTime": "datetime(UTC)",
  "item": {
    "id": "string",
    "sensorSerial": "string",
    "seq": "number",
    "value": "number",
    "unit": "string",
    "flag": "string",
    "trend": "string",
    "trendRate": "number",
    "measuredAt": "datetime(offset)",
    "uploadedAt": "datetime(UTC)"
  }
}
```

#### `GET /hc/v1/blood-glucose-records`

응답 200:
```json
{
  "serverTime": "datetime(UTC)",
  "records": [
    {
      "recordType": "string",
      "time": "datetime(UTC)",
      "zoneOffset": "string",
      "level": {
        "inMilligramsPerDeciliter": "number"
      },
      "specimenSource": "string",
      "mealType": "string",
      "relationToMeal": "string",
      "metadata": {
        "lastModifiedTime": "datetime(UTC)",
        "id": "string",
        "dataOrigin": {
          "packageName": "string"
        },
        "clientRecordId": "string"
      }
    }
  ]
}
```

#### `POST /api/v1/auth/refresh`

요청 본문:
```json
{
  "refreshToken": "<redacted>"
}
```
응답 200:
```json
{
  "expiresAt": "datetime(UTC)",
  "tokenType": "string",
  "accessToken": "<redacted>",
  "refreshToken": "<redacted>",
  "expiresIn": "number"
}
```

### 2.3 HAR만 보고 세운 가설 → 검증 결과
| 가설 (HAR에서 도출) | 등급 | 검증 |
|---|---|---|
| Bearer 토큰 인증, 토큰 없으면 401 | 확인 | HAR에 401 관찰 + 테스트 |
| refresh 시 refresh token도 새로 발급(회전), 이전 것은 무효 | 추론 → 확인 | `expiredTokenIsRejectedAndRefreshWorks` |
| `nextCursor`가 null이 될 때까지 페이지를 돈다 | 확인 | HAR의 연속 요청 |
| `measuredAt`은 로컬 오프셋, `uploadedAt`·`serverTime`은 UTC → 시간대 정규화 필요 | 확인 | HAR 스키마 |
| `value`가 null일 수 있다(범위 밖) | 가정 → 확인 | HAR에는 안 나옴. 코드·테스트로 확인 → **HAR 한 번으로는 드문 경우를 못 본다** |
| rate limit 존재 | 가정 | HAR에 없음. 테스트로만 확인 (`Retry-After: 60`) |

### 2.4 배운 점
- 한 번의 기록은 **정상 경로만** 보여준다. LO/HI, 429, 5xx, backfill 같은 드문 경우는 시나리오를 바꿔 여러 번 기록해야 한다.
- 실제 서비스 분석이었다면 rate limit을 확인하려고 요청을 반복하는 행위 자체가 약관 위반·서비스 방해가 될 수 있다 → 에뮬레이터에서만 한다.

## 3. 기술적 결정
- HAR 분석 도구는 개발용으로만 두고 운영 코드와 분리한다 (`poc/`).

## 4. 확인된 사실
2.2의 표는 실제 실행 결과다. 분석 결과에 비밀번호·토큰 노출 0건(스크립트로 대조 확인).

## 5. 미확인 사항
- REA `inspect_web_network_capture` 결과와의 비교 — REA 실행 허용 후

## 6. 테스트 결과
- HAR 11개 요청, 7개 엔드포인트, 상태코드 200/401 관찰

## 7. 향후 작업
- 시나리오별(SIGNAL_LOSS, UPLOAD_OUTAGE, 장애 주입) HAR을 추가 기록해 비교

## 변경 이력
| 일자 | 변경 내용 |
|---|---|
| 2026-11-04 | 최초 작성 |
