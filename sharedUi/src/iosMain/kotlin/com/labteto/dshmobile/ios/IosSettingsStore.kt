package com.labteto.dshmobile.ios

import com.labteto.dshmobile.ui.screens.settings.AppearanceSettings
import com.labteto.dshmobile.ui.screens.settings.SettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSUserDefaults

/**
 * The appearance and language settings, in `NSUserDefaults`.
 *
 * This is the iOS counterpart of the part of Android's `HostsStore` that holds `AppSettings`: the
 * same values, the same defaults, a different place. It is the [SettingsStore] the shared Settings
 * page writes through; nothing here is a secret, so none of it is in the Keychain.
 *
 * The keys are prefixed `dsh.appearance.` so a later reset or migration can find them, and the two
 * nullable values are removed rather than stored as an empty string: absent means "follow the
 * system", which is also what a fresh install has.
 */
internal class UserDefaultsSettingsStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : SettingsStore {

    private val mutex = Mutex()

    override suspend fun appearance(): AppearanceSettings = read()

    override suspend fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings) = mutex.withLock {
        val next = transform(read())
        defaults.setObject(next.themePreference, forKey = KEY_THEME)
        defaults.setObject(next.themeAccent, forKey = KEY_ACCENT)
        if (next.themeCustomAccent == null) defaults.removeObjectForKey(KEY_CUSTOM_ACCENT)
        else defaults.setObject(next.themeCustomAccent, forKey = KEY_CUSTOM_ACCENT)
        defaults.setBool(next.amoledBlack, forKey = KEY_AMOLED)
        defaults.setObject(next.fontSize, forKey = KEY_FONT_SIZE)
        defaults.setBool(next.hapticsEnabled, forKey = KEY_HAPTICS)
        defaults.setObject(next.hapticStrength, forKey = KEY_HAPTIC_STRENGTH)
        if (next.localeOverride == null) defaults.removeObjectForKey(KEY_LOCALE)
        else defaults.setObject(next.localeOverride, forKey = KEY_LOCALE)
    }

    private fun read(): AppearanceSettings = AppearanceSettings(
        themePreference = defaults.stringForKey(KEY_THEME) ?: "system",
        themeAccent = defaults.stringForKey(KEY_ACCENT) ?: "ink",
        themeCustomAccent = defaults.stringForKey(KEY_CUSTOM_ACCENT),
        amoledBlack = bool(KEY_AMOLED, false),
        fontSize = defaults.stringForKey(KEY_FONT_SIZE) ?: "default",
        hapticsEnabled = bool(KEY_HAPTICS, true),
        hapticStrength = defaults.stringForKey(KEY_HAPTIC_STRENGTH) ?: "normal",
        localeOverride = defaults.stringForKey(KEY_LOCALE),
    )

    /** `boolForKey` answers false for a key that was never written, which is wrong for a default of true. */
    private fun bool(key: String, fallback: Boolean): Boolean =
        if (defaults.objectForKey(key) == null) fallback else defaults.boolForKey(key)

    private companion object {
        const val KEY_THEME = "dsh.appearance.theme"
        const val KEY_ACCENT = "dsh.appearance.accent"
        const val KEY_CUSTOM_ACCENT = "dsh.appearance.customAccent"
        const val KEY_AMOLED = "dsh.appearance.amoled"
        const val KEY_FONT_SIZE = "dsh.appearance.fontSize"
        const val KEY_HAPTICS = "dsh.appearance.haptics"
        const val KEY_HAPTIC_STRENGTH = "dsh.appearance.hapticStrength"
        const val KEY_LOCALE = "dsh.appearance.locale"
    }
}
