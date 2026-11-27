package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import com.labteto.dshmobile.core.usage.UsageLabels

/**
 * The words and the digits a usage line is written with, in the reader's language: the short names of the quota
 * windows (`5h`, `week`, `d`, `h`, `m`) and the locale's decimal mark, digits and thousands mark. Android reads the
 * number symbols of the app's locale (`DecimalFormatSymbols`); iOS writes Western digits, a point and a comma, as its
 * other counts do ([com.labteto.dshmobile.ui.components.localizedCount]).
 */
@Composable
expect fun quotaLabels(): UsageLabels
