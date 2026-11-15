package com.labteto.dshmobile.ui.screens.runtime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.runtime.RuntimeAction
import com.labteto.dshmobile.runtime.RuntimeIssue
import com.labteto.dshmobile.runtime.SetupProgress
import com.labteto.dshmobile.runtime.SetupStep
import com.labteto.dshmobile.runtime.StepStatus
import com.labteto.dshmobile.runtime.TransferRate
import com.labteto.dshmobile.runtime.formatBytes
import com.labteto.dshmobile.runtime.runtimeActionLabel
import com.labteto.dshmobile.runtime.runtimeIssueText
import com.labteto.dshmobile.runtime.setupProgressDetail
import com.labteto.dshmobile.runtime.setupStepLabel
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.localizedCount
import com.labteto.dshmobile.ui.screens.connect.ConnectButtonVariant
import com.labteto.dshmobile.ui.screens.connect.DsConnectButton
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.delay

// The pieces the built-in harness's card, set-up page and control page share: the progress bar,
// one row of the step tracker, the failure block and the download speed line.

/**
 * A thin progress bar. [fraction] null is "working, nothing to measure yet": a short segment
 * sweeping across, which runs only while that is true. A known fraction follows the engine's
 * readings through [DsAnimations.progress], so it glides between them rather than jumping.
 */
@Composable
internal fun RuntimeProgressBar(fraction: Float?, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 6.dp) {
    val colors = DsTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shown by animateFloatAsState(fraction?.coerceIn(0f, 1f) ?: 0f, DsAnimations.progress, label = "runtimeProgress")
    val track = colors.bgLayer2
    val fill = colors.accent
    val semantics = if (fraction != null) Modifier.progressSemantics(fraction.coerceIn(0f, 1f)) else Modifier.progressSemantics()
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).then(semantics)) {
        if (fraction == null) {
            val transition = rememberInfiniteTransition(label = "runtimeSweep")
            val sweep by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
                label = "sweep",
            )
            Canvas(Modifier.fillMaxWidth().height(height)) {
                drawRect(track)
                val segment = size.width * 0.28f
                val x = -segment + (size.width + segment) * sweep
                val start = if (rtl) size.width - x - segment else x
                drawRoundRect(fill, Offset(start, 0f), Size(segment, size.height), CornerRadius(size.height / 2))
            }
        } else {
            Canvas(Modifier.fillMaxWidth().height(height)) {
                drawRect(track)
                val w = size.width * shown
                drawRoundRect(fill, Offset(if (rtl) size.width - w else 0f, 0f), Size(w, size.height), CornerRadius(size.height / 2))
            }
        }
    }
}

/**
 * Download speed and time left, sampled from [progress] while it is a download, refreshed once a
 * second so the numbers can be read rather than flicker.
 */
@Composable
internal fun rememberTransferLine(progress: SetupProgress?): String? {
    val rate = remember { TransferRate() }
    var line by remember { mutableStateOf<Pair<Long, Long?>?>(null) }
    val downloading = progress != null && progress.step == SetupStep.Download && progress.bytesTotal > 0 && !progress.waitingForNetwork
    LaunchedEffect(progress?.bytesDone, downloading) {
        if (downloading) rate.add(System.currentTimeMillis(), progress!!.bytesDone) else {
            rate.clear()
            line = null
        }
    }
    LaunchedEffect(downloading) {
        while (downloading) {
            val speed = rate.bytesPerSecond()
            line = speed?.let { it to rate.secondsLeft(progress!!.bytesTotal) }
            delay(1000)
        }
    }
    val (speed, left) = line ?: return null
    val speedText = stringResource(R.string.runtime_progress_speed, formatBytes(speed))
    return if (left != null && left > 0) speedText + "  ·  " + timeLeftText(left) else speedText
}

@Composable
private fun timeLeftText(seconds: Long): String = when {
    seconds >= 90 -> stringResource(R.string.runtime_progress_minutes_left, localizedCount(((seconds + 30) / 60).toInt()))
    else -> stringResource(R.string.runtime_progress_seconds_left, localizedCount(seconds.toInt().coerceAtLeast(1)))
}

