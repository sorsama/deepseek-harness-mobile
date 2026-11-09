package com.labteto.dshmobile.ui.screens.connect

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.connection.ConnectMode
import com.labteto.dshmobile.connection.ConnectStage
import com.labteto.dshmobile.connection.DiscoveredHost
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.parseHostInput
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.common_cancel
import com.labteto.dshmobile.shared.resources.connect_auto_lan
import com.labteto.dshmobile.shared.resources.connect_auto_last
import com.labteto.dshmobile.shared.resources.connect_auto_loopback
import com.labteto.dshmobile.shared.resources.connect_auto_relay
import com.labteto.dshmobile.shared.resources.connect_auto_start_loopback
import com.labteto.dshmobile.shared.resources.connect_auto_start_loopback_hint
import com.labteto.dshmobile.shared.resources.connect_auto_title
import com.labteto.dshmobile.shared.resources.connect_button
import com.labteto.dshmobile.shared.resources.connect_checking
import com.labteto.dshmobile.shared.resources.connect_discovered_hint
import com.labteto.dshmobile.shared.resources.connect_forget_harness
import com.labteto.dshmobile.shared.resources.connect_found_untrusted
import com.labteto.dshmobile.shared.resources.connect_found_untrusted_hint
import com.labteto.dshmobile.shared.resources.connect_harness_actions
import com.labteto.dshmobile.shared.resources.connect_hero_subtitle
import com.labteto.dshmobile.shared.resources.connect_host_hint
import com.labteto.dshmobile.shared.resources.connect_host_label
import com.labteto.dshmobile.shared.resources.connect_lan_notice
import com.labteto.dshmobile.shared.resources.connect_loopback_notice
import com.labteto.dshmobile.shared.resources.connect_loopback_start
import com.labteto.dshmobile.shared.resources.connect_manual_paste_hint
import com.labteto.dshmobile.shared.resources.connect_manual_title
import com.labteto.dshmobile.shared.resources.connect_mode_lan
import com.labteto.dshmobile.shared.resources.connect_mode_loopback
import com.labteto.dshmobile.shared.resources.connect_mode_relay
import com.labteto.dshmobile.shared.resources.connect_reachable
import com.labteto.dshmobile.shared.resources.connect_relay_discovered_hint
import com.labteto.dshmobile.shared.resources.connect_relay_encrypted
import com.labteto.dshmobile.shared.resources.connect_relay_notice
import com.labteto.dshmobile.shared.resources.connect_relay_pair_action
import com.labteto.dshmobile.shared.resources.connect_relay_pair_this
import com.labteto.dshmobile.shared.resources.connect_relay_plaintext
import com.labteto.dshmobile.shared.resources.connect_relay_refused
import com.labteto.dshmobile.shared.resources.connect_relay_refused_hint
import com.labteto.dshmobile.shared.resources.connect_relay_remembered
import com.labteto.dshmobile.shared.resources.connect_relay_remembered_empty
import com.labteto.dshmobile.shared.resources.connect_relay_scan
import com.labteto.dshmobile.shared.resources.connect_relay_unpaired
import com.labteto.dshmobile.shared.resources.connect_remembered
import com.labteto.dshmobile.shared.resources.connect_remembered_empty
import com.labteto.dshmobile.shared.resources.connect_same_device
import com.labteto.dshmobile.shared.resources.connect_scan
import com.labteto.dshmobile.shared.resources.connect_scan_progress_short
import com.labteto.dshmobile.shared.resources.connect_scanning
import com.labteto.dshmobile.shared.resources.connect_section_discovered
import com.labteto.dshmobile.shared.resources.connect_section_discovered_relay
import com.labteto.dshmobile.shared.resources.connect_sign_in
import com.labteto.dshmobile.shared.resources.connect_sign_in_label
import com.labteto.dshmobile.shared.resources.connect_sign_in_link_body
import com.labteto.dshmobile.shared.resources.connect_sign_in_no_host
import com.labteto.dshmobile.shared.resources.connect_sign_in_refused
import com.labteto.dshmobile.shared.resources.connect_sign_in_subtitle
import com.labteto.dshmobile.shared.resources.connect_sign_in_title
import com.labteto.dshmobile.shared.resources.connect_sign_in_to
import com.labteto.dshmobile.shared.resources.connect_sign_in_token_hint
import com.labteto.dshmobile.shared.resources.connect_sign_in_unreachable
import com.labteto.dshmobile.shared.resources.connect_title
import com.labteto.dshmobile.shared.resources.connect_unreachable
import com.labteto.dshmobile.shared.resources.settings_title
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.localizedCount
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.HoldAction
import com.labteto.dshmobile.ui.components.HoldMenu
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.PlatformBackHandler
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import org.jetbrains.compose.resources.stringResource

