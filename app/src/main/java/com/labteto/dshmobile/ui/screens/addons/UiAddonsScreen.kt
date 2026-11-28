package com.labteto.dshmobile.ui.screens.addons

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.core.settings.SettingsFailure
import com.labteto.dshmobile.core.uisync.UiSyncDiscoveryOutcome
import com.labteto.dshmobile.core.uisync.UiSyncDiscoveryPhase
import com.labteto.dshmobile.core.uisync.UiSyncDiscoveryTrigger
import com.labteto.dshmobile.core.uisync.UiSyncSettingsPhase
import com.labteto.dshmobile.core.uisync.UiSyncSettingsState
import com.labteto.dshmobile.core.uisync.UiSyncStoredItem
import com.labteto.dshmobile.core.uisync.uiSyncDiscoveryNeed
import com.labteto.dshmobile.core.uisync.uiSyncDiscoveryStale
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.managedPlugins
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.addons.LocalUiAddonsHost
import com.labteto.dshmobile.ui.addons.UiAddonsDiscoveryRecords
import com.labteto.dshmobile.ui.addons.uiAddonsDefaultOn
import com.labteto.dshmobile.ui.addons.uiAddonsHostKey
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.LocalSessionStore
import com.labteto.dshmobile.ui.screens.main.FeatureAction
import com.labteto.dshmobile.ui.screens.main.FeatureDiagnostics
import com.labteto.dshmobile.ui.screens.main.FeaturePage
import com.labteto.dshmobile.ui.screens.main.technicalDisplay
import com.labteto.dshmobile.ui.screens.models.Notice
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException

/** Whether "Show plugin add-ons" reads as on: what was set, else this device's memory default. */
@Composable
internal fun rememberUiAddonsShown(settings: AppSettings): Boolean {
    val context = LocalContext.current
    val defaultOn = remember(context) { uiAddonsDefaultOn(context) }
    return settings.showUiAddons ?: defaultOn
}

/**
 * The Settings card for plugin add-ons, shown while the harness has the `dsh-ui-sync` companion:
 * the device's own switch, and the way into the per-item targets.
 */
@Composable
fun UiAddonsSettingsCardContent(settings: AppSettings, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    val defaultOn = remember(context) { uiAddonsDefaultOn(context) }
    val shown = settings.showUiAddons ?: defaultOn
    ToggleRow(stringResource(R.string.ua_switch), shown, stringResource(R.string.ua_switch_hint)) { onToggle(!shown) }
    if (settings.showUiAddons == null && !defaultOn) {
        Text(stringResource(R.string.ua_switch_low_memory), style = DsType.caption11, color = colors.labelTertiary)
    }
    UiAddonsRow(onOpen)
}

/** The row that opens the Add-ons page: what it is for, and a chevron. */
@Composable
private fun UiAddonsRow(onOpen: () -> Unit) {
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
        Text(stringResource(R.string.ua_entry), Modifier.weight(1f), style = DsType.std14, color = colors.labelSecondary)
        // The arrow mirrors in right-to-left languages.
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.labelTertiary)
    }
}

/**
 * Settings > Add-ons: every plugin item the harness's item store knows, grouped by plugin package,
 * each with where it sits in the harness and where it shows: Both, Phone, Desktop or Off (only
 * "Show on desktop" and Off for an item whose slot has no place on the phone).
 *
 * The list is the `ui-sync` settings row the companion's pages keep on the host, so it needs no
 * page of its own. A pick is saved at once (the app's convention for switches), through
 * `settings/mutate` fenced on the row revision; the desktop and an open phone page follow it on
 * their own. A refusal puts the item back and says why under it. The list follows changes made
 * elsewhere while it is open (`settings/document-updated`).
 *
 * A package is headed by the title its plugin gives itself, read from the harness's plugin list
 * when the connection may ask for it, else by a readable form of its npm name.
 */
