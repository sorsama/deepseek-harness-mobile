package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.dto.HostDescription
import kotlinx.serialization.Serializable

/**
 * One remembered harness endpoint.
 *
 * The `last*` fields cache the newest `host.describe` so a Recent card can say what the harness is
 * before its liveness probe lands — and can still say it about a harness that is now switched off.
 * They all default, because the whole list is persisted as one JSON blob whose decode failure is
 * swallowed: a field without a default would silently wipe every remembered host on upgrade.
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
) {
    /** Bare `host:port` — the identity key and display form, deliberately scheme-free. */
    val authority: String get() = "$host:$port"
    val baseUrl: String get() = harnessBaseUrl(host, port, useTls)

    /** What a card prints: the authority, scheme-qualified only when it is not the plain default. */
    val displayAddress: String get() = if (useTls) "https://$authority" else authority
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
) {
    val authority: String get() = "$host:$port"

    /** Whether the harness accepted an `/api` call from this device. */
    val trusted: Boolean get() = description != null
}

/** App-level persisted settings (DataStore). */
data class AppSettings(
    val autoConnectLast: Boolean = true,
    val autoConnectLan: Boolean = false,
    val autoConnectLoopback: Boolean = true,
    val keepConnectedInBackground: Boolean = false,
    val notifyTurnComplete: Boolean = true,
    val notifyGoal: Boolean = true,
    val notifyNeedsAction: Boolean = true,
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