/**
 * Choose how to reach a harness, then reach one.
 *
 * The screen is one of three surfaces, picked from the connection state rather than from a local
 * flag, so the view model keeps owning the truth:
 *  - [ConnectSurface.Form] — the design's 02: mode switch, mode notice, Recent, Discovered, manual
 *    entry and a pinned primary action with the "reconnect to last used harness" switch.
 *  - [ConnectSurface.Connecting] — the design's 03: a real takeover with the stage checklist.
 *  - [ConnectSurface.Lost] — the design's 06: a warning card for the harness that stopped answering
 *    and a bottom plate offering Retry now / Choose another harness.
 *
 * One rule shapes the layout: at most one paragraph of prose before something you can act on. The
 * first cut of relay mode opened with six lines of explanation across two blocks, then three empty
 * sections each saying a version of "you have no relay", and only then the button that was the
 * entire point of the screen.
 */
@Composable
fun ConnectContent(
    state: ConnectUiState,
    controller: ConnectController,
    features: ConnectFeatures,
    onOpenSettings: () -> Unit,
    onPair: (prefillUrl: String?) -> Unit,
    loopbackCard: @Composable (recordAttempt: (() -> Unit) -> Unit) -> Unit = {},
    initialHost: String = "",
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    // Saveable: a rotation mid-connect used to wipe a hand-typed address.
    var host by rememberSaveable { mutableStateOf(initialHost) }
    // Whatever was tapped last, so a failure can offer to do it again. A failure used to end in a
    // block of advice and nothing to press; the only way to retry was to find the card again.
    var lastAttempt by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun attempt(action: () -> Unit) {
        lastAttempt = action
        action()
    }

    val relayMode = state.mode == ConnectMode.RELAY
    val loopbackMode = state.mode == ConnectMode.LOOPBACK
    val paired = state.visibleHosts

    // Invalid input is a typo to fix in the field, not a connection status: it stays inline on the
    // form. Everything else is a failed attempt and gets the full lost surface.
    val lostFailure = state.failure?.takeIf { it !is ConnectFailure.InvalidInput }
    var lastLostFailure by remember { mutableStateOf<ConnectFailure?>(null) }
    SideEffect { if (lostFailure != null) lastLostFailure = lostFailure }

    // A repeat of the same action when the app can name one: the tap that was just made, or the
    // remembered harness the failing attempt was pointed at (auto-connect makes no tap to repeat).
    val retryAction: (() -> Unit)? = state.failure?.takeIf { it.isRetryable() }?.let {
        lastAttempt ?: state.attempted?.let { attempted ->
            state.remembered.firstOrNull { it.authority == attempted }?.let { remembered ->
                { attempt { controller.connectTo(remembered) } }
            }
        }
    }

    val surface = when {
        lostFailure != null -> ConnectSurface.Lost
        state.connecting -> ConnectSurface.Connecting
        else -> ConnectSurface.Form
    }

    // Entering the lost state is a negative outcome, so it is felt before it is read.
    LaunchedEffect(surface) {
        if (surface == ConnectSurface.Lost) haptics.reject()
    }
    // A typo's inline card should not be silent either, but it must not also replace the form.
    LaunchedEffect(state.failure) {
        if (state.failure is ConnectFailure.InvalidInput) haptics.reject()
    }
    // Back from a takeover returns to the form rather than leaving the app; there is no route behind
    // this screen to go back to.
    PlatformBackHandler(enabled = surface != ConnectSurface.Form) { controller.cancelConnect() }

    var forgetTarget by remember { mutableStateOf<HostConfig?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Box(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            AnimatedContent(
                targetState = surface,
                transitionSpec = {
                    DsAnimations.screenEnter togetherWith DsAnimations.screenExit
                },
                label = "connectSurface",
            ) { current ->
                when (current) {
                    ConnectSurface.Form -> ConnectForm(
                        state = state,
                        host = host,
                        onHostChange = { host = it },
                        relayMode = relayMode,
                        loopbackMode = loopbackMode,
                        paired = paired,
                        onOpenSettings = onOpenSettings,
                        onPair = onPair,
                        recordAttempt = ::attempt,
                        controller = controller,
                        features = features,
                        loopbackCard = loopbackCard,
                        onForgetRequest = { forgetTarget = it },
                    )
                    ConnectSurface.Connecting -> ConnectConnectingSurface(
                        stage = state.stage,
                        attempted = state.attempted,
                        remembered = state.remembered,
                        onCancel = controller::cancelConnect,
                        onRetry = retryAction,
                    )
                    ConnectSurface.Lost -> {
                        val failure = lostFailure ?: lastLostFailure
                        if (failure != null) {
                            ConnectLostSurface(
                                failure = failure,
                                attempted = state.attempted,
                                remembered = state.remembered,
                                attempts = state.attempts,
                                retrying = state.retrying,
                                onRetry = retryAction,
                                onChooseAnother = controller::cancelConnect,
                                onPairAgain = { onPair(state.attemptedBaseUrl) },
                                onSignIn = { controller.setSignInOpen(true) },
                                onForget = { forgotten ->
                                    controller.forget(forgotten)
                                    controller.cancelConnect()
                                    forgetTarget = null
                                },
                            )
                        }
                    }
                }
            }

            if (state.signInOpen) {
                SignInSheet(
                    signingIn = state.signingIn,
                    error = state.signInError,
                    hostName = connectHostName(state.attempted, state.remembered),
                    onDismiss = { controller.setSignInOpen(false) },
                    onSubmit = controller::signIn,
                )
            }

            forgetTarget?.let { target ->
                val forget = stringResource(Res.string.connect_forget_harness)
                HoldMenu(
                    onDismiss = { forgetTarget = null },
                    actions = listOf(
                        HoldAction(
                            label = forget,
                            icon = DsIcons.Delete,
                            danger = true,
                            onClick = {
                                controller.forget(target)
                                forgetTarget = null
                            },
                        ),
                    ),
                    preview = listOf(target.name, target.displayAddress).joinToString("\n"),
                    monospacePreview = true,
                )
            }
        }
    }
}

