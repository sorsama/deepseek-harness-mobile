package com.labteto.dshmobile.ui.screens.connect

import com.labteto.dshmobile.connection.ConnectMode
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.DiscoveredHost
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.belongsTo
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.termux.HarnessControl

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

/** Why a launch-token exchange did not produce a browser session. */
enum class SignInError {
    /** The screen has no remembered host to sign in to — nothing was attempted yet. */
    NoHost,

    /** The harness answered and issued no session; the token is not the current process's. */
    Refused,

    /** The exchange never reached a harness. */
    Unreachable,
}

data class ConnectUiState(
    /**
     * Which way the user chose to connect: [ConnectMode.LOOPBACK], [ConnectMode.LAN] or
     * [ConnectMode.RELAY].
     *
     * A choice, never a detection. The paths have different trust models and different
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
    /**
     * Consecutive failed handshake attempts reported by the connection loop; 0 while none failed.
     *
     * Mirrored from [ConnectionManager] so the connection-lost surface can say "Attempt 2" the way
     * the design does, instead of a generic "reconnecting" that hides how often this has already
     * failed. UI-only: nothing makes decisions from it.
     */
    val attempts: Int = 0,
    /** The launch-token prompt is showing. */
    val signInOpen: Boolean = false,
    /** An exchange is in flight. */
    val signingIn: Boolean = false,
    /** Why the last exchange did not produce a session, or null. */
    val signInError: SignInError? = null,
    val autoConnectLast: Boolean = true,
    val autoConnectLan: Boolean = false,
    val autoConnectLoopback: Boolean = true,
    val autoConnectRelay: Boolean = false,
    val autoStartLoopbackHarness: Boolean = false,
    val showAdvanced: Boolean = false,
    // ---- "This phone" -----------------------------------------------------
    /** The port the harness on this device listens on; 3080 unless the user says otherwise. */
    val loopbackPort: Int = 3080,
    /** What the last probe of `127.0.0.1:loopbackPort` found. */
    val loopback: LoopbackStatus = LoopbackStatus.Unknown,
    val termuxInstalled: Boolean = false,
    /** Whether Termux's run-command permission has been granted to this app. */
    val termuxPermission: Boolean = false,
    /** What the Termux controller is doing, mirrored so the card can show it. */
    val harnessControl: HarnessControl = HarnessControl.Idle,
) {
    /** Remembered endpoints belonging to the selected mode. */
    val visibleHosts: List<HostConfig>
        get() = remembered.filter { it.belongsTo(mode) }
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
