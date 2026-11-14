package com.labteto.dshmobile.ui.input

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The indication every clickable in the app draws with: the platform's own (Material's ripple, which
 * also tints a hovered or focused control) and, on top of it, a ring around the control that has
 * keyboard focus.
 *
 * A tint alone is too faint to follow with Tab: Material's focus layer is a 10% overlay. The ring is
 * drawn only for focus, and a clickable takes focus only in keyboard mode (it is not focusable while
 * the last input was a touch), so a phone never shows one and a mouse click does not leave one
 * behind. Installed once, in the theme, so every `clickable`, `Surface(onClick)`, switch and tab in
 * the app has it without each of them being touched.
 */
@Composable
fun rememberFocusRingIndication(base: Indication, ring: Color): Indication =
    remember(base, ring) { if (base is IndicationNodeFactory) FocusRingIndication(base, ring) else base }

private class FocusRingIndication(
    private val base: IndicationNodeFactory,
    private val ring: Color,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusRingNode(interactionSource, base.create(interactionSource), ring)

    override fun equals(other: Any?): Boolean =
        other is FocusRingIndication && other.base == base && other.ring == ring

    override fun hashCode(): Int = 31 * base.hashCode() + ring.hashCode()
}

private class FocusRingNode(
    private val interactionSource: InteractionSource,
    baseNode: DelegatableNode,
    private val ring: Color,
) : DelegatingNode(), DrawModifierNode {
    private var focused = false

    init {
        delegate(baseNode)
    }

    override fun onAttach() {
        coroutineScope.launch {
            val held = mutableListOf<FocusInteraction.Focus>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> held.add(interaction)
                    is FocusInteraction.Unfocus -> held.remove(interaction.focus)
                    else -> return@collect
                }
                val now = held.isNotEmpty()
                if (now != focused) {
                    focused = now
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val stroke = 2.dp.toPx()
        val inset = stroke
        val width = size.width - inset * 2
        val height = size.height - inset * 2
        if (width <= 0f || height <= 0f) return
        // A pill or a circle is as round as it is tall; a row or a card keeps a 10dp corner.
        val radius = if (size.height <= 52.dp.toPx()) height / 2 else 10.dp.toPx()
        drawRoundRect(
            color = ring,
            topLeft = Offset(inset, inset),
            size = Size(width, height),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(stroke),
        )
    }
}