@Composable
fun UiAddonsScreen(settings: AppSettings, onClose: () -> Unit) {
    val store = LocalSessionStore.current
    val state by store.uiAddonsSettings.state.collectAsStateWithLifecycle()
    val connection by store.connectionState.collectAsStateWithLifecycle()
    val shownHere = rememberUiAddonsShown(settings)
    // Opened for one harness; a different one connecting closes it rather than showing its list under this title.
    val host = store.activeHostKey
    val openedFor = rememberSaveable { mutableStateOf(host) }.value
    LaunchedEffect(host) { if (openedFor != null && host != null && host != openedFor) onClose() }
    LaunchedEffect(Unit) { store.refreshUiAddons() }
    // Reopened, the page should not scold for a change nobody is making.
    DisposableEffect(store) { onDispose { store.uiAddonsSettings.clearFailures() } }
    val connected = connection.phase == ConnectionPhase.CONNECTED
    val titles = rememberAddonPackageTitles(store, host, connected)

    // Finding add-ons the harness has not listed yet: the web layer runs the page, this page shows how it goes.
    val discovery by store.uiAddonsDiscovery.state.collectAsStateWithLifecycle()
    val inventory by store.uiAddonsInventory.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val records = remember(context) { UiAddonsDiscoveryRecords(context) }
    val canDiscover = LocalUiAddonsHost.current != null
    // The run whose result this page shows: one started here, or one it saw running.
    var watchedRun by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(discovery) { (discovery as? UiSyncDiscoveryPhase.Running)?.let { watchedRun = it.run } }
    val plugins = inventory?.takeIf { it.host == host }
    val need = remember(state.row, state.store, plugins) {
        if (state.row != null && plugins != null) uiSyncDiscoveryNeed(state.store, plugins.entries) else null
    }
    val hostConfig = connection.host
    // Read again with every step of a run: its end writes the record.
    val record = remember(hostConfig, discovery) { hostConfig?.let { records.get(uiAddonsHostKey(it)) } }
    val find: (() -> Unit)? = if (canDiscover && connected) {
        { watchedRun = store.uiAddonsDiscovery.start(UiSyncDiscoveryTrigger.MANUAL, SystemClock.elapsedRealtime()) }
    } else {
        null
    }
    val finding = AddonsFinding(
        phase = discovery,
        result = (discovery as? UiSyncDiscoveryPhase.Done)?.takeIf { it.run == watchedRun }?.outcome,
        stale = uiSyncDiscoveryStale(need, record),
        find = find,
    )

    FeaturePage(
        title = stringResource(R.string.ua_title),
        subtitle = stringResource(R.string.ua_intro),
        onClose = onClose,
        onRefresh = { store.refreshUiAddons() },
    ) {
        if (!connected) Notice(stringResource(R.string.common_offline), warning = true)
        if (!shownHere) Notice(stringResource(R.string.ua_off_notice), warning = false)
        UiAddonsBody(state, store, titles, finding)
    }
}

/** What the page knows about finding add-ons: the run, the result of the one it watched, whether the list looks stale, and the action (null when none can run). */
private data class AddonsFinding(
    val phase: UiSyncDiscoveryPhase,
    val result: UiSyncDiscoveryOutcome?,
    val stale: Boolean,
    val find: (() -> Unit)?,
) {
    val running: Boolean get() = phase is UiSyncDiscoveryPhase.Running
}

/**
 * The plugins' own titles by module name, from the harness's plugin list. Best effort: a
 * connection that may not call the plugin manager (a relay that keeps it to its own machine), or
 * a harness without it, leaves this empty and the headers read the npm names in words.
 */
@Composable
private fun rememberAddonPackageTitles(store: SessionStore, host: String?, connected: Boolean): Map<String, String> {
    val locale = LocalConfiguration.current.locales[0]
    val tags = remember(locale) { listOf(locale.toLanguageTag(), locale.language) }
    var titles by remember(host) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(host, connected, tags) {
        if (!connected || store.pluginManagementAvailable.value == false) return@LaunchedEffect
        val api = store.apiForHost(host) ?: return@LaunchedEffect
        // Read straight from the API on purpose: a refusal here must not turn the Plugins page's feature flag off.
        val result = try {
            api.managedPlugins()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@LaunchedEffect
        }
        if (result is RpcResult.Ok && store.activeHostKey == host) titles = addonTitleMap(result.value, tags)
    }
    return titles
}

