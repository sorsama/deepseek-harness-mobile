package com.labteto.dshmobile.ui.screens.main

import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `fileSizeText` (common code since the tool cards moved) writes what it wrote with
 * `String.format(Locale.US, "%.1fMB", …)` before: the chip of every file size reads the same.
 */
class FileSizeTextTest {

    private fun before(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format(Locale.US, "%.1fMB", bytes.toDouble() / (1024 * 1024))
    }

    @Test
    fun `the unit boundaries read as before`() {
        listOf(0L, 1L, 1023L, 1024L, 1025L, 1_048_575L, 1_048_576L, 1_101_005L, 1_572_864L, 10_485_760L, 5_000_000_000L)
            .forEach { assertEquals("$it", before(it), fileSizeText(it)) }
        assertEquals("1.0MB", fileSizeText(1_048_576L))
        assertEquals("1023KB", fileSizeText(1_048_575L))
    }

    @Test
    fun `sizes of every scale match`() {
        val random = Random(141)
        repeat(20_000) {
            val bytes = when (it % 3) {
                0 -> random.nextLong(0, 4096)
                1 -> random.nextLong(0, 50L * 1024 * 1024)
                else -> random.nextLong(0, 1L shl 40)
            }
            assertEquals("$bytes", before(bytes), fileSizeText(bytes))
        }
    }
}
