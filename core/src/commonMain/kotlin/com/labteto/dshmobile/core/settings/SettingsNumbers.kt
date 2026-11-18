package com.labteto.dshmobile.core.settings

import kotlin.math.abs
import kotlin.math.floor

/**
 * Numbers as text, the same on every platform.
 *
 * `String.toDouble()` and `Double.toString()` are not specified to agree between the JVM and
 * Kotlin/Native: a 16-digit integer such as `9007199254740991` can come back one step off, and a
 * large or tiny double prints as `1.0E21` on one and differently on another. Anything a person
 * types, reads or has compared goes through this file instead: the digits are held as text and
 * whole numbers are handled as `Long`, which both platforms treat identically.
 */

/** The largest whole number a JavaScript number holds exactly (2^53 - 1); the harness cannot store more. */
internal const val MAX_SAFE_INTEGER = 9007199254740991L

private const val MAX_SAFE_DIGITS = "9007199254740991"

/** Longer than any plausible exponent; the guard against `1e999999999` building a huge string. */
private const val MAX_EXPONENT_DIGITS = 4
private const val MAX_EXPONENT = 1000

/**
 * A decimal number split into digits, with no rounding anywhere.
 *
 * [integer] has no leading zeros (`"0"` when there are none), [fraction] has no trailing zeros, and
 * zero is never negative, so two numbers with the same value are equal as objects (`4`, `4.0`,
 * `+4`, `004`, `4e0`, `-0`, `-0.0`).
 */
internal data class DecimalText(val negative: Boolean, val integer: String, val fraction: String) {
    val isWhole: Boolean get() = fraction.isEmpty()

    /** Beyond what the harness stores exactly, in either direction. */
    val exceedsSafeRange: Boolean
        get() {
            val order = if (integer.length != MAX_SAFE_DIGITS.length) integer.length.compareTo(MAX_SAFE_DIGITS.length) else integer.compareTo(MAX_SAFE_DIGITS)
            return order > 0 || (order == 0 && fraction.isNotEmpty())
        }

    /** The value as a `Long`, when it is a whole number inside the safe range. Pure integer arithmetic. */
    fun toWholeLongOrNull(): Long? {
        if (!isWhole || exceedsSafeRange) return null
        var value = 0L
        for (c in integer) value = value * 10 + (c - '0')
        return if (negative) -value else value
    }

    /**
     * The nearest double. Whole numbers in the safe range are exact (they pass through `Long`);
     * anything beyond the range is an infinity, so a comparison with a limit is decided by the
     * digits and never by how a platform rounds a long string.
     */
    fun toDoubleOrNull(): Double? {
        if (exceedsSafeRange) return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        toWholeLongOrNull()?.let { return it.toDouble() }
        return plain().toDoubleOrNull()
    }

    /** `-12.5`: ASCII digits, a point, no exponent, no trailing zeros. */
    fun plain(): String = buildString {
        if (negative) append('-')
        append(integer)
        if (fraction.isNotEmpty()) {
            append('.')
            append(fraction)
        }
    }
}

/**
 * Reads `[+-]digits[.digits]`, and with [allowExponent] a trailing `e`/`E` and signed exponent as
 * well. Nothing else is accepted (no `.5`, no `5.`, no `NaN`, no hex), so what passes is what a
 * JSON number looks like. Null when the text is anything else.
 */
internal fun parseDecimal(raw: String, allowExponent: Boolean = false): DecimalText? {
    val n = raw.length
    var i = 0
    var negative = false
    if (i < n && (raw[i] == '+' || raw[i] == '-')) {
        negative = raw[i] == '-'
        i++
    }
    val integerStart = i
    while (i < n && raw[i] in '0'..'9') i++
    val integerDigits = raw.substring(integerStart, i)
    if (integerDigits.isEmpty()) return null
    var fractionDigits = ""
    if (i < n && raw[i] == '.') {
        i++
        val start = i
        while (i < n && raw[i] in '0'..'9') i++
        fractionDigits = raw.substring(start, i)
        if (fractionDigits.isEmpty()) return null
    }
    var exponent = 0
    if (i < n && (raw[i] == 'e' || raw[i] == 'E')) {
        if (!allowExponent) return null
        i++
        var exponentNegative = false
        if (i < n && (raw[i] == '+' || raw[i] == '-')) {
            exponentNegative = raw[i] == '-'
            i++
        }
        val start = i
        while (i < n && raw[i] in '0'..'9') i++
        val digits = raw.substring(start, i)
        if (digits.isEmpty() || digits.length > MAX_EXPONENT_DIGITS) return null
        exponent = digits.toInt()
        if (exponent > MAX_EXPONENT) return null
        if (exponentNegative) exponent = -exponent
    }
    if (i != n) return null

    // Move the point by the exponent, on the digit string.
    val digits = integerDigits + fractionDigits
    val point = integerDigits.length + exponent
    val whole: String
    val fraction: String
    when {
        point <= 0 -> {
            whole = "0"
            fraction = "0".repeat(-point) + digits
        }
        point >= digits.length -> {
            whole = digits + "0".repeat(point - digits.length)
            fraction = ""
        }
        else -> {
            whole = digits.substring(0, point)
            fraction = digits.substring(point)
        }
    }
    val integer = whole.trimStart('0').ifEmpty { "0" }
    val trimmedFraction = fraction.trimEnd('0')
    val isZero = integer == "0" && trimmedFraction.isEmpty()
    return DecimalText(negative && !isZero, integer, trimmedFraction)
}

/**
 * A number as a person writes it: `50`, `0.5`, `1000000000000000000000`. Never an exponent, never
 * a trailing `.0`, never `-0`. Whole numbers in the safe range are printed through `Long`; any
 * other value goes through `Double.toString()` once and is then read back as digits, so an
 * exponent form from either platform (`1.0E21`, `1e+21`, `1.0E-7`) comes out the same.
 */
fun formatNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    if (value == 0.0) return "0"
    if (abs(value) <= MAX_SAFE_INTEGER.toDouble() && floor(value) == value) return value.toLong().toString()
    val printed = value.toString()
    return parseDecimal(printed, allowExponent = true)?.plain() ?: printed
}
