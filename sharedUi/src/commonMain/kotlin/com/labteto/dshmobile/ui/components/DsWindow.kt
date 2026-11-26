package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

/**
 * The one place a dialog window is created: [DsDialog] and, on Android, `DsFullScreenDialog` open theirs
 * through it, and `WindowRulesTest` fails the build if any other file creates a Compose `Dialog`.
 *
 * [content] fills the whole window and receives the insets to pad with (system bars, cutout and the keyboard).
 * [dimBehind] darkens what is under the window, for a plate that does not cover it.
 *
 * Android (`DsFullScreen.kt`) asks the window manager for the whole display, because a plain Compose `Dialog`
 * is laid out inside the system bars on Android 15 and newer. iOS uses Compose's own dialog at the size of the
 * screen and pads by the safe area.
 */
@Composable
internal expect fun DsWindow(
    onDismissRequest: () -> Unit,
    dimBehind: Boolean,
    content: @Composable (insets: WindowInsets) -> Unit,
)
