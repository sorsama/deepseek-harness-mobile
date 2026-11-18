package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import com.labteto.dshmobile.data.HarnessFeature
import com.labteto.dshmobile.data.PluginOperationState
import com.labteto.dshmobile.ui.components.*
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PluginManagerScreen(onClose: () -> Unit, onDraftOpened: () -> Unit = onClose) {
    val store = rememberSessionStore()
    val available by store.pluginManagementAvailable.collectAsStateWithLifecycle()
    val inventory by store.plugins.collectAsStateWithLifecycle()
    if (available == false) {
        inventory?.let { com.labteto.dshmobile.ui.screens.settings.PluginsSheet(it, onDismiss = onClose) }
        LaunchedEffect(inventory) { if (inventory == null) onClose() }
        return
    }
    val connection by store.connectionState.collectAsStateWithLifecycle()
    val host = store.activeHostKey
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    val operation by store.pluginOperations.state.collectAsStateWithLifecycle()
    val pending by store.pluginOperations.pending.collectAsStateWithLifecycle()
    val presets by store.agentPresets.collectAsStateWithLifecycle()
    var inventoryOpen by remember { mutableStateOf(false) }
    var bundles by remember(host) { mutableStateOf<List<PluginBundle>>(emptyList()) }
    var plugins by remember(host) { mutableStateOf<List<ManagedPlugin>>(emptyList()) }
    var registries by remember(host) { mutableStateOf<PluginRegistries?>(null) }
    var spec by remember(host) { mutableStateOf("") }
    var registry by remember(host) { mutableStateOf("") }
    // What the person was looking at survives the activity being recreated (text size, display size,
    // theme or language changed with the page open) and a process restart: the search, the chip and
    // the open bundle. Kept per harness: [openedFor] is the one the page was open for, which stands in
    // while the connection comes back after a restart, and a different harness starts afresh. Nothing
    // here is a secret, and the install form, the confirmations and the removal stay unsaved.
    val openedFor = rememberSaveable { mutableStateOf(host) }.value
    val stateHost = host ?: openedFor
    // True when this page came back from saved state rather than being opened just now. A page brought
    // back by a process restart finds no harness connected (the app does not reconnect while Settings
    // is in front) and would only show an error, so it closes; a page opened offline stays as before.
    var composedBefore by rememberSaveable { mutableStateOf(false) }
    val restored = remember { composedBefore }
    SideEffect { composedBefore = true }
    LaunchedEffect(host) {
        if (openedFor != null && host != openedFor && (host != null || restored)) onClose()
    }
    var search by rememberSaveable(stateHost) { mutableStateOf("") }
    var filter by rememberSaveable(stateHost) { mutableStateOf(PluginFilter.ALL) }
    // The open bundle is held by name and looked up from `bundles` below, so a refresh or a toggle
    // updates its page and a removed bundle closes it. Its search and chip live here, not inside the
    // page, so that the list's own search, chip and scroll position are never touched by it.
    var detailName by rememberSaveable(stateHost) { mutableStateOf<String?>(null) }
    var detailSearch by rememberSaveable(stateHost) { mutableStateOf("") }
    var detailFilter by rememberSaveable(stateHost) { mutableStateOf(PluginFilter.ALL) }
    val listState = rememberLazyListState()
    // The Discover view (the optional plugin market). It exists only while the harness mounts the market;
    // without it this page is the Installed list alone. Each view keeps its own search text and scroll position.
    val marketAvailable by store.pluginMarketAvailable.collectAsStateWithLifecycle()
    val onBuiltIn by androidx.hilt.navigation.compose.hiltViewModel<com.labteto.dshmobile.ui.screens.runtime.ProjectsViewModel>()
        .operations.onBuiltInState.collectAsStateWithLifecycle()
    var viewChoice by remember(host) { mutableStateOf(PluginView.INSTALLED) }
    val view = if (marketAvailable == true) viewChoice else PluginView.INSTALLED
    var marketSearch by remember(host) { mutableStateOf("") }
    val marketListState = rememberLazyListState()
    val browser = remember(host) { PluginMarketBrowser(scope, store, host) }
    var marketEntryName by remember(host) { mutableStateOf<String?>(null) }
    var sourcesOpen by remember(host) { mutableStateOf(false) }
    val languages = rememberMarketLanguages()
    var installOpen by remember(host) { mutableStateOf(false) }
    var operationOpen by remember(host) { mutableStateOf(false) }
    var installedVersion by remember(host) { mutableStateOf<String?>(null) }
    var replacing by remember(host) { mutableStateOf<String?>(null) }
    var inspection by remember(host) { mutableStateOf<PluginInspection?>(null) }
    var confirm by remember(host) { mutableStateOf<PluginInstallSubject?>(null) }
    var remove by remember(host) { mutableStateOf<PluginBundle?>(null) }
    var loading by remember(host) { mutableStateOf(false) }
    var loaded by remember(host) { mutableStateOf(false) }
    var error by remember(host) { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val busy = loading || pending.any { it.host == host && it.busy }
    val offline = stringResource(R.string.common_offline)
    val creator = stringResource(R.string.harness_creator_draft)
    val current = operation.takeIf { it.host == host }
    fun run(block: suspend (DshApiClient) -> Unit) { scope.launch {
        loading = true; error = null
        try { block(store.apiForHost(host) ?: error(offline)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: offline }
        finally { loading = false }
    } }
    // Whether the harness has the plugin market is cached per connection, and the market is a bundle that is turned on and
    // off while connected. So it is asked again whenever plugin state may have changed: when this page opens, after a
    // bundle or plugin is switched, installed or removed (each of those bumps `revision` or the operation result, which
    // run the load below), on the host's own "plugins changed" event (it bumps `revision`), and on Refresh.
    fun recheckMarket() { scope.launch { store.apiForHost(host)?.let { store.recheckPluginMarket(it) } } }
    LaunchedEffect(host, connection.phase, revision, current?.result, current?.unknown) { run { api ->
        store.recheckPluginMarket(api)
        val next = store.featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.pluginBundles() }.featureValue()
        val rows = store.featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.managedPlugins() }.featureValue()
        val sources = store.featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.pluginRegistries() }.featureValue()
        if (store.activeHostKey == host) { bundles = next; plugins = rows; registries = sources; loaded = true }
    } }
    LaunchedEffect(host) { store.hostEvents.collect { if (it.first == "plugin-manager/changed") revision++ } }
    // The market is read when Discover is first shown, and again (from the host's cached copy) when what is
    // installed or what the host lists as sources changes, so an installed entry reads Installed at once.
    LaunchedEffect(host, view) { if (view == PluginView.DISCOVER) browser.ensureLoaded() }
    LaunchedEffect(host, revision, current?.result, current?.unknown) { if (marketAvailable != false) browser.invalidate() }
    LaunchedEffect(host) { store.hostEvents.collect { if (it.first == "plugin-market/changed") browser.invalidate() } }
    // The market bundle was turned off: back to Installed with nothing of Discover left open. Turned on again: read it fresh.
    LaunchedEffect(host, marketAvailable) {
        if (marketAvailable == false) { viewChoice = PluginView.INSTALLED; marketEntryName = null; sourcesOpen = false }
        else if (marketAvailable == true) browser.invalidate()
    }
    // Typing waits for a pause before the harness is asked.
    LaunchedEffect(marketSearch, view) {
        if (view == PluginView.DISCOVER) { if (marketSearch != browser.query.text) delay(250); browser.setText(marketSearch) }
    }

    val pluginsById = remember(plugins) { plugins.associateBy { it.entryId } }
    val model = remember(bundles, plugins, search, filter) { pluginListModel(bundles, plugins, search, filter) }
    val standalone = remember(model.standalone) { pluginStandaloneModel(model.standalone) }
    // The names the list and the bundle pages draw, so an operation card calls its subject the same thing.
    val subjectNames = remember(bundles, plugins, model.names) { pluginSubjectNames(bundles, plugins, model.names) }
    fun subjectText(state: PluginOperationState): String? =
        state.displaySubject()?.let { pluginSubjectText(it, subjectNames) }?.let(::technicalDisplay)
    val enabledCounts = remember(bundles, pluginsById) {
        bundles.distinctBy { it.name }.associate { it.name to pluginEnabledCount(pluginModuleRows(it, pluginsById)) }
    }
    val detail = detailName?.let { name -> bundles.firstOrNull { it.name == name } }
    // A bundle that is no longer in the list (removed, or gone after a refresh) has no page to show.
    LaunchedEffect(detail == null, loaded) { if (loaded && detail == null && detailName != null) detailName = null }

    fun togglePlugin(plugin: ManagedPlugin) = run { api ->
        val result = store.featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.setPluginEnabled(plugin.entryId, !plugin.enabled) }.featureValue()
        if (host != null) store.pluginOperations.changed(host, result); revision++
    }

    fun toggleBundle(bundle: PluginBundle) {
        if (!bundle.enabled && bundle.availability == "missing" && bundle.installTarget != null) {
            confirm = bundle.catalogInstall(enabled = true)
        } else run { api ->
            val result = store.featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.setBundleEnabled(bundle.name, !bundle.enabled) }.featureValue()
            if (host != null) store.pluginOperations.changed(host, result); revision++
        }
    }

    fun updateBundle(bundle: PluginBundle) {
        val catalog = bundle.catalogInstall(enabled = bundle.enabled)
        if (catalog != null) confirm = catalog
        else { spec = bundle.source ?: bundle.name; registry = ""; inspection = null; installedVersion = bundle.version; replacing = bundle.name.takeIf { bundle.installed }; installOpen = true }
    }

    fun openDetail(bundle: PluginBundle) {
        detailSearch = bundle.searchCarriedToDetail(search)
        detailFilter = PluginFilter.ALL
        detailName = bundle.name
    }

    // The open market entry is held by name and looked up from what was read, as the open bundle is.
    val marketEntry = marketEntryName?.let { name -> browser.page?.entries?.firstOrNull { it.name == name } }
    LaunchedEffect(marketEntry == null, browser.status) {
        if (browser.status == MarketStatus.READY && marketEntry == null && marketEntryName != null) marketEntryName = null
    }

    /** Install or update a market entry: the harness checks the package, then the existing confirmation opens. */
    fun beginMarketInstall(entry: com.labteto.dshmobile.core.market.MarketEntry, action: MarketAction) {
        val (version, update) = when (action) {
            is MarketAction.Install -> action.version to false
            is MarketAction.Update -> action.version to true
            else -> return
        }
        val installation = com.labteto.dshmobile.core.market.marketInstallationFor(entry, bundles)
        val note = marketInstallNote(entry, version, browser.page?.header, installation, languages)
        val replaces = if (update) com.labteto.dshmobile.core.market.marketInstalledBundle(entry.name, bundles)?.takeIf { it.installed }?.name else null
        browser.beginInstall(entry, version, marketInstallSubject(version, note, replaces), update) { confirm = it }
    }

    @Composable
    fun Failure() {
        error?.let { message ->
            DsCard {
                Text(stringResource(R.string.ux_a_action_error), color = colors.error, style = DsType.std14)
                FeatureAction(stringResource(R.string.common_retry), !busy) { revision++ }
                if (inventory != null) FeatureAction(stringResource(R.string.settings_plugins_inventory)) { inventoryOpen = true }
                FeatureDiagnostics(message)
            }
        }
    }

    @Composable
    fun Operations() {
        // Ongoing operations are compact subject cards; terminal output stays behind disclosure.
        pending.filter { it.host == host }.forEach { state -> DsCard {
            row@ Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    subjectText(state) ?: stringResource(R.string.ux_a_previous_install),
                    style = DsType.std14Strong,
                    color = colors.labelPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PluginPhaseTag(
                    state.result?.application,
                    busy = state.busy,
                    unknown = state.unknown,
                    failed = state.result?.application == "failed",
                )
            }
            if (state.busy) {
                FeatureProgress()
                Text(stringResource(pluginPhase(state.phase)), style = DsType.small13, color = colors.labelSecondary)
                FeatureAction(stringResource(R.string.common_cancel)) { if (host != null && state.requestId != null) store.pluginOperations.cancel(host, state.requestId) }
            }
            if (state.unknown) {
                Text(stringResource(R.string.ux_a_unknown), style = DsType.small13, color = colors.warnText)
                FeatureAction(stringResource(R.string.ux_a_check_status)) { if (host != null) store.pluginOperations.resume(host, state.requestId) }
                FeatureAction(stringResource(R.string.harness_dismiss_unknown)) { if (host != null && state.requestId != null) store.pluginOperations.dismissUnknown(host, state.requestId) }
            }
            FeatureDiagnostics(listOfNotNull(state.requestId, state.phase, state.error, state.log.takeIf { it.isNotBlank() }).joinToString("\n"))
        } }
        current?.let { state -> state.result?.let { result -> DsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    subjectText(state) ?: stringResource(R.string.ux_a_previous_install),
                    style = DsType.std14Strong,
                    color = colors.labelPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PluginPhaseTag(result.application, busy = false, unknown = false, failed = result.application == "failed")
            }
            result.version?.let { Text(technicalDisplay(it), style = DsType.mono12, color = colors.labelSecondary) }
            // The tag already says "Applied" or "Saved. Restart..."; the line only adds what it does not.
            pluginStatusLine(result.application, busy = false, unknown = false, phase = null)?.let { line ->
                Text(
                    stringResource(line),
                    style = DsType.std14,
                    color = if (result.application == "restart-required") colors.warnText else colors.labelSecondary,
                )
            }
            result.error?.let { Text(stringResource(pluginReason(it.code)), color = colors.error, style = DsType.small13) }
            state.approvalSubject()?.let { approval ->
                FeatureAction(stringResource(R.string.ux_a_approve_retry), !busy, DsButtonVariant.Primary) { confirm = approval }
            }
            FeatureDiagnostics(listOfNotNull(state.requestId, result.error?.code, result.error?.diagnostic,
                result.warnings.joinToString("\n").ifBlank { null }, result.packageResult?.output?.takeLast(8192),
                result.packageResult?.takeIf { it.truncated }?.logPath, state.log.ifBlank { null }).joinToString("\n"))
        } } }
    }

    val operationCards = pending.filter { it.host == host } + listOfNotNull(current?.takeIf { it.result != null })
    val showCreate = presets?.presets?.any { it.id == "cordis" && it.broken == null } == true
    // With nothing to match, the mascot page says there are no plugins; a search or a chip that found
    // nothing gets one quiet line, short enough to stay visible above the keyboard.
    val nothingMatched = !(search.isBlank() && filter == PluginFilter.ALL)
    val emptyText = stringResource(if (nothingMatched) R.string.plugins_no_matches else R.string.plugins_empty)
    val installedHeader = stringResource(R.string.ux_a_installed)
    val availableHeader = stringResource(R.string.ux_a_available)

    val marketAll = stringResource(R.string.market_filter_all)
    LazyFeaturePage(
        title = stringResource(R.string.settings_plugins),
        onClose = onClose,
        onRefresh = { if (!busy) { if (view == PluginView.DISCOVER) { recheckMarket(); browser.refresh() } else revision++ } },
        footer = {
            Text(
                stringResource(R.string.ink_files_plugins_intro),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        },
        pinned = {
            if (marketAvailable == true) DsSegmented(
                listOf(
                    DsSegment(PluginView.INSTALLED.name, stringResource(R.string.ux_a_installed)),
                    DsSegment(PluginView.DISCOVER.name, stringResource(R.string.market_view_discover)),
                ),
                view.name,
                { viewChoice = PluginView.valueOf(it) },
                role = Role.Tab,
                stretch = true,
                minSegmentHeight = DsSpacing.touchTarget,
                maxLabelLines = Int.MAX_VALUE,
            )
            if (view == PluginView.DISCOVER) {
                DsSearchField(marketSearch, { marketSearch = it }, stringResource(R.string.market_search_hint))
                MarketFilterChips(browser.page?.header?.let { marketChips(it, browser.query, languages, marketAll) }.orEmpty()) { browser.applyQuery(browser.query.tap(it)) }
            } else {
                DsSearchField(search, { search = it }, stringResource(R.string.plugins_search_hint))
                PluginFilterChips(model.counts, filter) { filter = it }
            }
        },
        progress = busy || (view == PluginView.DISCOVER && (browser.status == MarketStatus.LOADING || browser.status == MarketStatus.MORE)),
        listState = if (view == PluginView.DISCOVER) marketListState else listState,
    ) {
        if (view == PluginView.INSTALLED) item(key = "actions") {
            PluginButtonPair {
                DsButton(
                    text = stringResource(R.string.harness_install_update),
                    onClick = {
                        spec = ""; registry = ""; inspection = null; installedVersion = null; replacing = null; installOpen = true
                    },
                    enabled = !busy,
                    variant = DsButtonVariant.Primary,
                    allowWrap = true,
                )
                if (showCreate) DsButton(
                    text = stringResource(if (loading) R.string.common_loading else R.string.harness_create_plugin),
                    onClick = {
                        run {
                            if (host != null && store.stageFeatureDraft(host, creator, "cordis")) onDraftOpened() else error(offline)
                        }
                    },
                    enabled = !busy,
                    variant = DsButtonVariant.Outline,
                    allowWrap = true,
                )
            }
        }
        itemsIndexed(operationCards, key = { index, _ -> "operation:$index" }) { _, state ->
            DsCard(onClick = { operationOpen = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        subjectText(state) ?: stringResource(R.string.ux_a_previous_install),
                        style = DsType.std14Strong,
                        color = colors.labelPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    PluginPhaseTag(
                        state.result?.application,
                        busy = state.busy,
                        unknown = state.unknown,
                        failed = state.result?.application == "failed" || state.unknown,
                    )
                }
                // The tag says "Applied"; a second line saying it again is dropped.
                pluginStatusLine(state.result?.application, state.busy, state.unknown, state.phase)?.let { line ->
                    Text(
                        stringResource(line),
                        style = DsType.small13,
                        color = if (state.unknown || state.result?.application == "restart-required") colors.warnText else colors.labelSecondary,
                    )
                }
                if (state.busy) FeatureProgress()
            }
        }
        if (view == PluginView.DISCOVER) {
            marketListItems(browser, bundles, languages, onOpenEntry = { marketEntryName = it }, onOpenSources = { sourcesOpen = true })
            return@LazyFeaturePage
        }
        if (busy && !loaded) item(key = "skeleton") { SkeletonPluginCards() }
        // An item with nothing in it still takes a gap in a lazy list, so the failure card only exists while there is one.
        if (error != null) item(key = "failure") { Failure() }
        if (loaded && model.bundles.isEmpty() && model.standalone.isEmpty()) item(key = "empty") {
            if (nothingMatched) {
                Text(
                    emptyText,
                    style = DsType.std14,
                    color = colors.labelSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.large),
                )
            } else {
                EmptyHero(emptyText, null, showPreview = false)
            }
        }
        listOf(true, false).forEach { installed ->
            val group = model.bundles.filter { it.listedAsInstalled() == installed }
            val unbundled = if (installed) standalone else PluginStandaloneModel(emptyList(), emptyList())
            if (group.isNotEmpty() || unbundled.open.isNotEmpty() || unbundled.locked.isNotEmpty()) item(key = "section:$installed") {
                SectionHeader(if (installed) installedHeader else availableHeader)
            }
            items(unbundled.open, key = { "plugin:${it.entryId}" }) { plugin ->
                // The whole card is the target: the row inside shares its press with the card, which draws it.
                val pressSource = remember { MutableInteractionSource() }
                DsCard(pressSource = pressSource) {
                    PluginToggleRow(
                        label = technicalDisplay(model.names[plugin.moduleName] ?: plugin.moduleName),
                        checked = plugin.enabled,
                        enabled = !busy,
                        onToggle = { togglePlugin(plugin) },
                        leading = { PluginGlyph(size = 40.dp) },
                        interactionSource = pressSource,
                        drawsPress = false,
                    )
                }
            }
            // The plugins the harness locks share one reason; it is said once, over compact rows, as on a bundle's page.
            items(unbundled.locked, key = { "locked:${it.reason}" }) { locked ->
                DsCard(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    PluginLockedHeader(stringResource(pluginReason(locked.reason)), Modifier.padding(bottom = DsSpacing.xsmall))
                    locked.plugins.forEachIndexed { index, plugin ->
                        if (index > 0) PluginRowDivider()
                        PluginLockedRow(
                            label = technicalDisplay(model.names[plugin.moduleName] ?: plugin.moduleName),
                            enabled = plugin.enabled,
                        )
                    }
                }
            }
            items(group, key = { "bundle:${it.name}" }) { bundle ->
                PluginBundleCard(
                    bundle = bundle,
                    name = model.names[bundle.name] ?: bundle.name,
                    count = enabledCounts[bundle.name],
                    search = search,
                    switchEnabled = !busy,
                    onOpen = { openDetail(bundle) },
                    onToggle = { toggleBundle(bundle) },
                )
            }
        }
    }
    detail?.let { bundle ->
        PluginBundleDetailPage(
            bundle = bundle,
            title = model.names[bundle.name] ?: bundle.name,
            rows = pluginModuleRows(bundle, pluginsById),
            search = detailSearch,
            onSearch = { detailSearch = it },
            filter = detailFilter,
            onFilter = { detailFilter = it },
            busy = busy,
            onClose = { detailName = null },
            onRefresh = { if (!busy) { revision++ } },
            onToggleBundle = { toggleBundle(bundle) },
            onTogglePlugin = ::togglePlugin,
            onUpdate = { updateBundle(bundle) },
            onRemove = { remove = bundle },
        )
    }
    marketEntry?.let { entry ->
        PluginMarketEntryPage(
            entry = entry,
            header = browser.page?.header,
            installation = com.labteto.dshmobile.core.market.marketInstallationFor(entry, bundles),
            installedBundle = com.labteto.dshmobile.core.market.marketInstalledBundle(entry.name, bundles),
            languages = languages,
            onBuiltIn = onBuiltIn,
            busy = busy,
            check = browser.check,
            onInstall = { beginMarketInstall(entry, it) },
            onOpenInstalled = { name ->
                // As the web client does: to the Installed view, on that bundle's own page.
                browser.dismissCheck(); marketEntryName = null; viewChoice = PluginView.INSTALLED
                bundles.firstOrNull { it.name == name }?.let(::openDetail)
            },
            onDismissCheck = browser::dismissCheck,
            onClose = { browser.dismissCheck(); marketEntryName = null },
            onRefresh = { if (!busy) { recheckMarket(); browser.refresh() } },
        )
    }
    if (sourcesOpen) PluginMarketSourcesPage(browser, languages) { sourcesOpen = false }
    if (operationOpen) FeaturePage(stringResource(R.string.harness_install_update), { operationOpen = false }) { Operations() }
    if (installOpen) FeaturePage(stringResource(R.string.harness_install_update), { installOpen = false }) {
        FeatureField(spec, { spec = it; inspection = null }, R.string.harness_package, !busy, singleLine = true, technical = true)
        FeatureField(registry, { registry = it; inspection = null }, R.string.harness_registry, !busy, singleLine = true, technical = true)
        installedVersion?.let { Text(stringResource(R.string.ux_a_installed_version, technicalDisplay(it)), style = DsType.small13, color = colors.labelSecondary) }
        FeatureDiagnostics(registries?.let { (listOfNotNull(it.registry ?: it.resolved) + it.fallbackRegistries).distinct().joinToString("\n", transform = ::technicalDisplay) })
        FeatureAction(stringResource(R.string.harness_inspect), !busy && spec.isNotBlank()) { run { api ->
            inspection = store.featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.inspectPlugin(spec.trim(), registry.trim().ifBlank { null }) }.featureValue()
        } }
        inspection?.let { inspected -> DsCard {
            Text(listOfNotNull(inspected.name, inspected.version).joinToString(" · ", transform = ::technicalDisplay), style = DsType.std14Strong, color = colors.labelPrimary)
            inspected.description?.let { Text(it, style = DsType.std14, color = colors.labelSecondary) }
            Text(stringResource(if (inspected.status == "accepted") R.string.ux_a_inspected else R.string.ux_a_inspect_notice), style = DsType.small13, color = colors.labelSecondary)
            if (inspected.problem != null || inspected.reason != null) Text(stringResource(pluginReason(inspected.problem)), style = DsType.small13, color = colors.error)
            FeatureDiagnostics(listOfNotNull(inspected.status, inspected.problem, inspected.reason).joinToString("\n"))
        } }
        DsButton(
            text = stringResource(R.string.harness_install_update),
            onClick = {
                confirm = PluginInstallSubject(spec.trim(), registry.trim().ifBlank { null }, replaces = replacing)
            },
            enabled = !busy && spec.isNotBlank(),
            variant = DsButtonVariant.Primary,
            allowWrap = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (loading) FeatureProgress()
        Failure()
    }
    if (inventoryOpen) inventory?.let { com.labteto.dshmobile.ui.screens.settings.PluginsSheet(it, onDismiss = { inventoryOpen = false }) }
    confirm?.let { subject -> DsDialog(stringResource(if (subject.approvedBuilds == null) R.string.harness_install_update else R.string.ux_a_approve_retry), { confirm = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Text(technicalDisplay(subject.target), style = DsType.std14Strong, color = colors.labelPrimary)
            subject.registry?.let { Text(technicalDisplay(it), style = DsType.mono12, color = colors.labelSecondary) }
            subject.approvedBuilds?.forEach { Text(technicalDisplay(it), style = DsType.small13, color = colors.labelPrimary) }
            // From a market: what it is called, who publishes it, the registry that will answer, and the warning.
            subject.market?.let { MarketConfirmNotes(it, registryNamed = subject.registry != null, defaultRegistry = registries?.let { r -> r.resolved ?: r.registry }) }
        }
        FeatureAction(stringResource(if (subject.approvedBuilds == null) R.string.harness_install_update else R.string.ux_a_approve_retry), variant = DsButtonVariant.Primary) {
            confirm = null; installOpen = false; operationOpen = true
            if (host != null) store.pluginOperations.install(host, subject.target, subject.registry, subject.approvedBuilds, subject.enabled, subject.saveExact, subject.replaces)
        }
        FeatureAction(stringResource(R.string.common_cancel)) { confirm = null }
    } }
    remove?.let { bundle -> DsDialog(stringResource(R.string.common_remove), { remove = null }) {
        Text(technicalDisplay(bundle.name), style = DsType.std14, color = colors.labelPrimary)
        FeatureAction(stringResource(R.string.common_remove), variant = DsButtonVariant.Danger) {
            remove = null
            run { api ->
                val result = store.featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.removePluginBundle(bundle.name) }.featureValue()
                if (host != null) store.pluginOperations.changed(host, result); revision++
            }
        }
        FeatureAction(stringResource(R.string.common_cancel)) { remove = null }
    } }
}

