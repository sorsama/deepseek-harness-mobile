package com.labteto.dshmobile.ui.screens.addons

import androidx.annotation.StringRes
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.labteto.dshmobile.core.uisync.UiSyncPlacement
import com.labteto.dshmobile.core.uisync.UiSyncSettingsPhase
import com.labteto.dshmobile.core.uisync.UiSyncSettingsState
import com.labteto.dshmobile.core.uisync.UiSyncStoredItem
import com.labteto.dshmobile.core.uisync.UiSyncTarget
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.addons.uiAddonsDefaultOn
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.ToggleRow
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
 * each with where it shows: Both, Phone only, Desktop only or Off.
 *
 * The list is the `ui-sync` settings row the companion's pages keep on the host, so it needs no
 * page of its own. A pick is saved at once (the app's convention for switches), through
 * `settings/mutate` fenced on the row revision; the desktop and an open phone page follow it on
 * their own. A refusal puts the item back and says why under it. The list follows changes made
 * elsewhere while it is open (`settings/document-updated`).
 */
@Composable
fun UiAddonsScreen(settings: AppSettings, onClose: () -> Unit) {
    val store = rememberSessionStore()
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

    FeaturePage(
        title = stringResource(R.string.ua_title),
        subtitle = stringResource(R.string.ua_intro),
        onClose = onClose,
        onRefresh = { store.refreshUiAddons() },
    ) {
        if (connection.phase != ConnectionPhase.CONNECTED) Notice(stringResource(R.string.common_offline), warning = true)
        if (!shownHere) Notice(stringResource(R.string.ua_off_notice), warning = false)
        UiAddonsBody(state, store)
    }
}

@Composable
private fun UiAddonsBody(state: UiSyncSettingsState, store: SessionStore) {
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
        Text(stringResource(R.string.ua_empty), style = DsType.std14, color = colors.labelTertiary)
        return
    }
    groups.forEach { (pkg, items) ->
        // Package names are what the harness's plugin list shows, so they read the same in every language.
        SectionHeader(pkg)
        DsCard(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            // Two items of one plugin with the same name are told apart by their slot.
            val repeated = items.groupingBy { it.displayTitle }.eachCount().filterValues { it > 1 }.keys
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = colors.borderL1, modifier = Modifier.padding(vertical = DsSpacing.small))
                ItemRow(item, state, store, showSlot = item.displayTitle in repeated)
            }
        }
    }
}

@Composable
private fun ItemRow(item: UiSyncStoredItem, state: UiSyncSettingsState, store: SessionStore, showSlot: Boolean) {
    val colors = DsTheme.colors
    val title = item.displayTitle
    val selected = state.writing[item.id] ?: item.target
    val groupLabel = stringResource(R.string.ua_target_group, title)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
            Text(title, style = DsType.rowTitle, color = colors.labelPrimary, modifier = Modifier.semantics { heading() })
            Text(stringResource(placementText(item.placement)), style = DsType.small13, color = colors.labelTertiary)
            // The slot's own name, left to right in every language: the one thing that differs.
            if (showSlot) Text(com.labteto.dshmobile.ui.screens.main.technicalDisplay(item.slot), style = DsType.caption11, color = colors.labelTertiary)
        }
        DsSegmented(
            segments = listOf(
                DsSegment(UiSyncTarget.BOTH.wire, stringResource(R.string.ua_target_both)),
                DsSegment(UiSyncTarget.PHONE.wire, stringResource(R.string.ua_target_phone)),
                DsSegment(UiSyncTarget.DESKTOP.wire, stringResource(R.string.ua_target_desktop)),
                DsSegment(UiSyncTarget.OFF.wire, stringResource(R.string.ua_target_off)),
            ),
            selectedKey = selected.wire,
            onSelect = { key -> UiSyncTarget.of(key)?.let { store.uiAddonsSettings.setTarget(item.id, it) } },
            enabled = state.writable,
            stretch = true,
            maxLabelLines = 2,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = groupLabel },
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

@StringRes
private fun placementText(placement: UiSyncPlacement?): Int = when (placement) {
    UiSyncPlacement.OVERLAY -> R.string.ua_place_overlay
    UiSyncPlacement.INPUT_LEFT, UiSyncPlacement.INPUT_RIGHT -> R.string.ua_place_input
    UiSyncPlacement.HEADER_ACTIONS -> R.string.ua_place_header
    null -> R.string.ua_place_none
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
