package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ModelStepRecord
import com.labteto.dshmobile.core.session.RecordDetail
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.session.ToolRecord
import com.labteto.dshmobile.core.session.TrajectoryRecord
import com.labteto.dshmobile.core.session.TrajectoryTimeline
import com.labteto.dshmobile.core.session.TurnTiming
import com.labteto.dshmobile.core.session.rawRecordJson
import com.labteto.dshmobile.core.session.readRecordDetail
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsTextAction
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.components.formatTokens
import com.labteto.dshmobile.ui.components.rememberCopier
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Trajectory tab's one sheet is showing. */
internal sealed interface TrajectorySheetState {
    /** One turn: its summary, its bar and its records. */
    data class Turn(val turn: Int) : TrajectorySheetState

    /** One record. [fromTurn] is the turn list it was opened from, so Back can return to it. */
    data class Record(val key: String, val fromTurn: Int?) : TrajectorySheetState
}

/** Most characters "Show all" will lay out; Copy always has the whole text. */
private const val MAX_SHOWN_CHARS = 100_000

/**
 * The Trajectory tab's sheet: a turn's records, or one record's inspector.
 *
 * One sheet that changes content rather than a sheet on a sheet, so Back and the system back gesture
 * each do one obvious thing. Text is read from the journal here, off the main thread, and only for the
 * record that is open.
 */
@Composable
internal fun TrajectorySheet(
    state: TrajectorySheetState,
    timeline: TrajectoryTimeline,
    records: Map<String, TrajectoryRecord>,
    journal: List<SessionEventEnvelope>,
    onNavigate: (TrajectorySheetState) -> Unit,
    onDismiss: () -> Unit,
) {
    val turn = (state as? TrajectorySheetState.Turn)?.let { s -> timeline.turns.firstOrNull { it.turn == s.turn } }
    val record = (state as? TrajectorySheetState.Record)?.let { records[it.key] }
    val title = when (state) {
        is TrajectorySheetState.Turn -> stringResource(R.string.trajectory_turn, state.turn)
        is TrajectorySheetState.Record -> record?.let { recordTitle(it) } ?: stringResource(R.string.trajectory_title)
    }
    val subtitle = when {
        record != null -> stringResource(R.string.traj_insp_where, record.turn, record.step)
        turn?.wallMs != null -> formatDurationMs(turn.wallMs!!)
        else -> null
    }
    val back = (state as? TrajectorySheetState.Record)?.fromTurn
    DsBottomSheet(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss,
        trailing = back?.let {
            {
                DsIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    onClick = { onNavigate(TrajectorySheetState.Turn(it)) },
                )
            }
        },
    ) {
        when {
            record != null -> RecordInspector(record, journal)
            turn != null -> TurnRecords(turn, onOpen = { onNavigate(TrajectorySheetState.Record(it.key, turn.turn)) })
            else -> Text(
                stringResource(R.string.traj_insp_gone),
                style = DsType.std14,
                color = DsTheme.colors.labelSecondary,
            )
        }
    }
}

/**
 * How tall the scrolling body of the sheet may be: a share of the screen rather than a fixed number of
 * dp, so the title row and the close button are never pushed off a short phone or a large font.
 */
@Composable
private fun sheetBodyMaxHeight(): Dp = (LocalConfiguration.current.screenHeightDp * 0.55f).dp

@Composable
private fun recordTitle(record: TrajectoryRecord): String = when (record) {
    is ModelStepRecord -> stringResource(R.string.traj_insp_kind_model)
    is ToolRecord -> shellToolTitle(record.name) ?: technicalDisplay(record.name)
}

// ------------------------------------------------------------------------------------------------
// A turn's records
// ------------------------------------------------------------------------------------------------

@Composable
private fun TurnRecords(turn: TurnTiming, onOpen: (TrajectoryRecord) -> Unit) {
    val colors = DsTheme.colors
    LazyColumn(
        modifier = Modifier.heightIn(max = sheetBodyMaxHeight()),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        item(key = "summary") {
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                if (turn.slices.isNotEmpty()) {
                    TimingBar(turn.slices)
                    TimingLegend(turn.slices)
                }
                if (turn.open) FactRow(stringResource(R.string.traj_insp_status), stringResource(R.string.jobs_running))
                turn.wallMs?.let { FactRow(stringResource(R.string.traj_insp_duration), formatDurationMs(it)) }
                CountFacts(turn.modelSteps, turn.toolCalls, turn.failedToolCalls, turn.stoppedToolCalls)
                UsageFacts(turn.usage)
            }
        }
        item(key = "records-heading") {
            Text(
                stringResource(R.string.traj_turn_records),
                style = DsType.label12,
                color = colors.labelTertiary,
                modifier = Modifier
                    .padding(top = DsSpacing.small)
                    .semantics { heading() },
            )
        }
        if (turn.records.isEmpty()) {
            item(key = "none") {
                Text(stringResource(R.string.traj_turn_no_records), style = DsType.small13, color = colors.labelSecondary)
            }
        }
        items(turn.records, key = { it.key }) { record -> RecordRow(record) { onOpen(record) } }
    }
}

