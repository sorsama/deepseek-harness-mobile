package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.termux.HarnessControl
import com.labteto.dshmobile.termux.StartSignIn
import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertFalse
import com.labteto.dshmobile.testing.assertNull
import com.labteto.dshmobile.testing.assertTrue
import com.labteto.dshmobile.ui.screens.connect.LoopbackStatus
import kotlin.test.Test

/**
 * How the built-in harness is shown: the Connect card's state and one action, the words and fix for
 * each failure, the set-up tracker's steps and progress, and the auto-start and Termux decisions.
 */
class RuntimePresentationTest {

    private val installed = InstalledRuntime("2026.11.14-2", 2, "0.2.2-rc.1", "24.22.3", "debian-r1", "dsh-0.2.2-rc.1")
    private val release = RuntimeReleaseInfo(
        "2026.11.14-2",
        "0.2.2-rc.1",
        listOf(
            ReleaseLayerPlan("base", downloadBytes = 72_000_000, unpackedBytes = 400_000_000, entries = 9_000),
            ReleaseLayerPlan("harness", downloadBytes = 84_000_000, unpackedBytes = 420_000_000, entries = 33_000),
        ),
    )
    private val down = LoopbackStatus.Down(ProbeOutcome.Refused)
    private val ready = LoopbackStatus.Ready(null)

    private fun card(runtime: RuntimeState, loopback: LoopbackStatus = down, connected: Boolean = false, f: (BuiltInHarnessState) -> BuiltInHarnessState = { it }) =
        builtInCard(f(BuiltInHarnessState(runtime, release = release)), loopback, connected, port = 3080)

    private fun running(signIn: StartSignIn = StartSignIn.SIGNED_IN, rolledBack: RolledBackUpdate? = null) =
        RuntimeState.Running(3080, "0.2.2-rc.1", 0, signIn, installed, rolledBack)

    // ---- The card -------------------------------------------------------------------------------

    @Test
    fun `there is no card where the built-in harness cannot run`() {
        assertNull(builtInCard(BuiltInHarnessState.Unsupported, down, false, 3080))
        assertNull(card(RuntimeState.NotSupported(RuntimeUnsupported.NoSupportedAbi(listOf("armeabi-v7a")))))
    }

    @Test
    fun `each state has its one obvious action`() {
        assertEquals(BuiltInCardPhase.NotSetUp, card(RuntimeState.NotInstalled)!!.phase)
        assertEquals(RuntimeAction.SetUp, card(RuntimeState.NotInstalled)!!.primary)
        assertEquals(RuntimeAction.ShowProgress, card(RuntimeState.Downloading(DownloadProgress("base", 0, 2, 10, 100)))!!.primary)
        assertEquals(RuntimeAction.Start, card(RuntimeState.Stopped(installed))!!.primary)
        assertEquals(RuntimeAction.Connect, card(running(), ready)!!.primary)
        assertEquals(RuntimeAction.Stop, card(running(), ready)!!.secondary)
        assertNull(card(running(), ready, connected = true)!!.primary)
        assertTrue(card(running(), ready, connected = true)!!.connected)
    }

    @Test
    fun `live states have no dead buttons`() {
        // A start can still be abandoned.
        val starting = card(RuntimeState.Starting(3080, installed))!!
        assertEquals(BuiltInCardPhase.Starting, starting.phase)
        assertNull(starting.primary)
        assertEquals(RuntimeAction.Stop, starting.secondary)
        val stopping = card(RuntimeState.Stopping(3080, installed))!!
        assertNull(stopping.primary)
        assertNull(stopping.secondary)
    }

