package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme

/**
 * A compact label — a badge at rest, a filter chip when it takes an [onClick].
 *
 * The kit distinguishes the two by shape: `.tag` is a 6dp-radius, 24px-high label; `.pill` is a
 * fully rounded 36px-high control. Keeping that distinction here means a tappable chip is
 * recognisable as a control without a hover state, and a badge never looks pressable.
 *
 * [selected] fills with the accent wash; [warn] uses the amber wash. Both keep the compact tag
 * shape even when tappable, for status chips that filter on tap.
 */
@Composable
fun DsPill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    warn: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) DsAnimations.Scale.pressed else DsAnimations.Scale.normal,
        animationSpec = DsAnimations.pressScale,
        label = "pillScale",
    )
    val tappable = onClick != null
    val shape = if (tappable) DsShapes.pillFull else DsShapes.chip
    val background = when {
        warn -> colors.warnTertiary
        selected -> colors.accentTertiary
        tappable -> colors.bgLayer1
        else -> colors.bgLayer3
    }
    val contentColor = when {
        warn -> colors.warnLabel
        selected -> colors.accent
        else -> colors.labelSecondary
    }
    Surface(
        onClick = {
            haptics.tap()
            onClick?.invoke()
        },
        modifier = modifier
            // A chip you can tap is the 36dp pill with a 48dp target; a tag you cannot is 24dp.
            .then(
                if (tappable) {
                    Modifier.minimumInteractiveComponentSize().heightIn(min = DsSizes.chip)
                } else {
                    Modifier.height(DsSizes.tag)
                },
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        enabled = tappable,
        shape = shape,
        color = background,
        contentColor = contentColor,
        border = when {
            warn -> BorderStroke(1.dp, colors.warnBorder)
            selected -> BorderStroke(1.dp, colors.accentBorder)
            tappable -> BorderStroke(1.dp, colors.borderL2)
            else -> null
        },
        interactionSource = interactionSource,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = if (tappable) 14.dp else 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = if (tappable) DsType.small13Strong else DsType.xsmall12,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DsPillPreview() {
    DshTheme(customization = com.labteto.dshmobile.ui.theme.ThemeCustomization.Default.copy(preference = com.labteto.dshmobile.ui.theme.ThemePreference.DARK)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsPill("Badge")
                DsPill("Warn", warn = true)
                DsPill("Selected", selected = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsPill("All 5", onClick = {})
                DsPill("Needs you 1", warn = true, onClick = {})
                DsPill("Archived", onClick = {})
            }
        }
    }
}
