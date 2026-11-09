package com.labteto.dshmobile.ui.screens.pair

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.common_back
import com.labteto.dshmobile.shared.resources.pair_code_hint
import com.labteto.dshmobile.shared.resources.pair_code_label
import com.labteto.dshmobile.shared.resources.pair_fail_certificate
import com.labteto.dshmobile.shared.resources.pair_fail_code
import com.labteto.dshmobile.shared.resources.pair_fail_expired
import com.labteto.dshmobile.shared.resources.pair_fail_host_refused
import com.labteto.dshmobile.shared.resources.pair_fail_not_a_code
import com.labteto.dshmobile.shared.resources.pair_fail_not_a_relay
import com.labteto.dshmobile.shared.resources.pair_fail_rate_limited
import com.labteto.dshmobile.shared.resources.pair_fail_rejected
import com.labteto.dshmobile.shared.resources.pair_fail_too_new
import com.labteto.dshmobile.shared.resources.pair_fail_unreachable
import com.labteto.dshmobile.shared.resources.pair_fail_url
import com.labteto.dshmobile.shared.resources.pair_hero_title
import com.labteto.dshmobile.shared.resources.pair_key_first_use
import com.labteto.dshmobile.shared.resources.pair_key_plaintext
import com.labteto.dshmobile.shared.resources.pair_key_verified
import com.labteto.dshmobile.shared.resources.pair_manual_title
import com.labteto.dshmobile.shared.resources.pair_name_label
import com.labteto.dshmobile.shared.resources.pair_scan
import com.labteto.dshmobile.shared.resources.pair_scanned
import com.labteto.dshmobile.shared.resources.pair_step_run
import com.labteto.dshmobile.shared.resources.pair_step_scan
import com.labteto.dshmobile.shared.resources.pair_submit
import com.labteto.dshmobile.shared.resources.pair_subtitle
import com.labteto.dshmobile.shared.resources.pair_title
import com.labteto.dshmobile.shared.resources.pair_url_hint
import com.labteto.dshmobile.shared.resources.pair_url_label
import com.labteto.dshmobile.shared.resources.pair_working
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.PlatformBackHandler
import com.labteto.dshmobile.ui.components.localizedCount
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.connect.InkTextField
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** The command the pairing steps name; a literal, never translated. */
private const val PAIR_COMMAND = "dsh relay pair"

/** How long to wait for a new failure card to be laid out before scrolling to it. */
private const val FAILURE_SCROLL_DELAY_MS = 120L

/**
 * Enrol this device with a relay, by QR or by typed code (design 05).
 *
 * Both routes end in the same `POST /relay/pair`, but they do not establish the same thing, and the
 * screen says which one happened rather than reporting "paired" twice. The QR carries the relay's
 * public key, so the claim is verified before a byte leaves; a typed address has no key to check
 * against until the relay answers, so it is trusted on first contact.
 *
 * The pairing code itself is only issuable from the machine running the harness. That is the
 * property the whole flow rests on: an endpoint that minted its own invitations would authenticate
 * nobody.
 */
