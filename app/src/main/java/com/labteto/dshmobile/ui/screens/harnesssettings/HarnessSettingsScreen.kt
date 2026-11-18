package com.labteto.dshmobile.ui.screens.harnesssettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.account.AccountController
import com.labteto.dshmobile.core.account.AccountPhase
import com.labteto.dshmobile.core.account.SignInRoute
import com.labteto.dshmobile.core.account.signInRoute
import com.labteto.dshmobile.core.settings.DshHarnessSettingsPort
import com.labteto.dshmobile.core.settings.FieldKind
import com.labteto.dshmobile.core.settings.HarnessSettingsController
import com.labteto.dshmobile.core.settings.HarnessSettingsPort
import com.labteto.dshmobile.core.settings.HarnessSettingsState
import com.labteto.dshmobile.core.settings.SettingsNotice
import com.labteto.dshmobile.core.settings.SettingsPage
import com.labteto.dshmobile.core.settings.SettingsPhase
import com.labteto.dshmobile.core.settings.needsRestart
import com.labteto.dshmobile.core.settings.pageNamespaces
import com.labteto.dshmobile.core.settings.settingsFields
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.data.HarnessFeature
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.models.sendsInClear
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.SecureContent
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.screens.main.FeatureAction
import com.labteto.dshmobile.ui.screens.main.FeatureDiagnostics
import com.labteto.dshmobile.ui.screens.main.FeaturePage
import com.labteto.dshmobile.ui.screens.models.Notice
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The calls the pages make, over the connected harness, with `settings/describe` reported to the
 * store's capability record so the Settings entry can hide itself on a harness that lacks it.
 */
private class StoreSettingsPort(
    private val store: SessionStore,
    private val api: DshApiClient,
    private val base: HarnessSettingsPort = DshHarnessSettingsPort(api),
) : HarnessSettingsPort by base {
    override suspend fun describe() = store.featureCall(HarnessFeature.HARNESS_SETTINGS, api) { base.describe() }
}

/** The row in Settings that opens the area: what is in it, and a chevron. */
@Composable
fun HarnessSettingsRow(onOpen: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.row)
            .clickable(role = Role.Button) {
                haptics.tap()
                onOpen()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.hs_entry_summary), Modifier.weight(1f), style = DsType.std14, color = colors.labelSecondary)
        // The arrow mirrors in right-to-left languages.
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.labelTertiary)
    }
}

/**
 * Harness settings: the namespaces the connected harness describes, each as a page, with the five
 * the app knows (web search, shell, subagents, agent loop, session log) first and in the app's own
 * words. One controller serves the list and every page opened from it, so a page opens on what the
 * list already read.
 */
