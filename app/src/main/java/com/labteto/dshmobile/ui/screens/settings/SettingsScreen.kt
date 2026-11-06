package com.labteto.dshmobile.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.update.UpdateChecker
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.BuildConfig
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.connection.ConnectionUiState
import com.labteto.dshmobile.core.DshCore
import com.labteto.dshmobile.core.wire.dto.PluginFiberPhase
import com.labteto.dshmobile.core.wire.dto.PluginInventoryEntry
import com.labteto.dshmobile.core.wire.dto.PluginInventorySnapshot
import com.labteto.dshmobile.termux.HarnessControl
import com.labteto.dshmobile.termux.TermuxPaths
import com.labteto.dshmobile.ui.screens.connect.LoopbackStatus
import com.labteto.dshmobile.ui.screens.connect.harnessFailureCommand
import com.labteto.dshmobile.ui.screens.connect.harnessFailureMessage
import com.labteto.dshmobile.ui.screens.connect.loopbackStatusText
import com.labteto.dshmobile.ui.screens.main.ConfirmDialog
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.DsSearchField
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.AccentPalette
import com.labteto.dshmobile.ui.theme.CUSTOM_ACCENT_KEY
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.FontSizeOption
import com.labteto.dshmobile.ui.theme.ThemeCustomization
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.labteto.dshmobile.ui.theme.deriveAccentRamp
import com.labteto.dshmobile.ui.theme.isDsDark
import com.labteto.dshmobile.ui.theme.parseAccentHex
import com.labteto.dshmobile.ui.theme.resolveCustomAccent
import com.labteto.dshmobile.ui.theme.toAccentHex
import java.util.Locale

/**
 * App settings, grouped into cards.
 *
 * Only the top group is genuinely the app's own; connection, harness facts and data are all about
 * the *link* to a harness. Keeping the read-only notice scoped to the harness group matters —
 * blanket-labelling the whole screen read-only, as it used to, tells users their own preferences
 * cannot be changed when they plainly can.
 */
