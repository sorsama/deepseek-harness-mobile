package com.labteto.dshmobile.ui.screens.runtime

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.runtime.BuiltInHarnessHost
import com.labteto.dshmobile.runtime.BuiltInHarnessState
import com.labteto.dshmobile.runtime.RuntimeAction
import com.labteto.dshmobile.runtime.RuntimeInstaller
import com.labteto.dshmobile.runtime.RuntimeState
import com.labteto.dshmobile.runtime.RuntimeStorageUsage
import com.labteto.dshmobile.runtime.formatBytes
import com.labteto.dshmobile.runtime.runtimeActionLabel
import com.labteto.dshmobile.runtime.runtimeIssue
import com.labteto.dshmobile.runtime.setupProgressDetail
import com.labteto.dshmobile.runtime.updateProgress
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsFullScreenDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.localizedCount
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.screens.connect.ConnectButtonVariant
import com.labteto.dshmobile.ui.screens.connect.DsConnectButton
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything about the built-in harness in one page: its state, start/stop/restart, the three
 * switches, updates, storage, the log and what keeps it alive in the background.
 *
 * Opened from Settings ("Harness on this phone") and from the Connect card. The auto-start switch is
 * an app setting (`AppSettings.autoStartBuiltInHarness`) and comes in from whichever screen opened
 * the page, so both write the same value.
 *
 * @param scrollToLog open at the log, for a "Show log" after a failed start.
 */
