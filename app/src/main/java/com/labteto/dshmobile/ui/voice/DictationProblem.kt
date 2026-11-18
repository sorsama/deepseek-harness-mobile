package com.labteto.dshmobile.ui.voice

/**
 * Why a dictation take ended without text, in the few kinds a person can act on.
 *
 * `SpeechRecognizer` reports fifteen numeric codes across API levels, most of them variations on
 * "the service had a problem". They are folded here into the messages the composer shows, so the
 * screen never has to explain a code and a new code from a newer Android falls into [Failed]
 * instead of crashing a `when`.
 */
enum class DictationProblem {
    /** The service heard speech but could not turn it into words. */
    NoMatch,

    /** Nothing was said before the service gave up waiting. */
    Timeout,

    /** The service needed the network and could not reach it. */
    Network,

    /** The service, or the speech request limit, is occupied; trying again in a moment works. */
    Busy,

    /** The microphone could not be opened or stopped delivering audio. */
    Audio,

    /** The recognition service failed, disconnected or is missing. */
    Service,

    /** The service has no model for the requested language. See [DictationController]'s fallback. */
    Language,

    /** Anything else, including codes this app has never seen. */
    Failed,

    /** Another app or a call took the audio away from the take. */
    Interrupted,

    /** The microphone permission was refused; the system dialog can still be shown again. */
    PermissionDenied,

    /** The microphone permission is refused for good; only the app's system settings can undo it. */
    PermissionBlocked,
    ;

    /** True where the message can usefully offer a way into the app's system settings. */
    val offersSettings: Boolean get() = this == PermissionBlocked
}

/**
 * `SpeechRecognizer.ERROR_*` as plain numbers.
 *
 * Written out rather than read from the platform class: the newer codes (API 31 and later) are
 * compile-time constants a unit test would not need Android for, and spelling them here makes the
 * mapping below readable next to the number a log line shows.
 */
internal object RecognizerCodes {
    const val NETWORK_TIMEOUT = 1
    const val NETWORK = 2
    const val AUDIO = 3
    const val SERVER = 4
    const val CLIENT = 5
    const val SPEECH_TIMEOUT = 6
    const val NO_MATCH = 7
    const val RECOGNIZER_BUSY = 8
    const val INSUFFICIENT_PERMISSIONS = 9
    const val TOO_MANY_REQUESTS = 10
    const val SERVER_DISCONNECTED = 11
    const val LANGUAGE_NOT_SUPPORTED = 12
    const val LANGUAGE_UNAVAILABLE = 13
    const val CANNOT_CHECK_SUPPORT = 14
    const val CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15
}

/** Maps a `SpeechRecognizer.ERROR_*` code to what the person is told. Unknown codes are [DictationProblem.Failed]. */
fun dictationProblemFor(code: Int): DictationProblem = when (code) {
    RecognizerCodes.NETWORK_TIMEOUT, RecognizerCodes.NETWORK -> DictationProblem.Network
    RecognizerCodes.AUDIO -> DictationProblem.Audio
    RecognizerCodes.SERVER, RecognizerCodes.SERVER_DISCONNECTED -> DictationProblem.Service
    RecognizerCodes.SPEECH_TIMEOUT -> DictationProblem.Timeout
    RecognizerCodes.NO_MATCH -> DictationProblem.NoMatch
    RecognizerCodes.RECOGNIZER_BUSY, RecognizerCodes.TOO_MANY_REQUESTS -> DictationProblem.Busy
    // The service refused for want of the permission although the app asked for it first: it was
    // revoked from the system settings while the take was running.
    RecognizerCodes.INSUFFICIENT_PERMISSIONS -> DictationProblem.PermissionBlocked
    RecognizerCodes.LANGUAGE_NOT_SUPPORTED, RecognizerCodes.LANGUAGE_UNAVAILABLE -> DictationProblem.Language
    // CLIENT is what a recogniser reports when it was destroyed under a running take, and the two
    // newest codes only come from calls this app does not make.
    else -> DictationProblem.Failed
}

/** How long a message stays under the composer before it takes itself away, in milliseconds. */
fun DictationProblem.noticeMillis(): Long = if (offersSettings) 8_000L else 5_000L