@Composable
fun SettingsScreen(onClose: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val store = rememberSessionStore()
    val plugins by store.plugins.collectAsStateWithLifecycle()
    val pluginManagementAvailable by store.pluginManagementAvailable.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    val toast = rememberDsToast()
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var pluginsOpen by remember { mutableStateOf(false) }
    // Settings and the archive are two different jobs: one is configuration, the other is a list to
    // search and restore from. A tab each keeps the settings reachable without scrolling past a
    // list that can be long, and the archive gets the whole height to be scrolled in.
    var page by rememberSaveable { mutableStateOf(PAGE_SETTINGS) }
    BackHandler(onBack = onClose)

    val hostsCleared = stringResource(R.string.settings_forget_hosts_done)
    val sessionsCleared = stringResource(R.string.settings_clear_last_sessions_done)
    val rememberedHosts by viewModel.rememberedHostCount.collectAsStateWithLifecycle()
    var confirmForget by remember { mutableStateOf(false) }
    var confirmClearSessions by remember { mutableStateOf(false) }

    if (confirmForget) {
        ConfirmDialog(
            title = stringResource(R.string.settings_forget_hosts_confirm_title),
            body = pluralStringResource(R.plurals.settings_forget_hosts_confirm_body, rememberedHosts, rememberedHosts),
            confirmLabel = stringResource(R.string.settings_forget_hosts_confirm_action),
            onDismiss = { confirmForget = false },
            onConfirm = {
                confirmForget = false
                viewModel.forgetHosts { toast.second(hostsCleared) }
            },
        )
    }
    if (confirmClearSessions) {
        ConfirmDialog(
            title = stringResource(R.string.settings_clear_last_sessions_confirm_title),
            body = stringResource(R.string.settings_clear_last_sessions_confirm_body),
            confirmLabel = stringResource(R.string.settings_clear_last_sessions_confirm_action),
            onDismiss = { confirmClearSessions = false },
            onConfirm = {
                confirmClearSessions = false
                viewModel.clearLastSessions { toast.second(sessionsCleared) }
            },
        )
    }

    // Fetched on open rather than kept live: the inventory only changes when the harness is
    // restarted with a different composition, and nothing pushes that over the wire.
    LaunchedEffect(Unit) { store.refreshPlugins() }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DsIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        onClick = onClose,
                        tint = colors.labelPrimary,
                    )
                    Spacer(Modifier.width(DsSpacing.small))
                    Text(
                        stringResource(R.string.settings_title),
                        style = DsType.display24,
                        color = colors.labelPrimary,
                    )
                }
                DsSegmented(
                    segments = listOf(
                        DsSegment(PAGE_SETTINGS, stringResource(R.string.settings_title)),
                        DsSegment(PAGE_ARCHIVED, stringResource(R.string.chatlist_archived)),
                    ),
                    selectedKey = page,
                    onSelect = { page = it },
                    role = Role.Tab,
                )

                if (page == PAGE_ARCHIVED) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        com.labteto.dshmobile.ui.screens.main.ArchivedSessions(store)
                    }
                } else Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
                ) {

                SettingsCard(stringResource(R.string.settings_general)) {
                    LanguageRow(settings) { tag -> viewModel.set { it.copy(localeOverride = tag) } }
                }

                // The customizable theme: scheme, accent, OLED canvas, text size and touch feedback.
                // Its own card and its own heading because it is the one group that restyles
                // everything else on screen as you use it.
                AppearanceCard(settings) { transform -> viewModel.set(transform) }

                SettingsCard(stringResource(R.string.settings_chat)) {
                    ToggleRow(
                        stringResource(R.string.settings_follow_streaming),
                        settings.followStreamingReply,
                        stringResource(R.string.settings_follow_streaming_hint),
                    ) { viewModel.set { it.copy(followStreamingReply = !it.followStreamingReply) } }
                }

                SettingsCard(stringResource(R.string.settings_connection)) {
                    ConnectionSection(connectionState, onDisconnect = { showDisconnectDialog = true })
                    ToggleRow(
                        stringResource(R.string.connect_auto_last),
                        settings.autoConnectLast,
                    ) { viewModel.set { it.copy(autoConnectLast = !it.autoConnectLast) } }
                    ToggleRow(
                        stringResource(R.string.connect_auto_lan),
                        settings.autoConnectLan,
                    ) { viewModel.set { it.copy(autoConnectLan = !it.autoConnectLan) } }
                    ToggleRow(
                        stringResource(R.string.connect_auto_loopback),
                        settings.autoConnectLoopback,
                    ) { viewModel.set { it.copy(autoConnectLoopback = !it.autoConnectLoopback) } }
                }

                // Only on a phone that has Termux: everything on this card goes through it.
                if (viewModel.termuxInstalled) {
                    val harnessControl by viewModel.harnessControl.collectAsStateWithLifecycle()
                    val loopback by viewModel.loopback.collectAsStateWithLifecycle()
                    val loopbackPort by viewModel.loopbackPort.collectAsStateWithLifecycle()
                    val context = LocalContext.current
                    // Which button opened the permission dialog; saveable for the same reason as
                    // on the connect card — it can outlive a rotation.
                    var pendingStop by rememberSaveable { mutableStateOf(false) }
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        if (granted) {
                            if (pendingStop) viewModel.stopHarness() else viewModel.startHarness()
                        }
                    }
                    LaunchedEffect(Unit) { viewModel.refreshLoopback() }
                    TermuxCard(
                        settings = settings,
                        control = harnessControl,
                        loopback = loopback,
                        port = loopbackPort,
                        onStart = {
                            if (viewModel.hasTermuxPermission()) {
                                viewModel.startHarness()
                            } else {
                                pendingStop = false
                                permissionLauncher.launch(TermuxPaths.PERMISSION)
                            }
                        },
                        onStop = {
                            if (viewModel.hasTermuxPermission()) viewModel.stopHarness()
                            else {
                                pendingStop = true
                                permissionLauncher.launch(TermuxPaths.PERMISSION)
                            }
                        },
                        onOpenTermux = {
                            viewModel.openTermuxIntent()?.let { intent -> runCatching { context.startActivity(intent) } }
                        },
                        onAcknowledge = viewModel::acknowledgeHarnessControl,
                        onToggleAutoStart = {
                            viewModel.set { it.copy(autoStartLoopbackHarness = !it.autoStartLoopbackHarness) }
                        },
                    )
                }

                SettingsCard(stringResource(R.string.settings_notifications)) {
                    ToggleRow(
                        stringResource(R.string.settings_notifications_turn),
                        settings.notifyTurnComplete,
                        stringResource(R.string.settings_notifications_turn_hint),
                    ) { viewModel.set { it.copy(notifyTurnComplete = !it.notifyTurnComplete) } }
                    ToggleRow(
                        stringResource(R.string.settings_notifications_goal),
                        settings.notifyGoal,
                        stringResource(R.string.settings_notifications_goal_hint),
                    ) { viewModel.set { it.copy(notifyGoal = !it.notifyGoal) } }
                    ToggleRow(
                        stringResource(R.string.settings_notifications_action),
                        settings.notifyNeedsAction,
                        stringResource(R.string.settings_notifications_action_hint),
                    ) { viewModel.set { it.copy(notifyNeedsAction = !it.notifyNeedsAction) } }
                    ToggleRow(
                        stringResource(R.string.settings_background),
                        settings.keepConnectedInBackground,
                        stringResource(R.string.settings_background_hint),
                    ) { viewModel.set { it.copy(keepConnectedInBackground = !it.keepConnectedInBackground) } }
                }

                SettingsCard(stringResource(R.string.settings_harness)) {
                    connectionState.description?.let { host ->
                        // Only the home directory survives from `host.describe`; the version and
                        // attached-session count are not published by 0.1.2 at all. About already
                        // shows the protocol baseline, so it is not repeated here.
                        LabelledValue(
                            stringResource(R.string.settings_host_info),
                            host.home,
                        )
                    }
                    Text(
                        stringResource(R.string.settings_readonly_banner),
                        style = DsType.caption11,
                        color = colors.warnLabel,
                    )
                }

                // Its own card rather than an addition to the harness one above: that card's
                // read-only banner is scoped to the facts it shows, and plugins are a different
                // subject managed through the pluginManager capability.
                if (pluginManagementAvailable != false) {
                    SettingsCard(stringResource(R.string.settings_plugins)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget)
                            .clickable { pluginsOpen = true }, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.ux_a_manage_plugins), Modifier.weight(1f), style = DsType.std14, color = colors.labelSecondary)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.labelTertiary)
                        }
                        if (plugins == null) Text(stringResource(R.string.common_loading), style = DsType.small13, color = colors.labelTertiary)
                    }
                } else plugins?.let { PluginsCard(it) { pluginsOpen = true } }

                SettingsCard(stringResource(R.string.settings_data)) {
                    // Both ask first. Forgetting the harnesses also drops every relay credential, so
                    // each relay needs a fresh QR pairing, and neither can be taken back.
                    DsButton(
                        text = stringResource(R.string.settings_forget_hosts),
                        onClick = {
                            // Nothing remembered means nothing to lose, so no question to ask.
                            if (rememberedHosts > 0) confirmForget = true
                            else viewModel.forgetHosts { toast.second(hostsCleared) }
                        },
                        variant = DsButtonVariant.Danger,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DsButton(
                        text = stringResource(R.string.settings_clear_last_sessions),
                        onClick = { confirmClearSessions = true },
                        variant = DsButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SettingsCard(stringResource(R.string.settings_about)) {
                    Text(
                        stringResource(
                            R.string.settings_about_version,
                            BuildConfig.VERSION_NAME,
                            DshCore.PROTOCOL_BASELINE,
                        ),
                        style = DsType.small13,
                        color = colors.labelTertiary,
                    )
                    // Beside the version, because that is what it is about — and off-switchable,
                    // since it is the one request this app makes to anything but the harness.
                    ToggleRow(
                        stringResource(R.string.settings_update_check),
                        settings.updateCheckEnabled,
                        stringResource(R.string.settings_update_check_hint),
                    ) { viewModel.set { it.copy(updateCheckEnabled = !it.updateCheckEnabled) } }
                    // The same check, on demand. Every outcome is reported: a button that says
                    // nothing when there is nothing to offer looks broken.
                    var checking by remember { mutableStateOf(false) }
                    var checkResult by remember { mutableStateOf<UpdateChecker.CheckOutcome?>(null) }
                    val scope = rememberCoroutineScope()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        DsButton(
                            text = stringResource(if (checking) R.string.common_loading else R.string.settings_update_check_now),
                            icon = FeatherIcons.RefreshCw,
                            onClick = {
                                checking = true
                                scope.launch {
                                    try { checkResult = viewModel.checkForUpdateNow(BuildConfig.VERSION_NAME) } finally { checking = false }
                                }
                            },
                            enabled = !checking,
                            variant = DsButtonVariant.Outline,
                            size = DsButtonSize.Small,
                        )
                        // A newer version is offered by the app's own update dialog, which the
                        // checker raises; only the two outcomes that dialog cannot show are said here.
                        when (val outcome = checkResult) {
                            is UpdateChecker.CheckOutcome.UpToDate -> Text(
                                stringResource(R.string.settings_update_up_to_date),
                                style = DsType.small13,
                                color = colors.labelTertiary,
                                modifier = Modifier.weight(1f),
                            )
                            UpdateChecker.CheckOutcome.Unreachable -> Text(
                                stringResource(R.string.settings_update_unreachable),
                                style = DsType.small13,
                                color = colors.warnLabel,
                                modifier = Modifier.weight(1f),
                            )
                            is UpdateChecker.CheckOutcome.Available, is UpdateChecker.CheckOutcome.Dismissed, null -> Unit
                        }
                    }
                }

                Spacer(Modifier.height(DsSpacing.xlarge))
                }
            }
            DsToastHost(toast, modifier = Modifier.fillMaxWidth())
        }
    }

    if (pluginsOpen) {
        if (pluginManagementAvailable == false) plugins?.let { PluginsSheet(it, onDismiss = { pluginsOpen = false }) }
        else com.labteto.dshmobile.ui.screens.main.PluginManagerScreen(onClose = { pluginsOpen = false }, onDraftOpened = { pluginsOpen = false; onClose() })
    }

    if (showDisconnectDialog) {
        DsDialog(
            title = stringResource(R.string.settings_connection_disconnect_confirm),
            onDismiss = { showDisconnectDialog = false },
        ) {
            Text(
                stringResource(R.string.settings_connection_disconnect_message),
                style = DsType.std14,
                color = colors.labelSecondary,
                modifier = Modifier.padding(bottom = DsSpacing.medium),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                DsButton(
                    text = stringResource(R.string.settings_connection_disconnect),
                    onClick = {
                        viewModel.disconnect()
                        showDisconnectDialog = false
                        onClose()
                    },
                    variant = DsButtonVariant.Danger,
                )
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { showDisconnectDialog = false },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

/** One settings group as a raised card, so groups read as blocks rather than a running list. */
@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        SectionHeader(title)
        Spacer(Modifier.height(DsSpacing.small))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.block)
                .background(colors.bgLayer1)
                .border(1.dp, colors.borderL2, DsShapes.block)
                .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            content()
        }
    }
}

