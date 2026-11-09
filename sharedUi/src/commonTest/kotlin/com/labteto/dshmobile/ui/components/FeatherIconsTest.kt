package com.labteto.dshmobile.ui.components

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import com.labteto.dshmobile.testing.assertEquals
import com.labteto.dshmobile.testing.assertTrue
import kotlin.test.Test

/**
 * The glyphs are hand-transcribed path data, and a mistyped builder fails quietly — an empty path
 * renders as a blank 16dp hole in a tool row rather than as an error. This checks each one actually
 * produced geometry on the grid the rest of the set uses.
 */
class FeatherIconsTest {

    private val glyphs: Map<String, ImageVector> = mapOf(
        "Terminal" to FeatherIcons.Terminal,
        "FileText" to FeatherIcons.FileText,
        "FilePlus" to FeatherIcons.FilePlus,
        "Edit3" to FeatherIcons.Edit3,
        "Search" to FeatherIcons.Search,
        "Globe" to FeatherIcons.Globe,
        "Tool" to FeatherIcons.Tool,
        "Code" to FeatherIcons.Code,
        "GitBranch" to FeatherIcons.GitBranch,
        "CheckSquare" to FeatherIcons.CheckSquare,
        "Archive" to FeatherIcons.Archive,
        "AlertTriangle" to FeatherIcons.AlertTriangle,
        "Info" to FeatherIcons.Info,
        "Menu" to FeatherIcons.Menu,
        "ChevronRight" to FeatherIcons.ChevronRight,
    )

    @Test
    fun `every glyph draws something on the 24-unit grid`() {
        glyphs.forEach { (name, vector) ->
            assertEquals("$name viewport width", 24f, vector.viewportWidth, 0f)
            assertEquals("$name viewport height", 24f, vector.viewportHeight, 0f)
            assertEquals("$name path count", 1, vector.root.size)
            val path = vector.root.first() as VectorPath
            assertTrue("$name has no path nodes", path.pathData.isNotEmpty())
        }
    }

    @Test
    fun `every glyph is stroked in Feather's own weight and never filled`() {
        glyphs.forEach { (name, vector) ->
            val path = vector.root.first() as VectorPath
            assertEquals("$name stroke width", 2f, path.strokeLineWidth, 0f)
            assertEquals("$name stroke cap", StrokeCap.Round, path.strokeLineCap)
            assertEquals("$name stroke join", StrokeJoin.Round, path.strokeLineJoin)
            assertEquals("$name should have no fill", null, path.fill)
        }
    }

    @Test
    fun `the set is memoized so a scrolling transcript rebuilds nothing`() {
        assertTrue(FeatherIcons.Terminal === FeatherIcons.Terminal)
    }
}