    @Test
    fun `a set-up in flight stays one compact state from download to start`() {
        val settingUp: (BuiltInHarnessState) -> BuiltInHarnessState = { it.copy(settingUp = true) }
        for (runtime in listOf(
            RuntimeState.NotInstalled,
            RuntimeState.Downloading(DownloadProgress("base", 0, 2, 1, 2)),
            RuntimeState.Verifying("harness"),
            RuntimeState.Unpacking(UnpackProgress("base", 0, 2, 5, 10, 0)),
            RuntimeState.Stopped(installed),
            RuntimeState.Starting(3080, installed),
        )) {
            val c = card(runtime, f = settingUp)!!
            assertEquals("$runtime", BuiltInCardPhase.SettingUp, c.phase)
            assertEquals(RuntimeAction.ShowProgress, c.primary)
        }
        // A download that broke off with the network is waiting, not failed.
        val waiting = card(RuntimeState.Failed(RuntimeFailure.Network("reset"), null)) { it.copy(settingUp = true, waitingForNetwork = true) }!!
        assertEquals(BuiltInCardPhase.SettingUp, waiting.phase)
        assertTrue(waiting.progress!!.waitingForNetwork)
    }

    @Test
    fun `waiting for the network holds the bar where the download stopped`() {
        val last = DownloadProgress("harness", 1, 2, 42_000_000, 84_000_000)
        val waiting = card(RuntimeState.Failed(RuntimeFailure.Network("reset"), null)) {
            it.copy(settingUp = true, waitingForNetwork = true, lastDownload = last)
        }!!
        assertEquals((72f + 42f) / 156f, waiting.progress!!.fraction!!, 0.0001f)
        assertEquals(114_000_000L, waiting.progress!!.bytesDone)
    }

    @Test
    fun `a harness up but not signed in offers a restart and never a connect`() {
        val c = card(running(StartSignIn.REFUSED), LoopbackStatus.NeedsSignIn)!!
        assertEquals(RuntimeAction.Restart, c.primary)
        assertTrue(c.signedOut)
        // The app's cookie expired while it ran: the probe says so even though the start signed in.
        assertEquals(RuntimeAction.Restart, card(running(), LoopbackStatus.NeedsSignIn)!!.primary)
    }

    @Test
    fun `an update is a quiet flag on the card that leads to the control page`() {
        val update = AvailableRuntimeUpdate("2026.11.20-1", "0.2.3", 84_000_000, baseChanges = false, harnessChanges = true)
        val c = card(running(), ready) { it.copy(update = update) }!!
        assertTrue(c.updateAvailable)
        assertEquals(RuntimeAction.Connect, c.primary)
        val updating = card(RuntimeState.Updating(UpdatePhase.Downloading(DownloadProgress("harness", 0, 1, 42_000_000, 84_000_000)), installed), ready) { it.copy(update = update) }!!
        assertEquals(BuiltInCardPhase.Updating, updating.phase)
        assertEquals(0.5f, updating.progress!!.fraction!!, 0.001f)
        assertEquals(RuntimeAction.OpenControls, updating.secondary)
    }

    @Test
    fun `a release the person went back from is not flagged on the card`() {
        val update = AvailableRuntimeUpdate("2026.11.14-4", "0.2.2-rc.1", 84_000_000, baseChanges = false, harnessChanges = true, sequence = 4)
        val skipped = card(RuntimeState.Stopped(installed)) { it.copy(update = update, skippedRelease = "2026.11.14-4") }!!
        assertFalse(skipped.updateAvailable)
        val offered = card(RuntimeState.Stopped(installed)) { it.copy(update = update) }!!
        assertTrue(offered.updateAvailable)
    }

    @Test
    fun `an update that needs a newer app is still flagged and says which app`() {
        val state = BuiltInHarnessState(RuntimeState.Stopped(installed), releaseProblem = RuntimeFailure.AppTooOld("0.23.0"))
        assertEquals("0.23.0", state.updateNeedsApp)
        assertTrue(state.updateWorthShowing)
        assertTrue(builtInCard(state, down, false, 3080)!!.updateAvailable)
        // Before anything is installed it is the card's own refusal, not an update.
        assertNull(BuiltInHarnessState(RuntimeState.NotInstalled, releaseProblem = RuntimeFailure.AppTooOld("0.23.0")).updateNeedsApp)
        // An unreachable server is not news.
        assertFalse(BuiltInHarnessState(RuntimeState.Stopped(installed), releaseProblem = RuntimeFailure.Network(null)).updateWorthShowing)
    }