/** Which of the three surfaces above is showing. */
private enum class ConnectSurface { Form, Connecting, Lost }

/**
 * The design's 02: the connect form.
 *
 * The mode switch is the first control because the paths are not variations of one connection. This
 * phone talks to a harness running on the device itself and nothing leaves the phone; Local network
 * talks straight to a harness on the Wi-Fi with no encryption; Relay talks to `dsh-relay` with a
 * credential issued once and pinned. Nothing here, auto-connect included, ever connects the way
 * that was not picked.
 */
@Composable
private fun ConnectForm(
    state: ConnectUiState,
    host: String,
    onHostChange: (String) -> Unit,
    relayMode: Boolean,
    loopbackMode: Boolean,
    paired: List<HostConfig>,
    onOpenSettings: () -> Unit,
    onPair: (String?) -> Unit,
    recordAttempt: (() -> Unit) -> Unit,
    controller: ConnectController,
    features: ConnectFeatures,
    loopbackCard: @Composable (recordAttempt: (() -> Unit) -> Unit) -> Unit,
    onForgetRequest: (HostConfig) -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var autoExpanded by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DsSpacing.large)
                .padding(top = DsSpacing.large, bottom = DsSpacing.large),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.large),
        ) {
            ConnectHeader(onOpenSettings = onOpenSettings.takeIf { features.settings })

            // A switch with one choice on it is not a switch: a platform that offers only the local
            // network (iOS, for now) shows no control and no empty tabs.
            val segments = buildList {
                if (features.thisPhone) add(DsSegment(ConnectMode.LOOPBACK, stringResource(Res.string.connect_mode_loopback)))
                add(DsSegment(ConnectMode.LAN, stringResource(Res.string.connect_mode_lan)))
                if (features.relay) add(DsSegment(ConnectMode.RELAY, stringResource(Res.string.connect_mode_relay)))
            }
            if (segments.size > 1) {
                DsSegmented(
                    segments = segments,
                    selectedKey = state.mode,
                    onSelect = controller::setMode,
                    role = Role.Tab,
                    // Three parts of one decision, so each gets a third of the track. Two label lines
                    // at larger text sizes wrap rather than being clipped by an ellipsis.
                    stretch = true,
                    maxLabelLines = 2,
                    minSegmentHeight = 40.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // The sections below belong to the chosen mode. They cross-fade with a short slide in the
            // direction of the tab that was picked, so switching reads as moving along the segmented
            // control above rather than as the page being rebuilt under the thumb.
            AnimatedContent(
                targetState = state.mode,
                transitionSpec = {
                    val order = listOf(ConnectMode.LOOPBACK, ConnectMode.LAN, ConnectMode.RELAY)
                    val forward = order.indexOf(targetState) >= order.indexOf(initialState)
                    (
                        fadeIn(tween(220, delayMillis = 40)) +
                            slideInHorizontally(tween(260, easing = DsAnimations.inkDecelerate)) { w ->
                                if (forward) w / 8 else -w / 8
                            }
                        ).togetherWith(
                        fadeOut(tween(120)) +
                            slideOutHorizontally(tween(200, easing = DsAnimations.emphasizedAccelerate)) { w ->
                                if (forward) -w / 8 else w / 8
                            },
                    ).using(SizeTransform(clip = false))
                },
                label = "connectMode",
            ) { mode ->
                val modeRelay = mode == ConnectMode.RELAY
                val modeLoopback = mode == ConnectMode.LOOPBACK
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.large)) {
                    ModeNotice(mode = mode, onPair = onPair)

                    if (modeLoopback) {
                        loopbackCard(recordAttempt)
                    }

                    // Hidden entirely when empty in relay mode: the pairing notice above already says
                    // there is nothing here, and saying it twice is what made the screen read as
                    // three dead ends. This phone has exactly one endpoint and the card above is it.
                    if (!modeLoopback && (paired.isNotEmpty() || !modeRelay)) {
                        RecentSection(
                            relayMode = modeRelay,
                            hosts = paired,
                            status = state.recentStatus,
                            onConnect = { target -> recordAttempt { controller.connectTo(target) } },
                            onForgetRequest = onForgetRequest,
                        )
                    }

                    // The subnet sweep needs the device's own addresses and, on iOS, the Local Network
                    // permission and a way to enumerate interfaces; until a platform has them the section
                    // is not shown at all rather than shown broken.
                    if (!modeLoopback && features.discovery) {
                        DiscoveredSection(
                            relayMode = modeRelay,
                            state = state,
                            onScan = controller::scan,
                            onCancelScan = controller::cancelScan,
                            onConnect = { found -> recordAttempt { controller.connectDiscovered(found) } },
                            onPair = onPair,
                        )
                    }

                    if (mode == ConnectMode.LAN) {
                        ManualSection(
                            host = host,
                            onHostChange = onHostChange,
                            onConnect = { recordAttempt { controller.connectManual(host, DEFAULT_PORT) } },
                            connecting = state.connecting,
                        )
                    }

                    // Every option in this section needs something a platform may not have: LAN
                    // auto-connect needs the sweep, relay auto-connect needs relays, and the loopback ones
                    // need a harness on the device. With none of them the section would open to nothing.
                    val hasAutoOptions = when (mode) {
                        ConnectMode.RELAY -> features.relay
                        ConnectMode.LOOPBACK -> features.thisPhone
                        else -> features.discovery
                    }
                    if (hasAutoOptions) {
                        AutoConnectSection(
                            mode = mode,
                            state = state,
                            expanded = autoExpanded,
                            onToggleExpanded = {
                                haptics.tap()
                                autoExpanded = !autoExpanded
                            },
                            onAutoChange = { key, value ->
                                haptics.toggle(value)
                                controller.setAuto(key, value)
                            },
                        )
                    }

                    state.failure?.takeIf { it is ConnectFailure.InvalidInput }?.let { failure ->
                        InlineFailureCard(body = failureBody(failure, state.attempted.orEmpty()))
                    }
                }
            }
        }

        ConnectBottomBar(
            state = state,
            mode = state.mode,
            host = host,
            paired = paired,
            relayMode = relayMode,
            loopbackMode = loopbackMode,
            recordAttempt = recordAttempt,
            onPair = onPair,
            controller = controller,
        )
    }
}

