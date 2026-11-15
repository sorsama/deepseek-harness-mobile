package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.runtime.ProcessTable.Companion.SIGKILL
import com.labteto.dshmobile.runtime.ProcessTable.Companion.SIGQUIT
import com.labteto.dshmobile.runtime.ProcessTable.Companion.SIGTERM
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HarnessSupervisorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val token = "s3cretLaunchToken_0123456789abcdef"
    private val readyLine = "dsh web: http://127.0.0.1:3080/?token=$token"

    /** A proot that prints what the test feeds it and ends when the test (or a signal) says so. */
    private class FakeProcess : HarnessProcess {
        val lines = Channel<String>(Channel.UNLIMITED)
        val exit = CompletableDeferred<Int>()
        override suspend fun readLine(): String? = lines.receiveCatching().getOrNull()
        override suspend fun awaitExit(): Int = exit.await()
        override val isAlive: Boolean get() = !exit.isCompleted

        fun end(code: Int) {
            lines.close()
            exit.complete(code)
        }
    }

    /**
     * A process table where proot (4242) runs node (4243), which runs a tool (4244).
     *
     * [onSignal] decides what a signal does; the default is real proot: SIGTERM to proot is
     * ignored, SIGQUIT makes it kill its tracees and exit, SIGKILL kills.
     */
    private inner class FakeTable(private val scope: CoroutineScope, private val now: () -> Long, val process: FakeProcess?) : ProcessTable {
        val alive = linkedMapOf(
            4242 to ProcInfo(4242, 1000, 1, "/data/app/x/lib/x86_64/libproot.so"),
            4243 to ProcInfo(4243, 4242, 2, "node"),
            4244 to ProcInfo(4244, 4243, 3, "git"),
            1000 to ProcInfo(1000, 1, 0, "com.labteto.dshmobile.debug"),
        )
        val signals = mutableListOf<Triple<Long, Int, Int>>()
        var onSignal: (pid: Int, signal: Int) -> Unit = { pid, signal -> realProot(pid, signal) }

        override val selfPid = 1000
        override fun snapshot() = alive.values.toList()
        override fun isAlive(pid: Int) = pid in alive
        override fun signal(pid: Int, signal: Int) {
            signals += Triple(now(), pid, signal)
            onSignal(pid, signal)
        }

        fun killTree(afterMs: Long) {
            scope.launch {
                delay(afterMs)
                alive.keys.removeAll(listOf(4242, 4243, 4244))
                process?.end(0)
            }
        }

        fun realProot(pid: Int, signal: Int) {
            when {
                signal == SIGKILL && pid == 4242 -> killTree(10)
                signal == SIGKILL -> alive.remove(pid)
                signal == SIGQUIT && pid == 4242 -> killTree(50)
            }
        }
    }

    private fun supervisor(scope: TestScope, table: ProcessTable, launch: () -> HarnessProcess, timing: HarnessSupervisor.Timing = HarnessSupervisor.Timing()) =
        HarnessSupervisor(
            scope = scope.backgroundScope,
            launcher = { _, _, _, _ -> launch() },
            procs = table,
            log = RotatingLog(File(tmp.root, "logs/harness.log")),
            timing = timing,
            clock = { scope.testScheduler.currentTime },
        )

    private val command = ProotCommand(listOf("sh"), emptyMap(), emptySet())

    /** Let the supervisor's background work (reader, exit watcher) catch up; advanceUntilIdle skips it. */
    private fun TestScope.settle() {
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
    }

    private suspend fun HarnessSupervisor.startOn(process: FakeProcess? = null, onExit: (Int, String) -> Unit = { _, _ -> }) =
        start(command, tmp.root, "dsh web: http://127.0.0.1:", 3080, onExit)

    @Test
    fun `readiness hands over the launch line and keeps the token out of the log`() = runTest {
        val process = FakeProcess()
        val table = FakeTable(backgroundScope, { testScheduler.currentTime }, process)
        val sup = supervisor(this, table, { process })
        process.lines.trySend("4242")
        process.lines.trySend("Loading plugins...")
        process.lines.trySend("\u001B[32m$readyLine\u001B[0m")
        process.lines.trySend("open http://127.0.0.1:3080/?token=$token&x=1 in a browser; token is $token")

        val outcome = sup.startOn() as HarnessSupervisor.StartOutcome.Ready
        assertEquals(readyLine, outcome.launchLine)
        assertEquals(3080, outcome.port)
        assertEquals(4242, outcome.pid)
        assertFalse(outcome.toString().contains(token))

        settle()
        val log = File(tmp.root, "logs/harness.log").readText()
        assertFalse("the log must never hold the token:\n$log", log.contains(token))
        assertTrue(log.contains("token=<redacted>"))
        assertTrue(log.contains("Loading plugins..."))
        assertFalse(sup.recentOutput().contains(token))
        // The pid line is the wrapper's, not the harness's.
        assertFalse(sup.recentOutput().lines().contains("4242"))
    }

    @Test
    fun `an exit before readiness reports the code and the end of the output`() = runTest {
        val process = FakeProcess()
        val sup = supervisor(this, FakeTable(backgroundScope, { testScheduler.currentTime }, process), { process })
        process.lines.trySend("4242")
        process.lines.trySend("Error: Cannot find module '/opt/dsh/lib/bin.js'")
        process.end(1)
        val outcome = sup.startOn() as HarnessSupervisor.StartOutcome.Exited
        assertEquals(1, outcome.exitCode)
        assertTrue(outcome.tail.contains("Cannot find module"))
        assertFalse(sup.isRunning)
    }

    @Test
    fun `no readiness line in time stops the harness and reports a timeout`() = runTest {
        val process = FakeProcess()
        val table = FakeTable(backgroundScope, { testScheduler.currentTime }, process)
        table.onSignal = { pid, signal -> if (signal == SIGTERM && pid == 4243) table.killTree(200) else table.realProot(pid, signal) }
        val sup = supervisor(this, table, { process }, HarnessSupervisor.Timing(readyTimeoutMs = 60_000))
        process.lines.trySend("4242")
        process.lines.trySend("still loading")

        val outcome = sup.startOn() as HarnessSupervisor.StartOutcome.TimedOut
        assertTrue(outcome.tail.contains("still loading"))
        assertEquals(listOf(Triple(60_000L, 4243, SIGTERM)), table.signals)
        assertFalse(sup.isRunning)
    }

    @Test
    fun `stop sends SIGTERM to the guest and nothing more when it goes`() = runTest {
        val process = FakeProcess()
        val table = FakeTable(backgroundScope, { testScheduler.currentTime }, process)
        table.onSignal = { pid, signal -> if (signal == SIGTERM && pid == 4243) table.killTree(300) else table.realProot(pid, signal) }
        val sup = supervisor(this, table, { process })
        process.lines.trySend("4242")
        process.lines.trySend(readyLine)
        sup.startOn()
        val t0 = testScheduler.currentTime

        val report = sup.stop()!!
        assertEquals(listOf(Triple(t0, 4243, SIGTERM)), table.signals)
        assertEquals(listOf("SIGTERM guest [4243]"), report.steps)
        assertEquals(emptyList<Int>(), report.leftover)
        assertEquals(300L, report.elapsedMs)
        assertFalse(sup.isRunning)
    }

    @Test
    fun `a guest that ignores SIGTERM gets SIGQUIT to proot after the grace, then SIGKILL`() = runTest {
        val process = FakeProcess()
        val table = FakeTable(backgroundScope, { testScheduler.currentTime }, process)
        // Nothing reacts to SIGTERM or SIGQUIT; only SIGKILL works.
        table.onSignal = { pid, signal -> if (signal == SIGKILL) table.realProot(pid, signal) }
        val sup = supervisor(this, table, { process })
        process.lines.trySend("4242")
        process.lines.trySend(readyLine)
        sup.startOn()
        val t0 = testScheduler.currentTime

        val report = sup.stop()!!
        val relative = table.signals.map { (t, pid, sig) -> Triple(t - t0, pid, sig) }
        assertEquals(Triple(0L, 4243, SIGTERM), relative[0])
        assertEquals(Triple(5_000L, 4242, SIGQUIT), relative[1])
        // SIGKILL to the whole tree 3 s later: descendants and proot itself.
        val kills = relative.drop(2)
        assertTrue(kills.all { it.first == 8_000L && it.third == SIGKILL })
        assertEquals(setOf(4242, 4243, 4244), kills.map { it.second }.toSet())
        assertEquals(3, report.steps.size)
        assertTrue(report.steps[1].startsWith("SIGQUIT"))
        assertTrue(report.steps[2].startsWith("SIGKILL"))
        assertEquals(emptyList<Int>(), report.leftover)
    }

    @Test
    fun `SIGQUIT is enough when proot honours it`() = runTest {
        val process = FakeProcess()
        val table = FakeTable(backgroundScope, { testScheduler.currentTime }, process)
        val sup = supervisor(this, table, { process }) // real proot: ignores SIGTERM to itself, node ignores it too here
        table.onSignal = { pid, signal -> if (signal != SIGTERM) table.realProot(pid, signal) }
        process.lines.trySend("4242")
        process.lines.trySend(readyLine)
        sup.startOn()
        val report = sup.stop()!!
        assertEquals(listOf(SIGTERM, SIGQUIT), table.signals.map { it.third })
        assertEquals(2, report.steps.size)
    }

    @Test
    fun `a harness left by an earlier app process is found and stopped`() = runTest {
        // No process of ours: proot 777 was re-parented to init when the old app process died.
        val table = object : ProcessTable {
            val alive = linkedMapOf(
                777 to ProcInfo(777, 1, 9, "/data/app/~~old==/com.labteto.dshmobile.debug-1/lib/x86_64/libproot.so"),
                778 to ProcInfo(778, 777, 10, "node"),
                900 to ProcInfo(900, 1, 5, "/system/bin/sh"),
            )
            val signals = mutableListOf<Pair<Int, Int>>()
            override val selfPid = 1000
            override fun snapshot() = alive.values.toList()
            override fun isAlive(pid: Int) = pid in alive
            override fun signal(pid: Int, signal: Int) {
                signals += pid to signal
                if (pid == 778 && signal == SIGTERM) backgroundScope.launch {
                    delay(250)
                    alive.remove(778)
                    alive.remove(777)
                }
            }
        }
        val sup = supervisor(this, table, { error("not started") })
        val reports = sup.cleanupStale()
        assertEquals(1, reports.size)
        assertEquals(777, reports[0].pid)
        assertEquals(listOf(778 to SIGTERM), table.signals)
        assertEquals(emptyList<Int>(), reports[0].leftover)
        assertTrue(table.isAlive(900))
    }

    @Test
    fun `an exit after readiness is reported once, and a stop is not an exit`() = runTest {
        val process = FakeProcess()
        val table = FakeTable(backgroundScope, { testScheduler.currentTime }, process)
        val sup = supervisor(this, table, { process })
        val exits = mutableListOf<Int>()
        process.lines.trySend("4242")
        process.lines.trySend(readyLine)
        sup.startOn(onExit = { code, _ -> exits += code })
        process.lines.trySend("FATAL: out of memory")
        process.end(137)
        settle()
        assertEquals(listOf(137), exits)
        assertFalse(sup.isRunning)

        val second = FakeProcess()
        val table2 = FakeTable(backgroundScope, { testScheduler.currentTime }, second)
        table2.onSignal = { pid, signal -> if (signal == SIGTERM && pid == 4243) table2.killTree(100) }
        val sup2 = supervisor(this, table2, { second })
        val exits2 = mutableListOf<Int>()
        second.lines.trySend("4242")
        second.lines.trySend(readyLine)
        sup2.startOn(onExit = { code, _ -> exits2 += code })
        sup2.stop()
        settle()
        assertEquals(emptyList<Int>(), exits2)
    }

    @Test
    fun `the redactor masks token parameters and remembered secrets`() {
        val r = TokenRedactor()
        assertEquals("GET /?token=<redacted> 303", r.redact("GET /?token=abcDEF123456 303"))
        r.remember("abcDEF123456")
        assertEquals("bare <redacted> here", r.redact("bare abcDEF123456 here"))
        assertEquals("nothing to hide", r.redact("nothing to hide"))
    }

    @Test
    fun `the log rotates at its cap and keeps one previous file`() {
        val file = File(tmp.root, "rot/harness.log")
        val log = RotatingLog(file, maxBytes = 1000)
        repeat(100) { log.append("line %03d %s".format(it, "x".repeat(40))) }
        log.close()
        assertTrue(file.length() <= 1000)
        assertTrue(File(file.parentFile, "harness.log.1").length() <= 1000)
        assertTrue(log.tail(3).endsWith("line 099 " + "x".repeat(40)))
        assertTrue(log.sizeBytes() <= 2000)
    }
}
