package com.labteto.dshmobile.core.wire

/**
 * One part of a multipart body: its `Content-Disposition` header, when it has one, and its bytes.
 *
 * That header is the only one [RpcMultipart] reads (it names the part), so the rest are not carried.
 */
internal class MultipartPart(val contentDisposition: String?, val body: ByteArray)

/**
 * Split a multipart body into its parts, in order.
 *
 * `expect`/`actual` so the Android app keeps parsing with OkHttp's `MultipartReader`, the parser it
 * has always used, while iOS (which has no OkHttp) uses [PortableMultipart]. The two are held to the
 * same answers by `PortableMultipartTest`, which runs both over generated bodies.
 *
 * @throws IllegalArgumentException when [contentType] names no boundary.
 * @throws Exception (an I/O error on the JVM, [IllegalArgumentException] in [PortableMultipart])
 *   when the body is not well-formed. The unary client treats the first as "not a harness" and the
 *   second as a crash, which is the JVM's behaviour from before this was shared and was left alone.
 */
internal expect fun readMultipartParts(contentType: String, body: ByteArray): List<MultipartPart>

/**
 * A multipart reader that needs nothing but the Kotlin standard library: the iOS side of
 * [readMultipartParts].
 *
 * It reads the same grammar OkHttp's `MultipartReader` does, because that is the parser the
 * harness's answers were checked against: an optional preamble, `--boundary` delimiters that must be
 * preceded by CRLF, optional spaces and tabs after a delimiter, header lines ended by CRLF or LF
 * (a header's name is everything before its first colon after the first character, its value is
 * trimmed), the body running to the next `CRLF--boundary`, `--boundary--` closing the message and
 * anything after it being ignored. The same body gives the same parts, and a body OkHttp refuses
 * is refused here.
 */
internal object PortableMultipart {

    fun read(contentType: String, body: ByteArray): List<MultipartPart> {
        val boundary = boundaryOf(contentType)
            ?: throw IllegalArgumentException("multipart response carries no boundary")
        val dashBoundary = ("--$boundary").encodeToByteArray()
        val crlfDashBoundary = ("\r\n--$boundary").encodeToByteArray()
        val parts = ArrayList<MultipartPart>()

        var pos = if (body.startsWith(dashBoundary, 0)) {
            dashBoundary.size
        } else {
            // A preamble: everything up to the first delimiter is ignored.
            val first = body.indexOf(crlfDashBoundary, 0)
            if (first < 0) throw IllegalArgumentException("multipart body has no boundary")
            first + crlfDashBoundary.size
        }

        while (true) {
            // After a delimiter: CRLF starts a part, `--` ends the message, spaces and tabs are padding.
            var padded = false
            while (true) {
                when {
                    body.startsWith(CRLF, pos) -> {
                        pos += 2
                        break
                    }
                    body.startsWith(DASHES, pos) -> {
                        if (padded) throw IllegalArgumentException("unexpected characters after boundary")
                        if (parts.isEmpty()) throw IllegalArgumentException("expected at least 1 part")
                        return parts
                    }
                    pos < body.size && (body[pos] == SPACE || body[pos] == TAB) -> {
                        padded = true
                        pos++
                    }
                    else -> throw IllegalArgumentException("unexpected characters after boundary")
                }
            }

            var disposition: String? = null
            while (true) {
                val end = body.indexOf(LF, pos)
                if (end < 0) throw IllegalArgumentException("multipart headers end early")
                val lineEnd = if (end > pos && body[end - 1] == CR) end - 1 else end
                val line = body.decodeToString(pos, lineEnd)
                pos = end + 1
                if (line.isEmpty()) break
                val colon = line.indexOf(':', 1)
                val name = if (colon != -1) line.substring(0, colon) else ""
                val value = if (colon != -1) line.substring(colon + 1) else line
                // The last header of a name wins, as `Headers.get` has it.
                if (name.equals("Content-Disposition", ignoreCase = true)) disposition = value.trim()
            }

            val next = body.indexOf(crlfDashBoundary, pos)
            if (next < 0) throw IllegalArgumentException("multipart part is not terminated")
            parts += MultipartPart(disposition, body.copyOfRange(pos, next))
            pos = next + crlfDashBoundary.size
        }
    }

    /**
     * The `boundary` parameter of a media type, or null when the text is not a media type or has none.
     *
     * OkHttp's `MediaType` grammar, `type "/" subtype *( ";" ws* [ token "=" ( token | quoted ) ] )`,
     * read the way its `toMediaTypeOrNull` does: the first parameter of that name, any text the
     * grammar does not account for makes the whole value invalid, and a token wrapped in single
     * quotes loses them.
     */
    fun boundaryOf(contentType: String): String? {
        var i = skipToken(contentType, 0)
        if (i == 0 || i >= contentType.length || contentType[i] != '/') return null
        val subtypeStart = i + 1
        i = skipToken(contentType, subtypeStart)
        if (i == subtypeStart) return null

        var boundary: String? = null
        while (i < contentType.length) {
            if (contentType[i] != ';') return null
            i++
            while (i < contentType.length && isRegexSpace(contentType[i])) i++
            val nameEnd = skipToken(contentType, i)
            if (nameEnd == i) continue
            // A name with no `=value` after it is not a parameter, and what follows does not parse.
            if (nameEnd >= contentType.length || contentType[nameEnd] != '=') return null
            val name = contentType.substring(i, nameEnd)
            var valueStart = nameEnd + 1
            val value: String
            if (valueStart < contentType.length && contentType[valueStart] == '"') {
                val close = contentType.indexOf('"', valueStart + 1)
                if (close < 0) return null
                value = contentType.substring(valueStart + 1, close)
                i = close + 1
            } else {
                val valueEnd = skipToken(contentType, valueStart)
                if (valueEnd == valueStart) return null
                val token = contentType.substring(valueStart, valueEnd)
                value = if (token.length > 2 && token.startsWith("'") && token.endsWith("'")) {
                    token.substring(1, token.length - 1)
                } else {
                    token
                }
                i = valueEnd
            }
            if (boundary == null && name.equals("boundary", ignoreCase = true)) boundary = value
        }
        return boundary
    }

    /** The index after the run of RFC 7230 token characters that starts at [from]. */
    private fun skipToken(text: String, from: Int): Int {
        var i = from
        while (i < text.length && isTokenChar(text[i])) i++
        return i
    }

    private fun isTokenChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-!#$%&'*+.^_`{|}~"

    /** What `\s` matches in a Java regular expression. */
    private fun isRegexSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    private const val CR: Byte = 13
    private const val LF: Byte = 10
    private const val SPACE: Byte = 32
    private const val TAB: Byte = 9
    private val CRLF = byteArrayOf(CR, LF)
    private val DASHES = byteArrayOf('-'.code.toByte(), '-'.code.toByte())

    private fun ByteArray.startsWith(prefix: ByteArray, at: Int): Boolean {
        if (at < 0 || at + prefix.size > size) return false
        for (i in prefix.indices) if (this[at + i] != prefix[i]) return false
        return true
    }

    private fun ByteArray.indexOf(value: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == value) return i
        return -1
    }

    private fun ByteArray.indexOf(needle: ByteArray, from: Int): Int {
        val last = size - needle.size
        var i = from
        while (i <= last) {
            if (startsWith(needle, i)) return i
            i++
        }
        return -1
    }
}
