package com.labteto.dshmobile.ui.screens.main

import android.content.Context
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.core.DshCore
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.PlanModeNode
import com.labteto.dshmobile.core.session.QueueItem
import com.labteto.dshmobile.core.session.WorkflowNode
import com.labteto.dshmobile.core.session.effectiveWorkingDirectory
import com.labteto.dshmobile.core.wire.dto.AgentPresetListValue
import com.labteto.dshmobile.core.wire.dto.ContextBreakdownView
import com.labteto.dshmobile.core.wire.dto.ContextPressureView
import com.labteto.dshmobile.core.wire.dto.FULL_ACCESS_PRESET
import com.labteto.dshmobile.core.wire.dto.GoalPhase
import com.labteto.dshmobile.core.wire.dto.GoalSnapshot
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.core.wire.dto.JobStatus
import com.labteto.dshmobile.core.wire.dto.JobView
import com.labteto.dshmobile.core.wire.dto.PermissionSelect
import com.labteto.dshmobile.core.wire.dto.SessionModelsValue
import com.labteto.dshmobile.core.wire.dto.SessionStatsView
import com.labteto.dshmobile.core.wire.dto.SubagentListEntry
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import com.labteto.dshmobile.core.wire.dto.displayPermissionPreset
import com.labteto.dshmobile.data.ActionOutcome
import com.labteto.dshmobile.data.ArchiveOutcome
import com.labteto.dshmobile.data.CommandOutcome
import com.labteto.dshmobile.data.PermissionSwitchResult
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.data.failureNotice
import com.labteto.dshmobile.ui.components.ContextMeter
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.components.formatTokens
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.Ds
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The session details panel: everything about the open session that is not the conversation.
 *
 * Drawn as an Ink side sheet — the full-height surface of design 13, with the rounded inner edge
 * and the little grabber that says it can be swiped away — rather than a second chat page. The
 * stack reads top to bottom as: what this session is (identity), how full its context is, what
 * model and permission it runs under, what the agent is doing (activity), and finally the host it
 * runs on. Cards that are actions (goal, plan mode, jobs, subagents) are single rows that expand in
 * place; the facts worth scanning never need a tap.
 */
