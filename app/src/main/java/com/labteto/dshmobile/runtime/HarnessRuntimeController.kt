package com.labteto.dshmobile.runtime

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.github.luben.zstd.ZstdInputStream
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.connection.LoopbackSignIn
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The built-in harness: installs the runtime, runs the harness inside proot, signs the app in.
 *
 * App-scoped, like [com.labteto.dshmobile.termux.TermuxHarnessController]: an install takes
 * minutes and a start up to half a minute, and whoever asked may leave the screen meanwhile.
 * Screens observe [state]. The controller never connects: when the harness is up it stores the
 * session cookie against the loopback record and reports [RuntimeState.Running]; the connect
 * screen decides what to do with that.
 *
 * Long work runs with [HarnessRuntimeService] in the foreground, which also holds a partial wake
 * lock while the harness runs (see [setHoldWakeLock]).
 */
@Singleton
class HarnessRuntimeController @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    private val loopbackSignIn: LoopbackSignIn,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val system = AndroidRuntimeSystem(context)
    private val preferences = RuntimePreferences.over(context.getSharedPreferences(RuntimePreferences.FILE, Context.MODE_PRIVATE))

    /** What harness updates remember: the two switches, the last check, skipped and failed releases. */
    val updatePreferences = RuntimeUpdatePreferences.over(context.getSharedPreferences(RuntimeUpdatePreferences.FILE, Context.MODE_PRIVATE))

    val layout = RuntimeLayout(
        root = File(context.noBackupFilesDir, "runtime"),
        cache = File(context.cacheDir, "runtime"),
    )

    private val log = RotatingLog(File(layout.logs, "harness.log"))

    /** Downloads are long reads from a CDN; the shared client's 30 s read timeout and pings are for the harness socket. */
    private val downloadClient = okHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    /**
     * Debug builds only: the version the release's `minAppVersion` is compared with. Lets the
     * device tests install a release cut for the next app version (the first runtime releases
     * needed 0.22.0 before the app was 0.22.0). Ignored in release builds, which always compare
     * [BuildConfig.VERSION_NAME].
     */
    @Volatile
    var appVersionOverride: String? = null

    /** Debug builds only: install a specific release (its manifest URL) instead of the latest. */
    @Volatile
    var manifestUrlOverride: String? = null

    /**
     * Debug builds only: the next start of a release on trial fails before readiness (node is told
     * to load a module that does not exist), so the device test can watch a real rollback.
     * Ignored in release builds.
     */
    @Volatile
    var failNextTrialStart: Boolean = false

    private val installer = RuntimeInstaller(
        layout = layout,
        store = InstalledStore(layout),
        fs = AndroidFsOps,
        client = downloadClient,
        downloader = LayerDownloader(downloadClient, layout.downloads),
        space = system.space,
        manifestUrl = { manifestUrlOverride?.takeIf { BuildConfig.DEBUG } ?: RuntimeInstaller.DEFAULT_MANIFEST_URL },
        appVersion = { appVersionOverride?.takeIf { BuildConfig.DEBUG } ?: BuildConfig.VERSION_NAME },
        deviceAbis = system.deviceAbis,
        decoder = { ZstdInputStream(it) },
    )

    private val supervisor = HarnessSupervisor(
        scope,
        JvmProcessLauncher,
        AndroidProcessTable,
        log,
        notHarnesses = { guestRunner.activePids },
        guestsActive = { guestRunner.running.value > 0 },
    )

    private val installedStore = InstalledStore(layout)

    /** The app's own commands in the runtime (a clone); see [runGuestCommand]. */
    private val guestRunner: GuestCommandRunner by lazy {
        GuestCommandRunner(
            launcher = JvmProcessLauncher,
            procs = AndroidProcessTable,
            build = { command ->
                installedStore.read()?.let { record ->
                    prepareGuestRun()
                    ProotCommand.buildGuest(system.nativeLibraryDir, layout, record, command)
                }
            },
            workDir = layout.root,
        )
    }

    /** Guest commands running now, for screens that say "a clone is still running". */
    val guestCommandsRunning: StateFlow<Int> get() = guestRunner.running

    /**
     * Run [command] inside the runtime, with the harness running or stopped. Refused (null) while
     * the runtime is being installed, updated or removed; an install or update waits for this to end.
     * Only the app's own builders make a [GuestCommand]; nothing outside the app reaches this.
     */
    suspend fun runGuestCommand(command: GuestCommand, timeoutMs: Long, onLine: (String) -> Unit): GuestCommandRunner.Outcome? {
        if (system.unsupportedReason() != null) return null
        if (!engine.beginGuestCommand()) return null
        return try {
            guestRunner.run(command, timeoutMs, onLine)
        } finally {
            engine.endGuestCommand()
        }
    }

    /** The files a start writes (DNS, CPU count), for a guest command run with the harness stopped. */
    private fun prepareGuestRun() {
        layout.run.mkdirs()
        layout.prootTmp.mkdirs()
        layout.workspaces.mkdirs()
        layout.home.mkdirs()
        val resolv = java.io.File(layout.run, ProotCommand.GENERATED_RESOLV)
        resolv.writeText(ProotCommand.resolvConf(system.dnsServers()))
        val stat = java.io.File(layout.run, ProotCommand.GENERATED_PROC_STAT)
        if (!stat.isFile) stat.writeText(ProotCommand.procStat(null, system.cpuCount))
    }

    /** Whether the device can run the runtime at all (the documents provider is enabled only then). */
    val supported: Boolean get() = system.unsupportedReason() == null

    private val platform: RuntimeEngine.Platform = object : RuntimeEngine.Platform {
        override val unsupported: RuntimeUnsupported? = system.unsupportedReason()
        override val nativeLibraryDir: File get() = system.nativeLibraryDir
        override fun dnsServers(): List<String> = system.dnsServers()
        override val cpuCount: Int get() = system.cpuCount
        override fun portFree(port: Int): Boolean = isPortFree(port)
        override fun keepAlive() {
            serviceStarter.ensureStarted()
        }
        override val modulePack: Boolean get() = preferences.modulePack.value
        override fun commandPermission(record: InstalledRecord): CommandPermission {
            val choice = preferences.commandPermission.value
            _startedWith.value = choice.takeIf { record.permissions != null }
            return choice
        }
        override fun guestEnvironment(record: InstalledRecord): Map<String, String> {
            if (!BuildConfig.DEBUG) return emptyMap()
            val extra = mutableMapOf<String, String>()
            // Debug only: start the harness exactly as upstream ships it, to make a session on the
            // harness's own Workspace Write (the one that refuses every command on a phone).
            val patch = record.permissions
            if (mobilePatchOff && patch != null) extra[patch.disableEnv] = patch.disableValue
            if (record.onTrial && failNextTrialStart) {
                failNextTrialStart = false
                extra["NODE_OPTIONS"] = "--require=/opt/dsh-fault-injection/missing-module.js"
            }
            return extra
        }
    }

    /** Debug builds only: start without the runtime's Android permission patch (its `disableEnv`). */
    @Volatile
    var mobilePatchOff: Boolean = false

    private val _startedWith = MutableStateFlow<CommandPermission?>(null)

    /**
     * The command choice the harness was last started with; null when that start was on a release
     * without the presets. Compared with [commandPermission] to offer a restart.
     */
    val startedWithCommandPermission: StateFlow<CommandPermission?> = _startedWith.asStateFlow()

    private val engine: RuntimeEngine = RuntimeEngine(
        scope = scope,
        layout = layout,
        installer = installer,
        supervisor = supervisor,
        fs = AndroidFsOps,
        platform = platform,
        signIn = { line, port -> loopbackSignIn.signIn(line, port) },
    )

    val state: StateFlow<RuntimeState> get() = engine.state

    /** Set by [checkForUpdate]; null when none is known. */
    val availableUpdate: StateFlow<AvailableRuntimeUpdate?> get() = engine.availableUpdate

    /**
     * Whether a partial wake lock is held while the harness runs (and while the runtime installs or
     * updates). On by default: in deep Doze the CPU sleeps and a long agent turn would stall with
     * the screen off. Persisted in [RuntimePreferences]; it takes effect at once.
     */
    val holdWakeLock: StateFlow<Boolean> get() = preferences.holdWakeLock

    fun setHoldWakeLock(enabled: Boolean) = preferences.setHoldWakeLock(enabled)

    /** "Fast start": the runtime's module pack. Persisted; takes effect on the next start. */
    val modulePack: StateFlow<Boolean> get() = preferences.modulePack

    fun setModulePack(enabled: Boolean) = preferences.setModulePack(enabled)

    /** What new sessions do when the agent wants to run a command. Persisted; takes effect on the next start. */
    val commandPermission: StateFlow<CommandPermission> get() = preferences.commandPermission

    fun setCommandPermission(choice: CommandPermission) = preferences.setCommandPermission(choice)

    /** Whether the person has seen the one-time explanation of [commandPermission]. */
    val commandPermissionExplained: StateFlow<Boolean> get() = preferences.commandPermissionExplained

    fun setCommandPermissionExplained(seen: Boolean) = preferences.setCommandPermissionExplained(seen)

    fun install(): Boolean = engine.install()

    /** Same as [install]: fetch the latest release and apply only what changed. */
    fun update(): Boolean = engine.install()

    /** The person's "go back to the previous version" (the installed manifest's `previous`). */
    fun goBack(): Boolean = engine.goBack()

    /** How each update and go-back ended; replays the last one. */
    val outcomes: SharedFlow<UpdateOutcome> get() = engine.outcomes

    fun cancel() = engine.cancel()

    fun start(port: Int): Boolean = engine.start(port)

    fun stop(): Boolean = engine.stop()

    fun restart(): Boolean = engine.restart()

    fun uninstall(keepUserData: Boolean = false): Boolean = engine.uninstall(keepUserData)

    // ---- Coming back after an app update (see ResumeAfterUpdate) ------------------------------

    private val resumeStore: ResumeAfterUpdate.Store =
        ResumeAfterUpdate.SharedStore(context.getSharedPreferences(ResumeAfterUpdate.SharedStore.FILE, Context.MODE_PRIVATE))

    /**
     * Called by the in-app updater right before it hands this app's update to the system: remember
     * whether the harness should come back afterwards. [autoStart] is the "start with the app" switch.
     */
    fun rememberForAppUpdate(autoStart: Boolean) {
        val port = ResumeAfterUpdate.portToResume(state.value, autoStart, resumeStore.lastPort())
        resumeStore.write(port?.let { ResumeAfterUpdate.Marker(it, BuildConfig.VERSION_CODE.toLong(), System.currentTimeMillis()) })
        Log.i(HarnessRuntimeService.TAG, "before app update: ${if (port != null) "start again on $port afterwards" else "leave it stopped"}")
    }

    /** The update was refused or cancelled: this process goes on, and so does the harness. */
    fun forgetAppUpdate() = resumeStore.write(null)

    /**
     * After an app update: start the harness again when it was running before. Quiet on every
     * refusal; whatever is left pending is tried again when the app is next opened. True when a
     * start was made.
     */
    fun resumeAfterAppUpdate(trigger: String): Boolean {
        val marker = resumeStore.read() ?: return false
        fun decide() = ResumeAfterUpdate.decide(marker, BuildConfig.VERSION_CODE.toLong(), System.currentTimeMillis(), state.value)
        val decision = decide()
        Log.i(HarnessRuntimeService.TAG, "after app update ($trigger): $decision")
        when (decision) {
            is ResumeAfterUpdate.Decision.Start -> {
                if (engine.start(decision.port)) {
                    resumeStore.write(null)
                    return true
                }
                // Someone else started it in between (the Connect screen), or it became busy.
                if (decide() == ResumeAfterUpdate.Decision.Drop) resumeStore.write(null)
            }
            ResumeAfterUpdate.Decision.Drop -> resumeStore.write(null)
            ResumeAfterUpdate.Decision.Wait, null -> Unit
        }
        return false
    }

    suspend fun checkForUpdate(): RuntimeInstaller.CheckResult = engine.checkForUpdate()

    suspend fun storageUsage(): RuntimeStorageUsage = engine.storageUsage()

    /** The end of the harness log, token already removed. */
    fun logTail(lines: Int = 200): String = log.tail(lines)

    /** Pids of this app's proot and guest processes right now, for diagnostics. */
    fun runtimeProcesses(): List<ProcInfo> {
        val all = AndroidProcessTable.snapshot()
        val proots = all.filter { it.argv0.endsWith("/" + ProotCommand.PROOT) }
        return (proots + proots.flatMap { all.descendantsOf(it.pid) }).distinctBy { it.pid }
    }

    /** Activities of this app that are started; above zero, the app is visible. */
    private val startedActivities = AtomicInteger()

    /**
     * How the app asks for [HarnessRuntimeService]; the service reports back to it. Operations are
     * started from the UI, so a refusal (Android 12+ refuses a service start from the background)
     * is a restart or an update nobody is looking at: it still runs, without the service's
     * protection, and the request is made again when the app is next visible.
     */
    internal val serviceStarter: RuntimeServiceStarter = RuntimeServiceStarter(object : RuntimeServiceStarter.Platform {
        override val appVisible: Boolean get() = startedActivities.get() > 0

        override fun wanted(): Boolean = engine.state.value.needsService()

        override fun startService(): Boolean = launchService(foreground = false)

        override fun startForegroundService(): Boolean = launchService(foreground = true)
    })

    private fun launchService(foreground: Boolean): Boolean {
        val intent = Intent(context, HarnessRuntimeService::class.java)
        HarnessRuntimeService.lastRequestAt = SystemClock.uptimeMillis()
        Log.i(HarnessRuntimeService.TAG, "asking for the service with ${if (foreground) "startForegroundService" else "startService"}, app visible=${startedActivities.get() > 0}, on ${Thread.currentThread().name}")
        return try {
            if (foreground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
            true
        } catch (e: IllegalStateException) {
            // Not allowed from the background (ForegroundServiceStartNotAllowedException and
            // BackgroundServiceStartNotAllowedException are both this).
            Log.w(HarnessRuntimeService.TAG, "service start refused: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun trackVisibility() {
        (context as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (startedActivities.incrementAndGet() == 1) {
                        // Off the main thread: the start is a binder call.
                        scope.launch {
                            serviceStarter.onAppVisible()
                            // The fallback for an app update the receiver could not resume after.
                            resumeAfterAppUpdate("app opened")
                        }
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities.decrementAndGet()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
    }

    init {
        trackVisibility()
        // The port an update brings the harness back on when it was set to start with the app.
        scope.launch { engine.state.collect { if (it is RuntimeState.Running) resumeStore.rememberPort(it.port) } }
    }

    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            // Node binds with SO_REUSEADDR too, so a port in TIME_WAIT is free for it as well.
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        }
        true
    } catch (e: java.io.IOException) {
        false
    }
}
