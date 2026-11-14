package com.labteto.dshmobile.ui.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

/**
 * The fold or hinge that crosses this window right now, or null.
 *
 * Reads Jetpack WindowManager's [WindowInfoTracker] for the hosting activity, so it follows the
 * device as it is folded, unfolded and propped open, and it follows the window when it is resized:
 * a feature is only reported while it intersects this window, which is why an app in the half of a
 * split screen that does not cover the hinge simply sees none. Collected with the lifecycle, so a
 * backgrounded app does not keep a listener on the display.
 *
 * Null as well where there is no activity to ask (a preview, a test that hosts the content in a
 * bare window) and on every device that does not fold.
 */
@Composable
fun rememberHinge(): Hinge? {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() } ?: return null
    val density = LocalDensity.current.density
    // Null until the tracker has said anything: the constructor of WindowLayoutInfo is not public API.
    val layoutInfo by remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
    }.collectAsStateWithLifecycle(initialValue = null as WindowLayoutInfo?)
    return remember(layoutInfo, density) { layoutInfo?.toHinge(density) }
}

/**
 * The feature the shell should lay out around.
 *
 * A device reports one per fold; the one that splits the window is the one that matters, so it wins
 * over a seamless crease that happens to be listed first.
 */
fun WindowLayoutInfo.toHinge(density: Float): Hinge? {
    val folds = displayFeatures.filterIsInstance<FoldingFeature>()
    val feature = folds.firstOrNull { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
        ?: folds.firstOrNull()
        ?: return null
    return feature.toHinge(density)
}

/** [FoldingFeature]'s facts in dp, which is the unit the planner and the composables work in. */
fun FoldingFeature.toHinge(density: Float): Hinge = Hinge(
    left = bounds.left / density,
    top = bounds.top / density,
    right = bounds.right / density,
    bottom = bounds.bottom / density,
    vertical = orientation == FoldingFeature.Orientation.VERTICAL,
    halfOpened = state == FoldingFeature.State.HALF_OPENED,
    separating = isSeparating,
    occluding = occlusionType == FoldingFeature.OcclusionType.FULL,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
