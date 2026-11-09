package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable

/**
 * Intercepts the system "back" while [enabled], and runs [onBack] instead.
 *
 * Android: the activity's back dispatcher (`androidx.activity.compose.BackHandler`), exactly as the
 * screens always used it. iOS has no system back: its navigation is the app's own controls, so there
 * is nothing to intercept and this does nothing.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
