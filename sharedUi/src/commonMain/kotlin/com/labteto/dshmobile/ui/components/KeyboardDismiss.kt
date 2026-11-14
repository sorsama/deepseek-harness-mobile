package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Closes the surface this is placed on when Escape is pressed.
 *
 * Android gives a dialog or a sheet no Escape handling of its own (back is the touch gesture, and a
 * physical keyboard's Escape is just another key), so a window that should close with it says so
 * here. Escape is looked at before anything inside the window, so it closes the surface even from a
 * text field.
 *
 * A key event needs somewhere to land: when the window opens with nothing focused inside it (a menu
 * sheet, a confirmation) the surface takes focus itself, once the first frame has settled, and
 * leaves it alone if a field in the window has already claimed it.
 */
fun Modifier.dismissOnEscape(enabled: Boolean = true, onDismiss: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        composed {
            val current by rememberUpdatedState(onDismiss)
            val requester = remember { FocusRequester() }
            var held by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                if (!held) runCatching { requester.requestFocus() }
            }
            Modifier
                .focusRequester(requester)
                .onFocusChanged { held = it.hasFocus }
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape) {
                        if (event.type == KeyEventType.KeyDown) current()
                        true
                    } else {
                        false
                    }
                }
        }
    }
