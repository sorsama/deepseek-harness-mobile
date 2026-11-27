package com.labteto.dshmobile.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.core.DshCore
import com.labteto.dshmobile.core.wire.HostGeneration
import com.labteto.dshmobile.data.SessionListRefresh
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.common_connected
import com.labteto.dshmobile.shared.resources.connect_harness_home
import com.labteto.dshmobile.shared.resources.connect_protocol_baseline
import com.labteto.dshmobile.shared.resources.connect_session_count
import com.labteto.dshmobile.shared.resources.ink_chat_sessions
import com.labteto.dshmobile.shared.resources.settings_connection_disconnect
import com.labteto.dshmobile.ui.LocalSessionStore
import com.labteto.dshmobile.ui.RootRoute
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.localizedCount
import com.labteto.dshmobile.ui.rootRouteOf
import com.labteto.dshmobile.ui.screens.connect.AddressFieldProbe
import com.labteto.dshmobile.ui.screens.connect.ConnectButtonVariant
import com.labteto.dshmobile.ui.screens.connect.ConnectContent
import com.labteto.dshmobile.ui.screens.connect.ConnectFailure
import com.labteto.dshmobile.ui.screens.connect.DsConnectButton
import com.labteto.dshmobile.ui.screens.connect.LocalAddressFieldProbe
import com.labteto.dshmobile.ui.screens.pair.PairContent
import com.labteto.dshmobile.ui.screens.pair.PairFailure
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

/**
 * What the host asked the app to do when it opened, beyond showing the Connect screen.
 *
 * The Swift host fills this in from launch arguments in debug builds only, which is how CI drives
 * the app in a simulator without a person typing. Each one goes down the same path a person's tap
 * does, so what a screenshot shows is what the screen does:
 *
 * - [address] pre-fills the address field, [autoConnect] then taps Connect.
 * - [signInToken], on a harness that wants a session, opens the sign-in sheet and submits the token,
 *   as pasting it would; [openSignIn] opens the sheet and leaves it open.
 * - [reset] forgets everything the app remembered (the hosts, the settings, the Keychain) before
 *   the screen is built, so a scenario does not depend on the one before it.
 * - [onOutcome] hears the one result line (see [DshApp]) so a script can read what happened.
 * - [connectMode] switches the Connect screen to `lan` or `relay` before anything is drawn.
 * - The pairing arguments open the Pair screen ([openPair], or any of the others, which imply it) and drive it
 *   as a person would: [pairUrl] and [pairCode] fill the form and [pairSubmit] taps Pair; [pairPayload] hands the
 *   controller the text a QR scan would have decoded (a simulator has no camera); [pairScanner] opens the camera
 *   sheet. [onPairOutcome] hears the pairing result line (`DSH_PAIR_RESULT …`).
 * - [onProbe] hears one `DSH_PROBE authority=… outcome=…` line per probe of a remembered host.
 * - [onSessions] hears how many sessions the session store holds once it has read the list after connecting, and
 *   again after every reconnect (`DSH_SESSIONS_RESULT listed count=…`, or `DSH_SESSIONS_RESULT failed reason=…`).
 * - [reconnectOnce] starts a fresh connection once, after the first list has been read: the call the app makes when
 *   it comes back from the background, which a simulator script cannot otherwise cause without restarting the app.
 * - [openDrawer] opens the session list as soon as the main screen shows (on a phone, where it is a drawer), and
 *   [onDrawer] hears `DSH_DRAWER_RESULT shown docked=… sessions=…` once the list is on screen with sessions in it.
 * - [openSession] opens a session once the list has been read, as a tap on its row does: `first` for the list's first
 *   session, or a session id. [chatScroll] says where the transcript is left ([ChatScroll]), and [onChat] hears
 *   `DSH_CHAT_RESULT opened rows=… session=… more=…` once it is laid out (then `older …` after a page back), or
 *   `DSH_CHAT_RESULT failed reason=…` when there is no such session.
 * - [composerDraft] is what the opened session's composer starts with; [chatDrive] sends a prompt, presses Stop or
 *   brings the keyboard up once it is open, answers the request a turn puts to the reader, reconnects while it is open
 *   ([ChatDrive]), and [onChat] hears what came of it.
 * - [focusAddress] moves focus into the Connect screen's address field, which brings the software keyboard up, and
 *   [onKeyboard] hears `DSH_KEYBOARD_RESULT field=address ime=… bottom=… window=… above=yes|no` once the screen has
 *   settled on it ([ConnectKeyboardProbe]).
 */
