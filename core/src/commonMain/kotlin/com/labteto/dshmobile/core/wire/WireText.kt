package com.labteto.dshmobile.core.wire

/*
 * The two text conversions the wire layer used to take from `java.net` and `java.util`, written so
 * they give the same answers on every platform. `WireTextTest` holds each equal to the JDK's over
 * tables and generated input, so moving the callers to common code did not change what the Android
 * app sends or accepts.
 */

/**
 * Percent-encode one query-string value: `URLEncoder.encode(value, "UTF-8")` with each `+` it
 * writes for a space spelled `%20` instead.
 *
 * Letters, digits and `. - * _` pass through; every other character is written as `%XX` for each
 * byte of its UTF-8 form, upper-case hex. That is also exactly what OkHttp's
 * `HttpUrl.Builder.addQueryParameter` does to a value, which is why the Ktor-side URL code can
 * share it. A lone surrogate is written as `?` (`%3F`) on every platform, so the answer does not
 * depend on how a platform's UTF-8 encoder replaces malformed text. (The JDK is not consistent about
 * a lone surrogate, dropping some in a long run, so only well-formed text is held equal to it; no
 * session id or file name the harness or the OS hands out contains one.)
 */
internal fun encodeQueryComponent(value: String): String {
    val out = StringBuilder(value.length + 8)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        val codePoint: Int
        if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) {
            codePoint = 0x10000 + ((c.code - 0xD800) shl 10) + (value[i + 1].code - 0xDC00)
            i += 2
        } else {
            codePoint = if (c.isSurrogate()) '?'.code else c.code
            i += 1
        }
        when {
            codePoint < 0x80 -> {
                val ch = codePoint.toChar()
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '*' || ch == '_') {
                    out.append(ch)
                } else {
                    appendPercent(out, codePoint)
                }
            }
            codePoint < 0x800 -> {
                appendPercent(out, 0xC0 or (codePoint shr 6))
                appendPercent(out, 0x80 or (codePoint and 0x3F))
            }
            codePoint < 0x10000 -> {
                appendPercent(out, 0xE0 or (codePoint shr 12))
                appendPercent(out, 0x80 or ((codePoint shr 6) and 0x3F))
                appendPercent(out, 0x80 or (codePoint and 0x3F))
            }
            else -> {
                appendPercent(out, 0xF0 or (codePoint shr 18))
                appendPercent(out, 0x80 or ((codePoint shr 12) and 0x3F))
                appendPercent(out, 0x80 or ((codePoint shr 6) and 0x3F))
                appendPercent(out, 0x80 or (codePoint and 0x3F))
            }
        }
    }
    return out.toString()
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun appendPercent(out: StringBuilder, byte: Int) {
    out.append('%').append(HEX_DIGITS[(byte shr 4) and 0x0F]).append(HEX_DIGITS[byte and 0x0F])
}

/**
 * Decode standard (RFC 4648 §4) base64 the way `java.util.Base64.getDecoder()` does, which is the
 * reading the 0.1.6 file-read fallback has always had: padding is accepted but not required, bits
 * left over after the last whole byte are ignored whatever their value, and a character outside the
 * alphabet, padding in the wrong place or a lone trailing character is an
 * [IllegalArgumentException]. Line breaks are not skipped.
 *
 * Written out rather than taken from `kotlin.io.encoding.Base64`, which is still experimental in
 * this Kotlin version and refuses non-zero leftover bits, so it would reject text the JDK accepts.
 */
internal fun decodeBase64(text: String): ByteArray {
    val out = ByteArray(text.length / 4 * 3 + 3)
    var written = 0
    var index = 0
    var bits = 0
    var shift = 18 // where the next sextet lands in the 24-bit group
    while (index < text.length) {
        val sextet = base64Value(text[index++])
        if (sextet < 0) {
            if (sextet == PADDING) {
                // `=` is only right as the 3rd or 4th character of a group, and a 3rd needs a 4th.
                if (shift == 18 || (shift == 6 && (index == text.length || text[index++] != '='))) {
                    throw IllegalArgumentException("base64 text has a wrong 4-character ending unit")
                }
                break
            }
            throw IllegalArgumentException("illegal base64 character ${text[index - 1].code.toString(16)}")
        }
        bits = bits or (sextet shl shift)
        shift -= 6
        if (shift < 0) {
            out[written++] = (bits shr 16).toByte()
            out[written++] = (bits shr 8).toByte()
            out[written++] = bits.toByte()
            shift = 18
            bits = 0
        }
    }
    when (shift) {
        6 -> out[written++] = (bits shr 16).toByte()
        0 -> {
            out[written++] = (bits shr 16).toByte()
            out[written++] = (bits shr 8).toByte()
        }
        12 -> throw IllegalArgumentException("base64 text ends in a single character")
    }
    // Anything after the padding is invalid.
    if (index < text.length) throw IllegalArgumentException("base64 text has data after its padding")
    return out.copyOf(written)
}

private const val PADDING = -2

private fun base64Value(c: Char): Int = when (c) {
    in 'A'..'Z' -> c - 'A'
    in 'a'..'z' -> c - 'a' + 26
    in '0'..'9' -> c - '0' + 52
    '+' -> 62
    '/' -> 63
    '=' -> PADDING
    else -> -1
}
