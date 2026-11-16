package com.labteto.dshmobile.connection

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * How much of the agent's work the transcript shows by default: the DSH Web "Work details"
 * setting (工作步骤展示), with the same four levels in the same order.
 *
 * The stored value is the [key] string, like [DELIVERY_QUEUE] and [DELIVERY_STEER] for the send
 * mode, so a hand-edited or newer preference can never leave the Settings row with nothing
 * selected: anything unrecognised reads as [DEFAULT_WORK_STEP_DISPLAY].
 *
 * What each level changes lives in the transcript's policy (`workStepPolicyFor`); this file only
 * names the levels and parses them. It is a per-device preference. The host's own `ui-chat`
 * settings document is deliberately not read or written, because a phone and a desktop want
 * different levels.
 */
enum class WorkStepDisplay(val key: String) {
    Compact("compact"),
    Standard("standard"),
    Detailed("detailed"),
    Verbose("verbose"),
    ;

    companion object {
        /** The level stored as [key], or [DEFAULT_WORK_STEP_DISPLAY] for null and anything else. */
        fun of(key: String?): WorkStepDisplay = entries.firstOrNull { it.key == key } ?: DEFAULT_WORK_STEP_DISPLAY
    }
}

/**
 * What a phone shows until the reader chooses: [WorkStepDisplay.Compact].
 *
 * It is the level that reproduces the transcript as it was before the setting existed: finished
 * turns fold into one row, tool calls stay in a closed group (also while running), the group header
 * names no running detail and a thinking row shows no preview. Standard adds the last two, so none
 * of the other three levels matches the old look.
 */
val DEFAULT_WORK_STEP_DISPLAY: WorkStepDisplay = WorkStepDisplay.Compact

/** The DataStore key the level is stored under. */
internal val WORK_STEP_DISPLAY_PREF = stringPreferencesKey("work_step_display")

/** The stored level's key, normalised: an absent or unknown value reads as the default. */
internal fun Preferences.readWorkStepDisplay(): String = WorkStepDisplay.of(this[WORK_STEP_DISPLAY_PREF]).key

/** Stores [value] as a known level's key; an unknown value is stored as the default. */
internal fun MutablePreferences.writeWorkStepDisplay(value: String) {
    this[WORK_STEP_DISPLAY_PREF] = WorkStepDisplay.of(value).key
}