@Composable
fun HarnessSettingsScreen(onClose: () -> Unit) {
    val store = rememberSessionStore()
    val connection by store.connectionState.collectAsStateWithLifecycle()
    val host = store.activeHostKey
    val api = store.apiForHost(host)
    val scope = rememberCoroutineScope()
    val cleartext = connection.host?.sendsInClear() == true
    val hostLabel = connection.host?.displayAddress.orEmpty()
    // Keyed on the client too: a reconnect hands out a new one, and the old one's socket is gone.
    val controller = remember(host, api) {
        api?.let { HarnessSettingsController(StoreSettingsPort(store, it), scope, cleartext) }
    }
    LaunchedEffect(controller) { controller?.load() }

    // DeepSeek sign-in is not a settings namespace: its state is the harness's account calls. One controller
    // per harness, not per connection, so a sign-in on screen survives the socket being replaced while the
    // app is in the browser; its port looks the current client up at each call.
    val accountController = remember(host) {
        host?.let {
            AccountController(
                StoreAccountPort(store, it), scope,
                // The chat usage card shows this account's balance: have it read again now rather than on its own timer.
                onSignInChanged = { scope.launch { store.refreshQuota(recheck = true) } },
            )
        }
    }
    // Where the browser can be sent back to: only a harness on this phone's own loopback can be signed in from the phone.
    val route = remember(connection.host) {
        connection.host?.let { signInRoute(it.host, it.port, it.useTls, it.basePath, it.isRelay) } ?: SignInRoute.OnHarnessComputer
    }
    LaunchedEffect(accountController) { accountController?.load() }
    // A reconnect hands out a new client: read the account again, which is also what finishes a sign-in
    // that was done while the app was away and the socket dropped.
    LaunchedEffect(api) { if (api != null) accountController?.refresh() }
    LaunchedEffect(accountController) {
        if (accountController == null) return@LaunchedEffect
        store.hostEvents.collect { (event, _) ->
            if (event == "deepseek-account/session-expired") accountController.onSessionExpired()
        }
    }
    // The page that is open survives the settings controller being replaced and the activity being
    // recreated (text size, display size, theme or language changed while it is open), as the Plugins
    // page does. Only the page's key is kept: nothing typed is saved, so a key entry comes back closed
    // and empty (see SecretControl). Kept per harness: [openedFor] is the one the area was open for, which
    // stands in while the connection comes back, and a different harness starts afresh on the list.
    val openedFor = rememberSaveable { mutableStateOf(host) }.value
    val stateHost = host ?: openedFor
    // True when this page came back from saved state rather than being opened just now. A page brought
    // back by a process restart finds no harness connected (the app does not reconnect while Settings
    // is in front) and would only say it is offline, so it closes; one opened offline stays as before.
    var composedBefore by rememberSaveable { mutableStateOf(false) }
    val restored = remember { composedBefore }
    SideEffect { composedBefore = true }
    LaunchedEffect(host) {
        if (openedFor != null && host != openedFor && (host != null || restored)) onClose()
    }
    var openKey by rememberSaveable(stateHost) { mutableStateOf<String?>(null) }

    LaunchedEffect(controller) {
        if (controller == null) return@LaunchedEffect
        store.hostEvents.collect { (event, args) ->
            when (event) {
                // A bundle was switched on or off (a module such as web search or the shell sandbox came
                // or went), and the namespaces the harness describes went with it: read the list again.
                "plugin-manager/changed" -> {
                    controller.load()
                    // The account calls come and go with the account plugin: the Account page is offered or dropped from what they answer.
                    accountController?.refresh()
                }
                // Another client saved a namespace: read it again. The harness announces our own writes
                // too, and the controller tells them apart by revision.
                "settings/document-updated" -> {
                    val ns = (args.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@collect
                    val revision = (args.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@collect
                    controller.onDocumentUpdated(ns, revision)
                }
            }
        }
    }

    FeaturePage(
        title = stringResource(R.string.hs_title),
        subtitle = stringResource(R.string.hs_intro),
        onClose = onClose,
        onRefresh = controller?.let {
            {
                it.load()
                accountController?.refresh()
            }
        },
    ) {
        if (controller == null) {
            Notice(stringResource(R.string.common_offline), warning = true)
        } else {
            val state by controller.state.collectAsStateWithLifecycle()
            SettingsAreaBody(state, controller, accountController, route, hostLabel, openKey) { openKey = it }
        }
    }
}

@Composable
private fun SettingsAreaBody(
    state: HarnessSettingsState,
    controller: HarnessSettingsController,
    account: AccountController?,
    route: SignInRoute,
    hostLabel: String,
    openKey: String?,
    onOpen: (String?) -> Unit,
) {
    val pages = remember(state.namespaces, state.writable) { state.pages }
    // The Account page is offered until the harness has said it has no account calls (an older one, or its
    // account plugin is off). Its own state decides, so a harness that cannot describe settings still has it.
    val accountState = account?.state?.collectAsStateWithLifecycle()?.value
    val accountOffered = account != null && accountState?.phase != AccountPhase.Unavailable
    val accountPages = if (accountOffered) listOf(SettingsPage.Account) else emptyList()
    LaunchedEffect(accountOffered) { if (!accountOffered && openKey == SettingsPage.Account.key) onOpen(null) }

    when (val phase = state.phase) {
        SettingsPhase.Loading -> SettingsSkeleton()
        SettingsPhase.Unsupported -> {
            Notice(stringResource(R.string.hs_unsupported), warning = false)
            if (accountPages.isNotEmpty()) PageList(accountPages) { onOpen(it.key) }
        }
        is SettingsPhase.Failed -> {
            // The app's usual load failure: a sentence, Retry, and the harness's own words folded away.
            DsCard {
                Text(stringResource(R.string.ux_a_load_error), color = DsTheme.colors.error, style = DsType.std14)
                FeatureAction(stringResource(R.string.common_retry)) { controller.load() }
                FeatureDiagnostics(failureDetail(phase))
            }
        }
        SettingsPhase.Ready -> {
            if (!state.writable) Notice(stringResource(R.string.hs_read_only), warning = false)
            RefreshNotice(state, controller)
            if (pages.isEmpty() && accountPages.isEmpty()) {
                Text(stringResource(R.string.hs_empty), style = DsType.std14, color = DsTheme.colors.labelTertiary)
            }
            val known = accountPages + pages.filter { it !is SettingsPage.Generic }
            val generic = pages.filterIsInstance<SettingsPage.Generic>()
            if (known.isNotEmpty()) PageList(known) { onOpen(it.key) }
            if (generic.isNotEmpty()) {
                SectionHeader(stringResource(R.string.hs_section_more))
                PageList(generic) { onOpen(it.key) }
            }
        }
    }

    if (account != null && accountOffered && openKey == SettingsPage.Account.key) {
        AccountPageScreen(account, route, onClose = { onOpen(null) })
    }
    pages.firstOrNull { it.key == openKey }?.let { page ->
        HarnessSettingsPageScreen(page, controller, hostLabel, onClose = { onOpen(null) })
    }
}

/** The harness's own words for a failed first read, for the "Technical details" line. */
private fun failureDetail(phase: SettingsPhase.Failed): String? = when (val failure = phase.failure) {
    is com.labteto.dshmobile.core.settings.SettingsFailure.Rejected -> failure.detail
    is com.labteto.dshmobile.core.settings.SettingsFailure.Unreachable -> failure.detail
    else -> null
}

/** Said once when a refresh failed, then forgotten: what is on screen is what was read before. */
@Composable
private fun RefreshNotice(state: HarnessSettingsState, controller: HarnessSettingsController) {
    val notice = state.notice
    if (notice is SettingsNotice.RefreshFailed) {
        LaunchedEffect(notice) {
            delay(6000)
            controller.dismissNotice()
        }
        Notice(stringResource(R.string.hs_refresh_failed), warning = true)
    }
}

@Composable
private fun SettingsSkeleton() {
    val colors = DsTheme.colors
    val loading = stringResource(R.string.common_loading)
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = loading; liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        repeat(3) {
            Box(Modifier.fillMaxWidth().height(72.dp).skeleton(colors.bgLayer2, colors.hover, DsShapes.block))
        }
    }
}

@Composable
private fun PageList(pages: List<SettingsPage>, onOpen: (SettingsPage) -> Unit) {
    DsCard(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        pages.forEachIndexed { index, page ->
            if (index > 0) HorizontalDivider(color = DsTheme.colors.borderL1)
            val title = pageTitleRes(page)?.let { stringResource(it) } ?: (page as? SettingsPage.Generic)?.let(::genericPageTitle).orEmpty()
            val summary = pageSummaryRes(page)?.let { stringResource(it) }
            PageRow(title, summary) { onOpen(page) }
        }
    }
}

@Composable
private fun PageRow(title: String, summary: String?, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(DsShapes.row)
            .clickable(role = Role.Button) {
                haptics.tap()
                onClick()
            }
            .padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
            Text(title, style = DsType.rowTitle, color = colors.labelPrimary)
            summary?.let { Text(it, style = DsType.small13, color = colors.labelTertiary) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.labelTertiary)
    }
}

