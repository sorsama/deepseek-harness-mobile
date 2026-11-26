package com.labteto.dshmobile.ui

import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.testing.assertEquals
import kotlin.test.Test

class RootRouteTest {

    private fun route(
        phase: ConnectionPhase,
        hasConnected: Boolean = false,
        showSettings: Boolean = false,
        showPair: Boolean = false,
    ) = rootRouteOf(phase, hasConnected, showSettings, showPair)

    @Test
    fun `nothing connected is the Connect screen`() {
        assertEquals(RootRoute.Connect, route(ConnectionPhase.DISCONNECTED))
    }

    @Test
    fun `a first connection on its way stays on the Connect screen`() {
        assertEquals(RootRoute.Connect, route(ConnectionPhase.CONNECTING))
        assertEquals(RootRoute.Connect, route(ConnectionPhase.RECONNECTING, hasConnected = false))
    }

    @Test
    fun `a connected link is the main screen`() {
        assertEquals(RootRoute.Main, route(ConnectionPhase.CONNECTED))
        assertEquals(RootRoute.Main, route(ConnectionPhase.CONNECTED, hasConnected = true))
    }

    @Test
    fun `a link that was up keeps the main screen while it reconnects`() {
        assertEquals(RootRoute.Main, route(ConnectionPhase.RECONNECTING, hasConnected = true))
    }

    @Test
    fun `a link that was up and dropped for good goes back to Connect`() {
        assertEquals(RootRoute.Connect, route(ConnectionPhase.DISCONNECTED, hasConnected = true))
        assertEquals(RootRoute.Connect, route(ConnectionPhase.CONNECTING, hasConnected = true))
    }

    @Test
    fun `Settings wins over every connection phase`() {
        ConnectionPhase.entries.forEach { phase ->
            assertEquals(RootRoute.Settings, route(phase, hasConnected = true, showSettings = true))
            assertEquals(RootRoute.Settings, route(phase, showSettings = true, showPair = true))
        }
    }

    @Test
    fun `Pair wins over the connection and not over Settings`() {
        ConnectionPhase.entries.forEach { phase ->
            assertEquals(RootRoute.Pair, route(phase, hasConnected = true, showPair = true))
        }
        assertEquals(RootRoute.Settings, route(ConnectionPhase.CONNECTED, showSettings = true, showPair = true))
    }
}
