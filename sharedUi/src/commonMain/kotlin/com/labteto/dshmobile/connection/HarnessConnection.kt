package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.HostGeneration
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.ui.screens.connect.ConnectionLink
import kotlinx.coroutines.flow.SharedFlow

/**
 * The live connection to one harness, as everything past the Connect screen needs it: the session list, the
 * transcript and the composer.
 *
 * [ConnectionLink] is what the Connect screen drives (connect, disconnect, the phase). This adds the three things a
 * screen reads from a connection once it is up, and the one way to ask for a fresh generation. Android's
 * `ConnectionManager` and iOS's `IosConnectionLink` both implement it, so the session store can move to shared code
 * over this one port.
 */
interface HarnessConnection : ConnectionLink {
    /**
     * The current generation, or null while disconnected.
     *
     * It carries the mux (to open a session's follow stream or the control stream) and the `clientId` every waterfall
     * answer quotes. It is replaced on each reconnect, so nothing may keep the mux out of it.
     */
    val generation: HostGeneration?

    /**
     * The unary client for the active host, with that host's credential and pin, or null while disconnected.
     *
     * It is built when a connection starts and again on [reconnectIfNeeded], because a relay token can change while
     * the app is in the background and the credential is part of the client.
     */
    val connectedApi: DshApiClient?

    /**
     * The host's `$events` frames for as long as a generation is open.
     *
     * Nothing is replayed after a reconnect: state that has to survive one comes from a query or a stream baseline.
     */
    val eventFrames: SharedFlow<RemoteEventFrame>

    /**
     * Start a fresh generation for the active host now, with a rebuilt client. Does nothing while disconnected.
     * Each platform calls it when its own signals say the socket may be gone (Android: the keep-alive job and a
     * stalled reconnect; iOS: the app coming back to the foreground).
     */
    fun reconnectIfNeeded()
}
