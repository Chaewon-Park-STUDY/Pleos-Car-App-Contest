package com.chaewon.safedrive.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener

/**
 * Pleos SpeechToText SDK v3.3.1 (ON_DEVICE 모드: 네트워크·앱 등록 불필요).
 * 규정상 외부 음성 인식 API 는 쓰지 않는다 → Pleos STT 가 없으면 음성 기록 기능만 끈다.
 *
 *  🎤 버튼 → listen() → request() → onUpdated(…, completed=true) → onResult(최종 문장)
 * 퍼미션: pleos.car.permission.STT_SERVICE
 */
class PleosSttInput(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onListening: (String?) -> Unit,   // null = 듣기 끝, "" = 듣는 중, 그 외 = 중간 결과
    private val onReady: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var ready = false
        private set
    private var stt: SpeechToText? = null
    private var listening = false

    private val resultListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() { main.post { onListening("") } }
        override fun onUpdated(stt: String, completed: Boolean) {
            main.post {
                if (completed) { finish(); onResult(stt) } else onListening(stt)
            }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() { main.post { finish() } }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "STT error: $errorType")
            main.post { finish() }
        }
    }

    init {
        try {
            val s = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    try { s.addListener(resultListener); ready = true; main.post { onReady() } } catch (t: Throwable) { Log.w(TAG, "addListener: ${t.message}") }
                }
                override fun onFailure(errorType: ErrorType) { Log.w(TAG, "STT init failed: $errorType") }
            })
            stt = s
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos STT unavailable: ${t.message}")
        }
    }

    /** 🎤 버튼: 듣기 시작 / 다시 누르면 중지 */
    fun toggle() {
        val s = stt ?: return
        if (!ready) return
        try {
            if (listening) { s.stop(); finish() }
            else { listening = true; onListening(""); s.request() }
        } catch (t: Throwable) {
            Log.w(TAG, "request/stop: ${t.message}"); finish()
        }
    }

    private fun finish() { listening = false; onListening(null) }

    fun release() {
        try { stt?.stop(); stt?.removeListener(resultListener); stt?.release() } catch (_: Throwable) {}
        stt = null; ready = false
    }

    companion object { private const val TAG = "PleosSttInput" }
}