@Composable
internal fun RuntimeControlPage(
    host: BuiltInHarnessHost,
    port: Int,
    autoStart: Boolean,
    onAutoStartChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onSetUp: () -> Unit,
    onAction: (RuntimeAction) -> Unit,
    onClose: () -> Unit,
    /** Remove the runtime; [keepUserData] keeps the harness's sessions and settings. */
    onUninstall: (keepUserData: Boolean) -> Unit,
    scrollToLog: Boolean = false,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val toast = rememberDsToast()
    val scope = rememberCoroutineScope()
    val state by host.state.collectAsStateWithLifecycle()
    val holdWakeLock by host.holdWakeLock.collectAsStateWithLifecycle()
    val modulePack by host.modulePack.collectAsStateWithLifecycle()
    val page = rememberPageVisibility(onClose)
    val runtime = state.runtime

    var storage by remember { mutableStateOf<RuntimeStorageUsage?>(null) }
    var log by remember { mutableStateOf("") }
    var checkResult by remember { mutableStateOf<RuntimeInstaller.CheckResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    // Storage and the log follow the state: re-read whenever it changes, and the log every few
    // seconds while the harness runs, so a start can be watched as it happens.
    LaunchedEffect(runtime::class, state.installed) {
        storage = runCatching { host.storageUsage() }.getOrNull()
    }
    LaunchedEffect(runtime::class) {
        while (true) {
            log = host.logTail(LOG_LINES)
            if (runtime !is RuntimeState.Running && runtime !is RuntimeState.Starting) break
            delay(3_000)
        }
    }

    val scroll = rememberScrollState()
    var logTop by remember { mutableStateOf(0) }
    LaunchedEffect(scrollToLog, logTop) {
        if (scrollToLog && logTop > 0) scroll.animateScrollTo(logTop)
    }

    DsFullScreenDialog(
        onDismissRequest = page::hide,
        visibleState = page.state,
        onWindowReady = page::show,
    ) {
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DsIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        onClick = page::hide,
                        tint = colors.labelPrimary,
                    )
                    Spacer(Modifier.width(DsSpacing.small))
                    Text(
                        stringResource(R.string.runtime_card_title),
                        style = DsType.large20,
                        color = colors.labelPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(horizontal = DsSpacing.comfortable),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(bottom = DsSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
                    ) {
                        StatusSection(state, port, onStart, onStop, onRestart, onSetUp, onAction)

                        if (state.installed != null) {
                            Section(stringResource(R.string.runtime_section_options)) {
                                ToggleRow(
                                    stringResource(R.string.runtime_switch_auto_start),
                                    autoStart,
                                    stringResource(R.string.runtime_switch_auto_start_hint),
                                ) { onAutoStartChange(!autoStart) }
                                ToggleRow(
                                    stringResource(R.string.runtime_switch_wake_lock),
                                    holdWakeLock,
                                    stringResource(R.string.runtime_switch_wake_lock_hint),
                                ) { host.setHoldWakeLock(!holdWakeLock) }
                                ToggleRow(
                                    stringResource(R.string.runtime_switch_fast_start),
                                    modulePack,
                                    stringResource(R.string.runtime_switch_fast_start_hint),
                                ) { host.setModulePack(!modulePack) }
                            }

                            UpdateSection(
                                state = state,
                                checking = checking,
                                checkResult = checkResult,
                                onCheck = {
                                    checking = true
                                    scope.launch {
                                        checkResult = runCatching { host.checkRelease() }.getOrNull()
                                        checking = false
                                    }
                                },
                                onUpdate = {
                                    haptics.confirm()
                                    host.update()
                                },
                                onCancel = host::cancelUpdate,
                            )

                            Section(stringResource(R.string.runtime_section_storage)) {
                                val usage = storage
                                ValueRow(stringResource(R.string.runtime_storage_runtime), usage?.let { formatBytes(it.layersBytes) } ?: "…")
                                ValueRow(stringResource(R.string.runtime_storage_data), usage?.let { formatBytes(it.userDataBytes) } ?: "…")
                                ValueRow(stringResource(R.string.runtime_storage_other), usage?.let { formatBytes(it.cacheBytes + it.logBytes) } ?: "…")
                                Spacer(Modifier.height(DsSpacing.xsmall))
                                RowOfButtons {
                                    DsConnectButton(
                                        text = stringResource(R.string.runtime_remove),
                                        onClick = { confirmRemove = true },
                                        variant = ConnectButtonVariant.Outline,
                                        enabled = !runtime.busy,
                                    )
                                    DsConnectButton(
                                        text = stringResource(R.string.runtime_delete_all),
                                        onClick = { confirmDeleteAll = true },
                                        variant = ConnectButtonVariant.Danger,
                                        enabled = !runtime.busy,
                                    )
                                }
                                Spacer(Modifier.height(DsSpacing.xsmall))
                            }
                        }

                        Column(Modifier.onTop { logTop = it }) {
                            Section(stringResource(R.string.runtime_section_log)) {
                                LogBlock(log)
                                RowOfButtons {
                                    val copied = stringResource(R.string.runtime_log_copied)
                                    val shareTitle = stringResource(R.string.runtime_section_log)
                                    DsConnectButton(
                                        text = stringResource(R.string.common_copy),
                                        onClick = {
                                            clipboard.setText(AnnotatedString(log))
                                            haptics.confirm()
                                            toast.second(copied)
                                        },
                                        variant = ConnectButtonVariant.Outline,
                                        enabled = log.isNotBlank(),
                                    )
                                    DsConnectButton(
                                        text = stringResource(R.string.runtime_log_share),
                                        onClick = { RuntimeSystem.share(context, log, shareTitle) },
                                        variant = ConnectButtonVariant.Outline,
                                        enabled = log.isNotBlank(),
                                    )
                                }
                                Spacer(Modifier.height(DsSpacing.xsmall))
                            }
                        }

                        ReliabilitySection()
                    }
                }
            }
            DsToastHost(toast, modifier = Modifier.fillMaxWidth())
        }
    }

    if (confirmRemove) {
        DsDialog(title = stringResource(R.string.runtime_remove_title), onDismiss = { confirmRemove = false }) {
            Text(
                stringResource(R.string.runtime_remove_body, storage?.let { formatBytes(it.layersBytes + it.cacheBytes) } ?: "…"),
                style = DsType.std14,
                color = colors.labelSecondary,
            )
            DialogButtons(
                dismiss = stringResource(R.string.common_cancel),
                confirm = stringResource(R.string.runtime_remove),
                onDismiss = { confirmRemove = false },
                onConfirm = {
                    confirmRemove = false
                    haptics.confirm()
                    onUninstall(true)
                },
            )
        }
    }
    if (confirmDeleteAll) {
        DsDialog(title = stringResource(R.string.runtime_delete_all_title), onDismiss = { confirmDeleteAll = false }) {
            Text(stringResource(R.string.runtime_delete_all_body), style = DsType.std14, color = colors.labelSecondary)
            DialogButtons(
                dismiss = stringResource(R.string.common_cancel),
                confirm = stringResource(R.string.runtime_delete_all),
                danger = true,
                onDismiss = { confirmDeleteAll = false },
                onConfirm = {
                    confirmDeleteAll = false
                    haptics.reject()
                    onUninstall(false)
                },
            )
        }
    }
}