/**
 * The screen's own masthead: mascot, name, one line of what this screen is for.
 *
 * The settings button is the one thing the design does not draw; it stays because Settings is the
 * only way to change appearance and language before a harness is connected, and the drawer that
 * also reaches it does not exist yet at that point.
 */
@Composable
private fun ConnectHeader(onOpenSettings: (() -> Unit)?) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        MascotMark(Modifier.size(48.dp), state = MascotState.Idle)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.connect_title),
                style = DsType.large20,
                color = colors.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(Res.string.connect_hero_subtitle),
                style = DsType.small13,
                color = colors.labelTertiary,
            )
        }
        if (onOpenSettings != null) {
            DsIconButton(
                icon = DsIcons.Settings,
                contentDescription = stringResource(Res.string.settings_title),
                onClick = onOpenSettings,
                tint = colors.labelTertiary,
            )
        }
    }
}

/**
 * The one notice under the mode switch: what this mode is and what it costs, in the design's three
 * tones — amber for the unencrypted LAN, accent for the relay (which is also the way into pairing),
 * neutral for the harness on this phone.
 */
@Composable
private fun ModeNotice(mode: String, onPair: (String?) -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val shape = DsShapes.buttonLarge
    when (mode) {
        ConnectMode.RELAY -> Surface(
            onClick = {
                haptics.tap()
                onPair(null)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = colors.accentTertiary,
            border = BorderStroke(1.dp, colors.accentBorder),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = DsSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Icon(
                    DsIcons.QrCodeScanner,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(Res.string.connect_relay_notice),
                    style = DsType.small13,
                    color = colors.accent,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    DsIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        ConnectMode.LOOPBACK -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = colors.bgLayer1,
            border = BorderStroke(1.dp, colors.borderL2),
        ) {
            NoticeRow(
                icon = null,
                text = stringResource(Res.string.connect_loopback_notice),
                textColor = colors.labelSecondary,
            )
        }
        else -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = colors.warnTertiary,
            border = BorderStroke(1.dp, colors.warnBorder),
        ) {
            NoticeRow(
                icon = DsIcons.Shield,
                text = stringResource(Res.string.connect_lan_notice),
                textColor = colors.warnText,
                iconTint = colors.warnLabel,
            )
        }
    }
}

@Composable
private fun NoticeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    text: String,
    textColor: Color,
    iconTint: Color? = null,
) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = DsSpacing.medium),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint ?: textColor,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(18.dp),
            )
        }
        Text(text, style = DsType.small13, color = textColor)
    }
}

