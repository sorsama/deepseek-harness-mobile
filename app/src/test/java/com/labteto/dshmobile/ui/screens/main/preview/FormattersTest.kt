package com.labteto.dshmobile.ui.screens.main.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `sizes read in the largest fitting unit`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("3.4 MB", formatBytes((3.4 * 1024 * 1024).toLong()))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `a player clock shows minutes and seconds, and hours when needed`() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:07", formatClock(7_400))
        assertEquals("3:45", formatClock(225_000))
        assertEquals("59:59", formatClock(3_599_999))
        assertEquals("1:02:09", formatClock(3_729_000))
        assertEquals("0:00", formatClock(-5))
    }

    @Test
    fun `a path splits into its folder and name`() {
        assertEquals("src/main/" to "App.kt", splitPath("src/main/App.kt"))
        assertEquals("" to "README.md", splitPath("README.md"))
        assertEquals("C:/work/" to "a.txt", splitPath("C:\\work\\a.txt"))
        assertEquals("/" to "etc", splitPath("/etc"))
    }

    @Test
    fun `a path that fits is left alone`() {
        assertEquals("src/main/", ellipsizeStart("src/main/") { it.length <= 20 })
    }

    @Test
    fun `a long path loses its start and keeps its end`() {
        val shown = ellipsizeStart("workspace/app/src/main/java/com/example/") { it.length <= 16 }
        assertEquals(16, shown.length)
        assertEquals("…", shown.substring(0, 1))
        assertEquals(true, shown.endsWith("com/example/"))
    }

    @Test
    fun `when nothing fits only the ellipsis is left`() {
        assertEquals("…", ellipsizeStart("abcdef") { it.isEmpty() || it.length <= 1 })
    }

    @Test
    fun `dimensions use a multiplication sign`() {
        assertEquals("1920 \u00D7 1080", formatDimensions(1920, 1080))
    }

    @Test
    fun `double tap zoom steps up and wraps back to fit`() {
        assertEquals(2f, nextZoomStep(1f, 5f), 0f)
        assertEquals(4f, nextZoomStep(2f, 5f), 0f)
        assertEquals(1f, nextZoomStep(4f, 5f), 0f)
        assertEquals(3f, nextZoomStep(2f, 3f), 0f)
        assertEquals(1f, nextZoomStep(3f, 3f), 0f)
    }
}
