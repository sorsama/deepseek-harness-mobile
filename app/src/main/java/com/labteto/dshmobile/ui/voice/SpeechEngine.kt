package com.labteto.dshmobile.ui.voice

/**
 * What a speech recogniser reports about one take, in the app's own terms.
 *
 * Every call arrives on the main thread. A take that was cancelled or replaced may still report
 * once more from the platform; the controller ignores those by take, so an engine does not have to
 * be perfect about it, only to not report after [SpeechEngine.release].
 */
interface SpeechListener {
    /** The recogniser is listening. */
    fun onReady()

    /** The input level in decibels, as `onRmsChanged` reports it. */
    fun onLevel(rmsDb: Float)

    /**
     * The recogniser stopped capturing on its own (the person fell silent) and is working out the
     * result; [onResults] or [onProblem] follows. Not reported for a stop the person asked for.
     */
    fun onEndOfSpeech() = Unit

    /** A partial hypothesis: what is settled, and the tail that may still change. */
    fun onPartial(stable: String?, unstable: String?)

    /** The take is over and these are its transcriptions, best first. */
    fun onResults(results: List<String>)

    /** The take is over without a result, for [problem]'s reason. */
    fun onProblem(problem: DictationProblem)
}

/**
 * The one door to the phone's speech recogniser.
 *
 * Kept this small so the dictation state machine is tested without a device: the real one is
 * [AndroidSpeechEngine], and a test brings a scripted fake. The recogniser is a platform service
 * that may be absent, so [available] is asked before anything is shown.
 *
 * The engine does not manage the audio itself. The recognition service owns the capture of a take,
 * and takes audio focus (or the microphone) for it as it sees fit, so an engine neither requests
 * focus nor ends a take because focus moved. A take ends when the person ends it, when the service
 * reports its end or an error, or when the screen reports that the app was left (see
 * [DictationController]).
 */
interface SpeechEngine {
    /** Whether the phone has a recognition service at all. Constant for a given engine. */
    val available: Boolean

    /**
     * Begins one take, listening for [language] (a BCP 47 tag) or for the phone's own when null.
     * Reports through [listener] until the take ends, is cancelled or the engine is released.
     */
    fun start(language: String?, listener: SpeechListener)

    /** Stops capturing and asks for the result of what was heard; [SpeechListener.onResults] follows. */
    fun stopListening()

    /** Abandons the take and reports nothing more for it. Safe to call when nothing is running. */
    fun cancel()

    /** Gives back everything held for the take: the recogniser. Nothing more is reported afterwards. */
    fun release()
}

/** A one-shot timer, so the controller's deadlines run without a real clock in a test. */
interface DictationClock {
    /** Runs [action] once after [delayMs] on the main thread; the returned function cancels it. */
    fun after(delayMs: Long, action: () -> Unit): () -> Unit
}
