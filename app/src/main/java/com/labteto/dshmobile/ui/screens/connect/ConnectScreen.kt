package com.labteto.dshmobile.ui.screens.connect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectMode
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.DiscoveredHost
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.termux.HarnessControl
import com.labteto.dshmobile.termux.HarnessControlFailure
import com.labteto.dshmobile.termux.StartSignIn
import com.labteto.dshmobile.termux.TermuxPaths
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.components.WhaleMark
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.delay

/**
 * Choose how to reach a harness, then reach one.
 *
 * The mode chooser is the first control on the screen because the paths are not variations of one
 * connection. This phone talks to a harness running on the device itself, in Termux or forwarded
 * over `adb reverse`, and nothing leaves the phone. Local network talks straight to a harness on
 * the Wi-Fi with no encryption, and is only safe on a network you trust. Relay talks to
 * `dsh-relay`, which holds this device to a token it was issued once and pins the key it answers
 * with — and works from outside the Wi-Fi. Nothing here, auto-connect included, ever connects the
 * way that was not picked.
 *
 * One rule shapes the layout: at most one paragraph of prose before something you can act on. The
 * first cut of relay mode opened with six lines of explanation across two blocks, then three empty
 * sections each saying a version of "you have no relay", and only then the button that was the
 * entire point of the screen.
 */
