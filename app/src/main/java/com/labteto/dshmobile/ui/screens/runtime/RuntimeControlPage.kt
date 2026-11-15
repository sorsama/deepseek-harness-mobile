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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.labteto.dshmobile.runtime.RuntimeFailure
import com.labteto.dshmobile.runtime.RuntimeInstaller
import com.labteto.dshmobile.runtime.RuntimeState
import com.labteto.dshmobile.runtime.RuntimeStorageUsage
import com.labteto.dshmobile.runtime.UpdateOutcome
import com.labteto.dshmobile.runtime.UpdatePhase
import com.labteto.dshmobile.runtime.formatBytes
import com.labteto.dshmobile.runtime.runtimeIssueText
import com.labteto.dshmobile.ui.screens.main.technicalDisplay
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
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
    val notifyUpdates by host.notifyUpdates.collectAsStateWithLifecycle()
    val autoInstallUpdates by host.autoInstallUpdates.collectAsStateWithLifecycle()
    // Outside the page's own window: the activity's view models, for "Check now" and "Update the app".
    val controls: BuiltInControlsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val page = rememberPageVisibility(onClose)
    val runtime = state.runtime

    var storage by remember { mutableStateOf<RuntimeStorageUsage?>(null) }
    var log by remember { mutableStateOf("") }
    var checkResult by remember { mutableStateOf<RuntimeInstaller.CheckResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmGoBack by remember { mutableStateOf(false) }
    // A hand-started update on a metered network asks first, as a set-up does.
    var askMeteredUpdate by remember { mutableStateOf(false) }
    var projectsOpen by rememberSaveable { mutableStateOf(false) }
    val projectsVm: ProjectsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
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
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.runtime_card_title),
                            style = DsType.large20,
                            color = colors.labelPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                        )
                        Spacer(Modifier.width(DsSpacing.small))
                        BetaBadge()
                    }
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

                            fun checkNow() {
                                haptics.tap()
                                checking = true
                                scope.launch {
                                    checkResult = runCatching { controls.checkNow() }.getOrNull()
                                    checking = false
                                    when (checkResult) {
                                        is RuntimeInstaller.CheckResult.Refused -> haptics.reject()
                                        else -> haptics.confirm()
                                    }
                                }
                            }
                            fun updateNow() {
                                // Nothing to ask about when the update is still on the phone (0 bytes).
                                if (host.isMetered() && (state.update?.downloadBytes ?: 1L) > 0L) {
                                    haptics.tap()
                                    askMeteredUpdate = true
                                    return
                                }
                                haptics.confirm()
                                host.dismissOutcome()
                                host.update()
                            }

                            // What the agent's commands do. No troubleshooting switch for the
                            // runtime's DSH_RUNTIME_MOBILE_PATCH=0: it gives back upstream's presets,
                            // under which no command can run on a phone, and a release that does not
                            // start already goes back to the one before by itself.
                            Section(stringResource(R.string.runtime_section_commands)) {
                                if (state.installed?.commandPermissions == true) {
                                    CommandChoiceBlock(
                                        host = host,
                                        state = state,
                                        place = CommandChoicePlace.Control,
                                        onRestart = onRestart,
                                        modifier = Modifier.padding(vertical = DsSpacing.small),
                                        onUpdate = ::updateNow,
                                        onCheck = ::checkNow,
                                        checking = checking,
                                    )
                                } else {
                                    CommandChoiceUnsupported(state, onUpdate = ::updateNow)
                                }
                            }

                            Section(stringResource(R.string.projects_title)) {
                                val projectList by projectsVm.operations.projects.collectAsStateWithLifecycle()
                                LaunchedEffect(Unit) { projectsVm.operations.refresh() }
                                val total = projectsVm.operations.totalBytes()
                                ValueRow(
                                    stringResource(R.string.projects_section_list),
                                    androidx.compose.ui.res.pluralStringResource(R.plurals.projects_count, projectList.size, projectList.size),
                                )
                                ValueRow(stringResource(R.string.projects_space_label), total?.let { isolated(com.labteto.dshmobile.ui.screens.main.preview.formatBytes(it)) } ?: "…")
                                Text(
                                    stringResource(R.string.projects_files_hint, isolated(stringResource(R.string.app_name))),
                                    style = DsType.small13,
                                    color = colors.labelSecondary,
                                    modifier = Modifier.padding(vertical = DsSpacing.xsmall),
                                )
                                RowOfButtons {
                                    DsConnectButton(
                                        text = stringResource(R.string.projects_manage),
                                        onClick = {
                                            haptics.tap()
                                            projectsOpen = true
                                        },
                                        variant = ConnectButtonVariant.Outline,
                                    )
                                    val noFiles = stringResource(R.string.projects_no_files_app)
                                    DsConnectButton(
                                        text = stringResource(R.string.projects_open_files),
                                        onClick = {
                                            haptics.tap()
                                            if (!openInFiles(context)) toast.second(noFiles)
                                        },
                                        variant = ConnectButtonVariant.Outline,
                                    )
                                }
                                Spacer(Modifier.height(DsSpacing.xsmall))
                            }

                            UpdateSection(
                                state = state,
                                checking = checking,
                                checkResult = checkResult,
                                notify = notifyUpdates,
                                autoInstall = autoInstallUpdates,
                                onCheck = ::checkNow,
                                onUpdate = ::updateNow,
                                onCancel = host::cancelUpdate,
                                onGoBack = { confirmGoBack = true },
                                onUpdateApp = { controls.updateApp(context) },
                                onDismissOutcome = {
                                    haptics.tap()
                                    host.dismissOutcome()
                                },
                                onNotifyChange = host::setNotifyUpdates,
                                onAutoInstallChange = host::setAutoInstallUpdates,
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

    if (projectsOpen) {
        ProjectsPage(projectsVm.operations, onClose = { projectsOpen = false })
    }

    if (confirmRemove) {
        DsDialog(
            title = stringResource(R.string.runtime_remove_title), onDismiss = { confirmRemove = false },
            body = {
                Text(
                    stringResource(R.string.runtime_remove_body, storage?.let { formatBytes(it.layersBytes + it.cacheBytes) } ?: "…"),
                    style = DsType.std14,
                    color = colors.labelSecondary,
                )
            },
            actions = {
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
            },
        )
    }
    if (askMeteredUpdate) {
        DsDialog(
            title = stringResource(R.string.runtime_setup_metered_title),
            onDismiss = { askMeteredUpdate = false },
            body = {
                Text(
                    stringResource(R.string.runtime_update_metered_body, state.update?.let { formatBytes(it.downloadBytes) } ?: "…"),
                    style = DsType.std14,
                    color = colors.labelSecondary,
                )
            },
            actions = {
                DialogButtons(
                    dismiss = stringResource(R.string.common_cancel),
                    confirm = stringResource(R.string.runtime_setup_metered_confirm),
                    onDismiss = { askMeteredUpdate = false },
                    onConfirm = {
                        askMeteredUpdate = false
                        haptics.confirm()
                        host.dismissOutcome()
                        host.update()
                    },
                )
            },
        )
    }
    val goBackTo = state.installed?.previousRelease
    if (confirmGoBack && goBackTo != null) {
        DsDialog(
            title = stringResource(R.string.runtime_go_back_title, technicalDisplay(goBackTo.releaseId)),
            onDismiss = { confirmGoBack = false },
            body = {
                Text(
                    stringResource(
                        R.string.runtime_go_back_body,
                        technicalDisplay(goBackTo.harnessVersion),
                        technicalDisplay(state.installed?.releaseId.orEmpty()),
                    ),
                    style = DsType.std14,
                    color = colors.labelSecondary,
                )
            },
            actions = {
                DialogButtons(
                    dismiss = stringResource(R.string.common_cancel),
                    confirm = stringResource(R.string.runtime_update_go_back),
                    onDismiss = { confirmGoBack = false },
                    onConfirm = {
                        confirmGoBack = false
                        haptics.confirm()
                        host.dismissOutcome()
                        host.goBack()
                    },
                )
            },
        )
    }
    if (confirmDeleteAll) {
        DsDialog(
            title = stringResource(R.string.runtime_delete_all_title), onDismiss = { confirmDeleteAll = false },
            body = {
                Text(stringResource(R.string.runtime_delete_all_body), style = DsType.std14, color = colors.labelSecondary)
            },
            actions = {
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
            },
        )
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
        // The update section says it in full (with the reason and the log) while its outcome is shown.
        (runtime as? RuntimeState.Running)?.rolledBack?.takeIf { state.outcome !is UpdateOutcome.RolledBack }?.let { rolledBack ->
            Text(
                stringResource(R.string.runtime_update_rolled_back, com.labteto.dshmobile.ui.screens.main.technicalDisplay(rolledBack.failedVersion)),
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

/**
 * Updates: what is on offer and what it would change, the progress of one, how the last one ended,
 * "go back to the previous version", and the two switches. One block at a time, cross-faded, so
 * the section never shows an offer and a result that contradict each other.
 */
@Composable
private fun UpdateSection(
    state: BuiltInHarnessState,
    checking: Boolean,
    checkResult: RuntimeInstaller.CheckResult?,
    notify: Boolean,
    autoInstall: Boolean,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onGoBack: () -> Unit,
    onUpdateApp: () -> Unit,
    onDismissOutcome: () -> Unit,
    onNotifyChange: (Boolean) -> Unit,
    onAutoInstallChange: (Boolean) -> Unit,
) {
    val colors = DsTheme.colors
    val runtime = state.runtime
    val update = state.update
    val installed = state.installed
    val outcome = state.outcome
    Section(stringResource(R.string.runtime_section_update)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = DsSpacing.small).animateContentSize(DsAnimations.expand),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            when {
                runtime is RuntimeState.Updating -> {
                    val progress = updateProgress(runtime.phase, update?.takeIf { !runtime.goingBack }, null)
                    // The offer being installed is known before its manifest is read again.
                    val target = runtime.target ?: update?.ref?.takeIf { !runtime.goingBack }
                    Text(
                        when {
                            target == null -> stringResource(R.string.runtime_card_updating)
                            runtime.goingBack -> stringResource(R.string.runtime_update_going_back_to, technicalDisplay(target.releaseId))
                            else -> stringResource(R.string.runtime_update_updating_to, technicalDisplay(target.releaseId))
                        },
                        style = DsType.std14Strong,
                        color = colors.labelPrimary,
                    )
                    if (target != null) FromTo(runtime.installed.releaseId, target.releaseId, runtime.installed.harnessVersion, target.harnessVersion)
                    RuntimeProgressBar(progress.fraction)
                    Text(setupProgressDetail(progress), style = DsType.small13, color = colors.labelSecondary)
                    if (runtime.phase !is UpdatePhase.Switching) {
                        DsConnectButton(stringResource(R.string.common_cancel), onCancel, variant = ConnectButtonVariant.Outline)
                    }
                }
                outcome is UpdateOutcome.RolledBack -> NoticeBlock(
                    warn = true,
                    text = stringResource(R.string.runtime_update_rolled_back_title),
                    detail = runtimeIssueText(runtimeIssue(outcome.reason, installed = true, port = 0)) + " " +
                        stringResource(R.string.runtime_update_rolled_back_detail, technicalDisplay(outcome.to.releaseId), technicalDisplay(outcome.from.releaseId)),
                    log = failureLog(outcome.reason),
                ) {
                    DsConnectButton(stringResource(R.string.common_ok), onDismissOutcome, variant = ConnectButtonVariant.Outline)
                }
                outcome is UpdateOutcome.Failed -> NoticeBlock(
                    warn = false,
                    text = runtimeIssueText(runtimeIssue(outcome.reason, installed = true, port = 0)),
                    detail = null,
                    log = null,
                ) {
                    DsConnectButton(runtimeActionLabel(RuntimeAction.RetryInstall), if (outcome.wentBack) onGoBack else onUpdate, enabled = !runtime.busy)
                    DsConnectButton(stringResource(R.string.common_ok), onDismissOutcome, variant = ConnectButtonVariant.Outline)
                }
                state.updateNeedsApp != null -> {
                    Text(stringResource(R.string.runtime_update_needs_app_title), style = DsType.std14Strong, color = colors.labelPrimary)
                    Text(
                        runtimeIssueText(runtimeIssue(RuntimeFailure.AppTooOld(state.updateNeedsApp!!), installed = false, port = 0)),
                        style = DsType.small13,
                        color = colors.labelSecondary,
                    )
                    DsConnectButton(runtimeActionLabel(RuntimeAction.UpdateApp), onUpdateApp)
                }
                update != null && state.skippedRelease != null -> {
                    Text(
                        stringResource(R.string.runtime_update_skipped, technicalDisplay(state.skippedRelease!!)),
                        style = DsType.std14Strong,
                        color = colors.labelPrimary,
                    )
                    Text(stringResource(R.string.runtime_update_skipped_hint), style = DsType.small13, color = colors.labelSecondary)
                    if (installed != null) FromTo(installed.releaseId, update.releaseId, installed.harnessVersion, update.harnessVersion)
                    DsConnectButton(stringResource(R.string.runtime_update_anyway), onUpdate, variant = ConnectButtonVariant.Outline, enabled = !runtime.busy)
                }
                update != null -> {
                    Text(stringResource(R.string.runtime_card_update), style = DsType.std14Strong, color = colors.labelPrimary)
                    if (installed != null) FromTo(installed.releaseId, update.releaseId, installed.harnessVersion, update.harnessVersion)
                    Text(
                        if (update.downloadBytes <= 0) {
                            // The release kept for a rollback is still unpacked: nothing to fetch.
                            stringResource(R.string.runtime_update_nothing_to_download)
                        } else {
                            stringResource(
                                if (update.baseChanges) R.string.runtime_update_what_both else R.string.runtime_update_what_harness,
                                formatBytes(update.downloadBytes),
                            )
                        },
                        style = DsType.small13,
                        color = colors.labelSecondary,
                    )
                    DsConnectButton(stringResource(R.string.runtime_update_button), onUpdate, enabled = !runtime.busy)
                }
                else -> {
                    if (outcome is UpdateOutcome.Applied) {
                        Text(
                            stringResource(
                                if (outcome.wentBack) R.string.runtime_update_went_back_done else R.string.runtime_update_done,
                                technicalDisplay(outcome.to.releaseId),
                            ),
                            style = DsType.std14Strong,
                            color = colors.labelPrimary,
                        )
                    }
                    val line = when {
                        checking -> stringResource(R.string.runtime_update_checking)
                        checkResult is RuntimeInstaller.CheckResult.UpToDate -> stringResource(R.string.runtime_update_none)
                        checkResult is RuntimeInstaller.CheckResult.Refused -> runtimeIssueTextOf(checkResult)
                        else -> null
                    }
                    if (line != null) {
                        Text(
                            line,
                            style = DsType.small13,
                            color = if (checkResult is RuntimeInstaller.CheckResult.Refused && !checking) colors.warnLabel else colors.labelSecondary,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    DsConnectButton(stringResource(R.string.runtime_update_check), onCheck, variant = ConnectButtonVariant.Outline, enabled = !checking && !runtime.busy)
                }
            }

            val previous = installed?.previousRelease
            if (previous != null && runtime !is RuntimeState.Updating) {
                Spacer(Modifier.height(DsSpacing.xsmall))
                ValueRow(stringResource(R.string.runtime_update_previous), previous.releaseId, mono = true)
                DsConnectButton(stringResource(R.string.runtime_update_go_back), onGoBack, variant = ConnectButtonVariant.Outline, enabled = !runtime.busy)
            }
        }
        ToggleRow(stringResource(R.string.runtime_switch_notify_updates), notify, stringResource(R.string.runtime_switch_notify_updates_hint)) {
            onNotifyChange(!notify)
        }
        ToggleRow(stringResource(R.string.runtime_switch_auto_update), autoInstall, stringResource(R.string.runtime_switch_auto_update_hint)) {
            onAutoInstallChange(!autoInstall)
        }
    }
}

/**
 * Release and harness, from what is installed to what an update brings. Each label sits above its
 * "from → to": two release ids side by side do not leave a label room beside them on a narrow
 * screen with large text. An unchanged harness version is shown once.
 */
@Composable
private fun FromTo(fromRelease: String, toRelease: String, fromHarness: String, toHarness: String) {
    StackedValue(
        stringResource(R.string.runtime_info_release),
        stringResource(R.string.runtime_update_versions, technicalDisplay(fromRelease), technicalDisplay(toRelease)),
    )
    // A release that only changes the system layer keeps the same harness: one value, not "x → x".
    StackedValue(
        stringResource(R.string.runtime_info_harness),
        if (fromHarness == toHarness) {
            technicalDisplay(toHarness)
        } else {
            stringResource(R.string.runtime_update_versions, technicalDisplay(fromHarness), technicalDisplay(toHarness))
        },
    )
}

@Composable
private fun StackedValue(label: String, value: String) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = DsType.small13, color = colors.labelSecondary)
        Text(value, style = DsType.std14, color = colors.labelPrimary)
    }
}

/**
 * The part of a failed start's output worth reading in the rollback notice: from the last line that
 * names an error (a stack trace's own end is rarely the point), at most [LOG_EXCERPT] lines; the
 * last lines when nothing names one. Null when there is no output.
 */
internal fun failureLog(reason: RuntimeFailure): String? {
    val text = when (reason) {
        is RuntimeFailure.ExitedBeforeReady -> reason.logTail
        is RuntimeFailure.ReadyTimeout -> reason.logTail
        is RuntimeFailure.Crashed -> reason.logTail
        is RuntimeFailure.LaunchFailed -> reason.detail
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lines = text.lines()
    val error = lines.indexOfLast { ERROR_LINE.containsMatchIn(it) }
    val excerpt = if (error >= 0) lines.drop(error).take(LOG_EXCERPT) else lines.takeLast(LOG_EXCERPT)
    return excerpt.joinToString("\n")
}

private const val LOG_EXCERPT = 8
private val ERROR_LINE = Regex("^\\s*(\\w*Error|Error)\\b|^\\s*error:", RegexOption.IGNORE_CASE)

/** A tinted block for how an update ended: amber when things are working again, red when they are not. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NoticeBlock(warn: Boolean, text: String, detail: String?, log: String?, buttons: @Composable () -> Unit) {
    val colors = DsTheme.colors
    val tint = if (warn) colors.warnLabel else colors.error
    androidx.compose.material3.Surface(
        shape = DsShapes.buttonLarge,
        color = if (warn) colors.warnTertiary else colors.errorTertiary,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (warn) colors.warnBorder else colors.errorBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(DsSpacing.medium), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Text(text, style = DsType.std14Strong, color = tint, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (detail != null) Text(detail, style = DsType.small13, color = colors.labelSecondary)
            if (log != null) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
                ) {
                    Text(
                        log,
                        style = DsType.mono12.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                        color = colors.labelSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(DsShapes.code)
                            .background(colors.codeBlockBg)
                            .horizontalScroll(rememberScrollState())
                            .padding(DsSpacing.compact),
                        softWrap = false,
                    )
                }
            }
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) { buttons() }
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
    // A log is left to right in every language: in Arabic or Urdu a line that starts with "----"
    // would otherwise be reordered and pushed to the right edge.
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
    ) {
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
                style = DsType.mono12.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                color = if (log.isBlank()) colors.labelTertiary else colors.labelSecondary,
                softWrap = false,
            )
        }
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
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val maxValueWidth = maxWidth * 0.6f
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(vertical = DsSpacing.xsmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        Text(label, style = DsType.std14, color = colors.labelSecondary, modifier = Modifier.weight(1f))
        Text(
            // A version or a port is left-to-right in every language: isolated, so Arabic and Urdu
            // do not reorder "0.2.2-rc.1" into "rc.1-0.2.2".
            if (mono) com.labteto.dshmobile.ui.screens.main.technicalDisplay(value) else value,
            style = if (mono) DsType.mono12 else DsType.std14,
            color = colors.labelPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            // Its own width up to most of the row, so a long value never squeezes its label to a
            // letter a line; the label takes whatever is left.
            modifier = Modifier.widthIn(max = maxValueWidth),
        )
    }
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
