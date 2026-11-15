package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.termux.HarnessControl
import com.labteto.dshmobile.termux.StartSignIn
import com.labteto.dshmobile.ui.screens.connect.LoopbackStatus

// How the built-in harness is shown: which card state, which one action, which words for a failure,
// how far a set-up has got. Plain functions over the state types, so the Connect screen, the set-up
// page and the control page agree, and the decisions are unit tested in commonTest.

/** Everything a button on the built-in harness's screens can do. */
enum class RuntimeAction {
    /** Open the set-up page from the start. */
    SetUp,

    /** Open the set-up page on the step it is at. */
    ShowProgress,
    Start,
    Stop,
    Connect,

    /** Stop and start the harness: a new launch token, so the app signs itself in again. */
    Restart,

    /** Fetch the release again; a broken-off download resumes. */
    RetryInstall,

    /** Start again after a start that failed. */
    RetryStart,

    /** Open the system's storage screen. */
    FreeSpace,

    /** Open the place this app is updated from. */
    UpdateApp,

    /** Move the harness to the next port and start it there. */
    UseOtherPort,

    /** Open the control page in Settings. */
    OpenControls,

    /** Open the control page at the harness log. */
    ShowLog,
}

/** Which kind of trouble, so each gets its own sentence. */
enum class RuntimeIssueKind {
    NoSpace,
    Network,

    /** The manifest or a file did not match what was signed; nothing untrusted was kept. */
    Untrusted,
    AppTooOld,

    /** The offered release is older than the installed one; nothing changed. */
    OlderRelease,
    NoBuildForDevice,
    Storage,
    LaunchFailed,
    ExitedBeforeReady,
    ReadyTimeout,
    Crashed,
    PortInUse,
}

/**
 * A failure, as the person reads it: what happened and the one thing that fixes it.
 *
 * @property fix the main action; null when nothing in the app can fix it (a release without a build
 *   for this device).
 * @property second a quieter action beside it (the log after a failed start, a retry after freeing
 *   space), or null.
 * @property updateFailed the failure ended an update or an install over a runtime that is still
 *   there: the message says the harness on the phone is unchanged.
 */
data class RuntimeIssue(
    val kind: RuntimeIssueKind,
    val fix: RuntimeAction?,
    val second: RuntimeAction? = null,
    val updateFailed: Boolean = false,
    val neededBytes: Long = 0,
    val availableBytes: Long = 0,
    val port: Int = 0,
    val nextPort: Int = 0,
    val minAppVersion: String = "",
    /** The end of the harness output for a start that failed: shown in a code block, never translated. */
    val logTail: String = "",
)

/** The next port to try when [port] is taken. */
fun nextPort(port: Int): Int = if (port >= 65535) 3080 else port + 1

/**
 * [failure] as an issue. [installed] is whether a runtime is still on the device, [port] the
 * loopback port the card uses.
 */
