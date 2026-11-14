package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable as foundationClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.delay

/*
 * Mouse and trackpad behaviour for the controls that have a tap.
 *
 * A touch screen has no pointer, so none of this changes anything on a phone; with a mouse (a
 * Chromebook, a tablet with a dock, a phone on a desktop display) it is what separates an app from
 * a stretched phone layout: the cursor says what is clickable, an icon without a label names itself
 * when it is pointed at, and the secondary button opens what a hold opens on a touch screen.
 */

/**
 * The hand cursor over something that can be clicked. A no-op for touch input, and a disabled
 * control keeps the arrow because nothing will happen when it is clicked.
 */
fun Modifier.handCursor(enabled: Boolean = true): Modifier =
    if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

/**
 * A [clickable] that also shows the hand cursor. Declared with the same name and shape as
 * foundation's so a file opts in with one import, instead of every call site adding the cursor by
 * hand and the next one forgetting it.
 */
fun Modifier.clickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = this
    .handCursor(enabled)
    .foundationClickable(enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)

/** The overload that takes its own interaction source and indication; see the one above. */
fun Modifier.clickable(
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = this
    .handCursor(enabled)
    .foundationClickable(
        interactionSource = interactionSource,
        indication = indication,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    )

/**
 * Runs [onClick] when the mouse's secondary button (right click, or a two-finger click on a
 * trackpad) is pressed over this element, and consumes that press so the primary click handler
 * beneath it does not also fire.
 *
 * Put it before the element's own click or hold modifier: the press is looked at on the way down
 * the tree (the Initial pass), and the clickable behind it then sees a consumed press and ignores
 * it. A finger never produces a secondary press, so touch behaviour is untouched.
 */
fun Modifier.onSecondaryClick(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        composed {
            val current by rememberUpdatedState(onClick)
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val press = event.type == PointerEventType.Press &&
                            event.buttons.isSecondaryPressed &&
                            event.changes.any { it.type == PointerType.Mouse }
                        if (press) {
                            event.changes.forEach { it.consume() }
                            current()
                        }
                    }
                }
            }
        }
    }

/** How long the pointer rests on a control before its name appears: Material's own hover delay. */
private const val HoverTooltipDelayMs = 500L

/**
 * Shows [text] in a tooltip while a mouse rests on [content], for a control that is only an icon.
 *
 * It is driven by the control's own [interactionSource] (hovered, not pressed) and not by
 * Material's built-in input handling, which would also answer a touch hold with a tooltip: a hold
 * already opens a menu on several of these controls, and a phone should not grow a new gesture.
 * Place it *inside* the control's hit area, around its visible content, so the tooltip anchors to
 * the control and the wrapper takes no layout room of its own.
 *
 * A null or blank [text] draws [content] alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoverTooltip(
    text: String?,
    interactionSource: InteractionSource,
    content: @Composable () -> Unit,
) {
    if (text.isNullOrBlank()) {
        content()
        return
    }
    val colors = DsTheme.colors
    val state = rememberTooltipState(isPersistent = true)
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(hovered, pressed) {
        if (hovered && !pressed) {
            delay(HoverTooltipDelayMs)
            // Suspends while the tooltip is up; leaving or pressing restarts this effect, which
            // cancels the call and takes the tooltip away.
            state.show()
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(containerColor = colors.toastBg, contentColor = colors.onToast) {
                Text(text, style = DsType.small13)
            }
        },
        state = state,
        enableUserInput = false,
        content = content,
    )
}

/**
 * Lets the mouse wheel scroll a row that only moves sideways.
 *
 * A wheel reports vertical motion, and a horizontal list ignores that: with a mouse the strip of
 * attachment chips or filter chips then looks stuck, because the only way to move it is a sideways
 * swipe that a mouse does not have. This turns a vertical notch into sideways travel, but only when
 * the strip can still move that way and the device did not already send a sideways delta (a
 * trackpad's two-finger swipe, or Shift+wheel, which the list handles itself).
 */
fun Modifier.horizontalWheelScroll(state: ScrollableState): Modifier = composed {
    val stepPx = with(LocalDensity.current) { WheelStep.toPx() }
    Modifier.pointerInput(state, stepPx) {
        awaitPointerEventScope {
            while (true) {
                // Before the children see it: a sideways list ignores a vertical notch, and this runs first so
                // the notch is not spent elsewhere when the strip can still move.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                val delta = change.scrollDelta
                // Only while the strip can move that way: at its ends, or when everything fits, the notch
                // goes on to whatever scrolls behind it (the transcript).
                val canMove = if (delta.y > 0f) state.canScrollForward else state.canScrollBackward
                if (!change.isConsumed && delta.x == 0f && delta.y != 0f && canMove) {
                    state.dispatchRawDelta(delta.y * stepPx)
                    change.consume()
                }
            }
        }
    }
}

/** How far one wheel notch moves a sideways list: about what a notch moves a vertical one. */
private val WheelStep = 48.dp

/**
 * The 2dp accent outline a control wears while it has keyboard focus, or null while it does not.
 *
 * Material's `Surface(onClick = ...)` brings its own ripple, so the app-wide focus indication (see
 * `rememberFocusRingIndication` in the app module) never reaches it; the controls built on it pass
 * this as their border instead. A clickable is only focusable in keyboard mode, so a touch screen
 * never shows it and a mouse click does not leave it behind.
 */
@Composable
fun rememberFocusBorder(interactionSource: InteractionSource): BorderStroke? {
    val focused by interactionSource.collectIsFocusedAsState()
    val accent = DsTheme.colors.accent
    return if (focused) BorderStroke(2.dp, accent) else null
}