internal class LaunchRequest(
    val address: String? = null,
    val autoConnect: Boolean = false,
    val openSignIn: Boolean = false,
    val signInToken: String? = null,
    val reset: Boolean = false,
    val connectMode: String? = null,
    val openPair: Boolean = false,
    val pairUrl: String? = null,
    val pairCode: String? = null,
    val pairSubmit: Boolean = false,
    val pairPayload: String? = null,
    val pairScanner: Boolean = false,
    val onOutcome: ((String) -> Unit)? = null,
    val onPairOutcome: ((String) -> Unit)? = null,
    val onProbe: ((String) -> Unit)? = null,
    val onSessions: ((String) -> Unit)? = null,
    val reconnectOnce: Boolean = false,
    val openDrawer: Boolean = false,
    val onDrawer: ((String) -> Unit)? = null,
    val openSession: String? = null,
    val chatScroll: ChatScroll? = null,
    val onChat: ((String) -> Unit)? = null,
    /** `-DshComposerDraft`: what the opened session's composer starts with. */
    val composerDraft: String? = null,
    /** `-DshPrompt`, `-DshSendPrompt`, `-DshPressStop`, `-DshFocusComposer`: what the chat pane does once open. */
    val chatDrive: ChatDrive = ChatDrive(),
    /** `-DshFocusAddress`: the software keyboard up over the Connect screen's address field. */
    val focusAddress: Boolean = false,
    val onKeyboard: ((String) -> Unit)? = null,
) {
    /** Whether the app starts on the Pair screen. */
    val startsPairing: Boolean
        get() = openPair || pairUrl != null || pairCode != null || pairSubmit || pairPayload != null || pairScanner
}

/** The port a bare host is given, as the Android Connect screen gives it. */
private const val DEFAULT_PORT = "3080"

/**
 * The iOS app: the shared Connect screen until a harness answers, then the main screen: the shared session list
 * beside (on a phone, in a drawer over) a chat pane. Pairing with a relay is the shared Pair screen, opened from the
 * Connect screen's Relay mode. Which of them shows is [rootRouteOf], the decision the Android root makes too; iOS has
 * no Settings screen yet.
 *
 * The chat pane is the open session's transcript under the shared top bar, with the shared composer below it
 * ([IosChatPane], the shared chat content: it sends, stops and lifts its composer as Android does).
 * Before a session is opened it is the Connected page: which harness, its home directory, how many sessions it has, and
 * this client's protocol baseline, with a way to disconnect. The list's overflow menu has Disconnect too.
 */
