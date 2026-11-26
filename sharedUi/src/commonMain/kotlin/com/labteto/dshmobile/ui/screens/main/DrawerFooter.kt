package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.chat_composer_send
import com.labteto.dshmobile.shared.resources.ink_chat_start_task
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import org.jetbrains.compose.resources.stringResource

/** The bottom bar: a task starts from here, on this harness. */
@Composable
internal fun StartTaskBar(hostName: String, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.pillFull,
        color = colors.bgLayer2,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderL2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(DsIcons.Add, contentDescription = null, tint = colors.labelSecondary, modifier = Modifier.size(22.dp))
            }
            Text(
                stringResource(Res.string.ink_chat_start_task, hostName),
                style = DsType.std14,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    DsIcons.ArrowUpward,
                    contentDescription = stringResource(Res.string.chat_composer_send),
                    tint = colors.onAccent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