@Composable
fun ConnectScreen(
    onOpenSettings: () -> Unit,
    onPair: (prefillUrl: String?) -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    // Saveable: a rotation mid-connect used to wipe a hand-typed address.
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("3080") }
    val relayMode = state.mode == ConnectMode.RELAY
    val loopbackMode = state.mode == ConnectMode.LOOPBACK
    val paired = state.visibleHosts
    val context = LocalContext.current

    // The "this phone" watch lives exactly as long as the card is on screen in that mode. The
    // ViewModel is scoped to the activity, so it cannot tell when this screen went away, and a
    // poll nobody is looking at is two requests a tick for nothing.
    LifecycleResumeEffect(loopbackMode) {
        if (loopbackMode) {
            viewModel.refreshTermux()
            viewModel.startLoopbackWatch()
        }
        onPauseOrDispose { viewModel.stopLoopbackWatch() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DsSpacing.xlarge, vertical = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DsIconButton(
                    icon = FeatherIcons.Tool,
                    contentDescription = stringResource(R.string.settings_title),
                    onClick = onOpenSettings,
                    tint = colors.labelTertiary,
                )
            }

            ConnectHeader()

            // ---- How to connect ----------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                SectionHeader(stringResource(R.string.connect_mode_title))
                DsSegmented(
                    segments = listOf(
                        DsSegment(ConnectMode.LOOPBACK, stringResource(R.string.connect_mode_loopback)),
                        DsSegment(ConnectMode.LAN, stringResource(R.string.connect_mode_lan)),
                        DsSegment(ConnectMode.RELAY, stringResource(R.string.connect_mode_relay)),
                    ),
                    selectedKey = state.mode,
                    onSelect = viewModel::setMode,
                    role = Role.Tab,
                    // Three parts of one decision, so each gets a third of the track. Hugging their
                    // labels left part of a full-width pill empty and made the thing look unfinished.
                    stretch = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // One notice, not two. What the mode is and what it costs you are the same thought,
                // and the tint carries the difference between them: local network is a warning,
                // this phone and relay are statements of fact.
                val warn = !relayMode && !loopbackMode
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (warn) colors.warnTertiary else colors.hoverSolid,
                ) {
                    Text(
                        stringResource(
                            when {
                                loopbackMode -> R.string.connect_mode_loopback_hint
                                relayMode -> R.string.connect_mode_relay_hint
                                else -> R.string.connect_mode_lan_hint
                            },
                        ),
                        style = DsType.small13,
                        color = if (warn) colors.warnLabel else colors.labelTertiary,
                        modifier = Modifier.padding(DsSpacing.medium),
                    )
                }
            }

            // ---- This phone --------------------------------------------------
            if (loopbackMode) {
                ThisPhoneCard(
                    state = state,
                    onPortChange = viewModel::setLoopbackPort,
                    onConnect = viewModel::connectLoopback,
                    onSignIn = viewModel::openLoopbackSignIn,
                    onStart = viewModel::startHarness,
                    onStop = viewModel::stopHarness,
                    onPermissionResult = viewModel::onTermuxPermissionResult,
                    onOpenTermux = {
                        viewModel.openTermuxIntent()?.let { intent -> runCatching { context.startActivity(intent) } }
                    },
                    onAcknowledge = viewModel::acknowledgeHarnessControl,
                )
            }

            // ---- Recent ------------------------------------------------------
            // Hidden entirely when empty in relay mode: the pairing card below already says there
            // is nothing here, and saying it twice is what made the screen read as three dead ends.
            // This phone has exactly one endpoint and the card above is it.
            if (!loopbackMode && (paired.isNotEmpty() || !relayMode)) {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    SectionHeader(
                        stringResource(
                            if (relayMode) R.string.connect_relay_remembered else R.string.connect_remembered,
                        ),
                    )
                    if (paired.isEmpty()) {
                        Text(
                            stringResource(R.string.connect_remembered_empty),
                            style = DsType.std14,
                            color = colors.labelCaption,
                        )
                    } else {
                        paired.forEach { saved ->
                            RecentHarnessCard(
                                host = saved,
                                probe = state.recentStatus[saved.authority],
                                onConnect = { viewModel.connectTo(saved) },
                                onForget = { viewModel.forget(saved) },
                            )
                        }
                    }
                }
            }

            // ---- Discovered --------------------------------------------------
            // Nothing to sweep for on this phone: the harness is at one address or it is not.
            if (!loopbackMode) Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                SectionHeader(
                    title = stringResource(
                        if (relayMode) R.string.connect_relay_discovered else R.string.connect_discovered,
                    ),
                    action = stringResource(
                        if (relayMode) R.string.connect_relay_scan else R.string.connect_scan,
                    ),
                    onAction = { viewModel.scan() },
                )
                val unknown = state.unknownDiscovered
                // Results and progress coexist: the sweep streams, so a host found in the first
                // batch belongs on screen while the rest of the subnet is still being knocked.
                if (state.scanning) {
                    ScanProgressRow(state.scanProgress) { viewModel.cancelScan() }
                }
                if (unknown.isEmpty()) {
                    // In relay mode the pairing card carries the instruction, so the empty line here
                    // would only be a third way of saying the same thing.
                    if (!state.scanning && !relayMode) {
                        Text(
                            stringResource(R.string.connect_discovered_hint),
                            style = DsType.std14,
                            color = colors.labelCaption,
                        )
                    }
                } else {
                    unknown.forEach { found ->
                        DiscoveredHarnessCard(found) {
                            // A relay will not answer `/api` to a device it has never seen, so
                            // "Connect" on one of these cards could only ever produce a 403. It
                            // carries the address into pairing instead.
                            if (found.isRelay) onPair(found.baseUrl) else viewModel.connectDiscovered(found)
                        }
                    }
                }
            }

            if (relayMode) {
                PairCallToAction(hasPaired = paired.isNotEmpty()) { onPair(null) }
            } else if (!loopbackMode) {
                // ---- Manual --------------------------------------------------
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    SectionHeader(stringResource(R.string.connect_manual_title))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = host,
                            onValueChange = { host = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(stringResource(R.string.connect_host_hint), style = DsType.std14)
                            },
                            singleLine = true,
                            label = { Text(stringResource(R.string.connect_host_label)) },
                            colors = connectFieldColors(),
                        )
                        Spacer(Modifier.width(DsSpacing.compact))
                        TextField(
                            value = port,
                            onValueChange = { port = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.width(92.dp),
                            singleLine = true,
                            label = { Text(stringResource(R.string.connect_port_label)) },
                            colors = connectFieldColors(),
                        )
                    }
                    DsButton(
                        text = stringResource(R.string.connect_button),
                        onClick = { viewModel.connectManual(host, port) },
                        enabled = !state.connecting,
                        variant = DsButtonVariant.Info,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Progress and failure are shared: an attempt reports the same way whichever mode
            // started it, and duplicating the block per mode is how the two drift apart.
            if (state.connecting) ConnectProgressRow(state.stage, state.attempted)
            state.failure?.let { failure ->
                ConnectFailureBlock(
                    failure = failure,
                    attempted = state.attempted,
                    retrying = state.retrying,
                    onCancel = viewModel::cancelConnect,
                    onPair = { onPair(state.attemptedBaseUrl) },
                    onSignIn = { viewModel.setSignInOpen(true) },
                )
            }

            if (state.signInOpen) {
                LaunchTokenDialog(
                    signingIn = state.signingIn,
                    error = state.signInError,
                    onDismiss = { viewModel.setSignInOpen(false) },
                    onSubmit = viewModel::signIn,
                )
            }

            // ---- Auto-connect ------------------------------------------------
            Column {
                SectionHeader(stringResource(R.string.connect_auto_title))
                ToggleRow(
                    label = stringResource(R.string.connect_auto_last),
                    checked = state.autoConnectLast,
                ) { viewModel.setAuto("last", !state.autoConnectLast) }
                when {
                    relayMode -> ToggleRow(
                        label = stringResource(R.string.connect_auto_relay),
                        checked = state.autoConnectRelay,
                    ) { viewModel.setAuto("relay", !state.autoConnectRelay) }
                    loopbackMode -> {
                        ToggleRow(
                            label = stringResource(R.string.connect_auto_loopback),
                            checked = state.autoConnectLoopback,
                        ) { viewModel.setAuto("loopback", !state.autoConnectLoopback) }
                        if (state.termuxInstalled) {
                            ToggleRow(
                                label = stringResource(R.string.connect_auto_start_loopback),
                                checked = state.autoStartLoopbackHarness,
                                hint = stringResource(R.string.connect_auto_start_loopback_hint),
                            ) { viewModel.setAuto("autoStart", !state.autoStartLoopbackHarness) }
                        }
                    }
                    else -> ToggleRow(
                        label = stringResource(R.string.connect_auto_lan),
                        checked = state.autoConnectLan,
                    ) { viewModel.setAuto("lan", !state.autoConnectLan) }
                }
            }

            Spacer(Modifier.height(DsSpacing.large))
        }
    }
}