@Composable
private fun UiAddonsBody(state: UiSyncSettingsState, store: SessionStore, titles: Map<String, String>, finding: AddonsFinding) {
    val colors = DsTheme.colors
    when (val phase = state.phase) {
        UiSyncSettingsPhase.Idle, UiSyncSettingsPhase.Loading -> if (state.row == null) Skeleton()
        UiSyncSettingsPhase.Missing -> Notice(stringResource(R.string.ua_missing), warning = false)
        is UiSyncSettingsPhase.Failed -> DsCard {
            Text(stringResource(R.string.ux_a_load_error), color = colors.error, style = DsType.std14)
            FeatureAction(stringResource(R.string.common_retry)) { store.refreshUiAddons() }
            FeatureDiagnostics(failureDetail(phase.failure))
        }
        UiSyncSettingsPhase.Ready -> Unit
    }
    if (state.row == null) return
    if (!state.writable) Notice(stringResource(R.string.ua_read_only), warning = false)
    state.refreshFailed?.let {
        LaunchedEffect(it) {
            kotlinx.coroutines.delay(6000)
            store.uiAddonsSettings.dismissRefreshFailed()
        }
        Notice(stringResource(R.string.ua_refresh_failed), warning = true)
    }
    val groups = state.store.byPackage
    if (groups.isEmpty()) {
        EmptyAddons(finding)
        return
    }
    FindingAboveList(finding)
    val headings = groups.map { (pkg, _) -> addonPackageTitle(pkg, titles) }
    groups.forEachIndexed { groupIndex, (pkg, items) ->
        val heading = headings[groupIndex]
        // The plugin's own name for itself, as a section label like the ones on the harness settings
        // pages; two packages that read the same keep their npm name under it.
        SectionHeader(heading)
        if (headings.count { it == heading } > 1) IdLine(pkg)
        DsCard(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = colors.borderL1, modifier = Modifier.padding(vertical = DsSpacing.small))
                ItemRow(item, items, state, store)
            }
        }
    }
    // A quiet way to look again, for a plugin added since (the list cannot always tell).
    val find = finding.find
    if (find != null && !finding.running && !finding.stale && finding.result != UiSyncDiscoveryOutcome.FAILED) {
        FeatureAction(stringResource(R.string.ua_find_more), variant = DsButtonVariant.Ghost, onClick = find)
    }
}

/**
 * The list is empty: say what belongs here and offer to look. While a look runs, it says so; when
 * one this page watched found nothing, or failed, it says that and offers to look again.
 */
@Composable
private fun EmptyAddons(finding: AddonsFinding) {
    val colors = DsTheme.colors
    val nothing = finding.result == UiSyncDiscoveryOutcome.NOTHING && !finding.running
    val failed = finding.result == UiSyncDiscoveryOutcome.FAILED && !finding.running
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text(
            stringResource(if (nothing) R.string.ua_nothing_title else R.string.ua_empty_title),
            style = DsType.rowTitle,
            color = colors.labelPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(if (nothing) R.string.ua_nothing else R.string.ua_empty),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = if (nothing) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
        )
        when {
            finding.running -> FindingProgress()
            failed -> FindFailed()
            finding.find != null && !nothing -> Text(stringResource(R.string.ua_find_hint), style = DsType.small13, color = colors.labelTertiary)
        }
        val find = finding.find
        if (find != null && !finding.running) {
            FeatureAction(
                stringResource(if (nothing || failed) R.string.ua_find_again else R.string.ua_find),
                variant = DsButtonVariant.Outline,
                onClick = find,
            )
        }
    }
}

/** The list has items: a look in progress, its result, or a note that a plugin may be missing. */
@Composable
private fun FindingAboveList(finding: AddonsFinding) {
    val colors = DsTheme.colors
    val find = finding.find
    when {
        finding.running -> DsCard { FindingProgress() }
        finding.result == UiSyncDiscoveryOutcome.FAILED -> DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            FindFailed()
            if (find != null) FeatureAction(stringResource(R.string.ua_find_again), onClick = find)
        }
        finding.result == UiSyncDiscoveryOutcome.FOUND -> Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Notice(stringResource(R.string.ua_found), warning = false)
        }
        finding.stale && find != null -> DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Text(stringResource(R.string.ua_stale), style = DsType.std14, color = colors.labelSecondary)
            Text(stringResource(R.string.ua_find_hint), style = DsType.small13, color = colors.labelTertiary)
            FeatureAction(stringResource(R.string.ua_find), onClick = find)
        }
    }
}

/** "Looking for add-ons", with a spinner; read out once when it appears. */
@Composable
private fun FindingProgress() {
    val colors = DsTheme.colors
    val text = stringResource(R.string.ua_finding)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DsSpacing.touchTarget)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), color = colors.labelSecondary, strokeWidth = 2.dp)
        Text(text, style = DsType.std14, color = colors.labelSecondary)
    }
}

