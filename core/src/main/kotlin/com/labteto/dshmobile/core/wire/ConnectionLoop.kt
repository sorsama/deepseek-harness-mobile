package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.core.wire.dto.HostFrame
import com.labteto.dshmobile.core.wire.dto.HostFrameSerializer
import com.labteto.dshmobile.core.wire.dto.MuxFrame
import com.labteto.dshmobile.core.wire.dto.MuxFrameSerializer
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

/** Loop connection state: `connected` once both streams + host.describe succeeded. */
enum class ConnectionState {
    CONNECTED,
    RECONNECTING,
}

/** Which part of the readiness handshake is under way. */
enum class HandshakeStep {
    /** Both downlink WebSockets are being opened. */
    OPENING_STREAMS,

    /** The streams are up; `host.describe` is in flight. */
    DESCRIBING,
}

/** Why one generation failed to reach [ConnectionState.CONNECTED]. */
sealed class GenerationFailure {
    /** Neither stream finished opening inside the budget. */
    data class StreamsTimedOut(val timeoutMs: Long) : GenerationFailure()

    /** A stream failed outright — a refused connection, or a rejected upgrade. */
    data class StreamFailed(val kind: TransportFailure, val message: String?) : GenerationFailure()

    /** The streams opened but `host.describe` did not answer usefully. */
    data class DescribeFailed(val error: RpcError) : GenerationFailure()
}

/** Callbacks from the connection loop. Exceptions thrown here never kill the loop. */
interface LoopSinks {
    /** One parsed mux-stream frame (including stream/error frames). */
    fun onMuxFrame(frame: ServerRequest)

    /** One parsed host-stream frame (including stream/error frames). */
    fun onHostFrame(frame: ServerRequest)

    /** The readiness handshake completed: both streams open and host.describe succeeded. */
    fun onConnected(description: HostDescription)

    /** The connection state changed. */
    fun onStateChange(state: ConnectionState)

    /** Progress within the current generation's handshake. Default no-op. */
    fun onHandshakeStep(step: HandshakeStep) {}

    /**
     * One generation failed; [attempt] is 1 for the first. The loop keeps retrying afterwards —
     * this reports *why* so a caller can say so rather than waiting on a timer that cannot know.
     * Default no-op.
     */
    fun onGenerationFailed(attempt: Int, failure: GenerationFailure) {}
}

/**
 * Reconnect/backoff policy. `delay` is injectable so tests can observe backoff growth without
 * real timers.
 */
class LoopConfig(
    /** Base backoff delay in ms (first reconnect sleeps in [baseDelayMs/2, baseDelayMs]). */
    val baseDelayMs: Long = 500L,
    /** Exponential growth factor per consecutive failed generation. */
    val backoffFactor: Double = 2.0,
    /** Upper bound on the per-attempt backoff cap in ms. */
    val maxDelayMs: Long = 10_000L,
    /** Jitter span bound: the actual sleep is uniform in [cap/2, cap] of the attempt cap. */
    val jitterCapMs: Long = 10_000L,
    /** How long a generation may take to open both streams before it is abandoned. */
    val streamOpenTimeoutMs: Long = 3_000L,
    /** Injectable sleep used between generations. */
    val delay: suspend (Long) -> Unit = ::defaultSleep,
)

/** Default reconnect sleep (delegates to kotlinx.coroutines.delay). */
private suspend fun defaultSleep(ms: Long) {
    delay(ms)
}

/**
 * Owns the readiness handshake and reconnect loop for the two downlink streams:
 *
 * 1. Open both WebSockets (`/api/events.mux` and `/api/events.host`) and wait for both opens
 *    (stream-open timeout 3s).
 * 2. Call `host.describe`; when it succeeds the loop is [ConnectionState.CONNECTED].
 * 3. Stream frames to [LoopSinks] until a `stream/error` frame, a socket close, or a failure
 *    terminates the generation — then reconnect with exponential backoff
 *    (base 500ms, factor 2, max 10s, jitter cap/2..cap).
 *
 * [start] and [stop] are idempotent. Sink exceptions are contained and never kill the loop.
 */
