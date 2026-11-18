package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.permission.PermissionDenial
import com.labteto.dshmobile.core.permission.permissionDenialOf
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.readToolResult
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.ui.components.DetailField
import com.labteto.dshmobile.ui.components.DetailItem
import com.labteto.dshmobile.ui.components.DetailLabel
import com.labteto.dshmobile.ui.components.DetailState
import com.labteto.dshmobile.ui.components.DetailValue
import com.labteto.dshmobile.ui.components.DetailsCard
import com.labteto.dshmobile.ui.components.DetailsTool
import com.labteto.dshmobile.ui.components.DisclosureState
import com.labteto.dshmobile.ui.components.ImageCard
import com.labteto.dshmobile.ui.components.ImageFacts
import com.labteto.dshmobile.ui.components.QuestionCard
import com.labteto.dshmobile.ui.components.QuestionLine
import com.labteto.dshmobile.ui.components.QuestionOutcome
import com.labteto.dshmobile.ui.components.ScheduleRepeat
import com.labteto.dshmobile.ui.components.SkillCard
import com.labteto.dshmobile.ui.components.SkillPhase
import com.labteto.dshmobile.ui.components.SubagentCard
import com.labteto.dshmobile.ui.components.SubagentPhase
import com.labteto.dshmobile.ui.components.TodoCard
import com.labteto.dshmobile.ui.components.TodoChange
import com.labteto.dshmobile.ui.components.TodoLine
import com.labteto.dshmobile.ui.components.TodoMark
import com.labteto.dshmobile.ui.components.ToolCardView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Cards for the harness's control tools, derived from the raw call and result.
 *
 * A port of the web client's tool views (`packages/client/ui-tool/src/client/tool`): `todo-row` and
 * `todo-diff-model` for the task list, `ask-question-row` for questions, `control-details-model` and
 * `details-card-model` for jobs, terminals, the language server, goals and schedules. The same rule
 * as [buildToolCardView] holds: a result whose shape is not recognised in full gets no card here, and
 * the call falls through to the generic one rather than being half-rendered.
 *
 * Every function is pure so the mapping is tested without a device; the words are chosen where the
 * cards are drawn.
 */
internal fun controlToolCard(
    call: ToolCallNode,
    args: JsonObject,
    result: ToolResultNode?,
    /** The list the previous `todo_write` recorded, or null when this is the conversation's first. */
    previousTodos: List<TodoEntry>?,
): ToolCardView? = when (call.name) {
    "todo_write" -> todoCard(args, result, previousTodos)
    "ask_user_question" -> questionCard(args, result)
    "subagent" -> subagentCard(args, result)
    "skill" -> skillCard(args, result)
    "read_image" -> imageCard(args, result)
    else -> detailsCard(call.name, args, result)
}

// ============================================================================================
// Task list
// ============================================================================================

private fun todoMark(status: String?): TodoMark? = when (status) {
    "pending" -> TodoMark.Pending
    "in_progress" -> TodoMark.InProgress
    "completed" -> TodoMark.Completed
    else -> null
}

/** The call's list, validated as the tool validates it: trimmed, non-empty, unique, known status. */
private fun todoArgs(args: JsonObject): List<TodoLine>? {
    val todos = args["todos"] as? JsonArray ?: return null
    val seen = HashSet<String>()
    return todos.map { item ->
        val o = item as? JsonObject ?: return null
        val content = str(o, "content")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val mark = todoMark(str(o, "status")) ?: return null
        if (!seen.add(content)) return null
        TodoLine(content, mark)
    }
}

/**
 * A `todo_write` call as one line, plus what changed.
 *
 * The full list is not repeated: the tool appends a `todo/write` event as it runs, and the
 * transcript draws that as the task list right below the call. So a recorded write lists only the
 * changes against the write before it (the web client's `todoDiffModel`), and the first write lists
 * nothing. A failed write recorded nothing, so its card lists what it tried to write.
 */
internal fun todoCard(args: JsonObject, result: ToolResultNode?, previous: List<TodoEntry>?): TodoCard? {
    val current = todoArgs(args) ?: return null
    val done = current.count { it.mark == TodoMark.Completed }
    val active = current.filter { it.mark == TodoMark.InProgress }
    val recorded = result?.isError != true
    if (!recorded) {
        return TodoCard(done, current.size, active.firstOrNull()?.content, (active.size - 1).coerceAtLeast(0),
            added = 0, updated = 0, removed = 0, initial = previous == null, recorded = false, lines = current)
    }
    val before = previous?.mapNotNull { entry -> todoMark(entry.status)?.let { TodoLine(entry.content.trim(), it) } }
    var added = 0
    var updated = 0
    val lines = ArrayList<TodoLine>()
    if (before != null) {
        val previousByTitle = LinkedHashMap<String, TodoLine>().apply { before.forEach { put(it.content, it) } }
        val currentTitles = current.map { it.content }.toSet()
        // Order among the items both lists share: an item that moved within them was reordered.
        val retainedPositions = before.filter { it.content in currentTitles }
            .mapIndexed { index, line -> line.content to index }.toMap()
        var retainedIndex = 0
        for (item in current) {
            val old = previousByTitle.remove(item.content)
            if (old == null) {
                added++
                lines += item.copy(change = TodoChange.Added)
                continue
            }
            val moved = retainedPositions[item.content] != retainedIndex++
            val statusChanged = old.mark != item.mark
            if (statusChanged || moved) {
                updated++
                lines += item.copy(
                    change = if (statusChanged) TodoChange.StatusChanged else TodoChange.Moved,
                    previous = if (statusChanged) old.mark else null,
                )
            }
        }
        previousByTitle.values.forEach { lines += it.copy(change = TodoChange.Removed) }
    }
    val removed = lines.count { it.change == TodoChange.Removed }
    return TodoCard(done, current.size, active.firstOrNull()?.content, (active.size - 1).coerceAtLeast(0),
        added, updated, removed, initial = before == null, recorded = true, lines = lines)
}

