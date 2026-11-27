package com.labteto.dshmobile.ui.screens.main

import androidx.core.text.BidiFormatter

internal actual fun isolatedCommand(text: String): String = BidiFormatter.getInstance().unicodeWrap(text)
