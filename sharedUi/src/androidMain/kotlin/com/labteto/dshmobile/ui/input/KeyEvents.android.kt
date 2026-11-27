package com.labteto.dshmobile.ui.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

actual fun KeyEvent.toComposerKeyPress(): ComposerKeyPress = ComposerKeyPress(
    isEnter = key == Key.Enter || key == Key.NumPadEnter,
    isDown = type == KeyEventType.KeyDown,
    isRepeat = nativeKeyEvent.repeatCount > 0,
    shift = isShiftPressed,
    ctrl = isCtrlPressed,
    meta = isMetaPressed,
    alt = isAltPressed,
    // An on-screen keyboard marks the key events it sends. Some send none at all and commit text
    // instead, which never reaches a key handler in the first place.
    fromSoftKeyboard = nativeKeyEvent.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD != 0,
)