@Composable
fun PairContent(
    state: PairUiState,
    controller: PairController,
    onClose: () -> Unit,
    scanner: PairScanner,
    prefillUrl: String? = null,
    /** Open with the scanner already up; a debug launch uses it to show the camera sheet without a tap. */
    initiallyScanning: Boolean = false,
    /**
     * Scroll the failure card into view when one appears.
     *
     * The card is the last thing on a long form, so on a short screen it can arrive below the fold and the
     * person sees a Pair button that did nothing. The Android screen has never scrolled to it and keeps it
     * that way (pixel for pixel); iOS asks for it.
     */
    scrollToFailure: Boolean = false,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    PlatformBackHandler(enabled = true, onBack = onClose)

    LaunchedEffect(prefillUrl) { prefillUrl?.let(controller::prefill) }
    // The connection is already under way by the time this fires; the screen's job is done.
    // Consumed before closing, not after: `onClose` removes this composable, which cancels the
    // effect — so a clear that came second would never run, and the flag would still be set the
    // next time pairing opened.
    LaunchedEffect(state.paired) {
        if (state.paired != null) {
            controller.acknowledgePaired()
            onClose()
        }
    }
    LaunchedEffect(state.failure) {
        if (state.failure != null) haptics.reject()
    }

    // The scanner is a sheet over this screen, not a separate destination: the typed form stays
    // composed underneath it, so closing the camera lands on the fallback already in place rather
    // than on a blank screen — which is what a refused camera permission used to look like.
    // Remembered across configuration changes: turning the phone while the camera is open used to
    // tear the sheet down, which reads as the scanner "closing itself" mid-scan.
    var scanning by rememberSaveable { mutableStateOf(initiallyScanning) }
    var focusCode by remember { mutableStateOf(false) }
    val codeFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scrollState = rememberScrollState()
    LaunchedEffect(state.failure, scrollToFailure) {
        if (scrollToFailure && state.failure != null) {
            // Let the card be measured first: until then the scroll range does not include it.
            delay(FAILURE_SCROLL_DELAY_MS)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    LaunchedEffect(focusCode) {
        if (focusCode) {
            // Coming back from the scanner: the typed form is below the fold, so bring the code
            // field (and its keyboard) into view rather than leaving the person on the scan button.
            scrollState.animateScrollTo(scrollState.maxValue)
            codeFocus.requestFocus()
            keyboard?.show()
            focusCode = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
                // A plain top bar, like the design's: back, the screen's name, and the balance space
                // that keeps the title centred rather than leaning on the back arrow.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = DsSpacing.touchTarget)
                        .padding(horizontal = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DsIconButton(
                        icon = DsIcons.ArrowBack,
                        contentDescription = stringResource(Res.string.common_back),
                        onClick = onClose,
                    )
                    Text(
                        stringResource(Res.string.pair_title),
                        style = DsType.large20,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = DsSpacing.small),
                    )
                    Spacer(Modifier.width(DsSpacing.touchTarget))
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = DsSpacing.large)
                        .padding(top = DsSpacing.small, bottom = DsSpacing.xlarge),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.large),
                ) {
                    PairHero()

                    // The one unambiguous action on the screen, as the design draws it. Everything
                    // else here is a fallback for the case where the camera cannot do its job.
                    DsButton(
                        text = stringResource(Res.string.pair_scan),
                        onClick = {
                            haptics.tap()
                            scanning = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        variant = DsButtonVariant.Primary,
                        size = DsButtonSize.Large,
                        icon = DsIcons.QrCodeScanner,
                    )

                    state.scanned?.let { payload ->
                        Text(
                            stringResource(Res.string.pair_scanned, payload.url),
                            style = DsType.small13,
                            color = colors.labelSecondary,
                        )
                    }

                    PairSteps()

                    // ---- Manual entry ------------------------------------------------
                    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        Text(
                            stringResource(Res.string.pair_manual_title).uppercase(),
                            style = DsType.label12,
                            color = colors.labelTertiary,
                        )
                        InkTextField(
                            value = state.url,
                            onValueChange = controller::setUrl,
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(Res.string.pair_url_label),
                            placeholder = stringResource(Res.string.pair_url_hint),
                            mono = true,
                            enabled = !state.busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        InkTextField(
                            value = state.code,
                            onValueChange = { controller.setCode(it.take(8)) },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(Res.string.pair_code_label),
                            mono = true,
                            enabled = !state.busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            focusRequester = codeFocus,
                        )
                        Text(
                            stringResource(Res.string.pair_code_hint),
                            style = DsType.xsmall12,
                            color = colors.labelTertiary,
                        )
                        InkTextField(
                            value = state.deviceName,
                            onValueChange = controller::setDeviceName,
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(Res.string.pair_name_label),
                            enabled = !state.busy,
                        )
                        DsButton(
                            text = stringResource(
                                if (state.busy) Res.string.pair_working else Res.string.pair_submit,
                            ),
                            onClick = controller::submit,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy,
                            variant = DsButtonVariant.Info,
                            size = DsButtonSize.Large,
                        )
                    }

                    TransportNotice(state.provenance())

                    state.failure?.let { failure -> PairFailureCard(failure) }
                }
            }

            AnimatedVisibility(
                visible = scanning,
                enter = fadeIn(tween(160)) + slideInVertically(tween(260)) { it },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it },
            ) {
                // Positional: a function type's parameter names are documentation, not argument names.
                scanner(
                    { scanning = false },
                    { text ->
                        scanning = false
                        controller.onScanned(text)
                    },
                    {
                        scanning = false
                        focusCode = true
                    },
                )
            }
        }
    }
}

