package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import com.labteto.dshmobile.data.HarnessFeature
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.components.*
import com.labteto.dshmobile.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * The full-screen management page shared by Automations and Plugins.
 *
 * The design draws these as pushed screens with a back bar, not as bottom sheets, so this is a
 * [DsFullScreenDialog]: it keeps the window-level stacking the sheets had (every nested dialog and
 * picker still opens over the right layer) while reading as a page, and it pads for the system
 * bars, so the [footer] clears the gesture bar. The content rises in once on mount and never
 * animates again.
 */
@Composable
internal fun FeaturePage(
    title: String,
    onClose: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    subtitle: String? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    DsFullScreenDialog(onDismissRequest = onClose) {
        Row(
            Modifier.fillMaxWidth().padding(
                start = DsSpacing.small,
                end = DsSpacing.small,
                top = DsSpacing.small,
                bottom = DsSpacing.xsmall,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                onClick = onClose,
            )
            Column(Modifier.weight(1f).padding(horizontal = DsSpacing.xsmall)) {
                Text(
                    title,
                    style = DsType.large20,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onRefresh != null) {
                DsIconButton(
                    icon = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.ux_a_refresh),
                    onClick = onRefresh,
                )
            }
        }
        AnimatedVisibility(
            visible = shown,
            modifier = Modifier.weight(1f),
            enter = fadeIn(tween(180, easing = DsAnimations.inkDecelerate)) +
                slideInVertically(tween(240, easing = DsAnimations.inkDecelerate)) { it / 30 },
        ) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                    content = content,
                )
                footer?.let {
                    Box(Modifier.fillMaxWidth().padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small)) {
                        it()
                    }
                }
            }
        }
    }
}

/** The Ink input: filled layer-2 plate, hairline border, accent focus ring. */
@Composable
internal fun FeatureField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    technical: Boolean = false,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().inkFieldFrame(),
        enabled = enabled,
        singleLine = singleLine,
        label = { Text(stringResource(label)) },
        textStyle = if (technical) {
            DsType.mono13.copy(textDirection = TextDirection.Ltr)
        } else {
            DsType.std14
        },
        colors = dialogTextFieldColors(),
        shape = DsShapes.control,
    )
}

@Composable
internal fun FeatureProgress() = LinearProgressIndicator(
    Modifier.fillMaxWidth(),
    color = DsTheme.colors.accent,
    trackColor = DsTheme.colors.borderL1,
)

@Composable
internal fun FeatureAction(text: String, enabled: Boolean = true, variant: DsButtonVariant = DsButtonVariant.Outline, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget), contentAlignment = Alignment.CenterStart) {
        DsButton(
            text,
            onClick,
            enabled = enabled,
            variant = variant,
            allowWrap = true,
        )
    }
}

@Composable
internal fun FeatureDiagnostics(text: String?) {
    if (text.isNullOrBlank()) return
    val colors = DsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    DisclosureRow(
        title = stringResource(R.string.ux_a_details),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = Modifier.heightIn(min = DsSpacing.touchTarget),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = DsSpacing.xsmall)
                .clip(DsShapes.code)
                .background(colors.codeBlockBg)
                .border(1.dp, colors.borderL1, DsShapes.code)
                .padding(DsSpacing.small),
        ) {
            SelectionContainer {
                Text(text, style = DsType.mono12, color = colors.labelSecondary, softWrap = true)
            }
        }
    }
}

@Composable
internal fun scheduleFormatter(): ScheduleFormatter {
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    return remember(resources, locale) { ScheduleFormatter(locale, ZoneId.systemDefault(),
        { id, args -> resources.getString(id, *args.toTypedArray()) },
        { id, count, number -> resources.getQuantityString(id, count, number) }) }
}

/** The stored rule in words, its next/last run, and whether it is live. */
@Composable
internal fun ScheduleSummary(task: AutomationTask) {
    val colors = DsTheme.colors
    val formatter = scheduleFormatter()
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        Text(formatter.frequency(task), style = DsType.small13, color = colors.labelSecondary)
        formatter.runSummary(task)?.let { Text(it, style = DsType.caption11, color = colors.labelTertiary) }
        DsPill(stringResource(if (task.status == "active") R.string.harness_active else R.string.harness_inactive))
    }
}

