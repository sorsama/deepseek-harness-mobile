package com.labteto.dshmobile.ui.screens.connect

import com.labteto.dshmobile.connection.ConnectMode
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.DiscoveredHost
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.connection.ProbeTimeouts
import com.labteto.dshmobile.connection.harnessBaseUrl
import com.labteto.dshmobile.core.wire.SessionExchange
import com.labteto.dshmobile.core.wire.dto.HostDescription
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
        // Filed under the mode the Connect screen on this platform can open: not the local network list.
        assertTrue(rig.controller.state.value.visibleHosts.isEmpty())
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
}
