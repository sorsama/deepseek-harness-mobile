package com.labteto.dshmobile.ui.screens.main.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewModelTest {

    @Test
    fun `kinds with two faces offer a toggle and the rest offer none`() {
        assertEquals(listOf("rendered", "source"), PreviewModes.forKind(PreviewKind.Markdown))
        assertEquals(listOf("rendered", "source"), PreviewModes.forKind(PreviewKind.Html))
        assertEquals(listOf("rendered", "source"), PreviewModes.forKind(PreviewKind.Svg))
        assertEquals(listOf("rendered", "raw"), PreviewModes.forKind(PreviewKind.Notebook))
        assertEquals(listOf("table", "raw"), PreviewModes.forKind(PreviewKind.Table))
        assertEquals(listOf("pretty", "tree", "raw"), PreviewModes.forKind(PreviewKind.Json))
        assertEquals(emptyList<String>(), PreviewModes.forKind(PreviewKind.Code))
        assertEquals(emptyList<String>(), PreviewModes.forKind(PreviewKind.Image))
    }

    @Test
    fun `a mode that the kind does not offer falls back to its first`() {
        assertEquals("rendered", PreviewModes.resolve(PreviewKind.Markdown, null))
        assertEquals("source", PreviewModes.resolve(PreviewKind.Markdown, "source"))
        assertEquals("table", PreviewModes.resolve(PreviewKind.Table, "tree"))
        assertEquals("tree", PreviewModes.resolve(PreviewKind.Json, "tree"))
        assertNull(PreviewModes.resolve(PreviewKind.Code, "source"))
    }

    @Test
    fun `the code view is used for source faces and plain code`() {
        assertTrue(usesCodeView(PreviewKind.Code, null))
        assertTrue(usesCodeView(PreviewKind.Text, null))
        assertTrue(usesCodeView(PreviewKind.Diff, null))
        assertFalse(usesCodeView(PreviewKind.Markdown, null))
        assertTrue(usesCodeView(PreviewKind.Markdown, "source"))
        assertFalse(usesCodeView(PreviewKind.Table, "table"))
        assertTrue(usesCodeView(PreviewKind.Table, "raw"))
        assertTrue(usesCodeView(PreviewKind.Json, null))
        assertFalse(usesCodeView(PreviewKind.Json, "tree"))
        assertTrue(usesCodeView(PreviewKind.Json, "raw"))
        assertFalse(usesCodeView(PreviewKind.Notebook, "rendered"))
        assertTrue(usesCodeView(PreviewKind.Notebook, "raw"))
        assertFalse(usesCodeView(PreviewKind.Image, null))
        assertFalse(usesCodeView(PreviewKind.Binary, null))
    }

    @Test
    fun `lines are numbered from one and a trailing newline adds no line`() {
        val rows = buildDisplayLines("one\ntwo\nthree\n")
        assertEquals(listOf("one", "two", "three"), rows.map { it.text })
        assertEquals(listOf(1, 2, 3), rows.map { it.number })
        assertEquals(listOf("only"), buildDisplayLines("only").map { it.text })
        assertEquals(listOf(""), buildDisplayLines("").map { it.text })
        assertEquals(listOf("", ""), buildDisplayLines("\n\n").map { it.text })
    }

    @Test
    fun `carriage returns are dropped and tabs are widened`() {
        val rows = buildDisplayLines("a\r\n\tb\r\n")
        assertEquals(listOf("a", "    b"), rows.map { it.text })
    }

    @Test
    fun `a very long line is cut into rows that continue it`() {
        val rows = buildDisplayLines("x".repeat(25) + "\nshort", maxRowChars = 10)
        assertEquals(listOf(10, 10, 5, 5), rows.map { it.text.length })
        assertEquals(listOf(1, 1, 1, 2), rows.map { it.number })
        assertEquals(listOf(false, true, true, false), rows.map { it.continuation })
    }

    @Test
    fun `an svg page drops the xml prolog and doctype and keeps the svg`() {
        val svg = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><circle r="4"/></svg>"""
        val page = svgPage(svg)
        assertFalse(page.contains("<?xml"))
        assertFalse(page.contains("DOCTYPE svg"))
        assertTrue(page.contains("<svg xmlns"))
        assertTrue(page.startsWith("<!doctype html>"))
        assertTrue(page.contains("max-width:100%"))
        assertTrue(page.endsWith("</svg></body></html>"))
    }

    @Test
    fun `a script in an svg is left as it is for the locked web view to ignore`() {
        // Whether a script runs is decided by the web view, which has scripting off, not by rewriting the file.
        val page = svgPage("<svg><script>alert(1)</script></svg>")
        assertTrue(page.contains("<script>alert(1)</script>"))
    }
}
