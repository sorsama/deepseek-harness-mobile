package com.labteto.dshmobile.core.wire

/**
 * The URL text rules `HarnessEndpoint` and `PortableUrls` share: OkHttp's percent-encoding of a
 * path and a query, and RFC 3986 dot-segment removal. Nothing here touches a URL library, which is
 * what lets both be written without OkHttp's `HttpUrl` and still be held equal to it by test.
 */
internal object UrlText {

    /** Characters a path segment must not carry raw (OkHttp's `PATH_SEGMENT_ENCODE_SET`, minus `/`). */
    const val PATH_ENCODE_SET = " \"<>^`{}|\\?#"

    /** Characters a query must not carry raw (OkHttp's `QUERY_ENCODE_SET`). */
    const val QUERY_ENCODE_SET = " \"'<>#"

    private const val HEX = "0123456789ABCDEF"

    /**
     * Percent-encode what a request line cannot carry, and nothing else.
     *
     * Existing `%XX` escapes pass through untouched (callers encode their own values, and
     * encoding twice would turn `%20` into `%2520`); a lone `%` is left as it is, as OkHttp's
     * non-strict canonicalization does. Controls, DEL, space-class characters in [encodeSet]
     * and every non-ASCII byte are encoded from their UTF-8 form.
     */
    fun canonicalize(input: String, encodeSet: String): String {
        val bytes = input.encodeToByteArray()
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val b = byte.toInt() and 0xff
            val c = b.toChar()
            if (b < 0x20 || b == 0x7f || b >= 0x80 || c in encodeSet) {
                out.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0f])
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }

    /** A character a host name, IPv4 address or (unbracketed) IPv6 literal may contain. */
    fun isHostChar(c: Char): Boolean =
        c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '_' || c == ':' || c == '%'

    private fun isDot(segment: String): Boolean = segment == "." || segment.equals("%2e", ignoreCase = true)

    private fun isDotDot(segment: String): Boolean =
        segment == ".." ||
            segment.equals("%2e.", ignoreCase = true) ||
            segment.equals(".%2e", ignoreCase = true) ||
            segment.equals("%2e%2e", ignoreCase = true)

    /** RFC 3986 §5.2.4 on an absolute [path], keeping a trailing slash where one is implied. */
    fun removeDotSegments(path: String): String {
        val segments = path.split('/').drop(1)
        val out = ArrayList<String>(segments.size)
        for ((index, segment) in segments.withIndex()) {
            val last = index == segments.lastIndex
            when {
                isDotDot(segment) -> {
                    if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                    if (last) out.add("")
                }
                isDot(segment) -> if (last) out.add("")
                else -> out.add(segment)
            }
        }
        return "/" + out.joinToString("/")
    }
}