    // ---- The chat's notice -------------------------------------------------------------------------

    @Test
    fun `the chat explains a crash of the harness on this phone and offers a restart`() {
        val crashed = RuntimeState.Failed(RuntimeFailure.Crashed(137, "killed"), installed)
        assertEquals(ChatNoticeKind.Crashed, builtInChatNotice(crashed, onBuiltIn = true, connected = false)!!.kind)
        assertEquals(ChatNoticeKind.Stopped, builtInChatNotice(RuntimeState.Stopped(installed), onBuiltIn = true, connected = false)!!.kind)
        assertEquals(ChatNoticeKind.Restarting, builtInChatNotice(RuntimeState.Starting(3080, installed), onBuiltIn = true, connected = false)!!.kind)
        assertEquals(ChatNoticeKind.Updating, builtInChatNotice(RuntimeState.Updating(UpdatePhase.Switching, installed), onBuiltIn = true, connected = false)!!.kind)
        val failed = builtInChatNotice(RuntimeState.Failed(RuntimeFailure.ReadyTimeout("tail"), installed), onBuiltIn = true, connected = false)!!
        assertEquals(ChatNoticeKind.StartFailed, failed.kind)
        assertEquals(RuntimeIssueKind.ReadyTimeout, failed.issue!!.kind)
    }

    @Test
    fun `the chat says nothing while connected or when its harness is another one`() {
        val crashed = RuntimeState.Failed(RuntimeFailure.Crashed(137, ""), installed)
        assertNull(builtInChatNotice(crashed, onBuiltIn = true, connected = true))
        assertNull(builtInChatNotice(crashed, onBuiltIn = false, connected = false))
        // Up but not reached yet is the chat's own reconnecting strip.
        assertNull(builtInChatNotice(running(), onBuiltIn = true, connected = false))
        assertNull(builtInChatNotice(RuntimeState.Failed(RuntimeFailure.Network(null), null), onBuiltIn = true, connected = false))
    }

    @Test
    fun `an update of both layers counts its bytes over both`() {
        val update = AvailableRuntimeUpdate("2026.11.14-4", "0.2.2-rc.1", 157_000_000, baseChanges = true, harnessChanges = true, layerDownloadBytes = listOf(72_000_000, 85_000_000))
        val first = updateProgress(UpdatePhase.Downloading(DownloadProgress("base", 0, 2, 36_000_000, 72_000_000)), update, null)
        assertEquals(36_000_000L, first.bytesDone)
        assertEquals(157_000_000L, first.bytesTotal)
        val second = updateProgress(UpdatePhase.Downloading(DownloadProgress("harness", 1, 2, 10_000_000, 85_000_000)), update, null)
        assertEquals(82_000_000L, second.bytesDone)
        assertTrue(second.fraction!! > first.fraction!!)
    }

    @Test
    fun `a rollback is said on the running card`() {
        assertTrue(card(running(rolledBack = RolledBackUpdate("0.2.3", RuntimeFailure.ReadyTimeout(""))), ready)!!.rolledBack)
    }

    @Test
    fun `a release this app cannot install is said before anything is tapped`() {
        val c = card(RuntimeState.NotInstalled) { it.copy(releaseProblem = RuntimeFailure.AppTooOld("0.22.0")) }!!
        assertEquals(BuiltInCardPhase.Failed, c.phase)
        assertEquals(RuntimeAction.UpdateApp, c.primary)
        assertEquals("0.22.0", c.issue!!.minAppVersion)
        // Being offline while nothing is set up is not a failure to show yet.
        assertEquals(BuiltInCardPhase.NotSetUp, card(RuntimeState.NotInstalled) { it.copy(releaseProblem = RuntimeFailure.Network(null)) }!!.phase)
    }