@Composable
private fun RecordRow(record: TrajectoryRecord, onOpen: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val summary = recordSummary(record)
    val running = when (record) {
        is ToolRecord -> !record.finished
        is ModelStepRecord -> !record.settled
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .clickable(role = Role.Button) {
                haptics.tap()
                onOpen()
            }
            .heightIn(min = DsSpacing.touchTarget)
            .padding(horizontal = DsSpacing.tiny, vertical = DsSpacing.xsmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                recordTitle(record),
                style = DsType.std14Strong,
                color = if (record.failed) colors.error else colors.labelPrimary,
            )
            summary?.let { Text(it, style = DsType.caption11, color = colors.labelTertiary) }
        }
        Column(horizontalAlignment = Alignment.End) {
            val duration = record.durationMs
            when {
                duration != null -> Text(formatDurationMs(duration), style = DsType.mono12, color = colors.labelTertiary)
                running -> Text(stringResource(R.string.jobs_running), style = DsType.mono12, color = colors.accent)
            }
            // A failure is said in words as well as drawn in red.
            if (record.failed) {
                Text(
                    stringResource(if (record is ModelStepRecord) R.string.traj_status_cut else R.string.traj_status_failed),
                    style = DsType.caption11Strong,
                    color = colors.error,
                )
            }
            // A call the user stopped is not a failure: its own icon and word, in the warning colour.
            if (record.stopped) StoppedLabel()
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = colors.labelCaption,
        )
    }
}

/** The second line of a row: what the record says about itself that is worth reading before opening it. */
@Composable
private fun recordSummary(record: TrajectoryRecord): String? = when (record) {
    is ModelStepRecord -> {
        val input = record.usage?.inputTokens
        val output = record.usage?.outputTokens
        if (input != null && output != null) {
            stringResource(R.string.traj_row_tokens, formatTokens(input), formatTokens(output))
        } else {
            null
        }
    }
    is ToolRecord -> record.approvalWaitMs?.let { stringResource(R.string.traj_row_approval, formatDurationMs(it)) }
}

// ------------------------------------------------------------------------------------------------
// One record
// ------------------------------------------------------------------------------------------------

@Composable
private fun RecordInspector(record: TrajectoryRecord, journal: List<SessionEventEnvelope>) {
    val latestJournal by rememberUpdatedState(journal)
    var detail by remember(record.key, record.eventSeqs) { mutableStateOf<RecordDetail?>(null) }
    // Reading and pretty-printing a large input or output is real work: never on the main thread.
    LaunchedEffect(record.key, record.eventSeqs) {
        detail = withContext(Dispatchers.Default) { readRecordDetail(record, latestJournal) }
    }
    var showRaw by remember(record.key) { mutableStateOf(false) }
    var raw by remember(record.key, record.eventSeqs) { mutableStateOf<String?>(null) }
    LaunchedEffect(showRaw, record.key, record.eventSeqs) {
        raw = if (showRaw) withContext(Dispatchers.Default) { rawRecordJson(record, latestJournal) } else null
    }
    Column(
        modifier = Modifier
            .heightIn(max = sheetBodyMaxHeight())
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        RecordFacts(record)
        val loaded = detail
        if (loaded == null) {
            FeatureProgress()
            Text(stringResource(R.string.traj_insp_loading), style = DsType.small13, color = DsTheme.colors.labelTertiary)
        } else {
            loaded.input?.let { BoundedText(stringResource(R.string.traj_insp_input), it, mono = true) }
            loaded.output?.let { BoundedText(stringResource(R.string.traj_insp_output), it, mono = record is ToolRecord) }
            loaded.thinking?.let { BoundedText(stringResource(R.string.traj_insp_reasoning), it, mono = false) }
            if (loaded.requestedTools.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                    InspectorHeading(stringResource(R.string.traj_insp_tools))
                    Text(
                        technicalDisplay(loaded.requestedTools.joinToString(", ") { shellToolTitle(it) ?: it }),
                        style = DsType.mono12,
                        color = DsTheme.colors.labelSecondary,
                    )
                }
            }
        }
        // The raw journal events are the power-user view: one explicit action away, never the default.
        DsTextAction(
            label = stringResource(if (showRaw) R.string.traj_insp_raw_hide else R.string.traj_insp_raw_show),
            onClick = { showRaw = !showRaw },
        )
        if (showRaw) {
            val text = raw
            if (text == null) FeatureProgress() else BoundedText(stringResource(R.string.trajectory_tab_raw), text, mono = true)
        }
    }
}

