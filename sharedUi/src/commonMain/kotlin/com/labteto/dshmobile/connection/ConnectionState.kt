package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.ui.screens.connect.ConnectFailure

/** UI-facing connection state. */
enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/**
 * How far the readiness handshake has got, so the connect screen can say what it is doing.
 *
 * `OpeningStreams` is now one socket rather than two, and `Verifying` is waiting for the
 * host's ready frame rather than a `host.describe` answer. The names are kept because what
 * they mean to someone watching the screen has not changed.
 */
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
    /**
     * Which generation this state's connection is, counted by the link over its whole life: 0 before the first
     * handshake, then a new number each time one completes. It is kept through the drop that follows.
     *
     * It is what makes a reconnect visible in the state itself. A whole reconnect (the socket drops, RECONNECTING, a
     * new handshake) otherwise ends on a CONNECTED state equal to the one before it, and a StateFlow hands a collector
     * only its latest value and skips one equal to the last it delivered: a collector that was busy for the length of a
     * fast handshake (a harness on the same machine or the LAN, a main thread composing a screen) never learnt that
     * the socket under it was replaced. With the number, the new generation is a new value; [statesSince] puts back
     * the drop the collector missed.
     */
    val connectedGeneration: Int = 0,
)

/**
 * The states a collector of a connection's state steps through to get from [prev], the last value it acted on, to
 * [next], the value it has just been handed.
 *
 * Usually that is [next] alone. A StateFlow conflates, though, so a collector that was busy while the connection moved
 * on is handed only where it ended up, and two moves end where they began:
 * - a reconnect of the same host, CONNECTED to CONNECTED with a later [ConnectionUiState.connectedGeneration]: the drop
 *   in between is put back, as [ConnectionPhase.RECONNECTING];
 * - a switch from a host that had connected to another that is connected now: the disconnect in between is put back,
 *   as the empty state a disconnect leaves.
 *
 * A collector that acts on each state in turn then does what it would have done had it seen every value: a reconnect
 * is baselined again ([needsBaseline]), another host starts from nothing.
 */
internal fun statesSince(prev: ConnectionUiState, next: ConnectionUiState): List<ConnectionUiState> = when {
    !sameHost(prev, next) && prev.hasConnected && next.phase == ConnectionPhase.CONNECTED ->
        listOf(ConnectionUiState(), next)
    sameHost(prev, next) &&
        prev.phase == ConnectionPhase.CONNECTED && next.phase == ConnectionPhase.CONNECTED &&
        prev.connectedGeneration != next.connectedGeneration ->
        listOf(prev.copy(phase = ConnectionPhase.RECONNECTING), next)
    else -> listOf(next)
}

/** Whether [prev] and [next] are the same harness: sessions behind different proxy roots belong to different hosts. */
internal fun sameHost(prev: ConnectionUiState, next: ConnectionUiState): Boolean =
    prev.host?.id == next.host?.id && prev.host?.baseUrl == next.host?.baseUrl

/**
 * Whether going from [prev] to [next] is a connection a collector has to read its baseline on again: the first
 * handshake with a host, or the end of a reconnect. Step through [statesSince] first, or a reconnect that came and went
 * between two values is not one.
 */
internal fun needsBaseline(prev: ConnectionUiState, next: ConnectionUiState): Boolean {
    val initialConnect = !prev.hasConnected && next.hasConnected
    val reconnect = prev.hasConnected &&
        prev.phase == ConnectionPhase.RECONNECTING &&
        next.phase == ConnectionPhase.CONNECTED
    return initialConnect || reconnect
}