/** Reports where this element starts inside the scrolling column, for scrolling to it. */
private fun Modifier.onTop(report: (Int) -> Unit): Modifier =
    this.onGloballyPositioned { report(it.positionInParent().y.toInt()) }

@Composable
private fun StatusSection(
    state: BuiltInHarnessState,
    port: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onSetUp: () -> Unit,
    onAction: (RuntimeAction) -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val runtime = state.runtime
    val installed = state.installed
    Section(stringResource(R.string.runtime_section_status)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateDot(
                when (runtime) {
                    is RuntimeState.Running -> StateDotState.Done
                    is RuntimeState.Failed -> StateDotState.Error
                    is RuntimeState.Starting, is RuntimeState.Stopping, is RuntimeState.Updating,
                    is RuntimeState.Downloading, is RuntimeState.Verifying, is RuntimeState.Unpacking,
                    -> StateDotState.Running
                    else -> StateDotState.Idle
                },
                size = 10.dp,
            )
            Spacer(Modifier.width(DsSpacing.compact))
            AnimatedContent(
                targetState = statusLine(state, port),
                transitionSpec = { fadeIn(DsAnimations.fade) togetherWith fadeOut(DsAnimations.fade) using SizeTransform(clip = false) },
                label = "runtimeStatus",
                modifier = Modifier.weight(1f),
            ) { line ->
                Text(line, style = DsType.std14Strong, color = colors.labelPrimary)
            }
        }
        if (runtime is RuntimeState.Running) {
            ValueRow(stringResource(R.string.runtime_info_uptime), uptimeText(runtime.sinceEpochMs))
        }
        if (installed != null) {
            ValueRow(stringResource(R.string.runtime_info_harness), installed.harnessVersion, mono = true)
            ValueRow(stringResource(R.string.runtime_info_release), installed.releaseId, mono = true)
            ValueRow(stringResource(R.string.runtime_info_port), port.toString(), mono = true)
        }
        (runtime as? RuntimeState.Running)?.rolledBack?.let { rolledBack ->
            Text(
                stringResource(R.string.runtime_update_rolled_back, rolledBack.failedVersion),
                style = DsType.small13,
                color = colors.warnLabel,
                modifier = Modifier.padding(vertical = DsSpacing.xsmall),
            )
        }
        (runtime as? RuntimeState.Failed)?.let { failed ->
            RuntimeIssueBlock(
                issue = runtimeIssue(failed.reason, installed = failed.installed != null, port = port),
                onAction = onAction,
                showActions = false,
                showLog = false,
                modifier = Modifier.padding(vertical = DsSpacing.xsmall),
            )
        }
        Spacer(Modifier.height(DsSpacing.xsmall))
        RowOfButtons {
            fun tap(action: () -> Unit): () -> Unit = {
                haptics.tap()
                action()
            }
            when {
                installed == null && !runtime.busy -> DsConnectButton(runtimeActionLabel(RuntimeAction.SetUp), tap(onSetUp))
                installed == null -> DsConnectButton(runtimeActionLabel(RuntimeAction.ShowProgress), tap(onSetUp))
                runtime is RuntimeState.Running -> {
                    DsConnectButton(runtimeActionLabel(RuntimeAction.Stop), tap(onStop), variant = ConnectButtonVariant.Outline)
                    DsConnectButton(runtimeActionLabel(RuntimeAction.Restart), tap(onRestart), variant = ConnectButtonVariant.Outline)
                }
                runtime is RuntimeState.Starting -> DsConnectButton(runtimeActionLabel(RuntimeAction.Stop), tap(onStop), variant = ConnectButtonVariant.Outline)
                runtime is RuntimeState.Stopped || runtime is RuntimeState.Failed -> DsConnectButton(runtimeActionLabel(RuntimeAction.Start), tap(onStart))
                runtime is RuntimeState.Updating -> Unit
                else -> DsConnectButton(stringResource(R.string.runtime_card_stopping), {}, enabled = false, variant = ConnectButtonVariant.Outline)
            }
        }
        Spacer(Modifier.height(DsSpacing.xsmall))
    }
}

