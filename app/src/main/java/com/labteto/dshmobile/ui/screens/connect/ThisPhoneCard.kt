package com.labteto.dshmobile.ui.screens.connect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.termux.HarnessControl
import com.labteto.dshmobile.termux.HarnessControlFailure
import com.labteto.dshmobile.termux.StartSignIn
import com.labteto.dshmobile.termux.TermuxPaths
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.delay

// The "This phone" card and the Termux wording around it. Android only, because the harness it controls
// runs in Termux: it stays in :app while the rest of the Connect screen is shared, and the shared screen
// hosts it through the loopbackCard slot of ConnectContent.

/**
 * The harness on this device: what it is doing, and the one or two things to do about it.
 *
 * Four states drive it — down, up but not signed in, ready, or the port is someone else's — and each
 * offers only the action that changes it. With Termux on the phone the card can also start and stop
 * the harness, which is where the permission is asked for: on the first tap of Start, never on
 * launch. A start that finishes reports how the sign-in went, then clears itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThisPhoneCard(
    state: ConnectUiState,
    viewModel: ConnectViewModel,
    onRecordAttempt: (() -> Unit) -> Unit,
) {
    val controller = viewModel.controller
    val context = LocalContext.current
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val clipboard = LocalClipboardManager.current
    val status = state.loopback
    val control = state.harnessControl
    val busy = control.busy
    var portText by rememberSaveable { mutableStateOf(state.loopbackPort.toString()) }
    // The remembered port arrives after the settings load; the field follows it once, and is
    // otherwise left alone — a half-typed port is not a port the ViewModel knows about.
    LaunchedEffect(state.loopbackPort) {
        if (portText.toIntOrNull() != state.loopbackPort) portText = state.loopbackPort.toString()
    }
    // Which button opened the permission dialog. Saveable: the dialog can outlive a rotation, and
    // a Stop that comes back as a Start would be the opposite of what was tapped.
    var pendingStop by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        controller.onTermuxPermissionResult(granted, pendingStop)
        pendingStop = false
    }
    // A finished start says its piece and steps aside; nothing is waiting on a tap.
    LaunchedEffect(control) {
        if (control is HarnessControl.Started) {
            delay(STARTED_NOTICE_MS)
            controller.acknowledgeHarnessControl()
        }
    }
    val dot = when {
        busy -> StateDotState.Running
        else -> when (status) {
            LoopbackStatus.Unknown -> StateDotState.Running
            is LoopbackStatus.Down -> StateDotState.Idle
            LoopbackStatus.NeedsSignIn, is LoopbackStatus.Foreign -> StateDotState.Warning
            is LoopbackStatus.Ready -> StateDotState.Done
        }
    }

    InkCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateDot(dot, size = 8.dp)
            Spacer(Modifier.width(DsSpacing.compact))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.connect_loopback_title),
                    style = DsType.std14Strong,
                    color = colors.labelPrimary,
                )
                Text(
                    "127.0.0.1:${state.loopbackPort}",
                    style = DsType.mono12,
                    color = colors.labelTertiary,
                )
            }
            Spacer(Modifier.width(DsSpacing.compact))
            InkTextField(
                value = portText,
                onValueChange = { typed ->
                    portText = typed.filter { c -> c.isDigit() }
                    controller.setLoopbackPort(portText)
                },
                modifier = Modifier.width(96.dp),
                placeholder = stringResource(R.string.connect_port_hint),
                contentDescription = stringResource(R.string.connect_port_label),
                mono = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Text(
            loopbackStatusText(status, control, state.loopbackPort),
            style = DsType.std14,
            color = when {
                busy -> colors.labelTertiary
                control is HarnessControl.Started -> colors.success
                status is LoopbackStatus.Ready -> colors.success
                status is LoopbackStatus.NeedsSignIn || status is LoopbackStatus.Foreign -> colors.warnLabel
                else -> colors.labelTertiary
            },
        )
        loopbackHint(status, state)?.let { hint ->
            Text(hint, style = DsType.small13, color = colors.labelTertiary)
        }
        (control as? HarnessControl.Failed)?.let { failed ->
            HarnessControlFailureBlock(
                failure = failed.reason,
                onCopyCommand = { command ->
                    clipboard.setText(AnnotatedString(command))
                    haptics.confirm()
                },
                onAcknowledge = controller::acknowledgeHarnessControl,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            when (status) {
                is LoopbackStatus.Ready -> DsConnectButton(
                    text = stringResource(R.string.connect_button),
                    onClick = { onRecordAttempt(controller::connectLoopback) },
                    variant = ConnectButtonVariant.Primary,
                    enabled = !state.connecting && !busy,
                )
                LoopbackStatus.NeedsSignIn -> DsConnectButton(
                    text = stringResource(R.string.connect_sign_in),
                    onClick = controller::openLoopbackSignIn,
                    variant = ConnectButtonVariant.Primary,
                    enabled = !busy,
                )
                else -> Unit
            }
            if (state.termuxInstalled) {
                when (status) {
                    is LoopbackStatus.Down -> DsConnectButton(
                        text = stringResource(R.string.connect_loopback_start),
                        onClick = {
                            if (state.termuxPermission) {
                                controller.startHarness()
                            } else {
                                pendingStop = false
                                permissionLauncher.launch(TermuxPaths.PERMISSION)
                            }
                        },
                        variant = ConnectButtonVariant.Primary,
                        enabled = !busy,
                    )
                    LoopbackStatus.Unknown -> Unit
                    else -> DsConnectButton(
                        text = stringResource(R.string.connect_loopback_stop),
                        onClick = {
                            if (state.termuxPermission) {
                                controller.stopHarness()
                            } else {
                                pendingStop = true
                                permissionLauncher.launch(TermuxPaths.PERMISSION)
                            }
                        },
                        variant = ConnectButtonVariant.Outline,
                        enabled = !busy,
                    )
                }
                DsConnectButton(
                    text = stringResource(R.string.connect_loopback_open_termux),
                    onClick = {
                        viewModel.openTermuxIntent()?.let { intent ->
                            runCatching { context.startActivity(intent) }
                        }
                    },
                    variant = ConnectButtonVariant.Ghost,
                )
            }
        }
    }
}

/** The card's third line, when the state has something to say about what to do next. */
@Composable
private fun loopbackHint(status: LoopbackStatus, state: ConnectUiState): String? = when {
    state.harnessControl.busy || state.harnessControl is HarnessControl.Failed -> null
    status is LoopbackStatus.Down -> if (state.termuxInstalled) {
        stringResource(R.string.connect_loopback_down_hint, state.loopbackPort)
    } else {
        stringResource(R.string.connect_loopback_termux_missing)
    }
    status is LoopbackStatus.NeedsSignIn -> stringResource(R.string.connect_loopback_needs_sign_in_hint)
    else -> null
}