/**
 * The host's composed plugins, as one row that opens the list.
 *
 * A stock web composition mounts around forty of them. Inlined into a page that already scrolls,
 * that buries everything below it and gives the list no room of its own; the count is the part
 * worth seeing without asking, so the card carries that and the rest lives in [PluginsSheet].
 *
 * Read-only, because that is all the harness offers a client: `pluginInventory/list` has no
 * counterpart that changes anything, and the `settings.*` calls behind the web UI's configurable
 * plugin cards edit a document this app has no editor for. Enabling or disabling one means
 * editing `cordis.patch.yml` on the harness computer.
 */
@Composable
private fun PluginsCard(inventory: PluginInventorySnapshot, onOpen: () -> Unit) {
    val colors = DsTheme.colors
    SettingsCard(stringResource(R.string.settings_plugins)) {
        if (inventory.entries.isEmpty()) {
            Text(
                stringResource(R.string.plugins_empty),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            return@SettingsCard
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.row)
                .clickable(onClick = onOpen)
                .padding(vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_plugins_inventory),
                style = DsType.std14,
                color = colors.labelSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                inventory.entries.size.toString(),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            Spacer(Modifier.width(DsSpacing.xsmall))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The plugin list itself.
 *
 * A sheet rather than an expanding block: forty rows need their own scroll surface, and nesting one
 * inside the settings page's scroll means the list and the page fight over the same drag. The
 * filter sits above the scroll so it stays reachable however far down the list you are.
 *
 * Rows show the shortened module name, since the scope and `dsh-host-`/`dsh-client-` prefixes are
 * the same on nearly every row and push the part that differs off the end of a phone screen.
 */
@Composable
internal fun PluginsSheet(inventory: PluginInventorySnapshot, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    var filter by remember { mutableStateOf("") }
    val matching = remember(inventory, filter) {
        val q = filter.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) {
            inventory.entries
        } else {
            inventory.entries.filter {
                it.moduleName.lowercase(Locale.ROOT).contains(q) ||
                    it.entryId.lowercase(Locale.ROOT).contains(q)
            }
        }
    }
    DsBottomSheet(
        title = stringResource(R.string.settings_plugins),
        subtitle = inventory.entries.size.toString(),
        onDismiss = onDismiss,
    ) {
        DsSearchField(filter, { filter = it }, stringResource(R.string.plugins_search_hint))
        if (matching.isEmpty()) {
            Text(
                stringResource(R.string.plugins_empty),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            return@DsBottomSheet
        }
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            items(matching, key = { it.entryId }) { entry -> PluginRow(entry) }
        }
    }
}

@Composable
private fun PluginRow(entry: PluginInventoryEntry) {
    val colors = DsTheme.colors
    var expanded by remember(entry.entryId) { mutableStateOf(false) }
    DisclosureRow(
        title = moduleShortName(entry.moduleName),
        summary = stringResource(
            if (entry.enabled) R.string.plugins_enabled else R.string.plugins_disabled,
        ),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(start = DsSpacing.xlarge, bottom = DsSpacing.xsmall),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            Text(entry.entryId, style = DsType.caption11, color = colors.labelCaption)
            Text(com.labteto.dshmobile.ui.screens.main.technicalDisplay(entry.moduleName), style = DsType.caption11, color = colors.labelCaption)
            // The mount phase only means anything for a plugin the composition asked for.
            if (entry.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateDot(
                        when (entry.fiberPhase) {
                            PluginFiberPhase.ACTIVE -> StateDotState.Done
                            PluginFiberPhase.LOADING, PluginFiberPhase.UNLOADING -> StateDotState.Running
                            PluginFiberPhase.FAILED -> StateDotState.Error
                            PluginFiberPhase.PENDING, null -> StateDotState.Idle
                        },
                    )
                    Spacer(Modifier.width(DsSpacing.xsmall))
                    Text(
                        stringResource(pluginPhaseLabel(entry.fiberPhase)),
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                    )
                }
            }
        }
    }
}

