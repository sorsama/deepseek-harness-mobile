package com.labteto.dshmobile.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.core.DshCore
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.common_connected
import com.labteto.dshmobile.shared.resources.connect_harness_home
import com.labteto.dshmobile.shared.resources.connect_protocol_baseline
import com.labteto.dshmobile.shared.resources.settings_connection_disconnect
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.screens.connect.ConnectButtonVariant
import com.labteto.dshmobile.ui.screens.connect.ConnectContent
import com.labteto.dshmobile.ui.screens.connect.ConnectFailure
import com.labteto.dshmobile.ui.screens.connect.DsConnectButton
import com.labteto.dshmobile.ui.screens.pair.PairContent
import com.labteto.dshmobile.ui.screens.pair.PairFailure
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

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
) {
    /** Whether the app starts on the Pair screen. */
    val startsPairing: Boolean
        get() = openPair || pairUrl != null || pairCode != null || pairSubmit || pairPayload != null || pairScanner
}

/** The port a bare host is given, as the Android Connect screen gives it. */
private const val DEFAULT_PORT = "3080"

/**
 * The iOS app: the shared Connect screen until a harness answers, then a plain "Connected" page.
 * Pairing with a relay is the shared Pair screen, opened from the Connect screen's Relay mode.
 *
 * Nothing sits behind a connection yet (the session list and the chat are later steps of the
 * port), so what a person sees on success is the result the screen has to give: which harness, its
 * home directory, and this client's protocol baseline, with a way to disconnect.
 */
@Composable
internal fun DshApp(app: IosApp, request: LaunchRequest) {
    DshTheme(hapticPlatform = IosHapticPlatform) {
        val state by app.controller.state.collectAsState()
        val link by app.link.state.collectAsState()
        val pairState by app.pair.state.collectAsState()
        val connected = link.phase == ConnectionPhase.CONNECTED ||
            (link.phase == ConnectionPhase.RECONNECTING && link.hasConnected)
        // The Pair screen is a destination of the app, like the Android root's `showPair`.
        var pairing by remember { mutableStateOf(request.startsPairing) }
        var pairPrefill by remember { mutableStateOf<String?>(null) }
        // The debug launch that asks for the camera sheet gets it once, not each time Pair is reopened.
        var scanFirst by remember { mutableStateOf(request.pairScanner) }

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

        if (pairing) {
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
        } else if (connected) {
            ConnectedPage(
                authority = link.host?.authority.orEmpty(),
                name = link.host?.name,
                home = link.description?.home ?: link.host?.lastHome,
                onDisconnect = { app.controller.cancelConnect() },
            )
        } else {
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

/** Where a connection ends up for now: which harness, where its home is, and a way back to the form. */
@Composable
private fun ConnectedPage(authority: String, name: String?, home: String?, onDisconnect: () -> Unit) {
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
                Text(
                    stringResource(Res.string.connect_protocol_baseline, DshCore.PROTOCOL_BASELINE),
                    style = DsType.small13,
                    color = colors.labelTertiary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
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
