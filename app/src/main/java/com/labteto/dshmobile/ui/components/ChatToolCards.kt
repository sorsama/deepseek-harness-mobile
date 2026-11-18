package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * What a card can do beyond showing its call, supplied by the transcript: open the job a background
 * command started, or the child a subagent call started, and the live state of either. Absent
 * actions draw no control.
 */
@Immutable
data class ToolCardActions(
    val openJob: (() -> Unit)? = null,
    /** The started job's live state in words, when the jobs list still has it. */
    val jobState: String? = null,
    val jobRunning: Boolean = false,
    val openSubagent: (() -> Unit)? = null,
    /** Whether the started child is working now; null when the subagent list does not know it. */
    val subagentRunning: Boolean? = null,
) {
    companion object {
        val None = ToolCardActions()
    }
}

/** Technical text (a command, a path, an id): kept left to right inside a right-to-left line. */
private fun technical(value: String): String = com.labteto.dshmobile.ui.screens.main.technicalDisplay(value)

/**
 * Whether a card draws a body to open. A header-only card leaves its subject to its own node, and a
 * generic card with no arguments, locations or content has nothing of its own to put in the box (the
 * tool's text is the transcript's Output row, drawn outside the card).
 */
internal fun ToolCardView.hasBody(): Boolean = when (this) {
    is TodoCard -> lines.isNotEmpty() || !recorded
    is DetailsCard -> !headerOnly
    is ToolCardView.GenericCard -> hasArguments(rawInput) || !locations.isNullOrEmpty() || !content.isNullOrEmpty()
    else -> true
}

// ---- Header ---------------------------------------------------------------------------------

internal fun ToolCardView.controlIcon(): ImageVector? = when (this) {
    is TodoCard -> FeatherIcons.List
    is QuestionCard -> FeatherIcons.Info
    is SubagentCard -> FeatherIcons.GitBranch
    is SkillCard -> FeatherIcons.Layers
    is ImageCard -> FeatherIcons.Image
    is DetailsCard -> when (tool) {
        DetailsTool.JobList, DetailsTool.JobOutput, DetailsTool.JobKill,
        DetailsTool.TerminalOpen, DetailsTool.TerminalRead, DetailsTool.TerminalList,
        DetailsTool.TerminalSignal, DetailsTool.TerminalClose -> FeatherIcons.Terminal
        DetailsTool.Lsp -> FeatherIcons.Code
        DetailsTool.Present -> FeatherIcons.FileText
        else -> FeatherIcons.Activity
    }
    else -> null
}

/** The header's summary for a control card, or null for the row model's own. */
@Composable
internal fun ToolCardView.controlSummary(): String? = when (this) {
    is TodoCard -> todoSummary()
    is QuestionCard -> questionOutcomeText()
    is SubagentCard -> listOfNotNull(title, subagentStateText()).joinToString(" · ")
    is SkillCard -> technical(name)
    is ImageCard -> null
    is DetailsCard -> detailsSummary()
    else -> null
}

@Composable
private fun TodoCard.todoSummary(): String {
    val parts = mutableListOf(stringResource(R.string.chat_tool_todo_progress, done, total))
    if (recorded && !initial) {
        val changes = listOfNotNull(
            added.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.chat_tool_todo_added, it, it) },
            updated.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.chat_tool_todo_changed, it, it) },
            removed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.chat_tool_todo_removed, it, it) },
        )
        parts += changes.ifEmpty { listOf(stringResource(R.string.chat_tool_todo_no_changes)) }
    }
    active?.let { parts += if (activeExtra > 0) "$it +$activeExtra" else it }
    return parts.joinToString(" · ")
}

