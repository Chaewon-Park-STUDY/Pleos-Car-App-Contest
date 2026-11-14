package com.chaewon.safedrive.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import com.chaewon.safedrive.app.VoiceOutput
import java.util.Locale

/**
 * 임시 음성 출력: 안드로이드 기본 TextToSpeech.
 * Pleos TextToSpeech SDK 문서 확인 후 PleosTtsVoice 로 교체한다 (VoiceOutput 인터페이스 동일).
 */
class AndroidTtsVoice(context: Context) : VoiceOutput {
    @Volatile private var ready = false
    private val pending = mutableListOf<String>()
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.KOREAN
                ready = true
                synchronized(pending) { pending.forEach { speakNow(it) }; pending.clear() }
            }
        }
    }

    override fun speak(text: String) {
        if (ready) speakNow(text) else synchronized(pending) { pending += text }
    }

    private fun speakNow(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "sd-" + System.nanoTime())
    }

    fun shutdown() = tts.shutdown()
}
