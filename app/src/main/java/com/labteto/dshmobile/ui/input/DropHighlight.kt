package com.labteto.dshmobile.ui.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** Test tag on the drop highlight, so a test can wait for it to appear and to go. */
const val DROP_HIGHLIGHT_TAG = "drop-highlight"

/**
 * What a drag over the chat looks like: the whole pane tinted with the accent, an accent outline
 * inside its edge and a plate in the middle saying what letting go will do. It takes no touches, so
 * it can never be the thing that gets in the way of dropping.
 */
@Composable
fun DropHighlight(visible: Boolean, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .testTag(DROP_HIGHLIGHT_TAG)
                .background(colors.accent.copy(alpha = 0.08f))
                .padding(8.dp)
                .border(2.dp, colors.accent, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = colors.bgLayer2,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.accent),
                shadowElevation = 6.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.chat_drop_hint), style = DsType.std14, color = colors.labelPrimary)
                }
            }
        }
    }
}
