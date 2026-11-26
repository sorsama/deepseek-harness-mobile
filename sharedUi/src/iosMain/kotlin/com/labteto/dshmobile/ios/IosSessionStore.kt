package com.labteto.dshmobile.ios

import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.data.KeyValueStore
import com.labteto.dshmobile.data.LastSessionStore
import com.labteto.dshmobile.data.StorePlatform
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.alpha2_migration
import com.labteto.dshmobile.shared.resources.goal_none
import com.labteto.dshmobile.shared.resources.subagents_readonly
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.compose.resources.getString
// A wildcard: `localTimeZone`, `secondsFromGMT` and `preferredLanguages` are declared in Foundation categories, which
// Kotlin/Native turns into extensions that each need their own import.
import platform.Foundation.*

/*
 * The iOS side of the session store's ports (issue #141, M3): what `AndroidStorePlatform` and `HostsStore` are on
 * Android, over `NSUserDefaults`, `NSTimeZone`, `NSLocale` and the bundle's Info.plist.
 */

/**
 * [StorePlatform] on iOS.
 *
 * The three messages come from the shared string resources, in the same 11 languages as Android. Reading one is a
 * suspend call and the port's properties are not, so [loadMessages] reads them once when the app starts; iOS
 * restarts an app whose language the person changes, so once is enough. Until then they are empty, which only a
 * goal, migration or subagent action in the first moments after launch could show.
 *
 * The language is the first of the person's preferred languages, the one Compose Multiplatform's resources pick
 * their strings by, so the harness answers in the language the screen is in.
 */
internal class IosStorePlatform(defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : StorePlatform {
    @Volatile
    private var messages = Messages("", "", "")

    suspend fun loadMessages() {
        messages = Messages(
            noGoal = getString(Res.string.goal_none),
            migration = getString(Res.string.alpha2_migration),
            subagentReadOnly = getString(Res.string.subagents_readonly),
        )
    }

    override val noCurrentGoalMessage: String get() = messages.noGoal
    override val migrationRequiredMessage: String get() = messages.migration
    override val subagentReadOnlyMessage: String get() = messages.subagentReadOnly

    override val timeZoneId: String get() = NSTimeZone.localTimeZone.name
    override val timeZoneOffsetSeconds: Int get() = NSTimeZone.localTimeZone.secondsFromGMT.toInt()

    /** A BCP 47 tag (`fr-FR`, `zh-Hans-CN`), which is how iOS spells its preferred languages already. */
    override val languageTag: String
        get() = (NSLocale.preferredLanguages.firstOrNull() as? String)?.takeIf { it.isNotBlank() } ?: "en"

    override val language: String get() = languageTag.substringBefore('-')

    override val appVersion: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0"

    override val pluginOperationStore: KeyValueStore = UserDefaultsKeyValueStore("dsh.store.pluginOperation.", defaults)
    override val composerDraftStore: KeyValueStore = UserDefaultsKeyValueStore("dsh.store.composerDraft.", defaults)

    override fun log(message: String, throwable: Throwable?) {
        println(if (throwable == null) "SessionStore: $message" else "SessionStore: $message ($throwable)")
    }

    private class Messages(val noGoal: String, val migration: String, val subagentReadOnly: String)
}

/**
 * A [KeyValueStore] in `NSUserDefaults`, its keys under [prefix] so two stores (and the Connect screen's own keys)
 * never meet. `NSUserDefaults` may be used from any thread and writes to disk by itself, like `apply()`.
 */
internal class UserDefaultsKeyValueStore(
    private val prefix: String,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueStore {
    override fun getString(key: String): String? = defaults.stringForKey(prefix + key)

    override fun edit(changes: Map<String, String?>) {
        changes.forEach { (key, value) ->
            if (value == null) defaults.removeObjectForKey(prefix + key) else defaults.setObject(value, forKey = prefix + key)
        }
    }
}

/**
 * The session to land in on each harness, keyed by its base URL as the store asks, newest first and at most
 * [MAX_HOSTS], as Android's `HostsStore` keeps them. iOS never had the older `host:port` keys Android still reads.
 */
internal class UserDefaultsLastSessions(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : LastSessionStore {
    private val mutex = Mutex()
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    override suspend fun lastSessionId(host: HostConfig): String? = read()[host.baseUrl]

    override suspend fun setLastSessionId(hostKey: String, sessionId: String) = mutex.withLock {
        val next = LinkedHashMap<String, String>()
        next[hostKey] = sessionId
        read().forEach { (key, value) -> if (key != hostKey) next[key] = value }
        val kept = next.entries.take(MAX_HOSTS).associate { it.key to it.value }
        defaults.setObject(WireJson.encodeToString(serializer, kept), forKey = KEY)
    }

    private fun read(): Map<String, String> {
        val raw = defaults.stringForKey(KEY) ?: return emptyMap()
        return runCatching { WireJson.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    }

    private companion object {
        const val KEY = "dsh.lastSessions"
        const val MAX_HOSTS = 8
    }
}
