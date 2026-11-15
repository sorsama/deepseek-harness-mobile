package com.labteto.dshmobile.ui.screens.runtime

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.runtime.BuiltInHarnessHost
import com.labteto.dshmobile.runtime.BuiltInHarnessState
import com.labteto.dshmobile.runtime.RuntimeAction
import com.labteto.dshmobile.runtime.RuntimeFailure
import com.labteto.dshmobile.runtime.RuntimeIssueKind
import com.labteto.dshmobile.runtime.RuntimeState
import com.labteto.dshmobile.runtime.SetupStep
import com.labteto.dshmobile.runtime.StepStatus
import com.labteto.dshmobile.runtime.failedStep
import com.labteto.dshmobile.runtime.formatBytes
import com.labteto.dshmobile.runtime.runtimeIssue
import com.labteto.dshmobile.runtime.setupProgress
import com.labteto.dshmobile.runtime.setupSteps
import com.labteto.dshmobile.termux.StartSignIn
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsFullScreenDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.connect.ConnectButtonVariant
import com.labteto.dshmobile.ui.screens.connect.DsConnectButton
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** What the page is showing; each has its own title, body and buttons. */
private enum class SetupPhase { Intro, Working, NoSpace, Failed, Done }

/**
 * The set-up page: from nothing to a running harness the app is signed in to.
 *
 * Download, check, unpack, start and sign in, as one step tracker. The active step carries the
 * numbers (bytes, speed and time left for the download, files for the unpack), and a finished step
 * settles with a check. The work belongs to [BuiltInHarnessHost], not to this page: closing it, or
 * leaving the app, changes nothing, and opening it again shows the step it has reached.
 *
 * It asks before downloading on a metered network, says "not enough space" with the numbers, and
 * asks once before a cancel, which leaves nothing half-installed. When the harness is up, the
 * Connect screen's own auto-connect connects, and this page steps aside as the connection opens.
 */