class ConnectionLoop(
    private val api: DshApiClient,
    private val sinks: LoopSinks,
    private val config: LoopConfig = LoopConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var generation: Generation? = null

    /** Begin the loop. Idempotent. */
    fun start() {
        if (job != null) return
        val newJob = scope.launch { runLoop() }
        job = newJob
    }

    /** Stop the loop and tear down any open streams. Idempotent. */
    fun stop() {
        val current = job ?: return
        job = null
        current.cancel()
        closeGeneration()
    }

    // ---------------------------------------------------------------- loop

    private suspend fun runLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            safeSink { sinks.onStateChange(ConnectionState.RECONNECTING) }
            when (val opened = openGeneration()) {
                is Opened.Ok -> {
                    attempt = 0
                    generation = opened.generation
                    safeSink { sinks.onConnected(opened.generation.description) }
                    safeSink { sinks.onStateChange(ConnectionState.CONNECTED) }
                    consumeGeneration(opened.generation)
                    closeGeneration()
                }

                is Opened.Failed -> {
                    attempt += 1
                    val current = attempt
                    safeSink { sinks.onGenerationFailed(current, opened.failure) }
                }
            }
            if (!currentCoroutineContext().isActive) break
            config.delay(nextBackoff(attempt))
        }
    }

    /** Outcome of one handshake attempt: the open generation, or why it did not open. */
    private sealed class Opened {
        data class Ok(val generation: Generation) : Opened()
        data class Failed(val failure: GenerationFailure) : Opened()
    }

    /** The open streams of the current generation, plus their frame/failure channels. */
    private class Generation(
        val muxWs: WsDownlink,
        val hostWs: WsDownlink,
        val muxFrames: Channel<ServerRequest>,
        val hostFrames: Channel<ServerRequest>,
        val muxFailed: CompletableDeferred<Throwable>,
        val hostFailed: CompletableDeferred<Throwable>,
        val description: HostDescription,
    )

    private class BridgeSink(
        private val frames: Channel<ServerRequest>,
        private val opened: CompletableDeferred<Unit>,
        private val failed: CompletableDeferred<Throwable>,
    ) : WsDownlinkSink {
        override fun onFrame(frame: ServerRequest) {
            frames.trySend(frame)
        }

        override fun onOpen() {
            opened.complete(Unit)
        }

        override fun onClosed(cause: Throwable?) {
            if (cause != null) {
                failed.complete(cause)
            } else if (!failed.isCompleted) {
                failed.complete(StreamClosedException())
            }
        }
    }

    /** Open both streams and complete the readiness handshake, or report why it did not. */
    private suspend fun openGeneration(): Opened {
        val muxFrames = Channel<ServerRequest>(Channel.UNLIMITED)
        val hostFrames = Channel<ServerRequest>(Channel.UNLIMITED)
        val muxOpened = CompletableDeferred<Unit>()
        val hostOpened = CompletableDeferred<Unit>()
        val muxFailed = CompletableDeferred<Throwable>()
        val hostFailed = CompletableDeferred<Throwable>()

        safeSink { sinks.onHandshakeStep(HandshakeStep.OPENING_STREAMS) }
        val muxWs = api.openEvents(mux = true, sink = BridgeSink(muxFrames, muxOpened, muxFailed))
        val hostWs = api.openEvents(mux = false, sink = BridgeSink(hostFrames, hostOpened, hostFailed))
        muxWs.start()
        hostWs.start()

        val opened = try {
            withTimeout(config.streamOpenTimeoutMs) {
                muxOpened.await()
                hostOpened.await()
            }
            true
        } catch (e: TimeoutCancellationException) {
            false
        }
        if (!opened || muxFailed.isCompleted || hostFailed.isCompleted) {
            // A stream that failed outright says more than the timeout does — a rejected upgrade
            // carries its status. `await` on an already-completed deferred returns at once, which
            // avoids the experimental getCompleted().
            val cause = when {
                muxFailed.isCompleted -> muxFailed.await()
                hostFailed.isCompleted -> hostFailed.await()
                else -> null
            }
            muxWs.close()
            hostWs.close()
            return Opened.Failed(
                if (cause != null) {
                    GenerationFailure.StreamFailed(TransportFailures.classify(cause), cause.message)
                } else {
                    GenerationFailure.StreamsTimedOut(config.streamOpenTimeoutMs)
                },
            )
        }

        // Describing is the last handshake step, and DshApiClient.hostDescribe latches this
        // connection's command-image capability from the answer — so by the time anyone can
        // reach a command through this client, its `commands/execute` shape is already decided.
        safeSink { sinks.onHandshakeStep(HandshakeStep.DESCRIBING) }
        return when (val describe = api.hostDescribe()) {
            is RpcResult.Ok -> Opened.Ok(
                Generation(
                    muxWs = muxWs,
                    hostWs = hostWs,
                    muxFrames = muxFrames,
                    hostFrames = hostFrames,
                    muxFailed = muxFailed,
                    hostFailed = hostFailed,
                    description = describe.value,
                ),
            )
            is RpcResult.Err -> {
                muxWs.close()
                hostWs.close()
                Opened.Failed(GenerationFailure.DescribeFailed(describe.error))
            }
        }
    }

    /** Forward frames until the generation ends (stream/error, close, or failure). */
    private suspend fun consumeGeneration(gen: Generation) {
        while (currentCoroutineContext().isActive) {
            val event = select<ConsumeEvent> {
                gen.muxFrames.onReceiveCatching { result ->
                    result.getOrNull()?.let { ConsumeEvent.Frame(it, true) } ?: ConsumeEvent.StreamEnded
                }
                gen.hostFrames.onReceiveCatching { result ->
                    result.getOrNull()?.let { ConsumeEvent.Frame(it, false) } ?: ConsumeEvent.StreamEnded
                }
                gen.muxFailed.onAwait { ConsumeEvent.StreamEnded }
                gen.hostFailed.onAwait { ConsumeEvent.StreamEnded }
            }
            when (event) {
                is ConsumeEvent.Frame -> {
                    safeSink {
                        if (event.mux) sinks.onMuxFrame(event.frame) else sinks.onHostFrame(event.frame)
                    }
                    if (isStreamError(event.frame, event.mux)) return
                }
                ConsumeEvent.StreamEnded -> return
            }
        }
    }

    private sealed class ConsumeEvent {
        data class Frame(val frame: ServerRequest, val mux: Boolean) : ConsumeEvent()
        object StreamEnded : ConsumeEvent()
    }

    /** Whether the frame's payload is a stream/error frame of either stream. */
    private fun isStreamError(frame: ServerRequest, mux: Boolean): Boolean {
        return try {
            if (mux) {
                decodeFromJsonElement(MuxFrameSerializer, frame.payload) is MuxFrame.StreamError
            } else {
                decodeFromJsonElement(HostFrameSerializer, frame.payload) is HostFrame.StreamError
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Tear down the current generation's sockets. */
    private fun closeGeneration() {
        val gen = generation
        generation = null
        if (gen != null) {
            runCatching { gen.muxWs.close() }
            runCatching { gen.hostWs.close() }
        }
    }

    /**
     * Exponential backoff with jitter: the attempt cap is min(maxDelay, base * factor^attempt),
     * and the actual sleep is uniform in [cap/2, cap].
     */
    private fun nextBackoff(attempt: Int): Long {
        val cap = minOf(
            config.maxDelayMs,
            (config.baseDelayMs * config.backoffFactor.pow(attempt)).toLong(),
        ).coerceAtLeast(config.baseDelayMs)
        val bounded = minOf(cap, config.jitterCapMs)
        val half = bounded / 2
        val span = bounded - half
        return if (span <= 0) half else half + Random.nextLong(span + 1)
    }

    /** Sink exceptions must not kill the loop. */
    private inline fun safeSink(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            // Contain: a misbehaving sink must not take the loop down.
        }
    }
}

/** Sentinel thrown into the failed channel when a socket closed without an error. */
private class StreamClosedException : Exception("downlink stream closed")

