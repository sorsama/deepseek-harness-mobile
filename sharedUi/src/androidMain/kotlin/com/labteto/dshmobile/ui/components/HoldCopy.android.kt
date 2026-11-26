package com.labteto.dshmobile.ui.components

import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The platform toast rather than the app's: a copy is often made from a sheet or a dialog, and the app's toast
 * is drawn in the screen's window underneath them. From Android 13 the system shows its own confirmation, so
 * a success is only said on older phones; a failure always is.
 */
@Composable
internal actual fun rememberCopyNotice(): (ok: Boolean, message: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { ok: Boolean, message: String ->
            if (!ok || Build.VERSION.SDK_INT < 33) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
