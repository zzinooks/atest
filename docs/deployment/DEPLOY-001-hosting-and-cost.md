# DEPLOY-001 무료 운영 검토와 배포

- 작성 일자: 2026-11-04 · 관련 STEP: 8 · 결정: ADR-0005

## 1. 핵심 판단
**무료 운영은 가능하지만, 무료로 "24시간 끊김 없는 알림"을 보장할 수는 없다.** 두 가지를 따로 평가했다.

이 서비스는 서버가 1분마다 동기화·미수신 검사·알림 전송을 해야 한다. 서버가 잠들면 알림이 멈춘다 → "요청이 없으면 잠드는" 무료 플랫폼은 부적합.

## 2. 후보 평가 (2026-11 웹 조사, 출처 하단)
| 항목 | 후보 | 비용 | 24시간 동작 | 판정 |
|---|---|---|---|---|
| Backend | Render Free | 무료 | ❌ 15분 무요청 시 잠듦 → 스케줄러 정지 | 부적합 |
| DB | Render Free Postgres | 무료 | 생성 30일 후 만료, 백업 없음 | 부적합 |
| Backend+DB | **Oracle Cloud Always Free VM (ARM)** | 무료 | ✅ 상시 가동 VM. 단, 7일간 사용률이 낮으면 회수될 수 있음, 2026년 할당량 축소 보도(미확인), 지역별 용량 부족 | **연습·시범 운영 권장** |
| DB | Neon Free | 무료 | 유휴 시 정지 후 재개(지연), 용량 0.5~1GB(출처 상충) | 대안 |
| Push | FCM | 무료(전송량 과금 없음, 분당 할당량 있음) | ✅ | 채택 |
| Notion API | 무료 | 평균 초당 3요청 | ✅ (하루 수 건) | 채택 |
| 도메인·HTTPS | Let's Encrypt (Caddy 자동) | 무료 / 도메인은 유료 가능 | ✅ | 채택 |
| 앱 배포 | Google Play 개발자 등록 | **US$25 1회** | - | 비용 발생 (내부 테스트는 APK 직접 설치로 무료) |

## 3. 권장 구성
- **연습·가족 시범 운영**: Oracle Always Free VM 1대 + `deploy/docker-compose.yml`(Backend + PostgreSQL + Caddy HTTPS) + 매일 `deploy/backup.sh` + 외부 무료 가동 감시(헬스체크 URL 호출).
- **실제로 가족의 안전을 맡길 때**: 회수·정지 위험이 없는 소액 유료 VPS 또는 관리형 DB로 전환 권장 (비용은 전환 시점에 확인). 무료 VM 회수는 예고 없이 알림 중단으로 이어진다.
- 어떤 구성이든: 앱에 "알림이 늦거나 오지 않을 수 있음"을 상시 안내 (구현됨).

## 4. 배포 절차 (VM)
```bash
# VM (Ubuntu, Docker 설치 후)
git clone <repo> /opt/fgc && cd /opt/fgc
(cd backend && ./gradlew bootJar)              # 또는 gradle bootJar
cd deploy && cp .env.example .env && vi .env   # 비밀값 생성·입력, 도메인 지정
mkdir -p secrets && cp <firebase-service-account>.json secrets/fcm.json && chmod 600 secrets/fcm.json .env
docker compose up -d --build
curl https://<도메인>/actuator/health
crontab -e   # 40 3 * * * /opt/fgc/deploy/backup.sh
```
FCM 을 쓰지 않을 때는 compose 의 `FGC_FCM_CREDENTIALS`·volume 줄을 지운다 (dev-outbox, 실제 알림 없음).

## 5. 검증 상태
| 항목 | 상태 |
|---|---|
| JAR 실행·Flyway 마이그레이션·스케줄러 | ✅ 이 환경에서 실프로세스로 확인 |
| 재시작 후 데이터 유지·스케줄 재개 | ✅ |
| 백업 덤프 → 다른 DB 복원, 행 수 일치 | ✅ (`pg_dump`/`psql` 로 스크립트 핵심 명령 검증) |
| Docker 이미지·compose·Caddy HTTPS | ❌ 미검증 (이 환경에 Docker 데몬 없음) |
| 실제 클라우드 배포 | ❌ 미실시 |

## 출처
- Render Free: https://render.com/docs/free
- Oracle Always Free: https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm , https://infoq.com/news/2026/07/oracle-cloud-free-tier-limits/ (축소 보도)
- Neon: https://neon.com/faqs/free-plan-limits-and-quotas
- Notion 요청 제한: https://developers.notion.com/reference/request-limits
- FCM 할당량: https://firebase.google.com/docs/cloud-messaging/throttling-and-quotas
- Google Play 등록비: https://support.google.com/googleplay/android-developer/answer/6112435
