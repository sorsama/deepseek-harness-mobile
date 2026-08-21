package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.dto.HostDescription
import kotlinx.serialization.Serializable

/**
 * One remembered harness endpoint, reached directly or through a `dsh-relay`.
 *
 * The `last*` fields cache the newest `host.describe` so a Recent card can say what the harness is
 * before its liveness probe lands — and can still say it about a harness that is now switched off.
 * They all default, because the whole list is persisted as one JSON blob whose decode failure is
 * swallowed: a field without a default would silently wipe every remembered host on upgrade. That
 * applies just as much to the relay fields below, which is why every one of them has a default even
 * though a paired relay always has three of them set.
 *
 * The bearer token is deliberately **not** here. This record is serialized into plain DataStore;
 * the credential lives in [RelayCredentialStore], keyed by [id].
 */
@Serializable
data class HostConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val isLoopback: Boolean = false,
    /**
     * Speak TLS to this endpoint. The harness itself never serves HTTPS — this is for a reverse
     * proxy someone put in front of it (Caddy at `https://agent.home`, say), which is the only
     * way it is ever reached over TLS.
     */
    val useTls: Boolean = false,
    val lastConnectedAt: Long = 0L,
    val lastVersion: String? = null,
    val lastCwd: String? = null,
    val lastSessions: Int? = null,
    /** Base64 SHA-256 of the relay's DER SubjectPublicKeyInfo; null when there is nothing to pin. */
    val relayFingerprint: String? = null,
    /** The relay's own id for this device, shown in its device list. Non-null iff this host is paired. */
    val relayDeviceId: String? = null,
    /** Epoch millis the device token expires, as the relay reported it at pairing. */
    val relayTokenExpiresAt: Long = 0L,
) {
    /** Bare `host:port` — the identity key and display form, deliberately scheme-free. */
    val authority: String get() = "$host:$port"
    val baseUrl: String get() = harnessBaseUrl(host, port, useTls)

    /** What a card prints: the authority, scheme-qualified only when it is not the plain default. */
    val displayAddress: String get() = if (useTls) "https://$authority" else authority

    /**
     * Whether this endpoint is a paired relay.
     *
     * Keyed on the device id rather than on the transport: a relay running `tls: off` is still a
     * relay that needs its bearer token, and an `https` address this device never paired with is
     * not one.
     */
    val isRelay: Boolean get() = relayDeviceId != null

    /** Whether traffic to this endpoint travels in the clear. */
    val isPlaintext: Boolean get() = !useTls
}

/**
 * A harness found by the active LAN scan.
 *
 * Carries the whole probe answer rather than two fields of it: the sweep already paid for the round
 * trip, and the card wants the session count and the default model too.
 *
 * [description] is null when the harness was identified by its static manifest but its trust fence
 * refused `host.describe` from this address. That is a real find, not a miss — it is a harness with
 * a `--trusted-host` still to add — so it is listed and explained rather than dropped.
 */
data class DiscoveredHost(
    val host: String,
    val port: Int,
    val description: HostDescription?,
    /** True when the advertisement said the listener terminates TLS. */
    val useTls: Boolean = false,
    /** SPKI pin from the mDNS `pin` record, when the listener terminates TLS. */
    val fingerprint: String? = null,
    /**
     * Whether this is a `dsh-relay` rather than a bare harness.
     *
     * A relay answers `/relay/health` and refuses `/api` until this device pairs, so it can never be
     * connected to straight from a discovery card the way a harness can — it routes to pairing.
     */
    val isRelay: Boolean = false,
    /**
     * The relay answered and its fence refused the address it was reached by.
     *
     * Still a find. The relay is running, on the right port, and one entry in its
     * `publicHostnames` away — hiding it would send someone looking for a fault that is not there.
     */
    val hostRefused: Boolean = false,
) {
    val authority: String get() = "$host:$port"

    /** Origin to address this endpoint by. */
    val baseUrl: String get() = harnessBaseUrl(host, port, useTls)

    /** Whether the harness accepted an `/api` call from this device. */
    val trusted: Boolean get() = description != null
}

/** App-level persisted settings (DataStore). */
data class AppSettings(
    val autoConnectLast: Boolean = true,
    val autoConnectLan: Boolean = false,
    val autoConnectLoopback: Boolean = true,
    /** Relay mode's counterpart to [autoConnectLan]: connect to a paired relay that mDNS finds. */
    val autoConnectRelay: Boolean = false,
    val keepConnectedInBackground: Boolean = false,
    val notifyTurnComplete: Boolean = true,
    val notifyGoal: Boolean = true,
    val notifyNeedsAction: Boolean = true,
    /**
     * Which way the user chose to reach a harness: `lan` or `relay`.
     *
     * Not a detected value. The two paths have different trust models — an unauthenticated LAN
     * harness against a credentialed relay — and auto-connect never crosses between them, so the
     * app connects only the way that was actually picked. Defaults to `lan` so an install that
     * predates relay support comes back where it was.
     */
    val connectMode: String = ConnectMode.LAN,
    val themePreference: String = "system", // light | dark | system
    val localeOverride: String? = null, // null = system
    val knownPorts: List<Int> = listOf(3080),
    /**
     * Whether to ask GitHub for the latest release on start.
     *
     * The only request this app makes to anything other than the harness the user pointed it at,
     * so it is worth being able to switch off — on a restricted network, or by anyone who would
     * rather it stayed local-only.
     */
    val updateCheckEnabled: Boolean = true,
    /** A release the user has already declined, so it is offered once rather than every launch. */
    val dismissedUpdate: String? = null,
)

/** The two ways the app can reach a harness. Persisted as [AppSettings.connectMode]. */
object ConnectMode {
    /** Straight at a harness on the local network, over plain HTTP, with no credential. */
    const val LAN: String = "lan"

    /** Through a `dsh-relay`, holding a device token and pinning the relay's key. */
    const val RELAY: String = "relay"

    /** Read a stored value back, falling back to [LAN] for anything unrecognised. */
    fun of(value: String?): String = if (value == RELAY) RELAY else LAN
}
