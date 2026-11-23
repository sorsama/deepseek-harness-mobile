package com.labteto.dshmobile.connection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Device-local layout choices for the resizable session pane. */
@Singleton
class ChatPanePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val LIST_WIDTH_DP = floatPreferencesKey("session_list_width_dp")
        val LIST_PINNED = booleanPreferencesKey("session_list_pinned")
    }

    val listWidthDp: Flow<Float?> = dataStore.data.map { it[Keys.LIST_WIDTH_DP] }
    val listPinned: Flow<Boolean> = dataStore.data.map { it[Keys.LIST_PINNED] ?: true }

    suspend fun setListWidthDp(value: Float) {
        dataStore.edit { it[Keys.LIST_WIDTH_DP] = value }
    }

    suspend fun setListPinned(value: Boolean) {
        dataStore.edit { it[Keys.LIST_PINNED] = value }
    }
}
