package com.labteto.dshmobile.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One program to run inside the runtime for the app itself (today only `git clone`), as an argument
 * list. There is no shell string anywhere: [argv] goes to the guest's `execve` as it is.
 *
 * Constructed only inside this module, by builders such as
 * [com.labteto.dshmobile.runtime.workspace.GitClone]; nothing outside the app can make one. No
 * exported component (the documents provider included) runs one, so this is not a way for another
 * app to run commands in the runtime.
 *
 * [secretEnvironment] is for a value that must reach the program but must not be on a command line
 * or in a log (a clone's token). [toString] names its keys only, and [redact] lists the strings to
 * remove from every output line.
 */
class GuestCommand internal constructor(
    val argv: List<String>,
    val environment: Map<String, String>,
    val secretEnvironment: Map<String, String>,
    val workingDirectory: String,
    val redact: List<String>,
) {
    override fun toString() = "GuestCommand(argv=$argv, environment=$environment, secrets=${secretEnvironment.keys}, cwd=$workingDirectory)"
}

/**
 * Runs a [GuestCommand] under proot, next to the harness or with the harness stopped: output line
 * by line (redacted), a timeout, and cancellation that ends the whole process tree.
 *
 * The program gets the same root file system and binds as the harness (the base layer, `/root`,
 * `/workspaces`), a clean environment (nothing of Android's), and umask 022. Its proot is never
 * mistaken for a harness left over from an earlier run: [activePids] is what
 * [HarnessSupervisor.cleanupStale] leaves alone.
 */
class GuestCommandRunner(
    private val launcher: ProcessLauncher,
    private val procs: ProcessTable,
    /** The proot command for a guest command; null when no runtime is installed. */
    private val build: (GuestCommand) -> ProotCommand?,
    private val workDir: File,
    private val inheritedEnvironment: () -> Set<String> = { System.getenv().keys },
) {
    sealed interface Outcome {
        data class Exited(val code: Int, val tail: List<String>) : Outcome
        data class TimedOut(val tail: List<String>) : Outcome
        data class LaunchFailed(val detail: String) : Outcome
        data object NotInstalled : Outcome
    }

    private val pids = ConcurrentHashMap.newKeySet<Int>()
    private val _running = MutableStateFlow(0)

    /** How many guest commands are running. */
    val running: StateFlow<Int> = _running.asStateFlow()

    /** proot pids of the commands running now. */
    val activePids: Set<Int> get() = pids.toSet()

    /**
     * Run [command] and wait for it. Cancelling the calling coroutine kills the process tree and
     * rethrows; a run longer than [timeoutMs] is killed and reported as [Outcome.TimedOut].
     */
    suspend fun run(command: GuestCommand, timeoutMs: Long, onLine: (String) -> Unit): Outcome {
        val proot = build(command) ?: return Outcome.NotInstalled
        _running.update { it + 1 }
        var pid: Int? = null
        try {
            val process = try {
                launcher.launch(proot.argv, proot.environment, proot.removeFromEnvironment + inheritedEnvironment(), workDir)
            } catch (e: Exception) {
                return Outcome.LaunchFailed(e.message ?: e.javaClass.simpleName)
            }
            val tail = ArrayDeque<String>()
            return try {
                coroutineScope {
                    val reader = launch {
                        var first = true
                        while (true) {
                            val raw = process.readLine() ?: break
                            if (first) {
                                first = false
                                val shellPid = raw.trim().toIntOrNull()
                                if (shellPid != null) {
                                    pid = shellPid
                                    pids += shellPid
                                    continue
                                }
                            }
                            val line = redact(raw, command.redact)
                            synchronized(tail) {
                                tail.addLast(line)
                                while (tail.size > TAIL_LINES) tail.removeFirst()
                            }
                            onLine(line)
                        }
                    }
                    val code = withTimeoutOrNull(timeoutMs) { process.awaitExit() }
                    if (code == null) {
                        kill(pid, process)
                        withTimeoutOrNull(DRAIN_MS) { reader.join() }
                        reader.cancel()
                        Outcome.TimedOut(synchronized(tail) { tail.toList() })
                    } else {
                        withTimeoutOrNull(DRAIN_MS) { reader.join() }
                        reader.cancel()
                        Outcome.Exited(code, synchronized(tail) { tail.toList() })
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { kill(pid, process) }
                throw e
            }
        } finally {
            pid?.let { pids -= it }
            _running.update { it - 1 }
        }
    }

    /** proot and everything below it, at once: a guest command has nothing to save on the way out. */
    private suspend fun kill(pid: Int?, process: HarnessProcess) {
        val root = pid ?: return
        val tree = procs.snapshot().descendantsOf(root)
        tree.forEach { procs.signal(it.pid, ProcessTable.SIGKILL) }
        procs.signal(root, ProcessTable.SIGKILL)
        withTimeoutOrNull(KILL_WAIT_MS) {
            while (process.isAlive) delay(50)
        }
        // A child re-parented before the snapshot is caught here.
        tree.filter { procs.isAlive(it.pid) }.forEach { procs.signal(it.pid, ProcessTable.SIGKILL) }
    }

    companion object {
        const val TAIL_LINES = 40
        private const val DRAIN_MS = 2_000L
        private const val KILL_WAIT_MS = 3_000L

        fun redact(line: String, secrets: List<String>): String {
            var out = line
            for (secret in secrets) if (secret.length >= 4) out = out.replace(secret, "[redacted]")
            return out
        }
    }
}
