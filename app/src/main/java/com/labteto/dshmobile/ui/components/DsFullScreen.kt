package com.labteto.dshmobile.ui.components

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.isDsDark
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * A full-screen page in its own window: Automations, Plugins, the Workspace, the full file viewer,
 * the image viewer. [content] is laid out inside the system bars, the display cutout and the
 * keyboard, so a footer or a key row at the bottom is never under the gesture bar. Nothing else in
 * the app may open a full-screen window by hand; see [DsWindow] for why.
 *
 * The page is a dialog window rather than a composable drawn over the chat because it keeps the
 * window-level stacking the sheets, dialogs and pickers a page opens depend on, and because a page
 * can be opened from deep inside a list (the image viewer) where an overlay would be clipped.
 *
 * A window has no enter or exit of its own: composing it shows it, and leaving the composition
 * removes it on the same frame. A page that wants one passes [visibleState]; the whole page
 * (background included) then rises in and sinks away through it, and the caller keeps the page
 * composed until the state reports it finished leaving (`currentState` false with nothing pending).
 * Left null, the page appears and goes instantly, as every other page does.
 *
 * [onWindowReady] is called once the window has had the few frames it needs to reach the screen.
 * An enter transition started with the composition plays out before anything is visible, so a
 * caller that animates the page in starts its transition from here rather than from composition.
 */
@Composable
fun DsFullScreenDialog(
    onDismissRequest: () -> Unit,
    containerColor: Color = DsTheme.colors.bgBase,
    visibleState: MutableTransitionState<Boolean>? = null,
    onWindowReady: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    DsWindow(onDismissRequest = onDismissRequest, dimBehind = false) { insets ->
        if (onWindowReady != null) {
            val ready by rememberUpdatedState(onWindowReady)
            LaunchedEffect(Unit) {
                delay(WindowSettleMillis)
                ready()
            }
        }
        val page: @Composable () -> Unit = {
            Surface(Modifier.fillMaxSize(), color = containerColor) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(insets), content = content)
            }
        }
        if (visibleState == null) {
            page()
        } else {
            AnimatedVisibility(
                visibleState = visibleState,
                modifier = Modifier.fillMaxSize(),
                enter = DsAnimations.pageEnter,
                exit = DsAnimations.pageExit,
            ) { page() }
        }
    }
}

/**
 * The one place the app creates a dialog window. [DsFullScreenDialog] and [DsDialog] both go
 * through it, and a unit test (`WindowRulesTest`) fails the build if any other file creates a
 * Compose `Dialog` or touches `decorFitsSystemWindows`.
 *
 * **Why.** A plain Compose `Dialog` is not edge to edge, whatever `decorFitsSystemWindows` says.
 * The window manager fits a dialog window inside the system bars (`fitInsetsTypes` is status bars,
 * navigation bars, caption bar and overlays) and moves it down by the status bar height, while
 * Compose still measures the content at the full display height. On Android 15 and newer the last
 * rows end up as many pixels below the screen as the gesture bar is tall, and the dialog reports no
 * insets, so nothing pads for them. That cut off the Automations footer and the Terminal key row
 * on a phone. The window is therefore asked to cover the whole display with no fitting, and the
 * content is padded from the window's own insets plus the activity's, translated to wherever the
 * window really sits.
 *
 * [content] receives the insets to pad with (system bars, cutout and the keyboard).
 */
@Composable
internal fun DsWindow(
    onDismissRequest: () -> Unit,
    dimBehind: Boolean,
    content: @Composable (insets: WindowInsets) -> Unit,
) {
    // Captured in the caller's composition on purpose. For the first window this is the activity's
    // own view, which is the one place the app's insets have always been right. A window opened from
    // inside another one inherits that host instead of mistaking the parent dialog for the activity.
    val parentView = LocalView.current
    val parentBars = WindowInsets.systemBars
    val parentCutout = WindowInsets.displayCutout
    val inherited = LocalDsHost.current
    val host = remember(inherited, parentView, parentBars, parentCutout) {
        inherited ?: DsHost(parentView, parentBars.union(parentCutout))
    }
    Dialog(
        onDismissRequest = onDismissRequest,
        // `usePlatformDefaultWidth = true` looks backwards and is deliberate: with it off, Compose sizes
        // the window to its content on every layout, and on Android 10 to 14 the content is capped at
        // the app-visible area, which left the chat showing through above and below the page.
        // [coverDisplay] asks for the whole display itself.
        properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false),
    ) {
        val windowView = LocalView.current
        EdgeToEdgeWindow(windowView, dimBehind)
        val bars = WindowInsets.systemBars
        val cutout = WindowInsets.displayCutout
        val keyboard = WindowInsets.ime
        // What a dialog window pads its content by: what the dialog reports for itself (it is the
        // window that receives the keyboard) together with the activity's bars translated into the
        // dialog's own coordinates. The second half is the safety net for Android versions and phone
        // makers where a dialog window reports no bar insets, or sits somewhere other than the full
        // display; either would leave the bottom of a page under the gesture bar.
        val insets = remember(bars, cutout, keyboard, host, windowView) {
            bars.union(cutout).union(keyboard).union(HostRelative(host, windowView))
        }
        // Escape closes any window the app opens, as back does on a touch screen.
        CompositionLocalProvider(LocalDsHost provides host) {
            Box(Modifier.fillMaxSize().dismissOnEscape { onDismissRequest() }) { content(insets) }
        }
    }
}

