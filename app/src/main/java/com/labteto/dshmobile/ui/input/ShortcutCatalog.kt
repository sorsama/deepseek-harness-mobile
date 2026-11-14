package com.labteto.dshmobile.ui.input

import android.view.KeyEvent
import androidx.annotation.StringRes
import com.labteto.dshmobile.R

/** One line of the system's keyboard shortcuts panel (Search+/ on a Chromebook, Meta+/ elsewhere). */
data class ShortcutEntry(
    @StringRes val label: Int,
    val keyCode: Int,
    /** `KeyEvent.META_*` flags. */
    val modifiers: Int = 0,
)

data class ShortcutGroup(
    @StringRes val title: Int,
    val entries: List<ShortcutEntry>,
)

private const val Ctrl = KeyEvent.META_CTRL_ON
private const val Shift = KeyEvent.META_SHIFT_ON

/**
 * Everything the keyboard does in the app, in the order the system panel lists it.
 *
 * The panel is the platform's own: `Activity.onProvideKeyboardShortcuts` hands it these, and it
 * shows them wherever the platform has a helper (Search+/ on ChromeOS, Meta+/ on Android desktop
 * and tablets with a keyboard). The chords that are *handled* live in [matchAppShortcut] and in the
 * composer's key handling; a test keeps the two lists from drifting apart.
 */
val ShortcutCatalog: List<ShortcutGroup> = listOf(
    ShortcutGroup(
        R.string.shortcuts_group_message,
        listOf(
            ShortcutEntry(R.string.shortcuts_send, KeyEvent.KEYCODE_ENTER),
            ShortcutEntry(R.string.shortcuts_send, KeyEvent.KEYCODE_ENTER, Ctrl),
            ShortcutEntry(R.string.shortcuts_newline, KeyEvent.KEYCODE_ENTER, Shift),
            ShortcutEntry(R.string.shortcuts_focus_composer, KeyEvent.KEYCODE_K, Ctrl),
            ShortcutEntry(R.string.shortcuts_focus_composer, KeyEvent.KEYCODE_SLASH, Ctrl),
        ),
    ),
    ShortcutGroup(
        R.string.shortcuts_group_sessions,
        listOf(
            ShortcutEntry(R.string.shortcuts_new_session, KeyEvent.KEYCODE_N, Ctrl),
            ShortcutEntry(R.string.shortcuts_search_sessions, KeyEvent.KEYCODE_F, Ctrl),
            ShortcutEntry(R.string.shortcuts_toggle_list, KeyEvent.KEYCODE_B, Ctrl),
        ),
    ),
    ShortcutGroup(
        R.string.shortcuts_group_general,
        listOf(
            ShortcutEntry(R.string.shortcuts_settings, KeyEvent.KEYCODE_COMMA, Ctrl),
            ShortcutEntry(R.string.shortcuts_close, KeyEvent.KEYCODE_ESCAPE),
        ),
    ),
)
