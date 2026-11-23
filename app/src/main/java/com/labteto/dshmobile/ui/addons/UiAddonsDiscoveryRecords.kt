package com.labteto.dshmobile.ui.addons

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.core.uisync.UiSyncDiscoveryRecord

/**
 * The last discovery load per host, kept on the device so the app looks at a plugin set once, not
 * on every start (see `uiSyncAutoDiscoveryAllowed`). Keyed by [uiAddonsHostKey]. Small: one line per
 * host the phone ever looked at.
 */
internal class UiAddonsDiscoveryRecords(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(hostKey: String): UiSyncDiscoveryRecord? = UiSyncDiscoveryRecord.decode(prefs.getString(hostKey, null))

    fun put(hostKey: String, record: UiSyncDiscoveryRecord) = prefs.edit { putString(hostKey, record.encode()) }

    private companion object {
        const val FILE = "ui-addons-discovery"
    }
}

/** The key the add-on layer and its records use for one connected host: the same host at another address is another page. */
internal fun uiAddonsHostKey(host: HostConfig): String = "${host.id}|${host.baseUrl}"
