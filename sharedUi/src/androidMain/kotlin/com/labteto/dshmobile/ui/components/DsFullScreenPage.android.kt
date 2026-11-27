package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

@Composable
internal actual fun DsFullScreenPage(
    onDismissRequest: () -> Unit,
    visibleState: MutableTransitionState<Boolean>?,
    onWindowReady: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) = DsFullScreenDialog(
    onDismissRequest = onDismissRequest,
    visibleState = visibleState,
    onWindowReady = onWindowReady,
    content = content,
)
