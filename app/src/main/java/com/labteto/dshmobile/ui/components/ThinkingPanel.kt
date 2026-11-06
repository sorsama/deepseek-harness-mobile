package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** Reasoning stays quiet and compact, independently of how many lines it contains. */
@Composable
fun ThinkingPanel(
    text: String,
    streaming: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    if (text.isBlank() && !streaming) return
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.bgLayer1)
            .border(1.dp, colors.borderL2, DsShapes.block),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clickable(role = Role.Button) {
                    haptics.tap()
                    onToggle()
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(FeatherIcons.Layers, null, Modifier.size(15.dp), tint = colors.labelTertiary)
            Text(
                stringResource(if (streaming) R.string.chat_thinking else R.string.chat_thought_process),
                style = DsType.body15,
                color = colors.labelSecondary,
                modifier = Modifier.weight(1f),
            )
            if (streaming) StateDot(StateDotState.Running, size = 6.dp)
            Icon(
                FeatherIcons.ChevronRight,
                null,
                Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = if (expanded) 90f else 0f },
                tint = colors.labelTertiary,
            )
        }
        if (expanded) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                // Selectable text rather than full-size reply markdown: this is reasoning, not the answer.
                if (content != null) content() else androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text.trimEnd(), style = DsType.small13, color = colors.labelTertiary, softWrap = true)
                }
            }
        }
    }
}
