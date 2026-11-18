package com.labteto.dshmobile.ui.screens.harnesssettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.account.AccountController
import com.labteto.dshmobile.core.account.AccountFailure
import com.labteto.dshmobile.core.account.AccountPhase
import com.labteto.dshmobile.core.account.AccountUiState
import com.labteto.dshmobile.core.account.AccountWork
import com.labteto.dshmobile.core.account.Loadable
import com.labteto.dshmobile.core.account.SignInOutcome
import com.labteto.dshmobile.core.account.SignInProblem
import com.labteto.dshmobile.core.account.SignInProgress
import com.labteto.dshmobile.core.account.SignInRoute
import com.labteto.dshmobile.core.account.SignInStep
import com.labteto.dshmobile.core.account.SignOutImpact
import com.labteto.dshmobile.core.account.accountPollDelayMs
import com.labteto.dshmobile.core.account.browserLink
import com.labteto.dshmobile.core.account.browserTarget
import com.labteto.dshmobile.core.account.linkRefused
import com.labteto.dshmobile.core.usage.DEEPSEEK_ACCOUNT
import com.labteto.dshmobile.core.usage.walletRow
import com.labteto.dshmobile.core.usage.walletText
import com.labteto.dshmobile.core.wire.dto.AccountBonus
import com.labteto.dshmobile.core.wire.dto.AccountProfile
import com.labteto.dshmobile.core.wire.dto.AccountWallet
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.screens.main.ConfirmDialog
import com.labteto.dshmobile.ui.screens.main.FeatureAction
import com.labteto.dshmobile.ui.screens.main.FeatureDiagnostics
import com.labteto.dshmobile.ui.screens.main.FeaturePage
import com.labteto.dshmobile.ui.screens.main.quotaLabels
import com.labteto.dshmobile.ui.screens.main.technicalDisplay
import com.labteto.dshmobile.ui.screens.models.Notice
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.isDsDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * DeepSeek sign-in for the connected harness: sign in through the browser, cancel one in progress, see
 * the profile and the balance, sign out.
 *
 * What is true of the harness is read from it (`account/getState`) whenever this page is shown, when the
 * app comes back to the front and while a sign-in runs, so nothing here has to survive the app being left
 * or killed: the harness still reports a sign-in in progress and the page reads it again.
 *
 * Where the sign-in can be finished depends on [route]: the browser is sent back to a loopback address on
 * the harness's own web server, so only a harness reached on this phone's loopback can be signed in from
 * the phone (see [SignInRoute]). For any other harness the page says so and starts nothing.
 */
@Composable
internal fun AccountPageScreen(controller: AccountController, route: SignInRoute, onClose: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val progress = state.snapshot?.progress

    // Back from the browser, or from anywhere: read the harness again at once.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refresh() }
    // While a sign-in runs and the page is in front, keep asking; the harness has no way to call the phone.
    val step = progress?.step
    LaunchedEffect(step) {
        if (step == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(accountPollDelayMs(step))
                controller.refresh()
            }
        }
    }

    FeaturePage(
        title = stringResource(R.string.acct_title),
        subtitle = stringResource(R.string.acct_summary),
        onClose = onClose,
        onRefresh = { controller.refresh() },
    ) {
        when (val phase = state.phase) {
            AccountPhase.Loading -> AccountSkeleton()
            AccountPhase.Unavailable -> Notice(stringResource(R.string.acct_unavailable), warning = false)
            is AccountPhase.Failed -> ErrorCard(phase.failure, onRetry = { controller.load() })
            AccountPhase.Ready -> AccountReady(state, controller, route)
        }
    }
}

@Composable
private fun AccountSkeleton() {
    val colors = DsTheme.colors
    val loading = stringResource(R.string.common_loading)
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = loading; liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        repeat(2) { Box(Modifier.fillMaxWidth().height(96.dp).skeleton(colors.bgLayer2, colors.hover, DsShapes.block)) }
    }
}

@Composable
private fun AccountReady(state: AccountUiState, controller: AccountController, route: SignInRoute) {
    val snapshot = state.snapshot ?: return
    val progress = snapshot.progress

    if (state.refreshFailed) Notice(stringResource(R.string.hs_refresh_failed), warning = true)
    state.actionFailure?.let { failure ->
        ErrorCard(failure, onRetry = retryOf(state.failedWork, controller, route))
    }

    if (progress != null) {
        ProgressCard(progress, state, controller, route)
        BrowserOpener(state, controller, progress)
    }
    if (snapshot.signedIn) {
        SignedInCards(state, controller)
    } else if (progress == null) {
        if (state.sessionEnded) Notice(stringResource(R.string.acct_session_ended), warning = true)
        SignedOutCard(state, controller, route, outcome = snapshot.outcome, retrying = snapshot.outcome != null || state.sessionEnded)
    }
}