/**
 * Why a Termux command did not do what was asked, with the fix where there is one.
 *
 * Three of these are fixed by one line in Termux, so that line is shown verbatim with a Copy button
 * rather than described: it is a command, not prose, and it is not translated.
 */
@Composable
private fun HarnessControlFailureBlock(
    failure: HarnessControlFailure,
    onCopyCommand: (String) -> Unit,
    onAcknowledge: () -> Unit,
) {
    val colors = DsTheme.colors
    val message = harnessFailureMessage(failure)
    val command = harnessFailureCommand(failure)
    val detail = when (failure) {
        is HarnessControlFailure.Exited -> failure.tail
        is HarnessControlFailure.ReadyTimeout -> failure.tail
        is HarnessControlFailure.TermuxUnavailable -> failure.detail.orEmpty()
        else -> ""
    }.trim().ifBlank { null }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.buttonLarge,
        color = colors.errorTertiary,
        border = BorderStroke(1.dp, colors.errorBorder),
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(DsSpacing.xsmall))
                Text(
                    message,
                    style = DsType.small13,
                    color = colors.error,
                    modifier = Modifier.weight(1f),
                )
            }
            (command ?: detail)?.let { text ->
                Text(
                    text,
                    style = DsType.mdCode,
                    color = colors.error,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                if (command != null) {
                    DsConnectButton(
                        text = stringResource(R.string.common_copy),
                        onClick = { onCopyCommand(command) },
                        variant = ConnectButtonVariant.Ghost,
                    )
                }
                DsConnectButton(
                    text = stringResource(R.string.common_ok),
                    onClick = onAcknowledge,
                    variant = ConnectButtonVariant.Ghost,
                )
            }
        }
    }
}

