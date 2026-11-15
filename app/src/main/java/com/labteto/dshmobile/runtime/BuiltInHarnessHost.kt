package com.labteto.dshmobile.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.labteto.dshmobile.ui.screens.connect.BuiltInHarness
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The built-in harness as the screens use it: [HarnessRuntimeController] plus the set-up flow.
 *
 * The controller knows install, start and stop as separate operations. A person who taps Set up
 * wants all of it, so this class carries a set-up through: install, then start on the port the
 * Connect screen uses. It lives as long as the app process (like the controller), so leaving the
 * set-up page or the app does not stop anything, and coming back shows the step it is on.
 *
 * Two things the engine leaves to its caller are done here:
 *  - **A download that broke off with the network** is resumed when the network comes back (the
 *    engine resumes with HTTP Range), shown as waiting rather than as a failure. With a network up
 *    and the server still failing, it tries three more times, then shows the failure.
 *  - **Cancel** leaves nothing behind: the partial downloads of a first install are deleted.
 *
 * It also reads the latest release's manifest before anything is downloaded, for the sizes the
 * Connect card and the set-up page show and for a refusal (the app is too old for the release)
 * that should be said before the person taps anything.
 */
@Singleton
class BuiltInHarnessHost @Inject constructor(
    @ApplicationContext private val context: Context,
    val controller: HarnessRuntimeController,
    private val updateChecker: com.labteto.dshmobile.update.UpdateChecker,
) : BuiltInHarness {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * "Update the app", for a harness release that needs a newer app. The app's own update check
     * runs first: when it finds a newer version, the in-app update dialog offers the download and
     * install. Only when it finds nothing (no release yet, or GitHub unreachable) does the releases
     * page open in the browser.
     */
    fun updateApp(onNothingFound: () -> Unit) {
        scope.launch {
            val outcome = runCatching { updateChecker.checkNow(com.labteto.dshmobile.BuildConfig.VERSION_NAME) }.getOrNull()
            if (outcome !is com.labteto.dshmobile.update.UpdateChecker.CheckOutcome.Available) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onNothingFound() }
            }
        }
    }

    private data class SetupFlow(
        val release: RuntimeReleaseInfo? = null,
        val releaseProblem: RuntimeFailure? = null,
        val settingUp: Boolean = false,
        val waitingForNetwork: Boolean = false,
        val port: Int = 3080,
    )

    private val flow = MutableStateFlow(SetupFlow())

    /** The last download event, for the set-up page's check step and the waiting state. */
    private val _lastDownload = MutableStateFlow<DownloadProgress?>(null)
    val lastDownload: StateFlow<DownloadProgress?> = _lastDownload

    /** How the last update or go-back ended, until [dismissOutcome] or the next one. */
    private val _outcome = MutableStateFlow<UpdateOutcome?>(null)

    private val updatePrefs get() = controller.updatePreferences

    private data class Extras(val last: DownloadProgress?, val skipped: ReleaseRef?, val outcome: UpdateOutcome?)

    private val extras = combine(_lastDownload, updatePrefs.skipped, _outcome, ::Extras)

    override val state: StateFlow<BuiltInHarnessState> = combine(controller.state, controller.availableUpdate, flow, extras) { runtime, update, f, x ->
        compose(runtime, update, f, x)
    }.stateIn(scope, SharingStarted.Eagerly, compose(controller.state.value, controller.availableUpdate.value, flow.value, Extras(null, updatePrefs.skipped.value, null)))

    private fun compose(runtime: RuntimeState, update: AvailableRuntimeUpdate?, f: SetupFlow, x: Extras): BuiltInHarnessState {
        // The check that reads sizes for a first install also fills availableUpdate; it is only an
        // update when something is installed.
        val installedUpdate = update.takeIf { runtime.hasInstall() }
        return BuiltInHarnessState(
            runtime = runtime,
            release = f.release,
            releaseProblem = f.releaseProblem,
            update = installedUpdate,
            settingUp = f.settingUp,
            waitingForNetwork = f.waitingForNetwork,
            lastDownload = x.last,
            skippedRelease = x.skipped?.takeIf { installedUpdate != null && isSkipped(installedUpdate, it) }?.releaseId,
            outcome = x.outcome,
        )
    }

    private var lastReleaseCheck = 0L
    private var retries = 0
    private var resumeJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        scope.launch { controller.state.collect(::follow) }
        scope.launch { controller.outcomes.collect { _outcome.value = it } }
        if (controller.state.value == RuntimeState.NotInstalled) refreshRelease()
    }

    // ---- The port --------------------------------------------------------------------------

    override fun setUp(port: Int) {
        val current = controller.state.value
        if (current.busy || current is RuntimeState.NotSupported) return
        retries = 0
        flow.update { it.copy(settingUp = true, waitingForNetwork = false, port = port) }
        if (current.hasInstall() && current !is RuntimeState.Running) {
            controller.start(port)
        } else if (!current.hasInstall()) {
            controller.install()
        }
    }

    override fun start(port: Int) {
        flow.update { it.copy(port = port) }
        controller.start(port)
    }

    override fun stop() {
        controller.stop()
    }

    override fun restart() {
        if (!controller.restart()) controller.start(flow.value.port)
    }

    // ---- What the set-up page and the control page add --------------------------------------

    /** Stop a set-up and leave nothing of it behind. */
    fun cancelSetUp() {
        flow.update { it.copy(settingUp = false, waitingForNetwork = false) }
        stopWaiting()
        scope.launch {
            when (controller.state.value) {
                is RuntimeState.Starting -> controller.stop()
                is RuntimeState.Downloading, is RuntimeState.Verifying, is RuntimeState.Unpacking -> controller.cancel()
                else -> Unit
            }
            val settled = controller.state.first { !it.busy }
            // A first install that was cancelled: the downloads kept for a resume go too.
            if (settled == RuntimeState.NotInstalled || settled is RuntimeState.Failed && settled.installed == null) {
                controller.uninstall(keepUserData = true)
            }
            _lastDownload.value = null
        }
    }

    /**
     * Read the latest release's manifest, for its sizes and for a refusal, at most every few
     * minutes unless [force]d. Also how the control page looks for an update.
     */
    fun refreshRelease(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastReleaseCheck < RELEASE_CHECK_INTERVAL_MS && flow.value.release != null) return
        lastReleaseCheck = now
        scope.launch { checkRelease() }
    }

    /** Look for an update now; the answer also lands in [state]. */
    suspend fun checkRelease(): RuntimeInstaller.CheckResult {
        val result = controller.checkForUpdate()
        flow.update { current ->
            when (result) {
                is RuntimeInstaller.CheckResult.Available -> current.copy(
                    release = RuntimeReleaseInfo(
                        releaseId = result.manifest.releaseId,
                        harnessVersion = result.manifest.harness.version,
                        layers = result.decision.layers.map { ReleaseLayerPlan(it.name, it.size, it.unpackedSize, it.entries) },
                        commandPermissions = result.manifest.runtime.permissions != null,
                        commandPermissionsVersion = result.manifest.runtime.permissions?.version ?: 0,
                    ),
                    releaseProblem = null,
                )
                is RuntimeInstaller.CheckResult.UpToDate -> current.copy(releaseProblem = null)
                is RuntimeInstaller.CheckResult.Refused -> current.copy(releaseProblem = result.failure)
            }
        }
        return result
    }

    /** Fetch and apply the newer release; a running harness is switched over when it is ready. */
    fun update(): Boolean = controller.update()

    /** Cancel an update in flight; what is installed keeps running. */
    fun cancelUpdate() = controller.cancel()

    /** Install the release before the installed one; only on the person's own request. */
    fun goBack(): Boolean = controller.goBack()

    /** The person has read how the last update ended. */
    fun dismissOutcome() {
        _outcome.value = null
    }

    /** Notifications about new harness releases (on by default). */
    val notifyUpdates: StateFlow<Boolean> get() = updatePrefs.notify

    fun setNotifyUpdates(enabled: Boolean) = updatePrefs.setNotify(enabled)

    /** Install harness updates by themselves when nothing would be interrupted (off by default). */
    val autoInstallUpdates: StateFlow<Boolean> get() = updatePrefs.autoInstall

    fun setAutoInstallUpdates(enabled: Boolean) = updatePrefs.setAutoInstall(enabled)

    /** The port a set-up or a start last used: where the harness is expected to listen. */
    val port: Int get() = flow.value.port

    fun uninstall(keepUserData: Boolean): Boolean {
        flow.update { it.copy(settingUp = false, waitingForNetwork = false) }
        val accepted = controller.uninstall(keepUserData)
        if (accepted) scope.launch {
            controller.state.first { it == RuntimeState.NotInstalled }
            refreshRelease(force = true)
        }
        return accepted
    }

    suspend fun storageUsage(): RuntimeStorageUsage = controller.storageUsage()

    fun logTail(lines: Int): String = controller.logTail(lines)

    val holdWakeLock: StateFlow<Boolean> get() = controller.holdWakeLock

    fun setHoldWakeLock(enabled: Boolean) = controller.setHoldWakeLock(enabled)

    val modulePack: StateFlow<Boolean> get() = controller.modulePack

    fun setModulePack(enabled: Boolean) = controller.setModulePack(enabled)

    /** "When the agent wants to run a command": the preset new sessions start on, from the next start. */
    val commandPermission: StateFlow<CommandPermission> get() = controller.commandPermission

    fun setCommandPermission(choice: CommandPermission) = controller.setCommandPermission(choice)

    /** The choice the running harness was started with (null on a release without the presets). */
    val startedWithCommandPermission: StateFlow<CommandPermission?> get() = controller.startedWithCommandPermission

    /** The one-time explanation of the choice has been seen (on the set-up page or the control page). */
    val commandPermissionExplained: StateFlow<Boolean> get() = controller.commandPermissionExplained

    fun markCommandPermissionExplained() = controller.setCommandPermissionExplained(true)

    /** Whether a download now would count against a data plan. */
    fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    /** The port the built-in harness last ran on in this app process, or null if it has not run. */
    @Volatile
    private var lastRunningPort: Int? = null

    /** Whether the built-in harness has been up on [port] in this app process: the chat's notice is about it. */
    fun servedThisProcess(port: Int): Boolean = lastRunningPort == port

    // ---- The flow -----------------------------------------------------------------------------

    private fun follow(runtime: RuntimeState) {
        if (runtime is RuntimeState.Running) lastRunningPort = runtime.port
        if (runtime is RuntimeState.Downloading && runtime.progress.bytesTotal > 0) _lastDownload.value = runtime.progress
        if (runtime is RuntimeState.Downloading && runtime.progress.bytesTotal > 0 || runtime is RuntimeState.Unpacking) {
            retries = 0
            if (flow.value.waitingForNetwork) flow.update { it.copy(waitingForNetwork = false) }
        }
        val f = flow.value
        if (!f.settingUp) return
        when (runtime) {
            // The engine takes the next operation the moment it publishes Stopped, so this is accepted.
            is RuntimeState.Stopped -> controller.start(f.port)
            is RuntimeState.Running -> flow.update { it.copy(settingUp = false, waitingForNetwork = false) }
            is RuntimeState.Failed -> if (runtime.reason is RuntimeFailure.Network && runtime.installed == null) {
                waitForNetwork()
            } else {
                // A failure the person has to see and act on: the set-up stops here.
                flow.update { it.copy(settingUp = false, waitingForNetwork = false) }
            }
            else -> Unit
        }
    }

    /**
     * Resume a broken-off download. No network: wait for one, as long as it takes. A network that
     * is up while the download still fails: three more tries, a little further apart each time.
     */
    private fun waitForNetwork() {
        flow.update { it.copy(waitingForNetwork = true) }
        if (!hasInternet()) {
            listenForNetwork()
            return
        }
        if (retries >= RETRY_DELAYS_MS.size) {
            flow.update { it.copy(settingUp = false, waitingForNetwork = false) }
            return
        }
        val wait = RETRY_DELAYS_MS[retries++]
        resumeJob?.cancel()
        resumeJob = scope.launch {
            delay(wait)
            resume()
        }
    }

    private fun resume() {
        if (!flow.value.settingUp) return
        stopWaiting()
        // Still "waiting" until bytes move again: a retry that fails at once must not flash an error.
        controller.install()
    }

    private fun listenForNetwork() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    resumeJob?.cancel()
                    // A moment for DNS and routes to settle on the new network.
                    resumeJob = scope.launch {
                        delay(NETWORK_SETTLE_MS)
                        resume()
                    }
                }
            }
        }
        networkCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onFailure { networkCallback = null }
    }

    private fun stopWaiting() {
        resumeJob?.cancel()
        resumeJob = null
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun hasInternet(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val RELEASE_CHECK_INTERVAL_MS = 10 * 60_000L
        val RETRY_DELAYS_MS = longArrayOf(3_000, 10_000, 30_000)
        const val NETWORK_SETTLE_MS = 1_500L
    }
}

/** Whether a runtime is on the device in this state. */
internal fun RuntimeState.hasInstall(): Boolean = when (this) {
    is RuntimeState.Stopped, is RuntimeState.Starting, is RuntimeState.Running, is RuntimeState.Stopping,
    is RuntimeState.Updating,
    -> true
    is RuntimeState.Failed -> installed != null
    else -> false
}