/**
 * One or two buttons as a row: side by side at equal width when the longer label fits on one line in
 * half the row, otherwise stacked, each at the full width, so a large font or a long language wraps
 * onto a second row of buttons instead of clipping a label. A single button takes the full width.
 * The buttons are passed without a width of their own; this sets it.
 */
@Composable
private fun PluginButtonPair(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val gap = DsSpacing.small
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val width = constraints.maxWidth
        val half = (width - gapPx) / 2
        // Natural width: the whole label on one line, with the button's own padding and icon.
        val sideBySide = measurables.size == 2 && measurables.all { it.maxIntrinsicWidth(0) <= half }
        if (sideBySide) {
            val first = measurables.map { it.measure(Constraints(minWidth = half, maxWidth = half, maxHeight = constraints.maxHeight)) }
            val height = first.maxOf { it.height }
            val placeables = if (first.all { it.height == height }) first else measurables.map {
                it.measure(Constraints.fixed(half, height))
            }
            layout(width, height) {
                placeables[0].placeRelative(0, 0)
                placeables[1].placeRelative(half + gapPx, 0)
            }
        } else {
            val placeables = measurables.map {
                it.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight))
            }
            val height = placeables.sumOf { it.height } + gapPx * (placeables.size - 1).coerceAtLeast(0)
            layout(width, height) {
                var y = 0
                placeables.forEach {
                    it.placeRelative(0, y)
                    y += it.height + gapPx
                }
            }
        }
    }
}

