package com.labteto.dshmobile.ui.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where one dictation stands. The draft is never part of it: it belongs to the composer. */
sealed interface DictationState {
    /** Not listening. */
    data object Idle : DictationState

    /** Waiting for the person to answer the system's microphone dialog. */
    data object AskingPermission : DictationState

    /** The recogniser is starting up. */
    data object Starting : DictationState

    /** Listening: [level] is 0..1 for the indicator, [heard] the running text of this take. */
    data class Listening(val level: Float, val heard: String) : DictationState

    /** The person stopped; the recogniser is working out what it heard. */
    data class Finishing(val heard: String) : DictationState

    /** True for everything but [Idle]. */
    val busy: Boolean get() = this !is Idle
}

/** A message for the strip under the composer. [serial] tells two identical ones apart, so each gets its own timer. */
data class DictationNotice(val problem: DictationProblem, val serial: Int)

/** Something that ended or interrupted a take from outside the person's own hand. */
enum class DictationInterruption {
    /** The app left the foreground, or the screen turned off. */
    Background,

    /** Another window, a system dialog or the notification shade took the screen's focus. */
    WindowCovered,

    /** The composer now belongs to another session; what was heard is not for it. */
    SessionChanged,
}

/** What the person feels and what the host learns, one per outcome. */
enum class DictationSignal { Started, Stopped, Inserted, Cancelled, Failed }

/** The surroundings of a take: what only the screen can know or do. Everything runs on the main thread. */
interface DictationHost {
    /** Whether the microphone permission is already granted. */
    fun microphoneAllowed(): Boolean

    /** Shows the system's microphone dialog; the answer comes back through [DictationController.onPermissionResult]. */
    fun requestMicrophone()

    /** False while the app is in the background, the screen is locked or another window has focus. */
    fun canListen(): Boolean

    /** Puts the recognised text in the draft, at the caret. */
    fun insert(text: String)

    /** The outcome, for haptics. */
    fun signal(signal: DictationSignal)
}

/**
 * One dictation at a time: tap to listen, tap again to stop, text goes into the draft.
 *
 * The rules, since the screen and the tests lean on them:
 *  - The draft is only ever written through [DictationHost.insert], once, with the final text.
 *    Cancel, an error, an interruption and a dispose never touch it.
 *  - A second tap while starting or finishing does nothing; there is never more than one take.
 *  - A language error retries the same take with the next language of [attempts], silently, so a
 *    service without the app's language still works in the phone's own.
 *  - A take that showed text and then ended empty-handed (no match, timeout, a stop the service
 *    never answered) keeps the last text it showed instead of losing it.
 *  - Anything that arrives from a take that has been replaced or cancelled is ignored.
 *
 * Plain Kotlin on purpose: the recogniser, the clock and the screen are behind small interfaces.
 */