fun runtimeIssue(failure: RuntimeFailure, installed: Boolean, port: Int): RuntimeIssue {
    // An install-side failure over an installed runtime was an update: the harness still works,
    // so starting it is the obvious thing, and trying the update again comes second.
    fun installSide(kind: RuntimeIssueKind, fix: RuntimeAction? = RuntimeAction.RetryInstall, second: RuntimeAction? = null) =
        if (installed) {
            RuntimeIssue(kind, fix = RuntimeAction.Start, second = fix?.takeIf { it != RuntimeAction.Start }, updateFailed = true)
        } else {
            RuntimeIssue(kind, fix = fix, second = second)
        }
    return when (failure) {
        is RuntimeFailure.NoSpace -> installSide(RuntimeIssueKind.NoSpace, RuntimeAction.FreeSpace, RuntimeAction.RetryInstall)
            .copy(neededBytes = failure.neededBytes, availableBytes = failure.availableBytes)
        is RuntimeFailure.Network -> installSide(RuntimeIssueKind.Network)
        RuntimeFailure.BadSignature,
        is RuntimeFailure.ChecksumMismatch,
        is RuntimeFailure.BadManifest,
        is RuntimeFailure.BadArchive,
        -> installSide(RuntimeIssueKind.Untrusted)
        is RuntimeFailure.AppTooOld -> installSide(RuntimeIssueKind.AppTooOld, RuntimeAction.UpdateApp)
            .copy(minAppVersion = failure.minAppVersion)
        is RuntimeFailure.OlderRelease -> RuntimeIssue(
            RuntimeIssueKind.OlderRelease,
            fix = if (installed) RuntimeAction.Start else RuntimeAction.RetryInstall,
            updateFailed = installed,
        )
        is RuntimeFailure.NoBuildForDevice -> installSide(RuntimeIssueKind.NoBuildForDevice, fix = null)
        is RuntimeFailure.Storage -> if (installed) {
            RuntimeIssue(RuntimeIssueKind.Storage, fix = RuntimeAction.RetryStart, second = RuntimeAction.ShowLog)
        } else {
            RuntimeIssue(RuntimeIssueKind.Storage, fix = RuntimeAction.RetryInstall)
        }
        is RuntimeFailure.LaunchFailed -> RuntimeIssue(RuntimeIssueKind.LaunchFailed, RuntimeAction.RetryStart, RuntimeAction.ShowLog, logTail = failure.detail.orEmpty())
        is RuntimeFailure.ExitedBeforeReady -> RuntimeIssue(RuntimeIssueKind.ExitedBeforeReady, RuntimeAction.RetryStart, RuntimeAction.ShowLog, logTail = failure.logTail)
        is RuntimeFailure.ReadyTimeout -> RuntimeIssue(RuntimeIssueKind.ReadyTimeout, RuntimeAction.RetryStart, RuntimeAction.ShowLog, logTail = failure.logTail)
        is RuntimeFailure.Crashed -> RuntimeIssue(RuntimeIssueKind.Crashed, RuntimeAction.RetryStart, RuntimeAction.ShowLog, logTail = failure.logTail)
        is RuntimeFailure.PortInUse -> RuntimeIssue(
            RuntimeIssueKind.PortInUse,
            fix = RuntimeAction.UseOtherPort,
            port = failure.port,
            nextPort = nextPort(failure.port),
        )
    }
}

// ---- Set-up progress -------------------------------------------------------------------------

/** The set-up page's steps, in order. */
enum class SetupStep { Download, Check, Unpack, Start, SignIn }

enum class StepStatus { Pending, Active, Done, Failed }

/**
 * Where a set-up (or an update) is, with the numbers for the active step.
 *
 * [fraction] is over the whole step, every layer included, so a bar never runs to the end on the
 * first layer and starts again; null while there is nothing to measure yet.
 */
data class SetupProgress(
    val step: SetupStep,
    val fraction: Float?,
    /** Download: bytes over every layer this set-up fetches. */
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    /** Unpack: files over every layer. */
    val entriesDone: Int = 0,
    val entriesTotal: Int = 0,
    /** The layer being worked on (`base` or `harness`), when the step has one. */
    val layer: String? = null,
    /** A layer is being checked inside the download step (every layer but the last). */
    val checking: Boolean = false,
    val waitingForNetwork: Boolean = false,
)

/** Download progress over every layer, weighted by the release's sizes when they are known. */
fun downloadProgress(p: DownloadProgress, release: RuntimeReleaseInfo?): SetupProgress {
    val plan = release?.layers?.takeIf { it.size == p.layerCount }
    val total = plan?.sumOf { it.downloadBytes } ?: 0L
    return if (plan != null && total > 0) {
        val before = plan.take(p.layerIndex).sumOf { it.downloadBytes }
        val done = (before + p.bytesDone).coerceIn(0, total)
        SetupProgress(SetupStep.Download, done.toFloat() / total, bytesDone = done, bytesTotal = total, layer = p.layer)
    } else if (p.bytesTotal > 0) {
        // No plan: each layer is an equal share, so the bar still only moves forward.
        val share = 1f / p.layerCount.coerceAtLeast(1)
        val fraction = (p.layerIndex * share + share * p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f)
        SetupProgress(SetupStep.Download, fraction, bytesDone = p.bytesDone, bytesTotal = p.bytesTotal, layer = p.layer)
    } else {
        // The first event of an install is a zero-byte placeholder while the manifest is read.
        SetupProgress(SetupStep.Download, null, layer = p.layer)
    }
}

