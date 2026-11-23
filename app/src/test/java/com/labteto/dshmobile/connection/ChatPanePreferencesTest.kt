package com.labteto.dshmobile.connection

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatPanePreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun open(scope: CoroutineScope) = ChatPanePreferences(
        PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("settings.preferences_pb") },
    )

    @Test fun defaultsKeepTheListPinnedWithoutAWidthOverride() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = open(scope)
        assertEquals(null, store.listWidthDp.first())
        assertEquals(true, store.listPinned.first())
        scope.coroutineContext.job.cancelAndJoin()
    }

    @Test fun widthAndPinStateSurviveReopening() = runBlocking {
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        open(first).apply {
            setListWidthDp(352.5f)
            setListPinned(false)
        }
        first.coroutineContext.job.cancelAndJoin()

        val second = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reopened = open(second)
        assertEquals(352.5f, reopened.listWidthDp.first())
        assertEquals(false, reopened.listPinned.first())
        second.coroutineContext.job.cancelAndJoin()
    }
}