@Composable
private fun statusLine(state: BuiltInHarnessState, port: Int): String = when (val r = state.runtime) {
    is RuntimeState.NotSupported, RuntimeState.NotInstalled -> stringResource(R.string.runtime_status_not_set_up)
    is RuntimeState.Downloading, is RuntimeState.Verifying, is RuntimeState.Unpacking -> stringResource(R.string.runtime_card_setting_up)
    is RuntimeState.Stopped -> stringResource(R.string.runtime_status_stopped)
    is RuntimeState.Starting -> stringResource(R.string.runtime_card_starting)
    is RuntimeState.Running -> stringResource(R.string.runtime_card_running, port.toString())
    is RuntimeState.Stopping -> stringResource(R.string.runtime_card_stopping)
    is RuntimeState.Updating -> stringResource(R.string.runtime_card_updating)
    is RuntimeState.Failed -> stringResource(if (r.installed != null) R.string.runtime_status_stopped else R.string.runtime_status_not_set_up)
}

/** "Running for", refreshed every half minute: a clock ticking every second would be noise. */
@Composable
private fun uptimeText(sinceEpochMs: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sinceEpochMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val minutes = ((now - sinceEpochMs).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.runtime_uptime_just_now)
        minutes < 60 -> stringResource(R.string.runtime_uptime_minutes, localizedCount(minutes))
        else -> stringResource(R.string.runtime_uptime_hours, localizedCount(minutes / 60), localizedCount(minutes % 60))
    }
}

@Composable
private fun UpdateSection(
    state: BuiltInHarnessState,
    checking: Boolean,
    checkResult: RuntimeInstaller.CheckResult?,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = DsTheme.colors
    val runtime = state.runtime
    val update = state.update
    Section(stringResource(R.string.runtime_section_update)) {
        Column(Modifier.fillMaxWidth().padding(vertical = DsSpacing.small).animateContentSize(DsAnimations.expand), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            when {
                runtime is RuntimeState.Updating -> {
                    val progress = updateProgress(runtime.phase, update, null)
                    Text(stringResource(R.string.runtime_card_updating), style = DsType.std14Strong, color = colors.labelPrimary)
                    RuntimeProgressBar(progress.fraction)
                    Text(setupProgressDetail(progress), style = DsType.small13, color = colors.labelSecondary)
                    DsConnectButton(stringResource(R.string.common_cancel), onCancel, variant = ConnectButtonVariant.Outline)
                }
                update != null -> {
                    Text(stringResource(R.string.runtime_update_available, update.harnessVersion), style = DsType.std14Strong, color = colors.labelPrimary)
                    Text(
                        stringResource(
                            if (update.baseChanges) R.string.runtime_update_what_both else R.string.runtime_update_what_harness,
                            formatBytes(update.downloadBytes),
                        ),
                        style = DsType.small13,
                        color = colors.labelSecondary,
                    )
                    Text(
                        stringResource(R.string.runtime_update_versions, state.installed?.harnessVersion.orEmpty(), update.harnessVersion),
                        style = DsType.mono12,
                        color = colors.labelTertiary,
                    )
                    DsConnectButton(stringResource(R.string.runtime_update_button), onUpdate, enabled = !runtime.busy)
                }
                else -> {
                    val line = when {
                        checking -> stringResource(R.string.runtime_update_checking)
                        checkResult is RuntimeInstaller.CheckResult.UpToDate -> stringResource(R.string.runtime_update_none)
                        checkResult is RuntimeInstaller.CheckResult.Refused -> runtimeIssueTextOf(checkResult)
                        else -> null
                    }
                    if (line != null) Text(line, style = DsType.small13, color = colors.labelSecondary)
                    DsConnectButton(stringResource(R.string.runtime_update_check), onCheck, variant = ConnectButtonVariant.Outline, enabled = !checking && !runtime.busy)
                }
            }
        }
    }
}

@Composable
private fun runtimeIssueTextOf(refused: RuntimeInstaller.CheckResult.Refused): String =
    com.labteto.dshmobile.runtime.runtimeIssueText(runtimeIssue(refused.failure, installed = false, port = 0))

/** The end of the harness log: monospace, selectable, scrolls on its own both ways. */
@Composable
private fun LogBlock(log: String) {
    val colors = DsTheme.colors
    val vertical = rememberScrollState()
    // The newest lines are the ones worth reading: stay at the end as the log grows.
    LaunchedEffect(log, vertical.maxValue) { vertical.scrollTo(vertical.maxValue) }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = DsSpacing.small)
            .heightIn(max = 280.dp)
            .clip(DsShapes.code)
            .background(colors.codeBlockBg)
            .border(1.dp, colors.borderL1, DsShapes.code)
            .verticalScroll(vertical)
            .horizontalScroll(rememberScrollState())
            .padding(DsSpacing.compact),
    ) {
        SelectionContainer {
            Text(
                log.ifBlank { stringResource(R.string.runtime_log_empty) },
                style = DsType.mono12,
                color = if (log.isBlank()) colors.labelTertiary else colors.labelSecondary,
                softWrap = false,
            )
        }
    }
}