/**
 * How long a freshly composed dialog window takes to reach the screen on a phone: measured on a
 * 60Hz emulator, the first frames of anything started with the composition were never seen.
 */
private const val WindowSettleMillis = 120L

/** The activity's window and the bars it reports, which every dialog window falls back to. */
@Immutable
internal class DsHost(val view: View, val bars: WindowInsets)

internal val LocalDsHost = compositionLocalOf<DsHost?> { null }

/**
 * The activity's bar insets expressed for a window that may be offset from, or a different size to,
 * the activity window: the safe rectangle is worked out in screen coordinates, then each side is
 * however far the window reaches past it. For a dialog that is exactly the activity's size and
 * place this is the activity's insets unchanged.
 */
private class HostRelative(private val host: DsHost, private val windowView: View) : WindowInsets {
    private val here = IntArray(2)
    private val there = IntArray(2)

    private fun placed(): Boolean =
        windowView.isAttachedToWindow && windowView.width > 0 && windowView.height > 0 &&
            host.view.isAttachedToWindow && host.view.width > 0 && host.view.height > 0

    private fun locate() {
        windowView.getLocationOnScreen(here)
        host.view.getLocationOnScreen(there)
    }

    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int {
        val inset = host.bars.getLeft(density, layoutDirection)
        if (!placed()) return inset
        locate()
        return max(0, there[0] + inset - here[0])
    }

    override fun getTop(density: Density): Int {
        val inset = host.bars.getTop(density)
        if (!placed()) return inset
        locate()
        return max(0, there[1] + inset - here[1])
    }

    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int {
        val inset = host.bars.getRight(density, layoutDirection)
        if (!placed()) return inset
        locate()
        return max(0, (here[0] + windowView.width) - (there[0] + host.view.width - inset))
    }

    override fun getBottom(density: Density): Int {
        val inset = host.bars.getBottom(density)
        if (!placed()) return inset
        locate()
        return max(0, (here[1] + windowView.height) - (there[1] + host.view.height - inset))
    }
}

/**
 * Makes the dialog window what its content assumes: the whole display, drawn under the bars, with
 * transparent bars whose icons follow the theme. Run once per window and theme, not per frame.
 */
@Composable
private fun EdgeToEdgeWindow(view: View, dimBehind: Boolean) {
    val dark = isDsDark(DsTheme.appearance)
    val window = remember(view) { view.dialogWindow() }
    DisposableEffect(window, dimBehind, dark) {
        window?.let { runCatching { it.coverDisplay(dimBehind, dark) } }
        onDispose { }
    }
}

private fun View.dialogWindow(): Window? =
    (this as? DialogWindowProvider)?.window ?: (parent as? DialogWindowProvider)?.window

@Suppress("DEPRECATION")
private fun Window.coverDisplay(dimBehind: Boolean, dark: Boolean) {
    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    addFlags(
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
    )
    clearFlags(
        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
    )
    if (dimBehind) addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND) else clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    WindowCompat.setDecorFitsSystemWindows(this, false)
    val params = attributes
    if (Build.VERSION.SDK_INT >= 28) {
        params.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    if (Build.VERSION.SDK_INT >= 30) {
        // The line that matters on Android 15 and newer: without it the window manager shrinks the
        // window to the space between the bars.
        params.fitInsetsTypes = 0
        params.fitInsetsSides = 0
    }
    attributes = params
    statusBarColor = android.graphics.Color.TRANSPARENT
    navigationBarColor = android.graphics.Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) {
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }
    WindowCompat.getInsetsController(this, decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}
