# SETUP-001 외부 서비스 준비 가이드 (아주 쉽게)

- 작성 목적: Firebase 프로젝트, Notion 통합 토큰, 서버(VM), 도메인을 처음 해보는 사람도 따라 할 수 있게 설명한다.
- 작성 일자: 2026-11-05
- 관련: DEPLOY-001, ADR-0005, BACKEND-001, ANDROID-001
- 같은 내용의 HTML 판: `SETUP-001-external-services.html` (브라우저로 열면 체크박스로 진행 상황을 표시할 수 있음)

> 화면 메뉴 이름은 서비스가 자주 바꾼다. 이 문서의 버튼 이름이 조금 달라도 비슷한 이름을 찾으면 된다.
> 이 문서의 화면 경로는 2026-11 기준 기억·공개 문서에 따른 것이며, 실제로 클릭해 보며 검증하지는 않았다.

---

## 0. 큰 그림 — 네 가지가 왜 필요할까?

우리 서비스를 **동네 소식지 배달**에 비유하면 이렇다.

| 준비물 | 비유 | 하는 일 |
|---|---|---|
| **Firebase 프로젝트** | 🏤 우체국 | 서버가 "할머니 혈당이 낮아요!" 알림을 가족 휴대폰으로 **배달**해 준다 |
| **Notion 통합 토큰** | 🔑 일기장 열쇠 | 서버 로봇이 내 Notion 일기장에 **하루 요약을 대신 써 준다** |
| **VM (가상 서버)** | 💻 24시간 켜진 빌린 컴퓨터 | 1분마다 혈당을 가져오고, 알림을 판단하는 **우리 서버가 사는 집** |
| **도메인** | 🏠 집 주소 | `152.67.x.x` 같은 숫자 대신 `my-fgc.duckdns.org` 처럼 **기억하기 쉬운 주소**. HTTPS(봉인된 편지)를 쓰려면 꼭 필요 |

**순서가 중요하다.** 아래 순서대로 하면 막히지 않는다.

1. Firebase (15분) → 2. Notion (15분) → 3. VM (30~60분) → 4. 도메인 (15분) → 5. 서버에 모두 꽂기 (20분) → 6. 앱 빌드 (20분)

### 🔒 절대 규칙 세 가지 (먼저 읽기)

1. **비밀 파일·비밀 글자는 GitHub에 올리지 않는다.** 서비스 계정 키(`.json`), Notion 토큰(`ntn_...`), `.env` 파일, SSH 키가 여기에 해당한다. (`.gitignore`에 이미 막아 두었지만 직접 `git add` 하면 올라간다.)
2. 비밀 글자를 채팅·메신저·스크린샷으로 남에게 보내지 않는다. Claude에게도 붙여 넣지 말고, 서버의 `.env`에 직접 입력한다.
3. 실수로 노출됐으면 **즉시 새로 만들고 옛것은 삭제**한다 (각 단계 끝의 "잃어버렸을 때" 참고).

---

## 1. Firebase 프로젝트 만들기 (🏤 우체국 등록)

### 1-1. 무엇을 얻게 되나
- `google-services.json` — **앱용 주소표**. 앱이 "나는 이 우체국 소속이야"라고 말할 때 쓴다. → `android/app/` 에 넣는다.
- 서비스 계정 키 `.json` — **서버용 인감도장**. 서버가 "이 편지 보내 주세요"라고 우체국에 요청할 때 찍는다. → 서버의 `deploy/secrets/fcm.json` 에 넣는다. **인감도장이라 절대 비밀.**

### 1-2. 프로젝트 만들기
1. 컴퓨터 브라우저로 <https://console.firebase.google.com> 접속 → Google 계정으로 로그인
   - 회사·학교 계정 말고 **개인 Gmail** 을 권장 (회사 계정은 키 만들기가 막혀 있는 경우가 있다).
2. **"프로젝트 만들기"(Create a project)** 클릭
3. 이름: `family-glucose-care` (아무거나 괜찮다)
4. Google 애널리틱스: **사용 안 함**으로 꺼도 된다 (건강 서비스는 불필요한 수집을 줄이는 게 좋다)
5. **"프로젝트 만들기"** → 1분 기다리기 → "계속"

