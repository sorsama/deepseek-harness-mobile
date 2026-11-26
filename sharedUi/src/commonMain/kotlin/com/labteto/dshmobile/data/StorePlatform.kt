package com.labteto.dshmobile.data

import com.labteto.dshmobile.connection.HostConfig

/**
 * What [SessionStore] needs from the platform it runs on, and nothing else.
 *
 * The store is platform-neutral Kotlin over `core` and the `HarnessConnection` port, shared by both apps since
 * issue #141's M3. Everything it used to read from Android directly comes through here: three strings in the
 * reader's language, the device's time zone and language, the app's version, two small preference files and a log.
 * Android implements it over its resources, `java.util` and `SharedPreferences` (`AndroidStorePlatform`); iOS over
 * the shared strings, `NSBundle`, `NSTimeZone`, `NSLocale` and `NSUserDefaults` (`IosStorePlatform`).
 *
 * Each value is read when the store needs it, not once: the person can change the language or travel while the
 * app is running.
 */
interface StorePlatform {
    /** The answer to a goal action when the session has no goal ("No goal is set"). */
    val noCurrentGoalMessage: String

    /** The answer when the harness's data needs a migration this app cannot do from the phone. */
    val migrationRequiredMessage: String

    /** The answer to a prompt aimed at a subagent session the harness keeps read-only. */
    val subagentReadOnlyMessage: String

    /** The device's time zone, as an IANA id (`Europe/Paris`); sent with every prompt. */
    val timeZoneId: String

    /** The device time zone's offset from UTC right now, in seconds; sent with account calls. */
    val timeZoneOffsetSeconds: Int

    /** The reader's locale as a BCP 47 tag (`fr-FR`); sent with account calls. */
    val languageTag: String

    /**
     * The reader's language code alone, spelt the way the platform spells it (`fr`), which picks the harness's
     * localized approval reason. Separate from [languageTag] because Android's legacy codes (`iw`, `in`) differ from
     * the tag's.
     */
    val language: String

    /** This app's version name (`0.24.0`); sent with account calls. */
    val appVersion: String

    /** Where [PluginOperations] keeps the installs it has not heard the end of, across a restart. */
    val pluginOperationStore: KeyValueStore

    /** Where the composer keeps each session's unsent draft. */
    val composerDraftStore: KeyValueStore

    /** A diagnostic line for the platform's log. Never shown to the person. */
    fun log(message: String, throwable: Throwable? = null)
}

/**
 * A small persistent map of strings: a `SharedPreferences` file on Android, `NSUserDefaults` on iOS.
 *
 * Reads are immediate. [edit] applies every change at once and may finish writing to disk later, as
 * `SharedPreferences.Editor.apply()` does.
 */
interface KeyValueStore {
    fun getString(key: String): String?

    /** Apply [changes] together: each key is set to its value, or removed when the value is null. */
    fun edit(changes: Map<String, String?>)
}

/**
 * The one session the store reopens when it connects to a harness again: the one the person was last in.
 *
 * Android keeps it in `HostsStore`'s DataStore beside the saved hosts. Two calls, so the store does not depend on
 * the rest of that store.
 */
interface LastSessionStore {
    /** The session last opened on [host], or null when there is none. */
    suspend fun lastSessionId(host: HostConfig): String?

    /** Remember [sessionId] as the session to land in on the host whose key is [hostKey] (its base URL). */
    suspend fun setLastSessionId(hostKey: String, sessionId: String)
}
