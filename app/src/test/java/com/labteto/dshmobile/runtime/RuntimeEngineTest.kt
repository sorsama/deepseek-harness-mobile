package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.termux.StartSignIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The engine end to end over a fake proot: install, start, stop, update, and the update trial. */
class RuntimeEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var fx: RuntimeFixture
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Harness versions whose start fails before readiness. */
    private val broken = mutableSetOf<String>()
    private val signIns = mutableListOf<Pair<String, Int>>()
    private val launches = mutableListOf<List<String>>()

    private class FakeProot : HarnessProcess {
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

    private val running = ConcurrentHashMap<Int, FakeProot>()
    private val nextPid = AtomicInteger(5000)

    /** proot as the engine sees it: SIGQUIT or SIGKILL ends it; it has no guest child here. */
    private val table = object : ProcessTable {
        override val selfPid = 1000
        override fun snapshot() = running.keys.map { ProcInfo(it, selfPid, 0, "/fake/lib/libproot.so") }
        override fun isAlive(pid: Int) = running.containsKey(pid)
        override fun signal(pid: Int, signal: Int) {
            if (signal == ProcessTable.SIGQUIT || signal == ProcessTable.SIGKILL) running.remove(pid)?.end(0)
        }
    }

    private val launcher = ProcessLauncher { argv, env, _, _ ->
        launches += argv
        assertTrue(env.containsKey("PROOT_LOADER"))
        val pid = nextPid.incrementAndGet()
        val proot = FakeProot()
        val harnessBind = argv.first { it.endsWith(":/opt/dsh") }
        proot.lines.trySend("$pid")
        if (broken.any { harnessBind.contains("harness-dsh-$it") }) {
            proot.lines.trySend("Error: Cannot find module 'missing-native.node'")
            proot.end(1)
        } else {
            running[pid] = proot
            proot.lines.trySend("dsh web: http://127.0.0.1:3080/?token=engineTestToken123")
        }
        proot
    }

    private val platform = object : RuntimeEngine.Platform {
        override val unsupported: RuntimeUnsupported? = null
        override val nativeLibraryDir = File("/fake/lib")
        override fun dnsServers() = listOf("10.0.2.3")
        override val cpuCount = 4
        override fun portFree(port: Int) = true
        override fun keepAlive() = Unit
    }

    private lateinit var engine: RuntimeEngine

    @Before
    fun setUp() {
        fx = RuntimeFixture(tmp)
        val supervisor = HarnessSupervisor(
            scope,
            launcher,
            table,
            RotatingLog(File(fx.layout.logs, "harness.log")),
            HarnessSupervisor.Timing(readyTimeoutMs = 10_000, termGraceMs = 200, quitGraceMs = 500, killGraceMs = 500, pollMs = 20, drainMs = 500),
        )
        engine = RuntimeEngine(scope, fx.layout, fx.installer(), supervisor, fx.fs, platform, signIn = { line, port ->
            signIns += line to port
            StartSignIn.SIGNED_IN
        })
    }

    @After
    fun tearDown() {
        scope.cancel()
        fx.close()
    }

    private fun await(what: String, predicate: (RuntimeState) -> Boolean): RuntimeState = runBlocking {
        try {
            withTimeout(30_000) { engine.state.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("never reached $what; last state ${engine.state.value}", e)
        }
    }

    @Test
    fun `install, start, stop, then an update whose harness fails is rolled back and the old one runs`() {
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100)
        assertEquals(RuntimeState.NotInstalled, engine.state.value)

        assertTrue(engine.install())
        val installed = await("Stopped after install") { it is RuntimeState.Stopped } as RuntimeState.Stopped
        assertEquals("1.0.0", installed.installed.harnessVersion)

        assertTrue(engine.start(3080))
        val up = await("Running") { it is RuntimeState.Running } as RuntimeState.Running
        assertEquals(StartSignIn.SIGNED_IN, up.signIn)
        assertNull(up.rolledBack)
        assertEquals(listOf("dsh web: http://127.0.0.1:3080/?token=engineTestToken123" to 3080), signIns)
        // Per-start files: device DNS, the CPU count, and the home skeleton from the base.
        assertEquals(
            "# Written by DSH Mobile at harness start from the device's DNS servers.\nnameserver 10.0.2.3\n",
            File(fx.layout.run, "resolv.conf").readText(),
        )
        assertEquals(4, File(fx.layout.run, "proc-stat").readLines().count { Regex("^cpu\\d+ ").containsMatchIn(it) })
        assertEquals("# bashrc\n", File(fx.layout.home, ".bashrc").readText())
        assertFalse(File(fx.layout.logs, "harness.log").readText().contains("engineTestToken123"))

        assertTrue(engine.stop())
        await("Stopped after stop") { it is RuntimeState.Stopped }
        assertTrue(running.isEmpty())

        // A harness-only update that cannot start.
        fx.publish(base, fx.harnessLayer("2.0.0"), sequence = 101)
        broken += "2.0.0"
        assertTrue(engine.install())
        val updated = await("Stopped on trial") { it is RuntimeState.Stopped && it.installed.harnessOnTrial } as RuntimeState.Stopped
        assertEquals("2.0.0", updated.installed.harnessVersion)

        assertTrue(engine.start(3080))
        val back = await("Running after rollback") { it is RuntimeState.Running } as RuntimeState.Running
        assertEquals("1.0.0", back.harnessVersion)
        assertEquals("dsh-1.0.0", back.installed.harnessId)
        assertFalse(back.installed.harnessOnTrial)
        val rolledBack = back.rolledBack!!
        assertEquals("2.0.0", rolledBack.failedVersion)
        val reason = rolledBack.reason as RuntimeFailure.ExitedBeforeReady
        assertEquals(1, reason.exitCode)
        assertTrue(reason.logTail.contains("missing-native.node"))
        assertFalse(File(fx.layout.root, "harness-dsh-2.0.0").exists())
        assertTrue(File(fx.layout.root, "harness-dsh-1.0.0").isDirectory)
        // Two launches for the trial start: the new harness, then the old one.
        assertTrue(launches[1].any { it.contains("harness-dsh-2.0.0") })
        assertTrue(launches[2].any { it.contains("harness-dsh-1.0.0") })
    }

    @Test
    fun `an update while running switches over and the new harness is committed once ready`() {
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100)
        engine.install()
        await("installed") { it is RuntimeState.Stopped }
        engine.start(3080)
        await("running v1") { it is RuntimeState.Running }

        fx.publish(base, fx.harnessLayer("3.0.0"), sequence = 102)
        assertTrue(engine.install())
        await("updating") { it is RuntimeState.Updating }
        val after = await("running v3") { it is RuntimeState.Running && it.harnessVersion == "3.0.0" } as RuntimeState.Running
        assertFalse(after.installed.harnessOnTrial)
        assertFalse(File(fx.layout.root, "harness-dsh-1.0.0").exists())
        assertEquals(1, running.size)
    }

    @Test
    fun `operations in the wrong state are refused, not queued`() {
        assertFalse(engine.start(3080)) // nothing installed
        assertFalse(engine.stop())
        assertFalse(engine.restart())
    }
}