@Composable
fun DetailsPanel(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAutomations: () -> Unit = {},
    /**
     * Show a subagent child's transcript. That transcript is the chat screen's sheet, not the
     * panel's, so the host of this panel closes it and opens the sheet on that child.
     */
    onOpenSubagent: (String) -> Unit = {},
    /** Open another session, such as the fork just made. Runs outside the panel, which is closing. */
    onOpenSession: (String) -> Unit = {},
) {
    // Back is handled by MainScreen, which drags the panel closed with the predictive back gesture.
    val store = rememberSessionStore()
    val automationAvailable by store.automationAvailable.collectAsStateWithLifecycle()
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toast = rememberDsToast()

    val conversation by store.currentConversation.collectAsState()
    val jobs by store.jobs.collectAsState()
    val jobControls by store.jobControls.collectAsState()
    val hostInfo by store.hostInfo.collectAsState()
    val subagents by store.subagents.collectAsState()
    val sessions by store.sessions.collectAsState()
    val currentSessionId by store.currentSessionId.collectAsState()
    val stats by store.sessionStats.collectAsState()
    val usage by store.tokenUsage.collectAsState()
    val breakdown by store.contextBreakdown.collectAsState()
    val pressure by store.contextPressure.collectAsState()
    val models by store.models.collectAsState()
    val agentPresets by store.agentPresets.collectAsState()
    val currentSubagent by store.currentSubagent.collectAsState()
    val connection by store.connectionState.collectAsStateWithLifecycle()
    val quota by store.quotaStatus.collectAsStateWithLifecycle()
    val quotaSelection by store.modelSelection.collectAsStateWithLifecycle()
    val quotaProvider = store.quotaProvider()
    var quotaExpanded by remember { mutableStateOf(false) }
    val quotaNow = rememberQuotaClock(store, Triple(currentSessionId, quotaProvider, quotaSelection), quotaExpanded)
    val permissions by store.permissions.collectAsStateWithLifecycle()
    val pendingPermission by store.pendingPermission.collectAsStateWithLifecycle()
    val current = sessions.firstOrNull { it.sessionId == currentSessionId }
    // A subagent child is readable here but not writable: rename, model, preset, `goals/*` and
    // `/plan` all resolve the ordinary agent behind the session and come back `session/agent-busy`.
    // The flow is what `openSession` settles from the row; the row field covers a child opened
    // before that lands.
    val subagentChild = currentSubagent != null || current?.subagent != null
    // Plan mode as the host last reported it. The switch shows the reader's own request until this
    // agrees with it, so a tap moves the switch at once instead of waiting on the round trip.
    val planObserved = remember(conversation) { conversation?.let { parsePlanActive(it) } }

    // This panel owns its own sheets rather than reaching back into ChatScreen's: it is reachable
    // on its own on a phone, where the chat surface is not even composed behind it.
    var sheet by remember { mutableStateOf<DetailsSheet?>(null) }
    var openJob by remember { mutableStateOf<JobView?>(null) }
    var archiveBusy by remember { mutableStateOf<com.labteto.dshmobile.data.ArchiveOutcome.Busy?>(null) }
    var archiveConfirm by remember { mutableStateOf(false) }
    var permissionConfirm by remember { mutableStateOf<String?>(null) }
    // A fork that was made and is waiting on the reader to say whether to open it.
    var forkedId by remember { mutableStateOf<String?>(null) }
    var forkBusy by remember { mutableStateOf(false) }
    var exportBusy by remember { mutableStateOf(false) }
    // The plan switch's requested position, held until the host's own state agrees with it.
    var pendingPlan by remember { mutableStateOf<Boolean?>(null) }
    var planRequest by remember { mutableIntStateOf(0) }

    val savedLabel = stringResource(R.string.chat_export_saved)
    val failedLabel = stringResource(R.string.chat_export_failed)
    val renameFailedLabel = stringResource(R.string.details_failed_rename)
    val forkFailedLabel = stringResource(R.string.details_failed_fork)
    val archiveFailedLabel = stringResource(R.string.details_failed_archive)
    val archivedLabel = stringResource(R.string.details_archived)
    val stopJobFailedLabel = stringResource(R.string.details_failed_stop_job)
    val queueFailedLabel = stringResource(R.string.details_failed_queue_remove)
    val goalFailedLabel = stringResource(R.string.details_failed_goal)
    val planFailedLabel = stringResource(R.string.details_failed_plan)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exportBusy = true
        scope.launch {
            try {
                val outcome = try {
                    context.contentResolver.openOutputStream(uri)?.use { sink ->
                        store.exportSessionTo(sink, includeDescendants = true, report = false)
                    } ?: ActionOutcome.Failed()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    ActionOutcome.Failed(failure.message)
                }
                if (outcome is ActionOutcome.Failed) {
                    // A failed save leaves an empty file at the place they picked; take it back.
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    toast.second(failureNotice(failedLabel, outcome.message))
                } else {
                    toast.second(savedLabel)
                }
            } finally {
                exportBusy = false
            }
        }
    }

    val commandFailedLabel = stringResource(R.string.err_command_failed)
    val commandUnknownLabel = stringResource(R.string.err_command_unknown)
    val permissionUnconfirmedLabel = stringResource(R.string.permission_unconfirmed)

    /** Say so when an action came back refused; the success shows itself in the panel. */
    fun sayIfFailed(label: String, outcome: ActionOutcome<*>) {
        if (outcome is ActionOutcome.Failed) toast.second(failureNotice(label, outcome.message))
    }

    /** Archiving can finish, ask to stop running work first, or fail; each says so out loud. */
    fun sayArchive(outcome: ArchiveOutcome) {
        when (outcome) {
            ArchiveOutcome.Archived -> toast.second(archivedLabel)
            is ArchiveOutcome.Busy -> archiveBusy = outcome
            ArchiveOutcome.Failed -> toast.second(archiveFailedLabel)
        }
    }

    // Let go of the plan request as soon as the host reports the position that was asked for.
    LaunchedEffect(planObserved, pendingPlan) {
        if (planPendingSettled(planObserved, pendingPlan)) pendingPlan = null
    }

    /** Switch the preset and say so if it failed or went unconfirmed; the card shows the rest. */
    fun switchPermission(value: String) {
        scope.launch {
            val notice = when (val result = store.setPermissionPreset(value)) {
                is PermissionSwitchResult.Unconfirmed -> permissionUnconfirmedLabel
                is PermissionSwitchResult.Failed -> when (val outcome = result.outcome) {
                    is CommandOutcome.Failed -> commandFailedLabel.format(outcome.message)
                    is CommandOutcome.Unknown -> commandUnknownLabel.format(outcome.line)
                    is CommandOutcome.Ok -> null
                }
                else -> null
            }
            notice?.let { toast.second(it) }
        }
    }

    /** Apply a permission preset, gating full access behind its acknowledgement dialog. */
    fun pickPermission(value: String) {
        if (value == FULL_ACCESS_PRESET) {
            permissionConfirm = value
        } else {
            haptics.confirm()
            switchPermission(value)
        }
    }

    Box(modifier.fillMaxHeight()) {
        // The sheet face: the rounded inner edge and hairline are the panel's whole chrome, since
        // MainScreen owns the slide and the scrim behind it.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.bgLayer2,
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
        ) {
            Row(Modifier.fillMaxSize()) {
                    // The rule on the chat side of the panel, drawn as a 1dp bar so it can sit on the
                    // rounded edge without a border on the other three sides.
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(colors.borderL2),
                    )
                    Column(
                        Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .safeDrawingPadding(),
                    ) {
                        // A grabber reads as "this edge moves"; the panel closes by dragging toward
                        // the chat, which is the same gesture family as a sheet pulled down.
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = DsSpacing.small),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                Modifier
                                    .width(40.dp)
                                    .height(4.dp)
                                    .clip(DsShapes.pillFull)
                                    .background(colors.borderL3),
                            )
                        }
                        HeaderRow(onClose = {
                            haptics.tap()
                            onClose()
                        })

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    start = DsSpacing.comfortable,
                                    end = DsSpacing.comfortable,
                                    bottom = DsSpacing.xlarge,
                                ),
                            verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                        ) {
                            val present = current?.copy(
                                cwd = effectiveWorkingDirectory(conversation?.projections, current.cwd),
                            )
                            if (present == null) {
                                Text(
                                    stringResource(R.string.chat_details_empty),
                                    style = DsType.small13,
                                    color = colors.labelTertiary,
                                    modifier = Modifier.padding(top = DsSpacing.medium),
                                )
                            } else {
                                SessionCard(
                                    session = present,
                                    models = models,
                                    presets = agentPresets,
                                    host = connection.host,
                                    subagentChild = subagentChild,
                                    forkBusy = forkBusy,
                                    exportBusy = exportBusy,
                                    onRename = { title ->
                                        scope.launch {
                                            val sid = currentSessionId ?: return@launch
                                            sayIfFailed(renameFailedLabel, store.renameSession(sid, title, report = false))
                                        }
                                    },
                                    onFork = {
                                        val sid = currentSessionId
                                        if (sid != null && !forkBusy) {
                                            forkBusy = true
                                            scope.launch {
                                                try {
                                                    when (val outcome = store.forkSession(sid, report = false)) {
                                                        is ActionOutcome.Done -> forkedId = outcome.value
                                                        is ActionOutcome.Failed ->
                                                            toast.second(failureNotice(forkFailedLabel, outcome.message))
                                                    }
                                                } finally {
                                                    forkBusy = false
                                                }
                                            }
                                        }
                                    },
                                    onArchive = { archiveConfirm = true },
                                    onExport = { exportLauncher.launch("dsh-session-${currentSessionId.orEmpty()}.zip") },
                                )

                                val conv = conversation
                                if (conv != null) {
                                    ContextCard(breakdown, pressure, usage, stats)

                                    UsageCard(quota, quotaProvider, quotaNow, onExpanded = { quotaExpanded = it }, onRefresh = { scope.launch { store.refreshQuota(quotaExpanded, recheck = true) } })
                                    PanelSection(stringResource(R.string.ink_panel_model_permission))
                                    ModelPermissionCard(
                                        models = models,
                                        presets = agentPresets,
                                        session = present,
                                        permissions = permissions,
                                        pendingPermission = pendingPermission,
                                        subagentChild = subagentChild,
                                        onOpenModels = {
                                            haptics.tap()
                                            sheet = DetailsSheet.Models
                                        },
                                        onOpenPermission = {
                                            haptics.tap()
                                            sheet = DetailsSheet.Permission
                                        },
                                        onOpenPresets = {
                                            haptics.tap()
                                            scope.launch { store.refreshAgentPresets() }
                                            sheet = DetailsSheet.Presets
                                        },
                                    )

                                    PanelSection(stringResource(R.string.details_section_activity))
                                    ActivityGroup(
                                        conversation = conv,
                                        store = store,
                                        jobs = jobs,
                                        jobControls = jobControls,
                                        subagents = subagents,
                                        subagentChild = subagentChild,
                                        planObserved = planObserved,
                                        planPending = pendingPlan,
                                        onOpenJob = { job -> openJob = job },
                                        onOpenSubagent = onOpenSubagent,
                                        onStopJob = { job ->
                                            scope.launch {
                                                sayIfFailed(stopJobFailedLabel, store.killJob(job.id, report = false))
                                            }
                                        },
                                        onTogglePlan = { next ->
                                            // One request at a time: a second tap while the first is in
                                            // flight would race it and leave the switch guessing.
                                            if (pendingPlan == null) {
                                                pendingPlan = next
                                                planRequest += 1
                                                val mine = planRequest
                                                scope.launch {
                                                    when (val outcome = store.runCommand(if (next) "/plan" else "/plan off", report = false)) {
                                                        is CommandOutcome.Ok -> {
                                                            outcome.text?.takeIf { it.isNotBlank() }?.let { toast.second(it) }
                                                            // The host's own state is what the switch shows; give it
                                                            // a moment to say so before the request is let go.
                                                            delay(PLAN_SETTLE_MS)
                                                            if (planRequest == mine) pendingPlan = null
                                                        }
                                                        is CommandOutcome.Failed -> {
                                                            if (planRequest == mine) pendingPlan = null
                                                            toast.second(failureNotice(planFailedLabel, outcome.message))
                                                        }
                                                        is CommandOutcome.Unknown -> {
                                                            if (planRequest == mine) pendingPlan = null
                                                            toast.second(commandUnknownLabel.format(outcome.line))
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        onRemoveQueued = { item ->
                                            scope.launch {
                                                sayIfFailed(queueFailedLabel, store.updateQueueOutcome(item.id, "remove", report = false))
                                            }
                                        },
                                        onGoalFailed = { message -> toast.second(failureNotice(goalFailedLabel, message)) },
                                    )
                                }

                                PanelSection(stringResource(R.string.details_section_host))
                                HostCard(hostInfo, connection.host, models)
                                if (automationAvailable != false) {
                                    AutomationsCard(onClick = onOpenAutomations)
                                }
                            }
                        }
                    }
                }
            }
            DsToastHost(toast, modifier = Modifier.fillMaxWidth())
        }

    when (sheet) {
        DetailsSheet.Models -> ModelsSheet(models = models, store = store, onDismiss = { sheet = null })
        DetailsSheet.Permission -> permissions?.let { select ->
            PermissionMenu(
                select = select,
                current = (pendingPermission ?: select.currentValue),
                onDismiss = { sheet = null },
                onPick = { value ->
                    sheet = null
                    pickPermission(value)
                },
            )
        }
        DetailsSheet.Presets -> PresetsSheet(
            presets = agentPresets,
            currentPreset = current?.agentPreset,
            sessionBlank = current?.blank ?: false,
            store = store,
            onDismiss = { sheet = null },
        )
        null -> Unit
    }

    permissionConfirm?.let { target ->
        FullAccessConfirmDialog(
            onDismiss = { permissionConfirm = null },
            onConfirm = {
                permissionConfirm = null
                haptics.confirm()
                switchPermission(target)
            },
        )
    }

    openJob?.let { job ->
        // The live row, so the sheet's stop button agrees with the card once the job settles.
        JobOutputSheet(
            job = jobs.firstOrNull { it.id == job.id } ?: job,
            store = store,
            canStop = jobControls,
            onDismiss = { openJob = null },
        )
    }

    if (archiveConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.chatlist_session_archive),
            body = current?.let { displaySessionTitle(it) }.orEmpty(),
            confirmLabel = stringResource(R.string.common_archive),
            onDismiss = { archiveConfirm = false },
            onConfirm = {
                archiveConfirm = false
                haptics.tap()
                scope.launch {
                    val sid = currentSessionId ?: return@launch
                    sayArchive(store.archiveSession(sid, report = false))
                }
            },
        )
    }

    archiveBusy?.let { busy ->
        ArchiveBusyDialog(
            busy = busy,
            onDismiss = { archiveBusy = null },
            onConfirm = {
                archiveBusy = null
                haptics.tap()
                scope.launch { sayArchive(store.archiveSession(busy.sessionId, stopActivity = true, report = false)) }
            },
        )
    }

    forkedId?.let { id ->
        ForkDoneDialog(
            onDismiss = { forkedId = null },
            onOpen = {
                forkedId = null
                haptics.confirm()
                onOpenSession(id)
            },
        )
    }
}

