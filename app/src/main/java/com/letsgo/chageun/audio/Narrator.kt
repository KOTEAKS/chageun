package com.letsgo.chageun.audio

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.letsgo.chageun.BuildConfig
import com.letsgo.chageun.core.AudioOutput

/**
 * Speaks the narration through the Pleos TTS SDK (console: "준비 중"). Subtitles are always on screen,
 * so a missing or failing TTS never blocks a puzzle.
 * Audio focus: requested per line and given back when it ends. On any loss speech stops and stays held until the player acts again ([unhold]);
 * a permanent loss is reported so the session can stop.
 */
class Narrator(
    context: Context,
    private val voiceEnabled: () -> Boolean,
    private val onFocusLost: (permanent: Boolean) -> Unit,
) : AudioOutput {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val tts = TextToSpeech(context.applicationContext, Mode.HYBRID)
    /** Long/short beeps for the episode sign, so the clue is not only visual. */
    private val tones: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
    private var ready = false
    /** Set when focus was lost; no new line is spoken automatically until the player resumes or taps "다시 말해 줘". */
    @Volatile private var held = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                held = true
                stopAll()
                onFocusLost(change == AudioManager.AUDIOFOCUS_LOSS)
            }
        }
        .build()

    /**
     * review-safety-v0.1 L7: focus is given back once the narrator has been quiet for [RELEASE_IDLE_MS], so radio or
     * navigation is ducked only while it talks, not for the whole session. The SDK's events carry no line id, so
     * instead of deciding which line an end event belongs to, any sign of a new line (say, ready, start) cancels the
     * pending release: the stop that speak(flush) sends for the line it replaces cannot take the new line's focus,
     * and a line that ends or fails at once still gives focus back (Codex review of L7).
     * "voice start/end" in the log also lets the demo script wait for a line to finish.
     */
    private val main = Handler(Looper.getMainLooper())
    private val releaseFocus = Runnable { audioManager.abandonAudioFocusRequest(focusRequest) }

    private val lineEvents = object : EventListener {
        override fun onStart() {
            main.removeCallbacks(releaseFocus)
            Log.i(TAG, "voice start")
        }
        override fun onReady() { main.removeCallbacks(releaseFocus) }
        override fun onDone() = lineEnded("done")
        override fun onStop() = lineEnded("stop")
        override fun onError(errorType: ErrorType) = lineEnded("error $errorType")
        override fun onUpdatedRms(rms: Double) {}
    }

    private fun lineEnded(how: String) {
        Log.i(TAG, "voice end ($how)")
        main.removeCallbacks(releaseFocus)
        main.postDelayed(releaseFocus, RELEASE_IDLE_MS)
    }

    fun start() {
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { tts.setAudioAttributes(attributes) }
                    runCatching { tts.addEventListener(lineEvents) }
                    tts.registerApp(BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET, object : OnServerConnectionListener {
                        override fun onConnected() { ready = true }
                        override fun onFailed(errorType: ErrorType) {
                            Log.w(TAG, "tts server: $errorType, using on-device voice")
                            ready = true
                        }
                    })
                }
                override fun onFailure(errorType: ErrorType) { Log.w(TAG, "tts init: $errorType (subtitles only)") }
            })
        }.onFailure { Log.w(TAG, "tts unavailable (subtitles only)", it) }
    }

    /** Allows speech again after a focus loss; called on an explicit player action. */
    fun unhold() {
        held = false
    }

    fun say(text: String) {
        if (!ready || !voiceEnabled() || held) return
        main.removeCallbacks(releaseFocus)
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        runCatching { tts.speak(text, true) }.onFailure {
            Log.w(TAG, "speak", it)
            main.postDelayed(releaseFocus, RELEASE_IDLE_MS)
        }
    }

    /** One beep of [millis]. Played only on a player's tap, and never while audio is held after a focus loss. */
    fun beep(millis: Int) {
        if (held) return
        runCatching { tones?.startTone(ToneGenerator.TONE_DTMF_6, millis) }
    }

    override fun stopAll() {
        main.removeCallbacks(releaseFocus)
        runCatching { tts.stop() }
        runCatching { tones?.stopTone() }
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun release() {
        stopAll()
        runCatching { tts.removeEventListener(lineEvents) }
        runCatching { tts.release() }
        runCatching { tones?.release() }
    }

    companion object {
        private const val TAG = "Chageun"
        private const val RELEASE_IDLE_MS = 600L
    }
}