/** The All / On / Off chips with their counts. Scrolls sideways, so a large font or a long word never clips a chip. */
@Composable
private fun PluginFilterChips(counts: PluginFilterCounts, selected: PluginFilter, onSelect: (PluginFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        PluginFilter.entries.forEach { filter ->
            DsFilterChip(
                label = stringResource(
                    when (filter) {
                        PluginFilter.ALL -> R.string.ink_chat_filter_all
                        PluginFilter.ON -> R.string.plugins_filter_on
                        PluginFilter.OFF -> R.string.plugins_filter_off
                    },
                    counts[filter],
                ),
                selected = selected == filter,
                onClick = { onSelect(filter) },
            )
        }
    }
}

/**
 * One bundle on the list: what it is and whether it is on, and nothing to scroll through.
 *
 * The card is three side-by-side targets that never overlap: the text opens the bundle, the switch
 * flips it, and the chevron opens it too. Each is at least 48dp tall (the row takes the height of
 * the text). The chevron is for sight and touch only; it stays out of the accessibility tree, where
 * the text already says "open details". A bundle the harness locks has no switch to draw: its state
 * is a word beside a lock, as on a locked row of the bundle's page.
 *
 * The text and the chevron share one interaction source, and the card ([DsCard]) fills itself with
 * the pressed tone while that source is pressed, so pressing either one lights the whole card and it
 * reads as one thing being pressed. Both clickables pass `indication = null`: the app's
 * `LocalIndication` (see `FocusRing.kt`) fills whatever a clickable covers with the pressed tone, which
 * here would light only the text or only the chevron and tint that half a second time over the card's
 * own fill. So the card does the drawing.
 */
