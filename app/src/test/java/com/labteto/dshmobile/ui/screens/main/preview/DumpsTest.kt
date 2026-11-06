package com.labteto.dshmobile.ui.screens.main.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DumpsTest {

    @Test
    fun `a sixteen byte row has an offset, hex with a middle gap and ascii`() {
        val rows = hexDump("Hello, hex dump!Next".toByteArray(), bytesPerRow = 16)
        assertEquals(2, rows.size)
        assertEquals(
            "0000  48 65 6c 6c 6f 2c 20 68  65 78 20 64 75 6d 70 21  |Hello, hex dump!|",
            rows[0],
        )
        // The last row is padded so the ascii column lines up with the rows above it.
        assertTrue(rows[1].startsWith("0010  4e 65 78 74 "))
        assertTrue(rows[1].endsWith("|Next|"))
        assertEquals(rows[0].indexOf('|'), rows[1].indexOf('|'))
        assertEquals(rows[0].length - 12, rows[1].length)
    }

    @Test
    fun `unprintable bytes show as dots`() {
        val rows = hexDump(byteArrayOf(0x00, 0x1F, 0x41, 0x7F, 0x80.toByte(), 0xFF.toByte()), bytesPerRow = 8)
        assertEquals("0000  00 1f 41 7f 80 ff " + " ".repeat(6) + " |..A...|", rows.single())
    }

    @Test
    fun `an eight byte row has no middle gap`() {
        val row = hexDump(ByteArray(8) { it.toByte() }, bytesPerRow = 8).single()
        assertEquals("0000  00 01 02 03 04 05 06 07  |........|", row)
    }

    @Test
    fun `the dump stops at the limit and the offset column fits the file`() {
        val rows = hexDump(ByteArray(1000), limit = 32, bytesPerRow = 16, totalSize = 5_000_000)
        assertEquals(2, rows.size)
        // 5,000,000 needs six hex digits.
        assertTrue(rows[0].startsWith("000000  "))
        assertTrue(rows[1].startsWith("000010  "))
        assertEquals(0, hexDump(ByteArray(0)).size)
    }

    @Test
    fun `a zip lists its entries without opening them`() {
        val file = File.createTempFile("dump-test", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("docs/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("docs/readme.txt"))
                zip.write("hello world".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("a.bin"))
                zip.write(ByteArray(2048))
                zip.closeEntry()
            }
            val listing = listZipEntries(file)!!
            assertEquals(3, listing.total)
            assertFalse(listing.truncated)
            val readme = listing.entries.first { it.name == "docs/readme.txt" }
            assertEquals(11L, readme.size)
            assertFalse(readme.directory)
            assertTrue(listing.entries.first { it.name == "docs/" }.directory)
            assertEquals(2048L, listing.entries.first { it.name == "a.bin" }.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a long zip is capped and says so`() {
        val file = File.createTempFile("dump-test", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                repeat(20) { zip.putNextEntry(ZipEntry("f$it.txt")); zip.write(1); zip.closeEntry() }
            }
            val listing = listZipEntries(file, maxEntries = 5)!!
            assertEquals(5, listing.entries.size)
            assertEquals(20, listing.total)
            assertTrue(listing.truncated)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `something that is not a zip lists as null`() {
        val file = File.createTempFile("dump-test", ".zip")
        try {
            file.writeText("definitely not a zip file")
            assertNull(listZipEntries(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `find reports each match by line and column in reading order`() {
        val lines = listOf("alpha beta", "Beta beta", "gamma")
        val result = findMatches(lines, "beta")
        assertEquals(listOf(TextMatch(0, 6, 10), TextMatch(1, 0, 4), TextMatch(1, 5, 9)), result.matches)
        assertFalse(result.capped)
    }

    @Test
    fun `find can be case sensitive`() {
        val result = findMatches(listOf("Beta beta"), "Beta", ignoreCase = false)
        assertEquals(listOf(TextMatch(0, 0, 4)), result.matches)
    }

    @Test
    fun `find does not overlap and ignores an empty query`() {
        assertEquals(2, findMatches(listOf("aaaa"), "aa").matches.size)
        assertEquals(0, findMatches(listOf("anything"), "").matches.size)
        assertEquals(0, findMatches(emptyList(), "x").matches.size)
    }

    @Test
    fun `find stops at the cap`() {
        val result = findMatches(List(100) { "x x x" }, "x", limit = 10)
        assertEquals(10, result.matches.size)
        assertTrue(result.capped)
    }
}