@Composable
internal fun DshApp(app: IosApp, request: LaunchRequest) {
    DshTheme(hapticPlatform = IosHapticPlatform) {
        val state by app.controller.state.collectAsState()
        val link by app.link.state.collectAsState()
        val pairState by app.pair.state.collectAsState()
        val connected = rootRouteOf(link.phase, link.hasConnected, showSettings = false, showPair = false) == RootRoute.Main
        // The Pair screen is a destination of the app, like the Android root's `showPair`.
        var pairing by remember { mutableStateOf(request.startsPairing) }
        var pairPrefill by remember { mutableStateOf<String?>(null) }
        // The debug launch that asks for the camera sheet gets it once, not each time Pair is reopened.
        var scanFirst by remember { mutableStateOf(request.pairScanner) }

        // The Recent rows are probed once when the controller is built, and a verdict was otherwise never revisited:
        // a row that read Unreachable (the relay was asleep, the phone had no signal at launch) stayed that way for the
        // life of the process. Android re-probes on every resume; this is the same for iOS, when the Connect screen
        // comes back (after a disconnect, or after closing Pair) and when the app returns to the foreground.
        var firstComposition by remember { mutableStateOf(true) }
        LaunchedEffect(connected, pairing) {
            if (firstComposition) {
                // The controller's own first pass is already running.
                firstComposition = false
            } else if (!connected && !pairing) {
                app.controller.refreshRecent()
            }
        }
        val connectScreenShown by rememberUpdatedState(!connected && !pairing)
        // iOS suspends an app soon after it leaves the screen, and its socket goes with it. Coming back from the
        // background starts a fresh generation at once rather than waiting for the old one to notice; pulling down
        // Control Center makes the app inactive without sending it to the background, and the socket is still there.
        DisposableEffect(app) {
            var wasInBackground = false
            val center = NSNotificationCenter.defaultCenter
            val background = center.addObserverForName(
                name = UIApplicationDidEnterBackgroundNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ -> wasInBackground = true }
            val active = center.addObserverForName(
                name = UIApplicationDidBecomeActiveNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                if (connectScreenShown) app.controller.refreshRecent()
                if (wasInBackground) app.link.reconnectIfNeeded()
                wasInBackground = false
            }
            onDispose {
                center.removeObserver(background)
                center.removeObserver(active)
            }
        }

        // The count on the Connected page (and the line a script reads) is the session store's list: the store reads
        // it itself whenever the link connects, and keeps it current from the host's pushes after that. Each time the
        // link reaches CONNECTED (the first time, and again after every reconnect) the list is read once more here,
        // because the store's own read is not observable: a list equal to the one before is no new value of
        // `sessions`. A reconnect keeps the page, so it keeps the count too; another harness starts without one.
        // Watched in a coroutine and keyed on the generation, not on Compose state: a reconnect on a fast network can
        // pass through RECONNECTING and back within one frame, which a recomposition would never see. A coroutine on
        // the main thread can miss it as well, while the chat pane composes (a StateFlow hands over only its latest
        // value); what still reaches it is the new generation's number in the state (`connectedGeneration`).
        val sessions by app.sessionStore.sessions.collectAsState()
        var listedHost by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(app) {
            var handled: HostGeneration? = null
            var reading: Job? = null
            var reconnectPending = request.reconnectOnce
            app.link.state.collect { state ->
                if (state.host?.id != listedHost) listedHost = null
                val generation = app.link.generation
                if (state.phase != ConnectionPhase.CONNECTED || generation == null || generation === handled) return@collect
                handled = generation
                val host = state.host?.id
                reading?.cancel()
                reading = launch {
                    when (val result = app.sessionStore.refreshSessions(quiet = true)) {
                        SessionListRefresh.Done -> {
                            listedHost = host
                            request.onSessions?.invoke("DSH_SESSIONS_RESULT listed count=${app.sessionStore.sessions.value.size}")
                            if (reconnectPending) {
                                reconnectPending = false
                                app.link.reconnectIfNeeded()
                            }
                        }
                        is SessionListRefresh.Failed ->
                            request.onSessions?.invoke("DSH_SESSIONS_RESULT failed reason=${result.reason.orEmpty()}")
                    }
                }
            }
        }
        val sessionCount = sessions.size.takeIf { listedHost != null && listedHost == link.host?.id }

        // Debug-launch behaviours, once. A tap on Connect is `connectManual`, so this is one.
        LaunchedEffect(Unit) {
            request.connectMode?.let(app.controller::setMode)
            val address = request.address
            if (request.autoConnect && address != null) app.controller.connectManual(address, DEFAULT_PORT)
            if (request.startsPairing) {
                request.pairUrl?.let(app.pair::setUrl)
                request.pairCode?.let(app.pair::setCode)
                val payload = request.pairPayload
                // A payload is a scan: the controller claims at once, as it does for a decoded QR. Otherwise
                // the Pair button is tapped.
                if (payload != null) app.pair.onScanned(payload) else if (request.pairSubmit) app.pair.submit()
            }
        }
        // The pairing result line a script reads (a success is reported by the ports, when the relay has answered).
        request.onPairOutcome?.let { report ->
            LaunchedEffect(pairState.failure) {
                pairState.failure?.let { report("DSH_PAIR_RESULT failed kind=${it.kind()}") }
            }
        }
        LaunchedEffect(state.failure) {
            if (state.failure is ConnectFailure.Unauthenticated) {
                val token = request.signInToken
                if (token != null) {
                    app.controller.setSignInOpen(true)
                    delay(SIGN_IN_PAUSE_MS)
                    app.controller.signIn(token)
                } else if (request.openSignIn) {
                    app.controller.setSignInOpen(true)
                }
            }
        }
        // The result line a script reads: connected, or why not.
        request.onOutcome?.let { report ->
            LaunchedEffect(link.phase, link.host) {
                if (link.phase == ConnectionPhase.CONNECTED) {
                    report(
                        "DSH_CONNECT_RESULT connected authority=${link.host?.authority} " +
                            "home=${link.description?.home} protocol=${DshCore.PROTOCOL_BASELINE}",
                    )
                }
            }
            LaunchedEffect(state.failure, state.attempted, state.connecting) {
                val failure = state.failure
                if (failure != null && failure !is ConnectFailure.InvalidInput && !state.connecting && !connected) {
                    report("DSH_CONNECT_RESULT failed kind=${failure.kind()} authority=${state.attempted.orEmpty()} detail=")
                }
            }
        }

        when (rootRouteOf(link.phase, link.hasConnected, showSettings = false, showPair = pairing)) {
            RootRoute.Pair -> {
                PairContent(
                    state = pairState,
                    controller = app.pair,
                    onClose = {
                        pairing = false
                        pairPrefill = null
                        scanFirst = false
                    },
                    scanner = { close, decoded, typeCode -> IosQrScanner(close, decoded, typeCode) },
                    prefillUrl = pairPrefill,
                    initiallyScanning = scanFirst,
                    scrollToFailure = true,
                )
            }
            RootRoute.Main -> CompositionLocalProvider(LocalSessionStore provides app.sessionStore) {
                val openSession = request.openSession
                if (openSession != null) {
                    LaunchedEffect(openSession) {
                        // Once the list has been read, the way a tap on the row opens it.
                        val rows = app.sessionStore.sessions.first { it.isNotEmpty() }
                        val id = if (openSession.equals("first", ignoreCase = true)) {
                            rows.first().sessionId
                        } else {
                            rows.firstOrNull { it.sessionId == openSession }?.sessionId
                        }
                        if (id == null) {
                            request.onChat?.invoke("DSH_CHAT_RESULT failed reason=no-such-session session=$openSession")
                        } else {
                            app.sessionStore.openSession(id)
                        }
                    }
                }
                IosMainPage(
                    openDrawer = request.openDrawer,
                    onDrawerShown = request.onDrawer,
                    onDisconnect = { app.controller.cancelConnect() },
                ) { onOpenSessions ->
                    IosChatPane(
                        onOpenSessions = onOpenSessions,
                        scroll = request.chatScroll,
                        onReport = request.onChat,
                        initialDraft = request.composerDraft,
                        drive = request.chatDrive,
                        // The call coming back to the foreground makes (the observer above).
                        onReconnect = { app.link.reconnectIfNeeded() },
                    ) {
                        ConnectedPage(
                            authority = link.host?.authority.orEmpty(),
                            name = link.host?.name,
                            home = link.description?.home ?: link.host?.lastHome,
                            sessionCount = sessionCount,
                            onOpenSessions = onOpenSessions,
                            onDisconnect = { app.controller.cancelConnect() },
                        )
                    }
                }
            }
            RootRoute.Connect, RootRoute.Settings -> ConnectKeyboardProbe(if (request.focusAddress) request.onKeyboard else null) {
                ConnectContent(
                    state = state,
                    controller = app.controller,
                    features = IosConnectFeatures,
                    onOpenSettings = {},
                    onPair = { url ->
                        pairPrefill = url
                        pairing = true
                    },
                    initialHost = request.address.orEmpty(),
                )
            }
        }
    }
}

