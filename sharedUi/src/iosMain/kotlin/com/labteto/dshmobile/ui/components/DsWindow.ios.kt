package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Compose's dialog, sized to the whole screen ([DialogProperties.usePlatformDefaultWidth] off) so the content
 * decides where its plate sits, as it does on Android. The content pads itself by the safe area and the keyboard.
 *
 * Not checked on a device: whether the platform insets the dialog adds on its own double the padding. A plate
 * would then sit a little further from the edges, which is harmless for the centred dialogs the shared screens open.
 */
@Composable
internal actual fun DsWindow(
    onDismissRequest: () -> Unit,
    dimBehind: Boolean,
    content: @Composable (insets: WindowInsets) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val scrim = if (dimBehind) Modifier.background(Color.Black.copy(alpha = 0.32f)) else Modifier
        Box(Modifier.fillMaxSize().then(scrim).dismissOnEscape { onDismissRequest() }) {
            content(WindowInsets.safeDrawing.union(WindowInsets.ime))
        }
    }
}
