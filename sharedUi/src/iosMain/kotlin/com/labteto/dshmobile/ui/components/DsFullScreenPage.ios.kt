package com.labteto.dshmobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsTheme

/** Not checked on a device: the page has only been drawn on the simulator. */
@Composable
internal actual fun DsFullScreenPage(
    onDismissRequest: () -> Unit,
    visibleState: MutableTransitionState<Boolean>?,
    onWindowReady: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    DsWindow(onDismissRequest = onDismissRequest, dimBehind = false) { insets ->
        if (onWindowReady != null) {
            val ready by rememberUpdatedState(onWindowReady)
            // The dialog draws in the app's own window: one frame and it is on screen.
            LaunchedEffect(Unit) {
                withFrameNanos { }
                ready()
            }
        }
        val page: @Composable () -> Unit = {
            Surface(Modifier.fillMaxSize(), color = DsTheme.colors.bgBase) {
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