@Composable
private fun QuestionCard.questionOutcomeText(): String = when (outcome) {
    QuestionOutcome.Waiting -> stringResource(R.string.chat_tool_ask_waiting)
    QuestionOutcome.Answered -> stringResource(R.string.chat_tool_ask_answered, answered, total)
    QuestionOutcome.Cancelled -> stringResource(R.string.chat_tool_ask_cancelled)
    QuestionOutcome.Interrupted -> stringResource(R.string.chat_tool_ask_interrupted)
    QuestionOutcome.TimedOut -> stringResource(R.string.chat_tool_ask_timed_out)
    QuestionOutcome.CarriedOn -> stringResource(R.string.chat_tool_ask_carried_on)
    QuestionOutcome.Settled -> stringResource(R.string.chat_tool_ask_closed)
}

/** The subagent's state: its call's own phase, refined by the live list once it has started. */
@Composable
private fun SubagentCard.subagentStateText(running: Boolean? = null): String = when (phase) {
    SubagentPhase.Starting -> stringResource(R.string.chat_tool_subagent_starting)
    SubagentPhase.Failed -> stringResource(R.string.chat_tool_subagent_failed)
    SubagentPhase.Stopped -> stringResource(R.string.chat_stopped)
    SubagentPhase.Replied -> stringResource(R.string.chat_tool_subagent_replied)
    SubagentPhase.Started -> when (running) {
        true -> stringResource(R.string.chat_tool_subagent_working)
        false -> stringResource(R.string.chat_tool_subagent_finished)
        null -> stringResource(R.string.chat_tool_subagent_started)
    }
}

@Composable
private fun DetailsCard.detailsSummary(): String? {
    val first = items.firstOrNull()
    val state = first?.let { detailStateText(it) }
    return when (tool) {
        DetailsTool.JobList -> if (items.isEmpty()) stringResource(R.string.chat_tool_jobs_none)
            else pluralStringResource(R.plurals.chat_tool_jobs_count, items.size, items.size)
        DetailsTool.TerminalList -> if (items.isEmpty()) stringResource(R.string.chat_tool_terminals_none)
            else pluralStringResource(R.plurals.chat_tool_terminals_count, items.size, items.size)
        DetailsTool.ScheduleList -> if (items.isEmpty()) stringResource(R.string.chat_tool_reminders_none)
            else pluralStringResource(R.plurals.chat_tool_reminders_count, items.size, items.size)
        DetailsTool.Lsp -> {
            val places = items.count { it.technicalTitle }
            val hover = items.singleOrNull()?.description != null && first?.fields.isNullOrEmpty()
            listOfNotNull(
                summary?.let(::technical),
                when {
                    hover -> null
                    places == 0 -> stringResource(R.string.chat_tool_places_none)
                    else -> pluralStringResource(R.plurals.chat_tool_places_count, places, places)
                },
            ).joinToString(" · ")
        }
        DetailsTool.GoalView -> if (items.isEmpty()) stringResource(R.string.chat_tool_goal_none)
            else listOfNotNull(first?.title, state).joinToString(" · ")
        DetailsTool.Present -> summary?.let(::technical) ?: pluralStringResource(R.plurals.chat_tool_files_count, items.size, items.size)
        DetailsTool.ScheduleCreate, DetailsTool.ScheduleUpdate ->
            listOfNotNull(summary, first?.fields?.firstOrNull { it.label == DetailLabel.When }?.let { fieldValueText(it.value) }).joinToString(" · ")
        DetailsTool.GoalCreate, DetailsTool.GoalUpdate ->
            listOfNotNull(summary, state).joinToString(" · ").ifEmpty { null }
        else -> listOfNotNull(
            summary?.let { if (first?.technicalTitle == true) technical(it) else it },
            state,
        ).joinToString(" · ").ifEmpty { null }
    }
}

// ---- Bodies ---------------------------------------------------------------------------------

/** The body of a control card, inside the shared card frame. */
@Composable
internal fun ControlCardBody(view: ToolCardView, actions: ToolCardActions) {
    when (view) {
        is TodoCard -> TodoBody(view)
        is QuestionCard -> QuestionBody(view)
        is SubagentCard -> SubagentBody(view, actions)
        is SkillCard -> SkillBody(view)
        is ImageCard -> ImageBody(view)
        is DetailsCard -> DetailsBody(view)
        else -> Unit
    }
}

