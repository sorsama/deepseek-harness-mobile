package com.labteto.dshmobile.ui.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted recogniser: records what the controller asked and lets a test play the platform's part. */
private class FakeEngine(override val available: Boolean = true) : SpeechEngine {
    val starts = mutableListOf<String?>()
    var stops = 0
    var cancels = 0
    var released = false
    var listener: SpeechListener? = null

    override fun start(language: String?, listener: SpeechListener) {
        starts += language
        this.listener = listener
    }

    override fun stopListening() { stops++ }
    override fun cancel() { cancels++ }
    override fun release() { released = true }
}

private class FakeClock : DictationClock {
    private class Timer(val at: Long, val action: () -> Unit) { var cancelled = false }
    private val timers = mutableListOf<Timer>()
    private var now = 0L

    override fun after(delayMs: Long, action: () -> Unit): () -> Unit {
        val timer = Timer(now + delayMs, action)
        timers += timer
        return { timer.cancelled = true }
    }

    fun advance(ms: Long) {
        now += ms
        timers.filter { !it.cancelled && it.at <= now }.forEach { it.cancelled = true; it.action() }
    }
}

private class FakeHost(
    var allowed: Boolean = true,
    var listenable: Boolean = true,
) : DictationHost {
    val inserted = mutableListOf<String>()
    val signals = mutableListOf<DictationSignal>()
    var permissionRequests = 0

    override fun microphoneAllowed() = allowed
    override fun requestMicrophone() { permissionRequests++ }
    override fun canListen() = listenable
    override fun insert(text: String) { inserted += text }
    override fun signal(signal: DictationSignal) { signals += signal }
}

class DictationControllerTest {
    private val engine = FakeEngine()
    private val clock = FakeClock()
    private val host = FakeHost()
    private fun controller(
        attempts: List<String?> = listOf("th-TH", null),
        engine: FakeEngine = this.engine,
    ) = DictationController(engine, host, clock, attempts)

    private fun DictationController.listening(): DictationController {
        toggle()
        engine.listener!!.onReady()
        return this
    }

    // ---- start ----

    @Test fun aTapStartsATakeInTheFirstLanguage() {
        val c = controller()
        c.toggle()
        assertEquals(DictationState.Starting, c.state.value)
        assertEquals(listOf<String?>("th-TH"), engine.starts)
        assertEquals(listOf(DictationSignal.Started), host.signals)
    }

    @Test fun readyMeansListeningAndTheLevelFollowsTheMicrophone() {
        val c = controller().listening()
        assertEquals(DictationState.Listening(0f, ""), c.state.value)
        engine.listener!!.onLevel(10f)
        assertEquals(DictationState.Listening(1f, ""), c.state.value)
        engine.listener!!.onLevel(-2f)
        assertEquals(DictationState.Listening(0f, ""), c.state.value)
    }

    @Test fun partialResultsAreShownAsTheyArriveAndMergeStableWithUnstable() {
        val c = controller().listening()
        engine.listener!!.onPartial("hello", null)
        assertEquals("hello", (c.state.value as DictationState.Listening).heard)
        engine.listener!!.onPartial("hello", "wor")
        assertEquals("hello wor", (c.state.value as DictationState.Listening).heard)
        assertTrue("a partial never reaches the draft", host.inserted.isEmpty())
    }

    @Test fun aServiceThatSkipsTheReadySignalIsListeningOnceItSendsText() {
        val c = controller()
        c.toggle()
        engine.listener!!.onPartial("hi", null)
        assertEquals(DictationState.Listening(0f, "hi"), c.state.value)
    }

    // ---- stop and final ----

    @Test fun tappingAgainStopsAndTheFinalTextGoesInOnce() {
        val c = controller().listening()
        engine.listener!!.onPartial("hello wor", null)
        c.toggle()
        assertEquals(DictationState.Finishing("hello wor"), c.state.value)
        assertEquals(1, engine.stops)
        engine.listener!!.onResults(listOf("hello world"))
        assertEquals(DictationState.Idle, c.state.value)
        assertEquals(listOf("hello world"), host.inserted)
        assertEquals(
            listOf(DictationSignal.Started, DictationSignal.Stopped, DictationSignal.Inserted),
            host.signals,
        )
        assertNull(c.notice.value)
    }

    @Test fun aTakeThatEndsByItselfOnSilenceStillInsertsItsText() {
        val c = controller().listening()
        engine.listener!!.onPartial("short one", null)
        engine.listener!!.onResults(listOf("short one"))
        assertEquals(DictationState.Idle, c.state.value)
        assertEquals(listOf("short one"), host.inserted)
    }

    @Test fun anEmptyFinalAfterVisiblePartialsKeepsTheLastPartial() {
        val c = controller().listening()
        engine.listener!!.onPartial("keep this", null)
        engine.listener!!.onResults(emptyList())
        assertEquals(listOf("keep this"), host.inserted)
    }