### 1-3. Android 앱 등록 → `google-services.json` 받기
1. 프로젝트 첫 화면에서 **Android 아이콘**(로봇 모양) 클릭
2. **Android 패키지 이름**: 정확히 `com.fgc.app` 입력 ← 한 글자라도 틀리면 알림이 안 온다
3. 앱 닉네임: `가족 혈당 케어` (선택)
4. SHA-1: 비워도 된다 (푸시 알림에는 필요 없음)
5. **"앱 등록"** → **`google-services.json` 다운로드**
6. 나머지 "SDK 추가" 안내는 **건너뛰기** (우리 코드에 이미 들어 있다) → "콘솔로 이동"
7. 받은 파일을 `android/app/google-services.json` 위치에 둔다.
   - 이 파일은 `.gitignore` 로 커밋이 막혀 있다. 그대로 두면 된다.

### 1-4. 서비스 계정 키(서버용 인감도장) 만들기
1. 왼쪽 위 **톱니바퀴 ⚙️ → 프로젝트 설정**
2. 위쪽 탭에서 **"서비스 계정"(Service accounts)**
3. "Firebase Admin SDK" 화면에서 **"새 비공개 키 생성"(Generate new private key)** → "키 생성"
4. `family-glucose-care-firebase-adminsdk-xxxx.json` 같은 파일이 내려받아진다.
5. 이 파일은 **5장에서 서버로 옮길 때까지** 내 컴퓨터의 안전한 폴더에만 둔다.
   - 저장소 폴더(`atest/`) 안에 두지 않는다.

### 1-5. 확인
- "Cloud Messaging" 기능은 새 프로젝트에 기본으로 켜져 있다.
- **프로젝트 설정 → 클라우드 메시징** 탭에서 "Firebase Cloud Messaging API (V1) 사용 설정됨"이 보이면 OK.

### 1-6. 잃어버렸을 때 / 노출됐을 때
- 프로젝트 설정 → 서비스 계정 → "Google Cloud에서 서비스 계정 관리" → 해당 계정의 **키 탭에서 옛 키 삭제** → 1-4를 다시 해서 새 키를 받는다.

### 💰 비용
FCM 알림 전송은 무료다. (분당 전송량 한도는 있지만 가족 단위 사용에는 충분하다.)

---

## 2. Notion 통합 토큰 만들기 (🔑 일기장 열쇠)

### 2-1. 무엇을 얻게 되나
- **통합 비밀(Internal Integration Secret)** — `ntn_` 으로 시작하는 긴 글자 → `NOTION_TOKEN`
- **일별 요약 데이터베이스 ID**(32글자) → `NOTION_DAILY_DB_ID`
- **주간 요약 데이터베이스 ID**(32글자) → `NOTION_WEEKLY_DB_ID`

열쇠(토큰)를 만들어도 **문을 열어 준 방(데이터베이스)에만** 들어갈 수 있다. 그래서 "열쇠 만들기"와 "방 문 열어 주기(연결)" 두 단계가 있다.

### 2-2. 열쇠(통합) 만들기
1. <https://www.notion.so/profile/integrations> 접속 (Notion 로그인 상태)
   - 안 열리면 Notion 왼쪽 아래 **설정 → 연결(Connections) → "통합 개발 또는 관리"** 를 찾는다.
2. **"새 통합"(New integration)** 클릭
3. 이름: `FGC 혈당 요약`, 연결할 워크스페이스 선택, 유형: **내부(Internal)**
4. **저장** → 화면의 **"내부 통합 비밀"** 옆 **표시 → 복사**
   - 지금은 메모장에 잠깐만 두고, 5장에서 서버 `.env` 에 넣은 뒤 메모는 지운다.
5. **기능(Capabilities)** 탭: "콘텐츠 읽기 / 업데이트 / 삽입" 세 개만 체크
   - "사용자 정보"는 **정보 없음**으로 둔다. 필요 없는 권한은 주지 않는다.

### 2-3. 방(데이터베이스) 두 개 만들기
Notion에서 새 페이지 → `/database` 입력 → **"데이터베이스 - 전체 페이지"** 선택.
이름을 `혈당 일별 요약` 으로 하고, 아래 열(속성)을 **이름·종류 그대로** 만든다.
**대소문자까지 똑같아야 한다** — 서버가 이 이름으로 칸을 찾는다.

