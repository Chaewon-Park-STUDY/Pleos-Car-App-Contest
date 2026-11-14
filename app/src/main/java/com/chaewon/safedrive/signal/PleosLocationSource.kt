package com.chaewon.safedrive.signal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import com.chaewon.safedrive.core.LocationSample

/**
 * Pleos FusedLocation SDK v1.1.3 → SignalListener.onLocation
 *  FusedLocation(context) → isSupported() → initialize() → registerFusedLocationCallback()
 *  종료: unregisterFusedLocationCallback() → release()
 *
 * LocationData 에는 속도 필드가 없으므로 위치만 넘기고, 속도·감속은 core.SpeedEstimator 가 계산한다.
 * 퍼미션 pleos.car.permission.FUSED_LOCATION 은 dangerous 등급 → MainActivity 에서 사용자 허용 후 start().
 */
class PleosLocationSource(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var fused: FusedLocation? = null
    private var target: SignalListener? = null

    var isRunning = false
        private set

    private val callback = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            val sample = LocationSample(
                latitude = location.latitude,
                longitude = location.longitude,
                confidence = location.confidence,
                timestampMillis = location.timestampMillis,
            )
            main.post { target?.onLocation(sample) }
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "location error: ${exception.message}")
        }
    }

    /** Pleos 환경에서 위치 SDK 를 쓸 수 있는지 (일반 폰 에뮬레이터면 false) */
    fun isSupported(): Boolean = try { FusedLocation(context).isSupported() } catch (t: Throwable) { false }

    /** @return 위치 수신을 시작했으면 true */
    fun start(listener: SignalListener): Boolean {
        if (isRunning) return true
        return try {
            val f = FusedLocation(context)
            if (!f.isSupported()) return false
            f.initialize()
            target = listener
            f.registerFusedLocationCallback(callback)
            fused = f
            isRunning = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "FusedLocation unavailable: ${t.message}")
            false
        }
    }

    fun stop() {
        val f = fused ?: return
        try { f.unregisterFusedLocationCallback(callback) } catch (t: Throwable) { Log.w(TAG, "unregister: ${t.message}") }
        try { f.release() } catch (t: Throwable) { Log.w(TAG, "release: ${t.message}") }
        fused = null; target = null; isRunning = false
    }

    companion object {
        private const val TAG = "PleosLocationSource"
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}
