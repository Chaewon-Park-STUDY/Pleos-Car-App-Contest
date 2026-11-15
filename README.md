# PaceNote (페이스노트)

> 평소의 나를 기준으로 안전운전을 코칭하고, 위험한 순간은 차량 신호와 음성으로 기록하는 **개인 맞춤 안전운전 앱**

2026 현대자동차그룹 차량용 앱 개발 공모전 **Code your App into Pleos App Market** 출품작 · Pleos Connect 인포테인먼트용 Android 앱

이름의 유래: 랠리 경기에서 코드라이버가 코스와 주행을 기록하는 **페이스노트**. 내 운전 페이스(Pace)를 배우고, 중요한 순간을 기록(Note)합니다.

---

## 📌 앱 소개

| 항목 | 내용 |
| --- | --- |
| 앱 이름 | PaceNote (페이스노트) |
| 분야 | 스마트 드라이빙 & 안전 |
| 참가 형태 | 개인 |

### 해결하려는 문제
- 운전 습관은 사람마다 다른데 안전 기준은 하나뿐이라, 알림이 과하거나 부족하다.
- 블랙박스 영상만으로는 위험했던 순간을 찾기 어렵고, **운전자가 감속했는지 차량(크루즈)이 감속했는지** 알 수 없다.

### 핵심 기능
| 구분 | 기능 |
| --- | --- |
| 매 주행 | **내 기준 학습**: 감속 구간마다 최대 감속을 쌓아 '평소의 나' 기준을 만들고, 그보다 센 감속만 '내 급감속'으로 판단 |
| 매 주행 | **민감도(민감/보통/둔감)·음성 알림 빈도(자주/보통/가끔/끄기)** 를 운전자 프로필별로 설정 |
| 매 주행 | **주행 후 요약**: "내 급감속 n회 · 평소보다 많아요" + 최근 7회 비교 |
| 위험한 순간 | **차량이 스스로 감속**: 크루즈 작동(ACTIVATED) 중 브레이크 없이 급감속 → 조용히 기록, 내 평가에서 제외 |
| 위험한 순간 | **🎤 말로 기록하기**: 버튼을 누르고 말하면 그 순간의 위치·차량 상태와 함께 진술 저장 |
| 위험한 순간 | **사고 감지**: 좌석별 에어백 전개 시 자동 기록 + 음성 안부 확인 |

### 사람과 자동의 경계
- 자동: 신호 감지 · 속도 계산 · 이벤트 기록 · 학습
- 사람: 프로필 · 민감도 · 알림 빈도 · 기준 다시 배우기 · 기록 삭제 · 위치 권한 허용
- 하지 않음: **차량 제어** (조회용 퍼미션만 사용)

---

## 🔌 사용한 Pleos Connect SDK

| SDK | 버전 | 사용 API | 용도 |
| --- | --- | --- | --- |
| Vehicle | 2.3.2 | `Brake.registerBrakePedalStatus` | 운전자 제동 여부 |
| | | `DrivingMode.registerCruiseControlState` / `registerCruiseControlTargetSpeed` | 크루즈 제어 중 여부, 목표 속도 |
| | | `Safety.registerSeatAirbagDeployed` / `registerPowerDownWarningIndicator` | 사고·차량 이상 |
| FusedLocation | 1.1.3 | `registerFusedLocationCallback` | 위치 → 속도·감속 계산 |
| TextToSpeech | 3.3.1 | `speak` (ON_DEVICE) | 주행 중 음성 안내 |
| SpeechToText | 3.3.1 | `request` + `ResultListener` (ON_DEVICE) | 음성 진술 기록 |

퍼미션: `CAR_DRIVE`, `CAR_SAFETY`, `FUSED_LOCATION`, `TTS_SERVICE`, `STT_SERVICE`

---

## ▶️ 실행 방법

```bash
git clone https://github.com/pacenote-dev/Pleos-Car-App-Contest.git
```
1. Android Studio 로 열기 → Gradle Sync (Pleos Maven 저장소는 `settings.gradle.kts` 에 등록되어 있음)
2. 에뮬레이터(AVD) 선택 → ▶ Run
3. 첫 실행: 운전자 이름 입력 → 동의 → 위치 권한 허용
4. 홈 오른쪽 **데모 모드**: ① 평소 주행 5회 기록(학습) → ② 시연 주행 시작
   - Pleos Connect Emulator 에서는 Virtual Console 신호(브레이크·크루즈·에어백·위치)를 받도록 구현되어 있습니다. 시연 영상은 일반 Android 에뮬레이터 + 데모 모드로 촬영했습니다.

---

## 🧪 테스트 환경

| 항목 | 내용 |
| --- | --- |
| 에뮬레이터 | Android Emulator · Medium Phone (Android 17, API 37, x86_64) |
| 화면 규격 | 2400 × 1080 가로 고정 (차량 화면과 같은 가로형 배치) |
| 차량 신호 | Pleos SDK 연결 코드 포함 · 일반 에뮬레이터에서는 데모 모드로 같은 인터페이스에 신호 재생 |
| Android Studio | Rabbit 2 (2026.2.2 Patch 1) |
| 언어 / minSdk / targetSdk | Kotlin · 28 · 37 |
| UI | Jetpack Compose (Material 3) |

단위 테스트: `app/src/test/.../CoreLogicTest.kt` (속도 계산, 이벤트 분류, 개인 기준 학습, 알림 정책, 데모 전체 흐름 등 35개 검사)

---

## 📦 APK
- 다운로드: [Releases v1.0](https://github.com/pacenote-dev/Pleos-Car-App-Contest/releases/tag/v1.0) · 파일명: `PaceNote.apk`

## 🎬 시연 영상
- https://youtu.be/6McIWpKU4yg

---

## 📁 구조

```
app/src/main/java/com/chaewon/safedrive/
├── core/     판단 로직 (속도 계산, 개인 기준, 이벤트 판단, 주행 요약) — SDK 의존성 없음
├── app/      DriveController (상태 관리), 저장소 인터페이스
├── signal/   Pleos Vehicle·FusedLocation 어댑터, 데모 시나리오
├── voice/    Pleos TTS·STT 어댑터 (+ 기본 TTS 대체)
├── data/     기기 내 JSON 저장 (외부 전송 없음)
└── ui/       Compose 화면 (프로필, 홈, 기록, 주행 요약, 설정)
docs/         기획 노트, SDK 사용 계획, 설계 문서, 시연 대본
```
