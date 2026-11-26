package com.labteto.dshmobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.labteto.dshmobile.ui.theme.DsAnimations

/**
 * Shows [content] for [value] while it is non-null, and animates it in and out.
 *
 * The point of this over a bare `AnimatedVisibility` is the exit: when the value goes to null the
 * last non-null one is held while the content leaves, so a dock or card shrinks away with what it
 * was showing instead of emptying out first and then collapsing.
 */
@Composable
fun <T : Any> AnimatedPresence(
    value: T?,
    modifier: Modifier = Modifier,
    enter: EnterTransition = DsAnimations.dockEnter,
    exit: ExitTransition = DsAnimations.dockExit,
    content: @Composable (T) -> Unit,
) {
    var last by remember { mutableStateOf(value) }
    SideEffect { if (value != null) last = value }
    val shown = value ?: last
    AnimatedVisibility(visible = value != null, modifier = modifier, enter = enter, exit = exit) {
        shown?.let { content(it) }
    }
}

/**
 * Shrinks the element slightly while [interactionSource] is pressed, the same press feedback
 * [DsButton] and [DsIconButton] give. For chips and cards that are clickable but not buttons.
 */
fun Modifier.pressScale(interactionSource: InteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = DsAnimations.pressScale,
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

// The haptic vocabulary lives in Haptics.kt, next to nothing else, so a screen that only wants
// touch feedback does not have to import the motion helpers too.
