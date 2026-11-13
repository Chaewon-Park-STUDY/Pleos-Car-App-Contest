# SDK 사용 계획 (확정본)

> 기준: Vehicle SDK v2.3.2 / FusedLocation SDK v1.1.3 — 2026-11-13 문서 확인

## 컨셉
운전 중 "그 순간"을 자동·음성으로 기록하는 차량용 이벤트 로그.
블랙박스 영상만으로는 남지 않는 **언제·어디서·무슨 일·누가(운전자/시스템) 제어 중이었는지**를 기록한다.

## 사용 API

| SDK / 도메인 | API | 용도 | 퍼미션 |
| --- | --- | --- | --- |
| Vehicle / Brake | `registerBrakePedalStatus` (Boolean, true=밟음) | 급제동 vs 시스템 감속 구분 | `CAR_DRIVE` |
| Vehicle / DrivingMode | `registerCruiseControlState` | 크루즈 ON/OFF | `CAR_DRIVE` |
| Vehicle / DrivingMode | `registerCruiseControlTargetSpeed` (m/s) | 유령 제동 판단 기준 속도 | `CAR_DRIVE` |
| Vehicle / Safety | `registerSeatAirbagDeployed(area, …)` | 사고 감지 | `CAR_SAFETY` |
| Vehicle / Safety | `registerSeatBeltNotBuckledWarning(area, …)` | 사고 시 탑승자 상태 기록 | `CAR_SAFETY` |
| Vehicle / Safety | `registerPowerDownWarningIndicator` | 차량 이상 이벤트 | `CAR_SAFETY` |
| FusedLocation | `registerFusedLocationCallback` → `LocationData(lat, lon, alt, heading, confidence, timestampMillis)` | 위치 기록 + 속도 계산 | `FUSED_LOCATION` |
| SpeechToText | 음성 인식 | "방금 저장해" 명령, 운전자 진술 텍스트화 | (문서 확인) |
| TextToSpeech | 음성 합성 | 저장 확인, 사고 시 "괜찮으세요?" | (문서 확인) |

사용하지 않음: `registerTelltales` / `checkTelltalesCapabilities` (`PRIVATE_CAR_SAFETY` 필요)

## 이벤트 탐지 규칙

속도 필드가 없으므로 위치로 계산:
1. 연속 위치 A(t1), B(t2) 사이 거리 d (Haversine)
2. 속도 v = d / (t2 − t1)
3. confidence 낮은 점 제외 + smoothing (이동평균 → 추후 Kalman filter)
4. 감속도 a = (v_prev − v_now) / Δt

| 이벤트 | 조건 |
| --- | --- |
| 수동 기록 | STT로 "저장" 명령 인식 |
| 급제동 | Brake = true AND a > 임계값 |
| **시스템 의심 감속 (유령 제동)** | Cruise = ON AND Brake = false AND (목표속도 − v) 급증 / a > 임계값 |
| 사고 | 어느 좌석이든 AirbagDeployed = true → TTS 확인 → STT 응답 대기 |
| 차량 이상 | PowerDownWarningIndicator = true |

각 이벤트 저장 항목: 시각, 위치, 계산 속도, 브레이크, 크루즈 상태·목표속도, 에어백·벨트 상태, 운전자 진술(STT 텍스트)

## 향후 계획 (현재 SDK 범위 밖)
- 블랙박스 영상 구간 자동 연동
- 긴급 연락·신고
- 주행보조(ADAS) 상세 상태 연동
- 배터리 SoC 연동 (EvBattery는 현재 전비 위주)

## Manifest
```xml
<uses-permission android:name="pleos.car.permission.CAR_DRIVE" />
<uses-permission android:name="pleos.car.permission.CAR_SAFETY" />
<uses-permission android:name="pleos.car.permission.FUSED_LOCATION" />
```
