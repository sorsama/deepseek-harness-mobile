package com.labteto.dshmobile.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A started harness process: proot, as seen from the app. */
interface HarnessProcess {
    /** The next line of stdout and stderr together, or null at the end of output. */
    suspend fun readLine(): String?

    /** Completes with the exit code when the process ends. */
    suspend fun awaitExit(): Int

    val isAlive: Boolean
}

/** Starts processes; [JvmProcessLauncher] on the device, a fake in tests. */
fun interface ProcessLauncher {
    fun launch(argv: List<String>, environment: Map<String, String>, removeFromEnvironment: Set<String>, workDir: File): HarnessProcess
}

/** One process as `/proc` shows it. */
data class ProcInfo(val pid: Int, val ppid: Int, val startTime: Long, val argv0: String)

/** The processes this app may see and signal: on Android, its own uid's. */
interface ProcessTable {
    /** This app's own pid. */
    val selfPid: Int

    /** Live processes; zombies are left out. */
    fun snapshot(): List<ProcInfo>
    fun signal(pid: Int, signal: Int)
    fun isAlive(pid: Int): Boolean

    companion object {
        const val SIGKILL = 9
        const val SIGQUIT = 3
        const val SIGTERM = 15
    }
}

/** Every process below [root], children before grandchildren. */
fun List<ProcInfo>.descendantsOf(root: Int): List<ProcInfo> {
    val byParent = groupBy { it.ppid }
    val out = ArrayList<ProcInfo>()
    val queue = ArrayDeque(listOf(root))
    val seen = HashSet<Int>()
    while (queue.isNotEmpty()) {
        val pid = queue.removeFirst()
        for (child in byParent[pid].orEmpty()) {
            if (seen.add(child.pid)) {
                out += child
                queue += child.pid
            }
        }
    }
    return out
}

/**
 * `java.lang.ProcessBuilder`, with the exit watched by one blocking `waitFor()`.
 *
 * Android's `Process.waitFor(timeout)` polls every 100 ms, which the spike measured as a
 * quantisation of every timing; a thread parked in `waitFor()` wakes the moment the child ends.
 */
object JvmProcessLauncher : ProcessLauncher {
    override fun launch(
        argv: List<String>,
        environment: Map<String, String>,
        removeFromEnvironment: Set<String>,
        workDir: File,
    ): HarnessProcess {
        val builder = ProcessBuilder(argv).directory(workDir).redirectErrorStream(true)
        val env = builder.environment()
        removeFromEnvironment.forEach { env.remove(it) }
        env.putAll(environment)
        val process = builder.start()
        // The harness never reads stdin; closing it means nothing can ever block on it.
        runCatching { process.outputStream.close() }
        val exit = CompletableDeferred<Int>()
        Thread({
            val code = try {
                process.waitFor()
            } catch (e: InterruptedException) {
                -1
            }
            exit.complete(code)
        }, "harness-exit").apply { isDaemon = true }.start()
        val reader = process.inputStream.bufferedReader()
        return object : HarnessProcess {
            override suspend fun readLine(): String? = withContext(Dispatchers.IO) {
                try {
                    reader.readLine()
                } catch (e: java.io.IOException) {
                    null
                }
            }

            override suspend fun awaitExit(): Int = exit.await()
            override val isAlive: Boolean get() = !exit.isCompleted
        }
    }
}
