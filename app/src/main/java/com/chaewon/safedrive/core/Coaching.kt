package com.chaewon.safedrive.core

import kotlin.math.abs

/** 음성 알림 빈도 (프로필별 설정). 기록은 설정과 무관하게 항상 남는다. */
enum class AlertLevel(
    val label: String,
    val minExcessRatio: Double?,   // 내 기준 대비 몇 배 이상일 때 말할지 (null = 말하지 않음)
    val cooldownMillis: Long,
) {
    OFTEN("자주", 1.00, 60_000),
    NORMAL("보통", 1.20, 5 * 60_000),
    RARE("가끔", 1.50, 15 * 60_000),
    OFF("끄기", null, 0),
}

/** 이벤트를 음성으로 말할지 결정 */
class AlertPolicy(var level: AlertLevel = AlertLevel.NORMAL) {
    private var lastSpokenAt: Long = Long.MIN_VALUE / 2

    fun shouldSpeak(e: DriveEvent): Boolean {
        if (e.type != EventType.MY_HARD_DECEL) return false   // 차량 감속은 조용히 기록
        val ratio = level.minExcessRatio ?: return false
        val d = e.decelMps2 ?: return false
        val th = e.thresholdMps2 ?: return false
        if (d < th * ratio) return false
        if (e.timestampMillis - lastSpokenAt < level.cooldownMillis) return false
        lastSpokenAt = e.timestampMillis
        return true
    }
}

/** 주행 1회 기록 */
data class Trip(
    val id: Long,
    val startMillis: Long,
    val endMillis: Long,
    val distanceM: Double,
    val myHardDecelCount: Int,
    val carDecelCount: Int,
    val myEpisodePeaks: List<Double>,  // 운전자 감속 구간 peak (세기 비교용)
)

enum class Comparison(val label: String) { MORE("평소보다 많아요"), SIMILAR("평소와 비슷해요"), FEWER("평소보다 적어요"), UNKNOWN("비교할 기록이 아직 부족해요") }
enum class Strength(val label: String) { STRONGER("평소보다 세게"), SIMILAR("평소와 비슷하게"), GENTLER("평소보다 부드럽게"), UNKNOWN("-") }

/** 화면에 보여 줄 '과거의 나' 대비 요약. 내부 수치는 화면에 노출하지 않는다. */
data class TripSummary(
    val trip: Trip,
    val count: Int,
    val countComparison: Comparison,
    val strength: Strength,
    val carDecelCount: Int,
    val recentCounts: List<Int>,   // 최근 7회(오늘 포함, 오래된 순)
)

object TripSummarizer {
    private const val MIN_HISTORY = 3
    private const val PER_KM = 10.0     // 10km 당 횟수로 정규화

    fun summarize(today: Trip, previous: List<Trip>): TripSummary {
        val hist = previous.filter { it.distanceM > 500 }.takeLast(10)
        val countCmp = if (hist.size < MIN_HISTORY || today.distanceM <= 500) Comparison.UNKNOWN else {
            val usual = hist.map { rate(it) }.average()
            val now = rate(today)
            when {
                now > usual * 1.25 + 0.1 -> Comparison.MORE
                now < usual * 0.75 - 0.1 -> Comparison.FEWER
                else -> Comparison.SIMILAR
            }
        }
        val usualPeaks = hist.flatMap { it.myEpisodePeaks }
        val strength = if (usualPeaks.size < 10 || today.myEpisodePeaks.isEmpty()) Strength.UNKNOWN else {
            val u = median(usualPeaks); val t = median(today.myEpisodePeaks)
            when {
                abs(t - u) <= u * 0.10 -> Strength.SIMILAR
                t > u -> Strength.STRONGER
                else -> Strength.GENTLER
            }
        }
        val recent = (previous.takeLast(6) + today).map { it.myHardDecelCount }
        return TripSummary(today, today.myHardDecelCount, countCmp, strength, today.carDecelCount, recent)
    }

    private fun rate(t: Trip) = t.myHardDecelCount / (t.distanceM / 1000.0) * PER_KM
    private fun median(xs: List<Double>): Double { val s = xs.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2 }
}

/**
 * 주행 경계 판단 + 거리 누적.
 *  시작: 속도 > startSpeedMps
 *  종료: 정차(속도 < stopSpeedMps)가 endHoldMillis 이상 유지
 */
class TripTracker(
    private val onTripStart: (Long) -> Unit,
    private val onTripEnd: (Trip) -> Unit,
    private val startSpeedMps: Double = 2.0,
    private val stopSpeedMps: Double = 0.5,
    private val endHoldMillis: Long = 60_000,
) {
    var activeTripId: Long? = null; private set
    private var start = 0L
    private var distance = 0.0
    private var lastT: Long? = null
    private var stoppedSince: Long? = null
    private var myHard = 0
    private var carDecel = 0
    private val peaks = mutableListOf<Double>()

    fun onSpeed(s: SpeedSample) {
        val prev = lastT
        lastT = s.timestampMillis
        if (activeTripId == null) {
            if (s.speedMps > startSpeedMps) begin(s.timestampMillis)
            return
        }
        if (prev != null) distance += s.speedMps * (s.timestampMillis - prev) / 1000.0
        if (s.speedMps < stopSpeedMps) {
            val since = stoppedSince ?: s.timestampMillis.also { stoppedSince = it }
            if (s.timestampMillis - since >= endHoldMillis) finish(s.timestampMillis)
        } else stoppedSince = null
    }

    fun onEvent(e: DriveEvent) {
        if (activeTripId == null) return
        when (e.type) { EventType.MY_HARD_DECEL -> myHard++; EventType.CAR_DECEL -> carDecel++; else -> {} }
    }

    fun onEpisode(r: EpisodeResult) { if (activeTripId != null && !r.byCar) peaks += r.peakMps2 }

    /** 데모·앱 종료 시 강제 종료 */
    fun forceEnd(now: Long) { if (activeTripId != null) finish(now) }

    private fun begin(now: Long) {
        activeTripId = now; start = now; distance = 0.0; stoppedSince = null
        myHard = 0; carDecel = 0; peaks.clear()
        onTripStart(now)
    }

    private fun finish(now: Long) {
        val id = activeTripId ?: return
        activeTripId = null
        onTripEnd(Trip(id, start, now, distance, myHard, carDecel, peaks.toList()))
    }
}
