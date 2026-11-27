package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertNotEquals
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A reconnect has to reach whoever collects the connection's state, however busy it was while it happened (#141 M6b).
 *
 * The iOS check's scenario 36 reconnects to a harness on the same machine once the session list has been read, and
 * expects the list to be read again. After the chat pane became the shared chat content it was not: the whole reconnect
 * (RECONNECTING, the handshake, CONNECTED again) happened while the main thread was composing the pane, and the state it
 * ended on was equal to the one before it, so the StateFlow had nothing new to hand the collector there. The links now
 * number each generation in the state ([ConnectionUiState.connectedGeneration]), and [statesSince] puts back the drop a
 * collector missed, so the store baselines the new socket ([needsBaseline]) as it does when it sees every step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionTransitionsTest {

    private val home = HostConfig("home-id", "home", "192.168.1.20", 3080)
    private val other = HostConfig("other-id", "other", "192.168.1.30", 3080)

    /** What both links publish when a handshake completes. */
    private fun connected(host: HostConfig, generation: Int) = ConnectionUiState(
        phase = ConnectionPhase.CONNECTED,
        host = host,
        description = HostDescription("/home/demo"),
        stage = ConnectStage.Connected,
        hasConnected = true,
        connectedGeneration = generation,
    )

    /** How many times a collector acting as the store does would read its baseline, from [start] through [handed]. */
    private fun baselines(start: ConnectionUiState, handed: List<ConnectionUiState>): Int {
        var last = start
        var count = 0
        for (latest in handed) {
            for (state in statesSince(last, latest)) {
                if (needsBaseline(last, state)) count++
                last = state
            }
        }
        return count
    }

    @Test
    fun `two generations of one host are different states`() {
        assertNotEquals(connected(home, 1), connected(home, 2))
    }

    @Test
    fun `a reconnect a busy collector missed is put back and baselined`() {
        val before = connected(home, 1)
        val after = connected(home, 2)
        assertEquals(listOf(before.copy(phase = ConnectionPhase.RECONNECTING), after), statesSince(before, after))
        assertEquals(1, baselines(before, listOf(after)))
    }

    @Test
    fun `a reconnect seen step by step is baselined once`() {
        val before = connected(home, 1)
        val dropped = before.copy(phase = ConnectionPhase.RECONNECTING)
        val handed = listOf(dropped, dropped.copy(stage = ConnectStage.OpeningStreams), connected(home, 2))
        assertEquals(1, baselines(before, handed))
    }

    @Test
    fun `the same generation handed again is no reconnect`() {
        val now = connected(home, 1)
        assertEquals(listOf(now), statesSince(now, now))
        assertEquals(0, baselines(now, listOf(now, now.copy(phase = ConnectionPhase.CONNECTED))))
    }

    @Test
    fun `the first connect is baselined`() {
        val connecting = ConnectionUiState(phase = ConnectionPhase.CONNECTING, host = home)
        assertEquals(1, baselines(connecting, listOf(connecting.copy(stage = ConnectStage.Verifying), connected(home, 1))))
    }

    @Test
    fun `a switch to another host that came and went is put back as a disconnect`() {
        val before = connected(home, 1)
        val after = connected(other, 2)
        assertEquals(listOf(ConnectionUiState(), after), statesSince(before, after))
        assertEquals(1, baselines(before, listOf(after)))
    }

    @Test
    fun `a collector busy for a whole reconnect is handed the new generation`() = runTest {
        val first = connected(home, 1)
        val handed = reconnectWhileBusy(first, connected(home, 2))
        assertEquals(listOf(first, connected(home, 2)), handed)
        assertEquals(1, baselines(first, handed.drop(1)))
    }

    @Test
    fun `without its number the new generation would not reach a busy collector`() = runTest {
        // Why the number is part of the state: an equal end state is never handed over, and nothing is baselined.
        val first = connected(home, 1)
        val handed = reconnectWhileBusy(first, connected(home, 1))
        assertEquals(listOf(first), handed)
        assertEquals(0, baselines(first, handed.drop(1)))
    }

    /**
     * Collect a connection's state on the test scheduler, then publish a whole reconnect ending on [end] without letting
     * the collector run in between, as a loop thread does while the collecting thread is busy. Returns what it was handed.
     */
    private fun TestScope.reconnectWhileBusy(
        start: ConnectionUiState,
        end: ConnectionUiState,
    ): List<ConnectionUiState> {
        val state = MutableStateFlow(start)
        val handed = mutableListOf<ConnectionUiState>()
        backgroundScope.launch { state.collect { handed += it } }
        runCurrent()
        state.value = state.value.copy(phase = ConnectionPhase.RECONNECTING)
        state.value = state.value.copy(stage = ConnectStage.OpeningStreams)
        state.value = state.value.copy(stage = ConnectStage.Verifying)
        state.value = end
        runCurrent()
        return handed.toList()
    }
}