// ============================================================================================
// Questions
// ============================================================================================

private class AskedQuestion(val id: String, val line: QuestionLine)

/** The call's questions; null when ids repeat or a question has no text, since pairing would guess. */
private fun askedQuestions(args: JsonObject): List<AskedQuestion>? {
    val questions = args["questions"] as? JsonArray ?: return null
    if (questions.isEmpty()) return null
    val ids = HashSet<String>()
    return questions.map { q ->
        val o = q as? JsonObject ?: return null
        val id = str(o, "id") ?: return null
        val text = str(o, "question") ?: return null
        if (!ids.add(id)) return null
        val options = (o["options"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { opt -> str(opt, "label") } }
        AskedQuestion(id, QuestionLine(text, str(o, "header"), options.orEmpty()))
    }
}

private class GivenAnswer(val id: String, val values: List<String>)

private fun givenAnswers(value: JsonElement?): List<GivenAnswer>? {
    val answers = (value as? JsonObject)?.get("answers") as? JsonArray ?: return null
    return answers.map { a ->
        val o = a as? JsonObject ?: return null
        val id = str(o, "id") ?: return null
        val selected = (o["selected"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
            ?: return null
        val custom = o["custom"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
        GivenAnswer(id, selected + listOfNotNull(custom?.takeIf { it.isNotEmpty() }))
    }
}

/**
 * An `ask_user_question` call: the questions, paired with the answers once there are any.
 *
 * How a question ended is read from the error the harness recorded ([questionVerdictOf]); a
 * `{ pending: true }` result is a timed question the agent carried on past.
 */
internal fun questionCard(args: JsonObject, result: ToolResultNode?): QuestionCard? {
    val asked = askedQuestions(args) ?: return null
    val lines = asked.map { it.line }
    if (result == null) return QuestionCard(lines, QuestionOutcome.Waiting)
    if (result.isError) return QuestionCard(lines, questionVerdictOf(result) ?: QuestionOutcome.Settled)
    val text = singleResultText(result) ?: return QuestionCard(lines, QuestionOutcome.Settled)
    val value = runCatching { WireJson.parseToJsonElement(text) }.getOrNull()
    val obj = value as? JsonObject
    if (obj != null && (obj["pending"] as? JsonPrimitive)?.contentOrNull == "true") {
        return QuestionCard(lines, QuestionOutcome.CarriedOn)
    }
    val answers = givenAnswers(value) ?: return QuestionCard(lines, QuestionOutcome.Settled)
    val answered = answers.count { it.values.isNotEmpty() }
    val byId = answers.associateBy { it.id }
    // Paired by the ids the result echoes; a batch that does not match the questions one to one
    // keeps only its count, since showing it beside the wrong question would put words in the
    // reader's mouth.
    val paired = if (byId.size == answers.size && answers.size == asked.size && asked.all { it.id in byId }) {
        asked.map { it.line.copy(answers = byId.getValue(it.id).values) }
    } else {
        lines
    }
    return QuestionCard(paired, QuestionOutcome.Answered, answered = answered, total = answers.size)
}

/**
 * How a failed `ask_user_question` ended, from the error the harness recorded, or null when the
 * error is none of the three endings a reader can cause. The codes are the real ones:
 * - dismissed: the web client rejects with `ASK_CANCELLED` (`ui-user-questions`, `slots.ts`); this
 *   app rejects the request with code `cancelled` and the message "the user closed this question
 *   request" (`QUESTION_CANCELLED`), which the harness records as `{ name: UserQuestionError, code:
 *   cancelled }` (seen on a device run, `docs/PROTOCOL.md`).
 * - stopped while open: `ASK_ABORTED` (`user-questions/src/index.ts`), and any other form a turn's
 *   stop takes ([isAbortedResult]).
 * - timed out: `ASK_TIMED_OUT`. A timed `ask_user_question` turns its own timeout into a
 *   `{ pending: true }` result, so this code only reaches a result when a client rejected an
 *   untimed question that way.
 */
internal fun questionVerdictOf(result: ToolResultNode?): QuestionOutcome? {
    if (result == null || !result.isError) return null
    return questionEndingOf(result.errorCode, result.errorName)
}

/** [questionVerdictOf] from a record's error code and name alone. */
private fun questionEndingOf(code: String?, name: String?): QuestionOutcome? = when {
    code == "ASK_CANCELLED" || code == "cancelled" -> QuestionOutcome.Cancelled
    code == "ASK_TIMED_OUT" -> QuestionOutcome.TimedOut
    isAbortError(code, name) -> QuestionOutcome.Interrupted
    else -> null
}

// ============================================================================================
// Subagents
// ============================================================================================

private val SUBAGENT_STARTED = Regex("^started (background subagent job|subagent) (\\S+)$")

/** A `subagent` call: the child's task, its model when one was asked for, and how far it got. */
internal fun subagentCard(args: JsonObject, result: ToolResultNode?): SubagentCard? {
    val prompt = str(args, "prompt")?.takeIf { it.isNotBlank() }
    val description = str(args, "description")?.takeIf { it.isNotBlank() }
    val title = description ?: prompt?.let(::firstLine) ?: return null
    val model = str(args, "model")?.takeIf { it.isNotBlank() }
    val base = SubagentCard(title = title, prompt = prompt, model = model, phase = SubagentPhase.Starting)
    if (result == null) return base
    if (result.isError) {
        // The same answer the row and its dot give: a subagent call the turn's stop cut short was
        // stopped, not failed to start.
        val stopped = toolOutcome("subagent", result, running = false) == ToolOutcome.Stopped
        return base.copy(phase = if (stopped) SubagentPhase.Stopped else SubagentPhase.Failed)
    }
    val text = singleResultText(result) ?: return base.copy(phase = SubagentPhase.Started)
    SUBAGENT_STARTED.find(text.trim())?.let { match ->
        // Only a session-backed child is something the Subagents sheet can open; a background job id
        // names a job, not a child.
        val child = if (match.groupValues[1] == "subagent") match.groupValues[2] else null
        return base.copy(phase = SubagentPhase.Started, childId = child)
    }
    return base.copy(phase = SubagentPhase.Replied, reply = text)
}

/**
 * The child a `subagent/descriptor` record describes, for its row: the label it was started with
 * and the model it runs on, when the descriptor records one.
 */
internal data class SubagentDescriptorFacts(val label: String?, val model: String?, val provider: String?)

internal fun subagentDescriptorFacts(data: JsonElement): SubagentDescriptorFacts {
    val o = data as? JsonObject ?: return SubagentDescriptorFacts(null, null, null)
    return SubagentDescriptorFacts(
        label = str(o, "label")?.takeIf { it.isNotBlank() },
        model = str(o, "agentModel")?.takeIf { it.isNotBlank() },
        provider = str(o, "agentProvider")?.takeIf { it.isNotBlank() },
    )
}

// ============================================================================================
// Skills and images
// ============================================================================================

private val SKILL_NAME = Regex("<skill_content name=\"([^\"]*)\">")
private val SKILL_INSTRUCTIONS = Regex("<skill_instructions>\\n([\\s\\S]*?)\\n</skill_instructions>")

/**
 * A `skill` call: the skill's name, and the first line of its instructions as what it is about.
 * The result is the harness's `renderSkillContent` envelope; front matter and headings' `#` marks are
 * skipped so the line reads as prose.
 */
internal fun skillCard(args: JsonObject, result: ToolResultNode?): SkillCard? {
    val requested = str(args, "name")?.takeIf { it.isNotBlank() } ?: return null
    if (result == null) return SkillCard(requested, null, SkillPhase.Loading)
    if (result.isError) return SkillCard(requested, null, SkillPhase.Failed)
    val text = singleResultText(result) ?: return SkillCard(requested, null, SkillPhase.Loaded)
    val name = SKILL_NAME.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: requested
    val body = SKILL_INSTRUCTIONS.find(text)?.groupValues?.get(1) ?: return SkillCard(name, null, SkillPhase.Loaded)
    return SkillCard(name, skillDescription(body), SkillPhase.Loaded)
}

/** The first line of a skill's instructions worth reading: front matter's `description`, else prose. */
internal fun skillDescription(instructions: String): String? {
    val lines = instructions.lines()
    var i = 0
    if (lines.firstOrNull()?.trim() == "---") {
        // Front matter: its own description is the best one-line summary there is.
        i = 1
        while (i < lines.size && lines[i].trim() != "---") {
            val line = lines[i].trim()
            if (line.startsWith("description:")) {
                return line.removePrefix("description:").trim().trim('"', '\'').takeIf { it.isNotEmpty() }
            }
            i++
        }
        i++
    }
    return lines.drop(i)
        .map { it.trim().trimStart('#', '>', '-', '*', ' ').trim() }
        .firstOrNull { it.isNotEmpty() && it != "---" }
}

/**
 * A `read_image` call: the path it read (the resolved one the harness recorded in `meta.path`, else
 * the argument) and the facts of each image it returned. The pixels are drawn from the result's
 * attachment, which the transcript already shows under the card.
 */
internal fun imageCard(args: JsonObject, result: ToolResultNode?): ImageCard? {
    val argPath = str(args, "file_path")?.takeIf { it.isNotBlank() } ?: return null
    val metaPath = (result?.meta as? JsonObject)?.let { str(it, "path") }?.takeIf { it.isNotBlank() }
    val images = toolResultImages(result?.content).map { ImageFacts(it.width, it.height, it.bytes, it.mediaType) }
    return ImageCard(metaPath ?: argPath, images)
}

// ============================================================================================
// Record-style tools: jobs, terminals, the language server, goals, schedules, deliverables
// ============================================================================================

private const val OUTPUT_TRUNCATED = "\n[output truncated]"

/** A recorded state word as a [DetailState], or null for one this build cannot name. */
internal fun detailState(raw: String): DetailState? = when (raw) {
    "running" -> DetailState.Running
    "stopping" -> DetailState.Stopping
    "completed", "complete", "done" -> DetailState.Completed
    "failed", "error" -> DetailState.Failed
    "killed" -> DetailState.Killed
    "pending" -> DetailState.Pending
    "in_progress" -> DetailState.InProgress
    "deleted" -> DetailState.Deleted
    else -> null
}

private fun stateItem(title: String, raw: String, technical: Boolean = false, subtitle: String? = null): DetailItem {
    val state = detailState(raw)
    return DetailItem(title, technicalTitle = technical, subtitle = subtitle, state = state ?: DetailState.Other,
        stateRaw = raw.takeIf { state == null })
}

private fun text(value: String, technical: Boolean = true): DetailValue = DetailValue.Text(value, technical)

private val JOB_LINE = Regex("^(\\S+) \\[([^\\]]+)] (\\S+) — (.*)$")
private val JOB_STATUS = Regex("\\n\\[status: ([^,\\]\\n]+)(?:, ([^\\]\\n]+))?]$")
private val TERMINAL_OPEN = Regex("^started terminal session (\\S+)(?: \\((.*?)\\))? \\[type: ([^\\]]+)]\\n([\\s\\S]*)$")
private val TERMINAL_READ = Regex("\\n\\[lines: (\\d+)-(\\d+) of (\\d+)](\\n\\[output truncated])?$")
private val TERMINAL_LINE = Regex("^(\\S+)(?: \\((.*?)\\))? \\[([^\\]]+)] (running|exited code=(\\S+) signal=(\\S+))(?: pid=(\\d+))?$")
private val TERMINAL_SIGNAL = Regex("^delivered (\\S+) to foreground process group (\\d+)$")
private val LSP_LOCATION = Regex("^(.*):(\\d+):(\\d+)$")

/**
 * A record-style tool's result as rows (the web client's `controlDetails` and `detailsCardModel`).
 * Goal and schedule calls are read from their JSON result; the rest from the text the tool renders,
 * which the harness keeps stable for exactly this kind of reader.
 */
internal fun detailsCard(name: String, args: JsonObject, result: ToolResultNode?): DetailsCard? {
    // Goal and deliverable calls have a node of their own in the transcript, so they are a header
    // line even before the result arrives; the rest show their rows once there is a result to read.
    when (name) {
        "create_goal", "update_goal" -> return goalChangeCard(name, args, result)
        "present" -> return presentCard(args, result)
    }
    if (result == null || result.isError) return null
    val text = singleResultText(result) ?: return null
    val target = listOf("target", "agent_id", "sessionId", "job_id").firstNotNullOfOrNull { str(args, it)?.takeIf(String::isNotEmpty) }.orEmpty()
    return when (name) {
        "job_list" -> jobList(text)
        "job_output" -> jobOutput(text, target)
        "job_kill" -> when {
            text == "requested cancellation of job $target" -> DetailsCard(DetailsTool.JobKill,
                listOf(DetailItem(target, technicalTitle = true, state = DetailState.CancelRequested, description = str(args, "reason"))), target)
            text.startsWith("job $target had already finished ") -> DetailsCard(DetailsTool.JobKill,
                listOf(DetailItem(target, technicalTitle = true, state = DetailState.AlreadyFinished)), target)
            else -> null
        }
        "terminal_open" -> TERMINAL_OPEN.find(text)?.let { m ->
            val (id, label, type, motd) = m.destructured
            val title = label.ifEmpty { id }
            DetailsCard(DetailsTool.TerminalOpen, listOf(DetailItem(title, technicalTitle = label.isEmpty(),
                subtitle = id.takeIf { label.isNotEmpty() }, state = DetailState.Started,
                fields = listOf(DetailField(DetailLabel.Type, text(type))), code = motd)), title)
        }
        "terminal_read" -> TERMINAL_READ.find(text)?.let { m ->
            val (begin, end, total, truncated) = m.destructured
            DetailsCard(DetailsTool.TerminalRead, listOf(DetailItem(target, technicalTitle = true,
                fields = listOf(DetailField(DetailLabel.Lines, text("$begin–$end / $total"))),
                code = text.substring(0, m.range.first))), target, truncated = truncated.isNotEmpty())
        }
        "terminal_list" -> terminalList(text)
        "terminal_signal" -> TERMINAL_SIGNAL.find(text)?.let { m ->
            DetailsCard(DetailsTool.TerminalSignal, listOf(DetailItem(target, technicalTitle = true, state = DetailState.SignalDelivered,
                fields = listOf(DetailField(DetailLabel.Signal, text(m.groupValues[1])), DetailField(DetailLabel.ProcessGroup, text(m.groupValues[2]))))), target)
        }
        "terminal_close" -> when (text) {
            "closed terminal session $target" ->
                DetailsCard(DetailsTool.TerminalClose, listOf(DetailItem(target, technicalTitle = true, state = DetailState.Closed)), target)
            "terminal session $target was already closing" ->
                DetailsCard(DetailsTool.TerminalClose, listOf(DetailItem(target, technicalTitle = true, state = DetailState.Closing)), target)
            else -> null
        }
        "lsp" -> lspCard(args, text)
        "get_goal" -> goalViewCard(text)
        "schedule_create", "schedule_update" -> parseJson(text)?.let(::scheduleItem)?.let { item ->
            DetailsCard(if (name == "schedule_create") DetailsTool.ScheduleCreate else DetailsTool.ScheduleUpdate, listOf(item), item.title)
        }
        "schedule_list" -> (parseJson(text) as? JsonArray)?.let { list ->
            val items = list.map { scheduleItem(it) ?: return null }
            DetailsCard(DetailsTool.ScheduleList, items)
        }
        "schedule_delete" -> (parseJson(text) as? JsonObject)?.let { o ->
            val id = str(o, "id")?.takeIf { it.isNotBlank() } ?: return null
            if ((o["deleted"] as? JsonPrimitive)?.contentOrNull != "true") return null
            DetailsCard(DetailsTool.ScheduleDelete, listOf(DetailItem(id, technicalTitle = true, state = DetailState.Deleted)), id)
        }
        else -> null
    }
}

private fun jobList(text: String): DetailsCard? {
    if (text == "(no background jobs)") return DetailsCard(DetailsTool.JobList, emptyList())
    val items = text.lines().map { line ->
        val (id, kind, state, title) = JOB_LINE.find(line)?.destructured ?: return null
        stateItem(title, state, technical = true, subtitle = id)
            .copy(fields = listOf(DetailField(DetailLabel.Kind, text(jobKindLabel(kind), technical = false))))
    }
    return DetailsCard(DetailsTool.JobList, items)
}

private fun jobOutput(text: String, target: String): DetailsCard? {
    val match = JOB_STATUS.find(text) ?: return null
    val output = text.substring(0, match.range.first)
    val truncated = output.endsWith(OUTPUT_TRUNCATED)
    val code = if (truncated) output.removeSuffix(OUTPUT_TRUNCATED) else output
    val detail = match.groupValues[2].takeIf { it.isNotEmpty() }
    val item = stateItem(target, match.groupValues[1], technical = true).copy(description = detail, code = code)
    return DetailsCard(DetailsTool.JobOutput, listOf(item), target, truncated = truncated)
}

private fun terminalList(text: String): DetailsCard? {
    if (text == "(no terminal sessions)") return DetailsCard(DetailsTool.TerminalList, emptyList())
    val items = text.lines().map { line ->
        val m = TERMINAL_LINE.find(line) ?: return null
        val (id, label, type, state, exitCode, signal, pid) = m.destructured
        val fields = buildList {
            add(DetailField(DetailLabel.Type, text(type)))
            if (pid.isNotEmpty()) add(DetailField(DetailLabel.Pid, text(pid)))
            if (exitCode.isNotEmpty()) add(DetailField(DetailLabel.ExitCode, text(exitCode)))
            if (signal.isNotEmpty() && signal != "null") add(DetailField(DetailLabel.Signal, text(signal)))
        }
        DetailItem(label.ifEmpty { id }, technicalTitle = label.isEmpty(), subtitle = id.takeIf { label.isNotEmpty() },
            state = if (state == "running") DetailState.Running else DetailState.Exited, fields = fields)
    }
    return DetailsCard(DetailsTool.TerminalList, items)
}

private fun lspCard(args: JsonObject, text: String): DetailsCard? {
    val file = str(args, "file_path")?.takeIf { it.isNotEmpty() } ?: return null
    val line = (args["line"] as? JsonPrimitive)?.longOrNull ?: return null
    val character = (args["character"] as? JsonPrimitive)?.longOrNull ?: return null
    val source = "$file:$line:$character"
    if (str(args, "operation") == "hover") {
        return DetailsCard(DetailsTool.Lsp, listOf(DetailItem(source, technicalTitle = true, description = text)), source)
    }
    if (text == "No results.") return DetailsCard(DetailsTool.Lsp, emptyList(), file)
    val items = text.lines().map { row ->
        if (row.startsWith("… ")) return@map DetailItem(row.removePrefix("… "))
        val (path, number, column) = LSP_LOCATION.find(row)?.destructured ?: return null
        DetailItem(path, technicalTitle = true, fields = listOf(DetailField(DetailLabel.Location, text("$number:$column"))))
    }
    return DetailsCard(DetailsTool.Lsp, items, file)
}

/** A goal's recorded state, the way the goal tools render it (`{ goal, activation }`). */
private fun goalItem(value: JsonElement?): DetailItem? {
    val o = value as? JsonObject ?: return null
    val goal = o["goal"] ?: return null
    if (goal is JsonNull) return DetailItem("", state = DetailState.GoalCleared)
    val g = goal as? JsonObject ?: return null
    val objective = str(g, "objective")?.takeIf { it.isNotBlank() } ?: return null
    val rounds = (g["roundsStarted"] as? JsonPrimitive)?.longOrNull ?: return null
    val maxRounds = (g["maxGoalRounds"] as? JsonPrimitive)?.longOrNull ?: return null
    val activation = str(o, "activation")
    val state = when (str(g, "phase")) {
        "active" -> if (activation == "disarmed") DetailState.GoalWaiting else DetailState.GoalActive
        "paused" -> DetailState.GoalPaused
        "blocked" -> DetailState.GoalBlocked
        "complete" -> DetailState.GoalComplete
        else -> return null
    }
    val fields = buildList {
        add(DetailField(DetailLabel.Rounds, text("$rounds / $maxRounds", technical = false)))
        (g["blockedReason"] as? JsonObject)?.let { str(it, "message") }?.let { add(DetailField(DetailLabel.Reason, text(it, technical = false))) }
    }
    return DetailItem(objective, state = state, fields = fields)
}

private fun goalViewCard(text: String): DetailsCard? {
    val item = goalItem(parseJson(text)) ?: return null
    return DetailsCard(DetailsTool.GoalView, if (item.state == DetailState.GoalCleared) emptyList() else listOf(item), item.title.ifEmpty { null })
}

/**
 * `create_goal` and `update_goal` change the goal, and the change is a `goal/change` event the
 * transcript draws as the goal's own card right after the call. So the call is one header line: what
 * it did, to which objective. While it runs, the objective comes from its arguments.
 */
private fun goalChangeCard(name: String, args: JsonObject, result: ToolResultNode?): DetailsCard? {
    val tool = if (name == "create_goal") DetailsTool.GoalCreate else DetailsTool.GoalUpdate
    val settled = result?.takeIf { !it.isError }?.let(::singleResultText)?.let { goalItem(parseJson(it)) }
    if (settled != null) return DetailsCard(tool, listOf(settled), settled.title.ifEmpty { null }, headerOnly = true)
    if (result != null && !result.isError) return null
    val objective = str(args, "objective")?.takeIf { it.isNotBlank() }
    val action = str(args, "action")
    val state = when (action) {
        "pause" -> DetailState.GoalPaused
        "complete" -> DetailState.GoalComplete
        "blocked" -> DetailState.GoalBlocked
        else -> null
    }
    // A failed change keeps its card: the header says what it tried, the failure line says why.
    return DetailsCard(tool, listOf(DetailItem(objective.orEmpty(), state = state)), objective, headerOnly = true)
}

/**
 * `present` shares files with the reader, and the harness records a `deliverables/presented` event
 * that the transcript draws as a card per file right after the call. So the call is one header line.
 */
private fun presentCard(args: JsonObject, result: ToolResultNode?): DetailsCard? {
    val files = (args["files"] as? JsonArray)?.map { (it as? JsonObject)?.let { f -> str(f, "path") } ?: return null } ?: return null
    if (files.isEmpty()) return null
    if (result != null && !result.isError && singleResultText(result)?.lines()?.all { it.startsWith("Presented ") } != true) return null
    return DetailsCard(DetailsTool.Present, files.map { DetailItem(it, technicalTitle = true) },
        files.singleOrNull()?.let(::basename), headerOnly = true)
}

private val SCHEDULE_INSTANT = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$")

/** One recorded schedule (`schedule_create`, `schedule_update`, `schedule_list`'s entries). */
internal fun scheduleItem(value: JsonElement): DetailItem? {
    val o = value as? JsonObject ?: return null
    if (str(o, "id").isNullOrBlank()) return null
    val prompt = str(o, "prompt")?.takeIf { it.isNotBlank() } ?: return null
    val scheduledAt = str(o, "scheduledAt")?.takeIf { SCHEDULE_INSTANT.matches(it) } ?: return null
    if (str(o, "deliveryMode") != "host" && str(o, "deliveryMode") != "session-local") return null
    val state = when (str(o, "state")) {
        "scheduled" -> DetailState.Scheduled
        "overdue" -> DetailState.Overdue
        else -> return null
    }
    fun seconds(key: String): Long? = (o[key] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    val repeat: ScheduleRepeat = when (str(o, "kind")) {
        "at" -> ScheduleRepeat.Once
        "after" -> { seconds("afterSeconds") ?: return null; ScheduleRepeat.Once }
        "every" -> ScheduleRepeat.Every(seconds("everySeconds") ?: return null)
        "daily" -> ScheduleRepeat.Daily(shortTime(str(o, "time") ?: return null), str(o, "timeZone") ?: return null)
        "weekly" -> {
            val days = (o["weekdays"] as? JsonArray)?.map { (it as? JsonPrimitive)?.longOrNull?.toInt()?.takeIf { d -> d in 1..7 } ?: return null }
            if (days.isNullOrEmpty()) return null
            ScheduleRepeat.Weekly(days, shortTime(str(o, "time") ?: return null), str(o, "timeZone") ?: return null)
        }
        "cron" -> ScheduleRepeat.Cron(str(o, "expression") ?: return null, str(o, "timeZone") ?: return null)
        else -> return null
    }
    // Every host view carries a title; a result recorded before titles existed keeps its instruction.
    val title = str(o, "title")?.takeIf { it.isNotBlank() } ?: prompt
    return DetailItem(title, state = state, fields = listOf(
        DetailField(DetailLabel.When, DetailValue.Text(scheduledAt, technical = false)),
        DetailField(DetailLabel.Repeats, DetailValue.Repeat(repeat)),
    ))
}

/** A stored wall clock without its zero seconds (`09:00:00.000` reads `09:00`). */
private fun shortTime(time: String): String = time.removeSuffix(":00.000").removeSuffix(".000")

// ============================================================================================
// Failures, background shells, names
// ============================================================================================

/** Why a settled call did not do what it set out to, in terms the transcript can word plainly. */
internal sealed interface ToolFailure {
    /** The turn was stopped while the call ran or before it started ([isAbortedResult]). */
    data object Interrupted : ToolFailure

    /** A permission gate or Auto review refused it, so it never ran. */
    data class Denied(val denial: PermissionDenial, val reason: String?) : ToolFailure

    /** It ran and failed; [message] is the first line of the tool's own failure text, without its `Error:` prefix. */
    data class Failed(val message: String?) : ToolFailure
}

/** `error.code` the harness records for a call cancelled after its body started (`TOOL_ABORTED`). */
internal const val TOOL_ABORTED_CODE = "ABORTED"

/** `error.code` for a call cancelled before its body started (`TOOL_ABORTED_BEFORE_DISPATCH`). */
internal const val TOOL_ABORTED_BEFORE_DISPATCH_CODE = "ABORTED_BEFORE_DISPATCH"

/** `error.name` of the canonical abort results (`core/tools/src/index.ts`, `toolAbortedResult`). */
internal const val ABORT_ERROR_NAME = "AbortError"

/**
 * Whether [result] says the turn was stopped while the call was pending, in any form the harness
 * uses (0.2.1-rc.2, `packages/core/tools/src/index.ts`, `agent-loop/src/tool-calls.ts`):
 * - `{ name: AbortError, code: ABORTED }`, text "Error: tool call aborted": the Stop button while
 *   the tool ran. `tool-bash` and `tool-pwsh` throw the same pair themselves.
 * - `{ name: AbortError, code: ABORTED_BEFORE_DISPATCH }`, "tool call aborted before dispatch": a
 *   call the stop reached before it started, or one the scheduler skipped.
 * - a tool's own abort code, which by the harness's convention ends in `_ABORTED`
 *   (`ASK_ABORTED`, `WEB_ABORTED`, `FS_ABORTED`).
 * - `interrupted`, from records written before these existed.
 */
internal fun isAbortedResult(result: ToolResultNode?): Boolean {
    if (result == null || !result.isError) return false
    return isAbortError(result.errorCode, result.errorName)
}

/** The abort forms listed at [isAbortedResult], from a record's error code and name alone. */
internal fun isAbortError(code: String?, name: String?): Boolean =
    code == TOOL_ABORTED_CODE || code == TOOL_ABORTED_BEFORE_DISPATCH_CODE || code == "interrupted" ||
        code?.endsWith("_ABORTED") == true || name == ABORT_ERROR_NAME

/**
 * How a call stands. This is the one answer every dot, icon, count and line about a call reads: the
 * row's state, a terminal card's own dot, a subagent card's, the failed count in a group's header and
 * a nested call's row. Each of them used to ask "is the result an error?" for itself, and a stopped
 * command (an error, by the harness's records) was amber on the row and still red inside its card.
 */
internal enum class ToolOutcome {
    /** No result yet, and the session is working. */
    Running,

    /** It ran to its end: a result that is not an error, or one that is an error but not a failure of the call (a question the reader closed). */
    Done,

    /** It ran and went wrong, or a gate refused it. */
    Failed,

    /** The turn was stopped while it ran or before it started ([isAbortedResult]). */
    Stopped,
}

/**
 * The [ToolOutcome] of the call named [name] with [result] (null while it has none). A call with no
 * result in a session that is not working is [ToolOutcome.Done]: nothing is going to change it, and
 * the row has never shown such a call as anything but plain.
 */
internal fun toolOutcome(name: String, result: ToolResultNode?, running: Boolean): ToolOutcome =
    toolOutcomeOfRecord(name, result != null, result?.isError == true, result?.errorCode, result?.errorName, running)

/**
 * [toolOutcome] for a record that is not a [ToolResultNode] (a nested call's settlement), from the
 * facts every kind of record carries. All of the logic is here; the other overload only reads a result.
 */
internal fun toolOutcomeOfRecord(
    name: String,
    settled: Boolean,
    isError: Boolean,
    errorCode: String?,
    errorName: String?,
    running: Boolean,
): ToolOutcome = when {
    !settled -> if (running) ToolOutcome.Running else ToolOutcome.Done
    !isError -> ToolOutcome.Done
    isAbortError(errorCode, errorName) -> ToolOutcome.Stopped
    // A question the reader closed, or nobody answered in time: how it ended, not a failure.
    name == "ask_user_question" && questionEndingOf(errorCode, errorName) != null -> ToolOutcome.Done
    else -> ToolOutcome.Failed
}

/** The leading slot and the shimmer of a row for this outcome: a failed call is red, a stopped one amber. */
internal fun ToolOutcome.disclosureState(): DisclosureState = when (this) {
    ToolOutcome.Running -> DisclosureState.Running
    ToolOutcome.Done -> DisclosureState.Idle
    ToolOutcome.Failed -> DisclosureState.Error
    ToolOutcome.Stopped -> DisclosureState.Stopped
}

/**
 * Whether a settled call counts in the "N failed" of its group's header. A call the turn's stop cut
 * short, and a question the reader closed or left unanswered, are outcomes the row states in their
 * own plain words; none of them is a failure.
 */
internal fun callFailed(call: ToolCallNode, result: ToolResultNode?): Boolean =
    toolOutcome(call.name, result, running = false) == ToolOutcome.Failed

/**
 * What went wrong with [result], or null when it did not fail. A shell command that exited non-zero
 * is not a failure here: the harness reports the exit status in the output (`[exit code: N]`) and
 * marks only spawn errors and aborts as errors (tool-bash's `render.ts`), and the terminal card shows
 * that status itself.
 */
internal fun toolFailureOf(result: ToolResultNode?): ToolFailure? {
    if (result == null || !result.isError) return null
    if (isAbortedResult(result)) return ToolFailure.Interrupted
    permissionDenialOf(result)?.let { denial ->
        val reason = result.errorReason?.trim()?.replace(Regex("[\\r\\n\\u2028\\u2029]+"), " ")?.takeIf { it.isNotEmpty() }
        return ToolFailure.Denied(denial, reason)
    }
    return ToolFailure.Failed(plainFailureMessage(toolFailureMessage(result.content)))
}

/** `Error:`, `TypeError:`, `Exception:` and the like at the start of a tool's failure text. */
private val ERROR_PREFIX = Regex("^(?:[A-Za-z_][A-Za-z0-9_.]*(?:Error|Exception)|Error|error|ERROR|Exception)\\s*:\\s*")

/** The longest failure line the transcript lays out; the whole text stays in the output and the hold menu. */
internal const val FAILURE_LINE_MAX_CHARS = 240

/**
 * A tool's failure text as one plain line to put after the app's own "Failed:". The harness renders
 * a thrown error as `Error: <message>`, so the app's label and the tool's would stack up as
 * "Failed: Error: …"; the prefix is dropped here. Only the first line is kept, cut to
 * [FAILURE_LINE_MAX_CHARS]. Null when nothing is left.
 */
internal fun plainFailureMessage(text: String?): String? {
    if (text == null) return null
    var line = firstLine(text)
    // Bounded: "Error: Error: x" is one prefix said twice, not a loop to trust.
    repeat(3) {
        val stripped = ERROR_PREFIX.replaceFirst(line, "")
        if (stripped == line) return@repeat
        line = stripped
    }
    line = line.trim()
    if (line.isEmpty()) return null
    if (line.length <= FAILURE_LINE_MAX_CHARS) return line
    val cut = line.take(FAILURE_LINE_MAX_CHARS).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
    return cut.trimEnd() + "…"
}

/**
 * Whether the transcript draws an Output row for the call: its card does not already say what the
 * result does, the failure line is not the same words, and there is text to show.
 */
internal fun showsOutputRow(card: ToolCardView, result: ToolResultNode?, failure: ToolFailure?): Boolean {
    val content = result?.content ?: return false
    if (card.coversOutput() || outputRepeatsFailure(result, failure)) return false
    return readToolResult(content)?.text?.isNotBlank() == true
}

/**
 * Whether the output of a failed call says nothing its failure line does not: a single line with the
 * same words. The unknown tool's `Error: unknown tool "x"` was drawn as the failure line and again as
 * the Output text right above it. Output with more to say (a second line, a trace) is kept.
 */
internal fun outputRepeatsFailure(result: ToolResultNode?, failure: ToolFailure?): Boolean {
    if (result == null || failure == null || !result.isError) return false
    val text = readToolResult(result.content)?.text?.trim() ?: return false
    if (text.isEmpty() || text.contains('\n')) return false
    val plain = plainFailureMessage(text)
    return when (failure) {
        is ToolFailure.Failed -> plain != null && plain == failure.message
        // The canonical abort sentences; the stopped line already says it.
        ToolFailure.Interrupted -> plain != null && (plain.startsWith("tool call aborted") || plain == "interrupted")
        is ToolFailure.Denied -> false
    }
}

private val BACKGROUND_STARTED = Regex("^started background job (\\S+)")

/** The job a background shell call started, from its result (`started background job <id>`). */
internal fun backgroundJobId(result: ToolResultNode?): String? =
    result?.takeIf { !it.isError }?.let(::singleResultText)?.trim()?.let { BACKGROUND_STARTED.find(it)?.groupValues?.get(1) }

/**
 * A tool or event identifier as words: `list_subagent_models` reads "List subagent models" and
 * `plugin:acme/sync-run` reads "Plugin acme sync run". For names that have no title of their own;
 * the identifier itself stays one tap away in the hold menu.
 */
internal fun humanizeIdentifier(name: String): String {
    val words = name.split(Regex("[_\\-./:\\s]+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return name
    val joined = words.joinToString(" ")
    return joined.replaceFirstChar { it.uppercaseChar() }
}

/** The tools whose row title is a phrase of the app's own, in the reader's language. */
internal enum class ToolTitle {
    TodoWrite, AskUser, Subagent, Skill, ReadImage,
    JobList, JobOutput, JobKill,
    TerminalOpen, TerminalRead, TerminalList, TerminalSignal, TerminalClose, TerminalSend,
    Lsp, GoalCreate, GoalView, GoalUpdate,
    ScheduleCreate, ScheduleList, ScheduleUpdate, ScheduleDelete, Present,
}

internal fun toolTitle(name: String): ToolTitle? = when (name) {
    "todo_write" -> ToolTitle.TodoWrite
    "ask_user_question" -> ToolTitle.AskUser
    "subagent" -> ToolTitle.Subagent
    "skill" -> ToolTitle.Skill
    "read_image" -> ToolTitle.ReadImage
    "job_list" -> ToolTitle.JobList
    "job_output" -> ToolTitle.JobOutput
    "job_kill" -> ToolTitle.JobKill
    "terminal_open" -> ToolTitle.TerminalOpen
    "terminal_read" -> ToolTitle.TerminalRead
    "terminal_list" -> ToolTitle.TerminalList
    "terminal_signal" -> ToolTitle.TerminalSignal
    "terminal_close" -> ToolTitle.TerminalClose
    "terminal_send" -> ToolTitle.TerminalSend
    "lsp" -> ToolTitle.Lsp
    "create_goal" -> ToolTitle.GoalCreate
    "get_goal" -> ToolTitle.GoalView
    "update_goal" -> ToolTitle.GoalUpdate
    "schedule_create" -> ToolTitle.ScheduleCreate
    "schedule_list" -> ToolTitle.ScheduleList
    "schedule_update" -> ToolTitle.ScheduleUpdate
    "schedule_delete" -> ToolTitle.ScheduleDelete
    "present" -> ToolTitle.Present
    else -> null
}

/** Whether a card already says everything its result does, so the transcript adds no Output row. */
internal fun ToolCardView.coversOutput(): Boolean = when (this) {
    is TodoCard, is QuestionCard, is ImageCard, is DetailsCard -> true
    is SubagentCard -> phase != SubagentPhase.Replied
    is ToolCardView.TerminalCard -> background
    else -> false
}

/**
 * Whether a card speaks for its own ending, so the transcript adds no failure line under it: a
 * question the reader closed, one that timed out, and one the turn's stop cut short each say so in
 * the header and the body.
 */
internal fun ToolCardView.explainsFailure(result: ToolResultNode?): Boolean =
    this is QuestionCard && questionVerdictOf(result) != null

/** The most recent `todo_write` list before [beforeSeq], for the task-list diff; null when none. */
internal fun previousTodoList(nodes: List<com.labteto.dshmobile.core.session.ChatNode>, beforeSeq: Long): List<TodoEntry>? =
    nodes.lastOrNull { it is com.labteto.dshmobile.core.session.TodoNode && it.seq < beforeSeq }
        ?.let { parseTodos((it as com.labteto.dshmobile.core.session.TodoNode).todos) }

// ============================================================================================
// Turn failures
// ============================================================================================

/** A failed turn's `reason.error.code`, as the kind of problem the reader can do something about. */
internal enum class TurnFailure {
    RateLimit, Quota, AccountQuota, ContextFull, EmptyReply, BadKey, MissingKey,
    Network, Timeout, Server, ProcessExited, SignedOut, Unknown,
}

internal fun turnFailureOf(code: String?): TurnFailure = when (code) {
    "RATE_LIMIT" -> TurnFailure.RateLimit
    "QUOTA" -> TurnFailure.Quota
    "ACCOUNT_QUOTA" -> TurnFailure.AccountQuota
    "CONTEXT_WINDOW_EXCEEDED" -> TurnFailure.ContextFull
    "EMPTY_RESPONSE" -> TurnFailure.EmptyReply
    "INVALID_CREDENTIAL", "AUTH", "UNAUTHORIZED" -> TurnFailure.BadKey
    "MISSING_CREDENTIAL" -> TurnFailure.MissingKey
    "TRANSPORT", "NETWORK" -> TurnFailure.Network
    "TIMEOUT" -> TurnFailure.Timeout
    "SERVER" -> TurnFailure.Server
    "INTERRUPTED" -> TurnFailure.ProcessExited
    "ACCOUNT_SIGNED_OUT" -> TurnFailure.SignedOut
    else -> TurnFailure.Unknown
}

// ============================================================================================
// Helpers
// ============================================================================================

private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun parseJson(text: String): JsonElement? = runCatching { WireJson.parseToJsonElement(text) }.getOrNull()

private fun firstLine(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: text.trim()