| 속성 이름 | 종류 | 들어가는 값 |
|---|---|---|
| `Name` | 제목 (기본으로 있는 칸, 이름만 `Name` 으로) | "2026-11-04 혈당 요약" |
| `Date` | 날짜 | 요약한 날 |
| `Patient` | 텍스트 | 환자 이름 |
| `Readings` | 숫자 | 측정 횟수 |
| `Coverage` | 숫자 | 데이터 충족률(%) |
| `Mean` | 숫자 | 평균 |
| `Min` | 숫자 | 최저 |
| `Max` | 숫자 | 최고 |
| `SD` | 숫자 | 표준편차 |
| `CV` | 숫자 | 변동계수(%) |
| `TIR` | 숫자 | 목표 범위 비율(%) |
| `TBR` | 숫자 | 낮음 비율(%) |
| `TAR` | 숫자 | 높음 비율(%) |

같은 방법으로 `혈당 주간 요약` 데이터베이스를 하나 더 만든다. 속성은 위와 **완전히 같다**.

> 팁: 일별 DB를 다 만든 뒤 페이지 메뉴의 **"복제"** 를 쓰면 주간 DB를 빨리 만들 수 있다 (복제 후 이름만 바꾸기).

### 2-4. 방 문 열어 주기 (연결)
각 데이터베이스 페이지에서:
1. 오른쪽 위 **••• (점 세 개) → 연결(Connections) → 연결 추가**
2. `FGC 혈당 요약` 선택 → 확인

**이걸 빼먹으면** 서버 로그에 `HTTP 404` 가 찍히고 요약이 안 써진다. 가장 흔한 실수다.

### 2-5. 데이터베이스 ID 찾기
1. 데이터베이스 페이지에서 **••• → 링크 복사**
2. 주소가 이렇게 생겼다:
   `https://www.notion.so/내워크스페이스/`**`1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d`**`?v=...`
3. **`?v=` 앞의 32글자**가 데이터베이스 ID다. 일별·주간 각각 적어 둔다.
   - 주소가 `혈당-일별-요약-1a2b...` 처럼 제목이 붙어 있으면 **맨 끝 32글자**만 쓴다.

### 2-6. 잃어버렸을 때 / 노출됐을 때
통합 설정 화면 → 비밀 옆 **"새로 고침"(Refresh/Regenerate)** → 옛 토큰은 즉시 무효가 된다 → 서버 `.env` 를 새 값으로 바꾸고 재시작.

### ⚠️ 알아 둘 점
- 환자가 앱 설정에서 **"Notion 요약 기록 동의"** 를 눌러야 그 환자의 요약이 써진다. 건강정보가 외부 서비스로 나가기 때문이다.
- Notion 에 쓰는 것은 **요약**뿐이다. 원본 혈당은 우리 DB에만 있다.
- 우리 서버는 Notion API 버전 `2022-06-28` 을 쓴다. 그래서 위처럼 **평범한 데이터베이스 하나**로 만들어야 한다. 한 데이터베이스에 여러 "데이터 소스"를 붙이는 새 기능은 쓰지 않는다.

### 💰 비용
Notion 무료 요금제로도 API를 쓸 수 있다. 하루에 몇 번만 쓰므로 요청 한도(평균 초당 3회)에 걸리지 않는다.

---

## 3. VM 만들기 (💻 24시간 켜진 빌린 컴퓨터)

우리 서버는 **잠들면 안 된다** (잠들면 저혈당 알림도 멈춘다). 그래서 "요청이 없으면 잠드는" 무료 서비스(Render 무료 등) 대신 **항상 켜진 컴퓨터**를 빌린다.
여기서는 무료인 **Oracle Cloud Always Free** 로 설명한다. 이유와 위험은 `DEPLOY-001` 참고.

### 3-1. 가입
1. <https://www.oracle.com/cloud/free/> → **"무료로 시작하기"**
2. 이메일·이름·주소 입력, **신용카드 등록**(본인 확인용, 무료 범위에서는 청구되지 않음)
3. **홈 리전(Home Region)** 선택: **대한민국 춘천** 또는 **대한민국 서울**
   - ⚠️ **나중에 못 바꾼다.** 가까운 곳을 고른다.
4. 가입 완료까지 몇 분~몇 시간 걸릴 수 있다 (메일 확인).

