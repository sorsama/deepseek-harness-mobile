package com.labteto.dshmobile.ui.screens.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labteto.dshmobile.connection.ConnectMode
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.DiscoveredHost
import com.labteto.dshmobile.connection.DiscoveryEngine
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.connection.MdnsDiscovery
import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.connection.ProbeTimeouts
import com.labteto.dshmobile.connection.parseHostInput
import com.labteto.dshmobile.core.wire.dto.HostDescription
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject

/** Whether a remembered harness is answering right now. */
sealed interface HostProbe {
    /** The probe is in flight. */
    data object Probing : HostProbe

    /** It answered `host.describe`; [description] is what it said. */
    data class Reachable(val description: HostDescription) : HostProbe

    /** No answer — switched off, asleep, or on another network. */
    data object Unreachable : HostProbe
}

/** How far the subnet sweep has got, so the UI can show more than a spinner. */
data class ScanProgress(val probed: Int, val total: Int)

data class ConnectUiState(
    /**
     * Which way the user chose to connect: [ConnectMode.LAN] or [ConnectMode.RELAY].
     *
     * A choice, never a detection. The two paths have different trust models and different
     * discovery, and nothing in the app connects a way the user did not pick — including
     * auto-connect, which is scoped to this.
     */
    val mode: String = ConnectMode.LAN,
    val remembered: List<HostConfig> = emptyList(),
    /** Liveness per remembered host, keyed by `host:port`. Absent = not probed yet. */
    val recentStatus: Map<String, HostProbe> = emptyMap(),
    val discovered: List<DiscoveredHost> = emptyList(),
    val scanning: Boolean = false,
    val scanProgress: ScanProgress? = null,
    /** What the connect attempt is doing right now. */
    val stage: ConnectStage = ConnectStage.Idle,
    /** Why the last attempt failed, or null. */
    val failure: ConnectFailure? = null,
    /** The authority actually attempted, e.g. `192.168.1.20:3080` — never the live field text. */
    val attempted: String? = null,
    /** The loop is still retrying in the background, so a cancel is worth offering. */
    val retrying: Boolean = false,
    val autoConnectLast: Boolean = true,
    val autoConnectLan: Boolean = false,
    val autoConnectLoopback: Boolean = true,
    val autoConnectRelay: Boolean = false,
    val showAdvanced: Boolean = false,
) {
    /** Remembered endpoints belonging to the selected mode. */
    val visibleHosts: List<HostConfig>
        get() = remembered.filter { it.isRelay == (mode == ConnectMode.RELAY) }
    /**
     * Derived, never stored.
     *
     * The old stored boolean was only ever cleared by a callback that could not fire, so a failed
     * connect left the button disabled for the rest of the session. Reading it off the stage means
     * there is no latch to get stuck: `disconnect()` resets the whole state object, and every other
     * path ends in either [ConnectStage.Connected] or a failure the screen displays.
     */
    val connecting: Boolean
        get() = stage != ConnectStage.Idle && stage != ConnectStage.Connected

    /**
     * Discovered harnesses that are not already remembered.
     *
     * A harness in both lists used to render twice, with two different Connect buttons doing the
     * same thing; the Recent card is the one with the history on it, so the sweep yields.
     */
    val unknownDiscovered: List<DiscoveredHost>
        get() {
            val known = remembered.map { it.authority }.toSet()
            return discovered.filterNot { it.authority in known }
        }

    /** Whether the endpoint currently being attempted is a paired relay. */
    val attemptingRelay: Boolean
        get() = remembered.firstOrNull { it.authority == attempted }?.isRelay == true

    /**
     * Origin of the endpoint being attempted, for a "pair again" that lands prefilled.
     *
     * Read off the remembered record rather than rebuilt from [attempted], because the scheme is
     * exactly the part an authority does not carry — and sending someone to `http://` for an
     * `https://` relay would fail in a way that looks like the relay is down.
     */
    val attemptedBaseUrl: String?
        get() = remembered.firstOrNull { it.authority == attempted }?.baseUrl
}

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val discoveryEngine: DiscoveryEngine,
    private val mdnsDiscovery: MdnsDiscovery,
    private val hostsStore: HostsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    /**
     * Non-null while this ViewModel owns the outcome rather than the manager.
     *
     * Validation and the pre-flight probe happen here and can end the attempt before the manager is
     * ever engaged; everything from the handshake on belongs to the manager. Set to null only when
     * handing over, so a locally-decided result is not overwritten by a manager still reporting the
     * previous attempt — and so a stale manager stage cannot pin the screen on "Reaching…".
     */
    private var localStage: ConnectStage? = null

    /** The sweep in flight, so a second tap cannot start a rival one and Cancel has something to stop. */
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            connectionManager.state.collect { conn ->
                _state.update { current ->
                    val connected = conn.phase == ConnectionPhase.CONNECTED
                    val owned = localStage != null
                    current.copy(
                        stage = localStage ?: conn.stage,
                        failure = when {
                            connected -> null
                            owned -> current.failure
                            else -> conn.failure
                        },
                        retrying = !owned && !connected && conn.attempts > 0,
                        // The Recent card's liveness dot used to be greyed by the failure callback
                        // that no longer exists; without this a dead entry keeps looking healthy.
                        recentStatus = current.attempted
                            ?.takeIf { !owned && conn.failure != null }
                            ?.let { current.recentStatus + (it to HostProbe.Unreachable) }
                            ?: current.recentStatus,
                    )
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            val settings = hostsStore.settingsOnce()
            _state.update {
                it.copy(
                    mode = settings.connectMode,
                    autoConnectLast = settings.autoConnectLast,
                    autoConnectLan = settings.autoConnectLan,
                    autoConnectLoopback = settings.autoConnectLoopback,
                    autoConnectRelay = settings.autoConnectRelay,
                )
            }
            hostsStore.hosts.collect { hosts ->
                _state.update { it.copy(remembered = hosts) }
            }
        }
        viewModelScope.launch { autoConnect() }
        viewModelScope.launch { probeRemembered() }
    }

    /**
     * Probe every remembered host once, concurrently.
     *
     * Without this a Recent row can only offer a Connect button that may or may not do anything;
     * one `host.describe` per entry is what turns the list into something you can read before
     * tapping. Results are folded back into storage so the metadata survives the harness going away.
     */
    private suspend fun probeRemembered() {
        val hosts = hostsStore.hosts.first()
        if (hosts.isEmpty()) return
        // A paired relay is probed *with* its credential: unauthenticated it can only ever answer
        // 403, which would grey out every relay card the moment it was drawn.
        _state.update { current ->
            current.copy(recentStatus = hosts.associate { it.authority to HostProbe.Probing })
        }
        supervisorScope {
            hosts.map { host ->
                async {
                    val description = runCatching {
                        // A remembered host is named, not swept — worth waiting for.
                        discoveryEngine.probe(host.host, host.port, ProbeTimeouts.Manual, config = host)
                    }.getOrNull()
                    _state.update { current ->
                        current.copy(
                            recentStatus = current.recentStatus + (
                                host.authority to (
                                    description?.let { HostProbe.Reachable(it) } ?: HostProbe.Unreachable
                                    )
                                ),
                        )
                    }
                    if (description != null) {
                        hostsStore.cacheDescription(host.host, host.port, description)
                    }
                }
            }.awaitAll()
        }
    }

    /** Re-run the liveness pass, e.g. after the user comes back to the screen. */
    fun refreshRecent() {
        viewModelScope.launch { probeRemembered() }
    }

    /**
     * Connect without being asked, but only the way the user chose.
     *
     * The mode gate is the whole point of the branch. LAN and relay are not two routes to the same
     * place: one talks to an unauthenticated harness on the local network, the other presents a
     * credential to a relay. Auto-connecting the *other* way would make the choice on the connect
     * screen a suggestion, and would do it before the user had a chance to look at it.
     */
    private suspend fun autoConnect() {
        val settings = hostsStore.settingsOnce()
        val relayMode = settings.connectMode == ConnectMode.RELAY
        // 1. Last used host — of this mode. A relay and a bare harness can be the same machine, and
        //    the most recent entry overall is often the one the user just switched away from.
        if (settings.autoConnectLast) {
            val last = hostsStore.hosts.first().firstOrNull { it.isRelay == relayMode }
            if (last != null) {
                val desc = discoveryEngine.probe(last.host, last.port, ProbeTimeouts.Manual, config = last)
                if (desc != null) {
                    connectTo(last)
                    return
                }
            }
        }
        if (relayMode) {
            // 2r. A relay this device has already paired with, found by its advertisement. There is
            //     deliberately no sweep here and no "first relay wins": an unpaired relay cannot be
            //     connected to without the user typing a code, so auto-connect has nothing to do
            //     with one.
            if (settings.autoConnectRelay) {
                val known = hostsStore.hosts.first().filter { it.isRelay }
                if (known.isNotEmpty()) {
                    val advertised = mdnsDiscovery.browse().map { it.authority }.toSet()
                    val match = known.firstOrNull { it.authority in advertised }
                    if (match != null) connectTo(match)
                }
            }
            return
        }
        // 2. LAN discovery.
        if (settings.autoConnectLan) {
            val first = firstReachableOnLan(settings.knownPorts)
            if (first != null) {
                val config = hostsStore.rememberHost(
                    name = hostLabel(first.host),
                    host = first.host,
                    port = first.port,
                    isLoopback = false,
                    description = first.description,
                )
                connectTo(config)
                return
            }
        }
        // 3. Same-device loopback.
        if (settings.autoConnectLoopback) {
            val desc = discoveryEngine.probe(LOOPBACK, DEFAULT_PORT)
            if (desc != null) {
                val config = hostsStore.rememberHost(
                    name = hostLabel(LOOPBACK),
                    host = LOOPBACK,
                    port = DEFAULT_PORT,
                    isLoopback = true,
                    description = desc,
                )
                connectTo(config)
            }
        }
    }

    /**
     * Sweep only until something answers, then stop.
     *
     * Auto-connect has no use for the rest of the subnet, so finishing the sweep before making the
     * first attempt is latency nobody asked for. A trust-fenced host does not count — auto-connect
     * cannot do anything with one, and stopping on it would hide a usable harness further along.
     */
    private suspend fun firstReachableOnLan(ports: List<Int>): DiscoveredHost? = coroutineScope {
        val hit = CompletableDeferred<DiscoveredHost?>()
        val sweep = launch {
            val all = discoveryEngine.scan(ports, onFound = { found ->
                if (found.trusted) hit.complete(found)
            })
            hit.complete(all.firstOrNull { it.trusted })
        }
        val result = hit.await()
        sweep.cancel()
        result
    }

    /**
     * Sweep the subnet, showing hosts as they are confirmed.
     *
     * Results stream rather than landing in one batch at the end: the harness someone is looking
     * for is usually found in the first fraction of the sweep, and making them watch the remaining
     * two hundred addresses finish before it appears is the difference between "fast" and "fast on
     * paper". [discovered] is therefore cleared at the start and appended to, not replaced.
     */
    fun scan() {
        if (scanJob?.isActive == true) return
        _state.update {
            it.copy(scanning = true, scanProgress = null, failure = null, discovered = emptyList())
        }
        scanJob = viewModelScope.launch {
            val settings = hostsStore.settingsOnce()
            try {
                if (settings.connectMode == ConnectMode.RELAY) {
                    scanForRelays()
                    return@launch
                }
                discoveryEngine.scan(
                    ports = settings.knownPorts,
                    onProgress = { probed, total ->
                        _state.update { it.copy(scanProgress = ScanProgress(probed, total)) }
                    },
                    onFound = { found ->
                        _state.update { state ->
                            if (state.discovered.any { it.authority == found.authority }) state
                            else state.copy(discovered = state.discovered + found)
                        }
                    },
                )
            } finally {
                // Also the cancel path: a sweep the user stopped keeps whatever it already found.
                _state.update { it.copy(scanning = false, scanProgress = null) }
            }
        }
    }

    /**
     * Relay discovery: the advertisement first, the sweep only if it turned nothing up.
     *
     * A relay publishes `_dsh._tcp` with its port, its TLS posture and its key, so a browse answers
     * in a second or two and finds relays a /24 sweep would never look at. Nothing depends on it
     * arriving, though — multicast is filtered on plenty of networks and the relay's `mdns` flag can
     * be off — so a quiet browse falls through to knocking the two ports a relay actually uses,
     * which is far cheaper than the harness sweep.
     */
    private suspend fun scanForRelays() {
        val report: (DiscoveredHost) -> Unit = { found ->
            _state.update { state ->
                if (state.discovered.any { it.authority == found.authority }) state
                else state.copy(discovered = state.discovered + found)
            }
        }
        val advertised = mdnsDiscovery.browse(onFound = report)
        if (advertised.isEmpty()) discoveryEngine.scanForRelays(onFound = report)
    }

    /** Stop a sweep in flight, keeping anything it has already turned up. */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
    }

    fun connectManual(host: String, port: String) {
        // The field takes what people actually have — a pasted URL as readily as a bare address.
        // A port named inside it was typed as part of this address, so it outranks the port field,
        // which may still hold the default from a different harness.
        val input = parseHostInput(host)
        val portInt = input?.port ?: port.trim().toIntOrNull()
        if (input == null || portInt == null || portInt !in 1..65535) {
            fail(ConnectFailure.InvalidInput, attempted = null)
            return
        }
        // An explicit scheme decides; otherwise port 443 means a TLS reverse proxy — the harness
        // itself never serves there, and plaintext to a TLS port yields an answer no one can read.
        val useTls = input.useTls ?: (portInt == 443)
        val authority = "${input.host}:$portInt"
        val isLoopback = input.host == LOOPBACK || input.host == "localhost"

        localStage = ConnectStage.Validating
        _state.update { it.copy(stage = ConnectStage.Validating, failure = null, attempted = authority) }

        viewModelScope.launch {
            val paired = hostsStore.hosts.first().firstOrNull { it.authority == authority && it.isRelay }
            // Cheap and decisive: the sweep only ever looks at this phone's own /24, so an address
            // outside it can never be reached from here and can never be found by scanning either.
            // Saying so now beats a four-second timeout that blames the firewall.
            //
            // A paired relay is the exception, and the only one. It holds a real credential rather
            // than relying on being on the same wire, and reaching one through a forwarded port or a
            // VPN is the reason the relay exists — so for those the guard would be refusing the
            // supported case with a confident, wrong explanation.
            if (!isLoopback && paired == null && !discoveryEngine.isOnLocalSubnet(input.host)) {
                fail(ConnectFailure.DifferentSubnet(discoveryEngine.localSubnetLabel()), authority)
                return@launch
            }
            localStage = ConnectStage.Reaching
            _state.update { it.copy(stage = ConnectStage.Reaching) }
            val outcome = discoveryEngine.probeOutcome(
                host = input.host,
                port = portInt,
                timeouts = ProbeTimeouts.Manual,
                preflight = true,
                useTls = useTls,
                config = paired,
            )
            if (outcome !is ProbeOutcome.Reachable) {
                fail(ConnectFailure.from(outcome, relay = paired != null), authority)
                return@launch
            }
            if (paired != null) {
                connectTo(paired)
                return@launch
            }
            hostsStore.addKnownPort(portInt)
            val config = hostsStore.rememberHost(
                name = hostLabel(input.host),
                host = input.host,
                port = portInt,
                isLoopback = isLoopback,
                useTls = useTls,
                description = outcome.description,
            )
            connectTo(config)
        }
    }

    /** Stop a connect attempt that the loop would otherwise keep retrying every few seconds. */
    fun cancelConnect() {
        // Keep ownership: the manager resets its own state on disconnect, but claiming Idle here
        // means the screen is never briefly re-driven by a trailing emission.
        localStage = ConnectStage.Idle
        connectionManager.disconnect()
        _state.update { it.copy(stage = ConnectStage.Idle, failure = null, retrying = false) }
    }

    /**
     * End the attempt with a reason, and mark the attempted host unreachable if there was one.
     *
     * Holds [localStage] at Idle rather than releasing it: this outcome was decided here, and a
     * manager emission from an earlier attempt must not replace it or revive `connecting`.
     */
    private fun fail(failure: ConnectFailure, attempted: String?) {
        localStage = ConnectStage.Idle
        _state.update {
            it.copy(
                stage = ConnectStage.Idle,
                failure = failure,
                attempted = attempted ?: it.attempted,
                retrying = false,
                recentStatus = attempted?.let { key -> it.recentStatus + (key to HostProbe.Unreachable) }
                    ?: it.recentStatus,
            )
        }
    }

    /**
     * Connect to a remembered host, and say so when it does not work.
     *
     * Progress and failure now arrive on the manager's own state flow, which the collector in
     * `init` folds in — so a tap on a dead Recent entry reports the same diagnosis as a manual
     * attempt instead of looking like an inert button.
     */
    fun connectTo(host: HostConfig) {
        localStage = null
        _state.update {
            it.copy(
                stage = ConnectStage.OpeningStreams,
                failure = null,
                attempted = host.authority,
                retrying = false,
            )
        }
        viewModelScope.launch { connectionManager.connect(host) }
    }

    fun connectDiscovered(discovered: DiscoveredHost) {
        _state.update { it.copy(failure = null, attempted = discovered.authority) }
        viewModelScope.launch {
            hostsStore.addKnownPort(discovered.port)
            val config = hostsStore.rememberHost(
                name = hostLabel(discovered.host),
                host = discovered.host,
                port = discovered.port,
                isLoopback = false,
                description = discovered.description,
            )
            connectTo(config)
        }
    }

    fun forget(host: HostConfig) {
        viewModelScope.launch { hostsStore.removeHost(host.id) }
    }

    fun setAuto(key: String, value: Boolean) {
        viewModelScope.launch {
            hostsStore.setSetting { current ->
                when (key) {
                    "last" -> current.copy(autoConnectLast = value)
                    "lan" -> current.copy(autoConnectLan = value)
                    "relay" -> current.copy(autoConnectRelay = value)
                    else -> current.copy(autoConnectLoopback = value)
                }
            }
            refreshSettings()
        }
    }

    /**
     * Switch between reaching a harness over the LAN and reaching one through a relay.
     *
     * Anything in flight is dropped rather than carried across: a sweep for harnesses has nothing to
     * say about relays, and a failure from the other mode would be read against the wrong trust
     * model — a 403 means "add a trusted host" on one side and "pair again" on the other.
     */
    fun setMode(mode: String) {
        val next = ConnectMode.of(mode)
        if (next == _state.value.mode) return
        cancelScan()
        _state.update {
            it.copy(mode = next, discovered = emptyList(), scanning = false, scanProgress = null, failure = null)
        }
        viewModelScope.launch {
            hostsStore.setSetting { it.copy(connectMode = next) }
            refreshSettings()
        }
    }

    private suspend fun refreshSettings() {
        val settings = hostsStore.settingsOnce()
        _state.update {
            it.copy(
                mode = settings.connectMode,
                autoConnectLast = settings.autoConnectLast,
                autoConnectLan = settings.autoConnectLan,
                autoConnectLoopback = settings.autoConnectLoopback,
                autoConnectRelay = settings.autoConnectRelay,
            )
        }
    }

    fun clearError() = _state.update { it.copy(failure = null) }

    /**
     * A readable name for an address: reverse DNS when the network offers one, the address itself
     * otherwise. Storing the IP as the name made a card's two lines say the same thing twice.
     */
    private suspend fun hostLabel(address: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val canonical = InetAddress.getByName(address).canonicalHostName
            canonical.takeIf { it.isNotBlank() && it != address }?.substringBefore('.') ?: address
        }.getOrDefault(address)
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val DEFAULT_PORT = 3080
    }
}
