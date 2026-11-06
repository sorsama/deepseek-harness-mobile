package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import com.labteto.dshmobile.data.HarnessFeature
import com.labteto.dshmobile.ui.components.*
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.*
import kotlinx.coroutines.CancellationException
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
    var search by remember(host) { mutableStateOf("") }
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
    LaunchedEffect(host, connection.phase, revision, current?.result, current?.unknown) { run { api ->
        val next = store.featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.pluginBundles() }.featureValue()
        val rows = store.featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.managedPlugins() }.featureValue()
        val sources = store.featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.pluginRegistries() }.featureValue()
        if (store.activeHostKey == host) { bundles = next; plugins = rows; registries = sources; loaded = true }
    } }
    LaunchedEffect(host) { store.hostEvents.collect { if (it.first == "plugin-manager/changed") revision++ } }

    @Composable
    fun PluginToggle(plugin: ManagedPlugin, compact: Boolean = false) {
        val hint = plugin.readOnlyReason?.let { stringResource(pluginReason(it)) }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (compact) 44.dp else DsSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            if (!compact) {
                PluginGlyph(size = 40.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    technicalDisplay(plugin.moduleName),
                    style = if (compact) DsType.mono12 else DsType.mono13,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hint?.let {
                    Text(it, style = DsType.caption11, color = colors.labelTertiary, maxLines = 2)
                }
            }
            DsSwitch(
                checked = plugin.enabled,
                enabled = !busy && plugin.readOnlyReason == null,
                onCheckedChange = {
                    run { api ->
                        val result = store.featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.setPluginEnabled(plugin.entryId, !plugin.enabled) }.featureValue()
                        if (host != null) store.pluginOperations.changed(host, result); revision++
                    }
                },
            )
        }
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
                    state.displaySubject()?.let(::technicalDisplay) ?: stringResource(R.string.ux_a_previous_install),
                    style = DsType.std14Strong,
                    color = colors.labelPrimary,
                    maxLines = 1,
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
                    state.displaySubject()?.let(::technicalDisplay) ?: stringResource(R.string.ux_a_previous_install),
                    style = DsType.std14Strong,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PluginPhaseTag(result.application, busy = false, unknown = false, failed = result.application == "failed")
            }
            result.version?.let { Text(technicalDisplay(it), style = DsType.mono12, color = colors.labelSecondary) }
            Text(
                stringResource(when (result.application) {
                    "applied" -> R.string.harness_applied; "restart-required" -> R.string.harness_restart
                    "overridden" -> R.string.harness_overridden; "cancelled" -> R.string.harness_cancelled; else -> R.string.harness_failed
                }),
                style = DsType.std14,
                color = if (result.application == "restart-required") colors.warnText else colors.labelSecondary,
            )
            result.error?.let { Text(stringResource(pluginReason(it.code)), color = colors.error, style = DsType.small13) }
            state.approvalSubject()?.let { approval ->
                FeatureAction(stringResource(R.string.ux_a_approve_retry), !busy, DsButtonVariant.Primary) { confirm = approval }
            }
            FeatureDiagnostics(listOfNotNull(state.requestId, result.error?.code, result.error?.diagnostic,
                result.warnings.joinToString("\n").ifBlank { null }, result.packageResult?.output?.takeLast(8192),
                result.packageResult?.takeIf { it.truncated }?.logPath, state.log.ifBlank { null }).joinToString("\n"))
        } } }
    }

    FeaturePage(
        title = stringResource(R.string.settings_plugins),
        onClose = onClose,
        onRefresh = { if (!busy) { revision++ } },
        footer = {
            Text(
                stringResource(R.string.ink_files_plugins_intro),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        },
    ) {
        DsSearchField(search, { search = it }, stringResource(R.string.plugins_search_hint))
        DsButton(
            text = stringResource(R.string.harness_install_update),
            icon = Icons.Filled.Add,
            onClick = {
                spec = ""; registry = ""; inspection = null; installedVersion = null; replacing = null; installOpen = true
            },
            enabled = !busy,
            variant = DsButtonVariant.Primary,
            allowWrap = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (presets?.presets?.any { it.id == "cordis" && it.broken == null } == true) {
            DsButton(
                text = stringResource(if (loading) R.string.common_loading else R.string.harness_create_plugin),
                onClick = {
                    run {
                        if (host != null && store.stageFeatureDraft(host, creator, "cordis")) onDraftOpened() else error(offline)
                    }
                },
                enabled = !busy,
                variant = DsButtonVariant.Outline,
                allowWrap = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (loading && loaded) FeatureProgress()
        (pending.filter { it.host == host } + listOfNotNull(current?.takeIf { it.result != null })).forEach { state ->
            DsCard(onClick = { operationOpen = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.displaySubject()?.let(::technicalDisplay) ?: stringResource(R.string.ux_a_previous_install),
                        style = DsType.std14Strong,
                        color = colors.labelPrimary,
                        maxLines = 1,
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
                Text(
                    stringResource(when {
                        state.unknown -> R.string.ux_a_check_status
                        state.busy -> pluginPhase(state.phase)
                        state.result?.application == "restart-required" -> R.string.harness_restart
                        state.result?.application == "applied" -> R.string.harness_applied
                        state.result?.application == "cancelled" -> R.string.harness_cancelled
                        else -> R.string.harness_failed
                    }),
                    style = DsType.small13,
                    color = if (state.unknown || state.result?.application == "restart-required") colors.warnText else colors.labelSecondary,
                )
                if (state.busy) FeatureProgress()
            }
        }
        if (busy && !loaded) SkeletonPluginCards()
        Failure()
        val bundledEntries = bundles.flatMap { it.rows }.mapNotNull { it.entryId }.toSet()
        val standalone = plugins.filter { it.entryId !in bundledEntries && it.moduleName.contains(search, true) }
        val matching = bundles.filter { it.name.contains(search, true) || it.description.orEmpty().contains(search, true) || it.rows.any { row -> row.moduleName.contains(search, true) } }
        if (loaded && matching.isEmpty() && standalone.isEmpty()) EmptyHero(
            stringResource(if (search.isBlank()) R.string.plugins_empty else R.string.harness_automation_no_matches), null, showPreview = false)
        listOf(true, false).forEach { installed ->
            val group = matching.filter { (it.availability?.let { location -> location != "missing" } ?: it.installed) == installed }
            if (group.isNotEmpty() || installed && standalone.isNotEmpty()) SectionHeader(stringResource(if (installed) R.string.ux_a_installed else R.string.ux_a_available))
            if (installed) standalone.forEach { plugin ->
                DsCard { PluginToggle(plugin) }
            }
            group.forEach { bundle ->
                DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PluginGlyph(size = 40.dp)
                        Spacer(Modifier.width(DsSpacing.small))
                        Column(Modifier.weight(1f)) {
                            Text(
                                technicalDisplay(bundle.name),
                                style = DsType.mono13,
                                color = colors.labelPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val meta = listOfNotNull(
                                bundle.version,
                                bundle.readOnlyReason?.let { stringResource(pluginReason(it)) },
                            ).joinToString(" · ")
                            if (meta.isNotEmpty()) {
                                Text(meta, style = DsType.small13, color = colors.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        DsSwitch(
                            checked = bundle.enabled,
                            enabled = !busy && bundle.readOnlyReason == null,
                            onCheckedChange = {
                                if (!bundle.enabled && bundle.availability == "missing" && bundle.installTarget != null) {
                                    confirm = bundle.catalogInstall(enabled = true)
                                } else run { api ->
                                    val result = store.featureCall(HarnessFeature.PLUGIN_MANAGEMENT, api) { api.setBundleEnabled(bundle.name, !bundle.enabled) }.featureValue()
                                    if (host != null) store.pluginOperations.changed(host, result); revision++
                                }
                            },
                        )
                    }
                    bundle.description?.let { Text(it, style = DsType.std14, color = colors.labelSecondary) }
                    bundle.error?.let {
                        Text(stringResource(pluginReason(it.code)), color = colors.error, style = DsType.small13)
                        FeatureDiagnostics(it.diagnostic)
                    }
                    bundle.rows.forEach { row ->
                        val plugin = plugins.firstOrNull { it.entryId == row.entryId }
                        if (plugin != null) {
                            PluginToggle(plugin, compact = true)
                        } else {
                            Text(technicalDisplay(row.moduleName), style = DsType.mono12, color = colors.labelSecondary)
                        }
                    }
                    val hasActions = bundle.availability != "installation" && (bundle.installed || bundle.installTarget != null) ||
                        bundle.removable && bundle.readOnlyReason == null
                    if (hasActions) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                        ) {
                            if (bundle.availability != "installation" && (bundle.installed || bundle.installTarget != null)) {
                                DsButton(
                                    text = stringResource(if (bundle.availability == "missing") R.string.harness_install_update else R.string.ux_a_update),
                                    onClick = {
                                        val catalog = bundle.catalogInstall(enabled = bundle.enabled)
                                        if (catalog != null) confirm = catalog
                                        else { spec = bundle.source ?: bundle.name; registry = ""; inspection = null; installedVersion = bundle.version; replacing = bundle.name.takeIf { bundle.installed }; installOpen = true }
                                    },
                                    enabled = !busy,
                                    variant = DsButtonVariant.Outline,
                                    size = DsButtonSize.Small,
                                )
                            }
                            if (bundle.removable && bundle.readOnlyReason == null) {
                                DsButton(
                                    text = stringResource(R.string.common_remove),
                                    onClick = { remove = bundle },
                                    enabled = !busy,
                                    variant = DsButtonVariant.Outline,
                                    size = DsButtonSize.Small,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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

/** The plugin glyph on its tinted plate, the same shape the design uses for a module. */
@Composable
private fun PluginGlyph(size: androidx.compose.ui.unit.Dp = 40.dp) {
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
    val (label, foreground, background, border) = when {
        busy -> PluginTag(stringResource(R.string.common_loading), colors.warnLabel, colors.warnTertiary, colors.warnBorder)
        unknown -> PluginTag(stringResource(R.string.harness_failed), colors.error, colors.errorTertiary, colors.errorBorder)
        failed -> PluginTag(stringResource(R.string.harness_failed), colors.error, colors.errorTertiary, colors.errorBorder)
        application == "applied" -> PluginTag(stringResource(R.string.harness_applied), colors.success, colors.successTertiary, colors.successBorder)
        application == "restart-required" -> PluginTag(stringResource(R.string.harness_restart), colors.warnLabel, colors.warnTertiary, colors.warnBorder)
        application != null -> PluginTag(stringResource(R.string.harness_failed), colors.error, colors.errorTertiary, colors.errorBorder)
        else -> PluginTag(stringResource(R.string.ux_a_installed), colors.labelSecondary, colors.bgLayer3, colors.borderL2)
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

private data class PluginTag(
    val label: String,
    val foreground: androidx.compose.ui.graphics.Color,
    val background: androidx.compose.ui.graphics.Color,
    val border: androidx.compose.ui.graphics.Color,
)

/** Placeholder cards while the plugin inventory is on the wire. */
@Composable
private fun SkeletonPluginCards() {
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

