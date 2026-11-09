package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme

/**
 * Button variants, mirroring the Ink kit's `.btn` family:
 *
 * - [Primary] — the white pill on ink (ink pill on paper). One per screen.
 * - [Info] — accent fill for "connect", "send", "apply".
 * - [Attention] — amber fill for "approve and run": the one decision a blocked turn waits on.
 * - [Ghost] — no fill, no border; secondary actions in dense chrome.
 * - [Outline] — hairline border; the counterpart to Primary in a two-button row.
 * - [Danger] — the kit's outlined destructive style (red label, dark red hairline).
 */
enum class DsButtonVariant { Primary, Info, Attention, Ghost, Outline, Danger }

/** Button sizes: [Normal] is h44, [Small] is h32, [Large] is h52 (design `.btn.lg`). */
enum class DsButtonSize { Normal, Small, Large }

/** Ink-style button with press-scale and haptic feedback. */
@Composable
fun DsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: DsButtonVariant = DsButtonVariant.Primary,
    size: DsButtonSize = DsButtonSize.Normal,
    icon: ImageVector? = null,
    allowWrap: Boolean = false,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    // A short, springless shrink: the design presses every control to .97 over 200ms.
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) DsAnimations.Scale.pressed else DsAnimations.Scale.normal,
        animationSpec = DsAnimations.pressScale,
        label = "buttonScale",
    )

    val (fill, content) = when (variant) {
        DsButtonVariant.Primary -> colors.brandPrimary to colors.onBrandPrimary
        DsButtonVariant.Info -> colors.buttonInfoFill to colors.onAccent
        DsButtonVariant.Attention -> colors.warn to colors.onWarn
        DsButtonVariant.Ghost -> Color.Transparent to colors.labelPrimary
        DsButtonVariant.Outline -> Color.Transparent to colors.labelPrimary
        DsButtonVariant.Danger -> Color.Transparent to colors.error
    }
    val background = when {
        !enabled -> when (variant) {
            DsButtonVariant.Ghost, DsButtonVariant.Outline -> Color.Transparent
            DsButtonVariant.Danger -> Color.Transparent
            else -> fill.copy(alpha = 0.4f)
        }
        hovered -> when (variant) {
            DsButtonVariant.Primary -> colors.buttonPrimaryHover
            DsButtonVariant.Info -> colors.buttonInfoHover
            DsButtonVariant.Attention -> colors.warnSecondary
            DsButtonVariant.Ghost, DsButtonVariant.Outline -> colors.hover
            DsButtonVariant.Danger -> colors.dangerHover
        }
        else -> fill
    }
    val contentColor = if (enabled) content else content.copy(alpha = 0.45f)
    val border = when (variant) {
        DsButtonVariant.Outline -> BorderStroke(1.dp, colors.borderL3)
        DsButtonVariant.Danger -> BorderStroke(1.dp, colors.errorBorder)
        else -> null
    }
    val height = when (size) {
        DsButtonSize.Normal -> DsSizes.button
        DsButtonSize.Small -> DsSizes.buttonCompact
        DsButtonSize.Large -> DsSizes.buttonLarge
    }
    val shape = when (size) {
        DsButtonSize.Normal, DsButtonSize.Small -> DsShapes.control
        DsButtonSize.Large -> DsShapes.buttonLarge
    }
    val horizontalPadding = when (size) {
        DsButtonSize.Normal -> 16.dp
        DsButtonSize.Small -> 12.dp
        DsButtonSize.Large -> 20.dp
    }
    Surface(
        onClick = {
            haptics.tap()
            onClick()
        },
        modifier = modifier
            .then(if (allowWrap) Modifier.heightIn(min = height) else Modifier.height(height))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        enabled = enabled,
        shape = shape,
        color = background,
        contentColor = contentColor,
        border = border,
        interactionSource = interaction,
    ) {
        Row(
            // Height, not size. `fillMaxSize` here made the *content* claim the whole width it was
            // offered, which pushed the button itself out to full width whatever the caller asked
            // for. A button that wants to span its parent says so through `modifier`.
            modifier = if (allowWrap) Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp)
            else Modifier.fillMaxHeight().padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(if (size == DsButtonSize.Small) 15.dp else 18.dp),
                    tint = contentColor,
                )
                if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
            }
            if (text.isNotEmpty()) {
                Text(
                    text,
                    style = when (size) {
                        DsButtonSize.Small -> DsType.small13Strong
                        DsButtonSize.Large -> DsType.buttonLarge
                        DsButtonSize.Normal -> DsType.button
                    },
                    color = contentColor,
                    maxLines = if (allowWrap) Int.MAX_VALUE else 1,
                )
            }
        }
    }
}

@Preview
@Composable
private fun DsButtonPreview() {
    DshTheme(customization = com.labteto.dshmobile.ui.theme.ThemeCustomization.Default.copy(preference = com.labteto.dshmobile.ui.theme.ThemePreference.DARK)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DsButton("Primary", {}, icon = Icons.Filled.Add)
            DsButton("Info", {}, variant = DsButtonVariant.Info)
            DsButton("Ghost", {}, variant = DsButtonVariant.Ghost)
            DsButton("Outline", {}, variant = DsButtonVariant.Outline)
            DsButton("Danger", {}, variant = DsButtonVariant.Danger)
            DsButton("Disabled", {}, enabled = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsButton("Small", {}, size = DsButtonSize.Small)
                DsButton("Large", {}, size = DsButtonSize.Large)
            }
        }
    }
}