### 3-2. 열쇠 꾸러미(SSH 키) 준비
SSH 키는 빌린 컴퓨터에 들어가는 **현관 열쇠**다. 인스턴스를 만들 때 Oracle이 만들어 주는 것을 내려받으면 된다 (3-3의 5번).

### 3-3. 컴퓨터(인스턴스) 만들기
1. Oracle 콘솔 왼쪽 위 ☰ → **컴퓨트(Compute) → 인스턴스(Instances) → 인스턴스 생성**
2. 이름: `fgc-server`
3. **이미지**: "편집" → **Canonical Ubuntu 24.04**
4. **셰이프(Shape)**: "셰이프 변경" → **Ampere → VM.Standard.A1.Flex**
   - OCPU **1~2개**, 메모리 **6~12GB** 정도면 충분하다.
   - **"Always Free 적격"** 표시가 붙은 것만 고른다. 표시가 없으면 돈이 나간다.
5. **SSH 키 추가**: **"개인 키 저장"** 버튼을 눌러 `ssh-key-xxxx.key` 파일을 꼭 내려받는다.
   - 이 파일이 없으면 다시는 못 들어간다.
6. 네트워킹: 기본값(새 VCN, **공용 IPv4 주소 할당** 체크)
7. **생성** → 상태가 초록색 **"실행 중"** 이 되면 **공용 IP 주소**(예: `152.67.12.34`)를 적어 둔다.

> "Out of capacity(용량 부족)"가 뜨면: 몇 시간 뒤 다시 하거나, OCPU를 1개로 줄여 본다. 인기 지역에서 흔한 일이다.

### 3-4. 대문 열기 — 방화벽 두 겹
웹 손님(80, 443번 문)이 들어올 수 있게 **두 군데**를 열어야 한다. 하나만 열면 안 된다.

**① Oracle 쪽 문 (보안 목록)**
1. 인스턴스 상세 → **서브넷** 링크 → **보안 목록(Security Lists)** → Default Security List
2. **수신 규칙 추가** 두 개:
   - 소스 CIDR `0.0.0.0/0`, IP 프로토콜 TCP, 대상 포트 `80`
   - 소스 CIDR `0.0.0.0/0`, IP 프로토콜 TCP, 대상 포트 `443`

**② 컴퓨터 안쪽 문 (Ubuntu 방화벽)** — 3-5에서 접속한 뒤 실행

### 3-5. 들어가 보기 (접속)
내 컴퓨터 터미널(Windows는 PowerShell)에서:
```bash
chmod 600 ~/Downloads/ssh-key-xxxx.key            # Windows는 이 줄 생략
ssh -i ~/Downloads/ssh-key-xxxx.key ubuntu@152.67.12.34
```
"yes" 를 입력하면 `ubuntu@fgc-server:~$` 가 보인다. 들어온 것이다. 🎉