/**
 * Remembered harnesses, as the design's Recent card: status dot, name, one line of monospace
 * metadata and a status tag that never wraps.
 *
 * The design mock wraps "4 sessions" onto a second line under the tag. That is a defect, not a
 * composition: the meta is a single ellipsized line here and the tag gets its own row slot on the
 * title line, with the overflow affordance beside it.
 */
@Composable
private fun RecentSection(
    relayMode: Boolean,
    hosts: List<HostConfig>,
    status: Map<String, HostProbe>,
    onConnect: (HostConfig) -> Unit,
    onForgetRequest: (HostConfig) -> Unit,
) {
    val colors = DsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        ConnectSectionLabel(
            stringResource(if (relayMode) Res.string.connect_relay_remembered else Res.string.connect_remembered),
        )
        if (hosts.isEmpty()) {
            Text(
                stringResource(
                    if (relayMode) Res.string.connect_relay_remembered_empty else Res.string.connect_remembered_empty,
                ),
                style = DsType.small13,
                color = colors.labelCaption,
            )
        } else {
            hosts.forEach { host ->
                RecentHarnessCard(
                    host = host,
                    probe = status[host.authority],
                    onConnect = { onConnect(host) },
                    onMore = { onForgetRequest(host) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentHarnessCard(
    host: HostConfig,
    probe: HostProbe?,
    onConnect: () -> Unit,
    onMore: () -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val reachable = probe as? HostProbe.Reachable
    val title = if (host.isLoopback) stringResource(Res.string.connect_same_device) else host.name
    val home = reachable?.description?.home ?: host.lastHome
    val meta = listOfNotNull(
        host.displayAddress,
        if (host.isRelay) {
            stringResource(
                if (host.isPlaintext) Res.string.connect_relay_plaintext else Res.string.connect_relay_encrypted,
            )
        } else {
            null
        },
        home?.let { basename(it) },
    ).joinToString(" · ")
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.bgLayer1)
            .border(1.dp, colors.borderL2, DsShapes.block)
            .combinedClickable(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                role = Role.Button,
                onLongClickLabel = stringResource(Res.string.connect_forget_harness),
                onLongClick = {
                    haptics.longPress()
                    onMore()
                },
                onClick = onConnect,
            )
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            StateDot(
                when (probe) {
                    is HostProbe.Reachable -> StateDotState.Done
                    HostProbe.Probing -> StateDotState.Running
                    HostProbe.Unreachable -> StateDotState.Warning
                    null -> StateDotState.Idle
                },
                size = 8.dp,
            )
            Text(
                title,
                style = DsType.rowTitle,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RecentStatusTag(probe)
            DsIconButton(
                icon = DsIcons.MoreVert,
                contentDescription = stringResource(Res.string.connect_harness_actions),
                onClick = onMore,
                tint = colors.labelTertiary,
                iconSize = 18.dp,
            )
        }
        Text(
            meta,
            style = DsType.mono12,
            color = colors.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = DsSpacing.large),
        )
    }
}

/** The reachability verdict on a Recent card; "checking" is the honest reading of `null`. */
@Composable
private fun RecentStatusTag(probe: HostProbe?) {
    when (probe) {
        is HostProbe.Reachable -> ConnectStatusTag(stringResource(Res.string.connect_reachable), TagTone.Success)
        HostProbe.Unreachable -> ConnectStatusTag(stringResource(Res.string.connect_unreachable), TagTone.Warn)
        else -> ConnectStatusTag(stringResource(Res.string.connect_checking), TagTone.Neutral)
    }
}

/**
 * The discovery section: the design's progress readout and a scanning placeholder that is clearly a
 * placeholder, not an empty result. Results stream in below it as the sweep finds them.
 */
@Composable
private fun DiscoveredSection(
    relayMode: Boolean,
    state: ConnectUiState,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onConnect: (DiscoveredHost) -> Unit,
    onPair: (String?) -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val found = state.unknownDiscovered
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        ConnectSectionLabel(
            text = stringResource(
                if (relayMode) Res.string.connect_section_discovered_relay else Res.string.connect_section_discovered,
            ),
            action = {
                if (state.scanning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = colors.accent,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            state.scanProgress?.let {
                                stringResource(Res.string.connect_scan_progress_short, localizedCount(it.probed), localizedCount(it.total))
                            } ?: stringResource(Res.string.connect_scanning),
                            style = DsType.small13Strong,
                            color = colors.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        stringResource(if (relayMode) Res.string.connect_relay_scan else Res.string.connect_scan),
                        style = DsType.small13Strong,
                        color = colors.accent,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(DsShapes.pillFull)
                            .clickable {
                                haptics.tap()
                                onScan()
                            }
                            .heightIn(min = 36.dp)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .padding(horizontal = 12.dp),
                    )
                }
            },
        )
        if (state.scanning) {
            DashedSkeletonCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .skeleton(
                                base = colors.bgLayer3,
                                highlight = colors.hoverSolid,
                                shape = CircleShape,
                            ),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.46f)
                                .height(12.dp)
                                .skeleton(
                                    base = colors.bgLayer3,
                                    highlight = colors.hoverSolid,
                                    shape = DsShapes.chip,
                                ),
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(0.70f)
                                .height(10.dp)
                                .skeleton(
                                    base = colors.bgLayer3,
                                    highlight = colors.hoverSolid,
                                    shape = DsShapes.chip,
                                ),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DsConnectButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onCancelScan,
                    variant = ConnectButtonVariant.Ghost,
                )
            }
        }
        if (found.isEmpty()) {
            if (!state.scanning && !relayMode) {
                Text(
                    stringResource(Res.string.connect_discovered_hint),
                    style = DsType.small13,
                    color = colors.labelCaption,
                )
            }
            if (!state.scanning && relayMode && state.visibleHosts.isEmpty()) {
                Text(
                    stringResource(Res.string.connect_relay_discovered_hint),
                    style = DsType.small13,
                    color = colors.labelCaption,
                )
            }
        } else {
            found.forEach { host ->
                FoundHarnessCard(
                    authority = host.authority,
                    detail = hostDetail(host),
                    warn = if (host.isRelay) {
                        host.hostRefused || host.fingerprint == null
                    } else {
                        host.description == null
                    },
                    actionLabel = when {
                        host.isRelay && !host.hostRefused -> stringResource(Res.string.connect_relay_pair_this)
                        !host.isRelay && host.description != null -> stringResource(Res.string.connect_button)
                        else -> null
                    },
                    tagText = if (host.isRelay) {
                        stringResource(Res.string.connect_relay_refused)
                    } else {
                        stringResource(Res.string.connect_found_untrusted)
                    },
                    onAction = {
                        if (host.isRelay) {
                            if (!host.hostRefused) onPair(host.baseUrl)
                        } else if (host.description != null) {
                            onConnect(host)
                        }
                    },
                )
            }
        }
    }
}

