package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable

/**
 * Western digits. Android localizes the digits of a count (see the `expect`) because its `getString`
 * does; on iOS the text was never localized that way, and a count in a failure message reads
 * correctly in every one of the 11 languages with plain digits.
 */
@Composable
actual fun localizedCount(value: Int): String = value.toString()