/** One sentence per way a Termux command can fail; shared with the Settings card. */
@Composable
internal fun harnessFailureMessage(reason: HarnessControlFailure): String = when (reason) {
    HarnessControlFailure.NotInstalled -> stringResource(R.string.connect_loopback_termux_missing)
    HarnessControlFailure.PermissionDenied -> stringResource(R.string.connect_loopback_permission_denied)
    HarnessControlFailure.ExternalAppsDisabled -> stringResource(R.string.connect_loopback_external_apps)
    HarnessControlFailure.DshMissing -> stringResource(R.string.connect_loopback_dsh_missing)
    HarnessControlFailure.NodeMissing -> stringResource(R.string.connect_loopback_node_missing)
    is HarnessControlFailure.Exited -> stringResource(R.string.connect_loopback_exited)
    is HarnessControlFailure.ReadyTimeout -> stringResource(R.string.connect_loopback_ready_timeout)
    is HarnessControlFailure.TermuxUnavailable -> stringResource(R.string.connect_loopback_termux_unavailable)
    is HarnessControlFailure.InstallFailed -> stringResource(R.string.update_install_failed, reason.detail)
}

/** The Termux one-liner that fixes [reason], for the three failures one line does fix. */
internal fun harnessFailureCommand(reason: HarnessControlFailure): String? = when (reason) {
    HarnessControlFailure.ExternalAppsDisabled -> ENABLE_EXTERNAL_APPS_COMMAND
    HarnessControlFailure.DshMissing -> INSTALL_DSH_COMMAND
    HarnessControlFailure.NodeMissing -> INSTALL_NODE_COMMAND
    else -> null
}

/** The status line for a harness on this device; shared with the Settings card. */
@Composable
internal fun loopbackStatusText(status: LoopbackStatus, control: HarnessControl, port: Int): String = when (control) {
    is HarnessControl.Starting -> stringResource(R.string.connect_loopback_starting)
    is HarnessControl.Stopping -> stringResource(R.string.connect_loopback_stopping)
    is HarnessControl.Started -> when (control.signIn) {
        StartSignIn.SIGNED_IN -> stringResource(R.string.connect_loopback_started)
        StartSignIn.NO_TOKEN -> stringResource(R.string.connect_loopback_started_no_token)
        StartSignIn.REFUSED, StartSignIn.UNREACHABLE -> stringResource(R.string.connect_loopback_sign_in_failed)
    }
    else -> when (status) {
        LoopbackStatus.Unknown -> stringResource(R.string.connect_checking)
        is LoopbackStatus.Down -> stringResource(R.string.connect_loopback_down)
        LoopbackStatus.NeedsSignIn -> stringResource(R.string.connect_loopback_needs_sign_in)
        is LoopbackStatus.Ready -> status.description?.home?.let { stringResource(R.string.connect_harness_home, it) }
            ?: stringResource(R.string.connect_loopback_ready)
        is LoopbackStatus.Foreign -> stringResource(R.string.connect_loopback_foreign, port)
    }
}

/** How long a finished start stays on the card before it clears itself. */
private const val STARTED_NOTICE_MS = 5_000L

/** The one line that lets other apps run commands in Termux; `termux-reload-settings` applies it. */
private const val ENABLE_EXTERNAL_APPS_COMMAND =
    "mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings"

private const val INSTALL_NODE_COMMAND = "pkg install nodejs"

private const val INSTALL_DSH_COMMAND = "pkg install nodejs && npm install -g @deepseek-ai/dsh"
