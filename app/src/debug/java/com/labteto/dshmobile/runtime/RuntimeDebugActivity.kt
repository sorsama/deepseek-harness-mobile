package com.labteto.dshmobile.runtime

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import com.labteto.dshmobile.connection.HarnessSessionStore
import com.labteto.dshmobile.connection.HostsStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Debug builds only: drive [HarnessRuntimeController] from adb.
 *
 * ```
 * adb shell am start -n com.labteto.dshmobile.debug/com.labteto.dshmobile.runtime.RuntimeDebugActivity --es op <op> [extras]
 * ```
 *
 * | op | extras | does |
 * |---|---|---|
 * | `install`, `update` | `--es appVersion <x.y.z>` (only for a release cut for a newer app than this build), `--es manifestUrl <url>` (`latest` for the latest) | install or update to the latest (or that) release |
 * | `check` | `--es appVersion` | look for an update, log the result |
 * | `cancel` | | abandon an install or a start |
 * | `start` | `--ei port 3080` | start the harness |
 * | `stop`, `restart` | | |
 * | `uninstall` | `--ez keepUserData true` | |
 * | `status` | | state, storage use, proot and guest processes |
 * | `logtail` | `--ei lines 40` | the end of the harness log (token already removed) |
 * | `wakelock` | `--ez on false` | the wake-lock switch |
 * | `probe` | `--ei port 3080` | `POST /api/session/list` without and with the stored cookie; status codes only |
 * | `orphan` | `--ei port 3080` | leave an untracked proot holding the port, as an earlier app process could |
 * | `goback` | | install the installed manifest's `previous` release (the control page's "Go back") |
 * | `beforeupdate` | | what the in-app updater does right before it installs an app update: remember whether the harness should start again after it (then `adb install -r` a build with a higher version code) |
 * | `failtrial` | `--ez on true` | the next start of a release on trial fails before readiness (node loads a missing module) |
 * | `permission` | `--es value full\|ask`, `--ez explained false` | show or change the command choice and whether its explanation was seen |
 * | `mobilepatch` | `--ez off true` | the next start leaves the runtime's Android permission patch out (upstream's presets) |
 * | `checknow` | | the control page's "Check for updates" (marks the release as told about) |
 * | `bgcheck` | `--ez reset true` | the daily background check; `reset` forgets when the last check ran |
 * | `updateprefs` | `--ez notify`, `--ez auto`, `--ez clear true` | show or change what harness updates remember |
 * | `mainstats` | `--ez reset true` | how long the main thread took to reach a posted message since the last reset ([MainThreadProbe]), with the number of states and notification posts |
 * | `jankinstall` | `--ei ms 12000` | block the main thread for `ms` while an install asks for the service (needs a cleared runtime); the foreground-service start timeout on purpose |
 *
 * Everything is logged to logcat (tag `DshRuntimeDebug`) and to `files/runtime-debug.log`
 * (`run-as com.labteto.dshmobile.debug cat files/runtime-debug.log`). State changes are logged as
 * they happen, with elapsed-realtime stamps for timing. No token or cookie is ever logged.
 */
@AndroidEntryPoint
class RuntimeDebugActivity : ComponentActivity() {

