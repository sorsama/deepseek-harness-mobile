package com.labteto.dshmobile.connection

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

// How the Settings > Chat > Work steps level is kept on Android. The levels themselves are in
// sharedUi (WorkStepDisplay.kt), which the transcript's policy reads on both platforms.

/** The DataStore key the level is stored under. */
internal val WORK_STEP_DISPLAY_PREF = stringPreferencesKey("work_step_display")

/** The stored level's key, normalised: an absent or unknown value reads as the default. */
internal fun Preferences.readWorkStepDisplay(): String = WorkStepDisplay.of(this[WORK_STEP_DISPLAY_PREF]).key

/** Stores [value] as a known level's key; an unknown value is stored as the default. */
internal fun MutablePreferences.writeWorkStepDisplay(value: String) {
    this[WORK_STEP_DISPLAY_PREF] = WorkStepDisplay.of(value).key
}
