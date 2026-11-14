package com.chaewon.safedrive.core

enum class EventType(val label: String) {
    MY_HARD_DECEL("내 급감속"),        // 코칭 대상 (브레이크를 밟았거나 크루즈 OFF)
    CAR_DECEL("차량이 스스로 감속"),    // 크루즈 ON + 브레이크 안 밟음 → 평가·학습 제외
    MANUAL("직접 기록"),              // "저장해" (STT)
    CRASH("사고 감지"),               // ☆ 에어백
    VEHICLE_WARNING("차량 이상 경고"),  // ☆
}

data class DriveEvent(
    val type: EventType,
    val timestampMillis: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedMps: Double? = null,
    val decelMps2: Double? = null,
    val thresholdMps2: Double? = null,
    val brakePressed: Boolean? = null,
    val cruiseOn: Boolean? = null,
    val cruiseTargetMps: Float? = null,
    val note: String? = null,
    val tripId: Long? = null,
    val id: Long = timestampMillis,
)

/** 감속 구간 하나가 끝났을 때의 요약 (주행 요약 계산용) */
data class EpisodeResult(
    val peakMps2: Double,
    val byCar: Boolean,       // 차량(크루즈)이 한 감속
    val flagged: Boolean,     // 급감속으로 판정됨
)

/**
 * 차량 신호 + 계산 속도 → 이벤트 판단. 안드로이드/SDK 의존성 없음.
 *
 * 감속 구간: 감속도 ≥ startMps2 에서 시작, < endMps2 에서 종료.
 *  - '누가 감속했나': 구간 중 한 번이라도 브레이크(또는 직전 grace 이내) → 운전자.
 *    아니면서 크루즈 ON → 차량. 둘 다 아니면(발 뗌 등) → 운전자.
 *  - 구간 중 내 기준을 처음 넘는 순간 이벤트 1회.
 *  - 구간 종료 시: 운전자 감속 & 미판정 구간만 학습에 추가.
 */
class EventDetector(
    val threshold: PersonalThreshold,
    private val onEvent: (DriveEvent) -> Unit,
    private val onEpisodeEnd: (EpisodeResult) -> Unit = {},
    private val startMps2: Double = 1.0,
    private val endMps2: Double = 0.5,
    private val brakeGraceMillis: Long = 1_500,
    private val crashCooldownMillis: Long = 60_000,
    private val stopSpeedMps: Double = 0.5,
    private val stopHoldMillis: Long = 3_000,
) {
    var brakePressed = false; private set
    private var lastBrakeAt: Long = Long.MIN_VALUE / 2
    var cruiseOn = false; private set
    var cruiseTargetMps: Float? = null; private set
    var last: SpeedSample? = null; private set
    var tripId: Long? = null

    private var inEpisode = false
    private var episodePeak = 0.0
    private var episodeFired = false
    private var episodeDriverBraked = false
    private var episodeCruise = false

    private var belowStopSince: Long? = null
    var isStopped = true; private set
    private var lastCrashAt: Long = Long.MIN_VALUE / 2

    fun onBrake(pressed: Boolean, now: Long) {
        brakePressed = pressed
        if (pressed) lastBrakeAt = now
        if (pressed && inEpisode) episodeDriverBraked = true
    }

    fun onCruiseState(on: Boolean) { cruiseOn = on }
    fun onCruiseTarget(mps: Float?) { cruiseTargetMps = mps }

    fun onSpeed(s: SpeedSample) {
        last = s
        updateStop(s)
        val d = s.decelMps2

        if (!inEpisode && d >= startMps2) {
            inEpisode = true; episodePeak = d; episodeFired = false
            episodeDriverBraked = recentlyBraked(s.timestampMillis)
            episodeCruise = cruiseOn
        }
        if (!inEpisode) return

        if (d > episodePeak) episodePeak = d
        if (recentlyBraked(s.timestampMillis)) episodeDriverBraked = true
        if (cruiseOn) episodeCruise = true

        val th = threshold.currentThresholdMps2()
        if (!episodeFired && d >= th) {
            episodeFired = true
            onEvent(decelEvent(s, th, byCar = isByCar()))
        }
        if (d < endMps2) {
            val byCar = isByCar()
            if (!byCar && !episodeFired) threshold.addEpisodePeak(episodePeak)
            onEpisodeEnd(EpisodeResult(episodePeak, byCar, episodeFired))
            inEpisode = false
        }
    }

    private fun isByCar() = episodeCruise && !episodeDriverBraked
    private fun recentlyBraked(now: Long) = brakePressed || (now - lastBrakeAt) <= brakeGraceMillis

    private fun decelEvent(s: SpeedSample, th: Double, byCar: Boolean) = DriveEvent(
        type = if (byCar) EventType.CAR_DECEL else EventType.MY_HARD_DECEL,
        timestampMillis = s.timestampMillis,
        latitude = s.latitude, longitude = s.longitude,
        speedMps = s.speedMps, decelMps2 = s.decelMps2, thresholdMps2 = th,
        brakePressed = episodeDriverBraked, cruiseOn = episodeCruise, cruiseTargetMps = cruiseTargetMps,
        tripId = tripId,
    )

    fun manualBookmark(now: Long, note: String?) = onEvent(snapshot(EventType.MANUAL, now, note))

    fun onAirbagDeployed(deployed: Boolean, now: Long, seat: String) {
        if (!deployed || now - lastCrashAt < crashCooldownMillis) return
        lastCrashAt = now
        onEvent(snapshot(EventType.CRASH, now, "에어백 전개: $seat"))
    }

    fun onVehicleWarning(active: Boolean, now: Long, what: String) {
        if (active) onEvent(snapshot(EventType.VEHICLE_WARNING, now, what))
    }

    private fun snapshot(type: EventType, now: Long, note: String?) = DriveEvent(
        type = type, timestampMillis = now,
        latitude = last?.latitude, longitude = last?.longitude,
        speedMps = last?.speedMps, decelMps2 = last?.decelMps2,
        brakePressed = brakePressed, cruiseOn = cruiseOn, cruiseTargetMps = cruiseTargetMps,
        note = note, tripId = tripId,
    )

    private fun updateStop(s: SpeedSample) {
        if (s.speedMps < stopSpeedMps) {
            val since = belowStopSince ?: s.timestampMillis.also { belowStopSince = it }
            if (s.timestampMillis - since >= stopHoldMillis) isStopped = true
        } else {
            belowStopSince = null
            isStopped = false
        }
    }
}
