package com.labteto.dshmobile.ui.screens.runtime

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.runtime.BuiltInCard
import com.labteto.dshmobile.runtime.BuiltInCardPhase
import com.labteto.dshmobile.runtime.BuiltInHarnessState
import com.labteto.dshmobile.runtime.RuntimeAction
import com.labteto.dshmobile.runtime.formatBytes
import com.labteto.dshmobile.runtime.setupProgressDetail
import com.labteto.dshmobile.runtime.setupStepLabel
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.connect.InkCard
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The built-in harness on the Connect screen's "This phone" mode: one state, one obvious action.
 *
 * The card's body cross-fades and resizes from one state to the next with the screen's own specs
 * ([DsAnimations.fade], [DsAnimations.expand]), so a start reads as the same card changing rather
 * than a new one replacing it. A set-up in progress is a compact step and bar; tapping it opens the
 * set-up page on that step.
 */
@Composable
internal fun BuiltInHarnessCard(
    card: BuiltInCard,
    builtIn: BuiltInHarnessState,
    port: Int,
    onAction: (RuntimeAction) -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val dot = when (card.phase) {
        BuiltInCardPhase.NotSetUp, BuiltInCardPhase.Stopped -> StateDotState.Idle
        BuiltInCardPhase.SettingUp, BuiltInCardPhase.Starting, BuiltInCardPhase.Stopping, BuiltInCardPhase.Updating -> StateDotState.Running
        BuiltInCardPhase.Running -> if (card.signedOut) StateDotState.Warning else StateDotState.Done
        BuiltInCardPhase.Failed -> StateDotState.Error
    }
    val installed = builtIn.installed
    InkCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StateDot(dot, size = 8.dp)
            Spacer(Modifier.width(DsSpacing.compact))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.runtime_card_title),
                        style = DsType.std14Strong,
                        color = colors.labelPrimary,
                        modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                    )
                    Spacer(Modifier.width(DsSpacing.small))
                    BetaBadge()
                }
                Text(
                    if (installed != null) {
                        stringResource(
                            R.string.runtime_card_address,
                            port.toString(),
                            com.labteto.dshmobile.ui.screens.main.technicalDisplay(installed.harnessVersion),
                        )
                    } else {
                        com.labteto.dshmobile.ui.screens.main.technicalDisplay("127.0.0.1:$port")
                    },
                    style = DsType.mono12,
                    color = colors.labelTertiary,
                )
            }
            AnimatedVisibility(card.updateAvailable, enter = fadeIn(DsAnimations.fade), exit = fadeOut(DsAnimations.fade)) {
                UpdateBadge(onClick = {
                    haptics.tap()
                    onAction(RuntimeAction.OpenControls)
                })
            }
        }

        AnimatedContent(
            targetState = card.bodyKey(),
            transitionSpec = {
                fadeIn(DsAnimations.fade) togetherWith fadeOut(DsAnimations.fade) using SizeTransform(clip = false) { _, _ -> DsAnimations.expand }
            },
            contentAlignment = Alignment.TopStart,
            label = "builtInBody",
        ) { key ->
            // The key only decides when to cross-fade; the content reads the live card, so progress
            // inside one state never restarts the fade.
            // The buttons belong to the state they act on, so they cross-fade with its words: a
            // Start must not appear while "Stopping the harness" is still fading out.
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                CardBody(phase = key.phase, card = card, builtIn = builtIn, port = port, onAction = onAction)
                if (key.phase == card.phase && card.phase != BuiltInCardPhase.SettingUp && (card.primary != null || card.secondary != null)) {
                    RuntimeActionRow(primary = card.primary, secondary = card.secondary, issue = card.issue, onAction = { action ->
                        haptics.tap()
                        onAction(action)
                    })
                }
            }
        }
    }
}

/** What decides a cross-fade of the card's body: the phase and the sentence it shows. */
private data class BodyKey(val phase: BuiltInCardPhase, val detail: Any?)

private fun BuiltInCard.bodyKey() = BodyKey(
    phase,
    when (phase) {
        BuiltInCardPhase.Failed -> issue?.kind
        BuiltInCardPhase.Running -> connected to signedOut
        else -> null
    },
)

