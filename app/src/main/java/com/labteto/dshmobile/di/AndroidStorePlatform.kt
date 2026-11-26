package com.labteto.dshmobile.di

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.R
import com.labteto.dshmobile.data.KeyValueStore
import com.labteto.dshmobile.data.StorePlatform
import java.util.Locale
import java.util.TimeZone

/**
 * Android's side of [StorePlatform]: the same calls the session store made itself before it became
 * platform-neutral, read fresh on every use so a language or time zone change is picked up.
 */
internal class AndroidStorePlatform(private val context: Context) : StorePlatform {
    override val noCurrentGoalMessage: String get() = context.getString(R.string.goal_none)
    override val migrationRequiredMessage: String get() = context.getString(R.string.alpha2_migration)
    override val subagentReadOnlyMessage: String get() = context.getString(R.string.subagents_readonly)

    override val timeZoneId: String get() = TimeZone.getDefault().id
    override val timeZoneOffsetSeconds: Int get() = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
    override val languageTag: String get() = Locale.getDefault().toLanguageTag()
    override val language: String get() = Locale.getDefault().language
    override val appVersion: String get() = BuildConfig.VERSION_NAME

    override val pluginOperationStore: KeyValueStore =
        SharedPreferencesKeyValueStore(context.getSharedPreferences("plugin-operation", Context.MODE_PRIVATE))
    override val composerDraftStore: KeyValueStore =
        SharedPreferencesKeyValueStore(context.getSharedPreferences("composer-drafts", Context.MODE_PRIVATE))

    override fun log(message: String, throwable: Throwable?) {
        if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
    }

    private companion object {
        const val TAG = "SessionStore"
    }
}

/** A [KeyValueStore] over one `SharedPreferences` file; [edit] is one `apply()`. */
internal class SharedPreferencesKeyValueStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun edit(changes: Map<String, String?>) {
        val editor = preferences.edit()
        changes.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
        editor.apply()
    }
}