/** A to-do's mark as a glyph whose shape, not only its colour, says where it stands. */
private fun TodoMark.glyph(): String = when (this) {
    TodoMark.Pending -> "○"
    TodoMark.InProgress -> "◐"
    TodoMark.Completed -> "✓"
}

@Composable
private fun TodoMark.label(): String = when (this) {
    TodoMark.Pending -> stringResource(R.string.chat_tool_todo_pending)
    TodoMark.InProgress -> stringResource(R.string.chat_tool_todo_in_progress)
    TodoMark.Completed -> stringResource(R.string.chat_tool_todo_done)
}

@Composable
private fun TodoBody(card: TodoCard) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!card.recorded) Caption(stringResource(R.string.chat_tool_todo_not_saved))
        card.lines.forEach { line ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    line.mark.glyph(),
                    style = DsType.small13Strong,
                    color = when (line.mark) {
                        TodoMark.Completed -> colors.success
                        TodoMark.InProgress -> colors.accent
                        TodoMark.Pending -> colors.labelTertiary
                    },
                    modifier = Modifier.width(20.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        line.content,
                        style = DsType.small13,
                        color = if (line.change == TodoChange.Removed) colors.labelTertiary else colors.labelPrimary,
                    )
                    val note = when (line.change) {
                        TodoChange.Added -> stringResource(R.string.chat_tool_todo_change_added) + " · " + line.mark.label()
                        TodoChange.Moved -> stringResource(R.string.chat_tool_todo_change_moved) + " · " + line.mark.label()
                        TodoChange.Removed -> stringResource(R.string.chat_tool_todo_change_removed)
                        TodoChange.StatusChanged -> line.mark.label() + (line.previous?.let {
                            " · " + stringResource(R.string.chat_tool_todo_change_was, it.label())
                        }.orEmpty())
                        null -> line.mark.label()
                    }
                    Caption(note)
                }
            }
        }
    }
}

@Composable
private fun QuestionBody(card: QuestionCard) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        card.questions.forEach { q ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                q.header?.takeIf { it.isNotBlank() }?.let { Caption(it) }
                Text(q.question, style = DsType.small13Strong, color = colors.labelPrimary)
                val answers = q.answers
                if (answers == null) {
                    q.options.forEach { option ->
                        Text("·  $option", style = DsType.small13, color = colors.labelTertiary)
                    }
                } else if (answers.isEmpty()) {
                    Text(stringResource(R.string.chat_tool_ask_not_answered), style = DsType.small13, color = colors.labelTertiary)
                } else {
                    Text(
                        stringResource(R.string.chat_tool_ask_your_answer, answers.joinToString(", ")),
                        style = DsType.small13,
                        color = colors.labelSecondary,
                    )
                }
            }
        }
        // How the questions ended ("You closed these questions") is said once, in the row's summary,
        // which is what a closed row shows; it used to be said again at the foot of the card.
    }
}

@Composable
private fun SubagentBody(card: SubagentCard, actions: ToolCardActions) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        card.prompt?.let { Text(it, style = DsType.small13, color = colors.labelSecondary) }
        // The model's name is the harness's text: its own left-to-right run after the app's label.
        card.model?.let {
            com.labteto.dshmobile.ui.screens.main.LabeledTechnicalText(
                label = stringResource(R.string.chat_tool_subagent_model_label),
                value = it,
                style = DsType.caption11,
                color = colors.labelCaption,
            )
        }
        val running = actions.subagentRunning
        val dot = when {
            card.phase == SubagentPhase.Stopped -> StateDotState.Warning
            card.phase == SubagentPhase.Failed -> StateDotState.Error
            card.phase == SubagentPhase.Starting || running == true -> StateDotState.Running
            card.phase == SubagentPhase.Started && running == null -> StateDotState.Idle
            else -> StateDotState.Done
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StateDot(dot, size = 8.dp)
            Text(card.subagentStateText(running), style = DsType.caption11Strong, color = colors.labelSecondary)
        }
        val open = actions.openSubagent
        if (open != null && card.childId != null) OpenRow(stringResource(R.string.chat_tool_subagent_open), FeatherIcons.GitBranch, open)
    }
}

