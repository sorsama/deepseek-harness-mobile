package com.labteto.dshmobile.termux

import android.content.Intent
import com.labteto.dshmobile.connection.HarnessSessionStore
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.core.wire.HarnessLaunchLine
import com.labteto.dshmobile.core.wire.SessionExchange
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How a start left the sign-in: the whole point of starting the harness from the app. */
enum class StartSignIn {
    /** The readiness line carried a token and the harness accepted it; the cookie is stored. */
    SIGNED_IN,

    /** The harness printed no token (older than 0.1.2); sign in with its startup line. */
    NO_TOKEN,

    /** The harness printed a token and then refused it — the line was stale or truncated. */
    REFUSED,

    /** The harness printed the line and then could not be reached for the exchange. */
    UNREACHABLE,
}

/** Why a Termux command did not do what was asked. */
sealed interface HarnessControlFailure {
    data object NotInstalled : HarnessControlFailure
    data object PermissionDenied : HarnessControlFailure
    data object ExternalAppsDisabled : HarnessControlFailure
    data object DshMissing : HarnessControlFailure
    data object NodeMissing : HarnessControlFailure

    /** The harness process ended before it was ready; [tail] is the end of its log. */
    data class Exited(val tail: String) : HarnessControlFailure

    /** The harness never printed its readiness line; [tail] is the end of its log. */
    data class ReadyTimeout(val tail: String) : HarnessControlFailure

    /** Termux did not run the command, or never answered; [detail] is whatever it said. */
    data class TermuxUnavailable(val detail: String?) : HarnessControlFailure

    /** The update could not be downloaded, verified or opened; [detail] names the step. */
    data class InstallFailed(val detail: String) : HarnessControlFailure
}

/** What the controller is doing, or last did. */
sealed interface HarnessControl {
    data object Idle : HarnessControl
    data class Starting(val port: Int) : HarnessControl
    data class Stopping(val port: Int) : HarnessControl
    data class Installing(val version: String) : HarnessControl

    /** A start finished; cleared by [TermuxHarnessController.acknowledge]. */
    data class Started(val port: Int, val signIn: StartSignIn) : HarnessControl

    /** A command failed; cleared by the next command or by [TermuxHarnessController.acknowledge]. */
    data class Failed(val reason: HarnessControlFailure) : HarnessControl

    val busy: Boolean get() = this is Starting || this is Stopping || this is Installing
}

/**
 * Starts, stops and updates through Termux, and signs the app in to a harness it started.
 *
 * App-scoped with its own supervisor, like the connection manager: a start takes up to a minute,
 * and the person who tapped it may well leave the connect screen for Settings in the meantime.
 * ViewModels observe [state]; none of them owns the command.
 *
 * The controller never connects. It stores the cookie and reports [HarnessControl.Started]; the
 * connect screen decides whether that means connecting, which keeps the auto-connect settings in
 * one place.
 */
