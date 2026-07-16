package com.school.faceverify.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import java.util.Locale

class FeedbackPlayer(context: Context) {
    private var tts: TextToSpeech? = null
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
    }

    fun playPass(name: String) {
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 200)
        tts?.speak("Verified. $name", TextToSpeech.QUEUE_FLUSH, null, "pass")
    }

    fun playFail() {
        tone.startTone(ToneGenerator.TONE_PROP_NACK, 400)
        tts?.speak("Verification failed", TextToSpeech.QUEUE_FLUSH, null, "fail")
    }

    fun release() {
        tts?.shutdown()
        tone.release()
    }
}
