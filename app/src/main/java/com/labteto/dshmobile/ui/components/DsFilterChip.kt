package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * A filter chip: the kit's `.pill`, brand-filled while selected, hairline while not, amber while
 * [warn] says it has someone to answer.
 *
 * Drawn [DsSizes.chip] (36dp) tall with the fully round pill shape, but given a 48dp touch target
 * through [minimumInteractiveComponentSize], so the visible chip stays slim while a thumb still has
 * somewhere to land. Selection is announced as a selected tab-like state, because the fill alone
 * cannot say so to a screen reader.
 */
@Composable
fun DsFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val fill = if (selected) colors.brandPrimary else Color.Transparent
    val content = when {
        selected -> colors.onBrandPrimary
        warn -> colors.warnLabel
        else -> colors.labelSecondary
    }
    Surface(
        onClick = {
            haptics.selection()
            onClick()
        },
        shape = DsShapes.pillFull,
        color = fill,
        contentColor = content,
        border = if (selected) null else BorderStroke(1.dp, if (warn) colors.warnBorder else colors.borderL3),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .height(DsSizes.chip)
            .semantics {
                this.selected = selected
                role = Role.Tab
            },
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, style = DsType.tabText, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