/** The fork exists. Whether to go there is the reader's call; the panel stays put if they decline. */
@Composable
private fun ForkDoneDialog(onDismiss: () -> Unit, onOpen: () -> Unit) {
    DsDialog(title = stringResource(R.string.details_fork_done_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.details_fork_done_body),
            style = DsType.std14,
            color = DsTheme.colors.labelSecondary,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DsButton(
                text = stringResource(R.string.details_fork_stay),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
            )
            Spacer(Modifier.width(DsSpacing.small))
            DsButton(
                text = stringResource(R.string.details_fork_open),
                onClick = onOpen,
                variant = DsButtonVariant.Info,
            )
        }
    }
}

/** How long the plan switch holds the reader's request while the host's own state catches up. */
private const val PLAN_SETTLE_MS = 4_000L

/** Which picker, if any, is open over the details panel. */
private enum class DetailsSheet { Models, Permission, Presets }

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

@Composable
private fun HeaderRow(onClose: () -> Unit) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = DsSpacing.comfortable, end = DsSpacing.tiny, top = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.chat_details_title),
            style = DsType.hero26,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        DsIconButton(
            icon = Icons.Filled.Close,
            contentDescription = stringResource(R.string.common_close),
            onClick = onClose,
        )
    }
}

/** A section label between groups of cards. */
@Composable
private fun PanelSection(title: String) {
    Text(
        title.uppercase(),
        style = DsType.label12,
        color = DsTheme.colors.labelTertiary,
        modifier = Modifier.padding(start = DsSpacing.tiny, top = DsSpacing.tiny),
    )
}