    @Inject lateinit var controller: HarnessRuntimeController
    @Inject lateinit var hosts: HostsStore
    @Inject lateinit var sessions: HarnessSessionStore
    @Inject lateinit var http: OkHttpClient
    @Inject lateinit var updates: RuntimeUpdates

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = RuntimeDebugLog.get(this)
        log.follow(controller)
        MainThreadProbe.start()
        val op = intent.getStringExtra("op").orEmpty()
        intent.getStringExtra("appVersion")?.let { controller.appVersionOverride = it }
        // "latest" (an empty extra does not survive `adb shell`) goes back to the latest release.
        intent.getStringExtra("manifestUrl")?.let { controller.manifestUrlOverride = it.takeIf { url -> url.startsWith("https://") } }
        val port = intent.getIntExtra("port", 3080)
        log.write("op=$op")
        when (op) {
            "install", "update" -> log.write("install accepted=${controller.install()}")
            "cancel" -> controller.cancel()
            "start" -> log.write("start accepted=${controller.start(port)}")
            "stop" -> log.write("stop accepted=${controller.stop()}")
            "restart" -> log.write("restart accepted=${controller.restart()}")
            "uninstall" -> log.write("uninstall accepted=${controller.uninstall(intent.getBooleanExtra("keepUserData", false))}")
            "wakelock" -> controller.setHoldWakeLock(intent.getBooleanExtra("on", true)).also { log.write("wakelock=${controller.holdWakeLock.value}") }
            "check" -> log.scope.launch { log.write("check: ${controller.checkForUpdate()}") }
            "status" -> log.scope.launch {
                log.write("state: ${controller.state.value}")
                log.write("update: ${controller.availableUpdate.value}")
                log.write("processes: ${controller.runtimeProcesses()}")
                log.write("storage: ${controller.storageUsage()}")
            }
            "jankinstall" -> {
                // Reproduces the worst moment on purpose: the install asks for the service from its
                // own thread, and this (main) thread is then unavailable for `ms` milliseconds, as
                // it is when frames back to back leave no room for the service's start.
                // The block starts 2 s from now, after the window has settled (a block while an
                // input or focus event is pending is the window's own ANR, not the service's), and
                // the install starts half a second into it.
                val ms = intent.getIntExtra("ms", 12_000)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    log.write("main thread blocked for $ms ms from here")
                    Thread.sleep(ms.toLong())
                    log.write("main thread free again")
                }, 2_000)
                log.scope.launch {
                    kotlinx.coroutines.delay(2_500)
                    log.write("install accepted=${controller.install()}")
                }
            }
            "mainstats" -> {
                log.write("mainstats: ${MainThreadProbe.summary()} notifications=${HarnessRuntimeService.notifyCount.get()} states=${log.stateCount.get()}")
                if (intent.getBooleanExtra("reset", false)) {
                    MainThreadProbe.reset()
                    HarnessRuntimeService.notifyCount.set(0)
                    log.stateCount.set(0)
                }
            }
            "logtail" -> log.write("log tail:\n" + controller.logTail(intent.getIntExtra("lines", 40)))
            "probe" -> log.scope.launch { probe(log, port) }
            "orphan" -> log.write("orphan: ${spawnOrphan(port)}")
            "goback" -> log.write("goback accepted=${controller.goBack()}")
            // What the in-app updater does right before it commits an install (SelfUpdateHooks), so
            // an `adb install -r` of a newer build can stand in for the update itself.
            "beforeupdate" -> log.scope.launch {
                controller.rememberForAppUpdate(hosts.settingsOnce().autoStartBuiltInHarness)
                log.write("beforeupdate: state=${controller.state.value::class.simpleName}")
            }
            "failtrial" -> {
                controller.failNextTrialStart = intent.getBooleanExtra("on", true)
                log.write("failNextTrialStart=${controller.failNextTrialStart}")
            }
            "permission" -> {
                intent.getStringExtra("value")?.let { controller.setCommandPermission(CommandPermission.fromStored(it)) }
                if (intent.hasExtra("explained")) controller.setCommandPermissionExplained(intent.getBooleanExtra("explained", false))
                log.write("permission: choice=${controller.commandPermission.value} explained=${controller.commandPermissionExplained.value} startedWith=${controller.startedWithCommandPermission.value}")
            }
            "mobilepatch" -> {
                controller.mobilePatchOff = intent.getBooleanExtra("off", false)
                log.write("mobilePatchOff=${controller.mobilePatchOff} (applies at the next start)")
            }
            "checknow" -> log.scope.launch { log.write("checknow: ${summary(updates.checkNow())}") }
            "bgcheck" -> log.scope.launch {
                if (intent.getBooleanExtra("reset", false)) controller.updatePreferences.lastCheckAt = 0
                log.write("bgcheck: ${updates.onBackground()?.let(::summary) ?: "skipped (checked recently, switch off, or nothing installed)"}")
            }
            "updateprefs" -> {
                val prefs = controller.updatePreferences
                if (intent.hasExtra("notify")) prefs.setNotify(intent.getBooleanExtra("notify", true))
                if (intent.hasExtra("auto")) prefs.setAutoInstall(intent.getBooleanExtra("auto", false))
                if (intent.getBooleanExtra("clear", false)) {
                    prefs.setSkipped(null)
                    prefs.setFailed(null)
                    prefs.notifiedSequence = 0
                    prefs.lastCheckAt = 0
                }
                log.write("updateprefs: notify=${prefs.notify.value} auto=${prefs.autoInstall.value} skipped=${prefs.skipped.value} failed=${prefs.failed.value} notified=${prefs.notifiedSequence} lastCheckAt=${prefs.lastCheckAt}")
            }
            else -> log.write("unknown op '$op'")
        }
        finish()
    }

    /**
     * Leave a proot behind that the supervisor does not know about: started from this process (so
     * it has the app's uid and SELinux domain, like a harness an earlier app process left), running
     * node with a listener on [port]. The next start has to find it, stop it and take the port.
     */
    private fun spawnOrphan(port: Int): String {
        val record = InstalledStore(controller.layout).read() ?: return "not installed"
        val nativeDir = File(applicationInfo.nativeLibraryDir)
        val rootfs = File(controller.layout.root, record.base.dir)
        val script = "require('net').createServer().listen($port, '127.0.0.1')"
        val builder = ProcessBuilder(
            File(nativeDir, ProotCommand.PROOT).path, "--kill-on-exit", "-0", "-r", rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "/usr/bin/env", "-i", "PATH=/opt/node/bin:/usr/bin:/bin", "/opt/node/bin/node", "-e", script,
        ).redirectErrorStream(true).redirectOutput(File("/dev/null"))
        builder.environment()["PROOT_LOADER"] = File(nativeDir, ProotCommand.LOADER).path
        builder.environment()["PROOT_TMP_DIR"] = controller.layout.prootTmp.apply { mkdirs() }.path
        val process = builder.start()
        RuntimeDebugLog.orphans += process
        return "started an untracked proot ($process) listening on $port"
    }

    /** A check result without the whole manifest. */
    private fun summary(result: RuntimeInstaller.CheckResult): String = when (result) {
        is RuntimeInstaller.CheckResult.Available -> "available ${result.manifest.releaseId} seq=${result.manifest.sequence} download=${result.downloadBytes} layers=${result.decision.layers.map { it.name }}"
        is RuntimeInstaller.CheckResult.UpToDate -> "up to date (${result.manifest.releaseId})"
        is RuntimeInstaller.CheckResult.Refused -> "refused ${result.failure}"
    }

    private suspend fun probe(log: RuntimeDebugLog, port: Int) {
        val host = hosts.ensureLoopbackHost(port)
        val cookie = sessions.cookie(host.id)
        val body = """{"type":"client-request","rpcId":"debug-probe","method":"session/list","payload":{"args":{"_request":{}}}}"""
        fun call(withCookie: Boolean): String = try {
            val request = Request.Builder()
                .url("http://127.0.0.1:$port/api/session/list")
                .post(body.toRequestBody("application/json".toMediaType()))
                .apply { if (withCookie && cookie != null) header("Cookie", cookie) }
                .build()
            http.newCall(request).execute().use { r -> "${r.code} (${r.body?.string()?.length ?: 0} bytes)" }
        } catch (e: Exception) {
            "failed: ${e.javaClass.simpleName} ${e.message}"
        }
        log.write("probe anonymous POST /api/session/list -> ${call(false)}")
        log.write("probe stored cookie present=${cookie != null}; POST /api/session/list -> ${call(true)}")
    }
}

