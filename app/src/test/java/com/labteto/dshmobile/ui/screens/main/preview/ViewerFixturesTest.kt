package com.labteto.dshmobile.ui.screens.main.preview

import com.labteto.dshmobile.mockharness.MockHarness
import com.labteto.dshmobile.mockharness.PanelScenario
import com.labteto.dshmobile.mockharness.addViewerFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * The detectors and parsers, run over the same sample files the mock harness serves for trying the
 * viewer by hand, so a fixture that stops being understood is caught here and not on a phone.
 */
class ViewerFixturesTest {

    private val files: Map<String, ByteArray> = PanelScenario(MockHarness()).also { it.addViewerFixtures() }.files

    private fun bytes(name: String) = files.getValue(name)
    private fun text(name: String) = bytes(name).toString(Charsets.UTF_8)
    private fun kind(name: String) = detectFileType(name)!!.kind

    @Test
    fun `every fixture is told apart by its name or its bytes`() {
        assertEquals(PreviewKind.Table, kind("data.csv"))
        assertEquals(PreviewKind.Table, kind("scores.tsv"))
        assertEquals(PreviewKind.Json, kind("settings.json"))
        assertEquals(PreviewKind.Json, kind("events.jsonl"))
        assertEquals(PreviewKind.Json, kind("places.geojson"))
        assertEquals(PreviewKind.Notebook, kind("notebook.ipynb"))
        assertEquals(PreviewKind.Diff, kind("change.patch"))
        assertEquals(PreviewKind.Svg, kind("logo.svg"))
        assertEquals(PreviewKind.Image, kind("image.png"))
        assertEquals(PreviewKind.Binary, kind("blob.bin"))
        assertEquals(PreviewKind.Archive, kind("bundle.zip"))
        assertEquals(PreviewKind.Text, kind("long.txt"))
        for (name in listOf("Dockerfile", "config.yaml", "settings.ini", "query.sql")) {
            assertEquals(name, PreviewKind.Code, kind(name))
        }
    }

    @Test
    fun `a picture with no extension is found by its first bytes`() {
        assertNull(typeFromName("image-noext"))
        val sniffed = detectFileType("image-noext", head = bytes("image-noext").copyOf(HEAD_BYTES.coerceAtMost(bytes("image-noext").size)))!!
        assertEquals(PreviewKind.Image, sniffed.kind)
        assertEquals("png", sniffed.extension)
        assertTrue(sniffed.sniffed)
    }

    @Test
    fun `the csv fixtures parse with their quotes intact`() {
        val data = parseDelimited(text("data.csv"), detectDelimiter(text("data.csv"), "csv"))
        assertEquals(5, data.totalRecords)
        assertEquals(listOf("2", "Grace", "Says \"hi\""), data.rows[2])
        assertEquals("two\nlines", data.rows[3][2])
        assertEquals(listOf("4", "Margaret", ""), data.rows[4])

        val wide = parseDelimited(text("wide.csv"), ',', keep = TABLE_ROW_CAP + 1)
        assertEquals(3001, wide.totalRecords)
        assertEquals(TABLE_ROW_CAP + 1, wide.rows.size)
        assertTrue(wide.truncated)
        assertEquals(8, wide.columns)

        val scores = parseDelimited(text("scores.tsv"), detectDelimiter(text("scores.tsv"), "tsv"))
        assertEquals(listOf("Grace", "12"), scores.rows[2])
    }

    @Test
    fun `the json fixtures parse and the broken one says where`() {
        val settings = parseJsonText(text("settings.json"), false) as JsonParse.Valid
        assertTrue(prettyJson(settings).lines().size > 8)
        val broken = parseJsonText(text("broken.json"), false) as JsonParse.Invalid
        assertEquals(3, broken.line)
        val events = parseJsonText(text("events.jsonl"), true) as JsonParse.Valid
        assertTrue(events.lineDelimited)
        assertEquals(40, flattenJson(events.root, setOf("$")).size - 1)
        assertTrue(parseJsonText(text("places.geojson"), false) is JsonParse.Valid)
    }

    @Test
    fun `the notebook fixture has every kind of cell and a decodable picture`() {
        val doc = (parseNotebook(text("notebook.ipynb")) as NotebookParse.Valid).doc
        assertEquals(
            listOf(NotebookCellKind.Markdown, NotebookCellKind.Code, NotebookCellKind.Code, NotebookCellKind.Code, NotebookCellKind.Raw),
            doc.cells.map { it.kind },
        )
        assertEquals("py", doc.languageKey)
        assertTrue((doc.cells[2].outputs.single() as NotebookOutput.Text).error)
        val picture = doc.cells[3].outputs.single() as NotebookOutput.Image
        val decoded = Base64.getDecoder().decode(picture.base64)
        assertEquals("png", sniffFileType(decoded)!!.extension)
    }

    @Test
    fun `the patch fixture colours its added and removed lines`() {
        val kinds = classifyDiffLines(text("change.patch").lines())
        assertEquals(2 to 1, diffTotals(kinds))
        assertEquals(DiffLineKind.Hunk, kinds[kinds.indexOfFirst { it == DiffLineKind.Hunk }])
        assertTrue(kinds.contains(DiffLineKind.FileHeader))
        assertTrue(kinds.contains(DiffLineKind.Meta))
    }

    @Test
    fun `the svg fixture keeps its shapes and its script for the locked web view to ignore`() {
        val page = svgPage(text("logo.svg"))
        assertTrue(page.contains("<circle"))
        assertFalse(page.contains("<?xml"))
    }

    @Test
    fun `source fixtures get colour on their first meaningful line`() {
        assertTrue(tokenizeLine("FROM node:20-alpine", "dockerfile").isNotEmpty())
        assertTrue(tokenizeLine("    branches: [main] # only main", "yaml").any { it.kind == TokenKind.Comment })
        assertTrue(tokenizeLine("SELECT id FROM users", "sql").any { it.kind == TokenKind.Keyword })
        assertTrue(tokenizeLine("port = 8080", "ini").any { it.kind == TokenKind.Key })
    }

    @Test
    fun `the binary fixture dumps to rows and the long text pages into lines`() {
        val rows = hexDump(bytes("blob.bin"), HEAD_BYTES, 16, bytes("blob.bin").size.toLong())
        assertEquals(375, rows.size)
        assertEquals(3000, buildDisplayLines(text("long.txt")).size)
    }

    @Test
    fun `the zip fixture lists its entries from a real file`() {
        val file = File.createTempFile("fixture", ".zip")
        try {
            file.writeBytes(bytes("bundle.zip"))
            val listing = listZipEntries(file)!!
            assertEquals(listOf("docs/", "docs/readme.txt", "src/main.kt", "assets/data.bin"), listing.entries.map { it.name })
            assertEquals(23L, listing.entries[1].size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a refusal that names a size limit is read as too large`() {
        assertTrue(looksTooLarge("workspace-file/too-large", null))
        assertTrue(looksTooLarge("workspace-file/read-failed", "File too large to read"))
        assertTrue(looksTooLarge("x", "size exceeds the limit"))
        assertFalse(looksTooLarge("workspace-file/not-found", "no entry at \"a\""))
        assertFalse(looksTooLarge("gateway/rate-limited", "slow down"))
    }
}