    @Test fun anEmptyFinalWithNothingHeardIsANoMatchAndTheDraftIsUntouched() {
        val c = controller().listening()
        engine.listener!!.onResults(emptyList())
        assertTrue(host.inserted.isEmpty())
        assertEquals(DictationProblem.NoMatch, c.notice.value!!.problem)
        assertEquals(DictationState.Idle, c.state.value)
    }

    @Test fun aNoMatchErrorAfterVisiblePartialsKeepsThemToo() {
        val c = controller().listening()
        engine.listener!!.onPartial("said something", null)
        engine.listener!!.onProblem(DictationProblem.NoMatch)
        assertEquals(listOf("said something"), host.inserted)
        assertNull(c.notice.value)
    }

    @Test fun aStopTheServiceNeverAnswersTakesTheLastPartialAfterTheDeadline() {
        val c = controller().listening()
        engine.listener!!.onPartial("almost", null)
        c.toggle()
        clock.advance(3_999)
        assertTrue(c.state.value is DictationState.Finishing)
        clock.advance(2)
        assertEquals(DictationState.Idle, c.state.value)
        assertEquals(listOf("almost"), host.inserted)
        assertTrue("the stuck take was cancelled", engine.cancels > 0)
    }

    @Test fun aStopTheServiceNeverAnswersWithNothingHeardIsATimeout() {
        val c = controller().listening()
        c.toggle()
        clock.advance(5_000)
        assertEquals(DictationState.Idle, c.state.value)
        assertTrue(host.inserted.isEmpty())
        assertEquals(DictationProblem.Timeout, c.notice.value!!.problem)
    }

    // ---- double tap ----

    @Test fun aSecondTapWhileStartingStartsNothingMore() {
        val c = controller()
        c.toggle()
        c.toggle()
        assertEquals(1, engine.starts.size)
        assertEquals(DictationState.Starting, c.state.value)
    }

    @Test fun aSecondTapWhileFinishingDoesNothing() {
        val c = controller().listening()
        c.toggle()
        c.toggle()
        assertEquals(1, engine.stops)
        assertTrue(c.state.value is DictationState.Finishing)
        assertEquals(1, engine.starts.size)
    }

    @Test fun aRecogniserThatNeverReportsReadyGivesUpAfterTheStartDeadline() {
        val c = controller()
        c.toggle()
        clock.advance(10_001)
        assertEquals(DictationState.Idle, c.state.value)
        assertEquals(DictationProblem.Service, c.notice.value!!.problem)
    }

    // ---- cancel ----

    @Test fun cancelDiscardsWhatWasHeardAndNeverTouchesTheDraft() {
        val c = controller().listening()
        engine.listener!!.onPartial("throw this away", null)
        c.cancel()
        assertEquals(DictationState.Idle, c.state.value)
        assertTrue(host.inserted.isEmpty())
        assertTrue(engine.cancels > 0)
        assertEquals(DictationSignal.Cancelled, host.signals.last())
        // What the abandoned take reports afterwards goes nowhere.
        engine.listener!!.onResults(listOf("throw this away"))
        assertTrue(host.inserted.isEmpty())
        assertEquals(DictationState.Idle, c.state.value)
    }

    @Test fun cancelWhileFinishingDiscardsToo() {
        val c = controller().listening()
        engine.listener!!.onPartial("nope", null)
        c.toggle()
        c.cancel()
        engine.listener!!.onResults(listOf("nope"))
        assertTrue(host.inserted.isEmpty())
        assertEquals(DictationState.Idle, c.state.value)
    }

    @Test fun cancelWhenIdleDoesNothing() {
        val c = controller()
        c.cancel()
        assertTrue(host.signals.isEmpty())
        assertEquals(0, engine.cancels)
    }

    @Test fun theNextTakeAfterACancelIsNotConfusedByTheOldOne() {
        val c = controller().listening()
        val old = engine.listener!!
        c.cancel()
        c.toggle()
        engine.listener!!.onReady()
        old.onPartial("ghost", null)
        old.onResults(listOf("ghost"))
        assertEquals(DictationState.Listening(0f, ""), c.state.value)
        assertTrue(host.inserted.isEmpty())
    }

    // ---- errors ----

    @Test fun anErrorKeepsTheDraftShowsItsMessageAndFeelsLikeAFailure() {
        val c = controller().listening()
        engine.listener!!.onProblem(DictationProblem.Network)
        assertEquals(DictationState.Idle, c.state.value)
        assertTrue(host.inserted.isEmpty())
        assertEquals(DictationProblem.Network, c.notice.value!!.problem)
        assertEquals(DictationSignal.Failed, host.signals.last())
    }

