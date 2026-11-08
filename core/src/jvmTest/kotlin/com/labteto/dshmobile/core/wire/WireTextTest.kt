package com.labteto.dshmobile.core.wire

import java.net.URLEncoder
import java.util.Base64
import java.util.Random
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DshApiClient` moved to common code, and with it two conversions it took from the JDK. These
 * hold the common versions equal to the JDK's so the Android app sends and accepts what it always did.
 */
class WireTextTest {

    private fun jdkEncode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private val samples = listOf(
        "", "abc", "ABC019", "a b", " ", "  leading and trailing  ", "a+b", "a%b", "100%", "%20", "%zz",
        "a&b=c", "a/b\\c", "a?b#c", "a:b@c;d,e", "(parens)", "[brackets]", "{braces}", "<angle>", "\"quotes\"", "'single'",
        "!*'();:@&=+\$,/?#[]", "~tilde", "a.b-c_d*e", "tab\there", "line\nbreak\r", "\u0000nul", "\u007fdel",
        "café", "ÿ", "Ā", "߿", "ࠀ", "￿", "€", "กข thai", "中文",
        "😀", "emoji 😀 mid",
        "session-1234-abcd", "7f3c1e0a-5b0d-4c1e-9f2e-0a1b2c3d4e5f", "report (final) v2.pdf", "é́",
    )

    @Test
    fun `query components encode exactly as the JDK encoder with spaces as percent-20`() {
        for (sample in samples) assertEquals("<$sample>", jdkEncode(sample), encodeQueryComponent(sample))
    }

    @Test
    fun `every single character encodes as the JDK encoder does`() {
        for (code in 0..0xFFFF) {
            if (code in 0xD800..0xDFFF) continue // a lone surrogate: see the next test
            val sample = code.toChar().toString()
            assertEquals("U+${code.toString(16)}", jdkEncode(sample), encodeQueryComponent(sample))
        }
    }

    @Test
    fun `every supplementary character encodes as the JDK encoder does`() {
        val random = Random(17)
        repeat(20_000) {
            val sample = String(Character.toChars(0x10000 + random.nextInt(0x100000)))
            assertEquals("U+${sample.codePointAt(0).toString(16)}", jdkEncode(sample), encodeQueryComponent(sample))
        }
    }

    @Test
    fun `random strings encode as the JDK encoder does`() {
        val random = Random(11)
        repeat(20_000) {
            val text = StringBuilder()
            repeat(random.nextInt(12)) {
                when (random.nextInt(8)) {
                    0 -> text.appendCodePoint(0x10000 + random.nextInt(0x100000))
                    else -> {
                        // Anything in the BMP that is not half of a surrogate pair.
                        var code = random.nextInt(0x3000)
                        if (random.nextInt(4) == 0) code = 0xE000 + random.nextInt(0x1FFF)
                        text.append(code.toChar())
                    }
                }
            }
            val sample = text.toString()
            assertEquals("<${sample.map { it.code.toString(16) }}>", jdkEncode(sample), encodeQueryComponent(sample))
        }
    }

    @Test
    fun `a lone surrogate is written as a question mark on every platform`() {
        assertEquals("%3F", encodeQueryComponent("\ud83d"))
        assertEquals("%3F", encodeQueryComponent("\ude00"))
        assertEquals("a%3Fb", encodeQueryComponent("a\ud83db"))
        assertEquals("%3F%3F", encodeQueryComponent("\ude00\ud83d"))
        assertEquals("%3F%F0%9F%98%80", encodeQueryComponent("\ud83d😀"))
    }

    @Test
    fun `a query parameter value is encoded as OkHttp encodes it`() {
        // The iOS URL code builds `?token=` with this function, so it must match what the JVM's
        // `HttpUrl.Builder.addQueryParameter` writes.
        val base = "http://example.com/".toHttpUrl()
        val random = Random(5)
        val inputs = samples + List(5_000) { String(CharArray(random.nextInt(10)) { (32 + random.nextInt(95)).toChar() }) }
        for (value in inputs) {
            if (value.any { it.isSurrogate() }) continue
            val written = base.newBuilder().addQueryParameter("token", value).build().encodedQuery
            assertEquals("<$value>", "token=" + encodeQueryComponent(value), written)
        }
    }

    // ---------------------------------------------------------------- base64

    private fun jdkDecode(text: String): List<Byte>? = runCatching { Base64.getDecoder().decode(text).toList() }.getOrNull()

    private fun ourDecode(text: String): List<Byte>? = runCatching { decodeBase64(text).toList() }.getOrNull()

    @Test
    fun `base64 decodes valid text to the same bytes as the JDK`() {
        val random = Random(3)
        for (size in 0..64) {
            val bytes = ByteArray(size) { random.nextInt(256).toByte() }
            val padded = Base64.getEncoder().encodeToString(bytes)
            val unpadded = Base64.getEncoder().withoutPadding().encodeToString(bytes)
            assertArrayEquals("padded, $size bytes", bytes, decodeBase64(padded))
            assertArrayEquals("unpadded, $size bytes", bytes, decodeBase64(unpadded))
        }
        assertEquals(0, decodeBase64("").size)
    }

    @Test
    fun `base64 refuses or accepts malformed text as the JDK does`() {
        val texts = listOf(
            "A", "AB", "ABC", "ABCD", "ABCDE", "AB=", "AB==", "AB===", "ABC=", "ABC==", "A===", "A=", "A==", "====", "=", "==",
            "AB=C", "AB==CD", "ABCD=", "ABCD==", "ABCDEFGH=", "ABCDEFG=", "ABCDEF==", "ABCDEF=",
            "AB CD", "AB\nCD", "AB\r\nCD", " ABCD", "ABCD ", "AB-_", "AB+/", "AéCD", "AB\u0000D", "ÿBCD",
            "TWFu", "TWE=", "TQ==", "TWE", "TQ", "TR==", "TX==", "TWF=", "Zm9vYg", "Zm9vYg==", "Zm9vYg=", "Zm9vYmE", "Zm9vYmE=",
        )
        for (text in texts) assertEquals("<$text>", jdkDecode(text), ourDecode(text))
    }

    @Test
    fun `random base64 text is accepted or refused as the JDK does`() {
        val random = Random(21)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        var accepted = 0
        repeat(40_000) {
            val text = buildString {
                repeat(random.nextInt(14)) {
                    append(
                        when (random.nextInt(40)) {
                            0 -> '='
                            1 -> ' '
                            2 -> '-'
                            3 -> '_'
                            4 -> '\n'
                            else -> alphabet[random.nextInt(alphabet.length)]
                        },
                    )
                }
                if (random.nextInt(3) == 0) repeat(random.nextInt(4)) { append('=') }
            }
            val expected = jdkDecode(text)
            if (expected != null) accepted++
            assertEquals("<$text>", expected, ourDecode(text))
        }
        assertTrue("the generator must produce many valid texts, produced $accepted", accepted > 5_000)
    }
}