/** Unpacking progress over every layer, weighted by file counts. */
fun unpackProgress(p: UnpackProgress, release: RuntimeReleaseInfo?): SetupProgress {
    val plan = release?.layers?.takeIf { it.size == p.layerCount }
    val total = plan?.sumOf { it.entries } ?: 0
    return if (plan != null && total > 0) {
        val before = plan.take(p.layerIndex).sumOf { it.entries }
        val done = (before + p.entriesDone).coerceIn(0, total)
        SetupProgress(SetupStep.Unpack, done.toFloat() / total, entriesDone = done, entriesTotal = total, layer = p.layer)
    } else {
        val share = 1f / p.layerCount.coerceAtLeast(1)
        val within = if (p.entriesTotal > 0) p.entriesDone.toFloat() / p.entriesTotal else 0f
        SetupProgress(
            SetupStep.Unpack,
            (p.layerIndex * share + share * within).coerceIn(0f, 1f),
            entriesDone = p.entriesDone,
            entriesTotal = p.entriesTotal,
            layer = p.layer,
        )
    }
}

/**
 * Checking a downloaded layer. Every layer is checked right after its own download, so only the
 * last one is the Check step; checking an earlier one is a pause inside Download. Otherwise the
 * tracker would step forward to Check and back to Download for the second layer.
 */
fun verifyProgress(layer: String, release: RuntimeReleaseInfo?, last: DownloadProgress?): SetupProgress {
    val lastLayer = release?.layers?.lastOrNull()?.name ?: LAYER_HARNESS_NAME
    return if (layer == lastLayer) {
        SetupProgress(SetupStep.Check, null, layer = layer)
    } else {
        val download = last?.let { downloadProgress(it.copy(bytesDone = it.bytesTotal), release) }
        (download ?: SetupProgress(SetupStep.Download, null)).copy(layer = layer, checking = true)
    }
}

private const val LAYER_HARNESS_NAME = "harness"

/**
 * Where the set-up page is for [state], or null when no set-up is under way and none just ended.
 * [lastDownload] is the last download event seen, for a check between two downloads.
 */
fun setupProgress(state: BuiltInHarnessState, lastDownload: DownloadProgress? = state.lastDownload): SetupProgress? {
    val release = state.release
    return when (val r = state.runtime) {
        is RuntimeState.Downloading -> downloadProgress(r.progress, release)
        is RuntimeState.Verifying -> verifyProgress(r.layer, release, lastDownload)
        is RuntimeState.Unpacking -> unpackProgress(r.progress, release)
        is RuntimeState.Stopped -> if (state.settingUp) SetupProgress(SetupStep.Start, null) else null
        is RuntimeState.Starting -> SetupProgress(SetupStep.Start, null)
        is RuntimeState.Running -> SetupProgress(SetupStep.SignIn, 1f)
        is RuntimeState.Failed, RuntimeState.NotInstalled -> if (state.settingUp && state.waitingForNetwork) {
            val download = lastDownload?.let { downloadProgress(it, release) } ?: SetupProgress(SetupStep.Download, null)
            download.copy(waitingForNetwork = true)
        } else {
            null
        }
        is RuntimeState.Updating -> updateProgress(r.phase, state.update, lastDownload)
        else -> null
    }
}

/** An update's progress, as the control page shows it, in the same terms as a set-up. */
fun updateProgress(phase: UpdatePhase, update: AvailableRuntimeUpdate?, lastDownload: DownloadProgress?): SetupProgress = when (phase) {
    UpdatePhase.Checking -> SetupProgress(SetupStep.Download, null)
    is UpdatePhase.Downloading -> {
        val p = phase.progress
        val sizes = update?.layerDownloadBytes?.takeIf { it.size == p.layerCount && it.sum() > 0 }
        val total = update?.downloadBytes?.takeIf { it > 0 }
        when {
            // Bytes over every layer the update fetches, so the line reads "90 MB of 157 MB" and
            // does not start again at the second layer.
            sizes != null -> {
                val all = sizes.sum()
                val done = (sizes.take(p.layerIndex).sum() + p.bytesDone).coerceIn(0, all)
                SetupProgress(SetupStep.Download, done.toFloat() / all, done, all, layer = p.layer)
            }
            total != null && p.layerCount == 1 ->
                SetupProgress(SetupStep.Download, (p.bytesDone.toFloat() / total).coerceIn(0f, 1f), p.bytesDone, total, layer = p.layer)
            else -> downloadProgress(p, null)
        }
    }
    is UpdatePhase.Verifying -> SetupProgress(SetupStep.Check, null, layer = phase.layer)
    is UpdatePhase.Unpacking -> unpackProgress(phase.progress, null)
    UpdatePhase.Switching -> SetupProgress(SetupStep.Start, null)
}

