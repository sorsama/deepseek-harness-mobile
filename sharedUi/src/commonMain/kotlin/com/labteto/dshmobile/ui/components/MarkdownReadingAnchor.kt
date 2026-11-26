package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.LayoutCoordinates

/** Optional geometry observer supplied by a resizable transcript, not by other Markdown hosts. */
val LocalMarkdownReadingObserver = staticCompositionLocalOf<((Any, LayoutCoordinates) -> Unit)?> { null }
