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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.screens.connect.LoopbackStatus
import com.labteto.dshmobile.ui.screens.connect.harnessFailureCommand
import com.labteto.dshmobile.ui.screens.connect.harnessFailureMessage
import com.labteto.dshmobile.ui.screens.connect.loopbackStatusText
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
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
    val colors = DsTheme.colors
    val toast = rememberDsToast()
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var pluginsOpen by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    val hostsCleared = stringResource(R.string.settings_forget_hosts_done)
    val sessionsCleared = stringResource(R.string.settings_clear_last_sessions_done)

    // Fetched on open rather than kept live: the inventory only changes when the harness is
    // restarted with a different composition, and nothing pushes that over the wire.
    LaunchedEffect(Unit) { store.refreshPlugins() }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DsIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        onClick = onClose,
                    )
                    Text(
                        stringResource(R.string.settings_title),
                        style = DsType.large20,
                        color = colors.labelPrimary,
                    )
                }

                SettingsCard(stringResource(R.string.settings_general)) {
                    LanguageRow(settings) { tag -> viewModel.set { it.copy(localeOverride = tag) } }
                    AppearanceRow(settings) { mode -> viewModel.set { it.copy(themePreference = mode) } }
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
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted -> if (granted) viewModel.startHarness() }
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
                                permissionLauncher.launch(TermuxPaths.PERMISSION)
                            }
                        },
                        onStop = viewModel::stopHarness,
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
                // subject that happens to also be read-only.
                plugins?.let { PluginsCard(it) { pluginsOpen = true } }

                SettingsCard(stringResource(R.string.settings_data)) {
                    DsButton(
                        text = stringResource(R.string.settings_forget_hosts),
                        onClick = { viewModel.forgetHosts { toast.second(hostsCleared) } },
                        variant = DsButtonVariant.Outline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DsButton(
                        text = stringResource(R.string.settings_clear_last_sessions),
                        onClick = { viewModel.clearLastSessions { toast.second(sessionsCleared) } },
                        variant = DsButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SettingsCard(stringResource(R.string.settings_about)) {
                    // Beside the version, because that is what it is about — and off-switchable,
                    // since it is the one request this app makes to anything but the harness.
                    ToggleRow(
                        stringResource(R.string.settings_update_check),
                        settings.updateCheckEnabled,
                        stringResource(R.string.settings_update_check_hint),
                    ) { viewModel.set { it.copy(updateCheckEnabled = !it.updateCheckEnabled) } }
                    Text(
                        stringResource(
                            R.string.settings_about_version,
                            BuildConfig.VERSION_NAME,
                            DshCore.PROTOCOL_BASELINE,
                        ),
                        style = DsType.small13,
                        color = colors.labelTertiary,
                    )
                }

                Spacer(Modifier.height(DsSpacing.xlarge))
            }
            DsToastHost(toast, modifier = Modifier.fillMaxWidth())
        }
    }

    plugins?.takeIf { pluginsOpen }?.let {
        PluginsSheet(inventory = it, onDismiss = { pluginsOpen = false })
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.block)
                .background(colors.bgLayer1)
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
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
private fun PluginsSheet(inventory: PluginInventorySnapshot, onDismiss: () -> Unit) {
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
        TextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.plugins_search_hint), style = DsType.std14) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.bgLayer2,
                unfocusedContainerColor = colors.bgLayer2,
                focusedIndicatorColor = colors.accent,
                unfocusedIndicatorColor = colors.borderL2,
                cursorColor = colors.accent,
            ),
        )
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
            Text(entry.moduleName, style = DsType.caption11, color = colors.labelCaption)
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
            anchor = {
                Row(
                    modifier = Modifier
                        .clip(DsShapes.pillFull)
                        .background(colors.hoverSolid)
                        .border(1.dp, colors.borderL2, DsShapes.pillFull)
                        .padding(horizontal = DsSpacing.compact, vertical = DsSpacing.tiny),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
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

@Composable
private fun AppearanceRow(settings: AppSettings, onSelect: (String) -> Unit) {
    val colors = DsTheme.colors
    Column(modifier = Modifier.padding(vertical = DsSpacing.small)) {
        Text(
            stringResource(R.string.settings_appearance),
            style = DsType.std14,
            color = colors.labelSecondary,
        )
        Spacer(Modifier.height(DsSpacing.small))
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            AppearanceChip(
                stringResource(R.string.settings_appearance_light),
                settings.themePreference == "light",
            ) { onSelect("light") }
            AppearanceChip(
                stringResource(R.string.settings_appearance_dark),
                settings.themePreference == "dark",
            ) { onSelect("dark") }
            AppearanceChip(
                stringResource(R.string.settings_appearance_system),
                settings.themePreference == "system",
            ) { onSelect("system") }
        }
    }
}

@Composable
private fun AppearanceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Box(
        modifier = Modifier
            .clip(DsShapes.cube)
            .background(if (selected) colors.accentTertiary else colors.bgModulePlatform)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = DsSpacing.small),
    ) {
        Text(
            label,
            style = DsType.small13,
            color = if (selected) colors.accent else colors.labelSecondary,
        )
    }
}