/** The debug driver's log: logcat plus a file, with every state change of the controller. */
class RuntimeDebugLog private constructor(private val file: File) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val following = AtomicBoolean(false)
    val stateCount = java.util.concurrent.atomic.AtomicInteger()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun write(message: String) {
        val line = "${stamp.format(Date())} t=${SystemClock.elapsedRealtime()} $message"
        Log.i(TAG, line)
        file.appendText(line + "\n")
    }

    /** Log state changes; progress only when the phase changes or every tenth of the way. */
    fun follow(controller: HarnessRuntimeController) {
        if (!following.compareAndSet(false, true)) return
        scope.launch { controller.outcomes.collect { write("outcome: $it") } }
        // States a fast collector sees: what a screen could be asked to redraw for.
        scope.launch(Dispatchers.Default) { controller.state.collect { stateCount.incrementAndGet() } }
        scope.launch {
            var lastKey = ""
            controller.state.collect { state ->
                val key = progressKey(state)
                if (key != lastKey) {
                    lastKey = key
                    write("state: $state")
                }
            }
        }
    }

    private fun progressKey(state: RuntimeState): String = when (state) {
        is RuntimeState.Downloading -> "dl-${state.progress.layer}-${state.progress.percent / 10}"
        is RuntimeState.Unpacking -> "un-${state.progress.layer}-${state.progress.percent / 10}"
        is RuntimeState.Updating -> when (val p = state.phase) {
            is UpdatePhase.Downloading -> "udl-${p.progress.layer}-${p.progress.percent / 10}"
            is UpdatePhase.Unpacking -> "uun-${p.progress.layer}-${p.progress.percent / 10}"
            else -> "u-$p"
        }
        else -> state.toString()
    }

    companion object {
        private const val TAG = "DshRuntimeDebug"

        /** Kept only so the orphan's pipes are not collected; nothing ever stops them. */
        val orphans = mutableListOf<Process>()

        @Volatile
        private var instance: RuntimeDebugLog? = null

        fun get(activity: ComponentActivity): RuntimeDebugLog =
            instance ?: synchronized(this) {
                instance ?: RuntimeDebugLog(File(activity.filesDir, "runtime-debug.log")).also { instance = it }
            }
    }
}
