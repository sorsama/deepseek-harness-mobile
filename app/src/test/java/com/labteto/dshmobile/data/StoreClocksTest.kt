package com.labteto.dshmobile.data

import java.util.Base64 as JavaBase64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The common Base64 and clock helpers the store uses in place of `android.util.Base64`, `System.currentTimeMillis()`
 * and `System.nanoTime()`.
 */
class StoreClocksTest {

    private val samples = listOf(
        ByteArray(0),
        byteArrayOf(0),
        byteArrayOf(-1, -2),
        byteArrayOf(1, 2, 3),
        ByteArray(256) { it.toByte() },
        ByteArray(1000) { (it * 7).toByte() },
    )

    @Test
    fun `encoding is standard Base64 with padding on one line`() {
        for (bytes in samples) {
            val encoded = encodeBase64(bytes)
            assertEquals(JavaBase64.getEncoder().encodeToString(bytes), encoded)
            assertTrue(encoded, '\n' !in encoded && '\r' !in encoded)
        }
    }

    @Test
    fun `decoding reads padded and unpadded text`() {
        for (bytes in samples) {
            val padded = JavaBase64.getEncoder().encodeToString(bytes)
            assertArrayEquals(bytes, decodeBase64Lenient(padded))
            assertArrayEquals(bytes, decodeBase64Lenient(padded.trimEnd('=')))
        }
    }

    @Test
    fun `decoding skips line breaks the way the Android decoder did`() {
        for (bytes in samples) {
            val wrapped = JavaBase64.getMimeEncoder().encodeToString(bytes)
            assertArrayEquals(bytes, decodeBase64Lenient(wrapped))
            assertArrayEquals(bytes, decodeBase64Lenient(wrapped.replace("\r\n", "\n")))
        }
    }

    @Test
    fun `the wall clock is the system clock`() {
        val before = System.currentTimeMillis()
        val read = wallClockMillis()
        val after = System.currentTimeMillis()
        assertTrue("$before <= $read <= $after", read in before..after)
    }

    @Test
    fun `the monotonic clock never goes back`() {
        var last = monotonicMillis()
        repeat(1000) {
            val next = monotonicMillis()
            assertTrue("$next < $last", next >= last)
            last = next
        }
    }
}
