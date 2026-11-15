package com.labteto.dshmobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePreferencesTest {
    private class MapStore : RuntimePreferences.KeyValue {
        val values = mutableMapOf<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = values[key] ?: default
        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }
        override fun getString(key: String): String? = null
        override fun putString(key: String, value: String) = Unit
    }

    @Test
    fun `both switches start on`() {
        val prefs = RuntimePreferences(MapStore())
        assertTrue(prefs.holdWakeLock.value)
        assertTrue(prefs.modulePack.value)
    }

    @Test
    fun `a change is kept for the next app process`() {
        val store = MapStore()
        RuntimePreferences(store).apply {
            setModulePack(false)
            setHoldWakeLock(false)
        }
        val next = RuntimePreferences(store)
        assertFalse(next.modulePack.value)
        assertFalse(next.holdWakeLock.value)
        assertEquals(2, store.values.size)
    }
}
