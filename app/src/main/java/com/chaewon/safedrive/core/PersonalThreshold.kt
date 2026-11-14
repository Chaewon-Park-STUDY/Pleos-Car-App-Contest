package com.chaewon.safedrive.core

import kotlin.math.ceil

/** 화면 설정: 얼마나 민감하게 '급감속'으로 볼지. 화면에는 label 만 노출. */
enum class Sensitivity(
    val label: String,
    val learningThresholdMps2: Double, // 학습 전 기본 기준
    val multiplier: Double,            // 학습 후: 내 기준(baseline) × multiplier
) {
    SENSITIVE("민감", 2.5, 1.00),
    NORMAL("보통", 3.5, 1.15),
    RELAXED("둔감", 4.5, 1.30),
}

/**
 * 개인 맞춤 급감속 기준 — "남이 아니라 과거의 나와 비교".
 *
 *  1) 학습 단계: 내 감속 구간의 최대 감속도(peak)를 minSamples 개 모은다.
 *  2) 학습 완료 순간: peak 분포의 baselineQuantile 분위수를 '내 기준(baseline)'으로 고정.
 *  3) 이후: 주행이 끝날 때마다 baseline 을 최근 주행 분위수 쪽으로 adaptRate 만큼만 이동 (EMA).
 *     → 습관이 거칠어져도 기준이 천천히만 따라가므로 '평소의 나보다 거칠다'를 잡아낼 수 있다.
 *  4) 급감속으로 판정된 구간, 차량(크루즈)이 한 감속 구간은 학습에 넣지 않는다 (호출 측 책임).
 *
 * 수치(minSamples, baselineQuantile, adaptRate, clamp 범위)는 검토 후 조정 예정.
 */
class PersonalThreshold(
    var sensitivity: Sensitivity = Sensitivity.NORMAL,
    val minSamples: Int = 30,
    private val baselineQuantile: Double = 0.95,
    private val adaptRate: Double = 0.05,
    private val maxHistory: Int = 500,
    private val minMps2: Double = 2.0,
    private val maxMps2: Double = 6.0,
) {
    private val peaks = ArrayDeque<Double>()
    private val tripPeaks = mutableListOf<Double>()

    /** 고정된 '내 기준'. 학습 전에는 null. */
    var baselineMps2: Double? = null
        private set

    val sampleCount: Int get() = peaks.size
    val isLearning: Boolean get() = baselineMps2 == null
    val learningProgress: Double get() =
        if (!isLearning) 1.0 else (peaks.size.toDouble() / minSamples).coerceAtMost(1.0)

    /** 정상 감속 구간 1개를 학습에 추가 */
    fun addEpisodePeak(peakMps2: Double) {
        peaks.addLast(peakMps2)
        while (peaks.size > maxHistory) peaks.removeFirst()
        tripPeaks += peakMps2
        if (isLearning && peaks.size >= minSamples) {
            baselineMps2 = clamp(quantile(peaks.sorted(), baselineQuantile))
        }
    }

    /** 주행 종료 시 호출: 이번 주행 표본으로 baseline 을 천천히 이동 */
    fun onTripEnd() {
        val base = baselineMps2
        if (base != null && tripPeaks.size >= 3) {
            val recent = quantile(tripPeaks.sorted(), baselineQuantile)
            baselineMps2 = clamp(base + adaptRate * (recent - base))
        }
        tripPeaks.clear()
    }

    /** 사람이 직접 누르는 '기준 다시 잡기' */
    fun resetLearning() {
        peaks.clear(); tripPeaks.clear(); baselineMps2 = null
    }

    fun currentThresholdMps2(): Double {
        val base = baselineMps2 ?: return sensitivity.learningThresholdMps2
        return clamp(base * sensitivity.multiplier)
    }

    // ---- 저장/복원 ----
    fun history(): List<Double> = peaks.toList()
    fun restore(history: List<Double>, baseline: Double?) {
        peaks.clear(); history.takeLast(maxHistory).forEach { peaks.addLast(it) }
        tripPeaks.clear()
        baselineMps2 = baseline
    }

    private fun clamp(v: Double) = v.coerceIn(minMps2, maxMps2)

    companion object {
        /** nearest-rank empirical quantile */
        fun quantile(sorted: List<Double>, q: Double): Double {
            require(sorted.isNotEmpty())
            val rank = ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