@Composable
private fun SkillBody(card: SkillCard) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(technical(card.name), style = DsType.small13Strong.copy(fontFamily = DsType.codeFont), color = colors.labelPrimary)
        card.description?.let { Text(it, style = DsType.small13, color = colors.labelSecondary) }
        Caption(
            stringResource(
                when (card.phase) {
                    SkillPhase.Loading -> R.string.chat_tool_skill_loading
                    SkillPhase.Loaded -> R.string.chat_tool_skill_loaded
                    SkillPhase.Failed -> R.string.chat_tool_skill_not_loaded
                },
            ),
        )
    }
}

@Composable
private fun ImageBody(card: ImageCard) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(technical(card.path), style = DsType.mdCode, color = colors.labelSecondary)
        if (card.images.isEmpty()) Caption(stringResource(R.string.chat_tool_image_none))
        card.images.forEach { image ->
            val size = android.text.format.Formatter.formatShortFileSize(context, image.bytes.toLong())
            Caption(technical("${image.width} × ${image.height}") + " · " + size)
        }
    }
}

@Composable
private fun DetailsBody(card: DetailsCard) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (card.items.isEmpty()) {
            Caption(
                when (card.tool) {
                    DetailsTool.JobList -> stringResource(R.string.chat_tool_jobs_none)
                    DetailsTool.TerminalList -> stringResource(R.string.chat_tool_terminals_none)
                    DetailsTool.ScheduleList -> stringResource(R.string.chat_tool_reminders_none)
                    DetailsTool.GoalView -> stringResource(R.string.chat_tool_goal_none)
                    else -> stringResource(R.string.chat_tool_places_none)
                },
            )
        }
        card.items.forEachIndexed { index, item -> DetailItemView(item, index) }
        if (card.truncated) Caption(stringResource(R.string.chat_tool_output_cut))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailItemView(item: DetailItem, index: Int) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        LabelWithChip {
            if (item.title.isNotEmpty()) {
                Text(
                    if (item.technicalTitle) technical(item.title) else item.title,
                    style = if (item.technicalTitle) DsType.small13Strong.copy(fontFamily = DsType.codeFont) else DsType.small13Strong,
                    color = colors.labelPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            detailStateText(item)?.let { StateChip(it, detailTone(item.state), Modifier.align(Alignment.CenterVertically)) }
        }
        item.subtitle?.let { Caption(technical(it)) }
        item.fields.forEach { field ->
            Caption(stringResource(R.string.chat_tool_field_value, fieldLabelText(field.label), fieldValueText(field.value)))
        }
        item.description?.let { Text(it, style = DsType.small13, color = colors.labelSecondary) }
        item.code?.takeIf { it.isNotEmpty() }?.let { code ->
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth()
                    .clip(DsShapes.row)
                    .background(colors.bgLayer2)
                    .border(1.dp, colors.borderL1, DsShapes.row)
                    .padding(10.dp),
            ) {
                CodeText(code, blockId = "item$index", fadeColor = colors.bgLayer2)
            }
        }
    }
}

