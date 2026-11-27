package com.labteto.dshmobile.ui.input

import androidx.compose.runtime.Composable

/** Not yet detected on iOS (see the expect): Enter adds a line, as on a phone without a keyboard. */
@Composable
actual fun hardwareKeyboardAttached(): Boolean = false
