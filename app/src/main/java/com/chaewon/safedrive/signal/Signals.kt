package com.chaewon.safedrive.signal

import com.chaewon.safedrive.core.LocationSample

/**
 * 앱 내부로 들어오는 차량/음성 신호. Pleos SDK 어댑터와 데모 모드(FakeSignalSource)가
 * 같은 인터페이스로 신호를 보내므로, 판단 로직은 어느 쪽인지 알 필요가 없다.
 */
interface SignalListener {
    fun onLocation(sample: LocationSample)
    fun onBrake(pressed: Boolean, now: Long)
    fun onCruiseState(on: Boolean, now: Long)
    fun onCruiseTarget(mps: Float?, now: Long)
    fun onAirbag(deployed: Boolean, seat: String, now: Long)
    fun onVehicleWarning(active: Boolean, what: String, now: Long)
    fun onVoiceCommand(text: String, now: Long)
}

/** 어떤 신호가 실제로 연결됐는지 (capability 확인 결과). 화면 안내에 사용. */
data class SignalAvailability(
    val location: Boolean = false,
    val brake: Boolean = false,
    val cruise: Boolean = false,
    val airbag: Boolean = false,
    val speech: Boolean = false,
)

interface SignalSource {
    fun start(listener: SignalListener, onAvailability: (SignalAvailability) -> Unit = {})
    fun stop()
}
