# Family Glucose Care 문서

| 디렉터리 | 내용 |
|---|---|
| requirements/ | 요구사항, 사용자 시나리오, 완료 기준 |
| research/ | 조사·PoC 보고서 (STEP별 보고서 포함) |
| architecture/ | 시스템 구성도, 데이터 흐름, 인증 구조 |
| database/ | ERD, 테이블 정의, 인덱스·보관 정책 |
| api/ | REST API 명세, 외부 API 분석 결과 |
| android/ | 앱 구조, 화면 설계, 백그라운드 동작 |
| backend/ | 서버 모듈 구조, 배치·알람 엔진 |
| testing/ | 테스트 계획·결과 |
| deployment/ | 배포·운영·비용 |
| security/ | 보안·개인정보·법적 검토 |
| decision/ | ADR (아키텍처 의사결정 기록) |

## 문서 공통 템플릿

```markdown
# <문서 제목>

- 작성 목적:
- 작성 일자: YYYY-MM-DD
- 관련 STEP:

## 1. 요구사항
## 2. 상세 내용
## 3. 기술적 결정
## 4. 확인된 사실        (근거/출처 포함)
## 5. 미확인 사항        (UNKNOWN — 확인 방법 포함)
## 6. 테스트 결과        (실행하지 않았으면 "미실행"이라고 적는다)
## 7. 향후 작업

## 변경 이력
| 일자 | 변경 내용 |
|---|---|
```

ADR은 `decision/ADR-NNNN-<제목>.md`로 작성한다 (상태: 제안/승인/폐기/대체).