@Composable
internal fun RuntimeSetupPage(
    host: BuiltInHarnessHost,
    state: BuiltInHarnessState,
    connectStage: ConnectStage,
    onSetUp: () -> Unit,
    onConnect: () -> Unit,
    onAction: (RuntimeAction) -> Unit,
    onClose: () -> Unit,
    /** False where the page cannot connect (opened from Settings): the last button just closes it. */
    canConnect: Boolean = true,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val page = rememberPageVisibility(onClose)
    fun requestClose() = page.hide()

    val runtime = state.runtime
    val progress = setupProgress(state)
    val failure = (runtime as? RuntimeState.Failed)?.reason?.takeIf { !(state.settingUp && state.waitingForNetwork) }
        ?: state.releaseProblem?.takeIf { runtime == RuntimeState.NotInstalled && !state.settingUp && it !is RuntimeFailure.Network }
    val issue = failure?.let { runtimeIssue(it, installed = state.installed != null, port = 0) }
    val signedIn = (runtime as? RuntimeState.Running)?.signIn == StartSignIn.SIGNED_IN
    val phase = when {
        runtime is RuntimeState.Running -> SetupPhase.Done
        issue?.kind == RuntimeIssueKind.NoSpace -> SetupPhase.NoSpace
        issue != null -> SetupPhase.Failed
        progress != null || state.settingUp -> SetupPhase.Working
        else -> SetupPhase.Intro
    }

    // Felt as well as seen: the harness answering, or the set-up stopping on a failure.
    var lastPhase by rememberSaveable { mutableStateOf<SetupPhase?>(null) }
    LaunchedEffect(phase) {
        val before = lastPhase
        if (before != null && before != phase) {
            when (phase) {
                SetupPhase.Done -> haptics.confirm()
                SetupPhase.Failed, SetupPhase.NoSpace -> haptics.reject()
                else -> Unit
            }
        }
        lastPhase = phase
    }
    // The connection opening is the end of this page's job; it steps aside rather than cutting out.
    LaunchedEffect(connectStage) {
        if (connectStage != ConnectStage.Idle && runtime is RuntimeState.Running) requestClose()
    }
    LaunchedEffect(Unit) { host.refreshRelease() }

    var askMetered by remember { mutableStateOf(false) }
    var askCancel by remember { mutableStateOf(false) }
    val start = {
        if (host.isMetered()) askMetered = true else {
            haptics.confirm()
            onSetUp()
        }
    }

    DsFullScreenDialog(
        onDismissRequest = ::requestClose,
        visibleState = page.state,
        onWindowReady = page::show,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsIconButton(
                icon = Icons.Filled.Close,
                contentDescription = stringResource(R.string.common_close),
                onClick = ::requestClose,
                tint = colors.labelPrimary,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DsSpacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
                MascotMark(
                    size = 64.dp,
                    state = when (phase) {
                        SetupPhase.Intro -> MascotState.Idle
                        SetupPhase.Working -> if (progress?.waitingForNetwork == true) MascotState.Offline else MascotState.Working
                        SetupPhase.Done -> MascotState.Done
                        SetupPhase.Failed, SetupPhase.NoSpace -> MascotState.NeedsYou
                    },
                )
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = { fadeIn(DsAnimations.fade) togetherWith fadeOut(DsAnimations.fade) using SizeTransform(clip = false) { _, _ -> DsAnimations.expand } },
                    label = "setupHeading",
                ) { shown ->
                    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        BetaBadge()
                        Text(
                            stringResource(
                                when (shown) {
                                    SetupPhase.Intro -> R.string.runtime_setup_title
                                    SetupPhase.Working -> R.string.runtime_setup_working_title
                                    SetupPhase.NoSpace -> R.string.runtime_setup_no_space_title
                                    SetupPhase.Failed -> R.string.runtime_setup_failed_title
                                    SetupPhase.Done -> R.string.runtime_setup_done_title
                                },
                            ),
                            style = DsType.display24,
                            color = colors.labelPrimary,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            when (shown) {
                                SetupPhase.Intro -> stringResource(R.string.runtime_setup_intro)
                                SetupPhase.Working -> stringResource(R.string.runtime_setup_leave_hint)
                                SetupPhase.NoSpace, SetupPhase.Failed -> stringResource(R.string.runtime_setup_failed_body)
                                SetupPhase.Done -> stringResource(if (signedIn) R.string.runtime_setup_done_body else R.string.runtime_card_signed_out)
                            },
                            style = DsType.std14,
                            color = colors.labelSecondary,
                        )
                        if (shown == SetupPhase.Intro) {
                            Text(
                                stringResource(R.string.runtime_setup_beta_note),
                                style = DsType.small13,
                                color = colors.labelSecondary,
                            )
                            state.release?.let { release ->
                                Text(
                                    stringResource(R.string.runtime_card_sizes, formatBytes(release.downloadBytes), formatBytes(release.unpackedBytes)),
                                    style = DsType.small13,
                                    color = colors.labelTertiary,
                                )
                            }
                        }
                    }
                }

                if (phase == SetupPhase.NoSpace && issue != null) {
                    SpaceNumbers(needed = issue.neededBytes, available = issue.availableBytes)
                }

                StepTracker(
                    steps = when (phase) {
                        SetupPhase.Done -> setupSteps(null, finished = true)
                        SetupPhase.Failed, SetupPhase.NoSpace -> setupSteps(null, failedAt = failure?.let(::failedStep) ?: SetupStep.Download)
                        SetupPhase.Working -> setupSteps(progress?.step ?: SetupStep.Download)
                        // Installed: the first three steps are behind it, the start is next.
                        SetupPhase.Intro -> setupSteps(if (state.installed != null) SetupStep.Start else null)
                            .map { (step, status) -> step to if (status == StepStatus.Active) StepStatus.Pending else status }
                    },
                    progress = progress,
                    transferLine = rememberTransferLine(progress),
                )

                if (phase == SetupPhase.Failed && issue != null) {
                    RuntimeIssueBlock(issue = issue, onAction = onAction, showActions = false, showLog = true)
                }

                // The command choice, explained once, while the download runs: there is time to
                // read it then, and nothing waits on it (the default stands until it is changed).
                val explained by host.commandPermissionExplained.collectAsStateWithLifecycle()
                var choiceHere by rememberSaveable { mutableStateOf(false) }
                val choiceMoment = (phase == SetupPhase.Working || phase == SetupPhase.Done) && commandChoiceSupported(state)
                LaunchedEffect(choiceMoment, explained) {
                    if (choiceMoment && !explained) choiceHere = true
                }
                AnimatedVisibility(
                    visible = choiceHere && phase != SetupPhase.Failed && phase != SetupPhase.NoSpace,
                    enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
                    exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
                ) {
                    CommandChoiceBlock(
                        host = host,
                        state = state,
                        place = CommandChoicePlace.SetUp,
                        onRestart = host::restart,
                        onDone = { choiceHere = false },
                    )
                }

                // The one thing the harness needs besides itself: a model provider it can use. Asked
                // of the harness as soon as it is up and signed in, with the way to add a key.
                com.labteto.dshmobile.ui.screens.models.BuiltInModelsHint(
                    port = (runtime as? RuntimeState.Running)?.port ?: 0,
                    signedIn = phase == SetupPhase.Done && signedIn,
                )
                Spacer(Modifier.height(DsSpacing.large))
            }
        }

        // Pinned, inside the window's insets (DsFullScreenDialog pads for the bars).
        Column(
            Modifier.fillMaxWidth().background(colors.bgBase).padding(horizontal = DsSpacing.large, vertical = DsSpacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                when (phase) {
                    SetupPhase.Intro -> DsConnectButton(
                        // Installed already (a set-up that was left after the unpack): only the start is left.
                        text = if (state.installed != null) {
                            com.labteto.dshmobile.runtime.runtimeActionLabel(RuntimeAction.Start)
                        } else {
                            state.release?.let { stringResource(R.string.runtime_setup_start_sized, formatBytes(it.downloadBytes)) }
                                ?: stringResource(R.string.runtime_setup_start)
                        },
                        onClick = start,
                        large = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SetupPhase.Working -> DsConnectButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = { askCancel = true },
                        variant = ConnectButtonVariant.Outline,
                        large = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SetupPhase.NoSpace, SetupPhase.Failed -> issue?.let {
                        RuntimeActionRow(
                            primary = it.fix,
                            secondary = it.second ?: RuntimeAction.ShowLog.takeIf { _ -> it.logTail.isNotBlank() },
                            issue = it,
                            onAction = { action ->
                                if (action == RuntimeAction.RetryInstall) start() else onAction(action)
                            },
                        )
                    }
                    SetupPhase.Done -> if (!canConnect) {
                        DsConnectButton(
                            text = stringResource(R.string.common_done),
                            onClick = ::requestClose,
                            large = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        val connecting = connectStage != ConnectStage.Idle
                        DsConnectButton(
                            text = stringResource(if (connecting) R.string.runtime_setup_connecting else R.string.connect_button),
                            onClick = onConnect,
                            enabled = !connecting && signedIn,
                            large = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (askMetered) {
        DsDialog(
            title = stringResource(R.string.runtime_setup_metered_title), onDismiss = { askMetered = false },
            body = {
                Text(
                    stringResource(R.string.runtime_setup_metered_body, state.release?.let { formatBytes(it.downloadBytes) } ?: "150 MB"),
                    style = DsType.std14,
                    color = colors.labelSecondary,
                )
            },
            actions = {
                DialogButtons(
                    dismiss = stringResource(R.string.common_cancel),
                    confirm = stringResource(R.string.runtime_setup_metered_confirm),
                    onDismiss = { askMetered = false },
                    onConfirm = {
                        askMetered = false
                        haptics.confirm()
                        onSetUp()
                    },
                )
            },
        )
    }
    if (askCancel) {
        DsDialog(
            title = stringResource(R.string.runtime_setup_cancel_title), onDismiss = { askCancel = false },
            body = {
                Text(stringResource(R.string.runtime_setup_cancel_body), style = DsType.std14, color = colors.labelSecondary)
            },
            actions = {
                DialogButtons(
                    dismiss = stringResource(R.string.runtime_setup_keep_going),
                    confirm = stringResource(R.string.runtime_setup_cancel_confirm),
                    danger = true,
                    onDismiss = { askCancel = false },
                    onConfirm = {
                        askCancel = false
                        haptics.reject()
                        host.cancelSetUp()
                    },
                )
            },
        )
    }
}

/** The five steps in one raised block. */
@Composable
private fun StepTracker(steps: List<Pair<SetupStep, StepStatus>>, progress: com.labteto.dshmobile.runtime.SetupProgress?, transferLine: String?) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.bgLayer1)
            .border(1.dp, colors.borderL2, DsShapes.block)
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
    ) {
        steps.forEachIndexed { index, (step, status) ->
            SetupStepRow(
                step = step,
                status = status,
                index = index,
                progress = progress.takeIf { status == StepStatus.Active },
                transferLine = transferLine.takeIf { status == StepStatus.Active && step == SetupStep.Download },
                modifier = Modifier.padding(vertical = DsSpacing.small),
            )
        }
    }
}

/** Needed and free, side by side, for the not-enough-space state. */
@Composable
private fun SpaceNumbers(needed: Long, available: Long) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
        listOf(R.string.runtime_space_needed to needed, R.string.runtime_space_free to available).forEach { (label, bytes) ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(DsShapes.block)
                    .background(colors.bgLayer1)
                    .border(1.dp, colors.borderL2, DsShapes.block)
                    .padding(DsSpacing.medium),
            ) {
                Text(stringResource(label), style = DsType.label12, color = colors.labelTertiary)
                Text(formatBytes(bytes), style = DsType.large20, color = colors.labelPrimary)
            }
        }
    }
}

@Composable
internal fun HintPlate(text: String) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.buttonLarge,
        color = colors.bgLayer1,
        border = BorderStroke(1.dp, colors.borderL2),
    ) {
        Text(text, style = DsType.small13, color = colors.labelSecondary, modifier = Modifier.padding(DsSpacing.medium))
    }
}

/** A dialog's two buttons, end-aligned, the confirming one last. */
@Composable
internal fun DialogButtons(
    dismiss: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    danger: Boolean = false,
) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        DsConnectButton(text = dismiss, onClick = onDismiss, variant = ConnectButtonVariant.Ghost)
        DsConnectButton(text = confirm, onClick = onConfirm, variant = if (danger) ConnectButtonVariant.Danger else ConnectButtonVariant.Primary)
    }
}