/**
 * The status of every step for a set-up at [current]. [failedAt] marks the step a failure ended,
 * [finished] that the harness is running and signed in.
 */
fun setupSteps(current: SetupStep?, failedAt: SetupStep? = null, finished: Boolean = false): List<Pair<SetupStep, StepStatus>> =
    SetupStep.entries.map { step ->
        step to when {
            finished -> StepStatus.Done
            failedAt != null && step == failedAt -> StepStatus.Failed
            failedAt != null -> if (step.ordinal < failedAt.ordinal) StepStatus.Done else StepStatus.Pending
            current == null -> StepStatus.Pending
            step.ordinal < current.ordinal -> StepStatus.Done
            step == current -> StepStatus.Active
            else -> StepStatus.Pending
        }
    }

/** The step a failure belongs to, for the tracker. */
fun failedStep(failure: RuntimeFailure): SetupStep = when (failure) {
    is RuntimeFailure.Network, is RuntimeFailure.NoSpace, is RuntimeFailure.AppTooOld,
    is RuntimeFailure.NoBuildForDevice, is RuntimeFailure.OlderRelease, RuntimeFailure.BadSignature,
    is RuntimeFailure.BadManifest,
    -> SetupStep.Download
    is RuntimeFailure.ChecksumMismatch -> SetupStep.Check
    is RuntimeFailure.BadArchive, is RuntimeFailure.Storage -> SetupStep.Unpack
    is RuntimeFailure.LaunchFailed, is RuntimeFailure.ExitedBeforeReady, is RuntimeFailure.ReadyTimeout,
    is RuntimeFailure.Crashed, is RuntimeFailure.PortInUse,
    -> SetupStep.Start
}

// ---- The Connect screen's card -----------------------------------------------------------------

enum class BuiltInCardPhase { NotSetUp, SettingUp, Stopped, Starting, Stopping, Running, Updating, Failed }

/**
 * The built-in harness card: one state, one obvious action.
 *
 * @property primary the action the card (and the screen's pinned button) offers; null while the
 *   state is live and there is nothing to press (stopping).
 * @property secondary a quieter second action, or null.
 */
data class BuiltInCard(
    val phase: BuiltInCardPhase,
    val primary: RuntimeAction?,
    val secondary: RuntimeAction? = null,
    val progress: SetupProgress? = null,
    val issue: RuntimeIssue? = null,
    val updateAvailable: Boolean = false,
    val connected: Boolean = false,
    /** The harness is up but its sign-in did not take: a restart signs the app in again. */
    val signedOut: Boolean = false,
    /** The last update's harness never started and the previous one was put back. */
    val rolledBack: Boolean = false,
)

/**
 * The card for [state], or null when this device cannot run the built-in harness at all.
 *
 * [loopback] is what the Connect screen's probe of the port found and [connected] whether the app
 * is connected to it: the card never connects by itself, it offers Connect and the screen's own
 * auto-connect rules decide the rest.
 */
