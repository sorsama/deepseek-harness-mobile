package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertFalse
import com.labteto.dshmobile.testing.assertNull
import com.labteto.dshmobile.testing.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The Settings page's state: what [SettingsController] reads from its store, what a change writes
 * back, and that a fresh install starts on the defaults the Android app has always had.
 *
 * The Android app keeps its own view model, so these are the decisions both platforms share; the
 * controller is what the iOS entry point drives. Runs on the JVM and on an iOS simulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsControllerTest {

    /** An in-memory [SettingsStore], with a record of every transform it was asked to apply. */
    private class FakeStore(var stored: AppearanceSettings = AppearanceSettings()) : SettingsStore {
        val updates = mutableListOf<AppearanceSettings>()

        override suspend fun appearance(): AppearanceSettings = stored

        override suspend fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings) {
            stored = transform(stored)
            updates += stored
        }
    }

    @Test
    fun a_fresh_store_starts_on_the_app_defaults() = runTest {
        val store = FakeStore()
        val controller = SettingsController(store, TestScope(testScheduler))
        runCurrent()

        val state = controller.state.value
        assertEquals("system", state.themePreference)
        assertEquals("ink", state.themeAccent)
        assertNull(state.themeCustomAccent)
        assertFalse(state.amoledBlack)
        assertEquals("default", state.fontSize)
        assertTrue(state.hapticsEnabled)
        assertEquals("normal", state.hapticStrength)
        assertNull(state.localeOverride)
    }

    @Test
    fun the_controller_loads_what_the_store_holds() = runTest {
        val store = FakeStore(AppearanceSettings(themePreference = "dark", themeAccent = "violet"))
        val controller = SettingsController(store, TestScope(testScheduler))
        runCurrent()

        assertEquals("dark", controller.state.value.themePreference)
        assertEquals("violet", controller.state.value.themeAccent)
    }

    @Test
    fun a_change_is_written_and_visible_at_once() = runTest {
        val store = FakeStore()
        val controller = SettingsController(store, TestScope(testScheduler))
        runCurrent()

        controller.set { it.copy(themePreference = "light", amoledBlack = true) }
        runCurrent()

        assertEquals("light", store.stored.themePreference)
        assertTrue(store.stored.amoledBlack)
        assertEquals("light", controller.state.value.themePreference)
        assertTrue(controller.state.value.amoledBlack)
        assertEquals(1, store.updates.size)
    }

    @Test
    fun a_custom_accent_is_kept_behind_a_preset_and_cleared_by_reset() = runTest {
        val store = FakeStore()
        val controller = SettingsController(store, TestScope(testScheduler))
        runCurrent()

        controller.set { it.copy(themeAccent = "custom", themeCustomAccent = "#6E9BFF") }
        runCurrent()
        assertEquals("#6E9BFF", store.stored.themeCustomAccent)

        // Picking a preset keeps the stored colour: the Custom swatch still shows it.
        controller.set { it.copy(themeAccent = "teal") }
        runCurrent()
        assertEquals("teal", store.stored.themeAccent)
        assertEquals("#6E9BFF", store.stored.themeCustomAccent)

        // The reset clears both the key and the colour.
        controller.set { it.copy(themeAccent = "ink", themeCustomAccent = null) }
        runCurrent()
        assertNull(store.stored.themeCustomAccent)
    }

    @Test
    fun the_locale_override_can_be_set_and_cleared() = runTest {
        val store = FakeStore()
        val controller = SettingsController(store, TestScope(testScheduler))
        runCurrent()

        controller.set { it.copy(localeOverride = "ar") }
        runCurrent()
        assertEquals("ar", store.stored.localeOverride)

        controller.set { it.copy(localeOverride = null) }
        runCurrent()
        assertNull(store.stored.localeOverride)
    }
}
