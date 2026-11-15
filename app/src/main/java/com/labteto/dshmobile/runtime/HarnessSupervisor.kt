package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.core.wire.HarnessLaunchLine
import com.labteto.dshmobile.runtime.ProcessTable.Companion.SIGKILL
import com.labteto.dshmobile.runtime.ProcessTable.Companion.SIGQUIT
import com.labteto.dshmobile.runtime.ProcessTable.Companion.SIGTERM
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Runs one harness process: starts it, watches its output for readiness, logs it with the token
 * removed, and stops it the way proot needs.
 *
 * Stopping is the part that is easy to get wrong. proot ignores SIGTERM, SIGINT and SIGHUP by
 * design, so `Process.destroy()` (SIGTERM on Android) does nothing at all and the next start
 * finds the port taken. The sequence here is the one the spike measured: SIGTERM to the guest's
 * main process (node, the direct child of proot), which exits and takes proot with it; if that
 * has not happened in [Timing.termGraceMs], SIGQUIT to proot, which makes it kill its tracees;
 * then SIGKILL to anything left.
 *
 * Plain Kotlin over [ProcessLauncher] and [ProcessTable]; all waiting is coroutine time, so the
 * tests run the whole sequence in virtual time.
 */
class HarnessSupervisor(
    private val scope: CoroutineScope,
    private val launcher: ProcessLauncher,
    private val procs: ProcessTable,
    private val log: RotatingLog,
    private val timing: Timing = Timing(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** proot pids that are not harnesses: the app's own guest commands ([GuestCommandRunner.activePids]). */
    private val notHarnesses: () -> Set<Int> = { emptySet() },
    /** Whether a guest command is running or about to ([GuestCommandRunner.running] above zero). */
    private val guestsActive: () -> Boolean = { false },
) {
    data class Timing(
        val readyTimeoutMs: Long = 240_000,
        val termGraceMs: Long = 5_000,
        val quitGraceMs: Long = 3_000,
        val killGraceMs: Long = 3_000,
        val pollMs: Long = 100,
        val drainMs: Long = 2_000,
    )

    sealed interface StartOutcome {
        /**
         * The readiness line arrived. [launchLine] holds the launch token: it goes to the sign-in
         * exchange and nowhere else. [toString] leaves it out.
         */
        class Ready(val launchLine: String, val port: Int, val pid: Int?, val elapsedMs: Long) : StartOutcome {
            override fun toString() = "Ready(port=$port, pid=$pid, elapsedMs=$elapsedMs)"
        }

        data class Exited(val exitCode: Int, val tail: String) : StartOutcome
        data class TimedOut(val tail: String) : StartOutcome
        data class LaunchFailed(val detail: String) : StartOutcome
    }

    /** What a stop did, in order; [leftover] lists pids that survived even SIGKILL. */
    data class StopReport(val pid: Int?, val steps: List<String>, val leftover: List<Int>, val elapsedMs: Long)

    private class Running(
        val process: HarnessProcess,
        val exit: kotlinx.coroutines.Deferred<Int>,
        @Volatile var pid: Int?,
        @Volatile var stopping: Boolean = false,
    ) {
        @Volatile
        var reader: Job? = null
    }

    private val lock = Mutex()
    private val redactor = TokenRedactor()
    private val tail = ArrayDeque<String>()

    @Volatile
    private var running: Running? = null

    val isRunning: Boolean get() = running?.process?.isAlive == true

    /** The pid of the running proot, if known. */
    val pid: Int? get() = running?.pid

    /** The last lines of output, redacted, newest last. */
    fun recentOutput(lines: Int = TAIL_LINES): String = synchronized(tail) { tail.toList().takeLast(lines).joinToString("\n") }

    /**
     * Start [command] and wait for [readinessPrefix].
     *
     * [onExitAfterReady] is called if the process ends on its own after it was ready; a stop
     * through [stop] does not call it.
     */
    suspend fun start(
        command: ProotCommand,
        workDir: File,
        readinessPrefix: String,
        requestedPort: Int,
        onExitAfterReady: (exitCode: Int, tail: String) -> Unit,
    ): StartOutcome = lock.withLock {
        check(running == null) { "a harness is already running" }
        synchronized(tail) { tail.clear() }
        val started = clock()
        log.append("---- start ${java.time.Instant.now()} port=$requestedPort")
        val process = try {
            launcher.launch(command.argv, command.environment, command.removeFromEnvironment, workDir)
        } catch (e: Exception) {
            log.append("---- launch failed: ${e.message}")
            return@withLock StartOutcome.LaunchFailed(e.message ?: e.javaClass.simpleName)
        }
        val ready = CompletableDeferred<String>()
        val exit = scope.async { process.awaitExit() }
        val state = Running(process, exit, pid = null)
        state.reader = scope.launch {
            var first = true
            while (true) {
                val raw = process.readLine() ?: break
                if (first) {
                    first = false
                    // The wrapper shell prints proot's pid before anything else (see ProotCommand).
                    val pid = raw.trim().toIntOrNull()
                    if (pid != null) {
                        state.pid = pid
                        continue
                    }
                }
                onLine(raw, ready, readinessPrefix)
            }
        }
        running = state
        val reader = state.reader!!

        val outcome = withTimeoutOrNull(timing.readyTimeoutMs) {
            select<StartOutcome> {
                ready.onAwait { line ->
                    val port = HarnessLaunchLine.parse(line)?.authority?.substringAfterLast(':')?.toIntOrNull() ?: requestedPort
                    StartOutcome.Ready(line, port, state.pid, clock() - started)
                }
                exit.onAwait { code ->
                    withTimeoutOrNull(timing.drainMs) { reader.join() }
                    StartOutcome.Exited(code, recentOutput())
                }
            }
        }
        when (outcome) {
            null -> {
                log.append("---- no readiness line after ${timing.readyTimeoutMs} ms; stopping")
                stopLocked()
                StartOutcome.TimedOut(recentOutput())
            }
            is StartOutcome.Exited -> {
                log.append("---- exited before ready with ${outcome.exitCode}")
                running = null
                outcome
            }
            is StartOutcome.Ready -> {
                log.append("---- ready after ${outcome.elapsedMs} ms (pid ${outcome.pid})")
                scope.launch {
                    val code = exit.await()
                    withTimeoutOrNull(timing.drainMs) { reader.join() }
                    if (state.stopping) return@launch
                    // A stop may have begun while this waited; whoever holds the lock decides.
                    val unexpected = lock.withLock {
                        (running === state && !state.stopping).also { if (it) running = null }
                    }
                    if (unexpected) {
                        log.append("---- exited on its own with $code")
                        onExitAfterReady(code, recentOutput())
                    }
                }
                outcome
            }
            else -> outcome
        }
    }

    /** Stop the running harness; null when nothing was running. */
    suspend fun stop(): StopReport? = lock.withLock { stopLocked() }

    private suspend fun stopLocked(): StopReport? {
        val current = running ?: return null
        current.stopping = true
        val pid = current.pid ?: findProot(current)
        val report = if (pid == null) {
            // Never learned the pid (the wrapper printed nothing): wait for it to end by itself.
            val gone = withTimeoutOrNull(timing.termGraceMs) { current.exit.await() } != null
            StopReport(null, listOf(if (gone) "exited" else "pid unknown, left running"), emptyList(), 0)
        } else {
            stopTree(pid) { timeout -> withTimeoutOrNull(timeout) { current.exit.await() } != null }
        }
        withTimeoutOrNull(timing.drainMs) { current.reader?.join() }
        running = null
        log.append("---- stopped: ${report.steps.joinToString(" -> ")}; leftover=${report.leftover} in ${report.elapsedMs} ms")
        return report
    }

    /**
     * Stop harnesses left by an earlier app process: any process of this uid whose argv[0] is a
     * `libproot.so`, other than the one this supervisor runs. Android usually kills an app's
     * children with it, but not always (a plain `kill` of the app's pid can leave them), and a
     * survivor holds the port.
     */
    suspend fun cleanupStale(): List<StopReport> = lock.withLock {
        val own = running?.pid
        // A guest command's proot is this process's child too, but before its first line of output
        // its pid is not in notHarnesses yet; a live child of this process is never "left by an
        // earlier run", so it is left alone either way.
        val guests = notHarnesses()
        val stale = procs.snapshot().filter {
            it.argv0.endsWith("/" + ProotCommand.PROOT) && it.pid != own && it.pid !in guests && !(guestsActive() && it.ppid == procs.selfPid)
        }
        // Only the top of each tree: a proot under another proot is stopped with its parent.
        val pids = stale.map { it.pid }.toSet()
        val roots = stale.filter { it.ppid !in pids }
        roots.map { info ->
            log.append("---- found a harness left by an earlier run (pid ${info.pid}); stopping it")
            stopTree(info.pid) { timeout -> pollGone(info.pid, timeout) }.also {
                log.append("---- stale stopped: ${it.steps.joinToString(" -> ")}; leftover=${it.leftover}")
            }
        }
    }

    private suspend fun stopTree(prootPid: Int, awaitGone: suspend (Long) -> Boolean): StopReport {
        val started = clock()
        val steps = ArrayList<String>()
        val tree = procs.snapshot().descendantsOf(prootPid)
        val guests = tree.filter { it.ppid == prootPid }
        var gone = false
        if (guests.isNotEmpty()) {
            guests.forEach { procs.signal(it.pid, SIGTERM) }
            steps += "SIGTERM guest ${guests.map { it.pid }}"
            gone = awaitGone(timing.termGraceMs)
        }
        if (!gone) {
            procs.signal(prootPid, SIGQUIT)
            steps += "SIGQUIT proot $prootPid"
            gone = awaitGone(timing.quitGraceMs)
        }
        if (!gone) {
            val all = (procs.snapshot().descendantsOf(prootPid) + tree).map { it.pid }.distinct()
            all.forEach { procs.signal(it, SIGKILL) }
            procs.signal(prootPid, SIGKILL)
            steps += "SIGKILL ${all + prootPid}"
            awaitGone(timing.killGraceMs)
        }
        // Anything from the original tree that outlived proot (re-parented to init) goes too.
        val stragglers = tree.map { it.pid }.filter { procs.isAlive(it) }
        if (stragglers.isNotEmpty()) {
            stragglers.forEach { procs.signal(it, SIGKILL) }
            steps += "SIGKILL stragglers $stragglers"
            stragglers.forEach { pollGone(it, timing.killGraceMs) }
        }
        val leftover = (tree.map { it.pid } + prootPid).filter { procs.isAlive(it) }
        return StopReport(prootPid, steps, leftover, clock() - started)
    }

    private suspend fun pollGone(pid: Int, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (procs.isAlive(pid)) delay(timing.pollMs)
            true
        } ?: false

    /** Fallback when the pid line was missing: our own proot child, by argv[0]. */
    private fun findProot(current: Running): Int? {
        if (!current.process.isAlive) return null
        val me = procs.selfPid
        return procs.snapshot().firstOrNull { it.ppid == me && it.argv0.endsWith("/" + ProotCommand.PROOT) }?.pid
    }

    private fun onLine(raw: String, ready: CompletableDeferred<String>, prefix: String) {
        val clean = ANSI.replace(raw, "").trimEnd()
        if (!ready.isCompleted && clean.trimStart().startsWith(prefix)) {
            HarnessLaunchLine.parse(clean)?.token?.let { redactor.remember(it) }
            ready.complete(clean.trim())
        }
        val safe = redactor.redact(clean)
        log.append(safe)
        synchronized(tail) {
            tail.addLast(safe)
            while (tail.size > TAIL_LINES) tail.removeFirst()
        }
    }

    companion object {
        const val TAIL_LINES = 40
        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    }
}
