package com.chaewon.safedrive.signal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.CruiseControlTargetSpeedListener
import ai.pleos.playground.vehicle.listener.PowerDownWarningIndicatorListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener

/**
 * Pleos Vehicle SDK v2.3.2 → SignalListener 어댑터 (읽기 전용, 제어 API 미사용).
 *
 *  Brake        : registerBrakePedalStatus            → onBrake
 *  DrivingMode  : registerCruiseControlState          → onCruiseState
 *                 registerCruiseControlTargetSpeed    → onCruiseTarget (m/s)
 *  Safety       : registerSeatAirbagDeployed(좌석별)   → onAirbag
 *                 registerPowerDownWarningIndicator   → onVehicleWarning
 *
 * SDK 콜백은 IPC 스레드에서 오므로 메인 스레드로 넘겨서 컨트롤러에 전달한다.
 * 어떤 신호가 실패(onFailed)하면 그 기능만 꺼지고 나머지는 계속 동작한다.
 */
class PleosVehicleSource(private val context: Context) : SignalSource {

    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var listener: SignalListener? = null
    private var avail = SignalAvailability()
    private var onAvail: (SignalAvailability) -> Unit = {}

    private val now: () -> Long = { System.currentTimeMillis() }
    private fun post(block: () -> Unit) = main.post(block)
    private fun update(f: (SignalAvailability) -> SignalAvailability) = post { avail = f(avail); onAvail(avail) }

    // ---------------- 리스너 ----------------

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            post { listener?.onBrake(status == true, now()) }
        }
        override fun onFailed(e: Exception) { fail("brake", e); update { it.copy(brake = false) } }
    }

    private val cruiseListener = object : CruiseControlStateListener {
        override fun onCruiseControlStateUpdated(state: CruiseControl?) {
            post { listener?.onCruiseState(isCruiseActive(state), now()) }
        }
        override fun onFailed(e: Exception) { fail("cruise", e); update { it.copy(cruise = false) } }
    }

    private val targetListener = object : CruiseControlTargetSpeedListener {
        override fun onCruiseControlTargetSpeedUpdated(speed: Float?) {
            post { listener?.onCruiseTarget(speed, now()) }
        }
        override fun onFailed(e: Exception) { fail("cruiseTarget", e) }
    }

    private val warningListener = object : PowerDownWarningIndicatorListener {
        override fun onPowerDownWarningIndicatorUpdated(state: Boolean?) {
            post { listener?.onVehicleWarning(state == true, "엔진 출력 저하 경고", now()) }
        }
        override fun onFailed(e: Exception) { fail("powerDown", e) }
    }

    /** 좌석별 에어백 리스너 해제 동작 (좌석 타입을 직접 다루지 않기 위해 해제 함수로 보관) */
    private val airbagUnregisters = mutableListOf<() -> Unit>()

    // ---------------- 시작 / 종료 ----------------

    override fun start(listener: SignalListener, onAvailability: (SignalAvailability) -> Unit) {
        this.listener = listener
        this.onAvail = onAvailability
        val v = try {
            Vehicle(context).also { it.initialize() }
        } catch (t: Throwable) {
            // Pleos 환경이 아닌 기기(일반 폰 에뮬레이터 등)에서는 차량 신호 없이 동작 → 데모 모드 사용
            Log.w(TAG, "Vehicle SDK unavailable: ${t.message}")
            return
        }
        vehicle = v

        safely("brake") { v.getBrake().registerBrakePedalStatus(brakeListener); update { it.copy(brake = true) } }
        safely("cruise") {
            val dm = v.getDrivingMode()
            dm.registerCruiseControlState(cruiseListener)
            dm.registerCruiseControlTargetSpeed(targetListener)
            update { it.copy(cruise = true) }
        }
        safely("safety") {
            val safety = v.getSafety()
            safety.registerPowerDownWarningIndicator(warningListener)
            // 차량이 지원하는 좌석 목록을 받아 좌석별로 에어백 구독
            safety.checkAirbagDeployedCapabilities(
                onComplete = { _, areas ->
                    areas.forEach { area ->
                        val seat = area.toString()
                        val l = object : SeatAirbagDeployedListener {
                            override fun onSeatAirbagDeployUpdated(deployed: Boolean?) {
                                post { listener.onAirbag(deployed == true, seat, now()) }
                            }
                            override fun onFailed(e: Exception) { fail("airbag $seat", e) }
                        }
                        safely("airbag $seat") {
                            safety.registerSeatAirbagDeployed(area, l)
                            airbagUnregisters += { safety.unregisterSeatAirbagDeployed(area, l) }
                        }
                    }
                    update { it.copy(airbag = areas.isNotEmpty()) }
                },
                onFailed = { e -> fail("airbagCapability", e) },
            )
        }
    }

    override fun stop() {
        val v = vehicle ?: return
        safely("stop") {
            v.getBrake().unregisterBrakePedalStatus(brakeListener)
            v.getDrivingMode().unregisterCruiseControlState(cruiseListener)
            v.getDrivingMode().unregisterCruiseControlTargetSpeed(targetListener)
            val safety = v.getSafety()
            safety.unregisterPowerDownWarningIndicator(warningListener)
            airbagUnregisters.forEach { it() }
            airbagUnregisters.clear()
        }
        safely("release") { v.release() }
        vehicle = null
        listener = null
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "$what failed: ${t.message}") }
    }

    private fun fail(what: String, e: Exception) = Log.w(TAG, "$what onFailed: ${e.message}")

    companion object {
        private const val TAG = "PleosVehicleSource"

        /**
         * CruiseControl: OTHER, ENABLED, ACTIVATED, USER_OVERRIDE, SUSPENDED, FORCED_DEACTIVATION_WARNING
         *  - ACTIVATED      : 크루즈가 실제로 속도를 제어 중 → 이때의 무제동 감속만 '차량 감속'
         *  - ENABLED        : 켜져 있지만 대기(속도 제어 안 함)
         *  - USER_OVERRIDE  : 운전자가 가속 페달로 개입 → 운전자 제어
         *  - SUSPENDED 등   : 일시 해제 → 운전자 제어
         */
        fun isCruiseActive(state: CruiseControl?): Boolean = state == CruiseControl.ACTIVATED
    }
}