    // ---- Failures --------------------------------------------------------------------------------

    @Test
    fun `every failure has its own sentence and the action that fixes it`() {
        fun issue(f: RuntimeFailure, installed: Boolean = false) = runtimeIssue(f, installed, 3080)
        assertEquals(RuntimeIssueKind.NoSpace, issue(RuntimeFailure.NoSpace(2_000, 1_000)).kind)
        assertEquals(RuntimeAction.FreeSpace, issue(RuntimeFailure.NoSpace(2_000, 1_000)).fix)
        assertEquals(RuntimeAction.RetryInstall, issue(RuntimeFailure.NoSpace(2_000, 1_000)).second)
        assertEquals(2_000L, issue(RuntimeFailure.NoSpace(2_000, 1_000)).neededBytes)
        assertEquals(RuntimeAction.RetryInstall, issue(RuntimeFailure.Network("x")).fix)
        assertEquals(RuntimeIssueKind.Untrusted, issue(RuntimeFailure.BadSignature).kind)
        assertEquals(RuntimeIssueKind.Untrusted, issue(RuntimeFailure.ChecksumMismatch("base.tar.zst")).kind)
        assertEquals(RuntimeIssueKind.Untrusted, issue(RuntimeFailure.BadArchive("..")).kind)
        assertEquals(RuntimeAction.UpdateApp, issue(RuntimeFailure.AppTooOld("0.22.0")).fix)
        assertNull(issue(RuntimeFailure.NoBuildForDevice("riscv64")).fix)
        assertEquals(RuntimeAction.RetryStart, issue(RuntimeFailure.ExitedBeforeReady(1, "boom"), installed = true).fix)
        assertEquals(RuntimeAction.ShowLog, issue(RuntimeFailure.ExitedBeforeReady(1, "boom"), installed = true).second)
        assertEquals("boom", issue(RuntimeFailure.ExitedBeforeReady(1, "boom"), installed = true).logTail)
        assertEquals(RuntimeIssueKind.ReadyTimeout, issue(RuntimeFailure.ReadyTimeout("t"), installed = true).kind)
        assertEquals(RuntimeIssueKind.Crashed, issue(RuntimeFailure.Crashed(137, "t"), installed = true).kind)
        assertEquals(RuntimeIssueKind.LaunchFailed, issue(RuntimeFailure.LaunchFailed("EACCES"), installed = true).kind)
        val port = issue(RuntimeFailure.PortInUse(3080), installed = true)
        assertEquals(RuntimeAction.UseOtherPort, port.fix)
        assertEquals(3081, port.nextPort)
    }

    @Test
    fun `an update that failed leaves the harness to start with the retry second`() {
        val update = runtimeIssue(RuntimeFailure.Network("reset"), installed = true, port = 3080)
        assertTrue(update.updateFailed)
        assertEquals(RuntimeAction.Start, update.fix)
        assertEquals(RuntimeAction.RetryInstall, update.second)
        val older = runtimeIssue(RuntimeFailure.OlderRelease(5, 4), installed = true, port = 3080)
        assertEquals(RuntimeAction.Start, older.fix)
        val space = runtimeIssue(RuntimeFailure.NoSpace(10, 1), installed = true, port = 3080)
        assertEquals(RuntimeAction.Start, space.fix)
        assertEquals(RuntimeAction.FreeSpace, space.second)
    }

    @Test
    fun `the port wraps round instead of leaving the valid range`() {
        assertEquals(3081, nextPort(3080))
        assertEquals(3080, nextPort(65535))
    }

    // ---- The step tracker ----------------------------------------------------------------------

