package com.labteto.dshmobile.ios

import com.labteto.dshmobile.connection.ConnectMode
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.RelayIdentity
import com.labteto.dshmobile.connection.harnessBaseUrl
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.ui.screens.connect.ConnectSettings
import com.labteto.dshmobile.ui.screens.connect.ConnectStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

/**
 * Remembered harnesses and the Connect screen's settings, in `NSUserDefaults`.
 *
 * This is the iOS counterpart of Android's `HostsStore` (Preferences DataStore): the same records, the
 * same rules, a different place. The harness list is one JSON value (the very `HostConfig` JSON the
 * Android store writes, with the same defaults for everything that can be missing), the settings are
 * individual keys. What is secret is not here: the browser session a harness issues is in the
 * Keychain ([KeychainSessionStore]), and forgetting a host forgets it there too.
 *
 * `NSUserDefaults` is the right size for this (a handful of small values, read at launch) and is what
 * iOS backs up and restores with the app. It is not encrypted, which is why nothing in it is a secret.
 */
internal class UserDefaultsConnectStore(
    private val sessions: KeychainSessionStore,
    /** The bearer tokens of paired relays; forgetting a host forgets its token too. */
    private val relayTokens: KeychainRelayTokens = KeychainRelayTokens(),
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : ConnectStore {
    private val mutex = Mutex()
    private val hostsSerializer = ListSerializer(HostConfig.serializer())
    private val hostsState = MutableStateFlow(readHosts())

    override val hosts: Flow<List<HostConfig>> = hostsState.asStateFlow()

    override suspend fun settings(): ConnectSettings = readSettings()

    override suspend fun update(transform: (ConnectSettings) -> ConnectSettings) = mutex.withLock {
        val next = transform(readSettings())
        defaults.setObject(next.connectMode, forKey = KEY_MODE)
        defaults.setBool(next.autoConnectLast, forKey = KEY_AUTO_LAST)
        defaults.setBool(next.autoConnectLan, forKey = KEY_AUTO_LAN)
        defaults.setBool(next.autoConnectLoopback, forKey = KEY_AUTO_LOOPBACK)
        defaults.setBool(next.autoConnectRelay, forKey = KEY_AUTO_RELAY)
        defaults.setBool(next.autoStartLoopbackHarness, forKey = KEY_AUTO_START)
    }

    override suspend fun setConnectModeIfUnset(default: suspend () -> String): String {
        defaults.stringForKey(KEY_MODE)?.let { return ConnectMode.of(it) }
        val chosen = ConnectMode.of(default())
        return mutex.withLock {
            // Conditional inside the lock, so two callers racing on a fresh install cannot pick differently.
            defaults.stringForKey(KEY_MODE)?.let { return@withLock ConnectMode.of(it) }
            defaults.setObject(chosen, forKey = KEY_MODE)
            chosen
        }
    }

    override suspend fun rememberHost(
        name: String,
        host: String,
        port: Int,
        isLoopback: Boolean,
        useTls: Boolean,
        description: HostDescription?,
        basePath: String,
    ): HostConfig = mutex.withLock {
        val existing = hostsState.value.firstOrNull { it.baseUrl == harnessBaseUrl(host, port, useTls, basePath) }
        val config = HostConfig(
            id = existing?.id ?: NSUUID().UUIDString,
            name = name,
            host = host,
            port = port,
            basePath = basePath,
            isLoopback = isLoopback,
            lastConnectedAt = nowMillis(),
            lastHome = description?.home ?: existing?.lastHome,
            useTls = useTls,
            relayFingerprint = existing?.relayFingerprint,
            relayDeviceId = existing?.relayDeviceId,
            relayTokenExpiresAt = existing?.relayTokenExpiresAt ?: 0L,
        )
        persist(listOf(config) + hostsState.value.filterNot { it.id == config.id || it.baseUrl == config.baseUrl })
        config
    }

    /**
     * Remember a relay that has just been paired (the iOS counterpart of `HostsStore.rememberHost(relay = …)`).
     *
     * A fresh pairing decides the transport, so [relay]'s scheme outranks anything the caller assumed, and the
     * record is found by that scheme's base URL: pairing again with a relay this device already knows reuses its
     * id (and so its place in Recent) and replaces the device id and expiry, which belong to the new enrolment.
     * The token is not here; it is in the Keychain, against the id this returns.
     */
    suspend fun rememberRelay(host: String, port: Int, basePath: String, relay: RelayIdentity): HostConfig = mutex.withLock {
        val existing = hostsState.value.firstOrNull { it.baseUrl == harnessBaseUrl(host, port, relay.useTls, basePath) }
        val config = HostConfig(
            id = existing?.id ?: NSUUID().UUIDString,
            name = host,
            host = host,
            port = port,
            basePath = basePath,
            isLoopback = false,
            lastConnectedAt = nowMillis(),
            lastHome = existing?.lastHome,
            useTls = relay.useTls,
            relayFingerprint = relay.fingerprint ?: existing?.relayFingerprint,
            relayDeviceId = relay.deviceId,
            relayTokenExpiresAt = relay.tokenExpiresAt,
        )
        persist(listOf(config) + hostsState.value.filterNot { it.id == config.id || it.baseUrl == config.baseUrl })
        config
    }

    /** Remember [config] as the most recent, replacing its earlier record. */
    suspend fun upsertHost(config: HostConfig) = mutex.withLock {
        persist(listOf(config) + hostsState.value.filterNot { it.id == config.id || it.baseUrl == config.baseUrl })
    }

    /** A connection to [id] has just succeeded. */
    suspend fun touchHost(id: String) = mutex.withLock {
        persist(hostsState.value.map { if (it.id == id) it.copy(lastConnectedAt = nowMillis()) else it })
    }

    override suspend fun cacheDescription(id: String, description: HostDescription) = mutex.withLock {
        if (hostsState.value.none { it.id == id }) return@withLock
        persist(hostsState.value.map { if (it.id == id) it.copy(lastHome = description.home) else it })
    }

    override suspend fun removeHost(id: String) {
        mutex.withLock { persist(hostsState.value.filterNot { it.id == id }) }
        sessions.remove(id)
        relayTokens.remove(id)
    }

    override suspend fun addKnownPort(port: Int) = mutex.withLock {
        val ports = (readSettings().knownPorts + port).distinct().take(8)
        defaults.setObject(ports.joinToString(","), forKey = KEY_PORTS)
    }

    override suspend fun ensureLoopbackHost(port: Int): HostConfig = mutex.withLock {
        hostsState.value.firstOrNull { it.host == LOOPBACK_HOST && it.port == port }?.let { return@withLock it }
        val config = HostConfig(
            id = NSUUID().UUIDString,
            name = LOOPBACK_HOST,
            host = LOOPBACK_HOST,
            port = port,
            isLoopback = true,
            lastConnectedAt = 0L,
        )
        persist(listOf(config) + hostsState.value)
        config
    }

    private fun persist(list: List<HostConfig>) {
        defaults.setObject(WireJson.encodeToString(hostsSerializer, list), forKey = KEY_HOSTS)
        hostsState.value = list.sortedByDescending { it.lastConnectedAt }
    }

    private fun readHosts(): List<HostConfig> {
        val raw = defaults.stringForKey(KEY_HOSTS) ?: return emptyList()
        return runCatching { WireJson.decodeFromString(hostsSerializer, raw).sortedByDescending { it.lastConnectedAt } }
            .getOrDefault(emptyList())
    }

    private fun readSettings(): ConnectSettings = ConnectSettings(
        connectMode = ConnectMode.of(defaults.stringForKey(KEY_MODE)),
        autoConnectLast = bool(KEY_AUTO_LAST, true),
        autoConnectLan = bool(KEY_AUTO_LAN, false),
        autoConnectLoopback = bool(KEY_AUTO_LOOPBACK, true),
        autoConnectRelay = bool(KEY_AUTO_RELAY, false),
        autoStartLoopbackHarness = bool(KEY_AUTO_START, false),
        knownPorts = defaults.stringForKey(KEY_PORTS)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(3080),
    )

    /** A stored boolean, or [fallback] when none was ever stored (`boolForKey` alone cannot tell). */
    private fun bool(key: String, fallback: Boolean): Boolean =
        if (defaults.objectForKey(key) == null) fallback else defaults.boolForKey(key)

    private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    internal companion object {
        /** Debug launches only: turn "reconnect to the last harness" off (the Recent list shows) or back on. */
        fun setAutoConnectLast(on: Boolean, defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) {
            defaults.setBool(on, forKey = KEY_AUTO_LAST)
        }

        const val KEY_HOSTS = "dsh.hosts"
        const val KEY_MODE = "dsh.connect.mode"
        const val KEY_AUTO_LAST = "dsh.auto.last"
        const val KEY_AUTO_LAN = "dsh.auto.lan"
        const val KEY_AUTO_LOOPBACK = "dsh.auto.loopback"
        const val KEY_AUTO_RELAY = "dsh.auto.relay"
        const val KEY_AUTO_START = "dsh.auto.startLoopback"
        const val KEY_PORTS = "dsh.ports"

        /** The address "this phone" mode reaches. Not offered on iOS; kept so the shared controller's call is answered. */
        const val LOOPBACK_HOST = "127.0.0.1"
    }
}
