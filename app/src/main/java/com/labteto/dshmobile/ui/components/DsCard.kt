package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import com.labteto.dshmobile.ui.components.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * A grouped surface: 18dp radius, `bgLayer1` fill, hairline border — the design kit's `.card`.
 *
 * [contentPadding] is 16dp all round by default. A card that splits into separate tap targets
 * (a row you open next to a switch you flip) passes zero and pads inside each target instead, so
 * no part of the card is dead.
 *
 * The border is load-bearing in the light theme, where the page is warm paper and the card is
 * white: the edge is what separates them, so it is never dropped even when the fill alone would
 * read in dark mode.
 *
 * A card that can be pressed answers with its own fill under its content: while the card's
 * [onClick], or whatever [pressSource] watches, is pressed, the whole card takes the `active` tone
 * over its fill, with the same tone and spring as the app's click indication (`FocusRing.kt`) gives
 * every other clickable. A card does not use that indication: its fill is the one press it shows, so
 * its own clickable passes `indication = null`, and so must the targets inside a card that shares
 * its [pressSource] (or the pressed tone would be drawn twice over them). [pressSource] is for a card
 * whose taps live inside it (a row that opens next to a switch): the parts share one source, and
 * pressing any of them lights the card as one thing, which a fill per target could not do.
 * While the source has keyboard focus the border becomes the accent ring, as it does on a button.
 */
@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(DsSpacing.small),
    contentPadding: PaddingValues = PaddingValues(DsSpacing.comfortable),
    pressSource: InteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val interaction = remember { MutableInteractionSource() }
    val watched = pressSource ?: interaction
    val interactive = onClick != null || pressSource != null
    val pressed by watched.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = if (interactive && pressed) colors.active.compositeOver(colors.bgLayer1) else colors.bgLayer1,
        animationSpec = DsAnimations.pressFill,
        label = "cardFill",
    )
    val focusBorder = if (interactive) rememberFocusBorder(watched) else null
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .drawBehind { drawRect(fill) }
            .border(focusBorder ?: BorderStroke(1.dp, colors.borderL2), DsShapes.block)
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
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
