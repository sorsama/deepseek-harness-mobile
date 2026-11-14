package com.labteto.dshmobile.ui.adaptive

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

/**
 * Makes the window's title bar fit the app when it runs in a desktop window.
 *
 * In a freeform window (ChromeOS, Android's desktop mode) the system draws a caption bar with the
 * back, minimize, maximize and close buttons over the top of the window, and the app draws
 * edge to edge beneath it. The bar has no colour of its own: it shows the app's, and picks light or
 * dark buttons from a window flag the app has to set. Left alone the buttons follow the *system*
 * theme, so a light app on a dark system (or the other way round) gets white buttons on a pale bar,
 * which is to say no visible buttons. [dark] says what the app's own background is, and
 * [background] is the colour ChromeOS paints its frame with, taken from the task description.
 *
 * Nothing here does anything on a phone, which has no caption bar.
 */
@Composable
fun CaptionBarStyle(dark: Boolean, background: Color) {
    val view = LocalView.current
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        // The window flag that says whether the title bar buttons are dark or light. It exists from Android 15;
        // before that the task description below is what the shell reads, so it is set on every version.
        if (Build.VERSION.SDK_INT >= 35) {
            activity.window.insetsController?.setSystemBarsAppearance(
                if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS,
            )
        }
        // The title bar colour on ChromeOS and Android desktop mode (the status bar colour is the one that
        // picks dark or light buttons); also what the recents list shows. Opaque by definition.
        val opaque = background.copy(alpha = 1f).toArgb()
        if (Build.VERSION.SDK_INT >= 33) {
            activity.setTaskDescription(ActivityManager.TaskDescription.Builder().setPrimaryColor(opaque).setBackgroundColor(opaque).setStatusBarColor(opaque).build())
        } else if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            activity.setTaskDescription(ActivityManager.TaskDescription(null as String?, 0, opaque))
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
