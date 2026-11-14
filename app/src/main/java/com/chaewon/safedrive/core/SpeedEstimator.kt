package com.chaewon.safedrive.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** FusedLocation LocationData에서 필요한 값만 옮긴 것 (SDK 의존성 없이 테스트하기 위함). */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val confidence: Float,
    val timestampMillis: Long,
)

/** 계산된 주행 상태 한 점. decelMps2 > 0 이면 감속 중. */
data class SpeedSample(
    val timestampMillis: Long,
    val speedMps: Double,
    val decelMps2: Double,
    val latitude: Double,
    val longitude: Double,
)

/**
 * LocationData에는 속도 필드가 없으므로 연속 위치로 속도·감속도를 계산한다.
 *  1) 두 위치 사이 거리(Haversine) / 시간 차 = 원시 속도
 *  2) EMA smoothing 으로 GPS 노이즈 완화
 *  3) 감속도 = (이전 속도 - 현재 속도) / 시간 차
 */
class SpeedEstimator(
    private val alpha: Double = 0.7,              // EMA 가중치 (클수록 최신값 반영↑)
    private val minConfidence: Float = 0f,        // 이 값 미만 위치는 버림 (실기기 값 분포 확인 후 조정)
    private val maxGapMillis: Long = 5_000,       // 이보다 간격이 크면 연속성 끊긴 것으로 보고 초기화
    private val maxPlausibleSpeedMps: Double = 70.0, // ≈252km/h, 위치 튐 제거
) {
    private var prevLoc: LocationSample? = null
    private var prevSpeed: Double? = null

    fun reset() { prevLoc = null; prevSpeed = null }

    fun feed(s: LocationSample): SpeedSample? {
        if (s.confidence < minConfidence) return null
        val p = prevLoc
        if (p == null) { prevLoc = s; return null }

        val dtMs = s.timestampMillis - p.timestampMillis
        if (dtMs <= 0) return null
        if (dtMs > maxGapMillis) { reset(); prevLoc = s; return null }

        val dt = dtMs / 1000.0
        val raw = haversineMeters(p.latitude, p.longitude, s.latitude, s.longitude) / dt
        if (raw > maxPlausibleSpeedMps) return null   // 위치 튐: 이 점은 무시, 이전 점 유지

        val last = prevSpeed
        val smoothed = if (last == null) raw else alpha * raw + (1 - alpha) * last
        val decel = if (last == null) 0.0 else (last - smoothed) / dt

        prevLoc = s
        prevSpeed = smoothed
        return SpeedSample(s.timestampMillis, smoothed, decel, s.latitude, s.longitude)
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(a))
        }
    }
}
