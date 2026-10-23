package com.deskrobot

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/** 系统 TTS 封装（P1 先用系统引擎，P2 替换为云端流式 TTS） */
class Speaker(context: Context, private val onDone: () -> Unit) {

    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            Log.i(TAG, "TTS init ok, chinese supported=$ready")
        } else {
            Log.e(TAG, "TTS init failed: $status")
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                RobotBus.speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                RobotBus.speaking.value = false
                onDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                RobotBus.speaking.value = false
                onDone()
            }
        })
    }

    fun speak(text: String) {
        RobotBus.lastSpeech.value = text
        if (!ready) {
            Log.w(TAG, "TTS not ready, skip: $text")
            onDone()
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        private const val TAG = "Speaker"
    }
}
