package com.chaewon.safedrive.voice

import android.content.Context
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import com.chaewon.safedrive.app.VoiceOutput

/**
 * Pleos TextToSpeech SDK v3.3.1 (ON_DEVICE 모드: 네트워크·앱 등록 불필요).
 * 초기화 전이거나 실패하면(일반 폰 에뮬레이터 등) fallback(안드로이드 기본 TTS)으로 말한다.
 * 퍼미션: pleos.car.permission.TTS_SERVICE
 */
class PleosTtsVoice(context: Context, private val fallback: VoiceOutput) : VoiceOutput {

    @Volatile private var ready = false
    private var tts: TextToSpeech? = null

    init {
        try {
            val t = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() { ready = true; Log.i(TAG, "Pleos TTS ready") }
                override fun onFailure(errorType: ErrorType) { ready = false; Log.w(TAG, "Pleos TTS init failed: $errorType") }
            })
            tts = t
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos TTS unavailable: ${t.message}")
        }
    }

    val usingPleos: Boolean get() = ready

    override fun speak(text: String) {
        val t = tts
        if (ready && t != null) {
            try { t.speak(text); return } catch (e: Throwable) { Log.w(TAG, "speak failed: ${e.message}") }
        }
        fallback.speak(text)
    }

    fun release() {
        try { tts?.stop(); tts?.release() } catch (_: Throwable) {}
        tts = null; ready = false
    }

    companion object { private const val TAG = "PleosTtsVoice" }
}