/**
 * A card that holds rows instead of blocks: no inner padding and hairlines between the rows, like
 * the design's `.card.tight`. The rows own their own 56dp rhythm.
 */
@Composable
private fun PanelGroup(content: @Composable ColumnScope.() -> Unit) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.bgLayer1)
            .border(1.dp, colors.borderL2, DsShapes.block)
            .padding(horizontal = DsSpacing.medium),
        content = content,
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = DsTheme.colors.borderL1)
}

/** The small `.tag` chip of the design: 24dp, 6dp radius, optional accent wash. */
@Composable
private fun InkTag(text: String, accent: Boolean = false, warn: Boolean = false) {
    val colors = DsTheme.colors
    val fill = when {
        warn -> colors.warnTertiary
        accent -> colors.accentTertiary
        else -> colors.bgLayer3
    }
    val content = when {
        warn -> colors.warnLabel
        accent -> colors.accent
        else -> colors.labelSecondary
    }
    Box(
        modifier = Modifier
            .height(24.dp)
            .clip(DsShapes.chip)
            .background(fill)
            .padding(horizontal = DsSpacing.small),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = DsType.xsmall12,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Session identity
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(
    session: SessionRow,
    models: SessionModelsValue?,
    presets: AgentPresetListValue?,
    host: HostConfig?,
    /** A subagent child, whose rename, model and preset writes the host refuses. */
    subagentChild: Boolean = false,
    /** A fork is on its way; the button waits instead of starting a second copy. */
    forkBusy: Boolean = false,
    /** A save is in progress, so a second tap does not start another. */
    exportBusy: Boolean = false,
    onRename: (String) -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var renaming by remember(session.sessionId) { mutableStateOf(false) }
    val modeLabel = when {
        host == null -> null
        host.isRelay -> stringResource(R.string.connect_mode_relay)
        host.isLoopback -> stringResource(R.string.connect_mode_loopback)
        else -> stringResource(R.string.connect_mode_lan)
    }
    val address = host?.displayAddress
    val identityLine = listOfNotNull(modeLabel, address).joinToString(" · ")
    val mascot = when {
        session.running -> MascotState.Working
        session.pendingInteraction != null -> MascotState.NeedsYou
        else -> MascotState.Idle
    }
    val modelName = modelDisplayName(models)
    val presetLabel = session.agentPreset?.let { agentPresetLabel(it, presets) }

    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Row(verticalAlignment = Alignment.Top) {
            MascotMark(size = 36.dp, state = mascot)
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The one thing on this panel that must never be cut short without an ellipsis.
                Text(
                    session.title ?: session.cwd?.let { technicalDisplay(basename(it)) }
                        ?: technicalDisplay(session.sessionId),
                    style = DsType.base16Strong,
                    color = colors.labelPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (identityLine.isNotEmpty()) {
                    Text(
                        technicalDisplay(identityLine),
                        style = DsType.mono12,
                        color = colors.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(R.string.connect_protocol_baseline, DshCore.PROTOCOL_BASELINE),
                    style = DsType.caption11,
                    color = colors.labelCaption,
                    maxLines = 1,
                )
            }
        }
        // Where it lives. The full path wraps rather than clipping: it is the fact that tells two
        // same-named projects apart.
        session.cwd?.let { cwd ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    FeatherIcons.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp).padding(top = 2.dp),
                    tint = colors.labelTertiary,
                )
                Spacer(Modifier.width(DsSpacing.xsmall))
                Text(
                    technicalDisplay(cwd),
                    style = DsType.mono12,
                    color = colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The model and the preset are the two things about a session people most often come here
        // to check. They are restated here as tags; the rows below are where they are changed.
        if (modelName != null || presetLabel != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                modelName?.let { InkTag(it, accent = true) }
                presetLabel?.let { InkTag(it) }
                if (session.pendingInteraction != null) {
                    InkTag(stringResource(R.string.chatlist_needs_action), warn = true)
                }
            }
        }
        // Actions: the three that change the session, then Export, which saves it to the phone. One
        // flow row of equal buttons, so a narrow panel wraps a button rather than squeezing it to
        // its icon, and Export never sits alone on a line of its own.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            // A child's title belongs to the harness that spawned it; `session/rename` resolves the
            // ordinary agent and is refused, so the button could only raise the error banner.
            if (!subagentChild) {
                DsButton(
                    text = stringResource(R.string.common_rename),
                    icon = FeatherIcons.Edit3,
                    onClick = {
                        haptics.tap()
                        renaming = true
                    },
                    variant = DsButtonVariant.Outline,
                )
            }
            DsButton(
                text = stringResource(R.string.chatlist_session_fork),
                icon = FeatherIcons.GitBranch,
                onClick = onFork,
                variant = DsButtonVariant.Outline,
                enabled = !forkBusy,
            )
            DsButton(
                text = stringResource(R.string.common_archive),
                icon = FeatherIcons.Archive,
                onClick = onArchive,
                variant = DsButtonVariant.Outline,
            )
            DsButton(
                text = stringResource(R.string.chat_export),
                icon = Icons.Filled.Download,
                onClick = onExport,
                variant = DsButtonVariant.Outline,
                enabled = !exportBusy,
            )
        }
    }
    // The button is gone for a child, and the dialog is the same refused write reached by another
    // path, so it stays closed there too.
    if (renaming && !subagentChild) {
        RenameDialog(
            initial = session.title.orEmpty(),
            title = stringResource(R.string.chatlist_session_rename),
            onDismiss = { renaming = false },
            onConfirm = {
                onRename(it)
                renaming = false
            },
        )
    }
}

