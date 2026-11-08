package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable

/** iOS has no system back to intercept; see the `expect`. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
