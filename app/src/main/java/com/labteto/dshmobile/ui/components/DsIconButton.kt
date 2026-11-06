package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * Icon button with a guaranteed 48dp touch target.
 *
 * The pressed highlight is a 48dp circle, which is what the design kit's `.ib` (44px) chrome rounds
 * up to without shrinking the thing a thumb has to hit. The glyph is centred in its own [Box] and sized
 * with `requiredSize`, not `size`: Material's `Surface` lays its content out with
 * `propagateMinConstraints = true`, so an `Icon` placed directly inside it inherits a 48dp
 * *minimum* and an ordinary `size` modifier is clamped straight back up.
 *
 * Two looks, by role (see the shape system on [DsShapes]):
 *  - **bare** (the default): no border, no fill at rest, a circular press highlight. For an icon that
 *    stands on its own in a bar or a row.
 *  - **[bordered]**: the twin of a text input — [DsSizes.field] tall and wide, the 12dp control corner,
 *    `bgLayer2` fill and a 1dp `borderL2` line. For an icon button that sits *next to* a field (a
 *    sort button beside a search field), so the pair reads as one toolbar. [selected] tints the
 *    border and glyph with the accent for a toggle that is on.
 */
@Composable
fun DsIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = DsTheme.colors.labelSecondary,
    iconSize: Dp = 20.dp,
    fill: Boolean = false,
    bordered: Boolean = false,
    selected: Boolean = false,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) DsAnimations.Scale.pressed else DsAnimations.Scale.normal,
        animationSpec = DsAnimations.pressScale,
        label = "iconButtonScale",
    )

    val rest = when {
        bordered -> colors.bgLayer2
        fill -> colors.bgLayer3
        else -> Color.Transparent
    }
    val background = when {
        !enabled -> rest
        isPressed -> colors.hover
        isHovered -> colors.hover.copy(alpha = 0.5f)
        else -> rest
    }
    val glyph = if (selected) colors.accent else tint

    Surface(
        onClick = {
            haptics.tap()
            onClick()
        },
        modifier = modifier
            .size(if (bordered) DsSizes.field else DsSpacing.touchTarget)
            .semantics { role = Role.Button },
        enabled = enabled,
        color = background,
        shape = if (bordered) DsShapes.control else CircleShape,
        border = if (bordered) BorderStroke(1.dp, if (selected) colors.accent else colors.borderL2) else null,
        interactionSource = interactionSource,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) glyph else glyph.copy(alpha = 0.4f),
                modifier = Modifier
                    .requiredSize(iconSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
    }
}
