package com.labteto.dshmobile.ui.screens.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * What the Settings screen asks of a platform.
 *
 * The screen is one page with a dozen cards, most of them Android-only (Termux, plugins, the update
 * checker, the data wipe). This file is the part iOS can have now: the appearance card and the
 * language row, over the small slice of the app's settings they read and write.
 *
 * Android: the slice is projected from `AppSettings` and written back through `HostsStore.setSetting`,
 * exactly as the settings page always did — no stored data moves and nothing needs a migration.
 * iOS: `UserDefaultsSettingsStore` in `iosMain` keeps the same values in `NSUserDefaults`.
 */

/** The slice of the app's settings the appearance card and the language row read and write. */
data class AppearanceSettings(
    /** `light`, `dark` or `system`. */
    val themePreference: String = "system",
    /** An [com.labteto.dshmobile.ui.theme.AccentPalette] key, or `custom`. */
    val themeAccent: String = "ink",
    /** The user's picked colour as `#RRGGBB`, kept even while a preset is in force. */
    val themeCustomAccent: String? = null,
    /** True-black canvas; only consulted in the dark scheme. */
    val amoledBlack: Boolean = false,
    /** `compact`, `default`, `large` or `larger`. */
    val fontSize: String = "default",
    val hapticsEnabled: Boolean = true,
    /** `light`, `normal` or `strong`. */
    val hapticStrength: String = "normal",
    /** A language tag, or null to follow the system. */
    val localeOverride: String? = null,
)

/**
 * Persistence of [AppearanceSettings].
 *
 * Android: `HostsStore` (Preferences DataStore, unchanged). iOS: `UserDefaultsSettingsStore`.
 * Both implementations read the values they have stored and fall back to the defaults above for
 * anything missing, so a fresh install and an older one behave the same.
 */
interface SettingsStore {
    suspend fun appearance(): AppearanceSettings

    /** Read, change and write the appearance settings as one step. */
    suspend fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings)
}

/**
 * The Settings screen's state on a platform whose entry point drives it (iOS).
 *
 * Android keeps its own `SettingsViewModel` and its `AppSettings` state, as the Connect screen's
 * view model keeps its controller; the shared composables take plain values and a setter, so the
 * controller is optional plumbing rather than a second source of truth.
 */
class SettingsController(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AppearanceSettings())
    val state: StateFlow<AppearanceSettings> = _state.asStateFlow()

    init {
        scope.launch { _state.value = store.appearance() }
    }

    fun set(transform: (AppearanceSettings) -> AppearanceSettings) {
        scope.launch {
            store.updateAppearance(transform)
            _state.value = store.appearance()
        }
    }
}
