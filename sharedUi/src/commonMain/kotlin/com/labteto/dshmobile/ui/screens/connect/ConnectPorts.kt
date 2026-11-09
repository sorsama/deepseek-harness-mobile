package com.labteto.dshmobile.ui.screens.connect

import com.labteto.dshmobile.connection.ConnectMode
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.connection.DiscoveredHost
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.connection.ProbeTimeouts
import com.labteto.dshmobile.core.wire.SessionExchange
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.termux.HarnessControl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/*
 * What `ConnectController` asks of a platform.
 *
 * Five small interfaces, each the shape of something the Android app already had as a concrete class
 * (`HostsStore`, `DiscoveryEngine` and `MdnsDiscovery`, `ConnectionManager`, `HarnessSessionStore`,
 * `TermuxHarnessController`). On Android they are implemented by thin adapters over those classes and
 * the data does not move; on iOS they are implemented in `iosMain` over `NSUserDefaults`, the Darwin
 * HTTP client and the Darwin mux socket. A platform that cannot do something (a subnet sweep, a
 * harness on the device) says so by implementing the port as "none", and the screen is told through
 * `ConnectFeatures` to hide what could never work.
 */

/** The slice of the app's settings the Connect screen reads and writes. Defaults are the app's. */
data class ConnectSettings(
    val connectMode: String = ConnectMode.LAN,
    val autoConnectLast: Boolean = true,
    val autoConnectLan: Boolean = false,
    val autoConnectLoopback: Boolean = true,
    val autoConnectRelay: Boolean = false,
    val autoStartLoopbackHarness: Boolean = false,
    val knownPorts: List<Int> = listOf(3080),
)

/**
 * Remembered harnesses and the settings of the Connect screen.
 *
 * Android: `HostsStore` (Preferences DataStore, unchanged). iOS: `UserDefaultsConnectStore`
 * (`NSUserDefaults`; a harness's browser session is the one secret and lives in the Keychain).
 */
interface ConnectStore {
    /** Remembered endpoints, most recently connected first. */
    val hosts: Flow<List<HostConfig>>

    suspend fun settings(): ConnectSettings

    /** Read, change and write the settings as one step. */
    suspend fun update(transform: (ConnectSettings) -> ConnectSettings)

    /**
     * Decide the connect mode once, on first run, and never again; returns the mode in effect.
     * [default] is only asked when nothing is stored.
     */
    suspend fun setConnectModeIfUnset(default: suspend () -> String): String

    /**
     * Remember (or refresh) one endpoint. The id is stable across reconnects: `host:port` is the
     * identity. Cached describe fields survive a call that does not supply them.
     */
    suspend fun rememberHost(
        name: String,
        host: String,
        port: Int,
        isLoopback: Boolean,
        useTls: Boolean = false,
        description: HostDescription? = null,
        basePath: String = "",
    ): HostConfig

    /** Fold a fresh host description into the remembered entry without touching its recency. */
    suspend fun cacheDescription(id: String, description: HostDescription)

    /** Forget an endpoint and the credential that went with it. */
    suspend fun removeHost(id: String)

    suspend fun addKnownPort(port: Int)

    /** The record for the harness on this device at [port], created (with no recency) when there is none. */
    suspend fun ensureLoopbackHost(port: Int): HostConfig
}

/** Looking at the network: probing an address, sweeping the subnet, browsing for relays. */
interface ConnectNetwork {
    /** Probe one authority; null when it is not a harness. */
    suspend fun probe(host: String, port: Int, timeouts: ProbeTimeouts, config: HostConfig? = null): HostDescription?

    /** Probe one authority and keep the reason it failed. */
    suspend fun probeOutcome(
        host: String,
        port: Int,
        timeouts: ProbeTimeouts,
        preflight: Boolean = false,
        useTls: Boolean = false,
        config: HostConfig? = null,
        basePath: String = "",
    ): ProbeOutcome

    /** Sweep this device's subnet on [ports], reporting progress and each find as it is confirmed. */
    suspend fun scan(
        ports: List<Int>,
        onProgress: (probed: Int, total: Int) -> Unit = { _, _ -> },
        onFound: (DiscoveredHost) -> Unit = {},
    ): List<DiscoveredHost>

    /** Knock the ports a relay uses; the fallback when [browseRelays] found nothing. */
    suspend fun scanForRelays(onFound: (DiscoveredHost) -> Unit = {}): List<DiscoveredHost>

    /** Relays that advertise themselves (mDNS). */
    suspend fun browseRelays(onFound: (DiscoveredHost) -> Unit = {}): List<DiscoveredHost>

    /** True when [host] is an IPv4 literal in one of this device's own /24s (or is not a literal). */
    suspend fun isOnLocalSubnet(host: String): Boolean

    /** This device's own /24 as a label, e.g. `192.168.1.x`, or null. */
    suspend fun localSubnetLabel(): String?

    /**
     * A readable name for an address: reverse DNS when the network offers one, the address itself
     * otherwise. Storing the IP as the name made a card's two lines say the same thing twice.
     */
    suspend fun hostLabel(address: String): String
}

/**
 * The live connection to one harness: the connection loop and what it reports.
 *
 * Android: `ConnectionManager` (also the foreground service binding). iOS: `IosConnectionLink`.
 */
interface ConnectionLink {
    val state: StateFlow<ConnectionUiState>

    /** Start driving [config]. Progress and failure arrive through [state]. */
    suspend fun connect(config: HostConfig)

    fun disconnect()
}

/** Exchanging a harness's launch token for a browser session. */
interface HarnessSignIn {
    /** [tokenInput] may be the bare token, the `?token=…` URL, or the whole startup line. */
    suspend fun pair(hostId: String, baseUrl: String, tokenInput: String): SessionExchange
}

/**
 * The harness on this very device, started and stopped through Termux on Android.
 *
 * iOS cannot run one (it does not allow the background processes Termux relies on), so it passes
 * [NoLoopbackHarness], and the Connect screen hides "This phone" there.
 */
interface LoopbackHarness {
    /** Whether the app that runs the harness is installed. */
    val installed: Boolean

    fun hasPermission(): Boolean

    val state: Flow<HarnessControl>

    fun start(port: Int)

    fun stop(port: Int)

    /** Clear a finished start or failure; the screen has shown it. */
    fun acknowledge()
}

/** A platform with no harness of its own. */
object NoLoopbackHarness : LoopbackHarness {
    override val installed: Boolean = false

    override fun hasPermission(): Boolean = false

    override val state: Flow<HarnessControl> = emptyFlow()

    override fun start(port: Int) = Unit

    override fun stop(port: Int) = Unit

    override fun acknowledge() = Unit
}

/**
 * What this platform's Connect screen can offer. The shared screen hides a section that could never
 * work rather than showing it broken.
 *
 * @property thisPhone "This phone": a harness on the device itself, through Termux. Android only.
 * @property relay Relay mode and pairing. Needs the pairing screen (QR scan, passcode), which is
 *   not shared yet.
 * @property discovery The subnet sweep (and the auto-connect that depends on it).
 * @property settings The Settings screen, reached from the Connect screen's header.
 */
class ConnectFeatures(
    val thisPhone: Boolean,
    val relay: Boolean,
    val discovery: Boolean,
    val settings: Boolean,
) {
    companion object {
        /** Everything the Android app has always offered. */
        val All = ConnectFeatures(thisPhone = true, relay = true, discovery = true, settings = true)
    }
}
