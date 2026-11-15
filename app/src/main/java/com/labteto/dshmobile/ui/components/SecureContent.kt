package com.labteto.dshmobile.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import com.labteto.dshmobile.BuildConfig
import java.io.File

/**
 * Keep what this window shows out of screenshots, screen recordings, the recents thumbnail and
 * autofill, for as long as the calling composable is on screen.
 *
 * `FLAG_SECURE` goes on the window the caller is drawn in (a [DsFullScreenDialog] page is its own
 * window) and on the activity behind it, whose thumbnail the recents screen would otherwise show.
 * Each flag is taken off again only if this call put it there. The page's views are also excluded
 * from autofill, so a key typed there is never offered to an autofill service to remember.
 *
 * Debug builds only: the flag can be switched off for evidence screenshots by creating the file
 * `files/debug-allow-capture` in the app's own storage (`adb shell run-as <package> touch ...`).
 */
@Composable
fun SecureContent() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        val windows = listOfNotNull(view.dsDialogWindow(), context.findActivity()?.window).distinct()
        val allowCapture = BuildConfig.DEBUG && File(context.filesDir, "debug-allow-capture").exists()
        val flagged = if (allowCapture) emptyList() else windows.filter { it.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 }
        flagged.forEach { it.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        val root = view.rootView
        val autofill = root.importantForAutofill
        root.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose {
            flagged.forEach { it.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            root.importantForAutofill = autofill
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The text toolbar for a secret field: paste and select-all stay, copy and cut are gone, so the
 * app never puts a key on the clipboard, where clipboard history and other apps could read it.
 */
class NoCopyTextToolbar(private val delegate: TextToolbar) : TextToolbar {
    override val status: TextToolbarStatus get() = delegate.status

    override fun hide() = delegate.hide()

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = delegate.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested)
}