@Singleton
class TermuxHarnessController @Inject constructor(
    private val bridge: TermuxBridge,
    private val hostsStore: HostsStore,
    private val sessions: HarnessSessionStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<HarnessControl>(HarnessControl.Idle)
    val state: StateFlow<HarnessControl> = _state.asStateFlow()

    val termuxInstalled: Boolean get() = bridge.isInstalled()

    fun hasPermission(): Boolean = bridge.hasPermission()

    fun launchTermuxIntent(): Intent? = bridge.launchIntent()

    /** Start the harness on [port]; a no-op while another command is running. */
    fun start(port: Int) {
        if (!begin(HarnessControl.Starting(port))) return
        scope.launch { _state.value = runStart(port) }
    }

    /** Stop the harness this app started; a no-op while another command is running. */
    fun stop(port: Int) {
        if (!begin(HarnessControl.Stopping(port))) return
        scope.launch { _state.value = runStop(port) }
    }

    /** Download [version] into Termux, verify it when a checksum file exists, and open the installer. */
    fun installUpdate(version: String, apkUrl: String, apkName: String, sha256Url: String?) {
        if (!begin(HarnessControl.Installing(version))) return
        scope.launch { _state.value = runInstall(apkUrl, apkName, sha256Url) }
    }

    /** Clear a finished [HarnessControl.Started] or [HarnessControl.Failed]; the screen has shown it. */
    fun acknowledge() {
        _state.update { if (it is HarnessControl.Started || it is HarnessControl.Failed) HarnessControl.Idle else it }
    }

    /**
     * A result whose command was issued by a process that no longer exists.
     *
     * The one that matters is a start: its stdout carries the launch token, and losing it means
     * asking the user to paste a line the app already had. Everything else is stale by the time
     * anyone could look at it.
     */
    internal fun onOrphanResult(kind: TermuxCommandKind?, port: Int?, result: TermuxResult) {
        if (kind != TermuxCommandKind.START || port == null || result !is TermuxResult.Ok) return
        scope.launch { signInFrom(result.stdout, port) }
    }

    private fun begin(next: HarnessControl): Boolean {
        var started = false
        _state.update { current ->
            if (current.busy) current else next.also { started = true }
        }
        return started
    }

    private suspend fun runStart(port: Int): HarnessControl {
        val command = runCatching { TermuxScripts.startCommand(port) }.getOrNull()
            ?: return HarnessControl.Failed(HarnessControlFailure.TermuxUnavailable("port $port"))
        return when (val result = bridge.run(command)) {
            is TermuxResult.Ok -> when (val marker = TermuxScripts.marker(result.stdout)) {
                ScriptMarker.Started, ScriptMarker.AlreadyRunning ->
                    HarnessControl.Started(port, signInFrom(result.stdout, port))
                ScriptMarker.MissingDsh -> HarnessControl.Failed(HarnessControlFailure.DshMissing)
                ScriptMarker.MissingNode -> HarnessControl.Failed(HarnessControlFailure.NodeMissing)
                is ScriptMarker.Exited -> HarnessControl.Failed(HarnessControlFailure.Exited(marker.tail))
                is ScriptMarker.ReadyTimeout -> HarnessControl.Failed(HarnessControlFailure.ReadyTimeout(marker.tail))
                else -> HarnessControl.Failed(HarnessControlFailure.TermuxUnavailable(result.stderr.ifBlank { result.stdout }))
            }
            else -> HarnessControl.Failed(failureOf(result))
        }
    }

    private suspend fun runStop(port: Int): HarnessControl =
        when (val result = bridge.run(TermuxScripts.stopCommand(port))) {
            is TermuxResult.Ok -> when (TermuxScripts.marker(result.stdout)) {
                ScriptMarker.Stopped, ScriptMarker.NotRunning -> HarnessControl.Idle
                else -> HarnessControl.Failed(HarnessControlFailure.TermuxUnavailable(result.stderr.ifBlank { result.stdout }))
            }
            else -> HarnessControl.Failed(failureOf(result))
        }

    private suspend fun runInstall(apkUrl: String, apkName: String, sha256Url: String?): HarnessControl {
        val command = runCatching { TermuxScripts.installCommand(apkUrl, apkName, sha256Url) }.getOrNull()
            ?: return HarnessControl.Failed(HarnessControlFailure.InstallFailed(apkUrl))
        return when (val result = bridge.run(command)) {
            is TermuxResult.Ok -> when (val marker = TermuxScripts.marker(result.stdout)) {
                ScriptMarker.Installing -> HarnessControl.Idle
                ScriptMarker.DownloadFailed -> HarnessControl.Failed(HarnessControlFailure.InstallFailed("download"))
                ScriptMarker.ChecksumFailed -> HarnessControl.Failed(HarnessControlFailure.InstallFailed("checksum"))
                ScriptMarker.OpenFailed -> HarnessControl.Failed(HarnessControlFailure.InstallFailed("termux-open"))
                else -> HarnessControl.Failed(
                    HarnessControlFailure.InstallFailed(marker?.toString() ?: result.stderr.ifBlank { "exit ${result.exitCode}" }),
                )
            }
            else -> HarnessControl.Failed(failureOf(result))
        }
    }

    /**
     * Exchange the token on the readiness line for a cookie, stored against the loopback record.
     *
     * The whole line goes to the exchange, which already accepts it; parsing here only decides
     * whether there is a token to exchange at all.
     */
    private suspend fun signInFrom(stdout: String, port: Int): StartSignIn {
        val line = HarnessLaunchLine.parse(stdout) ?: return StartSignIn.NO_TOKEN
        if (line.token == null) return StartSignIn.NO_TOKEN
        val host = hostsStore.ensureLoopbackHost(port)
        return when (sessions.pair(host.id, host.baseUrl, line.raw)) {
            is SessionExchange.Granted -> StartSignIn.SIGNED_IN
            is SessionExchange.Refused -> StartSignIn.REFUSED
            is SessionExchange.Unreachable -> StartSignIn.UNREACHABLE
        }
    }

    private fun failureOf(result: TermuxResult): HarnessControlFailure = when (result) {
        is TermuxResult.Ok -> HarnessControlFailure.TermuxUnavailable(result.stderr)
        TermuxResult.NotInstalled -> HarnessControlFailure.NotInstalled
        TermuxResult.PermissionDenied -> HarnessControlFailure.PermissionDenied
        TermuxResult.ExternalAppsDisabled -> HarnessControlFailure.ExternalAppsDisabled
        is TermuxResult.ServiceUnavailable -> HarnessControlFailure.TermuxUnavailable(result.detail)
        TermuxResult.Timeout -> HarnessControlFailure.TermuxUnavailable(null)
        is TermuxResult.Failed -> HarnessControlFailure.TermuxUnavailable(result.errmsg)
    }
}