이제 **컴퓨터 안쪽 문**을 연다 (Oracle의 Ubuntu 이미지는 기본으로 막혀 있다):
```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

필요한 프로그램 설치 (Docker, Java, Git):
```bash
sudo apt update && sudo apt install -y git openjdk-21-jdk-headless
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu && exit            # 한 번 나갔다가 다시 ssh 로 들어온다
```

### ⚠️ 무료 VM의 위험 (꼭 알기)
- Oracle은 **7일 동안 너무 한가한 무료 컴퓨터를 회수**할 수 있다. 우리 서버는 가벼워서 해당될 수 있다.
- 2026년에 무료 할당량이 줄었다는 보도도 있다 (공식 확인 안 됨).
- 그래서: 매일 백업(5-4)을 켜 두고, **가족이 이 알림에 정말 의존하게 되면 유료 VPS로 옮긴다** (ADR-0005).

---

## 4. 도메인 준비 (🏠 집 주소)

HTTPS(편지 봉인)를 쓰려면 숫자 IP 말고 **이름 주소**가 필요하다. 두 가지 방법 중 하나를 고른다.

### 방법 A — 무료: DuckDNS (연습용 추천)
1. <https://www.duckdns.org> → Google/GitHub 계정으로 로그인
2. sub domain 칸에 `my-fgc` 입력 → **add domain** → `my-fgc.duckdns.org` 가 생긴다
3. **current ip** 칸에 3-3에서 적은 **VM 공용 IP** 입력 → **update ip**
4. 끝. (화면 위의 `token` 은 DuckDNS용 비밀이니 공유하지 않는다)

### 방법 B — 유료: 내 도메인 사기
1. Cloudflare Registrar, 가비아, 후이즈 등에서 도메인 구입 (`.com` 등, 연 단위 요금 — 구입 시점 가격 확인)
2. 그 회사의 **DNS 관리** 화면에서 레코드 추가:
   - 종류 `A`, 이름 `fgc`(→ `fgc.내도메인.com`) 또는 `@`, 값 = **VM 공용 IP**
   - Cloudflare 를 쓰면 처음에는 **프록시(주황 구름)를 끄고** "DNS only"(회색 구름)로 둔다. 인증서 발급이 꼬이지 않는다.

### 확인
내 컴퓨터에서:
```bash
nslookup my-fgc.duckdns.org
```
VM 공용 IP가 나오면 성공이다. 안 나오면 5~30분 기다린다.

---

## 5. 서버에 모두 꽂기 (이삿짐 넣기)

VM 에 ssh 로 들어간 상태에서 진행한다.

### 5-1. 코드 가져오기
```bash
sudo mkdir -p /opt/fgc && sudo chown ubuntu /opt/fgc
git clone https://github.com/zzinooks/atest.git /opt/fgc
cd /opt/fgc && git checkout ccr-ac5d9c38-9bi9je
```
(비공개 저장소이면 GitHub 개인 액세스 토큰이나 SSH 키가 필요하다.)

### 5-2. 서버 프로그램 만들기
```bash
cd /opt/fgc/backend && ./gradlew bootJar       # 처음 한 번은 몇 분 걸린다
```

### 5-3. 비밀 넣기
**① Firebase 인감도장 옮기기** — 이건 **내 컴퓨터** 터미널에서 실행한다:
```bash
scp -i ~/Downloads/ssh-key-xxxx.key ~/안전한폴더/family-glucose-care-firebase-adminsdk-xxxx.json \
    ubuntu@152.67.12.34:/opt/fgc/deploy/fcm.json.tmp
```

**② VM 에서 정리:**
```bash
cd /opt/fgc/deploy
mkdir -p secrets && mv fcm.json.tmp secrets/fcm.json && chmod 600 secrets/fcm.json
cp .env.example .env && chmod 600 .env
python3 -c "import secrets;print('JWT  =', secrets.token_urlsafe(48))"
python3 -c "import secrets,base64;print('ENC  =', base64.b64encode(secrets.token_bytes(32)).decode())"
python3 -c "import secrets;print('DBPW =', secrets.token_urlsafe(24))"
nano .env
```

**③ `.env` 채우기** (`=` 뒤에 붙여 넣기, 따옴표 없이):

| 항목 | 넣을 값 |
|---|---|
| `FGC_DOMAIN` | `my-fgc.duckdns.org` (4장) |
| `FGC_DB_PASSWORD` | 위에서 나온 DBPW |
| `FGC_JWT_SECRET` | 위에서 나온 JWT |
| `FGC_ENCRYPTION_KEY` | 위에서 나온 ENC |
| `NOTION_TOKEN` | `ntn_...` (2-2) |
| `NOTION_DAILY_DB_ID` | 일별 DB 32글자 (2-5) |
| `NOTION_WEEKLY_DB_ID` | 주간 DB 32글자 (2-5) |

저장: `Ctrl+O` → Enter → `Ctrl+X`.

> ⚠️ **`FGC_ENCRYPTION_KEY` 는 한 번 정하면 바꾸지 않는다.** 바꾸면 이미 저장된 데이터 소스 비밀번호를 풀 수 없게 된다. `.env` 파일을 안전한 곳에 따로 백업해 둔다.
>
> Firebase 없이 먼저 해 보고 싶으면: `docker-compose.yml` 에서 `FGC_FCM_CREDENTIALS` 줄과 그 아래 `volumes: [ "./secrets/fcm.json..." ]` 줄을 지운다. 그러면 dev-outbox 로 동작하며, **휴대폰으로 알림이 가지 않는다**.

### 5-4. 켜기
```bash
cd /opt/fgc/deploy && docker compose up -d --build
docker compose logs -f backend      # "Started FgcApplication" 과 "Push provider: FCM" 이 보이면 OK (Ctrl+C 로 빠져나오기)
```
내 컴퓨터 브라우저에서 `https://my-fgc.duckdns.org/actuator/health` → `{"status":"UP"...}` 이 보이면 **이사 완료** 🎉
주소창에 자물쇠 🔒 가 보이면 HTTPS도 성공이다 (Caddy가 인증서를 자동으로 받아 온다).