@Composable
private fun ContextCard(
    breakdown: ContextBreakdownView?,
    pressure: ContextPressureView?,
    usage: TokenUsageView?,
    stats: SessionStatsView?,
) {
    val colors = DsTheme.colors
    if (breakdown == null && pressure == null && usage == null && stats == null) return
    val percent = pressure?.usedRatio?.let { "${(it * 100).toInt()}%" }
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                FeatherIcons.Layers,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.labelSecondary,
            )
            Spacer(Modifier.width(DsSpacing.small))
            Text(
                stringResource(R.string.chat_context_title),
                style = DsType.rowTitle,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            percent?.let {
                Text(
                    it,
                    style = DsType.mono13,
                    color = colors.labelPrimary,
                    maxLines = 1,
                )
            }
        }
        ContextMeter(breakdown, pressure, Modifier.fillMaxWidth(), barWidth = null)
        if (breakdown != null && breakdown.total > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
                // The bar's three slices are the shared ContextMeter's own palette; the legend has to
                // name the same colours, so it reads them from the same primitives rather than
                // inventing nearby tokens that would drift from the bar.
                LegendDot(stringResource(R.string.chat_context_system), colors.labelTertiary)
                LegendDot(stringResource(R.string.chat_context_tools), Ds.MeterTools)
                LegendDot(stringResource(R.string.chat_context_messages), Ds.MeterMessages)
            }
        }
        val meta = buildList {
            usage?.let {
                add(stringResource(R.string.chat_stats_tokens, formatTokens(it.inputTokens), formatTokens(it.outputTokens)))
            }
            stats?.let {
                add(stringResource(R.string.chat_stats_timing, formatDurationMs(it.llmMs), formatDurationMs(it.toolMs)))
            }
        }
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString(" · "),
                style = DsType.mono12,
                color = colors.labelTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        usage?.cacheHitRatio?.let { ratio ->
            Text(
                stringResource(R.string.chat_stats_cache, (ratio * 100).toInt()),
                style = DsType.caption11,
                color = colors.labelCaption,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(DsShapes.pillFull)
                .background(color),
        )
        Spacer(Modifier.width(DsSpacing.tiny))
        Text(label, style = DsType.caption11, color = DsTheme.colors.labelTertiary, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// Model & permission
// ---------------------------------------------------------------------------

@Composable
private fun ModelPermissionCard(
    models: SessionModelsValue?,
    presets: AgentPresetListValue?,
    session: SessionRow,
    permissions: PermissionSelect?,
    pendingPermission: String?,
    subagentChild: Boolean,
    onOpenModels: () -> Unit,
    onOpenPermission: () -> Unit,
    onOpenPresets: () -> Unit,
) {
    val modelName = modelDisplayName(models)
    val effortName = effortDisplayName(models)
    val permissionValue = permissions?.let { select ->
        val effective = pendingPermission ?: select.currentValue
        displayPermissionPreset(effective, select.current?.name ?: effective)
    }
    val permissionHint = permissions?.let { select ->
        val effective = pendingPermission ?: select.currentValue
        select.selectable.firstOrNull { it.value == effective }?.description
            ?: panelPermissionDescription(effective)
    }
    val presetLabel = session.agentPreset?.let { agentPresetLabel(it, presets) }
    val presetSelectable = !subagentChild && session.blank

    PanelGroup {
        PanelRow(
            icon = FeatherIcons.Cpu,
            title = stringResource(R.string.chat_model_label),
            subtitle = if (models != null && effortName == null) {
                stringResource(R.string.ink_panel_effort_default)
            } else {
                null
            },
            value = listOfNotNull(modelName, effortName).joinToString(" · ").ifEmpty { null }
                // Until the catalog arrives there is nothing to pick from, so the row says so.
                ?: if (models == null) stringResource(R.string.common_loading) else null,
            // A child runs on the model its parent gave it; `session/selectModel` resolves the
            // ordinary agent and is refused, so the row degrades to a fact. So does a row with no
            // catalog yet: its sheet would only say "Loading" for as long as the catalog is missing.
            onClick = if (subagentChild || models == null) null else onOpenModels,
        )
        if (permissions != null) {
            RowDivider()
            PanelRow(
                icon = Icons.Filled.Lock,
                title = stringResource(R.string.permission_preset),
                subtitle = permissionHint,
                value = permissionValue,
                // `/permission` resolves the ordinary agent behind the session and is refused for a
                // child, which is why the row is inert there.
                onClick = if (subagentChild) null else onOpenPermission,
            )
        }
        if (presetLabel != null) {
            RowDivider()
            PanelRow(
                icon = FeatherIcons.Users,
                title = stringResource(R.string.details_preset_label),
                subtitle = if (presetSelectable) null else stringResource(R.string.presets_locked),
                value = presetLabel,
                onClick = if (presetSelectable) onOpenPresets else null,
            )
        }
    }
}

/** One 56dp row of a [PanelGroup]: icon, title, a secondary line and an optional value. */
@Composable
private fun PanelRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button) {
                        haptics.tap()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .heightIn(min = 56.dp)
            .padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (onClick != null) colors.labelSecondary else colors.labelTertiary,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = DsType.rowTitle,
                color = if (onClick != null) colors.labelPrimary else colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = DsType.small13,
                    color = colors.labelTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        value?.let {
            Text(
                it,
                style = DsType.small13,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
        }
        if (onClick != null) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = -90f },
                tint = colors.labelTertiary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

@Composable
private fun ActivityGroup(
    conversation: ConversationSnapshot,
    store: SessionStore,
    jobs: List<JobView>,
    jobControls: Boolean,
    subagents: List<SubagentListEntry>,
    subagentChild: Boolean,
    /** Plan mode as the host reports it, or null when the host has not said. */
    planObserved: Boolean?,
    /** The position the reader asked for and the host has not confirmed yet. */
    planPending: Boolean?,
    onOpenJob: (JobView) -> Unit,
    onOpenSubagent: (String) -> Unit,
    onStopJob: (JobView) -> Unit,
    onTogglePlan: (Boolean) -> Unit,
    onRemoveQueued: (QueueItem) -> Unit,
    onGoalFailed: (String?) -> Unit,
) {
    val goal = parseGoal(conversation.projections["goal"])
    val workflows = remember(conversation.nodes) { parseWorkflows(conversation.nodes) }
    PanelGroup {
        var any = false
        // Goal and plan mode are writes on the harness side (`goals/*`, `/plan`), and a child's
        // agent belongs to its parent, so they are facts-only rows for a subagent child.
        if (!subagentChild) {
            GoalRow(goal, store, onFailed = onGoalFailed)
            any = true
            if (planObserved != null) {
                RowDivider()
                PlanModeRow(
                    active = planSwitchChecked(planObserved, planPending),
                    busy = planPending != null,
                    onToggle = onTogglePlan,
                )
            }
        }
        if (any) RowDivider()
        JobsRow(jobs, jobControls, onOpenJob, onStopJob)
        RowDivider()
        SubagentsRow(subagents, onOpenSubagent)
        if (conversation.queue.isNotEmpty()) {
            RowDivider()
            QueueRow(conversation.queue, onRemoveQueued)
        }
        if (workflows.isNotEmpty()) {
            RowDivider()
            WorkflowRow(workflows)
        }
    }
}

/**
 * The row frame shared by the activity items.
 *
 * Tapping the row does whatever the row is for — expand for a list, toggle for the plan switch —
 * and the trailing chevron is what says a list is in there. [trailing] is for a control the row
 * itself owns; a switch placed there does not also fire [onClick] (the switch consumes the tap).
 */
@Composable
private fun ActivityRow(
    icon: ImageVector,
    iconTint: Color = DsTheme.colors.labelSecondary,
    title: String,
    subtitle: String?,
    clickable: Boolean,
    expandable: Boolean = false,
    expanded: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = DsAnimations.chevron,
        label = "activityChevron",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (clickable && onClick != null) {
                        Modifier.clickable(role = Role.Button) {
                            haptics.tap()
                            onClick()
                        }
                    } else {
                        Modifier
                    },
                )
                .heightIn(min = 56.dp)
                .padding(vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = iconTint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // A row with nothing behind it reads as a fact, the way a model row that cannot be
                // changed does: quieter title, no chevron.
                val tappable = clickable && onClick != null
                Text(
                    title,
                    style = DsType.rowTitle,
                    color = if (tappable || trailing != null) colors.labelPrimary else colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = DsType.small13,
                        color = if (tappable || trailing != null) colors.labelSecondary else colors.labelTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) trailing()
            if (expandable) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.ux_b_expanded else R.string.ux_b_collapsed,
                    ),
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
                    tint = colors.labelTertiary,
                )
            }
        }
        if (expandable && content != null) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(DsAnimations.expand) + fadeIn(DsAnimations.fade),
                exit = shrinkVertically(DsAnimations.expand) + fadeOut(DsAnimations.fade),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, bottom = DsSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) { content() }
            }
        }
    }
}

