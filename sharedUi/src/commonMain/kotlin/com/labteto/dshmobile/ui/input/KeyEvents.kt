package com.labteto.dshmobile.ui.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

/**
 * The platform event reduced to what [decideComposerKey] needs.
 *
 * Android reads the repeat count and the soft-keyboard flag off the native event. iOS has neither on Compose's key
 * event: its on-screen keyboard commits text rather than sending key events, so what does arrive is from a hardware
 * keyboard, and a held key is not told apart from a press.
 */
expect fun KeyEvent.toComposerKeyPress(): ComposerKeyPress

/** Ctrl+V, Cmd+V or Shift+Insert: the three ways a keyboard pastes. */
fun isPasteChord(event: KeyEvent): Boolean =
    ((event.isCtrlPressed || event.isMetaPressed) && event.key == Key.V) ||
        (event.isShiftPressed && event.key == Key.Insert)