    @Test
    fun `download progress runs once over both layers weighted by their sizes`() {
        val first = downloadProgress(DownloadProgress("base", 0, 2, 36_000_000, 72_000_000), release)
        assertEquals(36_000_000L, first.bytesDone)
        assertEquals(156_000_000L, first.bytesTotal)
        assertEquals(36f / 156f, first.fraction!!, 0.0001f)
        val second = downloadProgress(DownloadProgress("harness", 1, 2, 0, 84_000_000), release)
        assertEquals(72f / 156f, second.fraction!!, 0.0001f)
        // Without the release's sizes each layer is an equal half, and the bar still only moves on.
        assertEquals(0.75f, downloadProgress(DownloadProgress("harness", 1, 2, 50, 100), null).fraction!!, 0.0001f)
        // The install's first event is a zero-byte placeholder: nothing to measure yet.
        assertNull(downloadProgress(DownloadProgress("base", 0, 2, 0, 0), null).fraction)
    }

    @Test
    fun `unpack progress counts files over both layers`() {
        val p = unpackProgress(UnpackProgress("harness", 1, 2, 16_500, 33_000, 0), release)
        assertEquals(25_500, p.entriesDone)
        assertEquals(42_000, p.entriesTotal)
        assertEquals(25_500f / 42_000f, p.fraction!!, 0.0001f)
    }

    @Test
    fun `checking the first layer is a pause inside download and the last one is the check step`() {
        val last = DownloadProgress("base", 0, 2, 72_000_000, 72_000_000)
        val between = verifyProgress("base", release, last)
        assertEquals(SetupStep.Download, between.step)
        assertTrue(between.checking)
        assertEquals(72f / 156f, between.fraction!!, 0.0001f)
        assertEquals(SetupStep.Check, verifyProgress("harness", release, last).step)
    }

    @Test
    fun `steps before the active one are done and a failure marks its own step`() {
        val steps = setupSteps(SetupStep.Unpack).toMap()
        assertEquals(StepStatus.Done, steps[SetupStep.Download])
        assertEquals(StepStatus.Done, steps[SetupStep.Check])
        assertEquals(StepStatus.Active, steps[SetupStep.Unpack])
        assertEquals(StepStatus.Pending, steps[SetupStep.SignIn])
        val failed = setupSteps(null, failedAt = failedStep(RuntimeFailure.ReadyTimeout(""))).toMap()
        assertEquals(StepStatus.Done, failed[SetupStep.Unpack])
        assertEquals(StepStatus.Failed, failed[SetupStep.Start])
        assertEquals(StepStatus.Pending, failed[SetupStep.SignIn])
        assertTrue(setupSteps(null, finished = true).all { it.second == StepStatus.Done })
        assertEquals(SetupStep.Download, failedStep(RuntimeFailure.NoSpace(1, 0)))
        assertEquals(SetupStep.Check, failedStep(RuntimeFailure.ChecksumMismatch("f")))
    }

    @Test
    fun `the set-up page follows the engine from download to signed in`() {
        val s = BuiltInHarnessState(RuntimeState.NotInstalled, release = release, settingUp = true)
        assertEquals(SetupStep.Download, setupProgress(s.copy(runtime = RuntimeState.Downloading(DownloadProgress("base", 0, 2, 1, 72_000_000))))!!.step)
        assertEquals(SetupStep.Unpack, setupProgress(s.copy(runtime = RuntimeState.Unpacking(UnpackProgress("base", 0, 2, 1, 9_000, 0))))!!.step)
        assertEquals(SetupStep.Start, setupProgress(s.copy(runtime = RuntimeState.Stopped(installed)))!!.step)
        assertEquals(SetupStep.Start, setupProgress(s.copy(runtime = RuntimeState.Starting(3080, installed)))!!.step)
        assertEquals(SetupStep.SignIn, setupProgress(s.copy(runtime = running()))!!.step)
        // No set-up under way and nothing in flight: there is nothing to track.
        assertNull(setupProgress(BuiltInHarnessState(RuntimeState.Stopped(installed))))
    }