/**
 * The screen's own masthead.
 *
 * Deliberately not [EmptyHero], which is the *chat's* empty state: it carries a "Preview" pill that
 * means nothing here, centres 32dp of padding around a 64dp mark, and pushed the mode chooser and
 * the one button on this screen below the fold on a normal phone.
 */
@Composable
private fun ConnectHeader() {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        WhaleMark(Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_long_name),
                style = DsType.large20,
                color = colors.labelPrimary,
            )
            Text(
                stringResource(R.string.connect_subtitle),
                style = DsType.small13,
                color = colors.labelTertiary,
            )
        }
    }
}

/**
 * The harness on this device: what it is doing, and the one or two things to do about it.
 *
 * Four states drive it — down, up but not signed in, ready, or the port is someone else's — and
 * each offers only the action that changes it. With Termux on the phone the card can also start
 * and stop the harness, which is where the permission is asked for: on the first tap of Start,
 * never on launch. A start that finishes reports how the sign-in went, then clears itself.
 */
@Composable
private fun ThisPhoneCard(
    state: ConnectUiState,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onSignIn: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    onOpenTermux: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    val colors = DsTheme.colors
    val status = state.loopback
    val control = state.harnessControl
    val busy = control.busy
    var portText by rememberSaveable { mutableStateOf(state.loopbackPort.toString()) }
    // The remembered port arrives after the settings load; the field follows it once, and is
    // otherwise left alone — a half-typed port is not a port the ViewModel knows about.
    LaunchedEffect(state.loopbackPort) {
        if (portText.toIntOrNull() != state.loopbackPort) portText = state.loopbackPort.toString()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        onPermissionResult,
    )
    // A finished start says its piece and steps aside; nothing is waiting on a tap.
    LaunchedEffect(control) {
        if (control is HarnessControl.Started) {
            delay(STARTED_NOTICE_MS)
            onAcknowledge()
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

    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
            Spacer(Modifier.width(DsSpacing.compact))
            TextField(
                value = portText,
                onValueChange = { typed ->
                    portText = typed.filter { c -> c.isDigit() }
                    onPortChange(portText)
                },
                modifier = Modifier.width(92.dp),
                singleLine = true,
                enabled = !busy,
                label = { Text(stringResource(R.string.connect_port_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = connectFieldColors(),
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
            HarnessControlFailureBlock(failed.reason, onAcknowledge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.compact)) {
            when (status) {
                is LoopbackStatus.Ready -> DsButton(
                    text = stringResource(R.string.connect_button),
                    onClick = onConnect,
                    enabled = !state.connecting && !busy,
                    variant = DsButtonVariant.Info,
                    size = DsButtonSize.Small,
                )
                LoopbackStatus.NeedsSignIn -> DsButton(
                    text = stringResource(R.string.connect_sign_in),
                    onClick = onSignIn,
                    enabled = !busy,
                    variant = DsButtonVariant.Info,
                    size = DsButtonSize.Small,
                )
                else -> Unit
            }
            if (state.termuxInstalled) {
                when (status) {
                    is LoopbackStatus.Down -> DsButton(
                        text = stringResource(R.string.connect_loopback_start),
                        onClick = {
                            if (state.termuxPermission) onStart() else permissionLauncher.launch(TermuxPaths.PERMISSION)
                        },
                        enabled = !busy,
                        variant = DsButtonVariant.Info,
                        size = DsButtonSize.Small,
                    )
                    LoopbackStatus.Unknown -> Unit
                    else -> DsButton(
                        text = stringResource(R.string.connect_loopback_stop),
                        onClick = onStop,
                        enabled = !busy,
                        variant = DsButtonVariant.Outline,
                        size = DsButtonSize.Small,
                    )
                }
                DsButton(
                    text = stringResource(R.string.connect_loopback_open_termux),
                    onClick = onOpenTermux,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
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
 * Three of these are fixed by one line in Termux, so that line is shown verbatim with a Copy
 * button rather than described: it is a command, not prose, and it is not translated.
 */
@Composable
private fun HarnessControlFailureBlock(reason: HarnessControlFailure, onAcknowledge: () -> Unit) {
    val colors = DsTheme.colors
    val clipboard = LocalClipboardManager.current
    val message = harnessFailureMessage(reason)
    val command = harnessFailureCommand(reason)
    val detail = when (reason) {
        is HarnessControlFailure.Exited -> reason.tail
        is HarnessControlFailure.ReadyTimeout -> reason.tail
        is HarnessControlFailure.TermuxUnavailable -> reason.detail.orEmpty()
        else -> ""
    }.trim().ifBlank { null }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.warnTertiary,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(StateDotState.Error, size = 8.dp)
                Spacer(Modifier.width(DsSpacing.xsmall))
                Text(message, style = DsType.small13, color = colors.warnLabel, modifier = Modifier.weight(1f))
            }
            (command ?: detail)?.let { text ->
                Text(
                    text,
                    style = DsType.mdCode,
                    color = colors.warnLabel,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.compact)) {
                if (command != null) {
                    DsButton(
                        text = stringResource(R.string.common_copy),
                        onClick = { clipboard.setText(AnnotatedString(command)) },
                        variant = DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                }
                DsButton(
                    text = stringResource(R.string.common_ok),
                    onClick = onAcknowledge,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
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

/**
 * The one thing this screen exists to offer, weighted by whether it has been done yet.
 *
 * With nothing paired it is the primary action and carries the instruction, because a relay cannot
 * be discovered into existence — someone has to open a page on the computer. Once a relay is
 * paired it steps back to a ghost button: still reachable, no longer the point.
 */
@Composable
private fun PairCallToAction(hasPaired: Boolean, onPair: () -> Unit) {
    val colors = DsTheme.colors
    if (hasPaired) {
        DsButton(
            text = stringResource(R.string.connect_relay_pair_another),
            onClick = onPair,
            variant = DsButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text(
            stringResource(R.string.connect_relay_pair_title),
            style = DsType.std14Strong,
            color = colors.labelPrimary,
        )
        Text(
            stringResource(R.string.connect_relay_pair_hint),
            style = DsType.small13,
            color = colors.labelTertiary,
        )
        DsButton(
            text = stringResource(R.string.connect_relay_pair_action),
            onClick = onPair,
            variant = DsButtonVariant.Info,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun connectFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = DsTheme.colors.bgLayer1,
    unfocusedContainerColor = DsTheme.colors.bgLayer1,
    focusedIndicatorColor = DsTheme.colors.accent,
    unfocusedIndicatorColor = DsTheme.colors.borderL2,
    cursorColor = DsTheme.colors.accent,
)

@Composable
private fun RecentHarnessCard(
    host: HostConfig,
    probe: HostProbe?,
    onConnect: () -> Unit,
    onForget: () -> Unit,
) {
    val colors = DsTheme.colors
    val reachable = probe as? HostProbe.Reachable
    val title = if (host.isLoopback) stringResource(R.string.connect_same_device) else host.name
    // 0.1.2 publishes only the host home. The harness version, its working directory and its
    // attached-session count all came from `host.describe`, which no longer exists.
    val home = reachable?.description?.home ?: host.lastHome

    DsCard(onClick = onConnect) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(
                when (probe) {
                    is HostProbe.Reachable -> StateDotState.Done
                    HostProbe.Probing -> StateDotState.Running
                    HostProbe.Unreachable -> StateDotState.Idle
                    null -> StateDotState.Idle
                },
                size = 8.dp,
            )
            Spacer(Modifier.width(DsSpacing.compact))
            Text(
                title,
                style = DsType.std14Strong,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (host.lastConnectedAt > 0L) {
                    relativeTime(host.lastConnectedAt)
                } else {
                    stringResource(R.string.connect_never)
                },
                style = DsType.caption11,
                color = colors.labelCaption,
                maxLines = 1,
            )
        }
        Text(
            listOfNotNull(
                host.displayAddress,
                // Named on the card rather than only on the pairing screen: whether this connection
                // is encrypted is a standing property of the endpoint, not a one-off notice.
                if (host.isRelay) {
                    stringResource(
                        if (host.isPlaintext) R.string.connect_relay_plaintext else R.string.connect_relay_encrypted,
                    )
                } else {
                    null
                },
                home?.let { basename(it) },
            ).joinToString(" · "),
            style = DsType.caption11,
            color = colors.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                statusLine(probe, home),
                style = DsType.caption11,
                color = if (probe is HostProbe.Unreachable) colors.labelCaption else colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DsButton(
                text = stringResource(R.string.common_delete),
                onClick = onForget,
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
    }
}

/**
 * The third line: what the harness is, or why it has nothing to say.
 *
 * Through 0.1.1 this named the harness version and its attached-session count, both from
 * `host.describe`. Neither is published any more, so a reachable host shows the home directory it
 * reported — the only host fact 0.1.2 carries — and says so plainly when even that is unknown.
 */
@Composable
private fun statusLine(probe: HostProbe?, home: String?): String = when {
    probe is HostProbe.Probing -> stringResource(R.string.connect_checking)
    probe is HostProbe.Unreachable -> stringResource(R.string.connect_unreachable)
    home != null -> stringResource(R.string.connect_harness_home, home)
    probe is HostProbe.Reachable -> stringResource(R.string.connect_reachable)
    else -> stringResource(R.string.common_loading)
}

/**
 * One sweep result.
 *
 * A harness whose trust fence refused us is still shown. It is the single most recoverable outcome
 * the scan can produce — the harness is running, on the right port, one `--trusted-host` away — and
 * reporting it as "nothing found" sends people looking for a fault that is not there.
 */
@Composable
private fun DiscoveredHarnessCard(found: DiscoveredHost, onConnect: () -> Unit) {
    val colors = DsTheme.colors
    val description = found.description
    // A relay is a find in its own right even though it says nothing about itself: `/relay/health`
    // is all an unpaired device may ask, and the card's job is to carry the address into pairing.
    if (found.isRelay) {
        DiscoveredRelayCard(found, onConnect)
        return
    }
    DsCard(onClick = if (description != null) onConnect else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                FeatherIcons.Globe,
                contentDescription = null,
                tint = if (description != null) colors.accent else colors.warn,
                modifier = Modifier.width(14.dp),
            )
            Spacer(Modifier.width(DsSpacing.compact))
            Text(
                found.authority,
                style = DsType.std14Strong,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (description != null) {
                DsButton(
                    text = stringResource(R.string.connect_button),
                    onClick = onConnect,
                    variant = DsButtonVariant.Info,
                    size = DsButtonSize.Small,
                )
            } else {
                DsPill(text = stringResource(R.string.connect_found_untrusted), warn = true)
            }
        }
        if (description != null) {
            Text(
                basename(description.home).takeIf { it.isNotBlank() }.orEmpty(),
                style = DsType.caption11,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                stringResource(R.string.connect_found_untrusted_hint),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
    }
}

/**
 * One relay found by its advertisement, or by knocking the ports a relay uses.
 *
 * Deliberately quiet about the harness behind it. Until this device pairs, the relay answers 403 to
 * everything but its own liveness probe — so there is no version, no cwd and no session count to
 * show, and inventing a Connect button that could only fail would be worse than none.
 */
@Composable
private fun DiscoveredRelayCard(found: DiscoveredHost, onPair: () -> Unit) {
    val colors = DsTheme.colors
    DsCard(onClick = if (found.hostRefused) null else onPair) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                FeatherIcons.Globe,
                contentDescription = null,
                tint = if (found.hostRefused) colors.warn else colors.accent,
                modifier = Modifier.width(14.dp),
            )
            Spacer(Modifier.width(DsSpacing.compact))
            Text(
                found.authority,
                style = DsType.std14Strong,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!found.hostRefused) {
                DsButton(
                    text = stringResource(R.string.connect_relay_pair_this),
                    onClick = onPair,
                    variant = DsButtonVariant.Info,
                    size = DsButtonSize.Small,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DsPill(
                text = stringResource(
                    when {
                        found.hostRefused -> R.string.connect_relay_refused
                        found.fingerprint != null -> R.string.connect_relay_encrypted
                        else -> R.string.connect_relay_plaintext
                    },
                ),
                warn = found.hostRefused || found.fingerprint == null,
            )
            Spacer(Modifier.width(DsSpacing.compact))
            Text(
                stringResource(R.string.connect_relay_unpaired),
                style = DsType.caption11,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The most recoverable outcome a relay scan can produce, and the one the phone cannot fix:
        // the relay is running and answering, and only its operator can widen the addresses it
        // answers to. Naming the address is the whole of the instruction.
        if (found.hostRefused) {
            Text(
                stringResource(R.string.connect_relay_refused_hint, found.authority),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
    }
}

/**
 * What the connect attempt is doing, named.
 *
 * A greyed-out button is the same picture whether the handshake is a second from finishing or the
 * packets are being dropped by a firewall. Naming the stage costs one line and turns a wait into a
 * progress report — and when it stops, the stage it stopped on is itself a clue.
 */
@Composable
private fun ConnectProgressRow(stage: ConnectStage, attempted: String?) {
    val colors = DsTheme.colors
    val label = when (stage) {
        ConnectStage.Validating -> stringResource(R.string.connect_stage_validating)
        ConnectStage.Reaching -> stringResource(R.string.connect_stage_reaching, attempted.orEmpty())
        ConnectStage.OpeningStreams -> stringResource(R.string.connect_stage_streams)
        ConnectStage.Verifying -> stringResource(R.string.connect_stage_verifying)
        ConnectStage.Connected -> stringResource(R.string.connect_stage_connected)
        ConnectStage.Idle -> return
    }
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(StateDotState.Running, size = 8.dp)
            Spacer(Modifier.width(DsSpacing.xsmall))
            Text(label, style = DsType.std14, color = colors.labelTertiary)
        }
        LinearProgressIndicator(
            progress = { stage.ordinal / (ConnectStage.entries.size - 1).toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = colors.accent,
            trackColor = colors.hoverSolid,
        )
    }
}

/**
 * Why it did not connect, and what to do about it.
 *
 * Deliberately one sentence of cause and one of action, with no commands: the device that failed is
 * the phone, and the fix almost always happens on the computer. `harness/README.md` carries the
 * PowerShell.
 */
@Composable
private fun ConnectFailureBlock(
    failure: ConnectFailure,
    attempted: String?,
    retrying: Boolean,
    onCancel: () -> Unit,
    onPair: () -> Unit,
    onSignIn: () -> Unit,
) {
    val colors = DsTheme.colors
    val authority = attempted.orEmpty()
    val port = authority.substringAfterLast(':', "").toIntOrNull() ?: 0
    // `connect_failed` is formatted from the two halves so it reads as one address; feeding it the
    // whole authority plus an empty port left a trailing colon. Blank means there was nothing to
    // attempt (bad input), and a headline naming no address would say nothing.
    val title = when {
        failure is ConnectFailure.TrustFence -> stringResource(R.string.connect_fail_fence_title)
        failure is ConnectFailure.PairingRequired -> stringResource(R.string.connect_fail_pairing_title)
        failure is ConnectFailure.CertificateChanged -> stringResource(R.string.connect_fail_certificate_title)
        authority.isBlank() -> null
        else -> stringResource(
            R.string.connect_failed,
            authority.substringBeforeLast(':', authority),
            port.toString(),
        )
    }
    val body = when (failure) {
        ConnectFailure.InvalidInput -> stringResource(R.string.connect_fail_invalid)
        is ConnectFailure.DifferentSubnet -> stringResource(
            R.string.connect_fail_subnet,
            authority,
            failure.localPrefix ?: stringResource(R.string.connect_unreachable),
        )
        ConnectFailure.Timeout -> stringResource(R.string.connect_fail_timeout, authority, port)
        ConnectFailure.Refused -> stringResource(R.string.connect_fail_refused, authority)
        ConnectFailure.TrustFence -> stringResource(R.string.connect_failed_fence)
        ConnectFailure.Unauthenticated -> stringResource(R.string.connect_fail_unauthenticated, authority)
        ConnectFailure.PairingRequired -> stringResource(R.string.connect_fail_pairing)
        ConnectFailure.CertificateChanged -> stringResource(R.string.connect_fail_certificate, authority)
        ConnectFailure.DnsFailure -> stringResource(R.string.connect_fail_dns, authority)
        ConnectFailure.NotAHarness -> stringResource(R.string.connect_fail_not_harness, authority)
        ConnectFailure.TlsFailure -> stringResource(R.string.connect_fail_tls, authority)
        ConnectFailure.StreamsBlocked -> stringResource(R.string.connect_fail_streams, authority)
        is ConnectFailure.Other -> stringResource(R.string.connect_fail_other, authority, failure.detail)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.warnTertiary,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(StateDotState.Error, size = 8.dp)
                Spacer(Modifier.width(DsSpacing.xsmall))
                Text(
                    title ?: body,
                    style = DsType.std14,
                    color = colors.warnLabel,
                )
            }
            // With no headline the body has already been shown beside the dot.
            if (title != null) Text(body, style = DsType.small13, color = colors.warnLabel)
            // A relay that will not accept this device is not something to retry into: the loop
            // has already been stopped, and the only move left is on the relay's pairing page.
            if (failure is ConnectFailure.PairingRequired || failure is ConnectFailure.CertificateChanged) {
                DsButton(
                    text = stringResource(R.string.connect_pair_again),
                    onClick = onPair,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
            // A harness that has not signed this phone in is fixed here rather than on the
            // harness: it wants a launch token, and retrying without one only repeats the 401.
            if (failure is ConnectFailure.Unauthenticated) {
                DsButton(
                    text = stringResource(R.string.connect_sign_in),
                    onClick = onSignIn,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
            // The loop backs off and retries forever; without this there is no way to stop it.
            if (retrying) {
                DsButton(
                    text = stringResource(R.string.connect_cancel),
                    onClick = onCancel,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
        }
    }
}

/**
 * The launch-token prompt.
 *
 * Harness 0.1.2 signs a browser in by exchanging a token it prints once per process, and accepts
 * that token only on its index route — so there is no way for a connection attempt to do this on
 * its own, and no way to skip it. The field takes the whole startup line as readily as the bare
 * token, because that is what people actually copy.
 */
@Composable
private fun LaunchTokenDialog(
    signingIn: Boolean,
    error: SignInError?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val colors = DsTheme.colors
    var input by remember { mutableStateOf("") }
    DsDialog(title = stringResource(R.string.connect_sign_in_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.connect_sign_in_body),
            style = DsType.small13,
            color = colors.labelSecondary,
        )
        TextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            enabled = !signingIn,
            label = { Text(stringResource(R.string.connect_sign_in_label)) },
            colors = connectFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        val message = when (error) {
            SignInError.Refused -> stringResource(R.string.connect_sign_in_refused)
            SignInError.Unreachable -> stringResource(R.string.connect_sign_in_unreachable)
            SignInError.NoHost -> stringResource(R.string.connect_sign_in_no_host)
            null -> null
        }
        if (message != null) {
            Text(message, style = DsType.caption11, color = colors.warnLabel)
        }
        DsButton(
            text = stringResource(R.string.connect_sign_in),
            onClick = { onSubmit(input) },
            enabled = !signingIn && input.isNotBlank(),
            variant = DsButtonVariant.Info,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Determinate sweep feedback: a /24 takes long enough that a static label reads as a hang. */
@Composable
private fun ScanProgressRow(progress: ScanProgress?, onCancel: () -> Unit) {
    val colors = DsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (progress == null) {
                    stringResource(R.string.connect_scanning)
                } else {
                    stringResource(R.string.connect_scan_progress, progress.probed, progress.total)
                },
                style = DsType.std14,
                color = colors.labelTertiary,
                modifier = Modifier.weight(1f),
            )
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onCancel,
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
        if (progress != null && progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.probed.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
                trackColor = colors.hoverSolid,
            )
        } else {
            Box(Modifier.fillMaxWidth().height(4.dp))
        }
    }
}

/** Last path segment of a host cwd, so a card can name the project rather than print a full path. */
private fun basename(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