fun builtInCard(state: BuiltInHarnessState, loopback: LoopbackStatus, connected: Boolean, port: Int): BuiltInCard? {
    if (!state.supported) return null
    val update = state.updateWorthShowing
    return when (val r = state.runtime) {
        is RuntimeState.NotSupported -> null
        RuntimeState.NotInstalled -> when {
            state.settingUp -> BuiltInCard(BuiltInCardPhase.SettingUp, RuntimeAction.ShowProgress, progress = setupProgress(state))
            state.releaseProblem != null && state.releaseProblem !is RuntimeFailure.Network -> {
                val issue = runtimeIssue(state.releaseProblem, installed = false, port = port)
                BuiltInCard(BuiltInCardPhase.Failed, issue.fix, issue.second, issue = issue)
            }
            else -> BuiltInCard(BuiltInCardPhase.NotSetUp, RuntimeAction.SetUp)
        }
        is RuntimeState.Downloading, is RuntimeState.Verifying, is RuntimeState.Unpacking ->
            BuiltInCard(BuiltInCardPhase.SettingUp, RuntimeAction.ShowProgress, progress = setupProgress(state))
        is RuntimeState.Stopped -> if (state.settingUp) {
            BuiltInCard(BuiltInCardPhase.SettingUp, RuntimeAction.ShowProgress, progress = setupProgress(state))
        } else {
            BuiltInCard(BuiltInCardPhase.Stopped, RuntimeAction.Start, updateAvailable = update)
        }
        is RuntimeState.Starting -> if (state.settingUp) {
            BuiltInCard(BuiltInCardPhase.SettingUp, RuntimeAction.ShowProgress, progress = setupProgress(state))
        } else {
            // A start can be abandoned, so the live state still has a button that does something.
            BuiltInCard(BuiltInCardPhase.Starting, primary = null, secondary = RuntimeAction.Stop)
        }
        is RuntimeState.Stopping -> BuiltInCard(BuiltInCardPhase.Stopping, primary = null)
        is RuntimeState.Running -> {
            val signedOut = r.signIn != StartSignIn.SIGNED_IN && loopback !is LoopbackStatus.Ready ||
                loopback is LoopbackStatus.NeedsSignIn
            BuiltInCard(
                phase = BuiltInCardPhase.Running,
                primary = when {
                    connected -> null
                    signedOut -> RuntimeAction.Restart
                    else -> RuntimeAction.Connect
                },
                secondary = RuntimeAction.Stop,
                updateAvailable = update,
                connected = connected,
                signedOut = signedOut && !connected,
                rolledBack = r.rolledBack != null,
            )
        }
        is RuntimeState.Updating -> BuiltInCard(
            BuiltInCardPhase.Updating,
            primary = if (loopback is LoopbackStatus.Ready && !connected) RuntimeAction.Connect else RuntimeAction.OpenControls,
            secondary = if (loopback is LoopbackStatus.Ready && !connected) RuntimeAction.OpenControls else null,
            progress = updateProgress(r.phase, state.update, null),
            connected = connected,
        )
        is RuntimeState.Failed -> if (state.settingUp && state.waitingForNetwork) {
            BuiltInCard(BuiltInCardPhase.SettingUp, RuntimeAction.ShowProgress, progress = setupProgress(state))
        } else {
            val issue = runtimeIssue(r.reason, installed = r.installed != null, port = port)
            BuiltInCard(BuiltInCardPhase.Failed, issue.fix, issue.second, issue = issue, updateAvailable = update)
        }
    }
}

// ---- The chat's notice ---------------------------------------------------------------------------

/** What the chat says about the harness on this phone when it is not up. */
enum class ChatNoticeKind {
    /** It ended on its own (a crash, or the system stopped it). Restart and the log. */
    Crashed,

    /** It is installed and not running. Restart (a start) and the log. */
    Stopped,

    /** A start or a restart is on its way; nothing to press. */
    Restarting,

    /** An update is being installed; the chat comes back when it is done. Nothing to press. */
    Updating,

    /** A start failed: its own sentence ([ChatNotice.issue]), Restart and the log. */
    StartFailed,
}

data class ChatNotice(val kind: ChatNoticeKind, val issue: RuntimeIssue? = null)

/**
 * The chat's notice for the built-in harness, or null when there is nothing to say.
 *
 * [onBuiltIn] is whether the chat's connection is to this phone's built-in harness (and that
 * harness has served it in this app process), [connected] whether that connection is up. Up means
 * no notice; a harness that runs but is not reached yet is the chat's own "Reconnecting" strip.
 */