@Composable
private fun detailStateText(item: DetailItem): String? = when (item.state) {
    null -> null
    DetailState.Running -> stringResource(R.string.jobs_running)
    DetailState.Stopping -> stringResource(R.string.jobs_stopping)
    DetailState.Completed -> stringResource(R.string.jobs_completed)
    DetailState.Failed -> stringResource(R.string.jobs_failed)
    DetailState.Killed -> stringResource(R.string.jobs_killed)
    DetailState.Exited -> stringResource(R.string.chat_tool_state_exited)
    DetailState.Started -> stringResource(R.string.chat_tool_state_started)
    DetailState.CancelRequested -> stringResource(R.string.chat_tool_state_stop_requested)
    DetailState.AlreadyFinished -> stringResource(R.string.chat_tool_state_already_finished)
    DetailState.SignalDelivered -> stringResource(R.string.chat_tool_state_signal_sent)
    DetailState.Closed -> stringResource(R.string.chat_tool_state_closed)
    DetailState.Closing -> stringResource(R.string.chat_tool_state_closing)
    DetailState.Scheduled -> stringResource(R.string.chat_tool_state_scheduled)
    DetailState.Overdue -> stringResource(R.string.chat_tool_state_overdue)
    DetailState.Deleted -> stringResource(R.string.chat_tool_state_deleted)
    DetailState.GoalActive -> stringResource(R.string.goal_phase_active)
    DetailState.GoalWaiting -> stringResource(R.string.chat_tool_state_goal_waiting)
    DetailState.GoalPaused -> stringResource(R.string.goal_phase_paused)
    DetailState.GoalBlocked -> stringResource(R.string.goal_phase_blocked)
    DetailState.GoalComplete -> stringResource(R.string.goal_phase_complete)
    DetailState.GoalCleared -> stringResource(R.string.chat_tool_state_goal_cleared)
    DetailState.Pending -> stringResource(R.string.chat_tool_todo_pending)
    DetailState.InProgress -> stringResource(R.string.chat_tool_todo_in_progress)
    DetailState.Other -> item.stateRaw
}

private enum class Tone { Success, Error, Warning, Info, Neutral }

private fun detailTone(state: DetailState?): Tone = when (state) {
    DetailState.Completed, DetailState.GoalComplete, DetailState.SignalDelivered -> Tone.Success
    DetailState.Failed -> Tone.Error
    DetailState.Killed, DetailState.Overdue, DetailState.GoalBlocked, DetailState.CancelRequested,
    DetailState.Stopping, DetailState.Pending -> Tone.Warning
    DetailState.Running, DetailState.Started, DetailState.GoalActive, DetailState.InProgress -> Tone.Info
    else -> Tone.Neutral
}

/**
 * A label and a state chip on one line while they fit, and stacked when they do not. Used wherever
 * a card puts a word beside a chip, so a large text size wraps them instead of clipping the chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelWithChip(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** A state in words on a tinted pill: the word carries the state, the tint only repeats it. */
@Composable
private fun StateChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val (foreground, background) = when (tone) {
        Tone.Success -> colors.success to colors.successTertiary
        Tone.Error -> colors.error to colors.errorTertiary
        Tone.Warning -> colors.warnLabel to colors.warnTertiary
        Tone.Info -> colors.accent to colors.accentTertiary
        Tone.Neutral -> colors.labelSecondary to colors.bgLayer3
    }
    // A chip is one word or two: it is never squeezed to fewer letters. Where it is longer than the
    // line it wraps within the chip instead.
    Text(
        text,
        style = DsType.caption11Strong,
        color = foreground,
        modifier = modifier
            .clip(DsShapes.pillFull)
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun fieldLabelText(label: DetailLabel): String = stringResource(
    when (label) {
        DetailLabel.Type -> R.string.chat_tool_field_type
        DetailLabel.Pid -> R.string.chat_tool_field_pid
        DetailLabel.ExitCode -> R.string.chat_tool_field_exit_code
        DetailLabel.Signal -> R.string.chat_tool_field_signal
        DetailLabel.ProcessGroup -> R.string.chat_tool_field_process_group
        DetailLabel.Lines -> R.string.chat_tool_field_lines
        DetailLabel.Rounds -> R.string.chat_tool_field_rounds
        DetailLabel.Reason -> R.string.chat_tool_field_reason
        DetailLabel.When -> R.string.chat_tool_field_when
        DetailLabel.Repeats -> R.string.chat_tool_field_repeats
        DetailLabel.Location -> R.string.chat_tool_field_location
        DetailLabel.Kind -> R.string.chat_tool_field_kind
    },
)

@Composable
private fun fieldValueText(value: DetailValue): String = when (value) {
    is DetailValue.Text -> when {
        // A recorded instant (a reminder's time) reads in the reader's own clock and format.
        !value.technical && SCHEDULE_INSTANT.matches(value.text) -> localDateTime(value.text)
        value.technical -> technical(value.text)
        else -> value.text
    }
    is DetailValue.Repeat -> repeatText(value.rule)
}

private val SCHEDULE_INSTANT = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$")

private fun localDateTime(iso: String): String = runCatching {
    java.time.format.DateTimeFormatter
        .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.parse(iso))
}.getOrDefault(iso)