/**
 * The screen's masthead: mascot, what to do, and why the camera is worth allowing.
 *
 * The subtitle keeps the design's wording but not its length: it says both routes exist and both
 * expire, which is the one thing a person needs before choosing between them.
 */
@Composable
private fun PairHero() {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        MascotMark(size = 48.dp, state = MascotState.Idle)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.pair_hero_title),
                style = DsType.large20,
                color = colors.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(Res.string.pair_subtitle),
                style = DsType.small13,
                color = colors.labelTertiary,
            )
        }
    }
}

/**
 * The design's two numbered steps, with `dsh relay pair` set as the command it is rather than as
 * prose. The command is passed through the translated sentence as an argument, so a translator can
 * put it wherever their grammar needs it; the span styling finds it in the finished string.
 */
@Composable
private fun PairSteps() {
    val colors = DsTheme.colors
    val run = stringResource(Res.string.pair_step_run, PAIR_COMMAND)
    val runStyled = remember(run, colors) {
        buildAnnotatedString {
            val at = run.indexOf(PAIR_COMMAND)
            if (at < 0) {
                append(run)
            } else {
                append(run.substring(0, at))
                withStyle(
                    SpanStyle(
                        fontFamily = com.labteto.dshmobile.ui.theme.DsType.codeFont,
                        color = colors.labelPrimary,
                        background = colors.bgLayer3,
                    ),
                ) {
                    append(PAIR_COMMAND)
                }
                append(run.substring(at + PAIR_COMMAND.length))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
        PairStep(number = 1, text = runStyled)
        PairStep(number = 2, text = AnnotatedString(stringResource(Res.string.pair_step_scan)))
    }
}

@Composable
private fun PairStep(number: Int, text: AnnotatedString) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(DsShapes.chip)
                .background(colors.bgLayer3),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = DsType.mono12,
                color = colors.labelSecondary,
            )
        }
        Text(
            text,
            style = DsType.std14,
            color = colors.labelSecondary,
        )
    }
}

/**
 * What this pairing will actually establish about the relay.
 *
 * Shown before the user commits rather than after. "Paired" reads the same in all three cases, and
 * only one of them means the key was checked — the client integration contract asks for the
 * plaintext case to be stated plainly, and the trust-on-first-use case deserves the same honesty.
 */
@Composable
private fun TransportNotice(provenance: KeyProvenance) {
    val colors = DsTheme.colors
    val (text, warn) = when (provenance) {
        KeyProvenance.Verified -> stringResource(Res.string.pair_key_verified) to false
        KeyProvenance.TrustedOnFirstUse -> stringResource(Res.string.pair_key_first_use) to true
        KeyProvenance.Plaintext -> stringResource(Res.string.pair_key_plaintext) to true
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.buttonLarge,
        color = if (warn) colors.warnTertiary else colors.accentTertiary,
        border = BorderStroke(1.dp, if (warn) colors.warnBorder else colors.accentBorder),
    ) {
        Text(
            text,
            style = DsType.small13,
            color = if (warn) colors.warnText else colors.accent,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = DsSpacing.medium),
        )
    }
}

/** Why the claim did not produce a credential, in terms of what to do next. */
@Composable
private fun PairFailureCard(failure: PairFailure) {
    val colors = DsTheme.colors
    val body = when (failure) {
        PairFailure.NotAPairingCode -> stringResource(Res.string.pair_fail_not_a_code)
        is PairFailure.TooNew -> stringResource(Res.string.pair_fail_too_new, localizedCount(failure.version))
        PairFailure.Expired -> stringResource(Res.string.pair_fail_expired)
        PairFailure.Rejected -> stringResource(Res.string.pair_fail_rejected)
        is PairFailure.RateLimited ->
            stringResource(Res.string.pair_fail_rate_limited, localizedCount(failure.seconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()))
        is PairFailure.NotARelay -> stringResource(Res.string.pair_fail_not_a_relay, failure.authority)
        is PairFailure.HostRefused -> stringResource(Res.string.pair_fail_host_refused, failure.authority)
        is PairFailure.Unreachable -> stringResource(Res.string.pair_fail_unreachable, failure.authority)
        is PairFailure.CertificateMismatch ->
            stringResource(Res.string.pair_fail_certificate, failure.authority)
        PairFailure.InvalidUrl -> stringResource(Res.string.pair_fail_url)
        PairFailure.InvalidCode -> stringResource(Res.string.pair_fail_code)
    }
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
