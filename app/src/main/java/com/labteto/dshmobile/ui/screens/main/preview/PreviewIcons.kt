package com.labteto.dshmobile.ui.screens.main.preview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The extra Feather glyphs the file viewer needs, drawn the same way as `FeatherIcons`: a 24-unit
 * grid, a 2-unit round-capped stroke, no fill, black so `Icon`'s tint colours them.
 *
 * Feather (https://feathericons.com, MIT © Cole Bemis), see `THIRD_PARTY_NOTICES.md`. They live
 * beside the viewer rather than in the shared set because nothing else uses them.
 */
internal object PreviewIcons {

    /** `download` */
    val Download: ImageVector by lazy {
        feather("Download") {
            moveTo(21f, 15f); verticalLineToRelative(4f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f); verticalLineToRelative(-4f)
            moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
            moveTo(12f, 15f); lineTo(12f, 3f)
        }
    }

    /** `maximize-2` */
    val Expand: ImageVector by lazy {
        feather("Expand") {
            moveTo(15f, 3f); lineTo(21f, 3f); lineTo(21f, 9f)
            moveTo(9f, 21f); lineTo(3f, 21f); lineTo(3f, 15f)
            moveTo(21f, 3f); lineTo(14f, 10f)
            moveTo(3f, 21f); lineTo(10f, 14f)
        }
    }

    /** `maximize`: corners pointing out. */
    val Fill: ImageVector by lazy {
        feather("Fill") {
            moveTo(8f, 3f); horizontalLineTo(5f); arcToRelative(2f, 2f, 0f, false, false, -2f, 2f); verticalLineToRelative(3f)
            moveTo(21f, 8f); verticalLineTo(5f); arcToRelative(2f, 2f, 0f, false, false, -2f, -2f); horizontalLineToRelative(-3f)
            moveTo(16f, 21f); horizontalLineToRelative(3f); arcToRelative(2f, 2f, 0f, false, false, 2f, -2f); verticalLineToRelative(-3f)
            moveTo(3f, 16f); verticalLineToRelative(3f); arcToRelative(2f, 2f, 0f, false, false, 2f, 2f); horizontalLineToRelative(3f)
        }
    }

    /** `minimize`: corners pointing in. */
    val Fit: ImageVector by lazy {
        feather("Fit") {
            moveTo(8f, 3f); verticalLineToRelative(3f); arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); horizontalLineTo(3f)
            moveTo(21f, 8f); horizontalLineToRelative(-3f); arcToRelative(2f, 2f, 0f, false, true, -2f, -2f); verticalLineTo(3f)
            moveTo(16f, 21f); verticalLineToRelative(-3f); arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); horizontalLineToRelative(3f)
            moveTo(3f, 16f); horizontalLineToRelative(3f); arcToRelative(2f, 2f, 0f, false, true, 2f, 2f); verticalLineToRelative(3f)
        }
    }

    /** `chevron-up` */
    val ChevronUp: ImageVector by lazy {
        feather("ChevronUp") { moveTo(18f, 15f); lineTo(12f, 9f); lineTo(6f, 15f) }
    }

    /** `chevron-down` */
    val ChevronDown: ImageVector by lazy {
        feather("ChevronDown") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    }

    /** `chevron-left` */
    val ChevronLeft: ImageVector by lazy {
        feather("ChevronLeft") { moveTo(15f, 18f); lineTo(9f, 12f); lineTo(15f, 6f) }
    }

    /** `play` */
    val Play: ImageVector by lazy {
        feather("Play") { moveTo(6f, 3f); lineTo(20f, 12f); lineTo(6f, 21f); close() }
    }

    /** `pause` */
    val Pause: ImageVector by lazy {
        feather("Pause") {
            rectangle(6f, 4f, 4f, 16f)
            rectangle(14f, 4f, 4f, 16f)
        }
    }

    /** `zoom-in` */
    val ZoomIn: ImageVector by lazy {
        feather("ZoomIn") {
            circle(11f, 11f, 8f)
            moveTo(21f, 21f); lineTo(16.65f, 16.65f)
            moveTo(11f, 8f); lineTo(11f, 14f)
            moveTo(8f, 11f); lineTo(14f, 11f)
        }
    }

    /** `zoom-out` */
    val ZoomOut: ImageVector by lazy {
        feather("ZoomOut") {
            circle(11f, 11f, 8f)
            moveTo(21f, 21f); lineTo(16.65f, 16.65f)
            moveTo(8f, 11f); lineTo(14f, 11f)
        }
    }

    /** `corner-down-left`: a line that wraps. */
    val Wrap: ImageVector by lazy {
        feather("Wrap") {
            moveTo(9f, 10f); lineTo(4f, 15f); lineTo(9f, 20f)
            moveTo(20f, 4f); verticalLineToRelative(7f); arcToRelative(4f, 4f, 0f, false, true, -4f, 4f); horizontalLineTo(4f)
        }
    }

    /** `hash`: line numbers. */
    val Hash: ImageVector by lazy {
        feather("Hash") {
            moveTo(4f, 9f); lineTo(20f, 9f)
            moveTo(4f, 15f); lineTo(20f, 15f)
            moveTo(10f, 3f); lineTo(8f, 21f)
            moveTo(16f, 3f); lineTo(14f, 21f)
        }
    }

    /** `film` */
    val Film: ImageVector by lazy {
        feather("Film") {
            rectangle(3f, 3f, 18f, 18f)
            moveTo(7f, 3f); lineTo(7f, 21f)
            moveTo(17f, 3f); lineTo(17f, 21f)
            moveTo(3f, 12f); lineTo(21f, 12f)
            moveTo(3f, 7.5f); lineTo(7f, 7.5f)
            moveTo(3f, 16.5f); lineTo(7f, 16.5f)
            moveTo(17f, 7.5f); lineTo(21f, 7.5f)
            moveTo(17f, 16.5f); lineTo(21f, 16.5f)
        }
    }

    /** `music` */
    val Music: ImageVector by lazy {
        feather("Music") {
            moveTo(9f, 18f); verticalLineTo(5f); lineToRelative(12f, -2f); verticalLineToRelative(13f)
            circle(6f, 18f, 3f)
            circle(18f, 16f, 3f)
        }
    }

    /** `grid`: a table. */
    val Grid: ImageVector by lazy {
        feather("Grid") {
            rectangle(3f, 3f, 7f, 7f)
            rectangle(14f, 3f, 7f, 7f)
            rectangle(14f, 14f, 7f, 7f)
            rectangle(3f, 14f, 7f, 7f)
        }
    }

    /** `type`: a font. */
    val Type: ImageVector by lazy {
        feather("Type") {
            moveTo(4f, 7f); lineTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 7f)
            moveTo(9f, 20f); lineTo(15f, 20f)
            moveTo(12f, 4f); lineTo(12f, 20f)
        }
    }

    /** `file`: a file the app cannot read. */
    val File: ImageVector by lazy {
        feather("File") {
            moveTo(13f, 2f); horizontalLineTo(6f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, 2f); verticalLineToRelative(16f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f); horizontalLineToRelative(12f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, -2f); verticalLineTo(9f); close()
            moveTo(13f, 2f); verticalLineToRelative(7f); horizontalLineToRelative(7f)
        }
    }

    /** `more-vertical` */
    val MoreVertical: ImageVector by lazy {
        feather("MoreVertical") {
            circle(12f, 12f, 1f)
            circle(12f, 5f, 1f)
            circle(12f, 19f, 1f)
        }
    }

    /** `chevron-right`, for tree rows (it is rotated when a row opens). */
    val ChevronRight: ImageVector by lazy {
        feather("ChevronRight") { moveTo(9f, 18f); lineTo(15f, 12f); lineTo(9f, 6f) }
    }
}

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

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = false, 2 * r, 0f)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = false, -2 * r, 0f)
}

private fun PathBuilder.rectangle(x: Float, y: Float, width: Float, height: Float) {
    moveTo(x, y)
    horizontalLineToRelative(width)
    verticalLineToRelative(height)
    horizontalLineToRelative(-width)
    close()
}