@Composable
private fun CardBody(
    phase: BuiltInCardPhase,
    card: BuiltInCard,
    builtIn: BuiltInHarnessState,
    port: Int,
    onAction: (RuntimeAction) -> Unit,
) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        when (phase) {
            BuiltInCardPhase.NotSetUp -> {
                Text(stringResource(R.string.runtime_card_intro), style = DsType.std14, color = colors.labelSecondary)
                builtIn.release?.let { release ->
                    Text(
                        stringResource(R.string.runtime_card_sizes, formatBytes(release.downloadBytes), formatBytes(release.unpackedBytes)),
                        style = DsType.small13,
                        color = colors.labelTertiary,
                    )
                }
            }
            BuiltInCardPhase.SettingUp -> SettingUpBody(card, onOpen = { onAction(RuntimeAction.ShowProgress) })
            BuiltInCardPhase.Stopped -> Text(stringResource(R.string.runtime_card_stopped), style = DsType.std14, color = colors.labelTertiary)
            BuiltInCardPhase.Starting -> Text(stringResource(R.string.runtime_card_starting), style = DsType.std14, color = colors.labelTertiary)
            BuiltInCardPhase.Stopping -> Text(stringResource(R.string.runtime_card_stopping), style = DsType.std14, color = colors.labelTertiary)
            BuiltInCardPhase.Running -> {
                when {
                    card.connected -> Text(stringResource(R.string.common_connected), style = DsType.std14, color = colors.success)
                    card.signedOut -> Text(stringResource(R.string.runtime_card_signed_out), style = DsType.std14, color = colors.warnLabel)
                    else -> Text(stringResource(R.string.runtime_card_running, port.toString()), style = DsType.std14, color = colors.success)
                }
                if (card.rolledBack) {
                    Text(stringResource(R.string.runtime_card_rolled_back), style = DsType.small13, color = colors.labelTertiary)
                }
                // Whether it can answer yet: asked of the harness itself once the app is signed in.
                com.labteto.dshmobile.ui.screens.models.BuiltInModelsHint(
                    port = port,
                    signedIn = !card.signedOut,
                    modifier = Modifier.padding(top = DsSpacing.xsmall),
                )
            }
            BuiltInCardPhase.Updating -> {
                val progress = card.progress
                Text(stringResource(R.string.runtime_card_updating), style = DsType.std14, color = colors.labelSecondary)
                RuntimeProgressBar(progress?.fraction)
            }
            BuiltInCardPhase.Failed -> card.issue?.let { issue ->
                RuntimeIssueBlock(issue = issue, onAction = onAction, showActions = false)
            }
        }
    }
}

/** The compact form of the set-up: the step, a bar and the numbers; the whole block opens the page. */
@Composable
private fun SettingUpBody(card: BuiltInCard, onOpen: () -> Unit) {
    val colors = DsTheme.colors
    val progress = card.progress
    val label = stringResource(R.string.runtime_action_show_progress_label)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onOpen)
            .padding(vertical = DsSpacing.xsmall),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress?.let { setupStepLabel(it.step) } ?: stringResource(R.string.runtime_card_setting_up),
                style = DsType.std14Strong,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(DsIcons.ChevronRight, contentDescription = null, tint = colors.labelTertiary, modifier = Modifier.size(18.dp))
        }
        // Waiting for the network holds the bar where the download stopped.
        RuntimeProgressBar(progress?.fraction)
        if (progress != null) {
            Text(
                setupProgressDetail(progress),
                style = DsType.small13,
                color = if (progress.waitingForNetwork) colors.warnLabel else colors.labelTertiary,
            )
        }
    }
}

/**
 * "Use Termux instead": the Termux card, exactly as it was, behind one row. Open from the start for
 * anyone who already uses Termux from this app (see `termuxSectionStartsOpen`).
 */
@Composable
internal fun TermuxDisclosure(open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val rotation by animateFloatAsState(if (open) 180f else 0f, DsAnimations.chevron, label = "termuxChevron")
    val expanded = stringResource(R.string.runtime_state_expanded)
    val collapsed = stringResource(R.string.runtime_state_collapsed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.row)
                .clickable(role = Role.Button) {
                    haptics.tap()
                    onToggle()
                }
                .heightIn(min = DsSpacing.touchTarget)
                .padding(horizontal = DsSpacing.small)
                .semantics { stateDescription = if (open) expanded else collapsed },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.runtime_use_termux),
                style = DsType.std14,
                color = colors.labelSecondary,
                modifier = Modifier.weight(1f),
            )
            Icon(DsIcons.ExpandMore, contentDescription = null, tint = colors.labelTertiary, modifier = Modifier.size(20.dp).rotate(rotation))
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
            exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
        ) {
            Column(Modifier.padding(top = DsSpacing.small)) { content() }
        }
    }
}
