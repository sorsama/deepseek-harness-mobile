package com.labteto.dshmobile.ui.input

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The app-wide keyboard shortcuts: things a person with a keyboard reaches for without finding the
 * button. Each is a Ctrl chord (Cmd is not matched on purpose: on a Chromebook the Search key is
 * Meta, and the system keeps its own combinations).
 */
enum class AppShortcut {
    /** Ctrl+N: start a new session. */
    NewSession,

    /** Ctrl+F: search the session list. */
    SearchSessions,

    /** Ctrl+K or Ctrl+/: move to the message box. */
    FocusComposer,

    /** Ctrl+B: show or hide the session list. */
    ToggleSessionList,

    /** Ctrl+, : open settings. */
    OpenSettings,
}

/**
 * Which shortcut a key press is, or null.
 *
 * Only a bare Ctrl chord counts: with Shift, Alt or Meta also down the press is somebody else's
 * (Ctrl+Shift+N is a different command nearly everywhere), so it is left alone.
 */
fun matchAppShortcut(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): AppShortcut? {
    if (!ctrl || shift || alt || meta) return null
    return when (key) {
        Key.N -> AppShortcut.NewSession
        Key.F -> AppShortcut.SearchSessions
        Key.K, Key.Slash -> AppShortcut.FocusComposer
        Key.B -> AppShortcut.ToggleSessionList
        Key.Comma -> AppShortcut.OpenSettings
        else -> null
    }
}

/**
 * Where shortcuts are announced. The activity and the root of the main screen post to it, and each
 * part of the screen that owns a command (the session list, the chat, the shell) listens for its
 * own. A shared flow, so several listeners can each see the same press.
 */
class ShortcutRouter {
    private val flow = MutableSharedFlow<AppShortcut>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<AppShortcut> = flow.asSharedFlow()

    fun post(shortcut: AppShortcut): Boolean = flow.tryEmit(shortcut)
}

val LocalShortcutRouter = staticCompositionLocalOf { ShortcutRouter() }