fun builtInChatNotice(runtime: RuntimeState, onBuiltIn: Boolean, connected: Boolean): ChatNotice? {
    if (!onBuiltIn || connected) return null
    return when (runtime) {
        is RuntimeState.Failed -> when {
            runtime.installed == null -> null
            runtime.reason is RuntimeFailure.Crashed -> ChatNotice(ChatNoticeKind.Crashed)
            else -> ChatNotice(ChatNoticeKind.StartFailed, runtimeIssue(runtime.reason, installed = true, port = 0))
        }
        is RuntimeState.Stopped -> ChatNotice(ChatNoticeKind.Stopped)
        is RuntimeState.Starting, is RuntimeState.Stopping -> ChatNotice(ChatNoticeKind.Restarting)
        is RuntimeState.Updating -> ChatNotice(ChatNoticeKind.Updating)
        else -> null
    }
}

// ---- Auto-start and the Termux section ---------------------------------------------------------

/**
 * Whether opening the app should start the built-in harness: the person asked for it, the runtime
 * is installed and idle, and nothing answers on the port yet. The same rule as Termux's auto-start;
 * connecting afterwards is left to the screen's usual "connect when ready".
 */
fun shouldAutoStartBuiltIn(autoStart: Boolean, state: BuiltInHarnessState, loopback: LoopbackStatus): Boolean {
    if (!autoStart || loopback !is LoopbackStatus.Down) return false
    return when (val r = state.runtime) {
        is RuntimeState.Stopped -> !state.settingUp
        // A failed start or a crash leaves an installed runtime; an install failure leaves nothing to start.
        is RuntimeState.Failed -> r.installed != null && !state.settingUp
        else -> false
    }
}

/**
 * Whether the "Use Termux instead" section starts open.
 *
 * Closed for someone new to "This phone" when the built-in harness can run here. Open for anyone who
 * already uses Termux from this app (it has the Termux permission, Termux auto-start is on, or a
 * Termux command is in flight), and open when a harness that is not the built-in one already answers
 * on the port, which on this phone is a harness in Termux. Nobody loses what they had.
 */
fun termuxSectionStartsOpen(
    builtIn: BuiltInHarnessState,
    termuxInstalled: Boolean,
    termuxPermission: Boolean,
    termuxAutoStart: Boolean,
    control: HarnessControl,
    loopback: LoopbackStatus,
): Boolean {
    if (!builtIn.supported) return true
    if (termuxInstalled && (termuxPermission || termuxAutoStart || control !is HarnessControl.Idle)) return true
    val builtInUp = builtIn.runtime is RuntimeState.Running || builtIn.runtime is RuntimeState.Starting ||
        builtIn.runtime is RuntimeState.Stopping || builtIn.runtime is RuntimeState.Updating
    return !builtInUp && (loopback is LoopbackStatus.Ready || loopback is LoopbackStatus.NeedsSignIn)
}

// ---- Download speed ---------------------------------------------------------------------------

/**
 * Download speed and time left, from samples of (milliseconds, bytes done).
 *
 * Measured over the last [WINDOW_MS] so a stall shows as a stall within seconds, and a resumed
 * download (whose first sample already holds what an earlier run fetched) is not counted as fetched
 * in no time.
 */
class TransferRate {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun add(atMs: Long, bytesDone: Long) {
        if (samples.isNotEmpty() && bytesDone < samples.last().second) samples.clear()
        samples.addLast(atMs to bytesDone)
        while (samples.size > 2 && atMs - samples.first().first > WINDOW_MS) samples.removeFirst()
    }

    /** Bytes per second, or null until there are two samples a second apart. */
    fun bytesPerSecond(): Long? {
        if (samples.size < 2) return null
        val (t0, b0) = samples.first()
        val (t1, b1) = samples.last()
        val dt = t1 - t0
        if (dt < 1000) return null
        return ((b1 - b0) * 1000 / dt).coerceAtLeast(0)
    }

    /** Seconds left for [bytesTotal], or null when the speed is not known or is zero. */
    fun secondsLeft(bytesTotal: Long): Long? {
        val rate = bytesPerSecond()?.takeIf { it > 0 } ?: return null
        val done = samples.lastOrNull()?.second ?: return null
        return ((bytesTotal - done).coerceAtLeast(0) + rate - 1) / rate
    }

    fun clear() = samples.clear()

    companion object {
        const val WINDOW_MS = 6_000L
    }
}
