package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.theme.DsTheme

/** Not checked on a device: the page has only been drawn on the simulator. */
@Composable
internal actual fun DsFullScreenPage(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DsWindow(onDismissRequest = onDismissRequest, dimBehind = false) { insets ->
        Surface(Modifier.fillMaxSize(), color = DsTheme.colors.bgBase) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(insets), content = content)
        }
    }
}