@Composable
private fun repeatText(rule: ScheduleRepeat): String = when (rule) {
    ScheduleRepeat.Once -> stringResource(R.string.chat_tool_repeat_once)
    is ScheduleRepeat.Every -> {
        val s = rule.seconds
        // "Every minute" rather than "Every 1 minute": one of a unit has its own wording.
        val (count, single, plural) = when {
            s % 86_400 == 0L -> Triple(s / 86_400, R.string.chat_tool_repeat_every_day, R.plurals.chat_tool_repeat_days)
            s % 3_600 == 0L -> Triple(s / 3_600, R.string.chat_tool_repeat_every_hour, R.plurals.chat_tool_repeat_hours)
            s % 60 == 0L -> Triple(s / 60, R.string.chat_tool_repeat_every_minute, R.plurals.chat_tool_repeat_minutes)
            else -> Triple(s, R.string.chat_tool_repeat_every_second, R.plurals.chat_tool_repeat_seconds)
        }
        if (count == 1L) stringResource(single) else pluralStringResource(plural, count.toInt(), count.toInt())
    }
    is ScheduleRepeat.Daily -> stringResource(R.string.chat_tool_repeat_daily, technical(rule.time), technical(rule.zone))
    is ScheduleRepeat.Weekly -> {
        val locale = java.util.Locale.getDefault()
        val days = rule.days.joinToString(", ") {
            java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, locale)
        }
        stringResource(R.string.chat_tool_repeat_weekly, days, technical(rule.time), technical(rule.zone))
    }
    is ScheduleRepeat.Cron -> stringResource(R.string.chat_tool_repeat_cron, technical(rule.expression), technical(rule.zone))
}

// ---- Background command ---------------------------------------------------------------------

/** The background part of a terminal card: where the job stands and the way to open it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackgroundJobLine(card: ToolCardView.TerminalCard, actions: ToolCardActions) {
    val colors = DsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The label and the state chip wrap onto separate lines when they do not fit side by side (a
        // large text size): the chip used to be squeezed to one letter instead.
        LabelWithChip {
            Text(
                stringResource(if (card.running == true) R.string.chat_tool_background_starting else R.string.chat_tool_background),
                style = DsType.small13,
                color = colors.labelSecondary,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            actions.jobState?.let { StateChip(it, if (actions.jobRunning) Tone.Info else Tone.Neutral, Modifier.align(Alignment.CenterVertically)) }
        }
        val open = actions.openJob
        if (open != null && card.jobId != null) OpenRow(stringResource(R.string.chat_tool_open_job), FeatherIcons.Terminal, open)
    }
}

// ---- Shared pieces --------------------------------------------------------------------------

@Composable
private fun Caption(text: String) {
    Text(text, style = DsType.caption11, color = DsTheme.colors.labelCaption)
}

/**
 * A full-width row that opens something elsewhere (a job, a subagent): a 48dp target with a word,
 * not an icon alone, so it is both easy to hit and says what it does.
 */
@Composable
private fun OpenRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.row)
            .border(1.dp, colors.borderL2, DsShapes.row)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = DsType.small13Strong, color = colors.accent, modifier = Modifier.weight(1f))
        Icon(FeatherIcons.ChevronRight, contentDescription = null, tint = colors.labelTertiary, modifier = Modifier.size(14.dp))
    }
}