@Composable
private fun GoalRow(goal: GoalSnapshot?, store: SessionStore, onFailed: (String?) -> Unit) {
    val colors = DsTheme.colors
    var expanded by remember(goal?.revision) { mutableStateOf(false) }
    val phase = goal?.phase?.let { stringResource(goalPhaseLabelRes(it)) }
    val meta = goal?.let { snapshot ->
        buildList {
            add(phase.orEmpty())
            if (snapshot.maxGoalRounds > 0) {
                add(stringResource(R.string.goal_max_rounds, snapshot.maxGoalRounds.toString()))
            }
        }.filter { it.isNotBlank() }.joinToString(" · ")
    }
    ActivityRow(
        icon = FeatherIcons.Target,
        iconTint = if (goal != null) colors.accent else colors.labelTertiary,
        title = stringResource(R.string.goal_title),
        subtitle = meta ?: stringResource(R.string.goal_none),
        clickable = goal != null,
        expandable = goal != null,
        expanded = expanded,
        onClick = if (goal != null) ({ expanded = !expanded }) else null,
    ) {
        if (goal != null) {
            Text(
                goal.objective,
                style = DsType.small13,
                color = colors.labelSecondary,
            )
            goal.blockedReason?.let {
                Text(
                    stringResource(R.string.goal_blocked_reason, it.message),
                    style = DsType.caption11,
                    color = colors.warnText,
                )
            }
            // The verbs sit right under the objective. They used to be a second collapsed bar
            // inside this row, so Pause, Edit and Clear were two taps down and read as missing.
            GoalActions(goal, store, onFailed = onFailed)
        }
    }
}