/** New tasks are drafted through the agent; the management API has no create endpoint. */
internal suspend fun SessionStore.stageFeatureDraft(host: String, text: String, preset: String? = null): Boolean {
    val api = apiForHost(host) ?: return false
    val id = api.sessionCreate(SessionCreateRequest()).featureValue().sessionId
    if (activeHostKey != host) return false
    if (preset != null) api.agentPresetSelect(id, preset).featureValue()
    if (activeHostKey != host) return false
    composers.get(ComposerKey(host, id)).text = text
    openSession(id)
    return activeHostKey == host && currentSessionId.value == id
}

@Composable
internal fun AutomationScreen(sessionId: String? = null, onClose: () -> Unit, onUnsupported: () -> Unit = onClose) {
    val store = rememberSessionStore()
    val available by store.automationAvailable.collectAsStateWithLifecycle()
    // MainScreen owns the single unavailable toast and resets its open state.
    LaunchedEffect(available) { if (available == false) onUnsupported() }
    if (available == false) return
    val connection by store.connectionState.collectAsStateWithLifecycle()
    val host = store.activeHostKey
    val scope = rememberCoroutineScope()
    var tasks by remember(host, sessionId) { mutableStateOf<List<AutomationTask>>(emptyList()) }
    var selected by remember(host, sessionId) { mutableStateOf<AutomationTask?>(null) }
    var search by remember(host, sessionId) { mutableStateOf("") }
    var statusFilter by remember(host, sessionId) { mutableStateOf(AutomationStatusFilter.ALL) }
    val listModel = remember(tasks, search, statusFilter) { automationListModel(tasks, search, statusFilter) }
    var error by remember(host) { mutableStateOf<String?>(null) }
    var busy by remember(host) { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val offline = stringResource(R.string.common_offline)
    val newPrompt = stringResource(R.string.harness_automation_draft)
    suspend fun refresh() {
        val api = store.apiForHost(host) ?: error(offline)
        val result = store.featureCall(HarnessFeature.AUTOMATION, api) { api.automationCatalog() }.featureValue()
        // 0.2.2 moved the delivery Session into `target`; `belongsToSession` reads both vocabularies.
        if (host == store.activeHostKey) tasks = result.filter { sessionId == null || it.belongsToSession(sessionId) }
    }
    fun run(block: suspend () -> Unit) { scope.launch {
        busy = true; error = null
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: offline }
        finally { busy = false }
    } }
    LaunchedEffect(host, sessionId, connection.phase, revision) { run { refresh() } }
    LaunchedEffect(host) { store.hostEvents.collect { if (it.first == "schedule/changed") revision++ } }
    FeaturePage(
        title = stringResource(R.string.harness_automations),
        subtitle = stringResource(R.string.ink_files_automations_intro),
        onClose = onClose,
        onRefresh = { if (!busy) revision++ },
        footer = {
            DsButton(
                text = stringResource(R.string.ink_files_new_automation),
                icon = Icons.Filled.Add,
                onClick = {
                    run {
                        if (host != null && store.stageFeatureDraft(host, newPrompt)) onClose() else error(offline)
                    }
                },
                enabled = !busy,
                variant = DsButtonVariant.Primary,
                allowWrap = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        DsSearchField(search, { search = it }, stringResource(R.string.harness_automation_search))
        DsSegmented(
            AutomationStatusFilter.entries.map { filter ->
                DsSegment(filter.name, stringResource(when (filter) {
                    AutomationStatusFilter.ALL -> R.string.harness_automation_all
                    AutomationStatusFilter.ACTIVE -> R.string.harness_active
                    AutomationStatusFilter.INACTIVE -> R.string.harness_inactive
                }))
            },
            statusFilter.name,
            { statusFilter = AutomationStatusFilter.valueOf(it) },
            stretch = true,
            minSegmentHeight = DsSpacing.touchTarget,
            maxLabelLines = Int.MAX_VALUE,
        )
        if (busy) FeatureProgress()
        error?.let { diagnostic -> DsCard {
            Text(stringResource(R.string.ux_a_load_error), color = DsTheme.colors.error, style = DsType.std14)
            FeatureAction(stringResource(R.string.common_retry), !busy) { run { refresh() } }
            FeatureDiagnostics(diagnostic)
        } }
        if (!busy && error == null) listModel.emptyState?.let { empty ->
            // The headline is a short phrase. The sentence about where tasks come from is the
            // subtitle: set as the headline it filled the screen as a three-line hero.
            EmptyHero(
                headline = stringResource(when (empty) {
                    AutomationEmptyState.NO_TASKS -> R.string.harness_automation_empty
                    AutomationEmptyState.NO_MATCHES -> R.string.harness_automation_no_matches
                    AutomationEmptyState.NO_INACTIVE_TASKS -> R.string.harness_automation_empty_inactive
                }),
                subtitle = if (empty == AutomationEmptyState.NO_TASKS) stringResource(R.string.harness_automation_empty_hint) else null,
                showPreview = false,
            )
        }
        listModel.tasks.forEach { task ->
            AutomationCard(task) { selected = task }
        }
    }
    selected?.let { task -> key(host, task.sessionIdEffective, task.id) {
        AutomationEditor(store, host, task, onClose = { selected = null; revision++ }, onSessionOpened = onClose)
    } }
}

/** One scheduled task: the rule, when it next runs, and whether it is live. */
@Composable
private fun AutomationCard(task: AutomationTask, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val formatter = scheduleFormatter()
    val active = task.status == "active"
    val lastRun = task.lastDelivery?.let { stringResource(R.string.ux_a_last_run, formatter.absolute(it.deliveredAt)) }
    val next = if (active) runCatching { formatter.nextRelative(task.scheduledAt) }.getOrNull() else null
    DsCard(
        onClick = onClick,
        verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(DsShapes.buttonSmall)
                    .background(colors.bgLayer3),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.accent)
            }
            Spacer(Modifier.width(DsSpacing.medium))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = DsType.rowTitle,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (!active) TextDecoration.LineThrough else null,
                )
                Text(
                    formatter.frequency(task),
                    style = DsType.mono12,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(DsSpacing.small))
            DsPill(
                text = stringResource(if (active) R.string.harness_active else R.string.harness_inactive),
                selected = active,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(DsShapes.pillFull)
                    .background(if (active) colors.success else colors.labelDimmed),
            )
            Text(
                lastRun ?: task.prompt,
                style = DsType.small13,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (active && next != null) {
                Text(next, style = DsType.mono12, color = colors.labelTertiary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AutomationEditor(store: SessionStore, host: String?, task: AutomationTask, onClose: () -> Unit, onSessionOpened: () -> Unit) {
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    var title by remember(task) { mutableStateOf(task.title) }
    var prompt by remember(task) { mutableStateOf(task.prompt) }
    var timing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TimingEditorDraft.from(task)) }
    var tab by remember(task.sessionIdEffective, task.id) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val archived by store.archivedSessionIds.collectAsStateWithLifecycle()
    val connection by store.connectionState.collectAsStateWithLifecycle()
    // 0.2.2 keeps the delivery Session in `target` (`sessionIdEffective`); a `new` target creates a
    // fresh Session per delivery and names none, so there is no single Session to open or check.
    val deliverySession = task.sessionIdEffective
    val linkMessage = when {
        deliverySession == null -> null
        connection.phase == com.labteto.dshmobile.connection.ConnectionPhase.DISCONNECTED -> R.string.common_offline
        connection.phase != com.labteto.dshmobile.connection.ConnectionPhase.CONNECTED -> R.string.common_loading
        deliverySession in archived -> R.string.ux_a_session_archived
        sessions.none { it.sessionId == deliverySession } -> R.string.ux_a_session_unavailable
        else -> null
    }
    val offline = stringResource(R.string.common_offline)
    val failure = stringResource(R.string.ux_a_action_error)
    val saveFailure = stringResource(R.string.ux_a_save_error)
    val deleteFailure = stringResource(R.string.ux_a_delete_error)
    val conflict = stringResource(R.string.harness_conflict)
    val notFound = stringResource(R.string.harness_automation_history_not_found)
    val active = task.status == "active"
    fun run(errorText: String = failure, block: suspend (DshApiClient) -> Unit) { scope.launch {
        busy = true; message = null; diagnostics = null
        try { block(store.apiForHost(host) ?: error(offline)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = errorText; diagnostics = e.message }
        finally { busy = false }
    } }
    FeaturePage(title = task.title, onClose = onClose) {
        DsSegmented(
            listOf(
                DsSegment("0", stringResource(R.string.harness_automation_rules)),
                DsSegment("1", stringResource(R.string.harness_automation_records)),
            ),
            tab.toString(),
            {
                tab = it.toInt()
            },
            enabled = !busy,
            stretch = true,
            minSegmentHeight = DsSpacing.touchTarget,
            maxLabelLines = Int.MAX_VALUE,
        )
        if (tab == 1) AutomationRecords(store, host, task) else {
            SectionLabel(stringResource(R.string.harness_automation_rules))
            FeatureField(title, { title = it }, R.string.harness_title, active && !busy)
            FeatureField(prompt, { prompt = it }, R.string.harness_prompt, active && !busy)
            DsCard {
                ScheduleSummary(task)
            }
            if (deliverySession != null) {
                FeatureAction(stringResource(R.string.harness_open_session), !busy && linkMessage == null) {
                    run {
                        if (host == store.activeHostKey) {
                            store.openSession(deliverySession)
                            if (host == store.activeHostKey && store.currentSessionId.value == deliverySession) onSessionOpened()
                        }
                    }
                }
                linkMessage?.let { Text(stringResource(it), style = DsType.small13, color = colors.labelSecondary) }
            }
            if (active) {
                // `rrule` tasks have no timing editor (harness 0.2.2 uses `patch.timing = {kind,rrule}`
                // for that); the rule is still shown read-only by ScheduleSummary above.
                if (draft.timingEditable) {
                    SectionLabel(stringResource(R.string.harness_change_timing))
                    ToggleRow(stringResource(R.string.harness_change_timing), timing, enabled = !busy) {
                        timing = !timing
                    }
                    if (timing) AutomationTimingFields(draft, { draft = it }, !busy)
                }
                DsButton(
                    text = stringResource(R.string.common_save),
                    onClick = {
                        run(saveFailure) { api ->
                            val result = store.featureCall(HarnessFeature.AUTOMATION, api) {
                                api.automationEdit(AutomationEditRequest(
                                    id = task.id,
                                    action = "update",
                                    patch = AutomationPatch(
                                        title = title.trim(),
                                        prompt = prompt.trim(),
                                        timing = if (timing && draft.timingEditable) draft.change() else null,
                                    ),
                                    // 0.2.2 compares `updatedAt` instead of 0.2.1's complete `expected` record.
                                    expectedUpdatedAt = task.updatedAt,
                                ))
                            }.featureValue()
                            // 0.2.2 answers an update with the whole catalog entry, so `id` without a
                            // code is the success signal; `record` remains 0.2.1's shape at no cost.
                            // Closing drops the cached row and re-reads the catalog for the new revision.
                            if (result.code == null && result.id != null) onClose() else {
                                message = when (result.code) { "schedule_conflict" -> conflict; "schedule_not_found" -> notFound; else -> saveFailure }
                                diagnostics = listOfNotNull(result.code, result.message).joinToString("\n")
                            }
                        }
                    },
                    enabled = !busy && title.trim().isNotEmpty() && title.trim().length <= 120 && prompt.trim().isNotEmpty() && (!timing || draft.valid),
                    variant = DsButtonVariant.Primary,
                    allowWrap = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    DsButton(
                        text = stringResource(R.string.common_delete),
                        onClick = { deleteConfirm = true },
                        enabled = !busy,
                        variant = DsButtonVariant.Danger,
                        allowWrap = true,
                    )
                }
            }
            if (busy) FeatureProgress()
            message?.let { text -> DsCard {
                Text(text, color = colors.error, style = DsType.std14)
                FeatureAction(stringResource(R.string.ux_a_refresh), !busy, onClick = onClose)
                FeatureDiagnostics(diagnostics)
            } }
        }
    }
    if (deleteConfirm) DsDialog(stringResource(R.string.common_delete), { deleteConfirm = false }) {
        Text(task.title, style = DsType.std14, color = colors.labelPrimary)
        Text(
            stringResource(R.string.ux_a_delete_error),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        FeatureAction(stringResource(R.string.common_delete), variant = DsButtonVariant.Danger) {
            deleteConfirm = false
            run(deleteFailure) { api ->
                // 0.2.2 folds delete into `schedule/edit`; the expected revision still guards the write.
                val result = store.featureCall(HarnessFeature.AUTOMATION, api) {
                    api.automationEdit(AutomationEditRequest(task.id, "delete", expectedUpdatedAt = task.updatedAt))
                }.featureValue()
                if (result.deleted == true) onClose() else { message = if (result.code == "schedule_not_found") notFound else deleteFailure; diagnostics = listOfNotNull(result.code, result.message).joinToString("\n") }
            }
        }
        FeatureAction(stringResource(R.string.common_cancel)) { deleteConfirm = false }
    }
}

/** Uppercase section label used inside management pages. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = DsType.label12,
        color = DsTheme.colors.labelTertiary,
        modifier = Modifier.padding(top = DsSpacing.tiny),
    )
}

