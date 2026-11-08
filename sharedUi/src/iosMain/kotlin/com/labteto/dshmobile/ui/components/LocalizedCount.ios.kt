package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle

/** The number in the device's language and numerals, without a grouping separator. */
@Composable
actual fun localizedCount(value: Int): String {
    val formatter = NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterDecimalStyle
        usesGroupingSeparator = false
    }
    return formatter.stringFromNumber(NSNumber.numberWithInt(value)) ?: value.toString()
}