@Composable
private fun FindFailed() {
    Text(
        stringResource(R.string.ua_find_failed),
        style = DsType.small13,
        color = DsTheme.colors.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** A technical id as a small, secondary line a person can select and copy, left to right in every language. */
@Composable
private fun IdLine(id: String) {
    SelectionContainer {
        Text(technicalDisplay(id), style = DsType.caption11, color = DsTheme.colors.labelTertiary)
    }
}

@Composable
private fun ItemRow(item: UiSyncStoredItem, siblings: List<UiSyncStoredItem>, state: UiSyncSettingsState, store: SessionStore) {
    val colors = DsTheme.colors
    val title = item.displayTitle
    val phoneCanShow = item.placement != null
    val selected = addonOptionOf(state.writing[item.id] ?: item.target, phoneCanShow)
    val groupLabel = stringResource(R.string.ua_target_group, title)
    val options = addonOptions(phoneCanShow).map { option ->
        AddonPickerOption(option.name, stringResource(shortLabel(option)), stringResource(longLabel(option)))
    }
    val idLine = addonIdLine(item, siblings)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
            Text(title, style = DsType.rowTitle, color = colors.labelPrimary, modifier = Modifier.semantics { heading() })
            // Where the item sits, in words; the raw slot id never stands in for them.
            Text(stringResource(slotText(addonSlot(item.slot))), style = DsType.small13, color = colors.labelTertiary)
            if (!phoneCanShow) Text(stringResource(R.string.ua_place_none), style = DsType.small13, color = colors.labelTertiary)
            if (idLine != null) IdLine(idLine)
        }
        AddonTargetPicker(
            options = options,
            selectedKey = selected.name,
            onSelect = { key ->
                addonOptionByName(key, phoneCanShow)?.let { store.uiAddonsSettings.setTarget(item.id, it.stores) }
            },
            groupLabel = groupLabel,
            enabled = state.writable,
        )
        state.failures[item.id]?.let { failure ->
            Text(
                stringResource(failureText(failure.failure)),
                style = DsType.small13,
                color = colors.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** The choice a picker key names, among those the item offers. */
private fun addonOptionByName(key: String, phoneCanShow: Boolean): AddonOption? = addonOptions(phoneCanShow).firstOrNull { it.name == key }

@StringRes
private fun shortLabel(option: AddonOption): Int = when (option) {
    AddonOption.BOTH -> R.string.ua_target_both
    AddonOption.PHONE -> R.string.ua_target_phone
    AddonOption.DESKTOP -> R.string.ua_target_desktop
    AddonOption.OFF -> R.string.ua_target_off
    AddonOption.SHOW_ON_DESKTOP -> R.string.ua_target_show_desktop
}

@StringRes
private fun longLabel(option: AddonOption): Int = when (option) {
    AddonOption.BOTH -> R.string.ua_target_both_long
    AddonOption.PHONE -> R.string.ua_target_phone_long
    AddonOption.DESKTOP -> R.string.ua_target_desktop_long
    AddonOption.OFF -> R.string.ua_target_off
    AddonOption.SHOW_ON_DESKTOP -> R.string.ua_target_show_desktop
}

@StringRes
private fun slotText(slot: AddonSlot): Int = when (slot) {
    AddonSlot.OVERLAY -> R.string.ua_slot_overlay
    AddonSlot.MESSAGE_BOX -> R.string.ua_slot_message_box
    AddonSlot.SESSION_HEADER -> R.string.ua_slot_session_header
    AddonSlot.PLUGIN_ROW -> R.string.ua_slot_plugin_row
    AddonSlot.SIDEBAR_FOOTER -> R.string.ua_slot_sidebar_footer
    AddonSlot.OTHER -> R.string.ua_slot_other
}

@StringRes
private fun failureText(failure: SettingsFailure): Int = when (failure) {
    SettingsFailure.Conflict -> R.string.ua_err_conflict
    SettingsFailure.Unavailable -> R.string.ua_err_unavailable
    is SettingsFailure.Rejected -> R.string.ua_err_rejected
    is SettingsFailure.Unreachable -> R.string.ua_err_unreachable
}

/** The harness's own words for a failed first read, for the "Technical details" line. */
private fun failureDetail(failure: SettingsFailure): String? = when (failure) {
    is SettingsFailure.Rejected -> failure.detail
    is SettingsFailure.Unreachable -> failure.detail
    else -> null
}

@Composable
private fun Skeleton() {
    val colors = DsTheme.colors
    val loading = stringResource(R.string.common_loading)
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = loading; liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        repeat(2) {
            Box(Modifier.fillMaxWidth().height(96.dp).skeleton(colors.bgLayer2, colors.hover, DsShapes.block))
        }
    }
}