/** One line describing a discovered endpoint, honest about what it did or did not say. */
@Composable
private fun hostDetail(host: DiscoveredHost): String {
    val description = host.description
    return when {
        host.isRelay -> if (host.hostRefused) {
            stringResource(Res.string.connect_relay_refused_hint, host.authority)
        } else {
            listOf(
                stringResource(Res.string.connect_relay_unpaired),
                stringResource(
                    if (host.fingerprint != null) Res.string.connect_relay_encrypted else Res.string.connect_relay_plaintext,
                ),
            ).joinToString(" · ")
        }
        description != null -> basename(description.home).takeIf { it.isNotBlank() }
            ?: stringResource(Res.string.connect_checking)
        else -> stringResource(Res.string.connect_found_untrusted_hint)
    }
}

/**
 * One sweep result. A harness whose trust fence refused us is still shown: it is the single most
 * recoverable outcome the scan can produce and reporting it as "nothing found" sends people looking
 * for a fault that is not there.
 */
@Composable
private fun FoundHarnessCard(
    authority: String,
    detail: String,
    warn: Boolean,
    actionLabel: String?,
    tagText: String,
    onAction: () -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.bgLayer1)
            .border(1.dp, colors.borderL2, DsShapes.block)
            .clickable(enabled = actionLabel != null) {
                haptics.tap()
                onAction()
            }
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        Icon(
            FeatherIcons.Globe,
            contentDescription = null,
            tint = if (warn) colors.warn else colors.accent,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                authority,
                style = DsType.rowTitle,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = DsType.xsmall12,
                color = if (warn) colors.warnLabel else colors.labelTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (actionLabel != null) {
            Text(
                actionLabel,
                style = DsType.small13Strong,
                color = colors.accent,
                maxLines = 1,
            )
        } else {
            ConnectStatusTag(tagText, TagTone.Warn)
        }
    }
}

/** The design's manual entry: one monospace address field and the paste affordance beside it. */
@Composable
private fun ManualSection(
    host: String,
    onHostChange: (String) -> Unit,
    onConnect: () -> Unit,
    connecting: Boolean,
) {
    val colors = DsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        ConnectSectionLabel(stringResource(Res.string.connect_manual_title))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            InkTextField(
                value = host,
                onValueChange = onHostChange,
                modifier = Modifier.weight(1f),
                placeholder = stringResource(Res.string.connect_host_hint),
                contentDescription = stringResource(Res.string.connect_host_label),
                mono = true,
                enabled = !connecting,
                // An address keyboard without autocorrect: "192.168.1.20" corrected to
                // "192.168.1.20." or capitalised was a failure people blamed on the network.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { if (host.isNotBlank() && !connecting) onConnect() }),
            )
            InkPasteButton(onPaste = onHostChange)
        }
        Text(
            stringResource(Res.string.connect_manual_paste_hint),
            style = DsType.xsmall12,
            color = colors.labelTertiary,
        )
    }
}

