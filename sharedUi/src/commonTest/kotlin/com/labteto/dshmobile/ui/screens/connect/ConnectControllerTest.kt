package com.labteto.dshmobile.ui.screens.connect

import com.labteto.dshmobile.connection.ConnectMode
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.DiscoveredHost
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.connection.ProbeTimeouts
import com.labteto.dshmobile.connection.belongsTo
import com.labteto.dshmobile.connection.harnessBaseUrl
import com.labteto.dshmobile.core.wire.SessionExchange
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.runtime.BuiltInHarnessState
import com.labteto.dshmobile.runtime.InstalledRuntime
import com.labteto.dshmobile.runtime.RuntimeAction
import com.labteto.dshmobile.runtime.RuntimeState
import com.labteto.dshmobile.termux.StartSignIn
import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertFalse
import com.labteto.dshmobile.testing.assertNull
import com.labteto.dshmobile.testing.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The Connect screen's decisions, driven through fake ports: what `ConnectController` does when an
 * address is typed, refused, answered, signed in to or cancelled. It is the logic the Android view model
 * used to hold, now run on the JVM and on an iOS simulator by the same file, so the two apps cannot
 * decide differently. (`runCurrent` and not `advanceUntilIdle`: the controller works in `backgroundScope`, whose
 * tasks `advanceUntilIdle` does not wait for.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectControllerTest {

    private class FakeStore : ConnectStore {
        val hostList = MutableStateFlow<List<HostConfig>>(emptyList())
        var current = ConnectSettings()
        override val hosts = hostList

        override suspend fun settings() = current

        override suspend fun update(transform: (ConnectSettings) -> ConnectSettings) {
            current = transform(current)
        }

        override suspend fun setConnectModeIfUnset(default: suspend () -> String): String = ConnectMode.of(default())

        override suspend fun rememberHost(
            name: String,
            host: String,
            port: Int,
            isLoopback: Boolean,
            useTls: Boolean,
            description: HostDescription?,
            basePath: String,
        ): HostConfig {
            val config = HostConfig(
                id = "id-$host-$port",
                name = name,
                host = host,
                port = port,
                isLoopback = isLoopback,
                useTls = useTls,
                basePath = basePath,
                lastHome = description?.home,
            )
            hostList.value = listOf(config) + hostList.value.filterNot { it.baseUrl == config.baseUrl }
            return config
        }

        override suspend fun cacheDescription(id: String, description: HostDescription) = Unit

        override suspend fun removeHost(id: String) {
            hostList.value = hostList.value.filterNot { it.id == id }
        }

        override suspend fun addKnownPort(port: Int) = Unit

        override suspend fun ensureLoopbackHost(port: Int): HostConfig = rememberHost("127.0.0.1", "127.0.0.1", port, true, false, null, "")
    }

    private class FakeNetwork : ConnectNetwork {
        var outcome: ProbeOutcome = ProbeOutcome.Refused
        var gate: CompletableDeferred<Unit>? = null
        val probed = mutableListOf<String>()

        override suspend fun probe(host: String, port: Int, timeouts: ProbeTimeouts, config: HostConfig?): HostDescription? =
            (probeOutcome(host, port, timeouts, config = config) as? ProbeOutcome.Reachable)?.description

        override suspend fun probeOutcome(
            host: String,
            port: Int,
            timeouts: ProbeTimeouts,
            preflight: Boolean,
            useTls: Boolean,
            config: HostConfig?,
            basePath: String,
        ): ProbeOutcome {
            probed += "$host:$port"
            gate?.await()
            return outcome
        }

        override suspend fun scan(ports: List<Int>, onProgress: (Int, Int) -> Unit, onFound: (DiscoveredHost) -> Unit) = emptyList<DiscoveredHost>()

        override suspend fun scanForRelays(onFound: (DiscoveredHost) -> Unit) = emptyList<DiscoveredHost>()

        override suspend fun browseRelays(onFound: (DiscoveredHost) -> Unit) = emptyList<DiscoveredHost>()

        override suspend fun isOnLocalSubnet(host: String) = true

        override suspend fun localSubnetLabel(): String? = null

        override suspend fun hostLabel(address: String) = address
    }

    private class FakeLink : ConnectionLink {
        private val mutable = MutableStateFlow(ConnectionUiState())
        override val state: StateFlow<ConnectionUiState> = mutable.asStateFlow()
        val connected = mutableListOf<HostConfig>()
        var disconnects = 0

        override suspend fun connect(config: HostConfig) {
            connected += config
        }

        override fun disconnect() {
            disconnects++
        }
    }

    private class FakeSignIn(var answer: SessionExchange = SessionExchange.Refused(401)) : HarnessSignIn {
        val tried = mutableListOf<String>()

        override suspend fun pair(hostId: String, baseUrl: String, tokenInput: String): SessionExchange {
            tried += tokenInput
            return answer
        }
    }

    private class Rig(scope: TestScope, loopbackIsThisPhone: Boolean = true) {
        val store = FakeStore()
        val network = FakeNetwork()
        val link = FakeLink()
        val signIn = FakeSignIn()
        val controller = ConnectController(scope.backgroundScope, store, network, link, signIn, NoLoopbackHarness, loopbackIsThisPhone)
    }

    @Test
    fun `a typo is reported as invalid input and nothing is probed`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.controller.connectManual("not an address", "3080")
        runCurrent()
        assertEquals(ConnectFailure.InvalidInput, rig.controller.state.value.failure)
        assertEquals(ConnectStage.Idle, rig.controller.state.value.stage)
        assertTrue(rig.network.probed.isEmpty())
    }

    @Test
    fun `an address that refuses is reported as refused and not remembered`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.network.outcome = ProbeOutcome.Refused
        rig.controller.connectManual("10.0.0.5", "3080")
        runCurrent()
        val state = rig.controller.state.value
        assertEquals(ConnectFailure.Refused, state.failure)
        assertEquals("10.0.0.5:3080", state.attempted)
        assertTrue(rig.store.hostList.value.isEmpty())
        assertTrue(rig.link.connected.isEmpty())
    }

    @Test
    fun `a harness that answers is remembered and connected to`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.network.outcome = ProbeOutcome.Reachable(HostDescription("/home/demo"))
        rig.controller.connectManual("10.0.0.5:3081", "3080")
        runCurrent()
        val remembered = rig.store.hostList.value.single()
        assertEquals("10.0.0.5:3081", remembered.authority)
        assertEquals("/home/demo", remembered.lastHome)
        assertEquals(listOf(remembered), rig.link.connected)
        assertNull(rig.controller.state.value.failure)
    }

    @Test
    fun `a harness that wants a session is remembered and a token signs it in`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.network.outcome = ProbeOutcome.Unauthenticated
        rig.controller.connectManual("10.0.0.5", "3080")
        runCurrent()
        assertEquals(ConnectFailure.Unauthenticated, rig.controller.state.value.failure)
        assertEquals("10.0.0.5:3080", rig.store.hostList.value.single().authority)

        // A stale token is refused and the sheet stays open with the reason.
        rig.controller.setSignInOpen(true)
        rig.controller.signIn("old")
        runCurrent()
        assertEquals(SignInError.Refused, rig.controller.state.value.signInError)
        assertTrue(rig.controller.state.value.signInOpen)
        assertTrue(rig.link.connected.isEmpty())

        // The current one closes it and connects with what it earned.
        rig.signIn.answer = SessionExchange.Granted("dsh-auth-x=1")
        rig.controller.signIn("current")
        runCurrent()
        assertEquals(listOf("old", "current"), rig.signIn.tried)
        assertFalse(rig.controller.state.value.signInOpen)
        assertEquals("10.0.0.5:3080", rig.link.connected.single().authority)
    }

    @Test
    fun `cancelling puts the screen back and a late answer connects nothing`() = runTest {
        val rig = Rig(this)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        rig.network.gate = gate
        rig.network.outcome = ProbeOutcome.Reachable(HostDescription("/home/demo"))
        rig.controller.connectManual("10.0.0.5", "3080")
        runCurrent()
        assertTrue(rig.controller.state.value.connecting)

        rig.controller.cancelConnect()
        assertFalse(rig.controller.state.value.connecting)
        assertEquals(1, rig.link.disconnects)

        gate.complete(Unit)
        runCurrent()
        assertTrue(rig.link.connected.isEmpty())
        assertTrue(rig.store.hostList.value.isEmpty())
        assertNull(rig.controller.state.value.failure)
    }

    @Test
    fun `a loopback address is the This-phone harness where there is such a mode`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.network.outcome = ProbeOutcome.Reachable(null)
        rig.controller.connectManual("127.0.0.1:3181", "3080")
        runCurrent()
        val remembered = rig.store.hostList.value.single()
        assertTrue(remembered.isLoopback)
        // Filed under the mode the Connect screen on this platform can open, not the local network list...
        assertFalse(remembered.belongsTo(ConnectMode.LAN))
        assertTrue(remembered.belongsTo(ConnectMode.LOOPBACK))
        // ...and the screen is on that mode now, so it is the one that lists the host (and the one the
        // next launch opens on). It used to stay on Local network, listing nothing.
        assertEquals(ConnectMode.LOOPBACK, rig.controller.state.value.mode)
        assertEquals(listOf(remembered), rig.controller.state.value.visibleHosts)
    }

    @Test
    fun `a loopback address is an ordinary host where there is no such mode`() = runTest {
        val rig = Rig(this, loopbackIsThisPhone = false)
        runCurrent()
        rig.network.outcome = ProbeOutcome.Reachable(null)
        rig.controller.connectManual("127.0.0.1:3181", "3080")
        runCurrent()
        val remembered = rig.store.hostList.value.single()
        assertFalse(remembered.isLoopback)
        assertEquals(listOf(remembered), rig.controller.state.value.visibleHosts)
        assertEquals(harnessBaseUrl("127.0.0.1", 3181, false, ""), remembered.baseUrl)
    }

    @Test
    fun `the harness last used is reconnected to when it still answers`() = runTest {
        val store = FakeStore()
        val last = store.rememberHost("home", "10.0.0.7", 3080, false, false, null, "")
        val network = FakeNetwork().apply { outcome = ProbeOutcome.Reachable(HostDescription("/home/demo")) }
        val link = FakeLink()
        ConnectController(backgroundScope, store, network, link, FakeSignIn(), NoLoopbackHarness, true)
        runCurrent()
        assertEquals(listOf(last), link.connected)
    }

    @Test
    fun `with reconnect switched off the last harness is only listed`() = runTest {
        val store = FakeStore()
        store.rememberHost("home", "10.0.0.7", 3080, false, false, null, "")
        store.current = ConnectSettings(autoConnectLast = false)
        val network = FakeNetwork().apply { outcome = ProbeOutcome.Reachable(null) }
        val link = FakeLink()
        val controller = ConnectController(backgroundScope, store, network, link, FakeSignIn(), NoLoopbackHarness, true)
        runCurrent()
        assertTrue(link.connected.isEmpty())
        assertEquals(1, controller.state.value.visibleHosts.size)
    }

    // ---- The built-in harness ------------------------------------------------------------------

    /** The built-in harness as the controller sees it: calls are recorded, the state is set by hand. */
    private class FakeBuiltIn(initial: RuntimeState) : BuiltInHarness {
        val mutable = MutableStateFlow(BuiltInHarnessState(initial))
        override val state: StateFlow<BuiltInHarnessState> = mutable.asStateFlow()
        val calls = mutableListOf<String>()
        override fun setUp(port: Int) {
            calls += "setUp:$port"
        }
        override fun start(port: Int) {
            calls += "start:$port"
        }
        override fun stop() {
            calls += "stop"
        }
        override fun restart() {
            calls += "restart"
        }
    }

    private val installedRuntime = InstalledRuntime("r", 1, "0.2.2", "24", "b", "h")

    private fun running(signIn: StartSignIn) = RuntimeState.Running(3080, "0.2.2", 0, signIn, installedRuntime)

    private class BuiltInRig(scope: TestScope, initial: RuntimeState, settings: ConnectSettings) {
        val store = FakeStore().apply { current = settings }
        val network = FakeNetwork()
        val link = FakeLink()
        val builtIn = FakeBuiltIn(initial)
        val controller = ConnectController(scope.backgroundScope, store, network, link, FakeSignIn(), NoLoopbackHarness, true, builtIn)
    }

    private val loopbackMode = ConnectSettings(connectMode = ConnectMode.LOOPBACK, autoConnectLoopback = false)

    @Test
    fun `starting the built-in harness connects once it answers like a Termux start`() = runTest {
        val rig = BuiltInRig(this, RuntimeState.Stopped(installedRuntime), loopbackMode)
        runCurrent()
        rig.controller.startBuiltIn()
        assertEquals(listOf("start:3080"), rig.builtIn.calls)
        // Up and signed in: the controller probes at once rather than waiting for the next tick.
        rig.network.outcome = ProbeOutcome.Reachable(null)
        rig.builtIn.mutable.value = BuiltInHarnessState(running(StartSignIn.SIGNED_IN))
        runCurrent()
        assertEquals(listOf("127.0.0.1:3080"), rig.link.connected.map { it.authority })
    }

    @Test
    fun `a built-in harness that did not sign the app in is not connected to`() = runTest {
        val rig = BuiltInRig(this, RuntimeState.Stopped(installedRuntime), loopbackMode)
        runCurrent()
        rig.controller.startBuiltIn()
        rig.network.outcome = ProbeOutcome.Unauthenticated
        rig.builtIn.mutable.value = BuiltInHarnessState(running(StartSignIn.REFUSED))
        runCurrent()
        assertTrue(rig.link.connected.isEmpty())
        // The card and the pinned button offer the restart that signs it in again.
        assertEquals(RuntimeAction.Restart, rig.controller.state.value.builtInCard?.primary)
        rig.controller.restartBuiltIn()
        assertEquals("restart", rig.builtIn.calls.last())
    }

    @Test
    fun `opening the app starts the built-in harness only when asked to`() = runTest {
        val asked = BuiltInRig(this, RuntimeState.Stopped(installedRuntime), loopbackMode.copy(autoStartBuiltInHarness = true))
        runCurrent()
        assertEquals(listOf("start:3080"), asked.builtIn.calls)

        val notAsked = BuiltInRig(this, RuntimeState.Stopped(installedRuntime), loopbackMode)
        runCurrent()
        assertTrue(notAsked.builtIn.calls.isEmpty())

        // Nothing is started in another mode, whatever the switch says.
        val lan = BuiltInRig(this, RuntimeState.Stopped(installedRuntime), ConnectSettings(autoStartBuiltInHarness = true))
        runCurrent()
        assertTrue(lan.builtIn.calls.isEmpty())
    }

    @Test
    fun `an auto-started built-in harness is connected to when it answers`() = runTest {
        val rig = BuiltInRig(this, RuntimeState.Stopped(installedRuntime), loopbackMode.copy(autoStartBuiltInHarness = true))
        runCurrent()
        rig.network.outcome = ProbeOutcome.Reachable(null)
        rig.builtIn.mutable.value = BuiltInHarnessState(running(StartSignIn.SIGNED_IN))
        runCurrent()
        assertEquals(1, rig.link.connected.size)
    }

    @Test
    fun `set-up and a move to a free port go to the built-in harness on the card's port`() = runTest {
        val rig = BuiltInRig(this, RuntimeState.NotInstalled, loopbackMode)
        runCurrent()
        rig.controller.setUpBuiltIn()
        assertEquals("setUp:3080", rig.builtIn.calls.last())
        rig.controller.startBuiltInOn(3081)
        assertEquals("start:3081", rig.builtIn.calls.last())
        assertEquals(3081, rig.controller.state.value.loopbackPort)
    }

    @Test
    fun `the auto-start switch is kept with the other connect settings`() = runTest {
        val rig = BuiltInRig(this, RuntimeState.Stopped(installedRuntime), loopbackMode)
        runCurrent()
        rig.controller.setAuto("autoStartBuiltIn", true)
        runCurrent()
        assertTrue(rig.store.current.autoStartBuiltInHarness)
        assertTrue(rig.controller.state.value.autoStartBuiltInHarness)
    }

    @Test
    fun `a stopped built-in harness is no longer connected to when it comes back`() = runTest {
        val rig = BuiltInRig(this, RuntimeState.Stopped(installedRuntime), loopbackMode)
        runCurrent()
        rig.controller.startBuiltIn()
        rig.controller.stopBuiltIn()
        assertEquals("stop", rig.builtIn.calls.last())
        // Started again by something other than this screen: auto-connect is off, so nothing connects.
        rig.network.outcome = ProbeOutcome.Reachable(null)
        rig.builtIn.mutable.value = BuiltInHarnessState(running(StartSignIn.SIGNED_IN))
        runCurrent()
        assertTrue(rig.link.connected.isEmpty())
    }

    // ---- A loopback pairing and the process dying ---------------------------------------------

    @Test
    fun `a loopback address typed on Local network moves the screen to This phone`() = runTest {
        val rig = Rig(this)
        runCurrent()
        assertEquals(ConnectMode.LAN, rig.controller.state.value.mode)
        rig.network.outcome = ProbeOutcome.Reachable(null)
        rig.controller.connectManual("127.0.0.1:3181", "3080")
        runCurrent()
        // The record is filed under This phone; the mode the screen opens on has to follow it, or the
        // next launch lists nothing and auto-connect never looks at it.
        assertTrue(rig.store.hostList.value.single().isLoopback)
        assertEquals(ConnectMode.LOOPBACK, rig.controller.state.value.mode)
        assertEquals(ConnectMode.LOOPBACK, rig.store.current.connectMode)
        assertEquals(3181, rig.controller.state.value.loopbackPort)
        assertEquals(listOf("127.0.0.1:3181"), rig.link.connected.map { it.authority })
    }

    @Test
    fun `a harness that wants a token is also remembered under This phone and the screen follows`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.network.outcome = ProbeOutcome.Unauthenticated
        rig.controller.connectManual("localhost", "3080")
        runCurrent()
        assertTrue(rig.store.hostList.value.single().isLoopback)
        assertEquals(ConnectMode.LOOPBACK, rig.store.current.connectMode)
        // The refusal is still the one the screen reports.
        assertEquals(ConnectFailure.Unauthenticated, rig.controller.state.value.failure)
    }

    @Test
    fun `other addresses leave the mode alone and so does the platform with no such mode`() = runTest {
        val lan = Rig(this)
        runCurrent()
        lan.network.outcome = ProbeOutcome.Reachable(null)
        lan.controller.connectManual("10.0.0.5:3081", "3080")
        runCurrent()
        assertEquals(ConnectMode.LAN, lan.controller.state.value.mode)
        assertEquals(ConnectMode.LAN, lan.store.current.connectMode)

        val noMode = Rig(this, loopbackIsThisPhone = false)
        runCurrent()
        noMode.network.outcome = ProbeOutcome.Reachable(null)
        noMode.controller.connectManual("127.0.0.1:3181", "3080")
        runCurrent()
        assertEquals(ConnectMode.LAN, noMode.controller.state.value.mode)
        assertEquals(ConnectMode.LAN, noMode.store.current.connectMode)
    }

    @Test
    fun `a pairing made with a pasted link survives the process dying and the app reconnects`() = runTest {
        val store = FakeStore()
        val network = FakeNetwork().apply { outcome = ProbeOutcome.Unauthenticated }
        val first = ConnectController(backgroundScope, store, network, FakeLink(), FakeSignIn(SessionExchange.Granted("cookie")), NoLoopbackHarness, true)
        runCurrent()
        // The link `dsh web` prints, token included, pasted into the Local network field.
        first.connectManual("http://127.0.0.1:3080/?token=abc123", "3080")
        runCurrent()
        assertEquals(1, store.hostList.value.size)

        // The process dies. A new one opens over the same storage, and the harness still answers with the cookie it holds.
        network.outcome = ProbeOutcome.Reachable(HostDescription("/home/demo"))
        val link = FakeLink()
        val second = ConnectController(backgroundScope, store, network, link, FakeSignIn(), NoLoopbackHarness, true)
        runCurrent()
        assertEquals(ConnectMode.LOOPBACK, second.state.value.mode)
        assertEquals(listOf("127.0.0.1:3080"), link.connected.map { it.authority })
    }

    @Test
    fun `a loopback host is not listed or reconnected to under Local network by design`() = runTest {
        // What the device run saw: the record was kept, but the screen opened on a mode that cannot show it.
        val store = FakeStore()
        store.rememberHost("127.0.0.1", "127.0.0.1", 3080, true, false, null, "")
        store.current = ConnectSettings(connectMode = ConnectMode.LAN)
        val network = FakeNetwork().apply { outcome = ProbeOutcome.Reachable(null) }
        val link = FakeLink()
        val controller = ConnectController(backgroundScope, store, network, link, FakeSignIn(), NoLoopbackHarness, true)
        runCurrent()
        assertTrue(controller.state.value.visibleHosts.isEmpty())
        assertTrue(link.connected.isEmpty())
        // The same storage on This phone reconnects.
        store.current = ConnectSettings(connectMode = ConnectMode.LOOPBACK)
        val onThisPhone = FakeLink()
        ConnectController(backgroundScope, store, network, onThisPhone, FakeSignIn(), NoLoopbackHarness, true)
        runCurrent()
        assertEquals(listOf("127.0.0.1:3080"), onThisPhone.connected.map { it.authority })
    }

    @Test
    fun `an address from another device is remembered and listed after the process dies`() = runTest {
        // The 10.0.2.2 pairing of the same run came back by itself: it is a Local network host.
        val store = FakeStore()
        val network = FakeNetwork().apply { outcome = ProbeOutcome.Reachable(HostDescription("/home/demo")) }
        val first = ConnectController(backgroundScope, store, network, FakeLink(), FakeSignIn(), NoLoopbackHarness, true)
        runCurrent()
        first.connectManual("10.0.2.2:3080", "3080")
        runCurrent()
        val link = FakeLink()
        val second = ConnectController(backgroundScope, store, network, link, FakeSignIn(), NoLoopbackHarness, true)
        runCurrent()
        assertEquals(listOf("10.0.2.2:3080"), second.state.value.visibleHosts.map { it.authority })
        assertEquals(listOf("10.0.2.2:3080"), link.connected.map { it.authority })
    }

    // ---- The built-in harness after the process dies, in the middle of a sign-in ----------------

    private val thisPhone = ConnectSettings(connectMode = ConnectMode.LOOPBACK)

    @Test
    fun `a built-in harness that kept running is connected to when the app comes back`() = runTest {
        // The harness is a child of a foreground service, so swiping the app away leaves it running. The
        // app opens on This phone, finds it answering with the cookie it holds, and reconnects.
        val rig = BuiltInRig(this, running(StartSignIn.SIGNED_IN), thisPhone)
        rig.network.outcome = ProbeOutcome.Reachable(HostDescription("/home/demo"))
        runCurrent()
        assertEquals(listOf("127.0.0.1:3080"), rig.link.connected.map { it.authority })
    }

    @Test
    fun `a built-in harness that went down with the process is not started on its own`() = runTest {
        // If the whole process died the harness died with it, and a sign-in attempt in progress was
        // only in its memory. Nothing starts it unless "start when the app opens" is on: the card says Start.
        val rig = BuiltInRig(this, RuntimeState.Stopped(installedRuntime), thisPhone)
        rig.network.outcome = ProbeOutcome.Refused
        runCurrent()
        assertTrue(rig.builtIn.calls.isEmpty())
        assertTrue(rig.link.connected.isEmpty())
        assertEquals(RuntimeAction.Start, rig.controller.state.value.builtInCard?.primary)
    }
}
