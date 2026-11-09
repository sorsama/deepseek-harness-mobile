package com.labteto.dshmobile.core.wire

import java.util.Random
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `PortableMultipart` is what iOS parses harness 0.1.7's multipart answers with; Android parses them
 * with OkHttp's `MultipartReader`. These tests run both over the same inputs, so "the same body gives
 * the same parts, and a body one refuses the other refuses" is asserted rather than hoped for.
 */
class PortableMultipartTest {

    private class Outcome(val parts: List<Pair<String?, List<Byte>>>?)

    private fun jvm(contentType: String, body: ByteArray) = outcome { readMultipartParts(contentType, body) }

    private fun portable(contentType: String, body: ByteArray) = outcome { PortableMultipart.read(contentType, body) }

    private fun outcome(read: () -> List<MultipartPart>): Outcome =
        Outcome(runCatching { read().map { it.contentDisposition to it.body.toList() } }.getOrNull())

    private fun assertSame(contentType: String, body: ByteArray, label: String) {
        val expected = jvm(contentType, body)
        val actual = portable(contentType, body)
        val shown = { String(body, Charsets.ISO_8859_1).replace("\r", "\\r").replace("\n", "\\n\n") }
        assertEquals("$label: accepted by OkHttp = accepted here\n${shown()}", expected.parts != null, actual.parts != null)
        assertEquals("$label: same parts\n${shown()}", expected.parts, actual.parts)
    }

    // ---------------------------------------------------------------- the Content-Type

    @Test
    fun `the boundary is read from a media type exactly as OkHttp reads it`() {
        val types = listOf(
            "multipart/form-data; boundary=abc",
            "multipart/form-data;boundary=abc",
            "multipart/form-data;   boundary=abc",
            "multipart/form-data; \tboundary=abc",
            "MULTIPART/FORM-DATA; BOUNDARY=abc",
            "multipart/form-data; boundary=\"abc def\"",
            "multipart/form-data; boundary=\"\"",
            "multipart/form-data; boundary='abc'",
            "multipart/form-data; boundary=''",
            "multipart/form-data; boundary=a'b'c",
            "multipart/form-data; charset=utf-8; boundary=abc",
            "multipart/form-data; boundary=abc; charset=utf-8",
            "multipart/form-data; boundary=abc; boundary=def",
            "multipart/form-data; boundary=abc;",
            "multipart/form-data; boundary=abc; ",
            "multipart/form-data; ; boundary=abc",
            "multipart/form-data; boundary",
            "multipart/form-data; boundary=",
            "multipart/form-data; boundary=\"abc",
            "multipart/form-data; boundary=abc\"def\"",
            "multipart/form-data; boundary=abc def",
            "multipart/form-data; =abc",
            "multipart/form-data boundary=abc",
            "multipart/form-data",
            "multipart/",
            "multipart",
            "/form-data; boundary=abc",
            " multipart/form-data; boundary=abc",
            "multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW",
            "multipart/form-data; boundary=!#$%&'*+.^_`{|}~-09azAZ",
            "multipart/mixed; boundary=\"gc0p4Jq0M2Yt08jU534c0p\"",
            "text/plain; charset=utf-8",
            "",
        )
        for (type in types) {
            assertEquals("boundary of <$type>", type.toMediaTypeOrNull()?.parameter("boundary"), PortableMultipart.boundaryOf(type))
        }
    }

    @Test
    fun `a media type is read the same way when characters are thrown at it`() {
        val random = Random(20260711)
        val alphabet = "multipartform-data;=\" '\\tboundary/abcXYZ019!#\$.~\r\n,"
        repeat(20_000) {
            val type = buildString {
                if (random.nextInt(3) > 0) append("multipart/form-data")
                repeat(random.nextInt(14)) { append(alphabet[random.nextInt(alphabet.length)]) }
                if (random.nextBoolean()) append("; boundary=").append(if (random.nextBoolean()) "abc" else "\"a b\"")
            }
            assertEquals("boundary of <$type>", type.toMediaTypeOrNull()?.parameter("boundary"), PortableMultipart.boundaryOf(type))
        }
    }

    // ---------------------------------------------------------------- bodies

    private fun crlf(vararg lines: String) = lines.joinToString("") { it + "\r\n" }