/**
 * [content] (the Connect screen) with the software keyboard brought up over its address field, for `-DshFocusAddress`;
 * just [content] when [onReport] is null. Once the field has been placed, focus moves into it as a tap does
 * ([AddressFieldProbe]); once the keyboard is up and the inset has been still for ten frames, [onReport] hears
 * `DSH_KEYBOARD_RESULT field=address ime=… bottom=… window=… above=yes|no` (pixels: the keyboard's height, the field's
 * bottom and the window's height; `above=yes` when the field ends on or above the keyboard). Every shared screen pads
 * itself with `safeDrawing`, keyboard included, and the view controller leaves the keyboard to the insets
 * (`OnFocusBehavior.DoNothing`), so this is the check that a focused field there is not left under the keyboard.
 */
@Composable
private fun ConnectKeyboardProbe(onReport: ((String) -> Unit)?, content: @Composable () -> Unit) {
    if (onReport == null) {
        content()
        return
    }
    val focus = remember { FocusRequester() }
    val fieldBottom = remember { mutableIntStateOf(0) }
    val imeBottom = remember { mutableIntStateOf(0) }
    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
    SideEffect { imeBottom.intValue = ime }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val probe = remember { AddressFieldProbe(focus) { fieldBottom.intValue = it.bottom.roundToInt() } }
    CompositionLocalProvider(LocalAddressFieldProbe provides probe) { content() }
    LaunchedEffect(probe) {
        snapshotFlow { fieldBottom.intValue > 0 }.first { it }
        withFrameNanos { }
        focus.requestFocus()
        withTimeoutOrNull(KEYBOARD_WAIT_MS) { snapshotFlow { imeBottom.intValue > 0 }.first { it } }
        var still = 0
        var last = -1
        var frames = 0
        while (still < 10 && frames < KEYBOARD_SETTLE_FRAMES) {
            withFrameNanos { }
            frames++
            val now = imeBottom.intValue
            still = if (now == last && now > 0) still + 1 else 0
            last = now
        }
        val keyboard = imeBottom.intValue
        val bottom = fieldBottom.intValue
        val above = keyboard > 0 && bottom <= windowHeight - keyboard + 1
        onReport(
            "DSH_KEYBOARD_RESULT field=address ime=$keyboard bottom=$bottom window=$windowHeight above=${if (above) "yes" else "no"}",
        )
    }
}

