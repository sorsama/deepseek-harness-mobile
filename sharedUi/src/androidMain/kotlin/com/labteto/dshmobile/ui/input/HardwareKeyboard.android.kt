package com.labteto.dshmobile.ui.input

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
actual fun hardwareKeyboardAttached(): Boolean =
    LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS
