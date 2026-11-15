package com.labteto.dshmobile.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/** The app's own guest commands: output, redaction, timeout, cancel, and staying apart from the harness. */
class GuestCommandRunnerTest {

    private class FakeProcess : HarnessProcess {
        val lines = Channel<String>(Channel.UNLIMITED)
        val exit = CompletableDeferred<Int>()
        override suspend fun readLine(): String? = lines.receiveCatching().getOrNull()
        override suspend fun awaitExit(): Int = exit.await()
        override val isAlive get() = !exit.isCompleted
        fun end(code: Int) {
            lines.close()
            exit.complete(code)
        }
    }

    /** proot 4000 with git 4001 under it; SIGKILL to proot ends the process. */
    private class Table(val process: FakeProcess) : ProcessTable {
        val signals: MutableList<Pair<Int, Int>> = Collections.synchronizedList(ArrayList())
        val alive = linkedMapOf(4000 to ProcInfo(4000, 1000, 1, "/lib/libproot.so"), 4001 to ProcInfo(4001, 4000, 2, "/usr/bin/git"))
        override val selfPid = 1000
        override fun snapshot() = alive.values.toList()
        override fun isAlive(pid: Int) = pid in alive
        override fun signal(pid: Int, signal: Int) {
            signals += pid to signal
            if (signal == ProcessTable.SIGKILL) {
                alive.remove(pid)
                if (pid == 4000) process.end(137)
            }
        }
    }

    private val command = GuestCommand(
        argv = listOf("/usr/bin/git", "clone", "--", "https://example.com/r", "/workspaces/x"),
        environment = mapOf("GIT_TERMINAL_PROMPT" to "0"),
        secretEnvironment = mapOf("GIT_CONFIG_VALUE_0" to "Authorization: Basic c2VjcmV0VG9rZW4="),
        workingDirectory = "/workspaces",
        redact = listOf("secretToken", "c2VjcmV0VG9rZW4="),
    )

    private class Launch(val argv: List<String>, val env: Map<String, String>, val removed: Set<String>)

    private fun runner(process: FakeProcess, table: ProcessTable, launches: MutableList<Launch>, installed: Boolean = true) = GuestCommandRunner(
        launcher = ProcessLauncher { argv, env, removed, _ ->
            launches += Launch(argv, env, removed)
            process
        },
        procs = table,
        build = { cmd -> if (!installed) null else ProotCommand(listOf("/system/bin/sh", "-c", "x", "sh", "/lib/libproot.so") + cmd.argv, cmd.environment + cmd.secretEnvironment, setOf("LD_PRELOAD")) },
        workDir = File("."),
        inheritedEnvironment = { setOf("ANDROID_DATA", "PATH", "BOOTCLASSPATH") },
    )

    @Test
    fun `output is streamed line by line, redacted, and the pid line is not output`() = runTest {
        val process = FakeProcess()
        val launches = ArrayList<Launch>()
        val r = runner(process, Table(process), launches)
        val seen = ArrayList<String>()
        val result = async { r.run(command, 60_000) { seen += it } }
        process.lines.send("4000")
        process.lines.send("Cloning into '/workspaces/x'...")
        process.lines.send("fatal: Authentication failed for secretToken at c2VjcmV0VG9rZW4=")
        yield()
        delay(10)
        assertEquals(setOf(4000), r.activePids)
        assertEquals(1, r.running.value)
        process.end(128)
        val outcome = result.await() as GuestCommandRunner.Outcome.Exited
        assertEquals(128, outcome.code)
        assertEquals(listOf("Cloning into '/workspaces/x'...", "fatal: Authentication failed for [redacted] at [redacted]"), seen)
        assertEquals(seen, outcome.tail)
        assertFalse(seen.any { "secretToken" in it })
        assertTrue(r.activePids.isEmpty())
        assertEquals(0, r.running.value)
        // Nothing of Android's environment reaches the guest; the fixed removals stay too.
        assertTrue(launches.single().removed.containsAll(setOf("ANDROID_DATA", "PATH", "BOOTCLASSPATH", "LD_PRELOAD")))
    }

    @Test
    fun `a command that runs too long is killed with its children`() = runTest {
        val process = FakeProcess()
        val table = Table(process)
        val r = runner(process, table, ArrayList())
        val result = async { r.run(command, 5_000) { } }
        process.lines.send("4000")
        val outcome = result.await()
        assertTrue(outcome is GuestCommandRunner.Outcome.TimedOut)
        assertTrue(4001 to ProcessTable.SIGKILL in table.signals)
        assertTrue(4000 to ProcessTable.SIGKILL in table.signals)
        assertFalse(process.isAlive)
        assertTrue(r.activePids.isEmpty())
    }

