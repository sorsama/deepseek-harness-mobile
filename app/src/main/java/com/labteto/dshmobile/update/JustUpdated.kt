package com.labteto.dshmobile.update

import android.content.Context
import com.labteto.dshmobile.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** The update that was handed to the system, written just before the install session is committed. */
@Serializable
data class UpdateMarker(
    val version: String,
    val versionCode: Long,
    val notes: String? = null,
    val releaseUrl: String,
)

/** "You are now on [version]", shown once on the first launch after an in-app update. */
data class JustUpdated(val version: String, val notes: String?, val releaseUrl: String)

/** Somewhere small and synchronous to keep the updater's own flags. A seam for tests. */
interface UpdaterPrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String): Long
    fun putLong(key: String, value: Long)
}

/** [UpdaterPrefs] in a private SharedPreferences file. */
@Singleton
class SharedUpdaterPrefs @Inject constructor(@ApplicationContext context: Context) : UpdaterPrefs {
    private val prefs = context.getSharedPreferences("dsh_updater", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    // commit(), not apply(): the marker is written just before an install that ends this process,
    // and a write still queued in the background would be lost with it.
    @android.annotation.SuppressLint("ApplySharedPref")
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }
    override fun getLong(key: String): Long = prefs.getLong(key, 0L)
    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }
}

/**
 * Remembers an install in flight across the process being replaced, and turns it into a single
 * [JustUpdated] on the next launch.
 *
 * Installing an update of this very app kills the process that asked for it, so the success
 * broadcast is usually never seen. Instead [markPending] is written before the session is
 * committed, and [consume] on the next launch compares it with the version now running: at or past
 * the marker means the update landed, and the marker is cleared so the confirmation shows once.
 * A marker for a version still ahead of the running one is kept (the install has not happened, or
 * is still waiting for the person); a failure reported by the system clears it ([clear]).
 */
class JustUpdatedTracker(
    private val prefs: UpdaterPrefs,
    private val currentVersionCode: Long,
    private val currentVersionName: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun markPending(marker: UpdateMarker) {
        prefs.putString(KEY, json.encodeToString(UpdateMarker.serializer(), marker))
    }

    fun clear() = prefs.putString(KEY, null)

    fun pending(): UpdateMarker? = prefs.getString(KEY)?.let { raw ->
        runCatching { json.decodeFromString(UpdateMarker.serializer(), raw) }.getOrNull()
    }

    /** The confirmation to show now, at most once per update. */
    fun consume(): JustUpdated? {
        val raw = prefs.getString(KEY) ?: return null
        val marker = runCatching { json.decodeFromString(UpdateMarker.serializer(), raw) }.getOrNull()
        if (marker == null) {
            clear()
            return null
        }
        val landed = if (marker.versionCode > 0) {
            currentVersionCode >= marker.versionCode
        } else {
            !isNewerVersion(marker.version, currentVersionName)
        }
        if (!landed) return null
        clear()
        return JustUpdated(currentVersionName, marker.notes, marker.releaseUrl)
    }

    private companion object {
        const val KEY = "pending_update"
    }
}

/** The app's own [JustUpdatedTracker], for the running build. */
@Singleton
class AppJustUpdatedTracker @Inject constructor(prefs: SharedUpdaterPrefs) {
    val tracker = JustUpdatedTracker(prefs, BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME)
}