/** Trying the action that failed again, or nothing to try. */
private fun retryOf(work: AccountWork?, controller: AccountController, route: SignInRoute): (() -> Unit)? = when (work) {
    AccountWork.Starting -> (route as? SignInRoute.OnThisPhone)?.let { onPhone -> { controller.startSignIn(onPhone.callbackOrigin) } }
    AccountWork.Cancelling -> controller::cancelSignIn
    AccountWork.SigningOut -> controller::signOut
    null -> null
}

// ------------------------------------------------------------------------------------- signed out

@Composable
private fun SignedOutCard(state: AccountUiState, controller: AccountController, route: SignInRoute, outcome: SignInOutcome?, retrying: Boolean) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
        when (route) {
            is SignInRoute.OnThisPhone -> {
                Text(stringResource(R.string.acct_signed_out_title), style = DsType.rowTitle, color = colors.labelPrimary, modifier = Modifier.semantics { heading() })
                outcome?.let { OutcomeLine(it) }
                Text(stringResource(R.string.acct_signed_out_body), style = DsType.small13, color = colors.labelSecondary)
                SettingsButton(
                    text = stringResource(if (retrying) R.string.acct_sign_in_again else R.string.acct_sign_in),
                    onClick = {
                        haptics.tap()
                        controller.startSignIn(route.callbackOrigin)
                    },
                    enabled = state.working == null,
                    variant = DsButtonVariant.Primary,
                )
            }
            SignInRoute.OnHarnessComputer -> {
                // The harness only takes a loopback address to send the browser back to, so from a phone nothing can be started.
                Text(stringResource(R.string.acct_remote_title), style = DsType.rowTitle, color = colors.labelPrimary, modifier = Modifier.semantics { heading() })
                outcome?.let { OutcomeLine(it) }
                Text(stringResource(R.string.acct_remote_body), style = DsType.small13, color = colors.labelSecondary)
                SettingsButton(stringResource(R.string.acct_check_again), onClick = { controller.refresh() })
            }
        }
    }
}

/**
 * How the last sign-in ended, in a plain line under the status. It stays until the next attempt starts
 * (the harness keeps the last attempt, so it is also there when the app was away or came back after the
 * process died): cancelled, timed out and failed are all said, none of them as a passing message.
 */
