package com.labteto.dshmobile.ui.screens.connect

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectMode
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.DiscoveredHost
import com.labteto.dshmobile.connection.HostConfig
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
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * Choose how to reach a harness, then reach one.
 *
 * The mode chooser is the first control on the screen because the two paths are not variations of
 * one connection. Local network talks straight to a harness that has no authentication at all, and
 * is only safe on a network you trust. Relay talks to `dsh-relay`, which holds this device to a
 * token it was issued once and pins the key it answers with — and works from outside the Wi-Fi.
 * Nothing here, auto-connect included, ever connects the way that was not picked.
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
    val paired = state.visibleHosts

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
                        DsSegment(ConnectMode.LAN, stringResource(R.string.connect_mode_lan)),
                        DsSegment(ConnectMode.RELAY, stringResource(R.string.connect_mode_relay)),
                    ),
                    selectedKey = state.mode,
                    onSelect = viewModel::setMode,
                    role = Role.Tab,
                    // Two halves of one decision, so each gets half the track. Hugging their labels
                    // left a third of a full-width pill empty and made the thing look unfinished.
                    stretch = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // One notice, not two. What the mode is and what it costs you are the same thought,
                // and the tint carries the difference between them: local network is a warning,
                // relay is a statement of fact.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (relayMode) colors.hoverSolid else colors.warnTertiary,
                ) {
                    Text(
                        stringResource(
                            if (relayMode) R.string.connect_mode_relay_hint else R.string.connect_mode_lan_hint,
                        ),
                        style = DsType.small13,
                        color = if (relayMode) colors.labelTertiary else colors.warnLabel,
                        modifier = Modifier.padding(DsSpacing.medium),
                    )
                }
            }

            // ---- Recent ------------------------------------------------------
            // Hidden entirely when empty in relay mode: the pairing card below already says there
            // is nothing here, and saying it twice is what made the screen read as three dead ends.
            if (paired.isNotEmpty() || !relayMode) {
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
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
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
            } else {
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
                )
            }

            // ---- Auto-connect ------------------------------------------------
            Column {
                SectionHeader(stringResource(R.string.connect_auto_title))
                ToggleRow(
                    label = stringResource(R.string.connect_auto_last),
                    checked = state.autoConnectLast,
                ) { viewModel.setAuto("last", !state.autoConnectLast) }
                if (relayMode) {
                    ToggleRow(
                        label = stringResource(R.string.connect_auto_relay),
                        checked = state.autoConnectRelay,
                    ) { viewModel.setAuto("relay", !state.autoConnectRelay) }
                } else {
                    ToggleRow(
                        label = stringResource(R.string.connect_auto_lan),
                        checked = state.autoConnectLan,
                    ) { viewModel.setAuto("lan", !state.autoConnectLan) }
                    ToggleRow(
                        label = stringResource(R.string.connect_auto_loopback),
                        checked = state.autoConnectLoopback,
                    ) { viewModel.setAuto("loopback", !state.autoConnectLoopback) }
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
                cwd?.let { basename(it) },
            ).joinToString(" · "),
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