    @Test fun everyProblemEndsTheTakeWithoutInsertingAnything() {
        DictationProblem.entries.filter { it != DictationProblem.Language }.forEach { problem ->
            val e = FakeEngine()
            val h = FakeHost()
            val c = DictationController(e, h, FakeClock(), listOf(null))
            c.toggle()
            e.listener!!.onReady()
            e.listener!!.onProblem(problem)
            assertEquals(problem.name, DictationState.Idle, c.state.value)
            assertTrue(problem.name, h.inserted.isEmpty())
            assertEquals(problem, c.notice.value!!.problem)
        }
    }

    @Test fun aNoticeIsClearedByItsOwnSerialOnly() {
        val c = controller().listening()
        engine.listener!!.onProblem(DictationProblem.Busy)
        val first = c.notice.value!!
        c.toggle()
        engine.listener!!.onReady()
        engine.listener!!.onProblem(DictationProblem.Audio)
        val second = c.notice.value!!
        assertTrue(second.serial != first.serial)
        c.clearNotice(first.serial)
        assertEquals(second, c.notice.value)
        c.clearNotice(second.serial)
        assertNull(c.notice.value)
    }

    @Test fun startingATakeTakesTheOldMessageAway() {
        val c = controller().listening()
        engine.listener!!.onProblem(DictationProblem.Busy)
        c.toggle()
        assertNull(c.notice.value)
    }

    @Test fun anAudioTakeoverIsReportedAsAnInterruptionAndNothingIsInserted() {
        val c = controller().listening()
        engine.listener!!.onPartial("half a thought", null)
        engine.listener!!.onProblem(DictationProblem.Interrupted)
        assertEquals(DictationState.Idle, c.state.value)
        assertTrue(host.inserted.isEmpty())
        assertEquals(DictationProblem.Interrupted, c.notice.value!!.problem)
    }

    // ---- language fallback ----

    @Test fun aLanguageErrorRetriesTheSameTakeInTheNextLanguageWithoutAMessage() {
        val c = controller().listening()
        engine.listener!!.onProblem(DictationProblem.Language)
        assertEquals(listOf<String?>("th-TH", null), engine.starts)
        assertEquals(DictationState.Starting, c.state.value)
        assertNull(c.notice.value)
        engine.listener!!.onReady()
        engine.listener!!.onResults(listOf("สวัสดี"))
        assertEquals(listOf("สวัสดี"), host.inserted)
    }

    @Test fun aLanguageErrorWithNoLanguageLeftIsAnError() {
        val c = DictationController(engine, host, clock, listOf(null))
        c.toggle()
        engine.listener!!.onReady()
        engine.listener!!.onProblem(DictationProblem.Language)
        assertEquals(DictationState.Idle, c.state.value)
        assertEquals(DictationProblem.Language, c.notice.value!!.problem)
    }

    @Test fun theFallbackAttemptIsTriedOnceNotForever() {
        val c = controller()
        c.toggle()
        engine.listener!!.onProblem(DictationProblem.Language)
        engine.listener!!.onProblem(DictationProblem.Language)
        assertEquals(2, engine.starts.size)
        assertEquals(DictationProblem.Language, c.notice.value!!.problem)
    }

    // ---- permission ----

    @Test fun theFirstTapAsksForThePermissionAndStartsNothingYet() {
        host.allowed = false
        val c = controller()
        c.toggle()
        assertEquals(DictationState.AskingPermission, c.state.value)
        assertEquals(1, host.permissionRequests)
        assertTrue(engine.starts.isEmpty())
    }

    @Test fun grantingThePermissionStartsTheTake() {
        host.allowed = false
        val c = controller()
        c.toggle()
        host.allowed = true
        c.onPermissionResult(granted = true, blocked = false)
        assertEquals(DictationState.Starting, c.state.value)
        assertEquals(listOf<String?>("th-TH"), engine.starts)
    }

    @Test fun denyingThePermissionIsAPlainMessageWithNoSettingsLink() {
        host.allowed = false
        val c = controller()
        c.toggle()
        c.onPermissionResult(granted = false, blocked = false)
        assertEquals(DictationState.Idle, c.state.value)
        assertEquals(DictationProblem.PermissionDenied, c.notice.value!!.problem)
        assertFalse(c.notice.value!!.problem.offersSettings)
        assertTrue(engine.starts.isEmpty())
    }

    @Test fun aPermanentDenialOffersTheSystemSettings() {
        host.allowed = false
        val c = controller()
        c.toggle()
        c.onPermissionResult(granted = false, blocked = true)
        assertEquals(DictationProblem.PermissionBlocked, c.notice.value!!.problem)
        assertTrue(c.notice.value!!.problem.offersSettings)
    }

