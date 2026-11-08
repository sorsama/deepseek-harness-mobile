package com.labteto.dshmobile.ios

import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.DiscoveredHost
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.connection.ProbeTimeouts
import com.labteto.dshmobile.connection.harnessBaseUrl
import com.labteto.dshmobile.connection.probeOutcomeOf
import com.labteto.dshmobile.core.wire.ConnectionLoop
import com.labteto.dshmobile.core.wire.ConnectionState
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.GenerationFailure
import com.labteto.dshmobile.core.wire.HandshakeStep
import com.labteto.dshmobile.core.wire.HarnessSession
import com.labteto.dshmobile.core.wire.HostGeneration
import com.labteto.dshmobile.core.wire.KtorPlainHttp
import com.labteto.dshmobile.core.wire.KtorRpcTransport
import com.labteto.dshmobile.core.wire.LoopConfig
import com.labteto.dshmobile.core.wire.LoopSinks
import com.labteto.dshmobile.core.wire.RemoteStreamMux
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.SessionExchange
import com.labteto.dshmobile.core.wire.TransportFailures
import com.labteto.dshmobile.core.wire.darwinHttpClient
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.core.wire.dto.REMOTE_STREAM_MUX_PATH
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.core.wire.platformMuxSocketFactory
import com.labteto.dshmobile.ui.screens.connect.ConnectController
import com.labteto.dshmobile.ui.screens.connect.ConnectFailure
import com.labteto.dshmobile.ui.screens.connect.ConnectFeatures
import com.labteto.dshmobile.ui.screens.connect.ConnectNetwork
import com.labteto.dshmobile.ui.screens.connect.ConnectionLink
import com.labteto.dshmobile.ui.screens.connect.HarnessSignIn
import com.labteto.dshmobile.ui.screens.connect.NoLoopbackHarness
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * The iOS halves of the ports `ConnectController` asks for (ConnectPorts.kt in commonMain): the
 * Darwin HTTP client for probes and sign-in, the Darwin mux socket for the live connection,
 * NSUserDefaults and the Keychain for what is remembered (IosConnectStore.kt, IosKeychain.kt).
 *
 * They are the same sequences the Android adapters run over OkHttp: one unary `session/canOpenWorkspacePath`
 * call decides what answered, then `ConnectionLoop` opens the mux and reads the ready frame.
 */

/** What the iOS Connect screen offers today; see [ConnectFeatures]. */
internal val IosConnectFeatures = ConnectFeatures(
    // iOS does not allow the background processes Termux relies on, so there is no harness on the device.
    thisPhone = false,
    // Relay mode needs the pairing screen (QR scan with AVFoundation, passcode), which is not shared yet.
    relay = false,
    // The sweep needs this device's own addresses and the Local Network permission decisions (docs/IOS.md).
    discovery = false,
    // There is no Settings screen on iOS yet.
    settings = false,
)

/** Probing an address over the Darwin engine. No sweep, no relay browsing, no raw-socket pre-flight. */
internal class IosConnectNetwork(
    private val client: HttpClient,
    private val sessions: KeychainSessionStore,
) : ConnectNetwork {

    override suspend fun probe(host: String, port: Int, timeouts: ProbeTimeouts, config: HostConfig?): HostDescription? =
        (probeOutcome(host, port, timeouts, config = config) as? ProbeOutcome.Reachable)?.description

    /**
     * One unary call, `session/canOpenWorkspacePath`, which is what the Android probe asks too: no arguments, served
     * by every harness, and its failures arrive classified (refused, timeout, DNS, 403 for the `Host` fence, 401 for
     * no browser session, not a harness, TLS). [preflight] is Android's raw-socket check that separates a drop from a
     * refusal; `NSURLSession` reports the two apart by itself, so it is not made here.
     */
    override suspend fun probeOutcome(
        host: String,
        port: Int,
        timeouts: ProbeTimeouts,
        preflight: Boolean,
        useTls: Boolean,
        config: HostConfig?,
        basePath: String,
    ): ProbeOutcome {
        val base = config?.baseUrl ?: harnessBaseUrl(host, port, useTls, basePath)
        val api = try {
            DshApiClient(
                KtorRpcTransport(
                    baseUrl = base,
                    client = client,
                    connectTimeoutMs = timeouts.connectMs,
                    readTimeoutMs = timeouts.readMs,
                    cookie = config?.let { sessions.cookie(it.id) },
                ),
            )
        } catch (e: IllegalArgumentException) {
            return ProbeOutcome.Other(e.message ?: "not an address")
        }
        return when (val result = api.sessionCanOpenWorkspacePath()) {
            is RpcResult.Ok -> ProbeOutcome.Reachable(if (config == null) null else describeHome(api))
            is RpcResult.Err -> probeOutcomeOf(TransportFailures.of(result.error), relay = false, detail = result.error.message)
        }
    }

    /** The host's home, for a host worth a second call: a directory listing carries it. */
    private suspend fun describeHome(api: DshApiClient): HostDescription? =
        (api.hostListDirectory() as? RpcResult.Ok)?.value?.home?.let { HostDescription(it) }

    override suspend fun scan(
        ports: List<Int>,
        onProgress: (probed: Int, total: Int) -> Unit,
        onFound: (DiscoveredHost) -> Unit,
    ): List<DiscoveredHost> = emptyList()

    override suspend fun scanForRelays(onFound: (DiscoveredHost) -> Unit): List<DiscoveredHost> = emptyList()

    override suspend fun browseRelays(onFound: (DiscoveredHost) -> Unit): List<DiscoveredHost> = emptyList()

    /** There is no sweep to be out of range of, so no address is refused for being off this device's /24. */
    override suspend fun isOnLocalSubnet(host: String): Boolean = true

    override suspend fun localSubnetLabel(): String? = null

    /** No reverse DNS: the address is its own name. */
    override suspend fun hostLabel(address: String): String = address
}

