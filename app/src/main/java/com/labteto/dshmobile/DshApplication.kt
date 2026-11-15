package com.labteto.dshmobile

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.labteto.dshmobile.connection.KeepAliveWorker
import com.labteto.dshmobile.notify.NotificationObserver
import com.labteto.dshmobile.runtime.RuntimeUpdates
import com.labteto.dshmobile.runtime.workspace.ProjectOperations
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DshApplication : Application() {

    // Injecting this constructs SessionStore + ConnectionManager and starts
    // their frame collectors; start() then begins notification classification.
    @Inject lateinit var notificationObserver: NotificationObserver

    // Harness updates: the daily check, the notifications and automatic installs.
    @Inject lateinit var runtimeUpdates: RuntimeUpdates

    // Projects of the built-in harness: the Files app's root follows the install.
    @Inject lateinit var projects: ProjectOperations

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(nightModeFor(storedThemePreference(this)))
        KeepAliveWorker.schedule(this)
        notificationObserver.start()
        runtimeUpdates.start()
        projects.start()
    }

    companion object {
        private const val UI_PREFS = "ui_prefs"
        private const val KEY_THEME = "theme_preference"

        /**
         * A synchronously-readable copy of the Appearance preference.
         *
         * The preference itself lives in DataStore, which is only readable from a coroutine — and
         * the scheme has to be known before any activity exists (see [nightModeFor]). Reading
         * DataStore with `runBlocking` here deadlocked startup and left the app on its splash
         * screen, so this mirror exists purely to be readable at that moment.
         * [com.labteto.dshmobile.connection.HostsStore] writes it whenever the preference changes;
         * an absent value means the default, which is to follow the system.
         */
        fun storedThemePreference(context: Context): String? =
            context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null)

        /** Mirror [themePreference] so the next process start can read it before any activity. */
        fun storeThemePreference(context: Context, themePreference: String) {
            context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME, themePreference)
                .apply()
        }

        /**
         * The [AppCompatDelegate] mode for a stored `themePreference`.
         *
         * Compose owns the palette; this is for everything it does not draw — the window
         * background, and the theme a starting window uses — which resolve from `values` vs
         * `values-night` and otherwise follow the *device's* setting. An app set to Dark on a light
         * phone therefore had a white window behind it, and that window is what shows during the
         * activity recreate a language change forces.
         *
         * Applied from `Application.onCreate` because [AppCompatDelegate.setDefaultNightMode]
         * recreates any activity already running, and the mode is not persisted across process
         * death — doing it from `MainActivity` would cost an extra recreate on every cold start for
         * anyone who had chosen a scheme. Nothing is running yet at this point, so it is free.
         */
        fun nightModeFor(themePreference: String?): Int = when (themePreference) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }
}
