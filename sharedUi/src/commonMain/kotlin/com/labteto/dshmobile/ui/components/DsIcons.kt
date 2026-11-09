package com.labteto.dshmobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Material icons the shared screens draw, written out as vectors.
 *
 * Why not `Icons.Filled.Settings` and the rest: the Android app already carries
 * `androidx.compose.material:material-icons-extended`, and Compose Multiplatform's own icon artifact
 * has the same classes under the same names, so having both on the Android classpath is a duplicate
 * class build failure. (It also would put the whole extended set, thousands of icons, into the iOS
 * framework for the sake of ten.) Each path below is copied from the androidx 1.7.6 source of the
 * icon named in its comment, which is the version the Android app resolves, so the drawn pixels are
 * the ones the app has always drawn.
 *
 * Add an icon here as the screen that needs it moves; keep the comment naming its Material origin.
 */
object DsIcons {
    /** `Icons.AutoMirrored.Filled.KeyboardArrowRight`. */
    val ChevronRight: ImageVector by lazy {
        icon("AutoMirrored.Filled.KeyboardArrowRight", autoMirror = true) {
            moveTo(8.59f, 16.59f)
            lineTo(13.17f, 12.0f)
            lineTo(8.59f, 7.41f)
            lineTo(10.0f, 6.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(-1.41f, -1.41f)
            close()
        }
    }

    /** `Icons.Filled.ExpandMore`. */
    val ExpandMore: ImageVector by lazy {
        icon("Filled.ExpandMore") {
            moveTo(16.59f, 8.59f)
            lineTo(12.0f, 13.17f)
            lineTo(7.41f, 8.59f)
            lineTo(6.0f, 10.0f)
            lineToRelative(6.0f, 6.0f)
            lineToRelative(6.0f, -6.0f)
            close()
        }
    }

    /** `Icons.Filled.Settings`. */
    val Settings: ImageVector by lazy {
        icon("Filled.Settings") {
            moveTo(19.14f, 12.94f)
            curveToRelative(0.04f, -0.3f, 0.06f, -0.61f, 0.06f, -0.94f)
            curveToRelative(0.0f, -0.32f, -0.02f, -0.64f, -0.07f, -0.94f)
            lineToRelative(2.03f, -1.58f)
            curveToRelative(0.18f, -0.14f, 0.23f, -0.41f, 0.12f, -0.61f)
            lineToRelative(-1.92f, -3.32f)
            curveToRelative(-0.12f, -0.22f, -0.37f, -0.29f, -0.59f, -0.22f)
            lineToRelative(-2.39f, 0.96f)
            curveToRelative(-0.5f, -0.38f, -1.03f, -0.7f, -1.62f, -0.94f)
            lineTo(14.4f, 2.81f)
            curveToRelative(-0.04f, -0.24f, -0.24f, -0.41f, -0.48f, -0.41f)
            horizontalLineToRelative(-3.84f)
            curveToRelative(-0.24f, 0.0f, -0.43f, 0.17f, -0.47f, 0.41f)
            lineTo(9.25f, 5.35f)
            curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f)
            lineTo(5.24f, 5.33f)
            curveToRelative(-0.22f, -0.08f, -0.47f, 0.0f, -0.59f, 0.22f)
            lineTo(2.74f, 8.87f)
            curveTo(2.62f, 9.08f, 2.66f, 9.34f, 2.86f, 9.48f)
            lineToRelative(2.03f, 1.58f)
            curveTo(4.84f, 11.36f, 4.8f, 11.69f, 4.8f, 12.0f)
            reflectiveCurveToRelative(0.02f, 0.64f, 0.07f, 0.94f)
            lineToRelative(-2.03f, 1.58f)
            curveToRelative(-0.18f, 0.14f, -0.23f, 0.41f, -0.12f, 0.61f)
            lineToRelative(1.92f, 3.32f)
            curveToRelative(0.12f, 0.22f, 0.37f, 0.29f, 0.59f, 0.22f)
            lineToRelative(2.39f, -0.96f)
            curveToRelative(0.5f, 0.38f, 1.03f, 0.7f, 1.62f, 0.94f)
            lineToRelative(0.36f, 2.54f)
            curveToRelative(0.05f, 0.24f, 0.24f, 0.41f, 0.48f, 0.41f)
            horizontalLineToRelative(3.84f)
            curveToRelative(0.24f, 0.0f, 0.44f, -0.17f, 0.47f, -0.41f)
            lineToRelative(0.36f, -2.54f)
            curveToRelative(0.59f, -0.24f, 1.13f, -0.56f, 1.62f, -0.94f)
            lineToRelative(2.39f, 0.96f)
            curveToRelative(0.22f, 0.08f, 0.47f, 0.0f, 0.59f, -0.22f)
            lineToRelative(1.92f, -3.32f)
            curveToRelative(0.12f, -0.22f, 0.07f, -0.47f, -0.12f, -0.61f)
            lineTo(19.14f, 12.94f)
            close()
            moveTo(12.0f, 15.6f)
            curveToRelative(-1.98f, 0.0f, -3.6f, -1.62f, -3.6f, -3.6f)
            reflectiveCurveToRelative(1.62f, -3.6f, 3.6f, -3.6f)
            reflectiveCurveToRelative(3.6f, 1.62f, 3.6f, 3.6f)
            reflectiveCurveTo(13.98f, 15.6f, 12.0f, 15.6f)
            close()
        }
    }