    @Test fun tappingAgainAfterADenialAsksAgainButNeverByItself() {
        host.allowed = false
        val c = controller()
        c.toggle()
        c.onPermissionResult(granted = false, blocked = false)
        assertEquals(1, host.permissionRequests)
        c.toggle()
        assertEquals(2, host.permissionRequests)
    }

    @Test fun theSystemDialogCoveringTheAppDoesNotForgetTheTap() {
        host.allowed = false
        val c = controller()
        c.toggle()
        c.interrupt(DictationInterruption.Background)
        c.interrupt(DictationInterruption.WindowCovered)
        assertEquals(DictationState.AskingPermission, c.state.value)
        host.allowed = true
        c.onPermissionResult(granted = true, blocked = false)
        assertEquals(1, engine.starts.size)
    }

    @Test fun aGrantAfterTheSessionChangedStartsNothing() {
        host.allowed = false
        val c = controller()
        c.toggle()
        c.interrupt(DictationInterruption.SessionChanged)
        c.onPermissionResult(granted = true, blocked = false)
        assertEquals(DictationState.Idle, c.state.value)
        assertTrue(engine.starts.isEmpty())
    }

    @Test fun aGrantWhileTheScreenIsNoLongerInFrontStartsNothing() {
        host.allowed = false
        val c = controller()
        c.toggle()
        host.listenable = false
        c.onPermissionResult(granted = true, blocked = false)
        assertEquals(DictationState.Idle, c.state.value)
        assertTrue(engine.starts.isEmpty())
        assertNull(c.notice.value)
    }

    @Test fun aStrayPermissionResultWhenNothingWasAskedIsIgnored() {
        val c = controller()
        c.onPermissionResult(granted = true, blocked = false)
        assertEquals(DictationState.Idle, c.state.value)
        assertTrue(engine.starts.isEmpty())
    }

    // ---- interruptions ----

    @Test fun goingToTheBackgroundEndsTheTakeAndDiscardsIt() {
        val c = controller().listening()
        engine.listener!!.onPartial("unfinished", null)
        c.interrupt(DictationInterruption.Background)
        assertEquals(DictationState.Idle, c.state.value)
        assertTrue(host.inserted.isEmpty())
        assertTrue(engine.cancels > 0)
        assertNull("leaving is not an error", c.notice.value)
    }

    @Test fun aWindowCoveringTheScreenEndsTheTake() {
        val c = controller().listening()
        c.interrupt(DictationInterruption.WindowCovered)
        assertEquals(DictationState.Idle, c.state.value)
        assertTrue(host.inserted.isEmpty())
    }

    @Test fun aSessionChangeEndsTheTakeSoNothingLandsInTheOtherDraft() {
        val c = controller().listening()
        engine.listener!!.onPartial("for the first session", null)
        c.interrupt(DictationInterruption.SessionChanged)
        engine.listener!!.onResults(listOf("for the first session"))
        assertTrue(host.inserted.isEmpty())
        assertEquals(DictationState.Idle, c.state.value)
    }

    @Test fun interruptingWhileFinishingDiscardsTheLateResult() {
        val c = controller().listening()
        engine.listener!!.onPartial("late", null)
        c.toggle()
        c.interrupt(DictationInterruption.Background)
        engine.listener!!.onResults(listOf("late"))
        assertTrue(host.inserted.isEmpty())
    }

    @Test fun interruptingWhenIdleDoesNothing() {
        val c = controller()
        c.interrupt(DictationInterruption.Background)
        assertTrue(host.signals.isEmpty())
        assertEquals(0, engine.cancels)
    }

    // ---- guards ----

    @Test fun nothingStartsFromTheBackgroundOrALockedScreen() {
        host.listenable = false
        val c = controller()
        c.toggle()
        assertEquals(DictationState.Idle, c.state.value)
        assertTrue(engine.starts.isEmpty())
        assertEquals(0, host.permissionRequests)
    }

    @Test fun withoutARecognitionServiceATapDoesNothing() {
        val none = FakeEngine(available = false)
        val c = controller(engine = none)
        c.toggle()
        assertEquals(DictationState.Idle, c.state.value)
        assertTrue(none.starts.isEmpty())
        assertEquals(0, host.permissionRequests)
    }

    // ---- dispose ----

    @Test fun disposeStopsTheTakeReleasesTheRecogniserAndSilencesLateCallbacks() {
        val c = controller().listening()
        engine.listener!!.onPartial("bye", null)
        c.dispose()
        assertEquals(DictationState.Idle, c.state.value)
        assertTrue(engine.released)
        engine.listener!!.onResults(listOf("bye"))
        assertTrue(host.inserted.isEmpty())
        c.toggle()
        assertEquals("a disposed controller starts nothing", 1, engine.starts.size)
    }

    @Test fun disposeCancelsPendingDeadlines() {
        val c = controller()
        c.toggle()
        c.dispose()
        clock.advance(60_000)
        assertNull(c.notice.value)
    }
}
