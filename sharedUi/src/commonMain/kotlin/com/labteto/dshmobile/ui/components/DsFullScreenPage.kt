package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/**
 * A page over the whole screen, on the app's base colour, padded for the system bars and the keyboard: the
 * full-screen output viewer is one, the composer's full-screen page another.
 *
 * Android is `DsFullScreenDialog` as it stands (its window rules, and Back as a key press closing the page even
 * with a text field focused). iOS opens a [DsWindow] with the same surface and padding.
 *
 * [visibleState] and [onWindowReady] are `DsFullScreenDialog`'s: with a state the page rises in and sinks away
 * through it (the caller keeps it composed until it has left), and [onWindowReady] says when the window is on
 * screen, which is when such a caller starts the entry. iOS draws the page in the app's own window, so it is ready
 * on its first frame.
 */
@Composable
internal expect fun DsFullScreenPage(
    onDismissRequest: () -> Unit,
    visibleState: MutableTransitionState<Boolean>? = null,
    onWindowReady: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
)
