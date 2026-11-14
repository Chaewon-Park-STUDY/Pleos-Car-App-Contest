package com.chaewon.safedrive

import android.app.Application
import com.chaewon.safedrive.app.DriveController
import com.chaewon.safedrive.app.VoiceOutput
import com.chaewon.safedrive.data.JsonFileStore
import com.chaewon.safedrive.signal.PleosLocationSource
import com.chaewon.safedrive.signal.PleosVehicleSource
import com.chaewon.safedrive.signal.SignalAvailability
import com.chaewon.safedrive.voice.AndroidTtsVoice
import com.chaewon.safedrive.voice.PleosSttInput
import com.chaewon.safedrive.voice.PleosTtsVoice
import kotlinx.coroutines.MainScope

/**
 * 앱 전체에서 하나씩만 존재하는 객체들 (Pleos SDK 4개 + 컨트롤러).
 * Pleos 환경이 아니면 각 SDK 는 조용히 비활성화되고, 데모 모드와 안드로이드 기본 TTS 로 동작한다.
 */
class SafeDriveApp : Application() {
    lateinit var controller: DriveController
        private set
    lateinit var stt: PleosSttInput
        private set
    lateinit var location: PleosLocationSource
        private set
    var vehicleSource: PleosVehicleSource? = null
        private set
    private lateinit var tts: PleosTtsVoice

    /** 화면이 다시 그려져도(회전 등) 데모 주행이 끊기지 않도록 앱 수준 scope 사용 */
    val appScope = MainScope()

    private var avail = SignalAvailability()

    override fun onCreate() {
        super.onCreate()
        tts = PleosTtsVoice(this, fallback = AndroidTtsVoice(this))
        val voice = VoiceOutput { text -> tts.speak(text) }

        controller = DriveController(store = JsonFileStore(this), voice = voice)

        stt = PleosSttInput(
            this,
            onResult = { text -> controller.recordVoiceNote(text, System.currentTimeMillis()) },
            onListening = { controller.setListening(it) },
            onReady = { refreshSpeechAvailability() },
        )

        // 차량 신호 (Vehicle SDK)
        vehicleSource = PleosVehicleSource(this).also { src ->
            src.start(controller) { a -> updateAvail { a.copy(location = it.location, speech = it.speech) } }
        }

        location = PleosLocationSource(this)
    }

    /** MainActivity 에서 위치 권한을 받은 뒤 호출 */
    fun startLocation() {
        val ok = location.start(controller)
        updateAvail { it.copy(location = ok) }
    }

    fun refreshSpeechAvailability() = updateAvail { it.copy(speech = stt.ready) }

    private fun updateAvail(f: (SignalAvailability) -> SignalAvailability) {
        avail = f(avail)
        controller.setAvailability(avail)
    }
}
