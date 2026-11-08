package com.labteto.dshmobile.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.common_connected
import com.labteto.dshmobile.shared.resources.connect_button
import com.labteto.dshmobile.shared.resources.connect_checking
import com.labteto.dshmobile.shared.resources.connect_fail_dns
import com.labteto.dshmobile.shared.resources.connect_fail_fence_title
import com.labteto.dshmobile.shared.resources.connect_fail_invalid
import com.labteto.dshmobile.shared.resources.connect_fail_not_harness
import com.labteto.dshmobile.shared.resources.connect_fail_other
import com.labteto.dshmobile.shared.resources.connect_fail_refused
import com.labteto.dshmobile.shared.resources.connect_fail_timeout
import com.labteto.dshmobile.shared.resources.connect_fail_tls
import com.labteto.dshmobile.shared.resources.connect_fail_unauthenticated
import com.labteto.dshmobile.shared.resources.connect_failed
import com.labteto.dshmobile.shared.resources.connect_failed_fence
import com.labteto.dshmobile.shared.resources.connect_harness_home
import com.labteto.dshmobile.shared.resources.connect_host_hint
import com.labteto.dshmobile.shared.resources.connect_host_label
import com.labteto.dshmobile.shared.resources.connect_protocol_baseline
import com.labteto.dshmobile.shared.resources.connect_title
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Where the screen is in one attempt. */
private sealed interface Attempt {
    data object Idle : Attempt

    data object Checking : Attempt

    data class Done(val outcome: ConnectOutcome) : Attempt
}

/**
 * The first screen of the iOS app: an address, a Connect button and what happened.
 *
 * This is the smallest real slice of the Android Connect screen (`ConnectScreen.kt`), enough to
 * prove the whole path from a Compose Multiplatform UI through `:core` and the Darwin engine to a
 * harness and back. The remembered hosts, network scan, relay pairing and launch-token sign-in are
 * the full port and come after this step.
 */
@Composable
internal fun ConnectScreen(request: LaunchRequest) {
    var address by remember { mutableStateOf(request.address.orEmpty()) }
    var attempt by remember { mutableStateOf<Attempt>(Attempt.Idle) }
    var running by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun connect() {
        if (address.isBlank() || attempt == Attempt.Checking) return
        running?.cancel()
        attempt = Attempt.Checking
        val typed = address
        running = scope.launch {
            val outcome = connectToHarness(typed)
            attempt = Attempt.Done(outcome)
            request.onOutcome?.invoke(outcome.logLine())
        }
    }

    // The host's launch request goes down the same path as a tap on Connect.
    LaunchedEffect(Unit) {
        if (request.autoConnect) connect()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Text(
                text = stringResource(Res.string.connect_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth().testTag("address-field"),
                label = { Text(stringResource(Res.string.connect_host_label)) },
                placeholder = { Text(stringResource(Res.string.connect_host_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { connect() }),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { connect() },
                enabled = address.isNotBlank() && attempt != Attempt.Checking,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("connect-button"),
            ) {
                Text(stringResource(Res.string.connect_button))
            }
            Spacer(Modifier.height(24.dp))
            ResultArea(attempt)
        }
    }
}

@Composable
private fun ResultArea(attempt: Attempt) {
    when (attempt) {
        Attempt.Idle -> Unit
        Attempt.Checking -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.testTag("result-checking"),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(Res.string.connect_checking), style = MaterialTheme.typography.bodyLarge)
        }
        is Attempt.Done -> when (val outcome = attempt.outcome) {
            is ConnectOutcome.Connected -> ResultCard(
                tag = "result-connected",
                container = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = stringResource(Res.string.common_connected),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(outcome.authority, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(Res.string.connect_harness_home, outcome.home), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(Res.string.connect_protocol_baseline, outcome.protocol), style = MaterialTheme.typography.bodyMedium)
            }
            is ConnectOutcome.Failed -> ResultCard(
                tag = "result-failed",
                container = MaterialTheme.colorScheme.errorContainer,
            ) {
                val title = failureTitle(outcome)
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val body = failureBody(outcome)
                if (body != null) {
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(tag: String, container: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(16.dp),
        color = container,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

/**
 * The headline of a failure: "Couldn't reach a harness at …", or the fence's own. Null when the body says
 * it all. A harness that answered and wants a session is not "couldn't reach", so its body stands alone.
 */
@Composable
private fun failureTitle(failed: ConnectOutcome.Failed): String? = when (failed.failure) {
    ConnectFailure.INVALID_ADDRESS, ConnectFailure.UNAUTHENTICATED -> null
    ConnectFailure.TRUST_FENCE -> stringResource(Res.string.connect_fail_fence_title)
    else -> stringResource(Res.string.connect_failed, failed.authority)
}

/** What to do about it, in the Android app's own wording (the same strings, from the same keys). */
@Composable
private fun failureBody(failed: ConnectOutcome.Failed): String? = when (failed.failure) {
    ConnectFailure.INVALID_ADDRESS -> stringResource(Res.string.connect_fail_invalid)
    ConnectFailure.TIMEOUT -> stringResource(Res.string.connect_fail_timeout, failed.authority, failed.port)
    ConnectFailure.REFUSED -> stringResource(Res.string.connect_fail_refused, failed.authority)
    ConnectFailure.TRUST_FENCE -> stringResource(Res.string.connect_failed_fence)
    ConnectFailure.UNAUTHENTICATED -> stringResource(Res.string.connect_fail_unauthenticated, failed.authority)
    ConnectFailure.DNS -> stringResource(Res.string.connect_fail_dns, failed.authority.substringBeforeLast(':'))
    ConnectFailure.NOT_A_HARNESS -> stringResource(Res.string.connect_fail_not_harness, failed.authority)
    ConnectFailure.TLS -> stringResource(Res.string.connect_fail_tls, failed.authority)
    // The title already says the harness could not be reached; there is nothing more to add.
    ConnectFailure.UNREACHABLE -> null
    ConnectFailure.OTHER -> stringResource(Res.string.connect_fail_other, failed.authority, failed.detail.orEmpty())
}
