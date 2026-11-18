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
 * Back, when it arrives as a key press, closes the surface this is placed on even while a text
 * field inside it has focus.
 *
 * Seen on Android 11 (key-based back, no predictive back): with a text field focused, the first back
 * hid the keyboard, the second did nothing at all, and only the third closed the sheet (and, later,
 * the Plugins page). The focused field takes the key before the window's own back handling sees it.
 * A preview handler on the surface runs before the field does, so the second back closes it. The
 * first back never gets here with the keyboard up: the keyboard takes that one and hides itself.
 * Newer Android delivers back as a callback rather than a key, so this never fires there.
 *
 * Put it on a node that is an ancestor of the fields, not beside them: a preview handler only sees
 * the keys of what is focused below it.
 */
fun Modifier.dismissOnBackKey(onDismiss: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.key == Key.Back) {
        if (event.type == KeyEventType.KeyUp) onDismiss()
        true
    } else {
        false
    }
}

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
