# ADR-0002: 수집 경로 결정과 가상 CGM 에뮬레이터 도입

- 상태: 승인 (연습 프로젝트 기준, 실기기 확보 시 재검토)
- 작성 일자: 2026-11-04
- 관련: ADR-0001, docs/research/STEP1-data-access-research.md

## 배경
실물 기기·계정이 없고, 바로잰 Fit의 공개 API도 없다. Health Connect·xDrip 연동 여부는 기기 없이 확인할 수 없다.
사용자는 연습 프로젝트로 진행하고 에뮬레이터로 대체하기로 했다.

## 결정
1. 어댑터 인터페이스 `GlucoseSourceAdapter` 뒤에 다음 구현을 둔다.
   | 어댑터 | source 값 | 용도 |
   |---|---|---|
   | `EmulatorCloudAdapter` | `EMULATOR` | 개발·테스트. 클라우드 폴링형(토큰, 페이지네이션, 지연, 중복, backfill) 처리 연습 |
   | `HealthConnectAdapter` (Android) | `HEALTH_CONNECT` | 실서비스 1순위 |
   | `NightscoutIngestAdapter` (Backend 수신 API) | `NIGHTSCOUT_UPLOAD` | 실서비스 2순위 (xDrip+ 업로드) |
   | (예약) `BarozenOfficialAdapter` | `BAROZEN_OFFICIAL` | 공식 제휴 확보 시 |
2. 마스터 프롬프트의 `REA_VERIFIED_API` source는 **만들지 않는다** (경로 5 제외).
3. 에뮬레이터 데이터는 운영 DB에서 `source = EMULATOR`로 구분하고, 화면에 "가상 데이터" 표시를 한다.
4. Samsung Health Data SDK는 파트너 승인이 필요하므로 구현하지 않는다.

## 결과
- 장점: 기기 없이도 수집·저장·알림·가족 공유 전체 흐름을 끝까지 검증할 수 있다. 장애 시나리오를 재현 가능하게 시험할 수 있다.
- 한계: 에뮬레이터를 통과해도 **실제 바로잰 데이터 수집은 검증되지 않은 상태**다. 마스터 프롬프트 18장의 "데이터 수집" 완료 기준은 실기기 검증 전까지 미완료로 남긴다.
