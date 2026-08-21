package com.labteto.dshmobile.connection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.labteto.dshmobile.DshApplication
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.HostDescription
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Persists remembered hosts and app settings. */
@Singleton
class HostsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val credentials: RelayCredentialStore,
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val HOSTS = stringPreferencesKey("hosts_json")
        val AUTO_LAST = booleanPreferencesKey("auto_last")
        val AUTO_LAN = booleanPreferencesKey("auto_lan")
        val AUTO_LOOPBACK = booleanPreferencesKey("auto_loopback")
        val AUTO_RELAY = booleanPreferencesKey("auto_relay")
        val CONNECT_MODE = stringPreferencesKey("connect_mode")
        val BACKGROUND = booleanPreferencesKey("background")
        val NOTIFY_TURN = booleanPreferencesKey("notify_turn")
        val NOTIFY_GOAL = booleanPreferencesKey("notify_goal")
        val NOTIFY_ACTION = booleanPreferencesKey("notify_action")
        val THEME = stringPreferencesKey("theme")
        val LOCALE = stringPreferencesKey("locale")
        val PORTS = stringPreferencesKey("ports_json")
        val LAST_SESSIONS = stringPreferencesKey("last_sessions_json")
        val SESSION_SORT = stringPreferencesKey("session_sort")
        val UPDATE_CHECK = booleanPreferencesKey("update_check")
        val DISMISSED_UPDATE = stringPreferencesKey("dismissed_update")
    }

    private val hostsSerializer = ListSerializer(HostConfig.serializer())
    private val lastSessionsSerializer = MapSerializer(String.serializer(), String.serializer())

    val hosts: Flow<List<HostConfig>> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.HOSTS] ?: return@map emptyList()
        runCatching {
            WireJson.decodeFromString(hostsSerializer, raw).sortedByDescending { it.lastConnectedAt }
        }.getOrDefault(emptyList())
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val ports = prefs[Keys.PORTS]
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(3080)
        AppSettings(
            autoConnectLast = prefs[Keys.AUTO_LAST] ?: true,
            autoConnectLan = prefs[Keys.AUTO_LAN] ?: false,
            autoConnectLoopback = prefs[Keys.AUTO_LOOPBACK] ?: true,
            autoConnectRelay = prefs[Keys.AUTO_RELAY] ?: false,
            connectMode = ConnectMode.of(prefs[Keys.CONNECT_MODE]),
            keepConnectedInBackground = prefs[Keys.BACKGROUND] ?: false,
            notifyTurnComplete = prefs[Keys.NOTIFY_TURN] ?: true,
            notifyGoal = prefs[Keys.NOTIFY_GOAL] ?: true,
            notifyNeedsAction = prefs[Keys.NOTIFY_ACTION] ?: true,
            themePreference = prefs[Keys.THEME] ?: "system",
            localeOverride = prefs[Keys.LOCALE],
            knownPorts = ports,
            updateCheckEnabled = prefs[Keys.UPDATE_CHECK] ?: true,
            dismissedUpdate = prefs[Keys.DISMISSED_UPDATE],
        )
    }

    suspend fun settingsOnce(): AppSettings = settings.first()

    suspend fun upsertHost(config: HostConfig) {
        val current = hosts.first().toMutableList()
        current.removeAll { it.host == config.host && it.port == config.port }
        current.add(0, config)
        persist(current)
    }

    suspend fun touchHost(host: String, port: Int) {
        val current = hosts.first().map {
            if (it.host == host && it.port == port) it.copy(lastConnectedAt = System.currentTimeMillis()) else it
        }
        persist(current)
    }

    /**
     * Remember (or refresh) one endpoint.
     *
     * The id is stable across reconnects: `host:port` is the identity, so a returning harness keeps
     * the id it already had. Minting a fresh UUID every time made the id useless as a list key and
     * as a handle for anything stored per host. Cached describe fields survive a call that does not
     * supply them.
     */
    suspend fun rememberHost(
        name: String,
        host: String,
        port: Int,
        isLoopback: Boolean,
        useTls: Boolean = false,
        description: HostDescription? = null,
        relay: RelayIdentity? = null,
    ): HostConfig {
        val existing = hosts.first().firstOrNull { it.host == host && it.port == port }
        val config = HostConfig(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            host = host,
            port = port,
            isLoopback = isLoopback,
            lastConnectedAt = System.currentTimeMillis(),
            lastVersion = description?.version ?: existing?.lastVersion,
            lastCwd = description?.cwd ?: existing?.lastCwd,
            lastSessions = description?.attachedSessions ?: existing?.lastSessions,
            // A fresh pairing replaces the whole relay identity rather than merging into it: a
            // re-pair mints a new device id, may move between TLS postures, and can land on a
            // regenerated key. Keeping any of the previous three would leave the record describing
            // two different enrolments at once. A pairing also decides the transport, which is why
            // it outranks the caller's `useTls` here rather than sitting beside it.
            useTls = relay?.useTls ?: useTls,
            relayFingerprint = relay?.fingerprint ?: existing?.relayFingerprint,
            relayDeviceId = relay?.deviceId ?: existing?.relayDeviceId,
            relayTokenExpiresAt = relay?.tokenExpiresAt ?: existing?.relayTokenExpiresAt ?: 0L,
        )
        upsertHost(config)
        return config
    }

    /** Fold a fresh `host.describe` into the remembered entry without touching its recency. */
    suspend fun cacheDescription(host: String, port: Int, description: HostDescription) {
        val current = hosts.first()
        if (current.none { it.host == host && it.port == port }) return
        persist(
            current.map {
                if (it.host == host && it.port == port) {
                    it.copy(
                        lastVersion = description.version,
                        lastCwd = description.cwd,
                        lastSessions = description.attachedSessions,
                    )
                } else {
                    it
                }
            },
        )
    }

    /**
     * Forget an endpoint, and the credential that went with it.
     *
     * The token is dropped in the same act rather than left to expire. It would otherwise outlive
     * everything that could ever present it, and a stored secret nothing can use is only a liability
     * — the relay's own device entry is revoked from the relay, not from here.
     */
    suspend fun removeHost(id: String) {
        persist(hosts.first().filterNot { it.id == id })
        credentials.remove(id)
    }

    /**
     * The session last opened on [hostKey] (`"host:port"`), or null when this harness has not been
     * used before. Keyed per host because session ids are host-scoped — one global key would try to
     * reopen a stale id from a different harness after every host switch.
     */
    suspend fun lastSessionId(hostKey: String): String? = lastSessions()[hostKey]

    /** Remember [sessionId] as the landing session for [hostKey], keeping the newest 8 hosts. */
    suspend fun setLastSessionId(hostKey: String, sessionId: String) {
        val next = LinkedHashMap<String, String>()
        next[hostKey] = sessionId
        lastSessions().forEach { (key, value) -> if (key != hostKey) next[key] = value }
        val trimmed = next.entries.take(MAX_REMEMBERED_HOSTS).associate { it.key to it.value }
        dataStore.edit { it[Keys.LAST_SESSIONS] = WireJson.encodeToString(lastSessionsSerializer, trimmed) }
    }

    /** Forget every remembered landing session (the Settings "clear data" action). */
    suspend fun clearLastSessions() {
        dataStore.edit { it.remove(Keys.LAST_SESSIONS) }
    }

    private suspend fun lastSessions(): Map<String, String> {
        val raw = dataStore.data.first()[Keys.LAST_SESSIONS] ?: return emptyMap()
        return runCatching { WireJson.decodeFromString(lastSessionsSerializer, raw) }.getOrDefault(emptyMap())
    }

    /** Drawer session ordering: `"manual"` follows the workspace order, `"updated"` sorts by recency. */
    val sessionSort: Flow<String> = dataStore.data.map { it[Keys.SESSION_SORT] ?: "manual" }

    suspend fun setSessionSort(value: String) {
        dataStore.edit { it[Keys.SESSION_SORT] = value }
    }

    /** Remember that this release was declined, so it is not offered again. */
    suspend fun setDismissedUpdate(version: String) {
        dataStore.edit { it[Keys.DISMISSED_UPDATE] = version }
    }

    suspend fun addKnownPort(port: Int) {
        val s = settingsOnce()
        val ports = (s.knownPorts + port).distinct().take(8)
        dataStore.edit { it[Keys.PORTS] = ports.joinToString(",") }
    }

    suspend fun setSetting(transform: (AppSettings) -> AppSettings) {
        val next = transform(settingsOnce())
        // Mirrored out to SharedPreferences as well: the scheme has to be readable before any
        // activity exists, and DataStore cannot be read from there. See DshApplication.
        DshApplication.storeThemePreference(context, next.themePreference)
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_LAST] = next.autoConnectLast
            prefs[Keys.AUTO_LAN] = next.autoConnectLan
            prefs[Keys.AUTO_LOOPBACK] = next.autoConnectLoopback
            prefs[Keys.AUTO_RELAY] = next.autoConnectRelay
            prefs[Keys.CONNECT_MODE] = next.connectMode
            prefs[Keys.BACKGROUND] = next.keepConnectedInBackground
            prefs[Keys.NOTIFY_TURN] = next.notifyTurnComplete
            prefs[Keys.NOTIFY_GOAL] = next.notifyGoal
            prefs[Keys.NOTIFY_ACTION] = next.notifyNeedsAction
            prefs[Keys.THEME] = next.themePreference
            prefs[Keys.UPDATE_CHECK] = next.updateCheckEnabled
            next.localeOverride?.let { prefs[Keys.LOCALE] = it } ?: prefs.remove(Keys.LOCALE)
        }
    }

    private suspend fun persist(list: List<HostConfig>) {
        dataStore.edit { it[Keys.HOSTS] = WireJson.encodeToString(hostsSerializer, list) }
    }

    private companion object {
        /** Bound on the remembered-session map, matching the known-port cap. */
        const val MAX_REMEMBERED_HOSTS = 8
    }
}

/**
 * What a successful relay pairing tells this app about an endpoint.
 *
 * Grouped rather than passed as four loose parameters because they are only ever meaningful
 * together: a fingerprint without a device id describes a relay this device cannot talk to, and a
 * device id without a scheme describes one it cannot address.
 */
data class RelayIdentity(
    val deviceId: String,
    val useTls: Boolean,
    val fingerprint: String?,
    val tokenExpiresAt: Long,
)