@Composable
private fun OutcomeLine(outcome: SignInOutcome) {
    val colors = DsTheme.colors
    Text(
        outcomeText(outcome),
        style = DsType.small13,
        color = if (outcome is SignInOutcome.Cancelled) colors.labelSecondary else colors.warnLabel,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun outcomeText(outcome: SignInOutcome): String = when (outcome) {
    SignInOutcome.Cancelled -> stringResource(R.string.acct_outcome_cancelled)
    SignInOutcome.Expired -> stringResource(R.string.acct_outcome_expired)
    is SignInOutcome.Failed -> stringResource(R.string.acct_outcome_failed) + " " + stringResource(
        when (outcome.problem) {
            SignInProblem.PlatformUnreachable -> R.string.acct_reason_platform
            SignInProblem.Storage -> R.string.acct_reason_storage
            SignInProblem.Generic -> R.string.acct_reason_generic
        },
    )
}

// ------------------------------------------------------------------------------------- in progress

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressCard(progress: SignInProgress, state: AccountUiState, controller: AccountController, route: SignInRoute) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val dark = isDsDark(DsTheme.appearance)
    val onPhone = route is SignInRoute.OnThisPhone
    val link = if (onPhone) progress.browserTarget(dark) else null
    // A link this app will not open cannot be finished from this page: offer a fresh attempt, not a Copy button.
    val callbackOrigin = (route as? SignInRoute.OnThisPhone)?.callbackOrigin
    val linkRefused = callbackOrigin != null && progress.linkRefused()
    var copied by remember(progress.attemptId) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    val body = when (progress.step) {
        SignInStep.Starting -> R.string.acct_progress_starting
        SignInStep.WaitingForBrowser -> if (onPhone) R.string.acct_progress_browser else R.string.acct_progress_remote
        SignInStep.Finishing -> R.string.acct_progress_finishing
    }
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(StateDotState.Running, size = 8.dp)
            Spacer(Modifier.width(DsSpacing.compact))
            Text(stringResource(R.string.acct_progress_title), style = DsType.rowTitle, color = colors.labelPrimary, modifier = Modifier.semantics { heading() })
        }
        Text(
            stringResource(body),
            style = DsType.small13,
            color = colors.labelSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (linkRefused) {
            Text(
                stringResource(R.string.acct_link_refused),
                style = DsType.small13,
                color = colors.warnLabel,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else if (state.openFailedFor == progress.attemptId) {
            Text(stringResource(R.string.acct_browser_missing), style = DsType.small13, color = colors.warnLabel)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            if (link != null) {
                SettingsButton(
                    text = stringResource(R.string.acct_open_browser),
                    onClick = {
                        haptics.tap()
                        // The exception names the link, so it is dropped here and never logged.
                        controller.browserOpened(progress.attemptId, opened = runCatching { uriHandler.openUri(link) }.isSuccess)
                    },
                    variant = DsButtonVariant.Primary,
                )
                SettingsButton(
                    text = stringResource(if (copied) R.string.acct_link_copied else R.string.acct_copy_link),
                    onClick = {
                        haptics.confirm()
                        copySensitive(context, context.getString(R.string.acct_title), link)
                        copied = true
                    },
                    variant = DsButtonVariant.Ghost,
                )
            }
            if (linkRefused && callbackOrigin != null) {
                SettingsButton(
                    text = stringResource(R.string.acct_try_again),
                    onClick = {
                        haptics.tap()
                        controller.retrySignIn(callbackOrigin)
                    },
                    enabled = state.working == null,
                    variant = DsButtonVariant.Primary,
                )
            }
            SettingsButton(
                text = stringResource(R.string.acct_cancel_sign_in),
                onClick = {
                    haptics.tap()
                    controller.cancelSignIn()
                },
                enabled = progress.cancellable && state.working == null,
            )
        }
    }
}

/**
 * Sends the link of the attempt this phone started to the browser, once, as soon as the harness has it.
 * It waits for the app to be in front (a link started from the background would be refused by Android),
 * and marks the attempt as done before it opens, so a recomposition or a return from the browser never opens it again.
 */
@Composable
private fun BrowserOpener(state: AccountUiState, controller: AccountController, progress: SignInProgress) {
    val pending = state.openBrowserFor
    val url = progress.authorizeUrl
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uriHandler = LocalUriHandler.current
    val dark = isDsDark(DsTheme.appearance)
    if (pending == null || pending != progress.attemptId || url == null) return
    LaunchedEffect(pending, url) {
        val target = browserLink(url, dark)
        lifecycle.withResumed {
            controller.browserOpened(pending, opened = target != null && runCatching { uriHandler.openUri(target) }.isSuccess)
        }
    }
}

// -------------------------------------------------------------------------------------- signed in

@Composable
private fun SignedInCards(state: AccountUiState, controller: AccountController) {
    val haptics = rememberDsHaptics()
    val scope = rememberCoroutineScope()
    var impact by remember { mutableStateOf<SignOutImpact?>(null) }
    var asking by remember { mutableStateOf(false) }

    Identity(state.profile)
    liveBonuses(state.bonuses, Instant.now()).forEach { bonus -> BonusCard(bonus) { controller.dismissBonus(bonus.orderId) } }
    BalanceCard(state.balance, onRetry = { controller.loadDetails() })
    Box(Modifier.fillMaxWidth()) {
        SettingsButton(
            text = stringResource(R.string.acct_sign_out),
            onClick = {
                haptics.tap()
                scope.launch {
                    asking = true
                    impact = controller.signOutImpact()
                    asking = false
                }
            },
            enabled = state.working == null && !asking,
        )
    }
    impact?.let { shown ->
        ConfirmDialog(
            title = stringResource(R.string.acct_sign_out_title),
            body = stringResource(
                when (shown) {
                    SignOutImpact.Idle -> R.string.acct_sign_out_idle
                    SignOutImpact.TasksRunning -> R.string.acct_sign_out_running
                    SignOutImpact.Unknown -> R.string.acct_sign_out_unknown
                },
            ),
            confirmLabel = stringResource(R.string.acct_sign_out),
            onDismiss = { impact = null },
            onConfirm = {
                impact = null
                haptics.reject()
                controller.signOut()
            },
        )
    }
}

@Composable
private fun Identity(profile: Loadable<AccountProfile>) {
    val colors = DsTheme.colors
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(StateDotState.Done, size = 8.dp)
            Spacer(Modifier.width(DsSpacing.compact))
            Text(stringResource(R.string.acct_signed_in), style = DsType.small13Strong, color = colors.labelSecondary)
        }
        when (profile) {
            Loadable.Loading -> Text(stringResource(R.string.common_loading), style = DsType.std14, color = colors.labelTertiary)
            Loadable.Failed -> Text(stringResource(R.string.acct_profile_unavailable), style = DsType.std14, color = colors.labelTertiary)
            is Loadable.Ready -> {
                Text(
                    profile.value.name ?: stringResource(R.string.acct_name_fallback),
                    style = DsType.rowTitle,
                    color = colors.labelPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                // Masked by DeepSeek already; set left to right so a phone number or an address reads whole in any language.
                profile.value.contact?.let { Text(technicalDisplay(it), style = DsType.small13, color = colors.labelSecondary) }
            }
        }
    }
}

@Composable
private fun BalanceCard(balance: Loadable<AccountWallet>, onRetry: () -> Unit) {
    val colors = DsTheme.colors
    val labels = quotaLabels()
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        Text(stringResource(R.string.quota_balance_title), style = DsType.caption11, color = colors.labelTertiary, modifier = Modifier.semantics { heading() })
        when (balance) {
            Loadable.Loading -> Text(stringResource(R.string.common_loading), style = DsType.std14, color = colors.labelTertiary)
            Loadable.Failed -> {
                Text(stringResource(R.string.quota_balance_failed), style = DsType.std14, color = colors.error)
                FeatureAction(stringResource(R.string.common_retry), onClick = onRetry)
            }
            is Loadable.Ready -> {
                // The same filtering and formatting as the chat usage card, so the two never disagree.
                val row = remember(balance) { walletRow(DEEPSEEK_ACCOUNT, balance.value, System.currentTimeMillis()) }
                if (row.wallets.isEmpty() && row.bonusWallets.isEmpty()) {
                    Text(stringResource(R.string.acct_balance_none), style = DsType.std14, color = colors.labelTertiary)
                }
                if (row.wallets.isNotEmpty()) {
                    Text(row.wallets.joinToString(" · ") { walletText(it, labels) }, style = DsType.rowTitle, color = colors.labelPrimary)
                }
                row.bonusWallets.forEach {
                    Text(stringResource(R.string.quota_balance_bonus, walletText(it, labels)), style = DsType.small13, color = colors.labelSecondary)
                }
            }
        }
    }
}