/** How long `-DshFocusAddress` waits for the software keyboard to come up. */
private const val KEYBOARD_WAIT_MS = 15_000L

/** A bound on the wait for the screen to settle on the keyboard: about five seconds of frames. */
private const val KEYBOARD_SETTLE_FRAMES = 300

/** The verdicts a script greps for in `DSH_PAIR_RESULT failed kind=…`. */
private fun PairFailure.kind(): String = when (this) {
    PairFailure.NotAPairingCode -> "NOT_A_PAIRING_CODE"
    is PairFailure.TooNew -> "TOO_NEW"
    PairFailure.Expired -> "EXPIRED"
    PairFailure.Rejected -> "REJECTED"
    is PairFailure.RateLimited -> "RATE_LIMITED"
    is PairFailure.NotARelay -> "NOT_A_RELAY"
    is PairFailure.HostRefused -> "HOST_REFUSED"
    is PairFailure.Unreachable -> "UNREACHABLE"
    is PairFailure.CertificateMismatch -> "CERTIFICATE_PIN"
    PairFailure.InvalidUrl -> "INVALID_URL"
    PairFailure.InvalidCode -> "INVALID_CODE"
}

/** Long enough for the sheet to be on screen (and in a screenshot) before the token is submitted. */
private const val SIGN_IN_PAUSE_MS = 2_500L

