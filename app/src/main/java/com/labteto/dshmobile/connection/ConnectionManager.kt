package com.labteto.dshmobile.connection

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.labteto.dshmobile.core.wire.ConnectionLoop
import com.labteto.dshmobile.core.wire.ConnectionState
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.core.wire.LoopConfig
import com.labteto.dshmobile.core.wire.GenerationFailure
import com.labteto.dshmobile.core.wire.HandshakeStep
import com.labteto.dshmobile.core.wire.LoopSinks
import com.labteto.dshmobile.ui.screens.connect.ConnectFailure
import com.labteto.dshmobile.ui.screens.connect.ConnectionLink
import com.labteto.dshmobile.core.wire.HostGeneration
import com.labteto.dshmobile.core.wire.RemoteStreamMux
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the live connection to one harness: the ConnectionLoop (readiness
 * handshake + reconnect/backoff), the foreground service binding for
 * background operation, and the UI state mirror. Single active host at a time.
 */
@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientFactory: HarnessClientFactory,
    private val hostsStore: HostsStore,
) : ConnectionLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ConnectionUiState())
    override val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    private var loop: ConnectionLoop? = null
    private var api: DshApiClient? = null
    private var activeHost: HostConfig? = null

    /**
     * The current generation, or null while disconnected.
     *
     * Screens need it for two things the unary client cannot give them: the mux, to open a
     * session journal or the control stream, and the `clientId` every waterfall answer has to
     * carry. It is replaced wholesale on each reconnect, so nothing may cache the mux out of it.
     */
    @Volatile
    var generation: HostGeneration? = null
        private set

    /**
     * Host event frames (screens subscribe here).
     *
     * One flow now, where there were two: 0.1.2 delivers notifications and pending waterfalls
     * on the single `$events` stream, and the mux/host split it replaced no longer exists.
     *
     * Nothing here is replayed after a reconnect. State that must survive one has to come from
     * a query or a stream baseline instead — an `emit` that arrives while disconnected is gone.
     */
    val eventFrames = kotlinx.coroutines.flow.MutableSharedFlow<RemoteEventFrame>(extraBufferCapacity = 256)

    private val sinks = object : LoopSinks {
        override fun onEventFrame(frame: RemoteEventFrame) {
            eventFrames.tryEmit(frame)
        }

        override fun onConnected(generation: HostGeneration) {
            this@ConnectionManager.generation = generation
            val host = activeHost
            if (host != null) scope.launch { hostsStore.touchHost(host.id) }
            _state.value = ConnectionUiState(
                phase = ConnectionPhase.CONNECTED,
                host = activeHost,
                description = generation.description,
                stage = ConnectStage.Connected,
                failure = null,
                attempts = 0,
                hasConnected = true,
            )
            maybeStartService()
        }

        override fun onStateChange(state: ConnectionState) {
            val current = _state.value
            val phase = when {
                state == ConnectionState.CONNECTED -> ConnectionPhase.CONNECTED
                // The loop opens every generation the same way, but the first one is not a
                // *re*connect — calling it that is what let a never-connected attempt look like a
                // healthy session dropping, and hid it from the connect screen entirely.
                current.hasConnected -> ConnectionPhase.RECONNECTING
                else -> ConnectionPhase.CONNECTING
            }
            // Note: does not clear `failure`. The loop emits this on every retry, so clearing here
            // would erase the explanation a fraction of a second after showing it.
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

    val connectedApi: DshApiClient? get() = api

    /**
     * Start driving [config]. Progress and failure arrive through [state], not a callback.
     *
     * There used to be a 2500ms timer here that reported failure if the phase was still CONNECTING.
     * It could never fire: the loop's first act is to publish RECONNECTING, so the phase had always
     * moved on by the time the timer checked. The result was a Connect button that stayed disabled
     * forever with nothing on screen. The loop now reports each failed generation directly, which
     * is both sooner and specific.
     */
    override suspend fun connect(config: HostConfig) {
        disconnect()
        activeHost = config
        _state.value = ConnectionUiState(
            phase = ConnectionPhase.CONNECTING,
            host = config,
            stage = ConnectStage.OpeningStreams,
        )
        api = clientFactory.clientFor(config)
        val loop = ConnectionLoop(muxFactory(config), sinks, LoopConfig())
        this.loop = loop
        loop.start()
        hostsStore.upsertHost(config)
    }

    override fun disconnect() {
        loop?.stop()
        loop = null
        api = null
        generation = null
        activeHost = null
        stopService()
        _state.value = ConnectionUiState()
    }

    fun reconnectIfNeeded() {
        val host = activeHost ?: return
        loop?.stop()
        scope.launch {
            // Rebuilding through the factory rather than reusing `api` blindly: a relay token can be
            // rotated or dropped while the app is backgrounded, and the credential is baked into the
            // client at construction. This is the path [KeepAliveWorker] takes, which is exactly
            // when that is most likely to have happened.
            api = clientFactory.clientFor(host)
            loop = ConnectionLoop(muxFactory(host), sinks, LoopConfig()).also { it.start() }
        }
    }

    /**
     * A per-generation mux builder for [host].
     *
     * The loop calls this once per attempt and owns the socket's lifetime, so the credential is
     * re-read on every reconnect rather than baked in once. That matters for the same reason
     * [reconnectIfNeeded] rebuilds its client: a relay token can rotate while the app is
     * backgrounded, and a socket built with the old one is refused at the upgrade.
     */
    private fun muxFactory(host: HostConfig): () -> RemoteStreamMux = {
        kotlinx.coroutines.runBlocking { clientFactory.muxFor(host) }
    }

    /**
     * Whether this failure means retrying is pointless against [host].
     *
     * The relay answers 403 for a missing, expired or revoked credential, and none of those come
     * back on their own — the client integration contract says so outright: "prompt to pair again,
     * do not retry with backoff". A changed certificate is the same kind of fact. The loop's default
     * is to retry forever, which against a relay that revoked this device is a request every few
     * seconds until the app is killed.
     */
    private fun isTerminalForRelay(host: HostConfig, failure: GenerationFailure): Boolean {
        if (!host.isRelay) return false
        val kind = when (failure) {
            is GenerationFailure.MuxFailed -> failure.kind
            is GenerationFailure.ReadyFailed -> TransportFailures.of(failure.error)
            is GenerationFailure.MuxTimedOut -> null
        }
        // UNAUTHENTICATED is terminal for a different reason. Behind a relay it is the harness
        // refusing the relay's own request, not this device's credential — but nothing the phone
        // retries will make the relay sign in; that takes a relay fixed on its own computer.
        return kind == TransportFailure.TRUST_FENCE ||
            kind == TransportFailure.CERTIFICATE_PIN ||
            kind == TransportFailure.UNAUTHENTICATED
    }

    /**
     * Stop the loop but keep the failure on screen.
     *
     * Not [disconnect]: that resets the whole state object, which would wipe the very explanation
     * the user needs in order to know that pairing again is the fix.
     */
    private fun stopRetrying() {
        loop?.stop()
        loop = null
        _state.value = _state.value.copy(
            phase = ConnectionPhase.DISCONNECTED,
            // Back to Idle, not left on whatever handshake step the last generation died at. The
            // connect screen derives "still connecting" from the stage, so a stage frozen mid-
            // handshake leaves the Connect button disabled with a spinner that will never finish —
            // which is precisely the failure this screen already learned once.
            stage = ConnectStage.Idle,
            attempts = 0,
        )
    }

    private fun maybeStartService() {
        val settings = runBlockingRead { hostsStore.settingsOnce() }
        if (settings.keepConnectedInBackground) startService()
    }

    private fun startService() {
        val intent = Intent(context, ConnectionService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopService() {
        context.stopService(Intent(context, ConnectionService::class.java))
    }

    private fun <T> runBlockingRead(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
