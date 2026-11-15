package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.termux.StartSignIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeUpdateCoordinatorTest {

    private class MapStore : RuntimeUpdatePreferences.KeyValue {
        val values = mutableMapOf<String, Any?>()
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
        override fun putLong(key: String, value: Long) { values[key] = value }
        override fun getString(key: String) = values[key] as? String
        override fun putString(key: String, value: String?) { values[key] = value }
    }

    private val real = RuntimeManifest.parse(
        requireNotNull(javaClass.getResourceAsStream("/runtime/release-2026.11.14-1.manifest.json")).use { it.readBytes() },
    )

    private fun available(sequence: Long, harness: String = "0.2.3"): RuntimeInstaller.CheckResult.Available {
        val manifest = real.copy(releaseId = "r$sequence", sequence = sequence, harness = real.harness.copy(version = harness))
        val layers = manifest.archs.getValue("x86_64").layers.filter { it.name == LAYER_HARNESS }
        return RuntimeInstaller.CheckResult.Available(manifest, ManifestPolicy.Decision.Install("x86_64", layers))
    }

    private val installed = InstalledRuntime("r1", 1, "0.2.2", "24", "base", "dsh")
    private val stopped = RuntimeState.Stopped(installed)
    private fun running(onTrial: Boolean = false) =
        RuntimeState.Running(3080, "0.2.2", 0, StartSignIn.SIGNED_IN, installed.copy(harnessOnTrial = onTrial))

    private inner class Harness(val test: TestScope) {
        val store = MapStore()
        val prefs = RuntimeUpdatePreferences(store)
        val runtime = MutableStateFlow<RuntimeState>(stopped)
        val update = MutableStateFlow<AvailableRuntimeUpdate?>(null)
        val outcomes = MutableSharedFlow<UpdateOutcome>(extraBufferCapacity = 8)
        val link = MutableStateFlow(RuntimeUpdateCoordinator.LinkState())
        var now = 1_000_000_000L
        var next: RuntimeInstaller.CheckResult = RuntimeInstaller.CheckResult.UpToDate(real)
        var checksAllowed = true
        var unmetered = true
        var installAccepted = true
        val checks = mutableListOf<Long>()
        val installs = mutableListOf<Long>()
        val notified = mutableListOf<String>()
        val reconnects = mutableListOf<Int>()

        val ports = object : RuntimeUpdateCoordinator.Ports {
            override suspend fun check(): RuntimeInstaller.CheckResult {
                checks += now
                // The host publishes what a check found, as the real one does.
                update.value = (next as? RuntimeInstaller.CheckResult.Available)?.toUpdate()
                return next
            }
            override fun install(): Boolean {
                installs += now
                return installAccepted
            }
            override suspend fun automaticChecksAllowed() = checksAllowed
            override fun unmetered() = unmetered
            override fun notifyAvailable(update: AvailableRuntimeUpdate) { notified += "available ${update.releaseId}" }
            override fun notifyUpdated(outcome: UpdateOutcome.Applied) { notified += "updated ${outcome.to.releaseId}" }
            override fun notifyRolledBack(outcome: UpdateOutcome.RolledBack) { notified += "rolled back ${outcome.to.releaseId}" }
            override fun reconnect(port: Int) { reconnects += port }
        }

        val coordinator = RuntimeUpdateCoordinator(
            scope = test.backgroundScope,
            prefs = prefs,
            runtime = runtime,
            available = update,
            outcomes = outcomes,
            link = link,
            ports = ports,
            clock = { now },
        ).also { it.start() }
    }

    private fun ref(sequence: Long) = ReleaseRef("r$sequence", "0.2.3", sequence)

    @Test
    fun `automatic checks run at most once in a few hours and only with the app's switch on`() = runTest {
        val h = Harness(this)
        runCurrent()
        h.coordinator.onAppOpen()
        h.coordinator.onAppOpen()
        h.coordinator.onBackground()
        assertEquals(1, h.checks.size)

        h.now += RuntimeUpdateCoordinator.CHECK_INTERVAL_MS - 1
        h.coordinator.onAppOpen()
        assertEquals(1, h.checks.size)
        h.now += 1
        h.coordinator.onBackground()
        assertEquals(2, h.checks.size)

        // A check that could not reach the server does not count: the next opening tries again.
        h.now += RuntimeUpdateCoordinator.CHECK_INTERVAL_MS
        h.next = RuntimeInstaller.CheckResult.Refused(RuntimeFailure.Network("offline"))
        h.coordinator.onAppOpen()
        h.coordinator.onAppOpen()
        assertEquals(4, h.checks.size)

        // "Check for updates automatically" off: nothing automatic, while Check now still works.
        h.checksAllowed = false
        h.now += RuntimeUpdateCoordinator.CHECK_INTERVAL_MS
        h.coordinator.onAppOpen()
        assertEquals(4, h.checks.size)
        h.coordinator.checkNow()
        assertEquals(5, h.checks.size)
    }

    @Test
    fun `nothing is checked automatically before the runtime is installed`() = runTest {
        val h = Harness(this)
        h.runtime.value = RuntimeState.NotInstalled
        assertNull(h.coordinator.onAppOpen())
        assertNull(h.coordinator.onBackground())
        assertTrue(h.checks.isEmpty())
    }

    @Test
    fun `a new release is notified once and never again`() = runTest {
        val h = Harness(this)
        runCurrent()
        h.next = available(5)
        h.coordinator.onAppOpen()
        assertEquals(listOf("available r5"), h.notified)

        h.now += RuntimeUpdateCoordinator.CHECK_INTERVAL_MS
        h.coordinator.onBackground()
        h.now += RuntimeUpdateCoordinator.CHECK_INTERVAL_MS
        h.coordinator.onAppOpen()
        assertEquals(listOf("available r5"), h.notified)

        // A newer one is its own news.
        h.next = available(6)
        h.now += RuntimeUpdateCoordinator.CHECK_INTERVAL_MS
        h.coordinator.onAppOpen()
        assertEquals(listOf("available r5", "available r6"), h.notified)
        assertEquals(6, h.prefs.notifiedSequence)
    }

    @Test
    fun `a release the person found with Check now is not notified afterwards`() = runTest {
        val h = Harness(this)
        runCurrent()
        h.next = available(5)
        h.coordinator.checkNow()
        h.now += RuntimeUpdateCoordinator.CHECK_INTERVAL_MS
        h.coordinator.onAppOpen()
        assertTrue(h.notified.isEmpty())
    }

    @Test
    fun `update notifications can be turned off`() = runTest {
        val h = Harness(this)
        runCurrent()
        h.prefs.setNotify(false)
        h.next = available(5)
        h.coordinator.onAppOpen()
        assertTrue(h.notified.isEmpty())
    }

    @Test
    fun `a release that needs a newer app is not notified and still counts as a check`() = runTest {
        val h = Harness(this)
        runCurrent()
        h.next = RuntimeInstaller.CheckResult.Refused(RuntimeFailure.AppTooOld("0.23.0"))
        h.coordinator.onAppOpen()
        h.coordinator.onAppOpen()
        assertEquals(1, h.checks.size)
        assertTrue(h.notified.isEmpty())
        assertEquals(h.now, h.prefs.lastCheckAt)
    }

    @Test
    fun `automatic install waits for things to settle, installs once and says what changed`() = runTest {
        val h = Harness(this)
        h.prefs.setAutoInstall(true)
        runCurrent()
        h.next = available(7)
        h.coordinator.onAppOpen()
        // About to install by itself: the notification that matters is the one afterwards.
        assertTrue(h.notified.isEmpty())
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS - 1)
        assertTrue(h.installs.isEmpty())
        advanceTimeBy(2)
        assertEquals(1, h.installs.size)

        h.runtime.value = RuntimeState.Updating(UpdatePhase.Checking, installed)
        h.update.value = null
        h.outcomes.emit(UpdateOutcome.Applied(ref(1), ref(7)))
        h.runtime.value = RuntimeState.Stopped(installed.copy(releaseId = "r7", sequence = 7, harnessOnTrial = true))
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS * 2)
        assertEquals(listOf("updated r7"), h.notified)
        assertEquals(1, h.installs.size)
    }

    @Test
    fun `automatic install gives way when the harness starts in the meantime`() = runTest {
        val h = Harness(this)
        h.prefs.setAutoInstall(true)
        runCurrent()
        h.next = available(7)
        h.coordinator.onAppOpen()
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS / 2)
        h.runtime.value = RuntimeState.Starting(3080, installed)
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS)
        assertTrue(h.installs.isEmpty())

        // Running and in a turn on the connected app: still nothing.
        h.runtime.value = running()
        h.link.value = RuntimeUpdateCoordinator.LinkState(onBuiltIn = true, connected = true, turnRunning = true)
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS * 2)
        assertTrue(h.installs.isEmpty())

        // The turn ends: now it goes.
        h.link.value = RuntimeUpdateCoordinator.LinkState(onBuiltIn = true, connected = true, turnRunning = false)
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS + 1)
        assertEquals(1, h.installs.size)
    }

    @Test
    fun `automatic install never runs on a metered network or with the switch off`() = runTest {
        val h = Harness(this)
        runCurrent()
        h.next = available(7)
        h.coordinator.onAppOpen()
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS * 3)
        assertTrue(h.installs.isEmpty()) // off by default
        assertEquals(listOf("available r7"), h.notified)

        h.unmetered = false
        h.prefs.setAutoInstall(true)
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS * 3)
        assertTrue(h.installs.isEmpty())
    }

    @Test
    fun `the rules for installing by itself`() {
        val update = available(7).toUpdate()
        fun allowed(
            runtime: RuntimeState = stopped,
            link: RuntimeUpdateCoordinator.LinkState = RuntimeUpdateCoordinator.LinkState(),
            enabled: Boolean = true,
            skipped: ReleaseRef? = null,
            failed: ReleaseRef? = null,
            blocked: Long? = null,
            unmetered: Boolean = true,
            offer: AvailableRuntimeUpdate? = update,
        ) = autoInstallAllowed(AutoInstallInputs(enabled, offer, skipped, failed, blocked, runtime, link), unmetered)

        assertTrue(allowed())
        assertTrue(allowed(runtime = RuntimeState.Failed(RuntimeFailure.Crashed(9, ""), installed)))
        assertFalse(allowed(runtime = RuntimeState.Failed(RuntimeFailure.Network(null), null)))
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(unmetered = false))
        assertFalse(allowed(offer = null))
        // Running: only when the app is not on it, or is on it and nothing is in a turn.
        assertTrue(allowed(runtime = running()))
        assertTrue(allowed(runtime = running(), link = RuntimeUpdateCoordinator.LinkState(onBuiltIn = true, connected = true)))
        assertFalse(allowed(runtime = running(), link = RuntimeUpdateCoordinator.LinkState(onBuiltIn = true, connected = true, turnRunning = true)))
        // Reconnecting to it: whether a turn runs is unknown.
        assertFalse(allowed(runtime = running(), link = RuntimeUpdateCoordinator.LinkState(onBuiltIn = true, connected = false)))
        assertFalse(allowed(runtime = RuntimeState.Starting(3080, installed)))
        assertFalse(allowed(runtime = RuntimeState.Updating(UpdatePhase.Checking, installed)))
        // Left on purpose, or already failed here.
        assertFalse(allowed(skipped = ref(7)))
        assertTrue(allowed(skipped = ref(6)))
        assertFalse(allowed(failed = ref(7)))
        assertFalse(allowed(blocked = 7))
    }

    @Test
    fun `going back remembers the release left until a newer one appears`() = runTest {
        val h = Harness(this)
        h.prefs.setAutoInstall(true)
        runCurrent()
        h.next = available(6)
        h.outcomes.emit(UpdateOutcome.Applied(from = ref(6), to = ref(5), wentBack = true))
        runCurrent()
        assertEquals(ref(6), h.prefs.skipped.value)
        // The latest is read again, so the screens can say "You went back from r6".
        assertEquals(1, h.checks.size)
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS * 3)
        assertTrue(h.installs.isEmpty())
        h.coordinator.onAppOpen()
        assertTrue(h.notified.isEmpty())
        assertTrue(isSkipped(h.update.value!!, h.prefs.skipped.value))

        // A newer release is offered as usual, and installs by itself again.
        h.next = available(8)
        h.now += RuntimeUpdateCoordinator.CHECK_INTERVAL_MS
        h.coordinator.onAppOpen()
        assertNull(h.prefs.skipped.value)
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS + 1)
        assertEquals(1, h.installs.size)
    }

    @Test
    fun `update anyway clears the skip`() = runTest {
        val h = Harness(this)
        h.prefs.setSkipped(ref(6))
        runCurrent()
        h.outcomes.emit(UpdateOutcome.Applied(from = ref(5), to = ref(6)))
        runCurrent()
        assertNull(h.prefs.skipped.value)
    }

    @Test
    fun `an automatic update that did not start is reported and not tried again by itself`() = runTest {
        val h = Harness(this)
        h.prefs.setAutoInstall(true)
        runCurrent()
        h.next = available(7)
        h.coordinator.onAppOpen()
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS + 1)
        assertEquals(1, h.installs.size)

        h.update.value = null
        h.outcomes.emit(UpdateOutcome.Applied(ref(1), ref(7)))
        h.outcomes.emit(UpdateOutcome.RolledBack(from = ref(1), to = ref(7), reason = RuntimeFailure.ExitedBeforeReady(1, "boom")))
        runCurrent()
        assertEquals(listOf("updated r7", "rolled back r7"), h.notified)
        assertEquals(ref(7), h.prefs.failed.value)

        // The next check offers r7 again; it waits for the person.
        h.now += RuntimeUpdateCoordinator.CHECK_INTERVAL_MS
        h.coordinator.onAppOpen()
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS * 3)
        assertEquals(1, h.installs.size)
    }

    @Test
    fun `an automatic install that broke off waits for the next check`() = runTest {
        val h = Harness(this)
        h.prefs.setAutoInstall(true)
        runCurrent()
        h.next = available(7)
        h.coordinator.onAppOpen()
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS + 1)
        h.outcomes.emit(UpdateOutcome.Failed(ref(1), ref(7), RuntimeFailure.Network("reset")))
        // The engine puts the same state back; the offer is unchanged.
        h.runtime.value = RuntimeState.Stopped(installed.copy(releaseId = "x"))
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS * 3)
        assertEquals(1, h.installs.size)

        h.now += RuntimeUpdateCoordinator.CHECK_INTERVAL_MS
        h.coordinator.onAppOpen()
        runCurrent()
        advanceTimeBy(RuntimeUpdateCoordinator.AUTO_SETTLE_MS + 1)
        assertEquals(2, h.installs.size)
    }

    @Test
    fun `the app reconnects when the harness is back up after an update or a restart`() = runTest {
        val h = Harness(this)
        h.runtime.value = running()
        runCurrent()
        assertTrue(h.reconnects.isEmpty())

        h.runtime.value = RuntimeState.Updating(UpdatePhase.Switching, installed)
        runCurrent()
        h.runtime.value = RuntimeState.Starting(3080, installed)
        runCurrent()
        h.runtime.value = running()
        runCurrent()
        assertEquals(listOf(3080), h.reconnects)

        h.runtime.value = RuntimeState.Stopping(3080, installed)
        runCurrent()
        h.runtime.value = running().copy(port = 3081)
        runCurrent()
        assertEquals(listOf(3080, 3081), h.reconnects)
    }

    @Test
    fun `preferences survive the next app process`() {
        val store = MapStore()
        RuntimeUpdatePreferences(store).apply {
            setAutoInstall(true)
            setNotify(false)
            setSkipped(ReleaseRef("2026.11.14-4", "0.2.2-rc.1", 1794684413))
            notifiedSequence = 42
        }
        val next = RuntimeUpdatePreferences(store)
        assertTrue(next.autoInstall.value)
        assertFalse(next.notify.value)
        assertEquals(ReleaseRef("2026.11.14-4", "0.2.2-rc.1", 1794684413), next.skipped.value)
        assertEquals(42, next.notifiedSequence)
        assertNull(next.failed.value)
        // Defaults: notifications on, automatic installs off.
        val fresh = RuntimeUpdatePreferences(MapStore())
        assertTrue(fresh.notify.value)
        assertFalse(fresh.autoInstall.value)
    }
}
