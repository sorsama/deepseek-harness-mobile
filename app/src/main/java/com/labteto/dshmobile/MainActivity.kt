package com.labteto.dshmobile

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.notify.DshNotifications
import com.labteto.dshmobile.ui.AppRoot
import com.labteto.dshmobile.ui.input.LocalShortcutRouter
import com.labteto.dshmobile.ui.input.ShortcutCatalog
import com.labteto.dshmobile.ui.input.ShortcutRouter
import com.labteto.dshmobile.ui.input.matchAppShortcut
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var hostsStore: HostsStore
    @Inject lateinit var notifications: DshNotifications

    /** Where keyboard shortcuts are announced; the main screen and its panes listen. */
    private val shortcuts = ShortcutRouter()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not — notifications degrade gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        notifications.ensureChannels()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)

        // Apply the persisted in-app language (11 locales, incl. Thai/RTL).
        //
        // This only handles a *change* made while the app is running. Restoring the choice on
        // launch is AppCompat's job, via the `autoStoreLocales` service declared in the manifest:
        // it re-applies the stored locale in `attachBaseContext`, before anything is composed. That
        // ordering is the point. Below API 33 the per-app locale lives on the AppCompatActivity's
        // base context, and any window built from a context captured earlier keeps the device
        // language — which is how bottom sheets and dialogs ended up disagreeing with the rest of
        // the app. Setting it from here alone could never fix that; this call is deliberately after
        // onCreate, which is where the AppCompat contract requires it.
        //
        // Only set the locale when it actually differs, or the recreate it triggers loops.
        lifecycleScope.launch {
            hostsStore.settings.collect { settings ->
                applyNightMode(settings.themePreference)

                val desiredLocales = settings.localeOverride?.let { tag ->
                    LocaleListCompat.forLanguageTags(tag)
                } ?: LocaleListCompat.getEmptyLocaleList()
                
                val currentLocales = AppCompatDelegate.getApplicationLocales()
                
                // Only update if locales actually changed to prevent recreation loop
                if (desiredLocales.toLanguageTags() != currentLocales.toLanguageTags()) {
                    AppCompatDelegate.setApplicationLocales(desiredLocales)
                }
            }
        }

        setContent {
            CompositionLocalProvider(LocalShortcutRouter provides shortcuts) {
                AppRoot()
            }
        }
    }

    /**
     * The platform's keyboard shortcuts panel (Search+/ on a Chromebook, Meta+/ on Android desktop
     * and on tablets with a keyboard) lists what [ShortcutCatalog] declares. The labels are read
     * here, from the activity's own context, so they follow the in-app language.
     */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>,
        menu: Menu?,
        deviceId: Int,
    ) {
        ShortcutCatalog.forEach { group ->
            data += KeyboardShortcutGroup(
                getString(group.title),
                group.entries.map { KeyboardShortcutInfo(getString(it.label), it.keyCode, it.modifiers) },
            )
        }
    }

    /**
     * Ctrl shortcuts nothing on screen took first. The main screen reads the same chords from its
     * own key events, which covers a focused window; this is for the moment nothing has focus yet
     * (just after launch, or after a dialog closed), which is when the first Ctrl+N tends to come.
     */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val shortcut = matchAppShortcut(
            key = Key(keyCode),
            ctrl = event.isCtrlPressed,
            shift = event.isShiftPressed,
            alt = event.isAltPressed,
            meta = event.isMetaPressed,
        )
        if (shortcut != null && event.repeatCount == 0) {
            shortcuts.post(shortcut)
            return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    /**
     * Keep the resource layer's scheme in step when the preference *changes* while running.
     *
     * [DshApplication.applyStoredNightMode] does the same thing at process start, which is where
     * the cost is zero; this only has to catch someone tapping Light or Dark. The guard is what
     * makes that true — without it, every settings emission would re-enter
     * [AppCompatDelegate.setDefaultNightMode] and recreate the activity.
     */
    private fun applyNightMode(themePreference: String) {
        val mode = DshApplication.nightModeFor(themePreference)
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