/** What keeps the harness alive in the background, each row opening the screen that changes it. */
@Composable
private fun ReliabilitySection() {
    val colors = DsTheme.colors
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(RuntimeSystem.notificationsEnabled(context)) }
    var battery by remember { mutableStateOf(RuntimeSystem.batteryUnrestricted(context)) }
    var childOff by remember { mutableStateOf(RuntimeSystem.childRestrictionsOff(context)) }
    // Each row opens a system screen; coming back is when the answer may have changed.
    LifecycleResumeEffect(Unit) {
        notifications = RuntimeSystem.notificationsEnabled(context)
        battery = RuntimeSystem.batteryUnrestricted(context)
        childOff = RuntimeSystem.childRestrictionsOff(context)
        onPauseOrDispose { }
    }
    Section(stringResource(R.string.runtime_section_reliability)) {
        CheckRow(
            title = stringResource(R.string.runtime_check_notifications),
            detail = stringResource(if (notifications) R.string.runtime_check_notifications_on else R.string.runtime_check_notifications_off),
            ok = notifications,
            onOpen = { RuntimeSystem.openNotificationSettings(context) },
        )
        CheckRow(
            title = stringResource(R.string.runtime_check_battery),
            detail = stringResource(if (battery) R.string.runtime_check_battery_on else R.string.runtime_check_battery_off),
            ok = battery,
            onOpen = { RuntimeSystem.openBatterySettings(context) },
        )
        if (RuntimeSystem.hasChildProcessLimit) {
            val switch = RuntimeSystem.hasChildProcessSwitch
            CheckRow(
                title = stringResource(R.string.runtime_check_child),
                detail = when {
                    !switch -> stringResource(R.string.runtime_check_child_body_12)
                    childOff == true -> stringResource(R.string.runtime_check_child_off)
                    else -> stringResource(R.string.runtime_check_child_body_14)
                },
                ok = if (switch) childOff else null,
                onOpen = if (switch) ({ RuntimeSystem.openDeveloperOptions(context) }) else null,
            )
        }
        Spacer(Modifier.height(DsSpacing.xsmall))
        Text(stringResource(R.string.runtime_check_footer), style = DsType.caption11, color = colors.labelCaption)
        Spacer(Modifier.height(DsSpacing.small))
    }
}

/**
 * One checklist row: a mark for its state ([ok] null is "can't tell"), the title and what it means,
 * and a chevron when tapping opens the system screen that changes it.
 */
@Composable
private fun CheckRow(title: String, detail: String, ok: Boolean?, onOpen: (() -> Unit)?) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val clickable = if (onOpen != null) {
        Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.runtime_check_open)) {
            haptics.tap()
            onOpen()
        }
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .then(clickable)
            .heightIn(min = 56.dp)
            .padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        StateDot(
            when (ok) {
                true -> StateDotState.Done
                false -> StateDotState.Warning
                null -> StateDotState.Idle
            },
            size = 8.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = DsType.std14, color = colors.labelPrimary)
            Text(detail, style = DsType.small13, color = if (ok == false) colors.warnLabel else colors.labelTertiary)
        }
        if (onOpen != null) {
            Icon(DsIcons.ChevronRight, contentDescription = null, tint = colors.labelTertiary, modifier = Modifier.size(18.dp))
        }
    }
}

/** A section the way Settings draws one: a small heading over a raised block. */
@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title)
        Spacer(Modifier.height(DsSpacing.small))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.block)
                .background(colors.bgLayer1)
                .border(1.dp, colors.borderL2, DsShapes.block)
                .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.xsmall),
            content = content,
        )
    }
}

@Composable
private fun ValueRow(label: String, value: String, mono: Boolean = false) {
    val colors = DsTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(vertical = DsSpacing.xsmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        Text(label, style = DsType.std14, color = colors.labelSecondary, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (mono) DsType.mono12 else DsType.std14,
            color = colors.labelPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RowOfButtons(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) { content() }
}

private const val LOG_LINES = 200
