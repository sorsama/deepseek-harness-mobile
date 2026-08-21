package com.labteto.dshmobile.ui.screens.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.DiscoveredHost
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

@Composable
fun ConnectScreen(onOpenSettings: () -> Unit, viewModel: ConnectViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    // Saveable: a rotation mid-connect used to wipe a hand-typed address.
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("3080") }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(DsSpacing.xlarge),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.large),
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

            EmptyHero(
                headline = stringResource(R.string.app_long_name),
                subtitle = stringResource(R.string.connect_subtitle),
                chips = emptyList(),
                onChipClick = {},
            )

            // Security banner (always on the connect screen).
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = colors.warnTertiary,
            ) {
                Text(
                    stringResource(R.string.connect_security_banner),
                    style = DsType.small13,
                    color = colors.warnLabel,
                    modifier = Modifier.padding(DsSpacing.medium),
                )
            }

            // ---- Recent ------------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                SectionHeader(stringResource(R.string.connect_remembered))
                if (state.remembered.isEmpty()) {
                    Text(
                        stringResource(R.string.connect_remembered_empty),
                        style = DsType.std14,
                        color = colors.labelCaption,
                    )
                } else {
                    state.remembered.forEach { saved ->
                        RecentHarnessCard(
                            host = saved,
                            probe = state.recentStatus[saved.authority],
                            onConnect = { viewModel.connectTo(saved) },
                            onForget = { viewModel.forget(saved) },
                        )
                    }
                }
            }

            // ---- Discovered --------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                SectionHeader(
                    title = stringResource(R.string.connect_discovered),
                    action = stringResource(R.string.connect_scan),
                    onAction = { viewModel.scan() },
                )
                val unknown = state.unknownDiscovered
                // Results and progress coexist: the sweep streams, so a host found in the first
                // batch belongs on screen while the rest of the subnet is still being knocked.
                if (state.scanning) {
                    ScanProgressRow(state.scanProgress) { viewModel.cancelScan() }
                }
                if (unknown.isEmpty()) {
                    if (!state.scanning) {
                        Text(
                            stringResource(R.string.connect_discovered_hint),
                            style = DsType.std14,
                            color = colors.labelCaption,
                        )
                    }
                } else {
                    unknown.forEach { found ->
                        DiscoveredHarnessCard(found) { viewModel.connectDiscovered(found) }
                    }
                }
            }

            // ---- Manual ------------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                SectionHeader(stringResource(R.string.connect_manual_title))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = host,
                        onValueChange = { host = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.connect_host_hint), style = DsType.std14) },
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
                if (state.connecting) ConnectProgressRow(state.stage, state.attempted)
                state.failure?.let { failure ->
                    ConnectFailureBlock(
                        failure = failure,
                        attempted = state.attempted,
                        retrying = state.retrying,
                        onCancel = viewModel::cancelConnect,
                    )
                }
            }

            // ---- Auto-connect ------------------------------------------------
            Column {
                SectionHeader(stringResource(R.string.connect_auto_title))
                AutoToggle(stringResource(R.string.connect_auto_last), state.autoConnectLast) {
                    viewModel.setAuto("last", it)
                }
                AutoToggle(stringResource(R.string.connect_auto_lan), state.autoConnectLan) {
                    viewModel.setAuto("lan", it)
                }
                AutoToggle(stringResource(R.string.connect_auto_loopback), state.autoConnectLoopback) {
                    viewModel.setAuto("loopback", it)
                }
            }

            Spacer(Modifier.height(DsSpacing.xlarge))
        }
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
private fun AutoToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = DsType.std14, color = colors.labelSecondary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * One remembered harness.
 *
 * Everything on the card is already known or already probed — the address, the last-connected
 * stamp, the harness version, the project it is sitting in, how many sessions are attached — and a
 * status dot says whether it is answering right now. The previous row showed an icon and an IP
 * repeated twice, which is why the list read as blank space.
 */
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
    val version = reachable?.description?.version ?: host.lastVersion
    val cwd = reachable?.description?.cwd ?: host.lastCwd
    val sessions = reachable?.description?.attachedSessions ?: host.lastSessions

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
            listOfNotNull(host.displayAddress, cwd?.let { basename(it) }).joinToString(" · "),
            style = DsType.caption11,
            color = colors.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                statusLine(probe, version, sessions),
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

/** The third line: what the harness is, or why it has nothing to say. */
@Composable
private fun statusLine(probe: HostProbe?, version: String?, sessions: Int?): String = when {
    probe is HostProbe.Probing -> stringResource(R.string.connect_checking)
    probe is HostProbe.Unreachable -> stringResource(R.string.connect_unreachable)
    version != null -> listOfNotNull(
        stringResource(R.string.connect_harness_version_only, version),
        sessions?.let { stringResource(R.string.connect_sessions_short, it) },
    ).joinToString(" · ")
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
                listOfNotNull(
                    stringResource(R.string.connect_harness_version_only, description.version),
                    basename(description.cwd).takeIf { it.isNotBlank() },
                    stringResource(R.string.connect_sessions_short, description.attachedSessions),
                ).joinToString(" · "),
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
) {
    val colors = DsTheme.colors
    val authority = attempted.orEmpty()
    val port = authority.substringAfterLast(':', "").toIntOrNull() ?: 0
    // `connect_failed` is formatted from the two halves so it reads as one address; feeding it the
    // whole authority plus an empty port left a trailing colon. Blank means there was nothing to
    // attempt (bad input), and a headline naming no address would say nothing.
    val title = when {
        failure is ConnectFailure.TrustFence -> stringResource(R.string.connect_fail_fence_title)
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