@Composable
private fun PluginBundleCard(
    bundle: PluginBundle,
    name: String,
    count: PluginEnabledCount?,
    search: String,
    switchEnabled: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val matches = if (search.isEmpty()) null else remember(bundle, search) {
        val names = pluginShortNames(bundle.rows.map { it.moduleName })
        bundle.rowMatches(search) { names[it] ?: it }
    }
    val countText = count?.let { stringResource(R.string.plugins_count_on, it.on, it.total) }
    val meta = pluginMetaLine(bundle.version?.let(::technicalDisplay), countText)
    val open = { haptics.tap(); onOpen() }
    val openLabel = stringResource(R.string.plugins_open_details)
    val pressSource = remember { MutableInteractionSource() }
    DsCard(contentPadding = PaddingValues(0.dp), pressSource = pressSource) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(interactionSource = pressSource, indication = null, onClickLabel = openLabel, role = Role.Button, onClick = open)
                    .padding(
                        start = DsSpacing.comfortable,
                        top = DsSpacing.medium,
                        bottom = DsSpacing.medium,
                        end = DsSpacing.small,
                    ),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                PluginGlyph(size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Two lines before an ellipsis: at a large font two long names no longer read the same.
                    Text(
                        technicalDisplay(name),
                        style = DsType.mono13,
                        color = colors.labelPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // No line limit: at a large font or in a long language the count wraps instead of being cut off.
                    if (meta.isNotEmpty()) Text(meta, style = DsType.small13, color = colors.labelTertiary)
                    if (matches != null) {
                        // A search that matched module names says which; the description would only add height.
                        matches.names.forEach {
                            Text(technicalDisplay(it), style = DsType.mono12, color = colors.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (matches.more > 0) {
                            Text(stringResource(R.string.plugins_more_matches, matches.more), style = DsType.small13, color = colors.labelTertiary, maxLines = 1)
                        }
                    } else {
                        bundle.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = DsType.small13, color = colors.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    bundle.error?.let {
                        Text(stringResource(pluginReason(it.code)), color = colors.error, style = DsType.small13, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (bundle.readOnlyReason != null) {
                    PluginLockedState(enabled = bundle.enabled)
                } else {
                    PluginSwitch(name = name, checked = bundle.enabled, enabled = switchEnabled, onToggle = onToggle)
                }
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(36.dp)
                    .clickable(interactionSource = pressSource, indication = null, onClick = open)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                // AutoMirrored: the arrow points to the start in right-to-left locales.
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = colors.labelTertiary)
            }
        }
    }
}

/** A bundle's own page: its rows, split into the ones you can flip and the ones the harness locks. */
@Composable
private fun PluginBundleDetailPage(
    bundle: PluginBundle,
    title: String,
    rows: List<PluginModuleRow>,
    search: String,
    onSearch: (String) -> Unit,
    filter: PluginFilter,
    onFilter: (PluginFilter) -> Unit,
    busy: Boolean,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onToggleBundle: () -> Unit,
    onTogglePlugin: (ManagedPlugin) -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = DsTheme.colors
    // A small bundle has no search or chips, so a search text carried in from the list must not
    // quietly hide rows behind a field nobody can see.
    val controls = pluginDetailShowsControls(rows.size)
    val names = remember(rows) { pluginShortNames(rows.map { it.row.moduleName }) }
    // Two rows can load one module; the row id is what tells them apart (see pluginRowQualifiers).
    val qualifiers = remember(rows, names) { pluginRowQualifiers(rows, names) }
    val shown = remember(rows, search, filter, controls) {
        pluginDetailModel(rows, if (controls) search else "", if (controls) filter else PluginFilter.ALL)
    }
    val count = remember(rows) { pluginEnabledCount(rows) }
    val canInstall = bundle.availability != "installation" && (bundle.installed || bundle.installTarget != null)
    val canRemove = bundle.removable && bundle.readOnlyReason == null
    val lockReason = bundle.readOnlyReason
    val plainNote = if (shown.plain.isEmpty()) null else bundle.plainRowsNote()
    // The scroll position belongs to this bundle's page and is not saved: a list state remembered the
    // usual way is restored by position after the activity is recreated, and the next bundle opened
    // (or the same one) came up scrolled to wherever a page had been left.
    val listState = remember(bundle.name) { LazyListState() }
    val controlsSlot: @Composable ColumnScope.() -> Unit = {
        DsSearchField(search, onSearch, stringResource(R.string.plugins_search_hint))
        PluginFilterChips(shown.counts, filter, onFilter)
    }
    LazyFeaturePage(
        title = technicalDisplay(title),
        subtitle = listOfNotNull(technicalDisplay(bundle.name), bundle.version?.let(::technicalDisplay)).joinToString(" · "),
        onClose = onClose,
        onRefresh = onRefresh,
        pinned = if (controls) controlsSlot else null,
        progress = busy,
        listState = listState,
        // The module rows are flat and draw their own dividers, so the page gives no gap between items
        // and the few blocks that need one (summary, count, group header, buttons) pad themselves.
        verticalArrangement = Arrangement.Top,
    ) {
        item(key = "summary") {
            DsCard(Modifier.padding(bottom = DsSpacing.medium)) {
                bundle.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = DsType.std14, color = colors.labelSecondary)
                }
                bundle.error?.let {
                    Text(stringResource(pluginReason(it.code)), color = colors.error, style = DsType.small13)
                    FeatureDiagnostics(it.diagnostic)
                }
                val state = stringResource(if (bundle.enabled) R.string.plugins_enabled else R.string.plugins_disabled)
                if (lockReason == null) {
                    PluginToggleRow(
                        label = state,
                        checked = bundle.enabled,
                        enabled = !busy,
                        onToggle = onToggleBundle,
                        labelStyle = DsType.rowTitle,
                    )
                } else {
                    // Locked: a dimmed switch reads as "off" even when the bundle is on, so the state is a word.
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget).semantics(mergeDescendants = true) { },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.labelTertiary)
                        Text(state, style = DsType.rowTitle, color = colors.labelPrimary)
                    }
                    Text(stringResource(pluginReason(lockReason)), style = DsType.small13, color = colors.labelTertiary)
                }
            }
        }
        count?.let { enabled ->
            item(key = "count") {
                Text(
                    stringResource(R.string.plugins_count_on, enabled.on, enabled.total),
                    style = DsType.small13Strong,
                    color = colors.labelSecondary,
                    modifier = Modifier.padding(bottom = DsSpacing.xsmall),
                )
            }
        }
        if (rows.isNotEmpty() && shown.isEmpty) item(key = "no-matches") {
            Text(stringResource(R.string.plugins_no_matches), style = DsType.small13, color = colors.labelTertiary)
        }
        items(shown.toggleable, key = { "row:${it.row.rowId}" }) { module ->
            val plugin = module.plugin ?: return@items
            Column {
                PluginToggleRow(
                    label = technicalDisplay(names[module.row.moduleName] ?: module.row.moduleName),
                    qualifier = qualifiers[module.row.rowId],
                    checked = plugin.enabled,
                    enabled = !busy,
                    onToggle = { onTogglePlugin(plugin) },
                )
                PluginRowDivider()
            }
        }
        shown.locked.forEach { group ->
            item(key = "reason:${group.reason}") {
                PluginLockedHeader(
                    stringResource(pluginReason(group.reason)),
                    Modifier.padding(top = DsSpacing.medium, bottom = DsSpacing.xsmall),
                )
            }
            items(group.rows, key = { "row:${it.row.rowId}" }) { module ->
                Column {
                    PluginLockedRow(
                        label = technicalDisplay(names[module.row.moduleName] ?: module.row.moduleName),
                        enabled = module.plugin?.enabled == true,
                        qualifier = qualifiers[module.row.rowId],
                    )
                    PluginRowDivider()
                }
            }
        }
        // Rows with no managed plugin have nothing to flip: said once, because the bundle is what is off.
        plainNote?.let { note ->
            item(key = "plain-note") {
                Text(
                    stringResource(
                        when (note) {
                            PluginPlainNote.BUNDLE_OFF -> R.string.plugins_modules_need_bundle_on
                            PluginPlainNote.NOT_INSTALLED -> R.string.plugins_modules_need_install
                        },
                    ),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                    modifier = Modifier.fillMaxWidth().padding(top = DsSpacing.small, bottom = DsSpacing.xsmall),
                )
            }
        }
        items(shown.plain, key = { "row:${it.row.rowId}" }) { module ->
            PluginNameBlock(
                label = technicalDisplay(names[module.row.moduleName] ?: module.row.moduleName),
                qualifier = qualifiers[module.row.rowId],
                style = DsType.mono12,
                color = colors.labelSecondary,
                modifier = Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget).wrapContentHeight(Alignment.CenterVertically),
            )
        }
        if (canInstall || canRemove) item(key = "actions") {
            Column(Modifier.padding(top = DsSpacing.comfortable), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                if (canInstall) {
                    DsButton(
                        text = stringResource(if (bundle.availability == "missing") R.string.harness_install_update else R.string.ux_a_update),
                        onClick = onUpdate,
                        enabled = !busy,
                        variant = DsButtonVariant.Primary,
                        allowWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (canRemove) {
                    DsButton(
                        text = stringResource(R.string.common_remove),
                        onClick = onRemove,
                        enabled = !busy,
                        variant = DsButtonVariant.Danger,
                        allowWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * The bundle card's switch as its own 48dp target: a [DsSwitch] draws it, this owns the tap and the
 * accessibility name (the switch has none of its own), the same split [ToggleRow] makes.
 */
@Composable
private fun PluginSwitch(name: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val haptics = rememberDsHaptics()
    Box(
        Modifier
            .defaultMinSize(minWidth = DsSpacing.touchTarget, minHeight = DsSpacing.touchTarget)
            .padding(horizontal = DsSpacing.tiny)
            .clip(DsShapes.row)
            .handCursor(enabled)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { haptics.toggle(it); onToggle() },
            )
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        DsSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A technical name that wraps to a second line before it is cut, with an optional small line under it.
 * Two long names that only differ at the end must not read the same.
 */
@Composable
private fun PluginNameBlock(
    label: String,
    qualifier: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = DsType.mono13,
    color: androidx.compose.ui.graphics.Color = DsTheme.colors.labelPrimary,
    maxLines: Int = 2,
) {
    Column(modifier) {
        Text(label, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        qualifier?.let {
            Text(technicalDisplay(it), style = DsType.mono12, color = DsTheme.colors.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A whole-row switch: the row is the target and its text is the name a screen reader says, like
 * [ToggleRow], but with a technical name that wraps to a second line and ends in an ellipsis after
 * that. [qualifier] is the small second line for a row whose module name another row shares.
 *
 * Pressing the row fills it with the pressed tone. The row draws that fill itself and passes
 * `indication = null` to its `toggleable`, so the app's `LocalIndication` (see `FocusRing.kt`) does not
 * draw the same tone over it a second time. A row that sits in a card passes the card's [interactionSource]
 * and `drawsPress = false`, and the card does the filling, so the whole card lights and not just the row.
 */
@Composable
private fun PluginToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = DsType.mono13,
    leading: (@Composable () -> Unit)? = null,
    qualifier: String? = null,
    interactionSource: MutableInteractionSource? = null,
    drawsPress: Boolean = true,
) {
    val haptics = rememberDsHaptics()
    val colors = DsTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = if (drawsPress && enabled && pressed) colors.active else colors.active.copy(alpha = 0f),
        animationSpec = DsAnimations.pressFill,
        label = "rowFill",
    )
    Row(
        modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .drawBehind { drawRect(fill) }
            .heightIn(min = DsSpacing.touchTarget)
            .handCursor(enabled)
            .toggleable(
                value = checked,
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { haptics.toggle(it); onToggle() },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        leading?.invoke()
        PluginNameBlock(label, qualifier, Modifier.weight(1f), labelStyle)
        DsSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** The state of a locked bundle on its card: a lock and a word where the switch would be. */
@Composable
private fun PluginLockedState(enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Row(
        modifier.padding(horizontal = DsSpacing.tiny).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(12.dp), tint = colors.labelTertiary)
        Text(
            stringResource(if (enabled) R.string.plugins_enabled else R.string.plugins_disabled),
            style = DsType.small13,
            color = colors.labelSecondary,
        )
    }
}

/** The lock and the reason a group of rows cannot be changed, written once above them. */
@Composable
private fun PluginLockedHeader(reason: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Row(
        modifier.fillMaxWidth().semantics { heading() },
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(16.dp), tint = colors.labelTertiary)
        Text(reason, style = DsType.small13, color = colors.labelSecondary, modifier = Modifier.weight(1f))
    }
}

/**
 * A locked row: its name and its state as a word. There is no switch to hint at a state. The name
 * wraps as far as it needs and is never cut: a locked row is information only, so the id is the one
 * thing it has to show whole, and at a large font two lines were not enough for a long one.
 */
@Composable
private fun PluginLockedRow(label: String, enabled: Boolean, modifier: Modifier = Modifier, qualifier: String? = null) {
    val colors = DsTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = DsSpacing.touchTarget)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        PluginNameBlock(label, qualifier, Modifier.weight(1f), maxLines = Int.MAX_VALUE)
        Text(
            stringResource(if (enabled) R.string.plugins_enabled else R.string.plugins_disabled),
            style = DsType.small13,
            color = colors.labelSecondary,
        )
    }
}

@Composable
internal fun PluginRowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(DsTheme.colors.borderL1))
}

/** The plugin glyph on its tinted plate, the same shape the design uses for a module. */
@Composable
internal fun PluginGlyph(size: androidx.compose.ui.unit.Dp = 40.dp) {
    val colors = DsTheme.colors
    Box(
        Modifier.size(size).clip(DsShapes.buttonSmall).background(colors.bgLayer3),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Extension,
            contentDescription = null,
            modifier = Modifier.size(size * 0.45f),
            tint = colors.labelSecondary,
        )
    }
}

/** On/off/loading/failed tag beside a managed plugin or an operation. */
@Composable
private fun PluginPhaseTag(application: String?, busy: Boolean, unknown: Boolean, failed: Boolean) {
    val colors = DsTheme.colors
    val label = stringResource(pluginTagLabel(application, busy, unknown, failed))
    val (foreground, background, border) = when {
        busy -> Triple(colors.warnLabel, colors.warnTertiary, colors.warnBorder)
        unknown || failed -> Triple(colors.error, colors.errorTertiary, colors.errorBorder)
        application == "applied" -> Triple(colors.success, colors.successTertiary, colors.successBorder)
        application == "restart-required" -> Triple(colors.warnLabel, colors.warnTertiary, colors.warnBorder)
        application != null -> Triple(colors.error, colors.errorTertiary, colors.errorBorder)
        else -> Triple(colors.labelSecondary, colors.bgLayer3, colors.borderL2)
    }
    Text(
        label,
        style = DsType.caption11Strong,
        color = foreground,
        maxLines = 2,
        modifier = Modifier
            .clip(DsShapes.chip)
            .background(background)
            .border(1.dp, border, DsShapes.chip)
            .padding(horizontal = DsSpacing.xsmall, vertical = 2.dp),
    )
}

/** Placeholder cards while the plugin inventory is on the wire. */
@Composable
internal fun SkeletonPluginCards() {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
        repeat(3) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(DsShapes.block)
                    .background(colors.bgLayer1)
                    .border(1.dp, colors.borderL2, DsShapes.block)
                    .padding(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).skeleton(colors.bgLayer3, colors.borderL2, DsShapes.buttonSmall))
                    Spacer(Modifier.width(DsSpacing.small))
                    Box(Modifier.fillMaxWidth(0.5f).height(12.dp).skeleton(colors.bgLayer2, colors.borderL2, DsShapes.chip))
                }
                Box(Modifier.fillMaxWidth(0.85f).height(10.dp).skeleton(colors.bgLayer2, colors.borderL2, DsShapes.chip))
                Box(Modifier.fillMaxWidth(0.6f).height(10.dp).skeleton(colors.bgLayer2, colors.borderL2, DsShapes.chip))
            }
        }
    }
}
