package com.labteto.dshmobile.ui.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The phone's own speech recogniser behind [SpeechEngine].
 *
 * Nothing here is a new dependency or a new server: it is the platform `SpeechRecognizer`, whichever
 * service the phone has chosen for it. That service may run on the device or may be an online
 * service of the phone's maker, which is why the first use says so (see `docs/SECURITY.md`). Audio
 * goes from the microphone to that service and nowhere else: the app does not use
 * `onBufferReceived`, does not write audio to disk and does not log what was recognised.
 *
 * A fresh recogniser is made for every take and destroyed at its end. Reusing one is the classic
 * way to get `ERROR_RECOGNIZER_BUSY` on the second take, and a take is short.
 *
 * While a take runs, the engine holds transient audio focus, so music pauses instead of being
 * recorded, and a call or another app taking the audio ends the take (see [SpeechListener.onProblem]
 * with [DictationProblem.Interrupted]).
 */
class AndroidSpeechEngine(context: Context) : SpeechEngine {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Asked once. Needs the `RecognitionService` query in the manifest: from Android 11 on, package
     * visibility makes the lookup answer false without it, on a phone that has a service.
     */
    override val available: Boolean = speechRecognitionAvailable(app)

    private var recognizer: SpeechRecognizer? = null
    private var focus: AudioFocusRequest? = null

    /** Bumped whenever a take ends, so a late callback from the platform finds itself out of date. */
    private var session = 0

    override fun start(language: String?, listener: SpeechListener) {
        endTake()
        val id = session
        if (!takeAudioFocus(id, listener)) {
            listener.onProblem(DictationProblem.Busy)
            return
        }
        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(app) }.getOrNull()
        if (created == null) {
            endTake()
            listener.onProblem(DictationProblem.Service)
            return
        }
        recognizer = created
        created.setRecognitionListener(Callbacks(id, listener))
        if (runCatching { created.startListening(recognizerIntent(language)) }.isFailure) {
            endTake()
            listener.onProblem(DictationProblem.Failed)
        }
    }

    override fun stopListening() {
        runCatching { recognizer?.stopListening() }
    }

    override fun cancel() = endTake()

    override fun release() = endTake()

    private fun recognizerIntent(language: String?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // A preference, not a requirement: a service with an offline model for the language
            // uses it, and one without falls back to its online recogniser as it always did.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Dictation is a sentence or several; the defaults cut a take off at the first breath.
            // Services are free to ignore these, and many do.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
            if (language != null) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            }
        }

    private fun takeAudioFocus(id: Int, listener: SpeechListener): Boolean {
        val manager = audio ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change ->
                // A call or another app has the audio now. Ducking for a notification is not that.
                val taken = change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                if (taken && id == session) {
                    endTake()
                    listener.onProblem(DictationProblem.Interrupted)
                }
            }, handler)
            .build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = request
        return true
    }

    /** Ends the take quietly: stops capture, destroys the recogniser, gives the audio back. Idempotent. */
    private fun endTake() {
        session++
        val ending = recognizer
        recognizer = null
        val held = focus
        focus = null
        if (held != null) runCatching { audio?.abandonAudioFocusRequest(held) }
        if (ending != null) {
            runCatching { ending.cancel() }
            // Not from inside the recogniser's own callback: destroying it there is allowed but
            // has crashed on some services.
            handler.post { runCatching { ending.destroy() } }
        }
    }

    private inner class Callbacks(private val id: Int, private val out: SpeechListener) : RecognitionListener {
        private fun live() = id == session

        override fun onReadyForSpeech(params: Bundle?) {
            if (live()) out.onReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (live()) out.onLevel(rmsdB)
        }

        // Raw audio is not kept: this callback is deliberately empty.
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (!live()) return
            endTake()
            out.onProblem(dictationProblemFor(error))
        }

        override fun onResults(results: Bundle?) {
            if (!live()) return
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            endTake()
            out.onResults(texts)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live() || partialResults == null) return
            out.onPartial(
                partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(),
                partialResults.getStringArrayList(UNSTABLE_TEXT)?.firstOrNull(),
            )
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        /** `RecognitionService`'s key for the tail of a partial result that may still be revised. */
        const val UNSTABLE_TEXT = "android.speech.extra.UNSTABLE_TEXT"
    }
}

/**
 * Whether the phone has a speech recognition service. Many phones without Google's services have
 * none (Settings then explains why there is no microphone), and a service that exists but was
 * disabled counts as absent.
 */
fun speechRecognitionAvailable(context: Context): Boolean =
    runCatching { SpeechRecognizer.isRecognitionAvailable(context.applicationContext) }.getOrDefault(false)

/** The main-thread [DictationClock] the screen uses. */
class HandlerDictationClock : DictationClock {
    private val handler = Handler(Looper.getMainLooper())

    override fun after(delayMs: Long, action: () -> Unit): () -> Unit {
        val task = Runnable(action)
        handler.postDelayed(task, delayMs)
        return { handler.removeCallbacks(task) }
    }
}