@Composable
private fun PlanModeRow(active: Boolean, busy: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    ActivityRow(
        icon = FeatherIcons.Map,
        title = stringResource(R.string.plan_mode_title),
        subtitle = stringResource(R.string.plan_mode_hint),
        clickable = !busy,
        trailing = {
            Switch(
                checked = active,
                enabled = !busy,
                onCheckedChange = {
                    haptics.toggle(it)
                    onToggle(it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    checkedBorderColor = colors.accent,
                    uncheckedThumbColor = colors.labelTertiary,
                    uncheckedTrackColor = colors.borderL3,
                    uncheckedBorderColor = colors.borderL3,
                ),
            )
        },
        // The whole row is the switch's target as well: a 56dp row is easier to hit than the
        // 52x32 control inside it, and either one reads as the same action.
        onClick = {
            haptics.toggle(!active)
            onToggle(!active)
        },
    )
}

@Composable
private fun JobsRow(
    jobs: List<JobView>,
    controls: Boolean,
    onOpen: (JobView) -> Unit,
    onStop: (JobView) -> Unit,
) {
    val colors = DsTheme.colors
    val running = jobs.count { it.finishedAt == null }
    var expanded by remember { mutableStateOf(false) }
    ActivityRow(
        icon = FeatherIcons.Cpu,
        title = stringResource(R.string.jobs_title),
        subtitle = when {
            jobs.isEmpty() -> stringResource(R.string.jobs_empty)
            running > 0 -> stringResource(R.string.jobs_running)
            else -> jobs.size.toString()
        },
        clickable = jobs.isNotEmpty(),
        expandable = jobs.isNotEmpty(),
        expanded = expanded,
        onClick = if (jobs.isNotEmpty()) ({ expanded = !expanded }) else null,
        trailing = if (running > 0) {
            { StateDot(StateDotState.Running) }
        } else {
            null
        },
    ) {
        // A running job's elapsed time has to be driven by a ticker; reading the clock inside a
        // composable that never recomposes leaves the timer frozen at its first value.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(running > 0) {
            while (running > 0) {
                delay(1_000)
                now = System.currentTimeMillis()
            }
        }
        jobs.forEach { job ->
            Row(
                modifier = if (controls) {
                    Modifier
                        .fillMaxWidth()
                        .clip(DsShapes.row)
                        .clickable(role = Role.Button) { onOpen(job) }
                } else {
                    Modifier.fillMaxWidth()
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                StateDot(jobStatusDot(job.status))
                Column(Modifier.weight(1f)) {
                    Text(
                        job.label,
                        style = DsType.small13,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (job.progress ?: job.detail)?.let { "${job.kind} · $it" } ?: job.kind,
                        style = DsType.caption11,
                        color = colors.labelCaption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    formatDurationMs((job.finishedAt ?: now) - job.startedAt),
                    style = DsType.caption11,
                    color = colors.labelCaption,
                )
                if (controls && job.status == JobStatus.RUNNING) {
                    DsIconButton(
                        icon = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.jobs_stop),
                        onClick = { onStop(job) },
                        tint = colors.labelTertiary,
                    )
                }
                // The row opens the job's output, so it says so the way the other openable rows do.
                if (controls) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.details_open_output),
                        modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = -90f },
                        tint = colors.labelTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubagentsRow(subagents: List<SubagentListEntry>, onOpen: (String) -> Unit) {
    val colors = DsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val running = subagents.count { subagentRunning(it) }
    ActivityRow(
        icon = FeatherIcons.Users,
        title = stringResource(R.string.subagents_title),
        subtitle = when {
            subagents.isEmpty() -> stringResource(R.string.subagents_empty)
            running > 0 -> stringResource(R.string.subagents_running)
            else -> subagents.size.toString()
        },
        clickable = subagents.isNotEmpty(),
        expandable = subagents.isNotEmpty(),
        expanded = expanded,
        onClick = if (subagents.isNotEmpty()) ({ expanded = !expanded }) else null,
        trailing = if (running > 0) {
            { StateDot(StateDotState.Running) }
        } else {
            null
        },
    ) {
        subagents.forEach { entry ->
            val id = subagentId(entry)
            // Only a child with a transcript on this phone can be opened. An external one has no
            // local session to read, and a diagnostic is a note, so those rows are plain facts.
            val openable = id != null && subagentOpenable(entry)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DsShapes.row)
                    .then(
                        if (openable && id != null) {
                            Modifier.clickable(role = Role.Button) { onOpen(id) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = DsSpacing.xsmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                MascotMark(
                    size = 22.dp,
                    state = if (subagentRunning(entry)) MascotState.Working else MascotState.Idle,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        subagentLabel(entry) ?: id.orEmpty(),
                        style = DsType.small13,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            if (subagentRunning(entry)) R.string.subagents_running
                            else R.string.subagents_inactive,
                        ),
                        style = DsType.caption11,
                        color = colors.labelCaption,
                    )
                }
                if (openable) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.ink_panel_open_session),
                        modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = -90f },
                        tint = colors.labelTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(queue: List<QueueItem>, onRemove: (QueueItem) -> Unit) {
    val colors = DsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    ActivityRow(
        icon = FeatherIcons.List,
        title = stringResource(R.string.chat_queue_title),
        subtitle = queue.size.toString(),
        clickable = true,
        expandable = true,
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        queue.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.previewText,
                        style = DsType.small13,
                        color = colors.labelPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(item.placement, style = DsType.caption11, color = colors.labelCaption)
                }
                DsButton(
                    text = stringResource(R.string.common_remove),
                    onClick = { onRemove(item) },
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun WorkflowRow(workflows: List<WorkflowView>) {
    val colors = DsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    ActivityRow(
        icon = FeatherIcons.Activity,
        title = stringResource(R.string.workflow_title),
        subtitle = workflows.size.toString(),
        clickable = true,
        expandable = true,
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        workflows.forEach { workflow ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        workflow.name,
                        style = DsType.small13,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.workflow_members, workflow.members),
                        style = DsType.caption11,
                        color = colors.labelCaption,
                    )
                }
                DsPill(
                    text = stringResource(
                        when (workflow.status) {
                            WorkflowStatus.Running -> R.string.workflow_running
                            WorkflowStatus.Completed -> R.string.workflow_completed
                            WorkflowStatus.Failed -> R.string.workflow_failed
                        },
                    ),
                    warn = workflow.status == WorkflowStatus.Failed,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Host
// ---------------------------------------------------------------------------

@Composable
private fun HostCard(hostInfo: HostDescription?, host: HostConfig?, models: SessionModelsValue?) {
    val colors = DsTheme.colors
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                FeatherIcons.Server,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.labelSecondary,
            )
            Spacer(Modifier.width(DsSpacing.small))
            Text(
                host?.name ?: stringResource(R.string.details_section_host),
                style = DsType.rowTitle,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            host?.let {
                Text(
                    technicalDisplay(it.displayAddress),
                    style = DsType.mono12,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        hostInfo?.home?.takeIf { it.isNotBlank() }?.let { home ->
            Text(
                stringResource(R.string.connect_harness_home, technicalDisplay(home)),
                style = DsType.mono12,
                color = colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            stringResource(R.string.connect_protocol_baseline, DshCore.PROTOCOL_BASELINE),
            style = DsType.caption11,
            color = colors.labelCaption,
        )
        // The model the host is serving is a host fact too: it says what the next session will run.
        modelDisplayName(models)?.let { name ->
            Text(name, style = DsType.caption11, color = colors.labelCaption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AutomationsCard(onClick: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Surface(
        onClick = {
            haptics.tap()
            onClick()
        },
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
        border = BorderStroke(1.dp, colors.borderL2),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = DsSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            Icon(FeatherIcons.Zap, contentDescription = null, Modifier.size(20.dp), tint = colors.labelSecondary)
            Text(
                stringResource(R.string.harness_automations),
                style = DsType.rowTitle,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = -90f },
                tint = colors.labelTertiary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Presentation helpers
// ---------------------------------------------------------------------------

/** The selected model's display name, looked up in the catalog; falls back to the wire id. */
@Composable
private fun modelDisplayName(models: SessionModelsValue?): String? {
    val current = models?.current ?: return null
    val group = models.groups.firstOrNull { it.id == current.provider }
    return group?.models?.firstOrNull { it.id == current.model }?.name
        ?: current.model.takeIf { it.isNotBlank() }
}

/** The effort name shown beside the model, in the model's own wording, or null when it has none. */
@Composable
private fun effortDisplayName(models: SessionModelsValue?): String? {
    val current = models?.current ?: return null
    val group = models.groups.firstOrNull { it.id == current.provider } ?: return current.reasoningEffort
    val model = group.models.firstOrNull { it.id == current.model } ?: return current.reasoningEffort
    // As in the picker and the top-bar chip: an explicit effort wins, and a selection that carries
    // none falls back to the model's adapter default instead of reading as "no tier".
    val effort = current.reasoningEffort ?: model.reasoning?.defaultEffort ?: return null
    return model.reasoning?.efforts?.firstOrNull { it.id == effort }?.name ?: effort
}

/** A short description for a preset the harness did not describe itself. */
@Composable
private fun panelPermissionDescription(value: String): String? = when (value) {
    "read-only", "read", "readonly" -> stringResource(R.string.ink_panel_permission_readonly_desc)
    FULL_ACCESS_PRESET -> stringResource(R.string.ink_panel_permission_full_desc)
    "workspace-write", "workspace", "write" -> stringResource(R.string.ink_panel_permission_workspace_desc)
    else -> null
}

// ---------------------------------------------------------------------------
// Parsing helpers local to the panel
// ---------------------------------------------------------------------------

/** Plan mode: the projection when present, otherwise the last `plan/mode` event seen. */
private fun parsePlanActive(conversation: ConversationSnapshot): Boolean? {
    val fromProjection = conversation.projections["plan"]?.let { element ->
        runCatching {
            val obj = element as? JsonObject ?: return@runCatching null
            obj["active"]?.jsonPrimitive?.booleanOrNull
                ?: obj["active"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        }.getOrNull()
    }
    return fromProjection
        ?: conversation.nodes.filterIsInstance<PlanModeNode>().lastOrNull()?.active
}

internal enum class WorkflowStatus { Running, Completed, Failed }

internal data class WorkflowView(
    val runId: String,
    val name: String,
    val status: WorkflowStatus,
    val members: Int,
)

private class WorkflowBuilder(val runId: String) {
    var name: String = ""
    val memberSeqs = mutableSetOf<String>()
    var status: WorkflowStatus? = null
    var failed = false
}

/** Fold the workflow event family into one row per run. */
internal fun parseWorkflows(nodes: List<ChatNode>): List<WorkflowView> {
    val builders = linkedMapOf<String, WorkflowBuilder>()
    for (node in nodes) {
        if (node !is WorkflowNode) continue
        val data = node.data as? JsonObject ?: continue
        val runId = data["runId"].asString() ?: continue
        val builder = builders.getOrPut(runId) { WorkflowBuilder(runId) }
        when (node.kind) {
            "tool-workflow/run-start" -> data["name"].asString()?.let { builder.name = it }
            "tool-workflow/agent-start" -> data["seq"].asString()?.let { builder.memberSeqs.add(it) }
            "tool-workflow/agent-end" -> {
                val outcome = data["outcome"].asString()
                if (outcome == "failed" || outcome == "cancelled") builder.failed = true
            }
            "tool-workflow/run-end" -> {
                val stop = data["stopReason"].asString()
                builder.status = if (stop == "completed") WorkflowStatus.Completed else WorkflowStatus.Failed
            }
        }
    }
    return builders.values.map { builder ->
        WorkflowView(
            runId = builder.runId,
            name = builder.name.ifBlank { builder.runId },
            status = builder.status ?: if (builder.failed) WorkflowStatus.Failed else WorkflowStatus.Running,
            members = builder.memberSeqs.size,
        )
    }
}