/** The launch-token exchange over the Darwin engine; the session it returns goes to the Keychain. */
internal class IosHarnessSignIn(
    private val client: HttpClient,
    private val sessions: KeychainSessionStore,
) : HarnessSignIn {
    override suspend fun pair(hostId: String, baseUrl: String, tokenInput: String): SessionExchange {
        val token = HarnessSession.tokenFrom(tokenInput) ?: return SessionExchange.Refused(0)
        val outcome = HarnessSession.exchange(baseUrl, token, KtorPlainHttp(client))
        if (outcome is SessionExchange.Granted) sessions.put(hostId, outcome.cookie)
        return outcome
    }
}

/**
 * The live connection to one harness: the connection loop over the Darwin mux socket, and what it
 * reports. The Android `ConnectionManager` minus what iOS has no use for yet (the foreground service,
 * relay credentials, the event stream the chat will read).
 */
internal class IosConnectionLink(
    private val store: UserDefaultsConnectStore,
    private val sessions: KeychainSessionStore,
) : ConnectionLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ConnectionUiState())
    override val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    private var loop: ConnectionLoop? = null
    private var activeHost: HostConfig? = null

    private val sinks = object : LoopSinks {
        override fun onEventFrame(frame: RemoteEventFrame) = Unit

        override fun onConnected(generation: HostGeneration) {
            val host = activeHost
            if (host != null) scope.launch { store.touchHost(host.id) }
            _state.value = ConnectionUiState(
                phase = ConnectionPhase.CONNECTED,
                host = host,
                description = generation.description,
                stage = ConnectStage.Connected,
                failure = null,
                attempts = 0,
                hasConnected = true,
            )
        }

        override fun onStateChange(state: ConnectionState) {
            val current = _state.value
            val phase = when {
                state == ConnectionState.CONNECTED -> ConnectionPhase.CONNECTED
                // The first generation is not a reconnect; see ConnectionManager for why that matters.
                current.hasConnected -> ConnectionPhase.RECONNECTING
                else -> ConnectionPhase.CONNECTING
            }
            // Does not clear `failure`: the loop emits this on every retry, and clearing it would erase
            // the explanation a fraction of a second after showing it.
            _state.value = current.copy(phase = phase)
        }

        override fun onHandshakeStep(step: HandshakeStep) {
            val stage = when (step) {
                HandshakeStep.OPENING_MUX -> ConnectStage.OpeningStreams
                HandshakeStep.AWAITING_READY -> ConnectStage.Verifying
            }
            _state.value = _state.value.copy(stage = stage)
        }

        override fun onGenerationFailed(attempt: Int, failure: GenerationFailure) {
            _state.value = _state.value.copy(failure = ConnectFailure.from(failure, relay = false), attempts = attempt)
        }
    }

    override suspend fun connect(config: HostConfig) {
        disconnect()
        activeHost = config
        _state.value = ConnectionUiState(
            phase = ConnectionPhase.CONNECTING,
            host = config,
            stage = ConnectStage.OpeningStreams,
        )
        // The session this device holds for the host, read for each attempt (a sign-in is a new attempt).
        val cookie = sessions.cookie(config.id)
        val socketFactory = platformMuxSocketFactory(
            url = config.baseUrl + REMOTE_STREAM_MUX_PATH,
            cookie = cookie,
            connectTimeoutMs = MUX_CONNECT_TIMEOUT_MS,
        )
        val next = ConnectionLoop(muxFactory = { RemoteStreamMux(socketFactory) }, sinks = sinks, config = LoopConfig())
        loop = next
        next.start()
        store.upsertHost(config)
    }

    override fun disconnect() {
        loop?.stop()
        loop = null
        activeHost = null
        _state.value = ConnectionUiState()
    }

    private companion object {
        /** The socket's own handshake clock; the loop abandons a generation at its own 3 s. */
        const val MUX_CONNECT_TIMEOUT_MS = 3_000L
    }
}

/** The iOS app's one Connect controller and what it was built from. */
internal class IosApp(scope: CoroutineScope) {
    private val sessions = KeychainSessionStore()
    private val store = UserDefaultsConnectStore(sessions)
    private val client: HttpClient = darwinHttpClient()
    val link = IosConnectionLink(store, sessions)
    val controller = ConnectController(
        scope = scope,
        store = store,
        network = IosConnectNetwork(client, sessions),
        link = link,
        sessions = IosHarnessSignIn(client, sessions),
        loopbackHarness = NoLoopbackHarness,
        // No "This phone" mode here: 127.0.0.1 is just an address (the simulator, a port forward).
        loopbackIsThisPhone = false,
    )
}
