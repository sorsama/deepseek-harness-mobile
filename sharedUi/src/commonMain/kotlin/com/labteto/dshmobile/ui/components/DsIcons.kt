package com.labteto.dshmobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Written by tools/icons/generate.mjs from the androidx 1.7.6 sources. Do not edit by hand: add the icon to
// the script's list and run it.

/**
 * The Material icons the shared screens draw, written out as vectors.
 *
 * Why not `Icons.Filled.Settings` and the rest: the Android app already carries
 * `androidx.compose.material:material-icons-extended`, and Compose Multiplatform's own icon artifact
 * has the same classes under the same names, so having both on the Android classpath is a duplicate
 * class build failure. (It also would put the whole extended set, thousands of icons, into the iOS
 * framework for the sake of a few.) Each path below is copied from the androidx 1.7.6 source of the
 * icon named in its comment, which is the version the Android app resolves, so the drawn pixels are
 * the ones the app has always drawn. `DsIconsMatchMaterialTest` in `:app` compares every one with the
 * real icon.
 *
 * Add an icon here as the screen that needs it moves, through `tools/icons/generate.mjs`.
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

    /** `Icons.Filled.Close`. */
    val Close: ImageVector by lazy {
        icon("Filled.Close") {
            moveTo(19.0f, 6.41f)
            lineTo(17.59f, 5.0f)
            lineTo(12.0f, 10.59f)
            lineTo(6.41f, 5.0f)
            lineTo(5.0f, 6.41f)
            lineTo(10.59f, 12.0f)
            lineTo(5.0f, 17.59f)
            lineTo(6.41f, 19.0f)
            lineTo(12.0f, 13.41f)
            lineTo(17.59f, 19.0f)
            lineTo(19.0f, 17.59f)
            lineTo(13.41f, 12.0f)
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

    /** `Icons.Filled.Add`. */
    val Add: ImageVector by lazy {
        icon("Filled.Add") {
            moveTo(19.0f, 13.0f)
            horizontalLineToRelative(-6.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-6.0f)
            horizontalLineTo(5.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(6.0f)
            verticalLineTo(5.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(6.0f)
            verticalLineToRelative(2.0f)
            close()
        }
    }

    /** `Icons.Outlined.Add`. */
    val AddOutlined: ImageVector by lazy {
        icon("Outlined.Add") {
            moveTo(19.0f, 13.0f)
            horizontalLineToRelative(-6.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-6.0f)
            horizontalLineTo(5.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(6.0f)
            verticalLineTo(5.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(6.0f)
            verticalLineToRelative(2.0f)
            close()
        }
    }

    /** `Icons.Filled.ArrowUpward`. */
    val ArrowUpward: ImageVector by lazy {
        icon("Filled.ArrowUpward") {
            moveTo(4.0f, 12.0f)
            lineToRelative(1.41f, 1.41f)
            lineTo(11.0f, 7.83f)
            verticalLineTo(20.0f)
            horizontalLineToRelative(2.0f)
            verticalLineTo(7.83f)
            lineToRelative(5.58f, 5.59f)
            lineTo(20.0f, 12.0f)
            lineToRelative(-8.0f, -8.0f)
            lineToRelative(-8.0f, 8.0f)
            close()
        }
    }

    /** `Icons.Filled.KeyboardArrowDown`. */
    val KeyboardArrowDown: ImageVector by lazy {
        icon("Filled.KeyboardArrowDown") {
            moveTo(7.41f, 8.59f)
            lineTo(12.0f, 13.17f)
            lineToRelative(4.59f, -4.58f)
            lineTo(18.0f, 10.0f)
            lineToRelative(-6.0f, 6.0f)
            lineToRelative(-6.0f, -6.0f)
            lineToRelative(1.41f, -1.41f)
            close()
        }
    }

    /** `Icons.Outlined.Archive`. */
    val Archive: ImageVector by lazy {
        icon("Outlined.Archive") {
            moveTo(20.54f, 5.23f)
            lineToRelative(-1.39f, -1.68f)
            curveTo(18.88f, 3.21f, 18.47f, 3.0f, 18.0f, 3.0f)
            lineTo(6.0f, 3.0f)
            curveToRelative(-0.47f, 0.0f, -0.88f, 0.21f, -1.16f, 0.55f)
            lineTo(3.46f, 5.23f)
            curveTo(3.17f, 5.57f, 3.0f, 6.02f, 3.0f, 6.5f)
            lineTo(3.0f, 19.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(14.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(21.0f, 6.5f)
            curveToRelative(0.0f, -0.48f, -0.17f, -0.93f, -0.46f, -1.27f)
            close()
            moveTo(6.24f, 5.0f)
            horizontalLineToRelative(11.52f)
            lineToRelative(0.81f, 0.97f)
            lineTo(5.44f, 5.97f)
            lineToRelative(0.8f, -0.97f)
            close()
            moveTo(5.0f, 19.0f)
            lineTo(5.0f, 8.0f)
            horizontalLineToRelative(14.0f)
            verticalLineToRelative(11.0f)
            lineTo(5.0f, 19.0f)
            close()
            moveTo(13.45f, 10.0f)
            horizontalLineToRelative(-2.9f)
            verticalLineToRelative(3.0f)
            lineTo(8.0f, 13.0f)
            lineToRelative(4.0f, 4.0f)
            lineToRelative(4.0f, -4.0f)
            horizontalLineToRelative(-2.55f)
            close()
        }
    }

    /** `Icons.AutoMirrored.Outlined.CallSplit`. */
    val CallSplit: ImageVector by lazy {
        icon("AutoMirrored.Outlined.CallSplit", autoMirror = true) {
            moveTo(14.0f, 4.0f)
            lineToRelative(2.29f, 2.29f)
            lineToRelative(-2.88f, 2.88f)
            lineToRelative(1.42f, 1.42f)
            lineToRelative(2.88f, -2.88f)
            lineTo(20.0f, 10.0f)
            lineTo(20.0f, 4.0f)
            horizontalLineToRelative(-6.0f)
            close()
            moveTo(10.0f, 4.0f)
            lineTo(4.0f, 4.0f)
            verticalLineToRelative(6.0f)
            lineToRelative(2.29f, -2.29f)
            lineToRelative(4.71f, 4.7f)
            lineTo(11.0f, 20.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-8.41f)
            lineToRelative(-5.29f, -5.3f)
            lineTo(10.0f, 4.0f)
            close()
        }
    }

    /** `Icons.Outlined.ContentCopy`. */
    val ContentCopy: ImageVector by lazy {
        icon("Outlined.ContentCopy") {
            moveTo(16.0f, 1.0f)
            lineTo(4.0f, 1.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            horizontalLineToRelative(2.0f)
            lineTo(4.0f, 3.0f)
            horizontalLineToRelative(12.0f)
            lineTo(16.0f, 1.0f)
            close()
            moveTo(19.0f, 5.0f)
            lineTo(8.0f, 5.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(14.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(11.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(21.0f, 7.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(19.0f, 21.0f)
            lineTo(8.0f, 21.0f)
            lineTo(8.0f, 7.0f)
            horizontalLineToRelative(11.0f)
            verticalLineToRelative(14.0f)
            close()
        }
    }

    /** `Icons.Outlined.Edit`. */
    val Edit: ImageVector by lazy {
        icon("Outlined.Edit") {
            moveTo(14.06f, 9.02f)
            lineToRelative(0.92f, 0.92f)
            lineTo(5.92f, 19.0f)
            lineTo(5.0f, 19.0f)
            verticalLineToRelative(-0.92f)
            lineToRelative(9.06f, -9.06f)
            moveTo(17.66f, 3.0f)
            curveToRelative(-0.25f, 0.0f, -0.51f, 0.1f, -0.7f, 0.29f)
            lineToRelative(-1.83f, 1.83f)
            lineToRelative(3.75f, 3.75f)
            lineToRelative(1.83f, -1.83f)
            curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
            lineToRelative(-2.34f, -2.34f)
            curveToRelative(-0.2f, -0.2f, -0.45f, -0.29f, -0.71f, -0.29f)
            close()
            moveTo(14.06f, 6.19f)
            lineTo(3.0f, 17.25f)
            lineTo(3.0f, 21.0f)
            horizontalLineToRelative(3.75f)
            lineTo(17.81f, 9.94f)
            lineToRelative(-3.75f, -3.75f)
            close()
        }
    }

    /** `Icons.Outlined.PushPin`. */
    val PushPin: ImageVector by lazy {
        icon("Outlined.PushPin") {
            moveTo(14.0f, 4.0f)
            verticalLineToRelative(5.0f)
            curveToRelative(0.0f, 1.12f, 0.37f, 2.16f, 1.0f, 3.0f)
            horizontalLineTo(9.0f)
            curveToRelative(0.65f, -0.86f, 1.0f, -1.9f, 1.0f, -3.0f)
            verticalLineTo(4.0f)
            horizontalLineTo(14.0f)
            moveTo(17.0f, 2.0f)
            horizontalLineTo(7.0f)
            curveTo(6.45f, 2.0f, 6.0f, 2.45f, 6.0f, 3.0f)
            curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
            curveToRelative(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
            lineToRelative(1.0f, 0.0f)
            verticalLineToRelative(5.0f)
            curveToRelative(0.0f, 1.66f, -1.34f, 3.0f, -3.0f, 3.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(5.97f)
            verticalLineToRelative(7.0f)
            lineToRelative(1.0f, 1.0f)
            lineToRelative(1.0f, -1.0f)
            verticalLineToRelative(-7.0f)
            horizontalLineTo(19.0f)
            verticalLineToRelative(-2.0f)
            curveToRelative(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
            curveToRelative(-1.66f, 0.0f, -3.0f, -1.34f, -3.0f, -3.0f)
            verticalLineTo(4.0f)
            lineToRelative(1.0f, 0.0f)
            curveToRelative(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
            curveTo(18.0f, 2.45f, 17.55f, 2.0f, 17.0f, 2.0f)
            lineTo(17.0f, 2.0f)
            close()
        }
    }

    /** `Icons.Outlined.Refresh`. */
    val Refresh: ImageVector by lazy {
        icon("Outlined.Refresh") {
            moveTo(17.65f, 6.35f)
            curveTo(16.2f, 4.9f, 14.21f, 4.0f, 12.0f, 4.0f)
            curveToRelative(-4.42f, 0.0f, -7.99f, 3.58f, -7.99f, 8.0f)
            reflectiveCurveToRelative(3.57f, 8.0f, 7.99f, 8.0f)
            curveToRelative(3.73f, 0.0f, 6.84f, -2.55f, 7.73f, -6.0f)
            horizontalLineToRelative(-2.08f)
            curveToRelative(-0.82f, 2.33f, -3.04f, 4.0f, -5.65f, 4.0f)
            curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
            reflectiveCurveToRelative(2.69f, -6.0f, 6.0f, -6.0f)
            curveToRelative(1.66f, 0.0f, 3.14f, 0.69f, 4.22f, 1.78f)
            lineTo(13.0f, 11.0f)
            horizontalLineToRelative(7.0f)
            verticalLineTo(4.0f)
            lineToRelative(-2.35f, 2.35f)
            close()
        }
    }

    /** `Icons.Outlined.Restore`. */
    val Restore: ImageVector by lazy {
        icon("Outlined.Restore") {
            moveTo(13.0f, 3.0f)
            curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
            lineTo(1.0f, 12.0f)
            lineToRelative(4.0f, 3.99f)
            lineTo(9.0f, 12.0f)
            lineTo(6.0f, 12.0f)
            curveToRelative(0.0f, -3.87f, 3.13f, -7.0f, 7.0f, -7.0f)
            reflectiveCurveToRelative(7.0f, 3.13f, 7.0f, 7.0f)
            reflectiveCurveToRelative(-3.13f, 7.0f, -7.0f, 7.0f)
            curveToRelative(-1.93f, 0.0f, -3.68f, -0.79f, -4.94f, -2.06f)
            lineToRelative(-1.42f, 1.42f)
            curveTo(8.27f, 19.99f, 10.51f, 21.0f, 13.0f, 21.0f)
            curveToRelative(4.97f, 0.0f, 9.0f, -4.03f, 9.0f, -9.0f)
            reflectiveCurveToRelative(-4.03f, -9.0f, -9.0f, -9.0f)
            close()
            moveTo(12.0f, 8.0f)
            verticalLineToRelative(5.0f)
            lineToRelative(4.25f, 2.52f)
            lineToRelative(0.77f, -1.28f)
            lineToRelative(-3.52f, -2.09f)
            lineTo(13.5f, 8.0f)
            close()
        }
    }

    /** `Icons.Outlined.Settings`. */
    val SettingsOutlined: ImageVector by lazy {
        icon("Outlined.Settings") {
            moveTo(19.43f, 12.98f)
            curveToRelative(0.04f, -0.32f, 0.07f, -0.64f, 0.07f, -0.98f)
            curveToRelative(0.0f, -0.34f, -0.03f, -0.66f, -0.07f, -0.98f)
            lineToRelative(2.11f, -1.65f)
            curveToRelative(0.19f, -0.15f, 0.24f, -0.42f, 0.12f, -0.64f)
            lineToRelative(-2.0f, -3.46f)
            curveToRelative(-0.09f, -0.16f, -0.26f, -0.25f, -0.44f, -0.25f)
            curveToRelative(-0.06f, 0.0f, -0.12f, 0.01f, -0.17f, 0.03f)
            lineToRelative(-2.49f, 1.0f)
            curveToRelative(-0.52f, -0.4f, -1.08f, -0.73f, -1.69f, -0.98f)
            lineToRelative(-0.38f, -2.65f)
            curveTo(14.46f, 2.18f, 14.25f, 2.0f, 14.0f, 2.0f)
            horizontalLineToRelative(-4.0f)
            curveToRelative(-0.25f, 0.0f, -0.46f, 0.18f, -0.49f, 0.42f)
            lineToRelative(-0.38f, 2.65f)
            curveToRelative(-0.61f, 0.25f, -1.17f, 0.59f, -1.69f, 0.98f)
            lineToRelative(-2.49f, -1.0f)
            curveToRelative(-0.06f, -0.02f, -0.12f, -0.03f, -0.18f, -0.03f)
            curveToRelative(-0.17f, 0.0f, -0.34f, 0.09f, -0.43f, 0.25f)
            lineToRelative(-2.0f, 3.46f)
            curveToRelative(-0.13f, 0.22f, -0.07f, 0.49f, 0.12f, 0.64f)
            lineToRelative(2.11f, 1.65f)
            curveToRelative(-0.04f, 0.32f, -0.07f, 0.65f, -0.07f, 0.98f)
            curveToRelative(0.0f, 0.33f, 0.03f, 0.66f, 0.07f, 0.98f)
            lineToRelative(-2.11f, 1.65f)
            curveToRelative(-0.19f, 0.15f, -0.24f, 0.42f, -0.12f, 0.64f)
            lineToRelative(2.0f, 3.46f)
            curveToRelative(0.09f, 0.16f, 0.26f, 0.25f, 0.44f, 0.25f)
            curveToRelative(0.06f, 0.0f, 0.12f, -0.01f, 0.17f, -0.03f)
            lineToRelative(2.49f, -1.0f)
            curveToRelative(0.52f, 0.4f, 1.08f, 0.73f, 1.69f, 0.98f)
            lineToRelative(0.38f, 2.65f)
            curveToRelative(0.03f, 0.24f, 0.24f, 0.42f, 0.49f, 0.42f)
            horizontalLineToRelative(4.0f)
            curveToRelative(0.25f, 0.0f, 0.46f, -0.18f, 0.49f, -0.42f)
            lineToRelative(0.38f, -2.65f)
            curveToRelative(0.61f, -0.25f, 1.17f, -0.59f, 1.69f, -0.98f)
            lineToRelative(2.49f, 1.0f)
            curveToRelative(0.06f, 0.02f, 0.12f, 0.03f, 0.18f, 0.03f)
            curveToRelative(0.17f, 0.0f, 0.34f, -0.09f, 0.43f, -0.25f)
            lineToRelative(2.0f, -3.46f)
            curveToRelative(0.12f, -0.22f, 0.07f, -0.49f, -0.12f, -0.64f)
            lineToRelative(-2.11f, -1.65f)
            close()
            moveTo(17.45f, 11.27f)
            curveToRelative(0.04f, 0.31f, 0.05f, 0.52f, 0.05f, 0.73f)
            curveToRelative(0.0f, 0.21f, -0.02f, 0.43f, -0.05f, 0.73f)
            lineToRelative(-0.14f, 1.13f)
            lineToRelative(0.89f, 0.7f)
            lineToRelative(1.08f, 0.84f)
            lineToRelative(-0.7f, 1.21f)
            lineToRelative(-1.27f, -0.51f)
            lineToRelative(-1.04f, -0.42f)
            lineToRelative(-0.9f, 0.68f)
            curveToRelative(-0.43f, 0.32f, -0.84f, 0.56f, -1.25f, 0.73f)
            lineToRelative(-1.06f, 0.43f)
            lineToRelative(-0.16f, 1.13f)
            lineToRelative(-0.2f, 1.35f)
            horizontalLineToRelative(-1.4f)
            lineToRelative(-0.19f, -1.35f)
            lineToRelative(-0.16f, -1.13f)
            lineToRelative(-1.06f, -0.43f)
            curveToRelative(-0.43f, -0.18f, -0.83f, -0.41f, -1.23f, -0.71f)
            lineToRelative(-0.91f, -0.7f)
            lineToRelative(-1.06f, 0.43f)
            lineToRelative(-1.27f, 0.51f)
            lineToRelative(-0.7f, -1.21f)
            lineToRelative(1.08f, -0.84f)
            lineToRelative(0.89f, -0.7f)
            lineToRelative(-0.14f, -1.13f)
            curveToRelative(-0.03f, -0.31f, -0.05f, -0.54f, -0.05f, -0.74f)
            reflectiveCurveToRelative(0.02f, -0.43f, 0.05f, -0.73f)
            lineToRelative(0.14f, -1.13f)
            lineToRelative(-0.89f, -0.7f)
            lineToRelative(-1.08f, -0.84f)
            lineToRelative(0.7f, -1.21f)
            lineToRelative(1.27f, 0.51f)
            lineToRelative(1.04f, 0.42f)
            lineToRelative(0.9f, -0.68f)
            curveToRelative(0.43f, -0.32f, 0.84f, -0.56f, 1.25f, -0.73f)
            lineToRelative(1.06f, -0.43f)
            lineToRelative(0.16f, -1.13f)
            lineToRelative(0.2f, -1.35f)
            horizontalLineToRelative(1.39f)
            lineToRelative(0.19f, 1.35f)
            lineToRelative(0.16f, 1.13f)
            lineToRelative(1.06f, 0.43f)
            curveToRelative(0.43f, 0.18f, 0.83f, 0.41f, 1.23f, 0.71f)
            lineToRelative(0.91f, 0.7f)
            lineToRelative(1.06f, -0.43f)
            lineToRelative(1.27f, -0.51f)
            lineToRelative(0.7f, 1.21f)
            lineToRelative(-1.07f, 0.85f)
            lineToRelative(-0.89f, 0.7f)
            lineToRelative(0.14f, 1.13f)
            close()
            moveTo(12.0f, 8.0f)
            curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
            reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
            reflectiveCurveToRelative(4.0f, -1.79f, 4.0f, -4.0f)
            reflectiveCurveToRelative(-1.79f, -4.0f, -4.0f, -4.0f)
            close()
            moveTo(12.0f, 14.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
            reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
            reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
            reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
            close()
        }
    }

    /** `Icons.Outlined.SwapVert`. */
    val SwapVert: ImageVector by lazy {
        icon("Outlined.SwapVert") {
            moveTo(16.0f, 17.01f)
            lineTo(16.0f, 10.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(7.01f)
            horizontalLineToRelative(-3.0f)
            lineTo(15.0f, 21.0f)
            lineToRelative(4.0f, -3.99f)
            horizontalLineToRelative(-3.0f)
            close()
            moveTo(9.0f, 3.0f)
            lineTo(5.0f, 6.99f)
            horizontalLineToRelative(3.0f)
            lineTo(8.0f, 14.0f)
            horizontalLineToRelative(2.0f)
            lineTo(10.0f, 6.99f)
            horizontalLineToRelative(3.0f)
            lineTo(9.0f, 3.0f)
            close()
            moveTo(16.0f, 17.01f)
            lineTo(16.0f, 10.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(7.01f)
            horizontalLineToRelative(-3.0f)
            lineTo(15.0f, 21.0f)
            lineToRelative(4.0f, -3.99f)
            horizontalLineToRelative(-3.0f)
            close()
            moveTo(9.0f, 3.0f)
            lineTo(5.0f, 6.99f)
            horizontalLineToRelative(3.0f)
            lineTo(8.0f, 14.0f)
            horizontalLineToRelative(2.0f)
            lineTo(10.0f, 6.99f)
            horizontalLineToRelative(3.0f)
            lineTo(9.0f, 3.0f)
            close()
        }
    }
}

/**
 * A 24dp Material icon: one black path, which `Icon(tint = …)` repaints. The path takes every value androidx's
 * `materialPath` gives it (the stroke ones too, though there is no stroke), so the copy equals the original.
 */
private fun icon(name: String, autoMirror: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).path(
        fill = SolidColor(Color.Black),
        stroke = null,
        strokeLineWidth = 1f,
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Bevel,
        strokeLineMiter = 1f,
        pathBuilder = block,
    ).build()
