package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * How wide the screen the app is drawn on is: Android's `Configuration.screenWidthDp` (the window less the system
 * bars beside it, as the chat always measured it), the window's own width on iOS.
 */
@Composable
internal expect fun screenWidth(): Dp

/**
 * How tall the screen the app is drawn on is: Android's `Configuration.screenHeightDp` (the window less the system bars
 * above and below it, which the approval card has always kept a fraction of), the window's own height on iOS.
 */
@Composable
internal expect fun screenHeight(): Dp
