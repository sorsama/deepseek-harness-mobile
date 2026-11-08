package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The hold gesture: press and hold anything that has more to offer than its tap, and a sheet of
 * what can be done with it comes up from the bottom.
 *
 * One gesture and one sheet everywhere — a sent message, a queued one, a tool call, a file — so a
 * reader who finds it once can expect it on the next thing. Holding gives the same long-press
 * haptic each time, and things that have no tap of their own squeeze slightly while held, so the
 * press reads as going somewhere before the sheet arrives.
 */

/** One row of a [HoldMenu]. */
@Immutable
data class HoldAction(
    val label: String,
    val icon: ImageVector,
    /** A second line saying what the action does when the label alone could mislead. */
    val detail: String? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/** How long a press must last before a holdable squeezes: long enough to let a scroll start clean. */
private const val HOLD_FEEDBACK_DELAY_MS = 90L

/**
 * Hold on something that has no tap of its own, such as a user bubble.
 *
 * A screen reader gets the hold as a custom long-press action named [label]; there is no click, so
 * TalkBack does not offer a double-tap that would do nothing.
 */
fun Modifier.holdable(label: String, enabled: Boolean = true, onHold: () -> Unit): Modifier = composed {
    if (!enabled) return@composed Modifier
    val haptics = rememberDsHaptics()
    val currentOnHold by rememberUpdatedState(onHold)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = DsAnimations.pressScale,
        label = "holdScale",
    )
    Modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    // Waits out the first moments of a touch, so a drag that is really the start of a
                    // scroll does not make every bubble it passes flinch.
                    val released = withTimeoutOrNull(HOLD_FEEDBACK_DELAY_MS) { tryAwaitRelease() }
                    if (released == null) {
                        pressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                        }
                    }
                },
                onLongPress = {
                    haptics.longPress()
                    currentOnHold()
                },
            )
        }
        .semantics {
            onLongClick(label) {
                currentOnHold()
                true
            }
        }
}

/**
 * A tap and a hold on the same element: the row's own action on tap, its [HoldMenu] on hold.
 *
 * [interactionSource] is passed through for rows that drive a hover or press state of their own.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.clickableWithHold(
    holdLabel: String,
    onHold: () -> Unit,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val haptics = rememberDsHaptics()
    combinedClickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        enabled = enabled,
        role = role,
        onLongClickLabel = holdLabel,
        onLongClick = {
            haptics.longPress()
            onHold()
        },
        onClick = onClick,
    )
}

/**
 * The sheet a hold opens: what was held, then what can be done with it.
 *
 * [preview] repeats the held thing at the top, cut to three lines, so the reader can see the sheet
 * is about the message they meant and not the one beside it. Picking a row closes the sheet first
 * and then runs the action, so an action that opens a dialog of its own opens it over the screen
 * rather than over a sheet on its way out.
 */
@Composable
fun HoldMenu(
    onDismiss: () -> Unit,
    actions: List<HoldAction>,
    preview: String? = null,
    monospacePreview: Boolean = false,
) {
    val colors = DsTheme.colors
    DsBottomSheet(title = null, onDismiss = onDismiss, modifier = Modifier.testTag(HOLD_MENU_TAG)) {
        if (!preview.isNullOrBlank()) {
            Text(
                preview,
                style = if (monospacePreview) DsType.mono13 else DsType.small13,
                color = colors.labelSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DsShapes.block)
                    .background(colors.bgModulePlatform)
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            )
        }
        Column {
            actions.forEach { action ->
                HoldMenuRow(action) {
                    onDismiss()
                    action.onClick()
                }
            }
        }
    }
}

/** Test tag on every [HoldMenu], so instrumented tests can wait for the sheet. */
const val HOLD_MENU_TAG = "hold-menu"

@Composable
private fun HoldMenuRow(action: HoldAction, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val tint = if (action.danger) colors.error else colors.labelPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.row)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            action.icon,
            contentDescription = null,
            tint = if (action.danger) colors.error else colors.labelSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(DsSpacing.medium))
        Column(Modifier.weight(1f)) {
            Text(action.label, style = DsType.rowTitle, color = tint)
            action.detail?.let {
                Text(it, style = DsType.caption11, color = colors.labelTertiary)
            }
        }
    }
}
