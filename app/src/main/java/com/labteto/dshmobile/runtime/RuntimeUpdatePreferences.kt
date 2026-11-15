package com.labteto.dshmobile.runtime

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the harness updates remember across app restarts.
 *
 * - [notify]: a notification when a new release is out. On by default.
 * - [autoInstall]: install an update by itself when nothing would be interrupted. Off by default.
 * - [lastCheckAt]: when the release manifest was last read, so opening the app often does not
 *   mean asking GitHub often.
 * - [notifiedSequence]: the newest release a notification (or the person's own check) has
 *   already told about. One notification per release, never two.
 * - [skipped]: the release the person went back from. Nothing offers it again by itself, and
 *   automatic updates leave it alone, until a newer release appears.
 * - [failed]: a release whose start failed after an update and was rolled back. Automatic updates
 *   do not try it again; the person still can.
 */
class RuntimeUpdatePreferences(private val store: KeyValue) {

    /** The calls this class needs from a key-value store, so a test can hold it in a map. */
    interface KeyValue {
        fun getBoolean(key: String, default: Boolean): Boolean
        fun putBoolean(key: String, value: Boolean)
        fun getLong(key: String, default: Long): Long
        fun putLong(key: String, value: Long)
        fun getString(key: String): String?
        fun putString(key: String, value: String?)
    }

    private val _notify = MutableStateFlow(store.getBoolean(KEY_NOTIFY, true))
    val notify: StateFlow<Boolean> = _notify.asStateFlow()

    private val _autoInstall = MutableStateFlow(store.getBoolean(KEY_AUTO, false))
    val autoInstall: StateFlow<Boolean> = _autoInstall.asStateFlow()

    private val _skipped = MutableStateFlow(readRef(KEY_SKIPPED))
    val skipped: StateFlow<ReleaseRef?> = _skipped.asStateFlow()

    private val _failed = MutableStateFlow(readRef(KEY_FAILED))
    val failed: StateFlow<ReleaseRef?> = _failed.asStateFlow()

    var lastCheckAt: Long
        get() = store.getLong(KEY_LAST_CHECK, 0)
        set(value) = store.putLong(KEY_LAST_CHECK, value)

    var notifiedSequence: Long
        get() = store.getLong(KEY_NOTIFIED, 0)
        set(value) = store.putLong(KEY_NOTIFIED, value)

    fun setNotify(enabled: Boolean) {
        store.putBoolean(KEY_NOTIFY, enabled)
        _notify.value = enabled
    }

    fun setAutoInstall(enabled: Boolean) {
        store.putBoolean(KEY_AUTO, enabled)
        _autoInstall.value = enabled
    }

    fun setSkipped(release: ReleaseRef?) {
        writeRef(KEY_SKIPPED, release)
        _skipped.value = release
    }

    fun setFailed(release: ReleaseRef?) {
        writeRef(KEY_FAILED, release)
        _failed.value = release
    }

    // A release is kept as "sequence|releaseId|harnessVersion": the ids never hold a '|'.
    private fun readRef(key: String): ReleaseRef? {
        val parts = store.getString(key)?.split('|') ?: return null
        if (parts.size != 3) return null
        val sequence = parts[0].toLongOrNull() ?: return null
        return ReleaseRef(parts[1], parts[2], sequence)
    }

    private fun writeRef(key: String, release: ReleaseRef?) {
        store.putString(key, release?.let { "${it.sequence}|${it.releaseId}|${it.harnessVersion}" })
    }

    companion object {
        const val FILE = "runtime_update_prefs"
        private const val KEY_NOTIFY = "notify"
        private const val KEY_AUTO = "auto_install"
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val KEY_NOTIFIED = "notified_sequence"
        private const val KEY_SKIPPED = "skipped_release"
        private const val KEY_FAILED = "failed_release"

        fun over(prefs: SharedPreferences): RuntimeUpdatePreferences = RuntimeUpdatePreferences(
            object : KeyValue {
                override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
                override fun putBoolean(key: String, value: Boolean) {
                    prefs.edit().putBoolean(key, value).apply()
                }
                override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
                override fun putLong(key: String, value: Long) {
                    prefs.edit().putLong(key, value).apply()
                }
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun putString(key: String, value: String?) {
                    prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
                }
            },
        )
    }
}
