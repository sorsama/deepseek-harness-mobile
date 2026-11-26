package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

@Composable
internal actual fun DsFullScreenPage(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    DsFullScreenDialog(onDismissRequest = onDismissRequest, content = content)