    /** `Icons.Filled.Warning`. */
    val Warning: ImageVector by lazy {
        icon("Filled.Warning") {
            moveTo(1.0f, 21.0f)
            horizontalLineToRelative(22.0f)
            lineTo(12.0f, 2.0f)
            lineTo(1.0f, 21.0f)
            close()
            moveTo(13.0f, 18.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            close()
            moveTo(13.0f, 14.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-4.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(4.0f)
            close()
        }
    }

    /** `Icons.AutoMirrored.Filled.ArrowBack`. */
    val ArrowBack: ImageVector by lazy {
        icon("AutoMirrored.Filled.ArrowBack", autoMirror = true) {
            moveTo(20.0f, 11.0f)
            horizontalLineTo(7.83f)
            lineToRelative(5.59f, -5.59f)
            lineTo(12.0f, 4.0f)
            lineToRelative(-8.0f, 8.0f)
            lineToRelative(8.0f, 8.0f)
            lineToRelative(1.41f, -1.41f)
            lineTo(7.83f, 13.0f)
            horizontalLineTo(20.0f)
            verticalLineToRelative(-2.0f)
            close()
        }
    }

    /** `Icons.Filled.Check`. */
    val Check: ImageVector by lazy {
        icon("Filled.Check") {
            moveTo(9.0f, 16.17f)
            lineTo(4.83f, 12.0f)
            lineToRelative(-1.42f, 1.41f)
            lineTo(9.0f, 19.0f)
            lineTo(21.0f, 7.0f)
            lineToRelative(-1.41f, -1.41f)
            close()
        }
    }

    /** `Icons.Outlined.Delete`. */
    val Delete: ImageVector by lazy {
        icon("Outlined.Delete") {
            moveTo(16.0f, 9.0f)
            verticalLineToRelative(10.0f)
            horizontalLineTo(8.0f)
            verticalLineTo(9.0f)
            horizontalLineToRelative(8.0f)
            moveToRelative(-1.5f, -6.0f)
            horizontalLineToRelative(-5.0f)
            lineToRelative(-1.0f, 1.0f)
            horizontalLineTo(5.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(14.0f)
            verticalLineTo(4.0f)
            horizontalLineToRelative(-3.5f)
            lineToRelative(-1.0f, -1.0f)
            close()
            moveTo(18.0f, 7.0f)
            horizontalLineTo(6.0f)
            verticalLineToRelative(12.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(8.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(7.0f)
            close()
        }
    }

    /** `Icons.Outlined.MoreVert`. */
    val MoreVert: ImageVector by lazy {
        icon("Outlined.MoreVert") {
            moveTo(12.0f, 8.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            reflectiveCurveToRelative(-2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            close()
            moveTo(12.0f, 10.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(12.0f, 16.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
            reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
            reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
            close()
        }
    }

    /** `Icons.Outlined.QrCodeScanner`. */
    val QrCodeScanner: ImageVector by lazy {
        icon("Outlined.QrCodeScanner") {
            moveTo(9.5f, 6.5f)
            verticalLineToRelative(3.0f)
            horizontalLineToRelative(-3.0f)
            verticalLineToRelative(-3.0f)
            horizontalLineTo(9.5f)
            moveTo(11.0f, 5.0f)
            horizontalLineTo(5.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(6.0f)
            verticalLineTo(5.0f)
            lineTo(11.0f, 5.0f)
            close()
            moveTo(9.5f, 14.5f)
            verticalLineToRelative(3.0f)
            horizontalLineToRelative(-3.0f)
            verticalLineToRelative(-3.0f)
            horizontalLineTo(9.5f)
            moveTo(11.0f, 13.0f)
            horizontalLineTo(5.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(6.0f)
            verticalLineTo(13.0f)
            lineTo(11.0f, 13.0f)
            close()
            moveTo(17.5f, 6.5f)
            verticalLineToRelative(3.0f)
            horizontalLineToRelative(-3.0f)
            verticalLineToRelative(-3.0f)
            horizontalLineTo(17.5f)
            moveTo(19.0f, 5.0f)
            horizontalLineToRelative(-6.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(6.0f)
            verticalLineTo(5.0f)
            lineTo(19.0f, 5.0f)
            close()
            moveTo(13.0f, 13.0f)
            horizontalLineToRelative(1.5f)
            verticalLineToRelative(1.5f)
            horizontalLineTo(13.0f)
            verticalLineTo(13.0f)
            close()
            moveTo(14.5f, 14.5f)
            horizontalLineTo(16.0f)
            verticalLineTo(16.0f)
            horizontalLineToRelative(-1.5f)
            verticalLineTo(14.5f)
            close()
            moveTo(16.0f, 13.0f)
            horizontalLineToRelative(1.5f)
            verticalLineToRelative(1.5f)
            horizontalLineTo(16.0f)
            verticalLineTo(13.0f)
            close()
            moveTo(13.0f, 16.0f)
            horizontalLineToRelative(1.5f)
            verticalLineToRelative(1.5f)
            horizontalLineTo(13.0f)
            verticalLineTo(16.0f)
            close()
            moveTo(14.5f, 17.5f)
            horizontalLineTo(16.0f)
            verticalLineTo(19.0f)
            horizontalLineToRelative(-1.5f)
            verticalLineTo(17.5f)
            close()
            moveTo(16.0f, 16.0f)
            horizontalLineToRelative(1.5f)
            verticalLineToRelative(1.5f)
            horizontalLineTo(16.0f)
            verticalLineTo(16.0f)
            close()
            moveTo(17.5f, 14.5f)
            horizontalLineTo(19.0f)
            verticalLineTo(16.0f)
            horizontalLineToRelative(-1.5f)
            verticalLineTo(14.5f)
            close()
            moveTo(17.5f, 17.5f)
            horizontalLineTo(19.0f)
            verticalLineTo(19.0f)
            horizontalLineToRelative(-1.5f)
            verticalLineTo(17.5f)
            close()
            moveTo(22.0f, 7.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineTo(4.0f)
            horizontalLineToRelative(-3.0f)
            verticalLineTo(2.0f)
            horizontalLineToRelative(5.0f)
            verticalLineTo(7.0f)
            close()
            moveTo(22.0f, 22.0f)
            verticalLineToRelative(-5.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(3.0f)
            horizontalLineToRelative(-3.0f)
            verticalLineToRelative(2.0f)
            horizontalLineTo(22.0f)
            close()
            moveTo(2.0f, 22.0f)
            horizontalLineToRelative(5.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineTo(4.0f)
            verticalLineToRelative(-3.0f)
            horizontalLineTo(2.0f)
            verticalLineTo(22.0f)
            close()
            moveTo(2.0f, 2.0f)
            verticalLineToRelative(5.0f)
            horizontalLineToRelative(2.0f)
            verticalLineTo(4.0f)
            horizontalLineToRelative(3.0f)
            verticalLineTo(2.0f)
            horizontalLineTo(2.0f)
            close()
        }
    }

    /** `Icons.Outlined.Shield`. */
    val Shield: ImageVector by lazy {
        icon("Outlined.Shield") {
            moveTo(12.0f, 2.0f)
            lineTo(4.0f, 5.0f)
            verticalLineToRelative(6.09f)
            curveToRelative(0.0f, 5.05f, 3.41f, 9.76f, 8.0f, 10.91f)
            curveToRelative(4.59f, -1.15f, 8.0f, -5.86f, 8.0f, -10.91f)
            verticalLineTo(5.0f)
            lineTo(12.0f, 2.0f)
            close()
            moveTo(18.0f, 11.09f)
            curveToRelative(0.0f, 4.0f, -2.55f, 7.7f, -6.0f, 8.83f)
            curveToRelative(-3.45f, -1.13f, -6.0f, -4.82f, -6.0f, -8.83f)
            verticalLineToRelative(-4.7f)
            lineToRelative(6.0f, -2.25f)
            lineToRelative(6.0f, 2.25f)
            verticalLineTo(11.09f)
            close()
        }
    }

    /** `Icons.Outlined.ContentPaste`. */
    val ContentPaste: ImageVector by lazy {
        icon("Outlined.ContentPaste") {
            moveTo(19.0f, 2.0f)
            horizontalLineToRelative(-4.18f)
            curveTo(14.4f, 0.84f, 13.3f, 0.0f, 12.0f, 0.0f)
            reflectiveCurveTo(9.6f, 0.84f, 9.18f, 2.0f)
            lineTo(5.0f, 2.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(16.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(21.0f, 4.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(12.0f, 2.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
            reflectiveCurveToRelative(-0.45f, 1.0f, -1.0f, 1.0f)
            reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
            reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
            close()
            moveTo(19.0f, 20.0f)
            lineTo(5.0f, 20.0f)
            lineTo(5.0f, 4.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(3.0f)
            horizontalLineToRelative(10.0f)
            lineTo(17.0f, 4.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(16.0f)
            close()
        }
    }
}

/** A 24dp Material icon: one black path, which `Icon(tint = …)` repaints. */
private fun icon(name: String, autoMirror: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).path(fill = SolidColor(Color.Black), pathBuilder = block).build()