    @Test
    fun `the shape the harness writes parses to its metadata and byte parts`() {
        val boundary = "----DshBoundary1234"
        val bytes = ByteArray(300) { (it * 7).toByte() }
        val body = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"metadata\"\r\n" +
                "Content-Type: application/json\r\n\r\n" +
                "{\"type\":\"server-response\"}\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"bytes-0\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            ).toByteArray() + bytes + "\r\n--$boundary--\r\n".toByteArray()
        val type = "multipart/form-data; boundary=$boundary"

        val parts = PortableMultipart.read(type, body)
        assertEquals(2, parts.size)
        assertEquals("form-data; name=\"metadata\"", parts[0].contentDisposition)
        assertEquals("{\"type\":\"server-response\"}", String(parts[0].body))
        assertArrayEquals(bytes, parts[1].body)
        assertSame(type, body, "harness shape")
    }

    @Test
    fun `no boundary is refused as an illegal argument by both`() {
        val body = "--x\r\n\r\n\r\n--x--".toByteArray()
        for (type in listOf("multipart/form-data", "application/json", "")) {
            try {
                readMultipartParts(type, body)
                fail("OkHttp side accepted <$type>")
            } catch (expected: IllegalArgumentException) {
                // refused
            }
            try {
                PortableMultipart.read(type, body)
                fail("portable side accepted <$type>")
            } catch (expected: IllegalArgumentException) {
                // refused
            }
        }
    }

    @Test
    fun `an empty message and a message with only the close delimiter are refused by both`() {
        for (text in listOf("", "--x--", "--x--\r\n", "\r\n--x--\r\n", "preamble\r\n--x--\r\n")) {
            assertSame("multipart/form-data; boundary=x", text.toByteArray(), "<$text>")
            assertNull(portable("multipart/form-data; boundary=x", text.toByteArray()).parts)
        }
    }

    @Test
    fun `hand-written variations agree with OkHttp`() {
        val type = "multipart/form-data; boundary=BB"
        val cases = mapOf(
            "preamble" to "ignored text\r\n--BB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nx\r\n--BB--\r\n",
            "preamble that mentions the boundary without CRLF" to "see --BB\r\n--BB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nx\r\n--BB--",
            "epilogue" to "--BB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nx\r\n--BB--\r\ngarbage --BB\r\n--BB\r\n",
            "no trailing newline" to "--BB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nx\r\n--BB--",
            "padding after a delimiter" to "--BB \t \r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nx\r\n--BB\t\r\nContent-Disposition: form-data; name=\"b\"\r\n\r\ny\r\n--BB--",
            "padding before the close" to "--BB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nx\r\n--BB --\r\n",
            "text after a delimiter" to "--BB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nx\r\n--BBx\r\n",
            "bare LF header lines" to "--BB\r\nContent-Disposition: form-data; name=\"a\"\nX-Other: 1\n\r\nx\r\n--BB--",
            "bare LF delimiters" to "--BB\nContent-Disposition: form-data; name=\"a\"\n\nx\n--BB--",
            "empty body" to "--BB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n\r\n--BB--",
            "no headers" to "--BB\r\n\r\nbody\r\n--BB--",
            "several dispositions: the last wins" to "--BB\r\nContent-Disposition: form-data; name=\"a\"\r\nCONTENT-DISPOSITION: form-data; name=\"b\"\r\n\r\nx\r\n--BB--",
            "lower-case header name" to "--BB\r\ncontent-disposition: form-data; name=\"a\"\r\n\r\nx\r\n--BB--",
            "space before the colon" to "--BB\r\nContent-Disposition : form-data; name=\"a\"\r\n\r\nx\r\n--BB--",
            "value padded with spaces" to "--BB\r\nContent-Disposition:    form-data; name=\"a\"   \r\n\r\nx\r\n--BB--",
            "a header with no colon" to "--BB\r\nbogus line\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nx\r\n--BB--",
            "a header starting with a colon" to "--BB\r\n:odd\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nx\r\n--BB--",
            "body that looks like a delimiter without its CRLF" to "--BB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nx--BB\r\n--BB--",
            "body with a near-miss delimiter" to "--BB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nx\r\n--B\r\n--BBB\r\n-\r\n--BB--",
            "body that is exactly a delimiter-sized lookalike" to "--BB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n\r\n--B\r\n--BB--",
            "only a preamble" to "nothing here",
            "unterminated part" to "--BB\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nx",
            "unterminated headers" to "--BB\r\nContent-Disposition: form-data; name=\"a\"",
            "delimiter then end" to "--BB",
            "delimiter then CRLF then end" to "--BB\r\n",
            "single dash after the delimiter" to "--BB-",
            "non-ASCII header value" to "--BB\r\nContent-Disposition: form-data; name=\"caf\u00e9\"\r\n\r\nx\r\n--BB--",
        )
        for ((label, text) in cases) assertSame(type, text.toByteArray(), label)
    }

    @Test
    fun `every truncation of a well-formed body is read as OkHttp reads it`() {
        val type = "multipart/form-data; boundary=BB"
        val full = (
            "pre\r\n--BB\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: a/b\r\n\r\n{}\r\n" +
                "--BB \r\nContent-Disposition: form-data; name=\"bytes-0\"\r\n\r\n\u0001\u0002\r\n-\r\n--B\r\n" +
                "--BB--\r\nepilogue"
            ).toByteArray(Charsets.ISO_8859_1)
        for (length in 0..full.size) assertSame(type, full.copyOf(length), "truncated to $length")
    }

    @Test
    fun `generated messages are read as OkHttp reads them`() {
        val random = Random(7)
        val boundaries = listOf("x", "BB", "----WebKitFormBoundary7MA4YWxk", "a'b", "q-q_q.q", "with space", "UPPER", "1")
        val dispositions = listOf(
            "Content-Disposition: form-data; name=\"metadata\"",
            "Content-Disposition: form-data; name=\"bytes-0\"",
            "Content-Disposition: form-data; name=bytes-1",
            "content-disposition: form-data; NAME=\"bytes-2\"; filename=\"f\"",
            "Content-Disposition: attachment",
            "Content-Type: application/json",
            "X-Empty:",
            "no colon at all",
            ":leading",
            "Content-Disposition:\tform-data; name=\"tab\"",
        )
        val nasty = listOf("\r\n", "\r\n-", "\r\n--", "\r", "\n", "--", "-", "\r\n\r\n", "\u0000", "\u00ff")
        var accepted = 0
        repeat(4_000) { case ->
            val boundary = boundaries[random.nextInt(boundaries.size)]
            val type = if (boundary.all { it.isLetterOrDigit() || it in "-_." }) {
                "multipart/form-data; boundary=$boundary"
            } else {
                "multipart/form-data; boundary=\"$boundary\""
            }
            val out = java.io.ByteArrayOutputStream()
            fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
            if (random.nextInt(4) == 0) write("preamble " + (if (random.nextBoolean()) "--$boundary" else "") + "\r\n")
            val partCount = random.nextInt(4)
            repeat(partCount) {
                write("--$boundary")
                write(listOf("", "", " ", "\t", " \t ")[random.nextInt(5)])
                write("\r\n")
                repeat(random.nextInt(4)) {
                    write(dispositions[random.nextInt(dispositions.size)])
                    write(if (random.nextInt(8) == 0) "\n" else "\r\n")
                }
                write("\r\n")
                val bodySize = random.nextInt(40)
                repeat(bodySize) {
                    if (random.nextInt(6) == 0) {
                        write(nasty[random.nextInt(nasty.size)])
                    } else if (random.nextInt(9) == 0) {
                        // A prefix of the real delimiter, which must not end the part.
                        val delimiter = "\r\n--$boundary"
                        write(delimiter.substring(0, random.nextInt(delimiter.length)))
                    } else {
                        out.write(random.nextInt(256))
                    }
                }
                write("\r\n")
            }
            when (random.nextInt(6)) {
                0 -> Unit
                1 -> write("--$boundary")
                2 -> write("--$boundary--" + "\r\nepilogue --$boundary")
                else -> write("--$boundary--" + listOf("", "\r\n")[random.nextInt(2)])
            }
            val body = out.toByteArray()
            assertSame(type, body, "generated #$case")
            if (portable(type, body).parts != null) accepted++
        }
        assertTrue("the generator must produce many valid messages, produced $accepted", accepted > 500)
    }

    @Test
    fun `random damage to a well-formed message is read as OkHttp reads it`() {
        val random = Random(99)
        val type = "multipart/form-data; boundary=BB"
        val base = (
            "--BB\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n{\"a\":1}\r\n" +
                "--BB\r\nContent-Disposition: form-data; name=\"bytes-0\"\r\n\r\nabc\r\n--BB--\r\n"
            ).toByteArray()
        repeat(6_000) { case ->
            val body = base.copyOf()
            repeat(1 + random.nextInt(3)) { body[random.nextInt(body.size)] = "-\r\nB \t:x".random(random).code.toByte() }
            assertSame(type, body, "damaged #$case")
        }
    }

    private fun String.random(random: Random): Char = this[random.nextInt(length)]
}
