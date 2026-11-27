package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable

/** Western digits, like every count on iOS (see `localizedCount`). */
@Composable
internal actual fun countdownNumber(seconds: Int): String = seconds.toString()
