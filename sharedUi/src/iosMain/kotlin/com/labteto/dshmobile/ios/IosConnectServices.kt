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
import com.labteto.dshmobile.core.wire.TransportFailure
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
import com.labteto.dshmobile.ui.screens.pair.PairController
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
    // Relay mode, with the Pair screen behind it (a QR read with AVFoundation, or a typed address and passcode).
    // The relay's token is in the Keychain, its key is pinned on every connection, and there is no mDNS
    // browse or port knock to find a relay, so Relay mode lists the ones this device has paired with.
    relay = true,
    // The sweep needs this device's own addresses and the Local Network permission decisions (docs/IOS.md).
    discovery = false,
    // There is no Settings screen on iOS yet.
    settings = false,
)

/** Probing an address over the Darwin engine. No sweep, no relay browsing, no raw-socket pre-flight. */
internal class IosConnectNetwork(
    private val client: HttpClient,
    private val sessions: KeychainSessionStore,
    private val relayTokens: KeychainRelayTokens,
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
        val relay = config?.isRelay == true
        // A paired relay is probed through its own record, which carries the transport, the pin and the
        // credential together: a client that enforces the relay's key, and its bearer token. Without them a
        // relay can only answer 403, which would grey out every relay card the moment it was drawn. A relay
        // paired over TLS with no key on record gets the ordinary client, which refuses a self-signed
        // certificate honestly instead of trusting it.
        val pin = config?.takeIf { it.isRelay && it.useTls }?.relayFingerprint
        val http = if (pin != null) darwinHttpClient(pinnedSpkiSha256 = pin) else client
        try {
            val api = try {
                DshApiClient(
                    KtorRpcTransport(
                        baseUrl = base,
                        client = http,
                        connectTimeoutMs = timeouts.connectMs,
                        readTimeoutMs = timeouts.readMs,
                        authorization = if (relay) relayTokens.authorization(config!!.id) else null,
                        // Behind a relay the relay holds the harness session and injects it upstream, so the
                        // phone sends none (the relay strips the header anyway).
                        cookie = if (relay) null else config?.let { sessions.cookie(it.id) },
                    ),
                )
            } catch (e: IllegalArgumentException) {
                return ProbeOutcome.Other(e.message ?: "not an address")
            }
            return when (val result = api.sessionCanOpenWorkspacePath()) {
                is RpcResult.Ok -> ProbeOutcome.Reachable(if (config == null) null else describeHome(api))
                is RpcResult.Err -> probeOutcomeOf(TransportFailures.of(result.error), relay = relay, detail = result.error.message)
            }
        } finally {
            if (http !== client) http.close()
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
    private val relayTokens: KeychainRelayTokens,
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
            val host = activeHost
            _state.value = _state.value.copy(
                failure = ConnectFailure.from(failure, relay = host?.isRelay == true),
                attempts = attempt,
            )
            if (host != null && isTerminalForRelay(host, failure)) stopRetrying()
        }
    }

    /**
     * Whether this failure means retrying is pointless against [host] (`ConnectionManager`'s rule).
     *
     * A relay answers 403 for a missing, expired or revoked credential, and none of those come back on
     * their own; a changed certificate is the same kind of fact, and so is the harness behind the relay
     * refusing the relay's own request. The loop's default is to retry forever, which against a relay that
     * revoked this device is a request every few seconds until the app is killed.
     */
    private fun isTerminalForRelay(host: HostConfig, failure: GenerationFailure): Boolean {
        if (!host.isRelay) return false
        val kind = when (failure) {
            is GenerationFailure.MuxFailed -> failure.kind
            is GenerationFailure.ReadyFailed -> TransportFailures.of(failure.error)
            is GenerationFailure.MuxTimedOut -> null
        }
        return kind == TransportFailure.TRUST_FENCE ||
            kind == TransportFailure.CERTIFICATE_PIN ||
            kind == TransportFailure.UNAUTHENTICATED
    }

    /** Stop the loop but keep the failure on screen: pairing again is the fix, and the screen has to say so. */
    private fun stopRetrying() {
        loop?.stop()
        loop = null
        _state.value = _state.value.copy(phase = ConnectionPhase.DISCONNECTED, stage = ConnectStage.Idle, attempts = 0)
    }

    override suspend fun connect(config: HostConfig) {
        disconnect()
        activeHost = config
        _state.value = ConnectionUiState(
            phase = ConnectionPhase.CONNECTING,
            host = config,
            stage = ConnectStage.OpeningStreams,
        )
        // The credential this device holds for the host, read for each attempt (a sign-in is a new attempt).
        // A harness reached directly takes the browser session it issued; a relay takes the bearer token it
        // minted at pairing, and holds the harness session itself, so none is sent (and the relay would strip
        // it anyway). A relay on TLS is reached only with the key it was paired with: the socket verifies the
        // leaf certificate against it, with no fallback to system validation.
        val relay = config.isRelay
        val cookie = if (relay) null else sessions.cookie(config.id)
        val authorization = if (relay) relayTokens.authorization(config.id) else null
        val socketFactory = platformMuxSocketFactory(
            url = config.baseUrl + REMOTE_STREAM_MUX_PATH,
            authorization = authorization,
            cookie = cookie,
            connectTimeoutMs = MUX_CONNECT_TIMEOUT_MS,
            pinnedSpkiSha256 = config.relayFingerprint.takeIf { relay && config.useTls },
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

/**
 * The iOS app's Connect and Pair controllers and what they were built from.
 *
 * [reportPair] hears the lines a script reads for the pairing flow (`DSH_PAIR_RESULT …`); null outside a
 * debug launch that asks for them.
 */
internal class IosApp(scope: CoroutineScope, reportPair: ((String) -> Unit)? = null) {
    private val sessions = KeychainSessionStore()
    private val relayTokens = KeychainRelayTokens()
    private val store = UserDefaultsConnectStore(sessions, relayTokens)
    private val client: HttpClient = darwinHttpClient()
    val link = IosConnectionLink(store, sessions, relayTokens)
    val controller = ConnectController(
        scope = scope,
        store = store,
        network = IosConnectNetwork(client, sessions, relayTokens),
        link = link,
        sessions = IosHarnessSignIn(client, sessions),
        loopbackHarness = NoLoopbackHarness,
        // No "This phone" mode here: 127.0.0.1 is just an address (the simulator, a port forward).
        loopbackIsThisPhone = false,
    )
    val pair = PairController(scope = scope, ports = IosPairPorts(store, relayTokens, link, reportPair))
}