@Composable
private fun RecordFacts(record: TrajectoryRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FactRow(
            stringResource(R.string.traj_insp_kind),
            stringResource(if (record is ModelStepRecord) R.string.traj_insp_kind_model else R.string.traj_insp_kind_tool),
        )
        val status = when {
            record is ModelStepRecord && record.interrupted -> R.string.traj_status_cut
            record.stopped -> R.string.chat_stopped
            record is ToolRecord && record.isError -> R.string.traj_status_failed
            record is ModelStepRecord && !record.settled -> R.string.jobs_running
            record is ToolRecord && !record.finished -> R.string.jobs_running
            else -> R.string.traj_status_done
        }
        if (record.stopped) {
            // The status of a stopped call is a word with its own icon, never only a colour.
            StoppedStatusRow(stringResource(R.string.traj_insp_status))
        } else {
            FactRow(stringResource(R.string.traj_insp_status), stringResource(status))
        }
        record.startMs?.let { FactRow(stringResource(R.string.traj_insp_started), clockText(it)) }
        record.durationMs?.let { FactRow(stringResource(R.string.traj_insp_duration), formatDurationMs(it)) }
        when (record) {
            is ModelStepRecord -> {
                record.firstTokenWaitMs?.let { FactRow(stringResource(R.string.traj_insp_first_token), formatDurationMs(it)) }
                record.generationMs?.let { FactRow(stringResource(R.string.traj_insp_generation), formatDurationMs(it)) }
                record.outputTokensPerSecond?.let {
                    FactRow(stringResource(R.string.traj_insp_speed), stringResource(R.string.traj_insp_speed_value, it))
                }
                (record.model ?: record.provider)?.let { FactRow(stringResource(R.string.traj_insp_model), technicalDisplay(it)) }
                UsageFacts(record.usage)
            }
            is ToolRecord -> {
                record.approvalWaitMs?.let { FactRow(stringResource(R.string.traj_insp_approval), formatDurationMs(it)) }
                // The error's name and code are for a failure; a stop is not one.
                if (record.failed) {
                    listOfNotNull(record.errorName, record.errorCode).takeIf { it.isNotEmpty() }?.let {
                        FactRow(stringResource(R.string.traj_insp_error), technicalDisplay(it.joinToString(" ")))
                    }
                }
            }
        }
    }
}

/** The local date and time of a recorded instant, formatted for the device's locale. */
@Composable
private fun clockText(ms: Long): String = remember(ms) {
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM)
        .format(java.util.Date(ms))
}

@Composable
private fun InspectorHeading(text: String) {
    Text(
        text,
        style = DsType.label12,
        color = DsTheme.colors.labelTertiary,
        modifier = Modifier.semantics { heading() },
    )
}

/**
 * A long text with its start shown, "Show all" for the rest, and Copy for all of it.
 *
 * The start is cut by [clipPreview], which scans only the first few thousand characters, so a result of
 * megabytes costs nothing until asked for. Even "Show all" lays out at most [MAX_SHOWN_CHARS], with a
 * note that says so; Copy never truncates.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoundedText(title: String, text: String, mono: Boolean) {
    val colors = DsTheme.colors
    val copy = rememberCopier()
    var showAll by remember(text) { mutableStateOf(false) }
    val capped = text.length > MAX_SHOWN_CHARS
    val shown = remember(text, showAll) {
        when {
            !showAll -> clipPreview(text)
            capped -> text.take(MAX_SHOWN_CHARS)
            else -> text
        }
    }
    val clipped = !showAll && shown.length != text.length
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                title,
                style = DsType.label12,
                color = colors.labelTertiary,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .semantics { heading() },
            )
            DsTextAction(stringResource(R.string.common_copy), onClick = { copy(text) })
            if (clipped) DsTextAction(stringResource(R.string.work_steps_show_all), onClick = { showAll = true })
        }
        SelectionContainer {
            Text(
                shown,
                style = if (mono) DsType.mono12.copy(textDirection = TextDirection.Ltr) else DsType.small13,
                color = colors.labelSecondary,
            )
        }
        if (showAll && capped) {
            Text(stringResource(R.string.traj_insp_truncated), style = DsType.caption11, color = colors.labelCaption)
        }
    }
}
