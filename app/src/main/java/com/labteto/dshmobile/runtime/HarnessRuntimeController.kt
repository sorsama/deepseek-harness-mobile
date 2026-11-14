package com.labteto.dshmobile.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.github.luben.zstd.ZstdInputStream
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.connection.LoopbackSignIn
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
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
     * device tests install a release cut for the next app version. Ignored in release builds.
     */
    @Volatile
    var appVersionOverride: String? = null

    /** Debug builds only: install a specific release (its manifest URL) instead of the latest. */
    @Volatile
    var manifestUrlOverride: String? = null

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

    private val supervisor = HarnessSupervisor(scope, JvmProcessLauncher, AndroidProcessTable, log)

    private val platform = object : RuntimeEngine.Platform {
        override val unsupported: RuntimeUnsupported? = system.unsupportedReason()
        override val nativeLibraryDir: File get() = system.nativeLibraryDir
        override fun dnsServers(): List<String> = system.dnsServers()
        override val cpuCount: Int get() = system.cpuCount
        override fun portFree(port: Int): Boolean = isPortFree(port)
        override fun keepAlive() = startService(null)
    }

    private val engine = RuntimeEngine(
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

    private val _holdWakeLock = MutableStateFlow(true)

    /**
     * Whether a partial wake lock is held while the harness runs (and while the runtime installs or
     * updates). On by default: in deep Doze the CPU sleeps and a long agent turn would stall with
     * the screen off. The single switch M3's settings can expose; it takes effect at once.
     */
    val holdWakeLock: StateFlow<Boolean> = _holdWakeLock.asStateFlow()

    fun setHoldWakeLock(enabled: Boolean) {
        _holdWakeLock.value = enabled
    }

    fun install(): Boolean = engine.install()

    /** Same as [install]: fetch the latest release and apply only what changed. */
    fun update(): Boolean = engine.install()

    fun cancel() = engine.cancel()

    fun start(port: Int): Boolean = engine.start(port)

    fun stop(): Boolean = engine.stop()

    fun restart(): Boolean = engine.restart()

    fun uninstall(keepUserData: Boolean = false): Boolean = engine.uninstall(keepUserData)

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

    internal fun startService(action: String?) {
        // Already in the foreground: it follows the state by itself, and a second start from the
        // background (an update's restart while the app is not visible) could be refused.
        if (action == null && HarnessRuntimeService.inForeground) return
        val intent = Intent(context, HarnessRuntimeService::class.java).setAction(action)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground-service start from the background. Operations are
            // started from the UI, so this is a restart nobody is looking at; it still runs, only
            // without the service's protection.
            android.util.Log.w("DshRuntime", "foreground service not started: ${e.javaClass.simpleName}")
        }
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