    // ---- Auto-start and Termux -----------------------------------------------------------------

    @Test
    fun `auto-start only starts an installed idle runtime when it is asked to and the port is quiet`() {
        val stopped = BuiltInHarnessState(RuntimeState.Stopped(installed))
        assertTrue(shouldAutoStartBuiltIn(true, stopped, down))
        assertFalse(shouldAutoStartBuiltIn(false, stopped, down))
        assertFalse(shouldAutoStartBuiltIn(true, stopped, ready))
        assertFalse(shouldAutoStartBuiltIn(true, BuiltInHarnessState(RuntimeState.NotInstalled), down))
        assertFalse(shouldAutoStartBuiltIn(true, BuiltInHarnessState(RuntimeState.Starting(3080, installed)), down))
        assertTrue(shouldAutoStartBuiltIn(true, BuiltInHarnessState(RuntimeState.Failed(RuntimeFailure.Crashed(1, ""), installed)), down))
        assertFalse(shouldAutoStartBuiltIn(true, BuiltInHarnessState(RuntimeState.Failed(RuntimeFailure.Network(null), null)), down))
        assertFalse(shouldAutoStartBuiltIn(true, BuiltInHarnessState.Unsupported, down))
    }

    @Test
    fun `Termux starts closed for someone new and open for someone who already uses it`() {
        val fresh = BuiltInHarnessState(RuntimeState.NotInstalled)
        assertFalse(termuxSectionStartsOpen(fresh, termuxInstalled = false, termuxPermission = false, termuxAutoStart = false, HarnessControl.Idle, down))
        assertFalse(termuxSectionStartsOpen(fresh, termuxInstalled = true, termuxPermission = false, termuxAutoStart = false, HarnessControl.Idle, down))
        assertTrue(termuxSectionStartsOpen(fresh, termuxInstalled = true, termuxPermission = true, termuxAutoStart = false, HarnessControl.Idle, down))
        assertTrue(termuxSectionStartsOpen(fresh, termuxInstalled = true, termuxPermission = false, termuxAutoStart = true, HarnessControl.Idle, down))
        // A harness answering on the port that is not the built-in one is a Termux harness.
        assertTrue(termuxSectionStartsOpen(fresh, termuxInstalled = false, termuxPermission = false, termuxAutoStart = false, HarnessControl.Idle, ready))
        assertFalse(termuxSectionStartsOpen(BuiltInHarnessState(running()), false, false, false, HarnessControl.Idle, ready))
        // Without a built-in harness the Termux card is the whole screen, as before.
        assertTrue(termuxSectionStartsOpen(BuiltInHarnessState.Unsupported, false, false, false, HarnessControl.Idle, down))
    }

    // ---- Download speed ------------------------------------------------------------------------

    @Test
    fun `speed and time left come from the last few seconds`() {
        val rate = TransferRate()
        rate.add(0, 0)
        assertNull(rate.bytesPerSecond())
        rate.add(500, 1_000_000)
        assertNull("under a second is too short to say", rate.bytesPerSecond())
        rate.add(2_000, 4_000_000)
        assertEquals(2_000_000L, rate.bytesPerSecond())
        assertEquals(3L, rate.secondsLeft(10_000_000))
        // Old samples fall out of the window: a stall shows as a stall.
        rate.add(10_000, 4_000_000)
        rate.add(12_000, 4_000_000)
        assertEquals(0L, rate.bytesPerSecond())
        assertNull(rate.secondsLeft(10_000_000))
    }

    @Test
    fun `a resumed download starts a fresh measurement`() {
        val rate = TransferRate()
        rate.add(0, 50_000_000)
        rate.add(1_000, 51_000_000)
        assertEquals(1_000_000L, rate.bytesPerSecond())
        rate.add(2_000, 0)
        assertNull(rate.bytesPerSecond())
    }
}
