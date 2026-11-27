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
    isRepeat = false,
    shift = isShiftPressed,
    ctrl = isCtrlPressed,
    meta = isMetaPressed,
    alt = isAltPressed,
    fromSoftKeyboard = false,
)