/**
 * The auto-connect switches other than "reconnect to last used harness" (which lives in the pinned
 * bar). Collapsed by default: the design's screen ends at the manual field, and these are settings
 * inside a flow, not part of it. Collapsing rather than removing keeps every existing option.
 */
@Composable
private fun AutoConnectSection(
    mode: String,
    state: ConnectUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onAutoChange: (String, Boolean) -> Unit,
) {
    val colors = DsTheme.colors
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.row)
                .clickable(onClick = onToggleExpanded)
                .heightIn(min = DsSpacing.touchTarget)
                .padding(horizontal = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.connect_auto_title),
                style = DsType.std14,
                color = colors.labelSecondary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                DsIcons.ExpandMore,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(160)) + expandVertically(DsAnimations.expand),
            exit = fadeOut(tween(120)),
        ) {
            Column {
                when (mode) {
                    ConnectMode.RELAY -> AutoToggle(
                        label = stringResource(Res.string.connect_auto_relay),
                        checked = state.autoConnectRelay,
                    ) { onAutoChange("relay", !state.autoConnectRelay) }
                    ConnectMode.LOOPBACK -> {
                        AutoToggle(
                            label = stringResource(Res.string.connect_auto_loopback),
                            checked = state.autoConnectLoopback,
                        ) { onAutoChange("loopback", !state.autoConnectLoopback) }
                        if (state.termuxInstalled) {
                            AutoToggle(
                                label = stringResource(Res.string.connect_auto_start_loopback),
                                hint = stringResource(Res.string.connect_auto_start_loopback_hint),
                                checked = state.autoStartLoopbackHarness,
                            ) { onAutoChange("autoStart", !state.autoStartLoopbackHarness) }
                        }
                    }
                    else -> AutoToggle(
                        label = stringResource(Res.string.connect_auto_lan),
                        checked = state.autoConnectLan,
                    ) { onAutoChange("lan", !state.autoConnectLan) }
                }
            }
        }
    }
}

@Composable
private fun AutoToggle(label: String, checked: Boolean, hint: String? = null, onChange: () -> Unit) {
    com.labteto.dshmobile.ui.components.ToggleRow(
        label = label,
        checked = checked,
        hint = hint,
        onChange = onChange,
    )
}

/** The port `connectManual` fallback uses when the typed/pasted address carries none. */
private const val DEFAULT_PORT = "3080"

/**
 * The pinned bottom bar: the "reconnect to last used harness" switch and the one primary action.
 *
 * The primary action is derived from what the selected mode can actually do right now — ready
 * harness on this phone, a typed address, the most recent remembered harness, or the pairing flow in
 * relay mode with nothing paired — so the button is never a dead end and never pretends a target
 * exists when it does not.
 */
@Composable
private fun ConnectBottomBar(
    state: ConnectUiState,
    mode: String,
    host: String,
    paired: List<HostConfig>,
    relayMode: Boolean,
    loopbackMode: Boolean,
    recordAttempt: (() -> Unit) -> Unit,
    onPair: (String?) -> Unit,
    controller: ConnectController,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val primary = when {
        relayMode -> {
            val target = paired.firstOrNull()
            if (target != null) {
                PrimaryAction(stringResource(Res.string.connect_button), true) {
                    recordAttempt { controller.connectTo(target) }
                }
            } else {
                PrimaryAction(stringResource(Res.string.connect_relay_pair_action), true) { onPair(null) }
            }
        }
        loopbackMode -> {
            val status = state.loopback
            when {
                state.harnessControl.busy -> PrimaryAction(stringResource(Res.string.connect_button), false) {}
                status is LoopbackStatus.Ready -> PrimaryAction(stringResource(Res.string.connect_button), true) {
                    recordAttempt(controller::connectLoopback)
                }
                status is LoopbackStatus.NeedsSignIn -> PrimaryAction(stringResource(Res.string.connect_sign_in), true) {
                    controller.openLoopbackSignIn()
                }
                status is LoopbackStatus.Down && state.termuxInstalled && state.termuxPermission ->
                    PrimaryAction(stringResource(Res.string.connect_loopback_start), true) {
                        recordAttempt(controller::startHarness)
                    }
                else -> PrimaryAction(stringResource(Res.string.connect_button), false) {}
            }
        }
        else -> {
            val typed = host.trim()
            when {
                typed.isNotEmpty() -> PrimaryAction(stringResource(Res.string.connect_button), true) {
                    recordAttempt { controller.connectManual(host, DEFAULT_PORT) }
                }
                paired.isNotEmpty() -> PrimaryAction(stringResource(Res.string.connect_button), true) {
                    recordAttempt { controller.connectTo(paired.first()) }
                }
                else -> PrimaryAction(stringResource(Res.string.connect_button), false) {}
            }
        }
    }

    Column(Modifier.fillMaxWidth().background(colors.bgBase)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DsSpacing.large)
                .clip(DsShapes.row)
                .clickable(role = Role.Switch) {
                    haptics.toggle(!state.autoConnectLast)
                    controller.setAuto("last", !state.autoConnectLast)
                }
                .heightIn(min = DsSpacing.touchTarget)
                .padding(vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.connect_auto_last),
                style = DsType.std14,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.autoConnectLast,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = colors.accent,
                    checkedThumbColor = colors.onAccent,
                    uncheckedTrackColor = colors.bgLayer2,
                    uncheckedBorderColor = colors.borderL2,
                ),
            )
        }
        DsConnectButton(
            text = primary.label,
            onClick = primary.run,
            enabled = primary.enabled && !state.connecting,
            variant = ConnectButtonVariant.Primary,
            large = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DsSpacing.large)
                .padding(bottom = DsSpacing.comfortable),
        )
    }
}

