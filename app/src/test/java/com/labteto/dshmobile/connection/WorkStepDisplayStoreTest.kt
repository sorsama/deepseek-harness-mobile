package com.labteto.dshmobile.connection

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkStepDisplayStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun open(scope: CoroutineScope) =
        PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("settings.preferences_pb") }

    @Test fun `nothing stored reads as the default level`() {
        assertEquals(DEFAULT_WORK_STEP_DISPLAY.key, emptyPreferences().readWorkStepDisplay())
    }

    @Test fun `a written level reads back`() {
        WorkStepDisplay.entries.forEach { level ->
            val prefs = emptyPreferences().toMutablePreferences().apply { writeWorkStepDisplay(level.key) }
            assertEquals(level.key, prefs.readWorkStepDisplay())
        }
    }

    @Test fun `a value that is not a level is stored and read as the default`() {
        val prefs = emptyPreferences().toMutablePreferences().apply { writeWorkStepDisplay("sideways") }
        assertEquals(DEFAULT_WORK_STEP_DISPLAY.key, prefs.readWorkStepDisplay())
        val hand = emptyPreferences().toMutablePreferences().apply { set(WORK_STEP_DISPLAY_PREF, "expanded") }
        assertEquals(DEFAULT_WORK_STEP_DISPLAY.key, hand.readWorkStepDisplay())
    }

    @Test fun `the level survives closing and reopening the store`() = runBlocking {
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        open(first).edit { it.writeWorkStepDisplay(WorkStepDisplay.Verbose.key) }
        first.coroutineContext.job.cancelAndJoin()

        // A new store on the same file is what an app restart sees.
        val second = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val read = open(second).data.first().readWorkStepDisplay()
        second.coroutineContext.job.cancelAndJoin()
        assertEquals(WorkStepDisplay.Verbose.key, read)
    }

    @Test fun `the level is its own key and touches no other setting`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = open(scope)
        store.edit { it[stringPreferencesKey("default_delivery_mode")] = "steer" }
        store.edit { it.writeWorkStepDisplay(WorkStepDisplay.Detailed.key) }
        val prefs = store.data.first()
        scope.coroutineContext.job.cancelAndJoin()
        assertEquals("steer", prefs[stringPreferencesKey("default_delivery_mode")])
        assertEquals(setOf("default_delivery_mode", "work_step_display"), prefs.asMap().keys.map { it.name }.toSet())
        // Nothing here names the host's own `ui-chat` document: the choice stays on this device.
        assertFalse(prefs.asMap().keys.any { it.name.contains("ui-chat") || it.name.contains("transcriptView") })
    }
}