    @Test
    fun `cancelling the caller kills the tree`() = runTest {
        val process = FakeProcess()
        val table = Table(process)
        val r = runner(process, table, ArrayList())
        val job = launch { r.run(command, 600_000) { } }
        process.lines.send("4000")
        delay(100)
        job.cancel()
        job.join()
        assertTrue(4001 to ProcessTable.SIGKILL in table.signals)
        assertTrue(4000 to ProcessTable.SIGKILL in table.signals)
        assertEquals(0, r.running.value)
    }

    @Test
    fun `no runtime and a failed launch are reported, not thrown`() = runTest {
        val process = FakeProcess()
        assertEquals(GuestCommandRunner.Outcome.NotInstalled, runner(process, Table(process), ArrayList(), installed = false).run(command, 1_000) { })
        val failing = GuestCommandRunner(
            launcher = { _, _, _, _ -> throw java.io.IOException("exec failed") },
            procs = Table(process),
            build = { ProotCommand(it.argv, emptyMap(), emptySet()) },
            workDir = File("."),
            inheritedEnvironment = { emptySet() },
        )
        assertEquals(GuestCommandRunner.Outcome.LaunchFailed("exec failed"), failing.run(command, 1_000) { })
        assertEquals(0, failing.running.value)
    }

    @Test
    fun `the command's text never shows its secrets`() {
        assertFalse(command.toString().contains("c2VjcmV0VG9rZW4="))
        assertTrue(command.toString().contains("GIT_CONFIG_VALUE_0"))
        assertEquals("a [redacted] b", GuestCommandRunner.redact("a secretToken b", listOf("secretToken")))
        // Too short to be a secret worth a blind replace.
        assertEquals("abc", GuestCommandRunner.redact("abc", listOf("b")))
    }

    @Test
    fun `the guest command line wraps the program without a script of its own`() {
        val layout = RuntimeLayout(File("/rt"), File("/cache"))
        val record = InstalledRecord(
            releaseId = "r", sequence = 1, abi = "x86_64", harnessVersion = "1", nodeVersion = "24",
            base = LayerRecord("b", "base-b", "1"), harness = LayerRecord("h", "harness-h", "2"),
            entrypoint = "dsh", readinessPrefix = "dsh web:", fakeProc = emptyList(), resolvConf = "/etc/resolv.conf",
        )
        val proot = ProotCommand.buildGuest(File("/native"), layout, record, command)
        val i = proot.argv.indexOf(ProotCommand.GUEST_WRAPPER)
        assertEquals(listOf("/bin/sh", "-c", ProotCommand.GUEST_WRAPPER, "sh"), proot.argv.subList(i - 2, i + 2))
        assertEquals(command.argv, proot.argv.subList(i + 2, proot.argv.size))
        assertEquals("umask 022; exec \"\$@\"", ProotCommand.GUEST_WRAPPER)
        assertEquals("/workspaces", proot.argv[proot.argv.indexOf("-w") + 1])
        assertTrue("${File("/rt/workspaces").path}:/workspaces" in proot.argv)
        assertEquals("/root", proot.environment["HOME"])
        assertEquals("0", proot.environment["GIT_TERMINAL_PROMPT"])
        assertFalse(proot.argv.any { "c2VjcmV0VG9rZW4=" in it })
        assertFalse(proot.toString().contains("c2VjcmV0VG9rZW4="))
    }

    @Test
    fun `the harness sweep leaves the app's own guest commands alone`() = runTest {
        val table = object : ProcessTable {
            val alive = linkedMapOf(
                777 to ProcInfo(777, 1, 9, "/data/app/x/lib/x86_64/libproot.so"),
                4000 to ProcInfo(4000, 1000, 10, "/data/app/x/lib/x86_64/libproot.so"),
                4100 to ProcInfo(4100, 1000, 11, "/data/app/x/lib/x86_64/libproot.so"),
            )
            val signalled = ArrayList<Int>()
            override val selfPid = 1000
            override fun snapshot() = alive.values.toList()
            override fun isAlive(pid: Int) = pid in alive
            override fun signal(pid: Int, signal: Int) {
                signalled += pid
                alive.remove(pid)
            }
        }
        val log = RotatingLog(File.createTempFile("guest", ".log").also { it.deleteOnExit() })
        val sup = HarnessSupervisor(
            backgroundScope,
            { _, _, _, _ -> error("no start") },
            table,
            log,
            notHarnesses = { setOf(4000) },
            // 4100 has not printed its pid yet; it is a live child of this process all the same.
            guestsActive = { true },
        )
        sup.cleanupStale()
        assertEquals(listOf(777), table.signalled.distinct())
    }
}