/** The verdicts a script greps for; the names are the ones the first iOS screen reported. */
private fun ConnectFailure.kind(): String = when (this) {
    ConnectFailure.InvalidInput -> "INVALID_ADDRESS"
    is ConnectFailure.DifferentSubnet -> "UNREACHABLE"
    ConnectFailure.Timeout -> "TIMEOUT"
    ConnectFailure.Refused -> "REFUSED"
    ConnectFailure.TrustFence, ConnectFailure.PairingRequired -> "TRUST_FENCE"
    ConnectFailure.Unauthenticated, ConnectFailure.RelayUnauthenticated -> "UNAUTHENTICATED"
    ConnectFailure.CertificateChanged -> "CERTIFICATE_PIN"
    ConnectFailure.DnsFailure -> "DNS"
    ConnectFailure.NotAHarness -> "NOT_A_HARNESS"
    ConnectFailure.TlsFailure -> "TLS"
    ConnectFailure.StreamsBlocked -> "STREAMS_BLOCKED"
    is ConnectFailure.Other -> "OTHER"
}

/**
 * The chat pane until the chat moves: which harness, where its home is, how many sessions it has, and a way back to
 * the form. [sessionCount] is null until the list call has answered, and stays null if it failed. [onOpenSessions]
 * (null where the list is docked beside this pane) opens the session list.
 */
@Composable
private fun ConnectedPage(
    authority: String,
    name: String?,
    home: String?,
    sessionCount: Int?,
    onOpenSessions: (() -> Unit)?,
    onDisconnect: () -> Unit,
) {
    val colors = DsTheme.colors
    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = DsSpacing.xlarge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(DsSpacing.xxlarge))
            MascotMark(size = 132.dp, state = MascotState.Done)
            Spacer(Modifier.height(DsSpacing.large))
            Text(
                stringResource(Res.string.common_connected),
                style = DsType.large20,
                color = colors.labelPrimary,
                textAlign = TextAlign.Center,
            )
            if (!name.isNullOrBlank() && name != authority) {
                Spacer(Modifier.height(DsSpacing.xsmall))
                Text(name, style = DsType.rowTitle, color = colors.labelPrimary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(DsSpacing.xsmall))
            Text(
                authority,
                style = DsType.mono12,
                color = colors.labelTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(DsSpacing.xlarge))
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!home.isNullOrBlank()) {
                    Text(stringResource(Res.string.connect_harness_home, home), style = DsType.small13, color = colors.labelSecondary, textAlign = TextAlign.Center)
                }
                if (sessionCount != null) {
                    Text(
                        stringResource(Res.string.connect_session_count, localizedCount(sessionCount)),
                        style = DsType.small13,
                        color = colors.labelSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    stringResource(Res.string.connect_protocol_baseline, DshCore.PROTOCOL_BASELINE),
                    style = DsType.small13,
                    color = colors.labelTertiary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            if (onOpenSessions != null) {
                DsConnectButton(
                    text = stringResource(Res.string.ink_chat_sessions),
                    onClick = onOpenSessions,
                    large = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(DsSpacing.small))
            }
            DsConnectButton(
                text = stringResource(Res.string.settings_connection_disconnect),
                onClick = onDisconnect,
                variant = ConnectButtonVariant.Outline,
                large = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(DsSpacing.large))
        }
    }
}
