package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.termux.StartSignIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        override val modulePack: Boolean get() = fastStart
    }

    @Volatile
    private var fastStart = true

    private lateinit var engine: RuntimeEngine

    @Before
    fun setUp() {
        fx = RuntimeFixture(tmp)
        engine = newEngine()
    }

    private fun newEngine(pacer: ProgressPacer = ProgressPacer()): RuntimeEngine {
        val supervisor = HarnessSupervisor(
            scope,
            launcher,
            table,
            RotatingLog(File(fx.layout.logs, "harness.log")),
            HarnessSupervisor.Timing(readyTimeoutMs = 10_000, termGraceMs = 200, quitGraceMs = 500, killGraceMs = 500, pollMs = 20, drainMs = 500),
        )
        return RuntimeEngine(scope, fx.layout, fx.installer(), supervisor, fx.fs, platform, signIn = { line, port ->
            signIns += line to port
            StartSignIn.SIGNED_IN
        }, progressPacer = pacer)
    }

    /** Install a release whose harness layer has [files] files, and return every state published on the way. */
    private fun statesDuringInstall(files: Int, pacer: ProgressPacer): List<RuntimeState> {
        engine = newEngine(pacer)
        val seen = java.util.Collections.synchronizedList(mutableListOf<RuntimeState>())
        // Unconfined: the collector runs on the publishing thread, so no state is conflated away.
        val watcher = scope.launch(Dispatchers.Unconfined) { engine.state.collect { seen += it } }
        // Not 7 + files: the layer's own header and directories count too, and the manifest says so.
        val harness = fx.harnessLayer("1.0.0") { repeat(files) { file("opt/dsh/f$it", "x") } }.let { it.copy(entries = it.entries + files) }
        fx.publish(fx.baseLayer(), harness, sequence = 100)
        assertTrue(engine.install())
        await("installed") { it is RuntimeState.Stopped }
        watcher.cancel()
        return seen.toList()
    }

    private fun List<RuntimeState>.unpacking(layer: String) =
        filterIsInstance<RuntimeState.Unpacking>().filter { it.progress.layer == layer }

    @Test
    fun `without pacing every progress event of an unpack becomes a state`() {
        val states = statesDuringInstall(1200, ProgressPacer(minIntervalMs = 0))
        // The start (0 entries), every 256 entries, and the end.
        assertEquals(6, states.unpacking(LAYER_HARNESS).size)
    }

    @Test
    fun `an unpack publishes its first and last progress and the pace in between`() {
        // A clock that never moves: nothing in between is due, only the first and the last.
        val states = statesDuringInstall(1200, ProgressPacer(minIntervalMs = 60_000, nowMs = { 0L }))
        val harness = states.unpacking(LAYER_HARNESS)
        assertEquals(2, harness.size)
        assertEquals(0, harness.first().progress.entriesDone)
        assertEquals(harness.last().progress.entriesTotal, harness.last().progress.entriesDone)
        // The step changes are never held back: every layer still shows up, in order.
        val steps = states.map { it::class.simpleName }.distinct()
        assertTrue(steps.indexOf("Downloading") < steps.indexOf("Verifying"))
        assertTrue(steps.indexOf("Verifying") < steps.indexOf("Unpacking"))
        assertTrue(states.unpacking(LAYER_BASE).isNotEmpty())
    }

    @Test
    fun `a download publishes its first and last progress and the pace in between`() {
        val states = statesDuringInstall(10, ProgressPacer(minIntervalMs = 60_000, nowMs = { 0L }))
        val downloads = states.filterIsInstance<RuntimeState.Downloading>().filter { it.progress.layer == LAYER_HARNESS }
        // The first report (nothing yet), at most the end; never a state per 256 KB.
        assertTrue("states: ${downloads.map { it.progress.bytesDone }}", downloads.size in 1..2)
        assertEquals(downloads.last().progress.bytesTotal, downloads.last().progress.bytesDone)
    }

    @After
    fun tearDown() {
        scope.cancel()
        fx.close()
    }

    /** Whether a layer directory for [prefix] (`harness-<id>`, then a hash suffix) exists. */
    private fun layerDir(prefix: String): Boolean =
        fx.layout.root.listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("$prefix-") }

    private fun await(what: String, predicate: (RuntimeState) -> Boolean): RuntimeState = runBlocking {
        try {
            withTimeout(30_000) { engine.state.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("never reached $what; last state ${engine.state.value}", e)
        }
    }

    @Test
    fun `a guest command needs an install and holds off updates and removal while it runs`() {
        assertFalse("nothing installed", engine.beginGuestCommand())
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100)
        assertTrue(engine.install())
        await("installed") { it is RuntimeState.Stopped }

        assertTrue(engine.beginGuestCommand())
        fx.publish(base, fx.harnessLayer("2.0.0"), sequence = 101)
        assertFalse("no update under a running clone", engine.install())
        assertFalse("no removal under a running clone", engine.uninstall())
        // Starting and stopping the harness itself is fine next to it.
        assertTrue(engine.start(3080))
        await("Running") { it is RuntimeState.Running }
        assertTrue(engine.stop())
        await("Stopped") { it is RuntimeState.Stopped }
        engine.endGuestCommand()

        assertTrue(engine.install())
        await("updated") { it is RuntimeState.Stopped && it.installed.harnessVersion == "2.0.0" }
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
        assertFalse(layerDir("harness-dsh-2.0.0"))
        assertTrue(layerDir("harness-dsh-1.0.0"))
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
        assertFalse(layerDir("harness-dsh-1.0.0"))
        assertEquals(1, running.size)
    }

    @Test
    fun `a failed start after an update while running rolls back and says why`() {
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100)
        engine.install()
        await("installed") { it is RuntimeState.Stopped }
        engine.start(3080)
        await("running v1") { it is RuntimeState.Running }

        // A base and harness update whose harness cannot start: the whole release comes back.
        fx.publish(fx.baseLayer("trixie-test-r2"), fx.harnessLayer("4.0.0"), sequence = 103)
        broken += "4.0.0"
        assertTrue(engine.install())
        val back = await("running v1 again") { it is RuntimeState.Running && it.rolledBack != null } as RuntimeState.Running
        assertEquals("1.0.0", back.harnessVersion)
        assertEquals("test-100", back.installed.releaseId)
        assertEquals("test-103", back.rolledBack!!.failedReleaseId)
        val outcome = runBlocking { withTimeout(5_000) { engine.outcomes.first() } } as UpdateOutcome.RolledBack
        assertEquals("test-103", outcome.to.releaseId)
        assertEquals("test-100", outcome.from.releaseId)
        assertTrue((outcome.reason as RuntimeFailure.ExitedBeforeReady).logTail.contains("missing-native.node"))
        assertFalse(layerDir("base-trixie-test-r2"))
        assertTrue(layerDir("base-trixie-test-r1"))
    }

    @Test
    fun `go back installs the previous release while running and the next update is offered again`() {
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100, dir = "r100")
        fx.publish(base, fx.harnessLayer("2.0.0"), sequence = 101, previous = "r100" to 100L)
        engine.install()
        await("installed v2") { it is RuntimeState.Stopped }
        engine.start(3080)
        val up = await("running v2") { it is RuntimeState.Running } as RuntimeState.Running
        assertEquals(100, up.installed.previousRelease!!.sequence)

        assertTrue(engine.goBack())
        val going = await("going back") { it is RuntimeState.Updating && it.target != null } as RuntimeState.Updating
        assertTrue(going.goingBack)
        assertEquals("test-100", going.target!!.releaseId)
        val back = await("running v1") { it is RuntimeState.Running && it.harnessVersion == "1.0.0" } as RuntimeState.Running
        assertEquals(100, back.installed.sequence)
        assertNull(back.installed.previousRelease)
        val outcome = runBlocking { withTimeout(5_000) { engine.outcomes.first() } } as UpdateOutcome.Applied
        assertTrue(outcome.wentBack)
        assertEquals("test-101", outcome.from.releaseId)
        // No previous pointer now: nothing further to go back to.
        assertFalse(engine.goBack())
        // The newer release is an ordinary update again.
        val check = runBlocking { engine.checkForUpdate() }
        assertEquals(101, (check as RuntimeInstaller.CheckResult.Available).manifest.sequence)
    }

    @Test
    fun `an update that cannot be fetched reports a failed outcome and the harness keeps running`() {
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100)
        engine.install()
        await("installed") { it is RuntimeState.Stopped }
        engine.start(3080)
        await("running") { it is RuntimeState.Running }

        val v5 = fx.harnessLayer("5.0.0")
        fx.publish(base, v5, sequence = 105)
        fx.release.files.remove("rel/assets/${v5.file}")
        assertTrue(engine.install())
        val outcome = runBlocking { withTimeout(15_000) { engine.outcomes.first { it is UpdateOutcome.Failed } } } as UpdateOutcome.Failed
        assertEquals("test-105", outcome.to.releaseId)
        assertTrue(outcome.reason is RuntimeFailure.Network)
        val still = await("still running") { it is RuntimeState.Running } as RuntimeState.Running
        assertEquals("1.0.0", still.harnessVersion)
    }

    @Test
    fun `operations in the wrong state are refused, not queued`() {
        assertFalse(engine.start(3080)) // nothing installed
        assertFalse(engine.stop())
        assertFalse(engine.restart())
    }

    /**
     * Fires [trigger], and in the very step that publishes a state matching [terminal] (the collector
     * is unconfined, so it runs on the publishing thread, inside the operation) calls [next].
     * Returns what [next] answered: true when the engine was ready for it the instant the state was
     * observable. This is the worst case of "start as soon as the state flips", with no luck needed.
     */
    private fun acceptedAtOnce(terminal: (RuntimeState) -> Boolean, trigger: () -> Boolean, next: () -> Boolean): Boolean {
        val answer = CompletableDeferred<Boolean>()
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            engine.state.first(terminal)
            answer.complete(next())
        }
        assertTrue(trigger())
        try {
            return runBlocking { withTimeout(30_000) { answer.await() } }
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun `a state that ends an install can be followed at once by a start`() {
        fx.publish(fx.baseLayer(), fx.harnessLayer("1.0.0"), sequence = 100)
        assertTrue(acceptedAtOnce({ it is RuntimeState.Stopped }, { engine.install() }, { engine.start(3080) }))
        await("running") { it is RuntimeState.Running }
    }

    @Test
    fun `a state that ends a start or a stop can be followed at once by the next one`() {
        fx.publish(fx.baseLayer(), fx.harnessLayer("1.0.0"), sequence = 100)
        engine.install()
        await("installed") { it is RuntimeState.Stopped }
        repeat(10) {
            assertTrue(acceptedAtOnce({ it is RuntimeState.Running }, { engine.start(3080) }, { engine.stop() }))
            assertTrue(acceptedAtOnce({ it is RuntimeState.Stopped }, { true }, { engine.start(3080) }))
            await("running again") { it is RuntimeState.Running }
            assertTrue(acceptedAtOnce({ it is RuntimeState.Stopped }, { engine.stop() }, { true }))
        }
    }

    @Test
    fun `a state that ends a failed start can be followed at once by another start`() {
        fx.publish(fx.baseLayer(), fx.harnessLayer("1.0.0"), sequence = 100)
        broken += "1.0.0"
        engine.install()
        await("installed") { it is RuntimeState.Stopped }
        assertTrue(acceptedAtOnce({ it is RuntimeState.Failed }, { engine.start(3080) }, { engine.start(3080) }))
    }

    @Test
    fun `a state that ends an uninstall can be followed at once by an install`() {
        fx.publish(fx.baseLayer(), fx.harnessLayer("1.0.0"), sequence = 100)
        engine.install()
        await("installed") { it is RuntimeState.Stopped }
        assertTrue(acceptedAtOnce({ it is RuntimeState.NotInstalled }, { engine.uninstall() }, { engine.install() }))
        await("installed again") { it is RuntimeState.Stopped }
    }

    @Test
    fun `a state that ends an update or a go back can be followed at once by a stop`() {
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100, dir = "r100")
        fx.publish(base, fx.harnessLayer("2.0.0"), sequence = 101, previous = "r100" to 100L)
        engine.install()
        await("installed v2") { it is RuntimeState.Stopped }
        engine.start(3080)
        await("running v2") { it is RuntimeState.Running }
        assertTrue(acceptedAtOnce({ it is RuntimeState.Running && it.harnessVersion == "1.0.0" }, { engine.goBack() }, { engine.stop() }))
        await("stopped") { it is RuntimeState.Stopped }

        // An ordinary update while running: the newer release comes back as one.
        engine.start(3080)
        await("running v1") { it is RuntimeState.Running }
        assertTrue(acceptedAtOnce({ it is RuntimeState.Running && it.harnessVersion == "2.0.0" }, { engine.install() }, { engine.stop() }))
        await("stopped again") { it is RuntimeState.Stopped }
    }

    @Test
    fun `a state that ends a failed update can be followed at once by a start`() {
        val base = fx.baseLayer()
        fx.publish(base, fx.harnessLayer("1.0.0"), sequence = 100)
        engine.install()
        await("installed") { it is RuntimeState.Stopped }
        val v5 = fx.harnessLayer("5.0.0")
        fx.publish(base, v5, sequence = 105)
        fx.release.files.remove("rel/assets/${v5.file}")
        assertTrue(acceptedAtOnce({ it is RuntimeState.Failed }, { engine.install() }, { engine.start(3080) }))
        await("running") { it is RuntimeState.Running }
    }

    @Test
    fun `the fast start switch reaches the guest environment on the next start`() {
        fx.publish(fx.baseLayer(), fx.harnessLayer("1.0.0"), sequence = 100)
        engine.install()
        await("installed") { it is RuntimeState.Stopped }

        fastStart = false
        assertTrue(engine.start(3080))
        await("running without the pack") { it is RuntimeState.Running }
        assertTrue(launches.last().contains("DSH_RUNTIME_MODULE_PACK=0"))
        engine.stop()
        await("stopped") { it is RuntimeState.Stopped }

        fastStart = true
        assertTrue(engine.start(3080))
        await("running with the pack") { it is RuntimeState.Running }
        assertFalse(launches.last().any { it.startsWith("DSH_RUNTIME_MODULE_PACK") })
    }
}
