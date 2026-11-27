package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/**
 * A page over the whole screen, on the app's base colour, padded for the system bars and the keyboard: the
 * full-screen output viewer is one.
 *
 * Android is `DsFullScreenDialog` as it stands (its window rules, and Back as a key press closing the page even
 * with a text field focused). iOS opens a [DsWindow] with the same surface and padding.
 */
@Composable
internal expect fun DsFullScreenPage(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit)
