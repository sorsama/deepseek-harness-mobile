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
import com.labteto.dshmobile.core.wire.ServerRequest
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

/** UI-facing connection state. */
enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/** How far the readiness handshake has got, so the connect screen can say what it is doing. */
enum class ConnectStage { Idle, Validating, Reaching, OpeningStreams, Verifying, Connected }

data class ConnectionUiState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val host: HostConfig? = null,
    val description: HostDescription? = null,
    val stage: ConnectStage = ConnectStage.Idle,
    /**
     * Why the most recent generation failed, or null.
     *
     * Survives the backoff ticks between attempts on purpose: the loop keeps retrying, and wiping
     * this on every state change would blank the only explanation the user gets.
     */
    val failure: ConnectFailure? = null,
    /** Consecutive failed handshake attempts; 0 while none has failed. */
    val attempts: Int = 0,
    /** True once at least one generation completed the readiness handshake. */
    val hasConnected: Boolean = false,
)

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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ConnectionUiState())
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    private var loop: ConnectionLoop? = null
    private var api: DshApiClient? = null
    private var activeHost: HostConfig? = null

    /** Downlink frame consumers (screens subscribe here). */
    val muxFrames = kotlinx.coroutines.flow.MutableSharedFlow<ServerRequest>(extraBufferCapacity = 256)
    val hostFrames = kotlinx.coroutines.flow.MutableSharedFlow<ServerRequest>(extraBufferCapacity = 64)

    private val sinks = object : LoopSinks {
        override fun onMuxFrame(frame: ServerRequest) {
            muxFrames.tryEmit(frame)
        }

        override fun onHostFrame(frame: ServerRequest) {
            hostFrames.tryEmit(frame)
        }

        override fun onConnected(description: HostDescription) {
            val host = activeHost
            if (host != null) scope.launch { hostsStore.touchHost(host.host, host.port) }
            _state.value = ConnectionUiState(
                phase = ConnectionPhase.CONNECTED,
                host = activeHost,
                description = description,
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
                HandshakeStep.OPENING_STREAMS -> ConnectStage.OpeningStreams
                HandshakeStep.DESCRIBING -> ConnectStage.Verifying
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
    suspend fun connect(config: HostConfig) {
        disconnect()
        activeHost = config
        _state.value = ConnectionUiState(
            phase = ConnectionPhase.CONNECTING,
            host = config,
            stage = ConnectStage.OpeningStreams,
        )
        val client = clientFactory.clientFor(config)
        api = client
        val loop = ConnectionLoop(client, sinks, LoopConfig())
        this.loop = loop
        loop.start()
        hostsStore.upsertHost(config)
    }

    fun disconnect() {
        loop?.stop()
        loop = null
        api = null
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
            val client = clientFactory.clientFor(host).also { api = it }
            loop = ConnectionLoop(client, sinks, LoopConfig()).also { it.start() }
        }
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
            is GenerationFailure.StreamFailed -> failure.kind
            is GenerationFailure.DescribeFailed -> TransportFailures.of(failure.error)
            is GenerationFailure.StreamsTimedOut -> null
        }
        return kind == TransportFailure.TRUST_FENCE || kind == TransportFailure.CERTIFICATE_PIN
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