/** What the pinned primary will do; a plain holder so the decision stays in one `when`. */
private class PrimaryAction(val label: String, val enabled: Boolean, val run: () -> Unit)

/**
 * The sign-in step (design 04), as a bottom sheet over the connect form.
 *
 * Harness 0.1.2 signs a browser in by exchanging a token it prints once per process, and accepts
 * that token only on its index route — so there is no way for a connection attempt to do this on
 * its own and no way to skip it. The field takes the whole startup line as readily as the bare
 * token, because that is what people actually copy; the paste button is the design's placement.
 */
@Composable
private fun SignInSheet(
    signingIn: Boolean,
    error: SignInError?,
    hostName: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var input by remember { mutableStateOf("") }
    LaunchedEffect(error) {
        if (error != null) haptics.reject()
    }
    val message = when (error) {
        SignInError.Refused -> stringResource(Res.string.connect_sign_in_refused)
        SignInError.Unreachable -> stringResource(Res.string.connect_sign_in_unreachable)
        SignInError.NoHost -> stringResource(Res.string.connect_sign_in_no_host)
        null -> null
    }
    DsBottomSheet(title = null, onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            MascotMark(size = 44.dp, state = MascotState.NeedsYou)
            Column(Modifier.weight(1f)) {
                Text(
                    hostName?.let { stringResource(Res.string.connect_sign_in_to, it) }
                        ?: stringResource(Res.string.connect_sign_in_title),
                    style = DsType.large20,
                    color = colors.labelPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(Res.string.connect_sign_in_subtitle),
                    style = DsType.small13,
                    color = colors.labelTertiary,
                )
            }
        }
        Text(
            stringResource(Res.string.connect_sign_in_link_body),
            style = DsType.std14,
            color = colors.labelSecondary,
        )
        InkTextField(
            value = input,
            onValueChange = { input = it },
            label = stringResource(Res.string.connect_sign_in_label),
            mono = true,
            singleLine = false,
            maxLines = 3,
            minHeight = 76.dp,
            enabled = !signingIn,
            trailing = { InkPasteButton(onPaste = { input = it }) },
        )
        Text(
            stringResource(Res.string.connect_sign_in_token_hint),
            style = DsType.xsmall12,
            color = colors.labelTertiary,
        )
        if (message != null) {
            Text(message, style = DsType.small13, color = colors.error)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            DsConnectButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = ConnectButtonVariant.Outline,
            )
            DsConnectButton(
                text = stringResource(Res.string.connect_sign_in),
                onClick = {
                    haptics.confirm()
                    onSubmit(input)
                },
                variant = ConnectButtonVariant.Primary,
                enabled = !signingIn && input.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The error card for a failure that belongs beside the field it came from (a typo). */
@Composable
private fun InlineFailureCard(body: String) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.buttonLarge,
        color = colors.errorTertiary,
        border = BorderStroke(1.dp, colors.errorBorder),
    ) {
        Row(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Icon(
                DsIcons.Warning,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(16.dp),
            )
            Text(body, style = DsType.small13, color = colors.error)
        }
    }
}

/** The generic card shell (bgLayer1 + hairline + 18dp radius) used by the connect cards. */
@Composable
fun InkCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.bgLayer1)
            .border(1.dp, colors.borderL2, DsShapes.block)
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        content = content,
    )
}

/** Last path segment of a host cwd, so a card can name the project rather than print a full path. */
private fun basename(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