**매일 백업 켜기:**
```bash
crontab -e      # 맨 아래에 다음 줄 추가 후 저장
40 3 * * * /opt/fgc/deploy/backup.sh >> /opt/fgc/deploy/backup.log 2>&1
```

### 5-5. 잘 안 될 때
| 증상 | 원인 → 해결 |
|---|---|
| 브라우저가 계속 로딩만 함 | 방화벽 두 겹 중 하나가 닫힘 → 3-4 ①② 다시 확인 |
| 자물쇠 없이 인증서 오류 | 도메인이 아직 VM IP를 가리키지 않음 → 4장 `nslookup` 확인 후 `docker compose restart caddy` |
| 로그에 `FGC_JWT_SECRET must be set` | `.env` 에 값이 비어 있음 |
| 로그에 `Push provider: dev-outbox` | `secrets/fcm.json` 이 없거나 경로가 틀림 |
| Notion 요약이 안 써짐, 로그에 `HTTP 404` | 2-4 "연결 추가"를 안 했음 |
| Notion 로그에 `HTTP 400` | 속성 이름·종류가 표와 다름 (대소문자 포함) |
| Notion 로그에 `HTTP 401` | 토큰이 틀렸거나 새로 고침 후 옛 값 사용 |

---

## 6. 앱 만들기 (휴대폰에 설치)

이제 `dl.google.com` 이 허용되어 앱도 만들 수 있다.

1. `android/app/google-services.json` 이 있는지 확인 (1-3)
2. 내 서버 주소를 넣어 빌드:
   ```bash
   cd android && ./gradlew :app:assembleRelease -Pfgc.baseUrl=https://my-fgc.duckdns.org
   ```
   - 연습용으로는 Android Studio 의 ▶ 실행(디버그)도 된다. 이 경우 기본 주소는 PC의 로컬 서버(`10.0.2.2:8080`)다.
3. 휴대폰에 설치 → 가입 → 설정 → "환자로 시작하기" → 데이터 연결
4. 가족 휴대폰에도 설치 → 가입 → 환자가 만든 **초대 코드** 입력
5. **알림 시험**: 환자 설정에서 "낮음" 기준을 잠깐 높게(예: 150) 저장 → 가족 휴대폰에 "저혈당" 알림이 오는지 확인 → **꼭 원래 값으로 되돌리기**

> Google Play 에 올리려면 개발자 등록비 US$25(1회)가 든다. 가족끼리 쓸 때는 APK 파일을 직접 설치해도 된다.

---

## 7. 마지막 체크리스트

- [ ] Firebase 프로젝트 생성, 패키지 `com.fgc.app` 으로 앱 등록
- [ ] `google-services.json` → `android/app/` (커밋 안 함)
- [ ] 서비스 계정 키 → 서버 `deploy/secrets/fcm.json` (권한 600)
- [ ] Notion 통합 생성, 토큰 복사
- [ ] Notion 일별·주간 DB 생성, 속성 13개 이름 일치
- [ ] 두 DB 모두 통합 "연결 추가"
- [ ] DB ID 두 개 복사
- [ ] Oracle VM 생성 (Always Free 적격 확인), SSH 키 보관
- [ ] 방화벽 두 겹(보안 목록 + iptables) 80/443 열기
- [ ] 도메인 → VM IP (`nslookup` 확인)
- [ ] `.env` 채움, `.env` 별도 백업
- [ ] `https://도메인/actuator/health` UP + 자물쇠
- [ ] 로그에 `Push provider: FCM`
- [ ] 백업 cron 등록
- [ ] 가족 휴대폰으로 실제 알림 수신 확인

## 변경 이력
| 일자 | 변경 내용 |
|---|---|
| 2026-11-05 | 최초 작성. backend 에 Gradle Wrapper 추가 (`./gradlew` 사용 가능) |