class DictationController(
    private val engine: SpeechEngine,
    private val host: DictationHost,
    private val clock: DictationClock,
    private val attempts: List<String?>,
) {
    private val stateFlow = MutableStateFlow<DictationState>(DictationState.Idle)
    private val noticeFlow = MutableStateFlow<DictationNotice?>(null)

    /** Where the dictation stands. */
    val state: StateFlow<DictationState> = stateFlow.asStateFlow()

    /** The message to show, or null. Cleared by [clearNotice], by the next tap and by a new take. */
    val notice: StateFlow<DictationNotice?> = noticeFlow.asStateFlow()

    private var take = 0
    private var attempt = 0
    private var serial = 0
    private var lastPartial = ""
    private var disposed = false
    private var cancelDeadline: (() -> Unit)? = null

    /** The microphone control was tapped: starts a take, or stops the one that is listening. */
    fun toggle() {
        if (disposed) return
        when (stateFlow.value) {
            DictationState.Idle -> startFromIdle()
            is DictationState.Listening -> stop()
            // Asking, starting and finishing already have their answer on the way.
            else -> Unit
        }
    }

    /** The answer to the system's microphone dialog; [blocked] is true when it can never be shown again. */
    fun onPermissionResult(granted: Boolean, blocked: Boolean) {
        if (disposed || stateFlow.value != DictationState.AskingPermission) return
        when {
            !granted -> {
                stateFlow.value = DictationState.Idle
                raise(if (blocked) DictationProblem.PermissionBlocked else DictationProblem.PermissionDenied)
                host.signal(DictationSignal.Failed)
            }
            host.canListen() -> begin(0)
            // Granted, but the person has already left: nothing to start, and no message for it.
            else -> stateFlow.value = DictationState.Idle
        }
    }

    /** Discards what this take heard. The draft is untouched. */
    fun cancel() {
        if (disposed || !stateFlow.value.busy) return
        abandon()
        host.signal(DictationSignal.Cancelled)
    }

    /** Ends the take because of [reason]; what it heard is discarded and the draft is untouched. */
    fun interrupt(reason: DictationInterruption) {
        if (disposed) return
        when (stateFlow.value) {
            DictationState.Idle -> Unit
            // The system's own dialog covers the app while it asks; that is not a reason to forget the tap.
            DictationState.AskingPermission ->
                if (reason == DictationInterruption.SessionChanged) abandon()
            else -> {
                abandon()
                host.signal(DictationSignal.Cancelled)
            }
        }
    }

    /** Takes [notice] away once its time is up, unless a newer one has replaced it. */
    fun clearNotice(serial: Int) {
        if (noticeFlow.value?.serial == serial) noticeFlow.value = null
    }

    /** The composer is gone: stops everything and gives the recogniser back. The controller is not reused. */
    fun dispose() {
        if (disposed) return
        disposed = true
        take++
        cancelDeadline?.invoke()
        cancelDeadline = null
        engine.cancel()
        engine.release()
        stateFlow.value = DictationState.Idle
    }

    private fun startFromIdle() {
        if (!engine.available || !host.canListen()) return
        noticeFlow.value = null
        if (!host.microphoneAllowed()) {
            stateFlow.value = DictationState.AskingPermission
            host.requestMicrophone()
            return
        }
        begin(0)
    }

    private fun begin(index: Int) {
        attempt = index
        lastPartial = ""
        stateFlow.value = DictationState.Starting
        host.signal(DictationSignal.Started)
        launch()
    }

    /** One start of the recogniser, for the current attempt's language, under the start deadline. */
    private fun launch() {
        deadline(START_DEADLINE_MS) {
            if (stateFlow.value == DictationState.Starting) fail(DictationProblem.Service)
        }
        val id = ++take
        engine.start(attempts[attempt], Take(id))
    }

    private fun stop() {
        val heard = (stateFlow.value as? DictationState.Listening)?.heard.orEmpty()
        stateFlow.value = DictationState.Finishing(heard)
        host.signal(DictationSignal.Stopped)
        deadline(FINISH_DEADLINE_MS) {
            // The service never answered the stop: keep what was on screen rather than nothing.
            if (stateFlow.value is DictationState.Finishing) {
                engine.cancel()
                if (lastPartial.isBlank()) fail(DictationProblem.Timeout) else complete(lastPartial)
            }
        }
        engine.stopListening()
    }

    private fun complete(text: String) {
        clearDeadline()
        take++
        stateFlow.value = DictationState.Idle
        if (text.isBlank()) {
            raise(DictationProblem.NoMatch)
            host.signal(DictationSignal.Failed)
        } else {
            host.insert(text)
            host.signal(DictationSignal.Inserted)
        }
    }

    private fun fail(problem: DictationProblem) {
        clearDeadline()
        take++
        engine.cancel()
        stateFlow.value = DictationState.Idle
        raise(problem)
        host.signal(DictationSignal.Failed)
    }

    private fun abandon() {
        clearDeadline()
        take++
        engine.cancel()
        lastPartial = ""
        stateFlow.value = DictationState.Idle
        noticeFlow.value = null
    }

    private fun raise(problem: DictationProblem) {
        noticeFlow.value = DictationNotice(problem, ++serial)
    }

    private fun deadline(delayMs: Long, action: () -> Unit) {
        clearDeadline()
        cancelDeadline = clock.after(delayMs) {
            cancelDeadline = null
            action()
        }
    }

    private fun clearDeadline() {
        cancelDeadline?.invoke()
        cancelDeadline = null
    }

    /** The listener for one take; anything it reports after the take was replaced is dropped. */
    private inner class Take(private val id: Int) : SpeechListener {
        private fun current() = !disposed && id == take

        override fun onReady() {
            if (!current() || stateFlow.value != DictationState.Starting) return
            clearDeadline()
            stateFlow.value = DictationState.Listening(0f, "")
        }

        override fun onLevel(rmsDb: Float) {
            if (!current()) return
            val now = stateFlow.value as? DictationState.Listening ?: return
            stateFlow.value = now.copy(level = normalizeRms(rmsDb))
        }

        override fun onPartial(stable: String?, unstable: String?) {
            if (!current()) return
            val merged = mergePartial(stable, unstable)
            if (merged.isBlank()) return
            lastPartial = merged
            when (val now = stateFlow.value) {
                is DictationState.Listening -> stateFlow.value = now.copy(heard = merged)
                DictationState.Starting -> {
                    // A service that skipped the ready signal is evidently listening.
                    clearDeadline()
                    stateFlow.value = DictationState.Listening(0f, merged)
                }
                is DictationState.Finishing -> stateFlow.value = DictationState.Finishing(merged)
                else -> Unit
            }
        }

        override fun onResults(results: List<String>) {
            if (!current() || !stateFlow.value.busy || stateFlow.value == DictationState.AskingPermission) return
            complete(chooseFinalText(results, lastPartial))
        }

        override fun onProblem(problem: DictationProblem) {
            if (!current() || !stateFlow.value.busy || stateFlow.value == DictationState.AskingPermission) return
            when {
                // No model for the language asked for: the same take again in the next one, quietly.
                problem == DictationProblem.Language && attempt + 1 < attempts.size -> {
                    attempt++
                    stateFlow.value = DictationState.Starting
                    launch()
                }
                // Something was heard and shown; a failure at the very end does not take it back.
                (problem == DictationProblem.NoMatch || problem == DictationProblem.Timeout) && lastPartial.isNotBlank() ->
                    complete(lastPartial)
                else -> fail(problem)
            }
        }
    }

    private companion object {
        /** A recogniser that has not said it is ready by now is not going to. */
        const val START_DEADLINE_MS = 10_000L

        /** How long a stop may wait for the service's answer before the last partial is taken instead. */
        const val FINISH_DEADLINE_MS = 4_000L
    }
}
