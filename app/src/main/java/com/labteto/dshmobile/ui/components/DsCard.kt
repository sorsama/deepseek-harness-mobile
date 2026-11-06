package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * A grouped surface: 18dp radius, `bgLayer1` fill, hairline border — the design kit's `.card`.
 *
 * The border is load-bearing in the light theme, where the page is warm paper and the card is
 * white: the edge is what separates them, so it is never dropped even when the fill alone would
 * read in dark mode.
 */
@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(DsSpacing.small),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.bgLayer1)
            .border(1.dp, colors.borderL2, DsShapes.block)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null) {
                        haptics.tap()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.comfortable),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