// ------------------------------------------------------------------------------------- one page

@Composable
private fun HarnessSettingsPageScreen(
    page: SettingsPage,
    controller: HarnessSettingsController,
    hostLabel: String,
    onClose: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val haptics = rememberDsHaptics()
    val title = pageTitleRes(page)?.let { stringResource(it) } ?: (page as? SettingsPage.Generic)?.let(::genericPageTitle).orEmpty()
    val served = state.namespaces
    val hasSecrets = remember(page, served) { pageHasSecrets(page, state) }
    val restart = remember(page, served) { needsRestart(pageNamespaces(page, served.keys).mapNotNull { served[it] }) }

    // Reopened, a page should not scold for a change nobody is making.
    DisposableEffect(controller) { onDispose { controller.clearFailures() } }
    // A key saved or removed is felt once; the status line under the field says it in words.
    LaunchedEffect(state.notice) {
        when (state.notice) {
            SettingsNotice.KeySaved, SettingsNotice.KeyRemoved -> {
                haptics.confirm()
                controller.dismissNotice()
            }
            else -> Unit
        }
    }

    FeaturePage(
        title = title,
        subtitle = pageSummaryRes(page)?.let { stringResource(it) },
        onClose = onClose,
        onRefresh = { controller.load() },
    ) {
        // A key on screen stays out of screenshots, the recents thumbnail and autofill.
        if (hasSecrets) SecureContent()
        if (pageNamespaces(page, served.keys).isEmpty()) {
            Text(stringResource(R.string.hs_empty), style = DsType.std14, color = DsTheme.colors.labelTertiary)
            return@FeaturePage
        }
        if (!state.writable) Notice(stringResource(R.string.hs_read_only), warning = false)
        if (restart) Notice(stringResource(R.string.hs_note_restart), warning = true)
        SettingsPageBody(page, state, controller, hostLabel)
    }
}

private fun pageHasSecrets(page: SettingsPage, state: HarnessSettingsState): Boolean = when (page) {
    SettingsPage.WebSearch -> true
    is SettingsPage.Generic -> state.namespaces[page.ns]?.let { row -> settingsFields(row).any { it.kind == FieldKind.Secret } } == true
    else -> false
}
