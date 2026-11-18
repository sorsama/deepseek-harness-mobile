package com.labteto.dshmobile.ui.input

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The indication every `clickable`, `toggleable`, `selectable` and `combinedClickable` in the app
 * draws with (and `clickableWithHold`, which passes it on): the design system's own press and hover
 * fills, and a ring around the control that has keyboard focus. Installed once, in the theme
 * (`LocalIndication`), so no screen has to know it exists. Material's `Surface(onClick)` controls
 * (`DsButton`, `DsFilterChip`, `DsIconButton`) do not read it: they bring Material's ripple.
 *
 * - **Pressed:** the `active` tone over the element, the same tone and the same spring
 *   ([DsAnimations.pressFill]) a pressed [DsCard][com.labteto.dshmobile.ui.components.DsCard] fills
 *   with. A quick tap is held for [MinimumPress] so it is seen even when the press and the release
 *   arrive together (a tap in a scrolling list is reported only when the finger lifts).
 * - **Hovered** (a mouse or a trackpad): the lighter `hover` tone.
 * - **Keyboard focus:** a 2dp accent ring, and no tint. A clickable takes focus only in keyboard
 *   mode, so a touch screen never shows the ring and a mouse click does not leave one behind.
 *
 * **Why it draws the fills itself.** It used to wrap Material's ripple in a node that also drew the
 * ring, and no clickable in the app showed a press. A Compose node that is itself a draw node is
 * drawn as that one node: Compose UI 1.7 (`LayoutNodeDrawScope` and `dispatchForKind`) only looks at
 * the nodes a `DelegatingNode` delegates to when the node is not of the kind being drawn, so the
 * ripple inside was never drawn and only the ring was. Moving the ring to a second delegated draw
 * node beside the ripple does not work either: every delegated draw node is drawn in turn and each
 * one draws the content beneath it, so the element would be drawn twice. Drawing the wrapped ripple
 * by hand is not possible with Material 3's `ripple()`, whose real draw node is created privately
 * inside a theme-reading wrapper. And the design system describes presses and hovers as fills (a
 * row's fill in `DsShapes.row`, a card's in `DsCard`), so this one node draws them.
 *
 * The fill covers the clickable's bounds; an element with a rounded shape clips before its
 * `clickable`, as it would for a ripple.
 */
@Composable
fun rememberAppIndication(ring: Color): Indication {
    val colors = DsTheme.colors
    return remember(colors.hover, colors.active, ring) { DsIndication(colors.hover, colors.active, ring) }
}

/** How long a press stays visible at the least, from the moment it starts. */
internal val MinimumPress = 120.milliseconds

private class DsIndication(
    private val hover: Color,
    private val press: Color,
    private val ring: Color,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        DsIndicationNode(interactionSource, hover, press, ring)

    override fun equals(other: Any?): Boolean =
        other is DsIndication && other.hover == hover && other.press == press && other.ring == ring

    override fun hashCode(): Int = (31 * hover.hashCode() + press.hashCode()) * 31 + ring.hashCode()
}

private class DsIndicationNode(
    private val interactionSource: InteractionSource,
    private val hover: Color,
    private val press: Color,
    private val ring: Color,
) : Modifier.Node(), DrawModifierNode {
    /** The press colour at nothing: the fill fades in and out without passing through black. */
    private val rest = press.copy(alpha = 0f)
    private var fill = Animatable(rest)
    private var target = rest
    private var move: Job? = null
    private val presses = mutableListOf<PressInteraction.Press>()
    private val hovers = mutableListOf<HoverInteraction.Enter>()
    private val focuses = mutableListOf<FocusInteraction.Focus>()
    private var pressStarted: TimeMark? = null
    private var focused = false

    override fun onAttach() {
        // A node can be detached and attached again (a reused list item): start from nothing.
        presses.clear(); hovers.clear(); focuses.clear()
        fill = Animatable(rest)
        target = rest
        focused = false
        pressStarted = null
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction -> handle(interaction) }
        }
    }

    private fun handle(interaction: Interaction) {
        when (interaction) {
            is PressInteraction.Press -> {
                presses += interaction
                pressStarted = TimeSource.Monotonic.markNow()
            }
            is PressInteraction.Release -> presses -= interaction.press
            is PressInteraction.Cancel -> presses -= interaction.press
            is HoverInteraction.Enter -> hovers += interaction
            is HoverInteraction.Exit -> hovers -= interaction.enter
            is FocusInteraction.Focus -> focuses += interaction
            is FocusInteraction.Unfocus -> focuses -= interaction.focus
            else -> return
        }
        val nowFocused = focuses.isNotEmpty()
        if (nowFocused != focused) {
            focused = nowFocused
            invalidateDraw()
        }
        val next = when {
            presses.isNotEmpty() -> press
            hovers.isNotEmpty() -> hover
            else -> rest
        }
        if (next == target) return
        target = next
        move?.cancel()
        move = coroutineScope.launch {
            if (next != press) {
                val held = pressStarted?.elapsedNow()
                if (held != null && held < MinimumPress) {
                    // Finish showing the press before letting go of it. On the frame clock rather than
                    // with a delay, so it moves with the animation clock (and a test's virtual one).
                    val remaining = (MinimumPress - held).inWholeMilliseconds.toInt().coerceAtLeast(1)
                    fill.animateTo(press, tween(remaining)) { invalidateDraw() }
                }
            }
            fill.animateTo(next, DsAnimations.pressFill) { invalidateDraw() }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val tone = fill.value
        if (tone.alpha > 0f) drawRect(tone)
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
