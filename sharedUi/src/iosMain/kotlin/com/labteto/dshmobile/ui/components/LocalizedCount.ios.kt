package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable

/**
 * Western digits. Android localizes the digits of a count (see the `expect`) because its `getString`
 * does; on iOS the text was never localized that way, and a count in a failure message reads
 * correctly in every one of the 11 languages with plain digits.
 */
@Composable
actual fun localizedCount(value: Int): String = value.toString()

/** Western digits and a point, like [localizedCount]. */
@Composable
actual fun localizedDecimal(value: Double, decimals: Int): String {
    val places = decimals.coerceIn(0, 6)
    var scale = 1L
    repeat(places) { scale *= 10 }
    val scaled = kotlin.math.round(value * scale).toLong()
    if (places == 0) return scaled.toString()
    val whole = scaled / scale
    val fraction = (kotlin.math.abs(scaled) % scale).toString().padStart(places, '0')
    val sign = if (scaled < 0 && whole == 0L) "-" else ""
    return "$sign$whole.$fraction"
}