/** A bonus DeepSeek credited. The words are the server's, in the language the app asked for; closing it tells the harness it was seen. */
@Composable
private fun BonusCard(bonus: AccountBonus, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text(
            bonus.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.acct_bonus_title),
            style = DsType.rowTitle,
            color = colors.labelPrimary,
            modifier = Modifier.semantics { heading() },
        )
        if (bonus.message.isNotBlank()) Text(bonus.message, style = DsType.small13, color = colors.labelSecondary)
        SettingsButton(stringResource(R.string.acct_bonus_dismiss), onClick = onDismiss)
    }
}

// ----------------------------------------------------------------------------------------- errors

/** The app's usual error card: a sentence, Retry when there is something to retry, and the harness's own words folded away. */
@Composable
private fun ErrorCard(failure: AccountFailure, onRetry: (() -> Unit)?) {
    val (text, detail) = failureText(failure)
    DsCard {
        Text(text, color = DsTheme.colors.error, style = DsType.std14, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (onRetry != null) FeatureAction(stringResource(R.string.common_retry), onClick = onRetry)
        FeatureDiagnostics(detail)
    }
}

@Composable
private fun failureText(failure: AccountFailure): Pair<String, String?> = when (failure) {
    AccountFailure.Unavailable -> stringResource(R.string.acct_unavailable) to null
    is AccountFailure.PlatformUnreachable -> stringResource(R.string.acct_reason_platform) to failure.detail
    is AccountFailure.Storage -> stringResource(R.string.acct_reason_storage) to failure.detail
    is AccountFailure.Rejected -> stringResource(R.string.acct_fail_rejected) to failure.detail
    is AccountFailure.Unreachable -> stringResource(R.string.acct_fail_unreachable) to failure.detail
}
