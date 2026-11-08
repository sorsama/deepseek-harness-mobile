package com.labteto.dshmobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Feather glyphs the transcript and chrome use, traced as Compose vectors.
 *
 * Feather (https://feathericons.com, MIT © Cole Bemis) — see `THIRD_PARTY_NOTICES.md`. The set is
 * inlined rather than pulled in as a dependency for the same reason the harness ships its own
 * `ic_ds_*` icons inline: fifteen glyphs is not worth a library, and the ones that matter here have
 * to sit on the same 24-unit grid with the same 2-unit round-capped stroke as the desktop UI they
 * mirror. Material's own icons are a different drawing language (filled, 20-unit optical sizing) and
 * mixing the two in one row reads as a mistake.
 *
 * Every glyph is stroke-only and drawn in black, so `Icon`'s tint colours it.
 */
object FeatherIcons {

    val Folder: ImageVector by lazy {
        feather("Folder") {
            moveTo(22f, 19f); curveTo(22f, 20.1f, 21.1f, 21f, 20f, 21f)
            lineTo(4f, 21f); curveTo(2.9f, 21f, 2f, 20.1f, 2f, 19f)
            lineTo(2f, 5f); curveTo(2f, 3.9f, 2.9f, 3f, 4f, 3f)
            lineTo(9f, 3f); lineTo(11f, 6f); lineTo(20f, 6f)
            curveTo(21.1f, 6f, 22f, 6.9f, 22f, 8f); close()
        }
    }

    val MessageSquare: ImageVector by lazy {
        feather("MessageSquare") {
            moveTo(21f, 15f); curveTo(21f, 16.1f, 20.1f, 17f, 19f, 17f)
            lineTo(7f, 17f); lineTo(3f, 21f); lineTo(3f, 5f)
            curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f); lineTo(19f, 3f)
            curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f); close()
        }
    }

    /** `terminal` — the shell tools (bash, pwsh). */
    val Terminal: ImageVector by lazy {
        feather("Terminal") {
            moveTo(4f, 17f); lineTo(10f, 11f); lineTo(4f, 5f)
            moveTo(12f, 19f); lineTo(20f, 19f)
        }
    }

    /** `file-text` — reading a file. */
    val FileText: ImageVector by lazy {
        feather("FileText") {
            documentOutline()
            moveTo(16f, 13f); lineTo(8f, 13f)
            moveTo(16f, 17f); lineTo(8f, 17f)
            moveTo(10f, 9f); lineTo(8f, 9f)
        }
    }

    /** `file-plus` — creating a file. */
    val FilePlus: ImageVector by lazy {
        feather("FilePlus") {
            documentOutline()
            moveTo(12f, 18f); lineTo(12f, 12f)
            moveTo(9f, 15f); lineTo(15f, 15f)
        }
    }

    /** `edit-3` — editing a file. */
    val Edit3: ImageVector by lazy {
        feather("Edit3") {
            moveTo(12f, 20f); horizontalLineToRelative(9f)
            moveTo(16.5f, 3.5f)
            arcToRelative(2.121f, 2.121f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 3f)
            lineTo(7f, 19f)
            lineToRelative(-4f, 1f)
            lineToRelative(1f, -4f)
            lineTo(16.5f, 3.5f)
            close()
        }
    }

    /** `search` — grep, glob, web search. */
    val Search: ImageVector by lazy {
        feather("Search") {
            circle(11f, 11f, 8f)
            moveTo(21f, 21f); lineTo(16.65f, 16.65f)
        }
    }

    /** `globe` — web fetch / retrieval cards. */
    val Globe: ImageVector by lazy {
        feather("Globe") {
            circle(12f, 12f, 10f)
            moveTo(2f, 12f); lineTo(22f, 12f)
            moveTo(12f, 2f)
            arcToRelative(15.3f, 15.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4f, 10f)
            arcToRelative(15.3f, 15.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -4f, 10f)
            arcToRelative(15.3f, 15.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -4f, -10f)
            arcToRelative(15.3f, 15.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4f, -10f)
            close()
        }
    }

    /** `tool` — an unclassified tool call. */
    val Tool: ImageVector by lazy {
        feather("Tool") {
            moveTo(14.7f, 6.3f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = false, 0f, 1.4f)
            lineToRelative(1.6f, 1.6f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = false, 1.4f, 0f)
            lineToRelative(3.77f, -3.77f)
            arcToRelative(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = true, -7.94f, 7.94f)
            lineToRelative(-6.91f, 6.91f)
            arcToRelative(2.12f, 2.12f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3f, -3f)
            lineToRelative(6.91f, -6.91f)
            arcToRelative(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7.94f, -7.94f)
            lineToRelative(-3.76f, 3.76f)
            close()
        }
    }

    /** `code` — `run_code` and diff cards. */
    val Code: ImageVector by lazy {
        feather("Code") {
            moveTo(16f, 18f); lineTo(22f, 12f); lineTo(16f, 6f)
            moveTo(8f, 6f); lineTo(2f, 12f); lineTo(8f, 18f)
        }
    }

    /** `git-branch` — workflow rows. */
    val GitBranch: ImageVector by lazy {
        feather("GitBranch") {
            moveTo(6f, 3f); lineTo(6f, 15f)
            circle(18f, 6f, 3f)
            circle(6f, 18f, 3f)
            moveTo(18f, 9f)
            arcToRelative(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, -9f, 9f)
        }
    }

    /** `check-square` — todo docks. */
    val CheckSquare: ImageVector by lazy {
        feather("CheckSquare") {
            moveTo(9f, 11f); lineTo(12f, 14f); lineTo(22f, 4f)
            moveTo(21f, 12f)
            verticalLineToRelative(7f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
            verticalLineTo(5f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
            horizontalLineToRelative(11f)
        }
    }

    /** `archive` — compaction rows. */
    val Archive: ImageVector by lazy {
        feather("Archive") {
            moveTo(21f, 8f); lineTo(21f, 21f); lineTo(3f, 21f); lineTo(3f, 8f)
            rectangle(1f, 3f, 22f, 5f)
            moveTo(10f, 12f); lineTo(14f, 12f)
        }
    }

    /** `alert-triangle` — warnings and connection banners. */
    val AlertTriangle: ImageVector by lazy {
        feather("AlertTriangle") {
            moveTo(10.29f, 3.86f)
            lineTo(1.82f, 18f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 1.71f, 3f)
            horizontalLineToRelative(16.94f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 1.71f, -3f)
            lineTo(13.71f, 3.86f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, -3.42f, 0f)
            close()
            moveTo(12f, 9f); lineTo(12f, 13f)
            moveTo(12f, 17f); lineTo(12.01f, 17f)
        }
    }

    /** `info` — the details-panel button. Outline, unlike Material's filled disc. */
    val Info: ImageVector by lazy {
        feather("Info") {
            circle(12f, 12f, 10f)
            moveTo(12f, 16f); lineTo(12f, 12f)
            moveTo(12f, 8f); lineTo(12.01f, 8f)
        }
    }

    /** `menu` — the drawer button. */
    val Menu: ImageVector by lazy {
        feather("Menu") {
            moveTo(3f, 6f); lineTo(21f, 6f)
            moveTo(3f, 12f); lineTo(21f, 12f)
            moveTo(3f, 18f); lineTo(21f, 18f)
        }
    }

    /** `at-sign` — insert a structured draft reference. */
    val AtSign: ImageVector by lazy {
        feather("AtSign") {
            circle(12f, 12f, 4f)
            moveTo(16f, 8f); verticalLineToRelative(5f)
            arcToRelative(3f, 3f, 0f, false, false, 6f, 0f)
            verticalLineToRelative(-1f)
            arcToRelative(10f, 10f, 0f, true, false, -4f, 8f)
        }
    }

    /** `chevron-right` — disclosure affordance; rotates to 90° when open. */
    val ChevronRight: ImageVector by lazy {
        feather("ChevronRight") {
            moveTo(9f, 18f); lineTo(15f, 12f); lineTo(9f, 6f)
        }
    }

    /**
     * A six-dot grip, the drag handle. Not a Feather glyph (the set has none): six round-capped points
     * laid out on the same 24-unit grid, each a zero-length stroke that the round cap turns into a dot.
     */
    val GripVertical: ImageVector by lazy {
        feather("GripVertical") {
            moveTo(9f, 5f); lineTo(9.01f, 5f)
            moveTo(9f, 12f); lineTo(9.01f, 12f)
            moveTo(9f, 19f); lineTo(9.01f, 19f)
            moveTo(15f, 5f); lineTo(15.01f, 5f)
            moveTo(15f, 12f); lineTo(15.01f, 12f)
            moveTo(15f, 19f); lineTo(15.01f, 19f)
        }
    }

    /** feather:layers */
    val Layers: ImageVector by lazy {
        feather("Layers") {
            moveTo(12f, 2f); lineTo(2f, 7f); lineTo(12f, 12f); lineTo(22f, 7f); close()
            moveTo(2f, 17f); lineTo(12f, 22f); lineTo(22f, 17f)
            moveTo(2f, 12f); lineTo(12f, 17f); lineTo(22f, 12f)
        }
    }

    /** feather:server */
    val Server: ImageVector by lazy {
        feather("Server") {
            moveTo(4f, 2f); lineTo(20f, 2f); curveTo(21.1f, 2f, 22f, 2.9f, 22f, 4f); lineTo(22f, 8f)
            curveTo(22f, 9.1f, 21.1f, 10f, 20f, 10f); lineTo(4f, 10f); curveTo(2.9f, 10f, 2f, 9.1f, 2f, 8f)
            lineTo(2f, 4f); curveTo(2f, 2.9f, 2.9f, 2f, 4f, 2f); close()
            moveTo(4f, 14f); lineTo(20f, 14f); curveTo(21.1f, 14f, 22f, 14.9f, 22f, 16f); lineTo(22f, 20f)
            curveTo(22f, 21.1f, 21.1f, 22f, 20f, 22f); lineTo(4f, 22f); curveTo(2.9f, 22f, 2f, 21.1f, 2f, 20f)
            lineTo(2f, 16f); curveTo(2f, 14.9f, 2.9f, 14f, 4f, 14f); close()
            moveTo(6f, 6f); lineTo(6.01f, 6f)
            moveTo(6f, 18f); lineTo(6.01f, 18f)
        }
    }

    /** feather:zap */
    val Zap: ImageVector by lazy {
        feather("Zap") {
            moveTo(13f, 2f); lineTo(3f, 14f); lineTo(12f, 14f); lineTo(11f, 22f); lineTo(21f, 10f); lineTo(12f, 10f); close()
        }
    }

    /** feather:target */
    val Target: ImageVector by lazy {
        feather("Target") {
            moveTo(22f, 12f); arcTo(10f, 10f, 0f, true, true, 2f, 12f); arcTo(10f, 10f, 0f, true, true, 22f, 12f); close()
            moveTo(18f, 12f); arcTo(6f, 6f, 0f, true, true, 6f, 12f); arcTo(6f, 6f, 0f, true, true, 18f, 12f); close()
            moveTo(14f, 12f); arcTo(2f, 2f, 0f, true, true, 10f, 12f); arcTo(2f, 2f, 0f, true, true, 14f, 12f); close()
        }
    }

    /** feather:compass */
    val Compass: ImageVector by lazy {
        feather("Compass") {
            moveTo(22f, 12f); arcTo(10f, 10f, 0f, true, true, 2f, 12f); arcTo(10f, 10f, 0f, true, true, 22f, 12f); close()
            moveTo(16.24f, 7.76f); lineTo(14.12f, 14.12f); lineTo(7.76f, 16.24f); lineTo(9.88f, 9.88f); close()
        }
    }

    /** feather:cpu */
    val Cpu: ImageVector by lazy {
        feather("Cpu") {
            moveTo(6f, 4f); lineTo(18f, 4f); curveTo(19.1f, 4f, 20f, 4.9f, 20f, 6f); lineTo(20f, 18f)
            curveTo(20f, 19.1f, 19.1f, 20f, 18f, 20f); lineTo(6f, 20f); curveTo(4.9f, 20f, 4f, 19.1f, 4f, 18f)
            lineTo(4f, 6f); curveTo(4f, 4.9f, 4.9f, 4f, 6f, 4f); close()
            moveTo(9f, 9f); lineTo(15f, 9f); lineTo(15f, 15f); lineTo(9f, 15f); close()
            moveTo(9f, 1f); lineTo(9f, 4f); moveTo(15f, 1f); lineTo(15f, 4f)
            moveTo(9f, 20f); lineTo(9f, 23f); moveTo(15f, 20f); lineTo(15f, 23f)
            moveTo(20f, 9f); lineTo(23f, 9f); moveTo(20f, 14f); lineTo(23f, 14f)
            moveTo(1f, 9f); lineTo(4f, 9f); moveTo(1f, 14f); lineTo(4f, 14f)
        }
    }

    /** feather:list */
    val List: ImageVector by lazy {
        feather("List") {
            moveTo(8f, 6f); lineTo(21f, 6f); moveTo(8f, 12f); lineTo(21f, 12f); moveTo(8f, 18f); lineTo(21f, 18f)
            moveTo(3f, 6f); lineTo(3.01f, 6f); moveTo(3f, 12f); lineTo(3.01f, 12f); moveTo(3f, 18f); lineTo(3.01f, 18f)
        }
    }

    /** feather:users */
    val Users: ImageVector by lazy {
        feather("Users") {
            moveTo(17f, 21f); lineTo(17f, 19f); curveTo(17f, 16.8f, 15.2f, 15f, 13f, 15f); lineTo(5f, 15f)
            curveTo(2.8f, 15f, 1f, 16.8f, 1f, 19f); lineTo(1f, 21f)
            moveTo(13f, 7f); arcTo(4f, 4f, 0f, true, true, 5f, 7f); arcTo(4f, 4f, 0f, true, true, 13f, 7f); close()
            moveTo(23f, 21f); lineTo(23f, 19f); curveTo(23f, 17.2f, 21.8f, 15.6f, 20f, 15.1f)
            moveTo(16f, 3.1f); curveTo(17.8f, 3.6f, 19f, 5.2f, 19f, 7f); curveTo(19f, 8.8f, 17.8f, 10.4f, 16f, 10.9f)
        }
    }

    /** feather:activity */
    val Activity: ImageVector by lazy {
        feather("Activity") {
            moveTo(22f, 12f); lineTo(18f, 12f); lineTo(15f, 21f); lineTo(9f, 3f); lineTo(6f, 12f); lineTo(2f, 12f)
        }
    }

    /** feather:map */
    val Map: ImageVector by lazy {
        feather("Map") {
            moveTo(1f, 6f); lineTo(1f, 22f); lineTo(8f, 18f); lineTo(16f, 22f); lineTo(23f, 18f); lineTo(23f, 2f)
            lineTo(16f, 6f); lineTo(8f, 2f); close()
            moveTo(8f, 2f); lineTo(8f, 18f); moveTo(16f, 6f); lineTo(16f, 22f)
        }
    }


    /** feather:refresh-cw */
    val RefreshCw: ImageVector by lazy {
        feather("RefreshCw") {
            moveTo(23f, 4f); lineTo(23f, 10f); lineTo(17f, 10f)
            moveTo(1f, 20f); lineTo(1f, 14f); lineTo(7f, 14f)
            moveTo(3.51f, 9f); arcTo(9f, 9f, 0f, false, true, 18.36f, 5.64f); lineTo(23f, 10f)
            moveTo(1f, 14f); lineTo(5.64f, 18.36f); arcTo(9f, 9f, 0f, false, false, 20.49f, 15f)
        }
    }

    /** feather:corner-left-up */
    val CornerLeftUp: ImageVector by lazy {
        feather("CornerLeftUp") {
            moveTo(14f, 9f); lineTo(9f, 4f); lineTo(4f, 9f)
            moveTo(20f, 20f); lineTo(13f, 20f); curveTo(10.8f, 20f, 9f, 18.2f, 9f, 16f); lineTo(9f, 4f)
        }
    }


    /** feather:external-link */
    val ExternalLink: ImageVector by lazy {
        feather("ExternalLink") {
            moveTo(18f, 13f); lineTo(18f, 19f); curveTo(18f, 20.1f, 17.1f, 21f, 16f, 21f); lineTo(5f, 21f)
            curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f); lineTo(3f, 8f); curveTo(3f, 6.9f, 3.9f, 6f, 5f, 6f); lineTo(11f, 6f)
            moveTo(15f, 3f); lineTo(21f, 3f); lineTo(21f, 9f)
            moveTo(10f, 14f); lineTo(21f, 3f)
        }
    }

    /** feather:image */
    val Image: ImageVector by lazy {
        feather("Image") {
            moveTo(5f, 3f); lineTo(19f, 3f); curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f); lineTo(21f, 19f)
            curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f); lineTo(5f, 21f); curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
            lineTo(3f, 5f); curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f); close()
            moveTo(10f, 8.5f); arcTo(1.5f, 1.5f, 0f, true, true, 7f, 8.5f); arcTo(1.5f, 1.5f, 0f, true, true, 10f, 8.5f); close()
            moveTo(21f, 15f); lineTo(16f, 10f); lineTo(5f, 21f)
        }
    }

}

// ---------------------------------------------------------------------------
// Builders
// ---------------------------------------------------------------------------

/** One Feather glyph: 24-unit grid, 2-unit round-capped stroke, no fill. */
private fun feather(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = "Feather.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

/** SVG's `<circle>`, as the two half-arcs an `M … a … a …` path would draw. */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = false, 2 * r, 0f)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = false, -2 * r, 0f)
}

/** SVG's `<rect>` without corner radii. */
private fun PathBuilder.rectangle(x: Float, y: Float, width: Float, height: Float) {
    moveTo(x, y)
    horizontalLineToRelative(width)
    verticalLineToRelative(height)
    horizontalLineToRelative(-width)
    close()
}

/** The dog-eared page both `file-text` and `file-plus` are drawn on. */
private fun PathBuilder.documentOutline() {
    moveTo(14f, 2f)
    horizontalLineTo(6f)
    arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, -2f, 2f)
    verticalLineToRelative(16f)
    arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 2f, 2f)
    horizontalLineToRelative(12f)
    arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 2f, -2f)
    verticalLineTo(8f)
    close()
    moveTo(14f, 2f); lineTo(14f, 8f); lineTo(20f, 8f)
}
