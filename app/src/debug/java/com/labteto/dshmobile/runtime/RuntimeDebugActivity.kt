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
 * | `install`, `update` | `--es appVersion 0.22.0` (optional) | install or update to the latest release |
 * | `check` | `--es appVersion` | look for an update, log the result |
 * | `cancel` | | abandon an install or a start |
 * | `start` | `--ei port 3080` | start the harness |
 * | `stop`, `restart` | | |
 * | `uninstall` | `--ez keepUserData true` | |
 * | `status` | | state, storage use, proot and guest processes |
 * | `logtail` | `--ei lines 40` | the end of the harness log (token already removed) |
 * | `wakelock` | `--ez on false` | the wake-lock switch |
 * | `probe` | `--ei port 3080` | `POST /api/session/list` without and with the stored cookie; status codes only |
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = RuntimeDebugLog.get(this)
        log.follow(controller)
        val op = intent.getStringExtra("op").orEmpty()
        intent.getStringExtra("appVersion")?.let { controller.appVersionOverride = it }
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
            "logtail" -> log.write("log tail:\n" + controller.logTail(intent.getIntExtra("lines", 40)))
            "probe" -> log.scope.launch { probe(log, port) }
            else -> log.write("unknown op '$op'")
        }
        finish()
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

        @Volatile
        private var instance: RuntimeDebugLog? = null

        fun get(activity: ComponentActivity): RuntimeDebugLog =
            instance ?: synchronized(this) {
                instance ?: RuntimeDebugLog(File(activity.filesDir, "runtime-debug.log")).also { instance = it }
            }
    }
}
