package com.labteto.dshmobile.data

/**
 * The epoch milliseconds of an ISO-8601 instant such as `2026-11-26T09:41:07.123Z`, or null when [text] is not one.
 *
 * A common stand-in for `java.time.Instant.parse(text).toEpochMilli()`, which the store used before it had to build
 * without `java.time`. It accepts what `Instant.parse` accepts in every shape a harness sends: a four-digit year,
 * `T` (either case), seconds, an optional fraction of 0 to 9 digits after a `.`, and `Z` (either case) or an offset
 * `+HH:MM` or `+HH:MM:SS`. Like `Instant.parse` it rejects impossible dates and times, reads `24:00:00` as the next
 * midnight and a leap second `23:59:60` as `23:59:59`, and drops digits finer than a millisecond rather than
 * rounding. `IsoInstantTest` holds the two equal on a table of inputs, valid and not.
 */
internal fun parseIsoInstantMillis(text: String): Long? {
    var at = 0

    fun number(digits: Int): Int? {
        if (at + digits > text.length) return null
        var value = 0
        for (index in at until at + digits) {
            val c = text[index]
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
        }
        at += digits
        return value
    }

    fun literal(expected: Char): Boolean {
        if (at >= text.length || !text[at].equals(expected, ignoreCase = true)) return false
        at++
        return true
    }

    val year = number(4) ?: return null
    if (!literal('-')) return null
    val month = number(2) ?: return null
    if (!literal('-')) return null
    val day = number(2) ?: return null
    if (!literal('T')) return null
    var hour = number(2) ?: return null
    if (!literal(':')) return null
    val minute = number(2) ?: return null
    if (!literal(':')) return null
    var second = number(2) ?: return null

    var nanos = 0
    if (literal('.')) {
        var digits = 0
        while (at < text.length && text[at] in '0'..'9') {
            if (digits == 9) return null
            nanos = nanos * 10 + (text[at] - '0')
            digits++
            at++
        }
        repeat(9 - digits) { nanos *= 10 }
    }

    val offsetSeconds: Int = when {
        literal('Z') -> 0
        at < text.length && (text[at] == '+' || text[at] == '-') -> {
            val sign = if (text[at] == '-') -1 else 1
            at++
            val offsetHours = number(2) ?: return null
            if (!literal(':')) return null
            val offsetMinutes = number(2) ?: return null
            val offsetSecs = if (literal(':')) number(2) ?: return null else 0
            if (offsetHours > 18 || offsetMinutes > 59 || offsetSecs > 59) return null
            val total = offsetHours * 3600 + offsetMinutes * 60 + offsetSecs
            if (total > 18 * 3600) return null
            sign * total
        }
        else -> return null
    }
    if (at != text.length) return null

    var extraDays = 0
    if (hour == 24 && minute == 0 && second == 0 && nanos == 0) {
        hour = 0
        extraDays = 1
    } else if (hour == 23 && minute == 59 && second == 60) {
        second = 59
    }
    if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
    if (hour > 23 || minute > 59 || second > 59) return null

    val epochDay = epochDayOf(year, month, day) + extraDays
    val epochSecond = epochDay * 86_400L + hour * 3_600L + minute * 60L + second - offsetSeconds
    return epochSecond * 1_000L + nanos / 1_000_000
}

private fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (isLeapYear(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** Days from 1970-01-01 to the given proleptic Gregorian date (Howard Hinnant's `days_from_civil`). */
private fun epochDayOf(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val monthIndex = (month + 9) % 12
    val dayOfYear = (153 * monthIndex + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}
