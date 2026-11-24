package io.github.vitorhpsousa.dpfsentinel

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

// Says regen alerts out loud with the phone's own text-to-speech. Played as navigation guidance,
// so it goes where the sat-nav voice goes (the car's speakers over Bluetooth or Android Auto) and
// music pauses while it talks. The engine is started on first use and kept for the app's life.
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()

    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayList<String>()   // said before the engine was ready
    private var speaking = 0
    private var nextId = 0

    /** "" while fine, else why nothing can be said (shown by the settings screen). */
    @Volatile var problem = ""
        private set

    @Synchronized fun say(text: String) {
        if (tts == null) {
            problem = ""
            tts = TextToSpeech(app, this)
        }
        if (ready) speakNow(text) else waiting.add(text)
    }

    @Synchronized override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            problem = "This phone has no text-to-speech voice set up (Settings, Accessibility, Text-to-speech)"
            Log.w(TAG, "text-to-speech failed to start: $status")
            waiting.clear()
            tts = null
            return
        }
        runCatching {
            t.setAudioAttributes(attrs)
            // The alerts are English: use an English voice even when the phone is in another language.
            if (Locale.getDefault().language != "en" && t.isLanguageAvailable(Locale.UK) >= TextToSpeech.LANG_AVAILABLE) t.language = Locale.UK
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = finished()
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = finished()
                override fun onError(id: String?, code: Int) = finished()
            })
        }
        ready = true
        waiting.forEach { speakNow(it) }
        waiting.clear()
    }

    // Call with the lock held.
    private fun speakNow(text: String) {
        val t = tts ?: return
        if (speaking == 0) runCatching { audio?.requestAudioFocus(focus) }
        speaking++
        val r = runCatching { t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "dpf${nextId++}") }.getOrDefault(TextToSpeech.ERROR)
        if (r != TextToSpeech.SUCCESS) finished()
    }

    @Synchronized private fun finished() {
        if (speaking > 0) speaking--
        if (speaking == 0) runCatching { audio?.abandonAudioFocusRequest(focus) }   // music carries on
    }

    private companion object { const val TAG = "Speaker" }
}