/**
 * One row of the step tracker. The active row shows its detail and a bar; a finished row settles
 * with a check that pops in once; a failed row is marked in the error colour.
 */
@Composable
internal fun SetupStepRow(
    step: SetupStep,
    status: StepStatus,
    index: Int,
    progress: SetupProgress?,
    transferLine: String?,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val label = setupStepLabel(step)
    val active = status == StepStatus.Active
    val labelColor by animateColorAsState(
        when (status) {
            StepStatus.Pending -> colors.labelTertiary
            StepStatus.Failed -> colors.error
            else -> colors.labelPrimary
        },
        DsAnimations.tabSwapColor,
        label = "stepLabel",
    )
    val detail = if (active && progress != null) setupProgressDetail(progress) else null
    val stateText = when (status) {
        StepStatus.Done -> stringResource(R.string.runtime_step_done)
        StepStatus.Active -> stringResource(R.string.runtime_step_active)
        StepStatus.Failed -> stringResource(R.string.runtime_step_failed)
        StepStatus.Pending -> stringResource(R.string.runtime_step_pending)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = stateText
                if (active) liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        StepMarker(status = status, number = index + 1, modifier = Modifier.padding(top = 1.dp))
        // Not spaced: a hidden detail must not leave a gap under the label.
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(
                label,
                style = if (active) DsType.std14Strong else DsType.std14,
                color = labelColor,
            )
            AnimatedVisibility(
                visible = active,
                enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
                exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall), modifier = Modifier.padding(top = DsSpacing.small)) {
                    if (progress != null && (progress.step == SetupStep.Download || progress.step == SetupStep.Unpack)) {
                        RuntimeProgressBar(progress.fraction)
                    }
                    if (detail != null) {
                        Text(
                            detail,
                            style = DsType.small13,
                            color = if (progress?.waitingForNetwork == true) colors.warnLabel else colors.labelSecondary,
                        )
                    }
                    if (transferLine != null) {
                        Text(transferLine, style = DsType.xsmall12, color = colors.labelTertiary)
                    }
                }
            }
        }
    }
}

/** The circle at the start of a step row: a number, a ring while active, a check, or a cross. */
@Composable
private fun StepMarker(status: StepStatus, number: Int, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val fill by animateColorAsState(
        when (status) {
            StepStatus.Done -> colors.success
            StepStatus.Failed -> colors.error
            StepStatus.Active -> colors.accentTertiary
            StepStatus.Pending -> colors.bgLayer2
        },
        DsAnimations.tabSwapColor,
        label = "stepFill",
    )
    val ring = if (status == StepStatus.Active) colors.accent else colors.borderL2
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, if (status == StepStatus.Done || status == StepStatus.Failed) fill else ring, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            StepStatus.Done, StepStatus.Failed -> {
                // Pops in once when the step finishes: the moment worth a little motion.
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }
                AnimatedVisibility(visible = shown, enter = scaleIn(tween(240, easing = DsAnimations.inkOvershoot), initialScale = 0.4f) + fadeIn(DsAnimations.fade)) {
                    Icon(
                        if (status == StepStatus.Done) DsIcons.Check else DsIcons.Warning,
                        contentDescription = null,
                        tint = if (status == StepStatus.Done) colors.onAccent else colors.onError,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            else -> Text(
                localizedCount(number),
                style = DsType.caption11Strong,
                color = if (status == StepStatus.Active) colors.accent else colors.labelTertiary,
            )
        }
    }
}

/**
 * What went wrong and what fixes it: the sentence, the harness output when a start failed (a code
 * block, never translated), and the fix as buttons.
 */
