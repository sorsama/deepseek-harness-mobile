package com.labteto.dshmobile.ui.screens.pair

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * Enrol this device with a relay, by QR or by typed code.
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
fun PairScreen(
    onClose: () -> Unit,
    prefillUrl: String? = null,
    viewModel: PairViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    BackHandler(onBack = onClose)

    LaunchedEffect(prefillUrl) { prefillUrl?.let(viewModel::prefill) }
    // The connection is already under way by the time this fires; the screen's job is done.
    LaunchedEffect(state.paired) { if (state.paired != null) onClose() }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        // A null payload is a cancel or a denied camera. Both leave the typed form on screen, which
        // is the fallback, so there is nothing to announce.
        result.contents?.let(viewModel::onScanned)
    }
    val scanPrompt = stringResource(R.string.pair_scan_prompt)

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(DsSpacing.xlarge),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.large),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DsIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    onClick = onClose,
                )
                Text(stringResource(R.string.pair_title), style = DsType.large20, color = colors.labelPrimary)
            }

            Text(
                stringResource(R.string.pair_subtitle),
                style = DsType.std14,
                color = colors.labelSecondary,
            )

            // The trust statement belongs here rather than on the connect screen: this is the
            // moment the reach is actually granted, and on the screen before it was one of six
            // lines of prose nobody had a reason to read yet.
            Text(
                stringResource(R.string.connect_relay_banner),
                style = DsType.small13,
                color = colors.labelTertiary,
            )

            DsButton(
                text = stringResource(R.string.pair_scan),
                onClick = {
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt(scanPrompt)
                            .setBeepEnabled(false)
                            // The pairing page is often read off a laptop held at an angle; locking
                            // to portrait makes that harder for no benefit.
                            .setOrientationLocked(false),
                    )
                },
                enabled = !state.busy,
                variant = DsButtonVariant.Info,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                SectionHeader(stringResource(R.string.pair_manual_title))
                TextField(
                    value = state.url,
                    onValueChange = viewModel::setUrl,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.pair_url_label)) },
                    placeholder = { Text(stringResource(R.string.pair_url_hint), style = DsType.std14) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = pairFieldColors(),
                )
                TextField(
                    value = state.code,
                    onValueChange = viewModel::setCode,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.pair_code_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = pairFieldColors(),
                )
                TextField(
                    value = state.deviceName,
                    onValueChange = viewModel::setDeviceName,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.pair_name_label)) },
                    colors = pairFieldColors(),
                )
                DsButton(
                    text = stringResource(if (state.busy) R.string.pair_working else R.string.pair_submit),
                    onClick = viewModel::submit,
                    enabled = !state.busy,
                    variant = DsButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            TransportNotice(state.provenance())

            state.failure?.let { PairFailureBlock(it) }

            Spacer(Modifier.height(DsSpacing.xlarge))
        }
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
        KeyProvenance.Verified -> stringResource(R.string.pair_key_verified) to false
        KeyProvenance.TrustedOnFirstUse -> stringResource(R.string.pair_key_first_use) to true
        KeyProvenance.Plaintext -> stringResource(R.string.pair_key_plaintext) to true
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (warn) colors.warnTertiary else colors.hoverSolid,
    ) {
        Text(
            text,
            style = DsType.small13,
            color = if (warn) colors.warnLabel else colors.labelTertiary,
            modifier = Modifier.padding(DsSpacing.medium),
        )
    }
}

/** Why the claim did not produce a credential, in terms of what to do next. */
@Composable
private fun PairFailureBlock(failure: PairFailure) {
    val colors = DsTheme.colors
    val body = when (failure) {
        PairFailure.NotAPairingCode -> stringResource(R.string.pair_fail_not_a_code)
        is PairFailure.TooNew -> stringResource(R.string.pair_fail_too_new, failure.version)
        PairFailure.Expired -> stringResource(R.string.pair_fail_expired)
        PairFailure.Rejected -> stringResource(R.string.pair_fail_rejected)
        is PairFailure.RateLimited -> stringResource(R.string.pair_fail_rate_limited, failure.seconds)
        is PairFailure.NotARelay -> stringResource(R.string.pair_fail_not_a_relay, failure.authority)
        is PairFailure.HostRefused -> stringResource(R.string.pair_fail_host_refused, failure.authority)
        is PairFailure.Unreachable -> stringResource(R.string.pair_fail_unreachable, failure.authority)
        is PairFailure.CertificateMismatch ->
            stringResource(R.string.pair_fail_certificate, failure.authority)
        PairFailure.InvalidUrl -> stringResource(R.string.pair_fail_url)
        PairFailure.InvalidCode -> stringResource(R.string.pair_fail_code)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.warnTertiary,
    ) {
        Row(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalAlignment = Alignment.Top,
        ) {
            StateDot(StateDotState.Error, size = 8.dp)
            Spacer(Modifier.width(DsSpacing.xsmall))
            Text(body, style = DsType.small13, color = colors.warnLabel)
        }
    }
}

@Composable
private fun pairFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = DsTheme.colors.bgLayer1,
    unfocusedContainerColor = DsTheme.colors.bgLayer1,
    focusedIndicatorColor = DsTheme.colors.accent,
    unfocusedIndicatorColor = DsTheme.colors.borderL2,
    cursorColor = DsTheme.colors.accent,
)