@StringRes
private fun pluginPhaseLabel(phase: PluginFiberPhase?): Int = when (phase) {
    PluginFiberPhase.PENDING -> R.string.plugins_phase_pending
    PluginFiberPhase.LOADING -> R.string.plugins_phase_loading
    PluginFiberPhase.ACTIVE -> R.string.plugins_phase_active
    PluginFiberPhase.FAILED -> R.string.plugins_phase_failed
    PluginFiberPhase.UNLOADING -> R.string.plugins_phase_unloading
    null -> R.string.plugins_phase_unmounted
}

/**
 * `@deepseek-ai/dsh-client-ui-plan` → `ui-plan`.
 *
 * Ported from the harness's own `moduleShortName`, prefix for prefix, so the two lists name the
 * same plugin the same way.
 */
private fun moduleShortName(moduleName: String): String {
    val prefixes = listOf("cordis:", "cordis-plugin-", "dsh-host-", "dsh-client-", "dsh-")
    var name = moduleName.substringAfterLast('/')
    for (prefix in prefixes) name = name.removePrefix(prefix)
    return name.ifBlank { moduleName }
}

/**
 * Start, stop and auto-start for the harness on this device, from wherever the user is.
 *
 * The connect screen's card is where this lives when the app is not connected; this one exists
 * for the other half of the time — stopping a harness from inside a chat, or turning auto-start
 * on once the first manual start has worked. Same controller, same wording.
 */