@Composable
internal fun RuntimeIssueBlock(
    issue: RuntimeIssue,
    onAction: (RuntimeAction) -> Unit,
    modifier: Modifier = Modifier,
    showActions: Boolean = true,
    showLog: Boolean = false,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.buttonLarge,
        color = colors.errorTertiary,
        border = BorderStroke(1.dp, colors.errorBorder),
    ) {
        Column(Modifier.padding(DsSpacing.medium), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(DsIcons.Warning, contentDescription = null, tint = colors.error, modifier = Modifier.padding(top = 1.dp).size(16.dp))
                Spacer(Modifier.width(DsSpacing.small))
                Text(
                    runtimeIssueText(issue),
                    style = DsType.small13,
                    color = colors.error,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (showLog && issue.logTail.isNotBlank()) {
                Text(
                    issue.logTail.trim().lines().takeLast(8).joinToString("\n"),
                    style = DsType.mdCode,
                    color = colors.error,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showActions && (issue.fix != null || issue.second != null)) {
                RuntimeActionRow(primary = issue.fix, secondary = issue.second, issue = issue, onAction = onAction)
            }
        }
    }
}

/** A primary and a quieter secondary action, wrapping onto a second line when the labels are long. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun RuntimeActionRow(
    primary: RuntimeAction?,
    secondary: RuntimeAction?,
    issue: RuntimeIssue?,
    onAction: (RuntimeAction) -> Unit,
    enabled: Boolean = true,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        if (primary != null) {
            DsConnectButton(
                text = runtimeActionLabel(primary, issue),
                onClick = { onAction(primary) },
                variant = if (primary == RuntimeAction.Stop) ConnectButtonVariant.Outline else ConnectButtonVariant.Primary,
                enabled = enabled,
            )
        }
        if (secondary != null) {
            DsConnectButton(
                text = runtimeActionLabel(secondary, issue),
                onClick = { onAction(secondary) },
                variant = ConnectButtonVariant.Outline,
                enabled = enabled,
            )
        }
    }
}

/** The small accent plate that says a newer runtime is available. */
@Composable
internal fun UpdateBadge(onClick: () -> Unit) {
    val colors = DsTheme.colors
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = DsShapes.pill,
        color = colors.accentTertiary,
        border = com.labteto.dshmobile.ui.components.rememberFocusBorder(interaction) ?: BorderStroke(1.dp, colors.accentBorder),
        interactionSource = interaction,
        modifier = Modifier.heightIn(min = DsSpacing.touchTarget),
    ) {
        Box(Modifier.heightIn(min = DsSpacing.touchTarget).padding(horizontal = DsSpacing.compact), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.runtime_card_update),
                style = DsType.caption11Strong.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accent,
                maxLines = 1,
            )
        }
    }
}

/**
 * A full-screen page's rise and fall: it rises once its window is on screen ([show]) and calls
 * [onClosed] when it has finished sinking away after [hide]. Before it has been shown, the
 * transition state already reads "hidden and idle", so closing waits for a page that was shown.
 */
internal class PageVisibility(val state: androidx.compose.animation.core.MutableTransitionState<Boolean>) {
    var shown by mutableStateOf(false)
        private set
    var hiding by mutableStateOf(false)
        private set

    fun show() {
        shown = true
        state.targetState = true
    }

    fun hide() {
        hiding = true
        state.targetState = false
    }
}

@Composable
internal fun rememberPageVisibility(onClosed: () -> Unit): PageVisibility {
    val page = remember { PageVisibility(androidx.compose.animation.core.MutableTransitionState(false)) }
    val closed = androidx.compose.runtime.rememberUpdatedState(onClosed)
    val state = page.state
    LaunchedEffect(page.hiding, page.shown, state.currentState, state.isIdle) {
        // Dismissed before the window was ready: there is nothing to animate away.
        if (page.hiding && (!page.shown || !state.currentState && state.isIdle)) closed.value()
    }
    return page
}

/**
 * "Beta" next to the built-in harness's name: on the Connect card, the set-up page and the harness
 * page. The kit's resting tag (not a control) in the accent wash, so it reads as a label.
 */
@Composable
internal fun BetaBadge(modifier: Modifier = Modifier) {
    com.labteto.dshmobile.ui.components.DsPill(
        text = stringResource(R.string.runtime_beta_badge),
        selected = true,
        modifier = modifier,
    )
}