@Composable
private fun TermuxCard(
    settings: AppSettings,
    control: HarnessControl,
    loopback: LoopbackStatus,
    port: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenTermux: () -> Unit,
    onAcknowledge: () -> Unit,
    onToggleAutoStart: () -> Unit,
) {
    val colors = DsTheme.colors
    SettingsCard(stringResource(R.string.settings_termux)) {
        LabelledValue(stringResource(R.string.settings_termux_status), loopbackStatusText(loopback, control, port))
        (control as? HarnessControl.Failed)?.let { failed ->
            Text(harnessFailureMessage(failed.reason), style = DsType.caption11, color = colors.warnLabel)
            harnessFailureCommand(failed.reason)?.let { command ->
                Text(command, style = DsType.mdCode, color = colors.warnLabel)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.compact)) {
            val busy = control.busy
            when (loopback) {
                is LoopbackStatus.Down -> DsButton(
                    text = stringResource(R.string.connect_loopback_start),
                    onClick = onStart,
                    enabled = !busy,
                    variant = DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                )
                LoopbackStatus.Unknown -> Unit
                else -> DsButton(
                    text = stringResource(R.string.connect_loopback_stop),
                    onClick = onStop,
                    enabled = !busy,
                    variant = DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                )
            }
            DsButton(
                text = stringResource(R.string.connect_loopback_open_termux),
                onClick = onOpenTermux,
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
            if (control is HarnessControl.Failed) {
                DsButton(
                    text = stringResource(R.string.common_ok),
                    onClick = onAcknowledge,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
        }
        ToggleRow(
            stringResource(R.string.connect_auto_start_loopback),
            settings.autoStartLoopbackHarness,
            stringResource(R.string.connect_auto_start_loopback_hint),
        ) { onToggleAutoStart() }
        Text(
            stringResource(R.string.settings_termux_hint),
            style = DsType.caption11,
            color = colors.labelCaption,
        )
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DsType.std14, color = colors.labelSecondary, modifier = Modifier.weight(1f))
        Text(
            value,
            style = DsType.caption11,
            color = colors.labelTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f),
        )
    }
}

@Composable
private fun ConnectionSection(connectionState: ConnectionUiState, onDisconnect: () -> Unit) {
    val colors = DsTheme.colors
    val isConnected = connectionState.phase == ConnectionPhase.CONNECTED ||
        connectionState.phase == ConnectionPhase.RECONNECTING

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.settings_connection_status),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(
                when (connectionState.phase) {
                    ConnectionPhase.CONNECTED -> StateDotState.Done
                    ConnectionPhase.RECONNECTING, ConnectionPhase.CONNECTING -> StateDotState.Running
                    else -> StateDotState.Idle
                },
            )
            Spacer(Modifier.width(DsSpacing.small))
            Text(
                when (connectionState.phase) {
                    ConnectionPhase.CONNECTED -> stringResource(R.string.common_connected)
                    ConnectionPhase.RECONNECTING -> stringResource(R.string.common_reconnecting)
                    ConnectionPhase.CONNECTING -> stringResource(R.string.common_loading)
                    else -> stringResource(R.string.common_offline)
                },
                style = DsType.small13,
                color = colors.labelTertiary,
            )
        }
    }

    if (isConnected && connectionState.host != null) {
        LabelledValue(
            stringResource(R.string.settings_connection_host),
            connectionState.host.authority,
        )
        DsButton(
            text = stringResource(R.string.settings_connection_disconnect),
            onClick = onDisconnect,
            variant = DsButtonVariant.Outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LanguageRow(settings: AppSettings, onSelect: (String?) -> Unit) {
    val colors = DsTheme.colors
    // A dropdown, not a grid of twelve cells. The grid spent four rows of a settings page on a
    // choice that is made once and then never touched, and at three per row the longer endonyms had
    // to be ellipsised to fit — so the control was both the largest thing on the screen and unable
    // to spell out its own options.
    val labels = LanguageOptions.associateWith { option ->
        option.label ?: option.labelRes?.let { stringResource(it) }.orEmpty()
    }
    val current = LanguageOptions.firstOrNull { it.tag == settings.localeOverride }
        ?: LanguageOptions.first()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_language),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        DsMenu(
            // A select is a control, not a status pill: the 12dp corner every other input and button has.
            anchorShape = DsShapes.control,
            anchor = {
                Row(
                    modifier = Modifier
                        .height(DsSizes.buttonCompact)
                        .clip(DsShapes.control)
                        .background(colors.bgLayer2)
                        .border(1.dp, colors.borderL2, DsShapes.control)
                        .padding(horizontal = DsSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Text(
                        labels[current].orEmpty(),
                        style = DsType.small13,
                        color = colors.labelPrimary,
                        maxLines = 1,
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = colors.labelSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            },
            items = LanguageOptions.map { option ->
                MenuItem(
                    text = labels[option].orEmpty(),
                    icon = Icons.Filled.Check.takeIf { option.tag == settings.localeOverride },
                ) { onSelect(option.tag) }
            },
        )
    }
}

/**
 * The customizable theme card: scheme, accent, OLED canvas, text size and touch feedback.
 *
 * Everything here writes through [SettingsViewModel.set] like the rest of the page, so a change is
 * persisted and re-themes the whole app in the same frame. The controls are previews as well as
 * pickers — the segmented thumb, the accent swatch and the sample text are already rendered in the
 * scheme being chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceCard(
    settings: AppSettings,
    onSet: ((AppSettings) -> AppSettings) -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val preference = ThemePreference.of(settings.themePreference)
    val accent = AccentPalette.of(settings.themeAccent)
    // The colour in force, if the accent is the user's own. The stored colour on its own is not
    // that: it is kept behind a preset too, so the Custom swatch can keep showing it.
    val customInForce = resolveCustomAccent(settings.themeAccent, settings.themeCustomAccent)
    val storedCustom = parseAccentHex(settings.themeCustomAccent)
    val fontSize = FontSizeOption.of(settings.fontSize)
    val dark = isDsDark(
        ThemeCustomization(
            preference = preference,
            accent = accent,
            customAccent = customInForce,
            amoled = settings.amoledBlack,
            fontSize = fontSize,
            haptics = settings.hapticsEnabled,
        ),
    )
    var pickerOpen by remember { mutableStateOf(false) }
    SettingsCard(stringResource(R.string.settings_appearance)) {
        // Scheme.
        DsSegmented(
            segments = listOf(
                DsSegment("light", stringResource(R.string.settings_appearance_light)),
                DsSegment("dark", stringResource(R.string.settings_appearance_dark)),
                DsSegment("system", stringResource(R.string.settings_appearance_system)),
            ),
            selectedKey = preference.name.lowercase(),
            onSelect = { mode ->
                onSet { it.copy(themePreference = mode.lowercase()) }
            },
            stretch = true,
            role = Role.Tab,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.appearance_customize_hint),
            style = DsType.small13,
            color = colors.labelTertiary,
            modifier = Modifier.padding(top = DsSpacing.tiny, bottom = DsSpacing.medium),
        )

        // Accent.
        Text(stringResource(R.string.appearance_accent), style = DsType.label12, color = colors.labelTertiary)
        Spacer(Modifier.height(DsSpacing.small))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            AccentPalette.entries.forEach { palette ->
                AccentSwatch(
                    palette = palette,
                    selected = customInForce == null && palette == accent,
                    dark = dark,
                    onClick = {
                        haptics.selection()
                        onSet { it.copy(themeAccent = palette.key) }
                    },
                )
            }
            CustomAccentSwatch(
                color = storedCustom,
                selected = customInForce != null,
                dark = dark,
                onClick = {
                    haptics.tap()
                    pickerOpen = true
                },
            )
        }
        Spacer(Modifier.height(DsSpacing.small))

        // OLED canvas. Disabled in the light scheme, where it would change nothing and read as a
        // broken switch.
        ToggleRow(
            label = stringResource(R.string.appearance_amoled),
            checked = settings.amoledBlack,
            hint = stringResource(R.string.appearance_amoled_hint),
            enabled = dark,
            onChange = { onSet { it.copy(amoledBlack = !it.amoledBlack) } },
        )

        // Text size, applied as a multiplier on top of the system font scale.
        Text(
            stringResource(R.string.appearance_text_size),
            style = DsType.label12,
            color = colors.labelTertiary,
            modifier = Modifier.padding(top = DsSpacing.small),
        )
        Spacer(Modifier.height(DsSpacing.small))
        DsSegmented(
            segments = listOf(
                DsSegment(FontSizeOption.Compact.key, stringResource(R.string.appearance_text_size_compact)),
                DsSegment(FontSizeOption.Default.key, stringResource(R.string.appearance_text_size_default)),
                DsSegment(FontSizeOption.Large.key, stringResource(R.string.appearance_text_size_large)),
                DsSegment(FontSizeOption.Larger.key, stringResource(R.string.appearance_text_size_larger)),
            ),
            selectedKey = fontSize.key,
            onSelect = { key -> onSet { it.copy(fontSize = key) } },
            stretch = true,
            modifier = Modifier.fillMaxWidth(),
        )

        ToggleRow(
            label = stringResource(R.string.appearance_haptics),
            checked = settings.hapticsEnabled,
            hint = stringResource(R.string.appearance_haptics_hint),
            onChange = {
                haptics.toggle(!settings.hapticsEnabled)
                onSet { it.copy(hapticsEnabled = !it.hapticsEnabled) }
            },
        )

        // Reset to the ink defaults, keeping the light/dark choice: resetting appearance should not
        // also undo the scheme the user is comfortable in.
        Text(
            stringResource(R.string.appearance_reset),
            style = DsType.small13Strong,
            color = colors.accent,
            modifier = Modifier
                .padding(top = DsSpacing.small)
                .clip(DsShapes.pillFull)
                .clickable {
                    haptics.tap()
                    onSet {
                        it.copy(
                            themeAccent = AccentPalette.Ink.key,
                            // The picked colour goes too: "reset" that left a custom colour behind
                            // would reset to Ink and still show a swatch for something not in use.
                            themeCustomAccent = null,
                            amoledBlack = false,
                            fontSize = FontSizeOption.Default.key,
                            hapticsEnabled = true,
                        )
                    }
                }
                .padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
    if (pickerOpen) {
        AccentPickerSheet(
            // Where the reader already is: the stored pick if there is one, else the accent that
            // is painting the app right now.
            initial = storedCustom ?: colors.accent,
            onApply = { picked ->
                onSet { it.copy(themeAccent = CUSTOM_ACCENT_KEY, themeCustomAccent = picked.toAccentHex()) }
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** One accent choice: the colour disc plus its name, checked when active. */
@Composable
private fun AccentSwatch(
    palette: AccentPalette,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val fill = if (dark) palette.dark else palette.light
    val onFill = if (dark) palette.onDark else palette.onLight
    SwatchFrame(
        label = stringResource(accentLabelRes(palette)),
        selected = selected,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize().background(fill), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = onFill,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The swatch that opens the picker: the user's colour once one is set (drawn the way the app will
 * paint it in the active scheme, like the presets beside it), a colour wheel with a plus until then.
 */
@Composable
private fun CustomAccentSwatch(
    color: Color?,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val ramp = remember(color) { color?.let(::deriveAccentRamp) }
    val label = stringResource(R.string.appearance_accent_custom)
    val description = color?.let { stringResource(R.string.appearance_accent_custom_value, it.toAccentHex()) } ?: label
    val wheel = remember { Brush.sweepGradient((0..6).map { Color.hsv(it * 60f, 0.75f, 1f) }) }
    SwatchFrame(
        label = label,
        description = description,
        selected = selected,
        onClick = onClick,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (ramp != null) Modifier.background(ramp.fill(dark)) else Modifier.background(wheel)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                selected && ramp != null -> Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = ramp.on(dark),
                    modifier = Modifier.size(18.dp),
                )
                ramp == null -> Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Shared chrome of an accent swatch: a 36dp disc over its name, one selectable radio target.
 *
 * The whole column is the target — at least 48dp each way, so a swatch is as easy to hit as any
 * other control — and it speaks as one radio button: the name (or [description]), and whether it
 * is the one in force.
 */
@Composable
private fun SwatchFrame(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = label,
    disc: @Composable () -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = modifier
            .heightIn(min = DsSpacing.touchTarget)
            .widthIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.row)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = DsSpacing.tiny),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(DsShapes.pillFull)
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.labelPrimary else colors.borderL2, DsShapes.pillFull),
        ) { disc() }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = DsType.caption11,
            color = if (selected) colors.labelPrimary else colors.labelTertiary,
            maxLines = 1,
        )
    }
}

private fun accentLabelRes(palette: AccentPalette): Int = when (palette) {
    AccentPalette.Ink -> R.string.appearance_accent_ink
    AccentPalette.Violet -> R.string.appearance_accent_violet
    AccentPalette.Teal -> R.string.appearance_accent_teal
    AccentPalette.Green -> R.string.appearance_accent_green
    AccentPalette.Amber -> R.string.appearance_accent_amber
    AccentPalette.Rose -> R.string.appearance_accent_rose
    AccentPalette.Mono -> R.string.appearance_accent_mono
}

private const val PAGE_SETTINGS = "settings"
private const val PAGE_ARCHIVED = "archived"
