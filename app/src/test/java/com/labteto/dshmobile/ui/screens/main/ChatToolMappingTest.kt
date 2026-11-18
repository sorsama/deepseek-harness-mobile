package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.permission.PermissionDenial
import com.labteto.dshmobile.core.session.TodoNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.ui.components.DetailLabel
import com.labteto.dshmobile.ui.components.DetailState
import com.labteto.dshmobile.ui.components.DetailValue
import com.labteto.dshmobile.ui.components.DetailsCard
import com.labteto.dshmobile.ui.components.DetailsTool
import com.labteto.dshmobile.ui.components.ImageCard
import com.labteto.dshmobile.ui.components.QuestionCard
import com.labteto.dshmobile.ui.components.QuestionOutcome
import com.labteto.dshmobile.ui.components.ScheduleRepeat
import com.labteto.dshmobile.ui.components.SkillCard
import com.labteto.dshmobile.ui.components.SkillPhase
import com.labteto.dshmobile.ui.components.SubagentCard
import com.labteto.dshmobile.ui.components.SubagentPhase
import com.labteto.dshmobile.ui.components.TodoCard
import com.labteto.dshmobile.ui.components.TodoChange
import com.labteto.dshmobile.ui.components.TodoMark
import com.labteto.dshmobile.ui.components.ToolCardView
import com.labteto.dshmobile.ui.components.hasBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control tools' cards and the transcript's failure and naming rules.
 *
 * Payloads are the harness's own, from its source and tests at 0.2.1-rc.2 (each test names where):
 * a card that is right for an invented shape and wrong for the real one is worse than none.
 */
class ChatToolMappingTest {

    private fun call(name: String, args: String, seq: Long = 10, id: String = "c1") = ToolCallNode(seq, id, name, args, 1, 1)

    private fun text(value: String): JsonElement = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", value) }))

    private fun result(
        text: String?,
        isError: Boolean = false,
        meta: JsonElement? = null,
        errorCode: String? = null,
        errorName: String? = null,
        errorReason: String? = null,
        id: String = "c1",
    ) = ToolResultNode(20, id, text?.let(::text) ?: JsonArray(emptyList()), isError, 1, 1, meta, errorCode, errorName, errorReason)

    private fun card(name: String, args: String, result: ToolResultNode? = null, previousTodos: List<TodoEntry>? = null): ToolCardView =
        buildToolCardView(call(name, args), result, running = result == null, cwd = "/w", previousTodos = previousTodos)

    private fun obj(json: String) = WireJson.parseToJsonElement(json) as JsonObject

    // ---- Task list -------------------------------------------------------------------------

    // tool-todo/tests/tool-todo.spec.ts, "returns the canonical list": plan in progress, build pending.
    private val firstWrite = """{"todos":[{"content":"plan","status":"in_progress"},{"content":"build","status":"pending"}]}"""

    @Test
    fun `the first todo write is one line and lists nothing the dock below already shows`() {
        val todo = card("todo_write", firstWrite, result("Updated todo list: 1 pending, 1 in progress, 0 completed.")) as TodoCard
        assertEquals(0, todo.done)
        assertEquals(2, todo.total)
        assertEquals("plan", todo.active)
        assertTrue(todo.initial)
        assertTrue(todo.recorded)
        assertTrue(todo.lines.isEmpty())
        // Nothing to open: the list itself is the transcript's todo node right below the call.
        assertFalse(todo.hasBody())
        assertTrue(todo.coversOutput())
    }

    @Test
    fun `a later write lists only what changed against the write before it`() {
        // tool-todo.spec.ts, "replaces the whole list": [a pending] then [a completed and b in progress].
        val previous = listOf(TodoEntry("a", "pending"))
        val args = """{"todos":[{"content":"a","status":"completed"},{"content":"b","status":"in_progress"}]}"""
        val todo = card("todo_write", args, result("Updated todo list: 0 pending, 1 in progress, 1 completed."), previous) as TodoCard
        assertFalse(todo.initial)
        assertEquals(1, todo.added)
        assertEquals(1, todo.updated)
        assertEquals(0, todo.removed)
        assertEquals(1, todo.done)
        val a = todo.lines.single { it.content == "a" }
        assertEquals(TodoChange.StatusChanged, a.change)
        assertEquals(TodoMark.Pending, a.previous)
        assertEquals(TodoMark.Completed, a.mark)
        assertEquals(TodoChange.Added, todo.lines.single { it.content == "b" }.change)
    }

    @Test
    fun `a dropped item reads as removed and a reordered one as moved`() {
        val previous = listOf(TodoEntry("one", "pending"), TodoEntry("two", "pending"), TodoEntry("three", "pending"))
        val args = """{"todos":[{"content":"two","status":"pending"},{"content":"one","status":"pending"}]}"""
        val todo = todoCard(obj(args), result("ok"), previous)!!
        assertEquals(1, todo.removed)
        assertEquals(TodoChange.Removed, todo.lines.single { it.content == "three" }.change)
        assertTrue(todo.lines.any { it.change == TodoChange.Moved })
    }

    @Test
    fun `an unchanged list says so and has nothing to open`() {
        val previous = listOf(TodoEntry("plan", "in_progress"), TodoEntry("build", "pending"))
        val todo = todoCard(obj(firstWrite), result("ok"), previous)!!
        assertEquals(0, todo.added + todo.updated + todo.removed)
        assertFalse(todo.hasBody())
    }

    @Test
    fun `a failed write lists what it tried to write because no todo node was recorded`() {
        // tool-todo.spec.ts: duplicate content is rejected, so nothing is appended to the session.
        val todo = card("todo_write", firstWrite, result("Error: invalid todos: duplicate content", isError = true)) as TodoCard
        assertFalse(todo.recorded)
        assertEquals(listOf("plan", "build"), todo.lines.map { it.content })
        assertTrue(todo.hasBody())
    }

    @Test
    fun `a todo shape the tool would reject gets the generic card`() {
        // tool-todo.spec.ts: status "doing" is outside the enum.
        assertTrue(card("todo_write", """{"todos":[{"content":"x","status":"doing"}]}""") is ToolCardView.GenericCard)
        assertTrue(card("todo_write", """{"todos":[{"content":"x","status":"pending"},{"content":" x ","status":"pending"}]}""") is ToolCardView.GenericCard)
    }

    @Test
    fun `the previous list is the last todo node before the call`() {
        val nodes = listOf(
            TodoNode(1, WireJson.parseToJsonElement("""[{"content":"old","status":"pending"}]""")),
            TodoNode(5, WireJson.parseToJsonElement("""[{"content":"newer","status":"completed"}]""")),
            TodoNode(12, WireJson.parseToJsonElement("""[{"content":"this call's own","status":"pending"}]""")),
        )
        assertEquals(listOf(TodoEntry("newer", "completed")), previousTodoList(nodes, beforeSeq = 10))
        assertNull(previousTodoList(nodes, beforeSeq = 1))
    }

    // ---- Questions -------------------------------------------------------------------------

    // ui-tool/tests/ask-question-row.client.spec.tsx, READABLE_ARGS.
    private val questions = """{"questions":[
        {"id":"goal","question":"What do you want to accomplish?","options":[{"label":"Ship it"},{"label":"Explore"}]},
        {"id":"scope","question":"Which project should this apply to?"},
        {"id":"notes","question":"Anything else?"}]}"""

    @Test
    fun `an open question waits for the reader`() {
        val q = card("ask_user_question", questions) as QuestionCard
        assertEquals(QuestionOutcome.Waiting, q.outcome)
        assertEquals(listOf("Ship it", "Explore"), q.questions.first().options)
        assertNull(q.questions.first().answers)
    }

    @Test
    fun `answers pair with their questions by id`() {
        // The result shape is the spec's `answers([...])`: { answers: [{ id, selected, custom? }] }.
        val answers = """{"answers":[{"id":"scope","selected":["dsh-mobile"]},{"id":"goal","selected":["Ship it"],"custom":"today"},{"id":"notes","selected":[]}]}"""
        val q = card("ask_user_question", questions, result(answers)) as QuestionCard
        assertEquals(QuestionOutcome.Answered, q.outcome)
        assertEquals(2, q.answered)
        assertEquals(3, q.total)
        assertEquals(listOf("Ship it", "today"), q.questions[0].answers)
        assertEquals(listOf("dsh-mobile"), q.questions[1].answers)
        // Skipped: answered with nothing.
        assertEquals(emptyList<String>(), q.questions[2].answers)
    }

    @Test
    fun `a batch that does not match the questions keeps only its count`() {
        val answers = """{"answers":[{"id":"goal","selected":["x"]}]}"""
        val q = card("ask_user_question", questions, result(answers)) as QuestionCard
        assertEquals(1, q.answered)
        assertTrue(q.questions.all { it.answers == null })
    }

    // What this app sends when the reader dismisses the set (core `QUESTION_CANCELLED`, docs/PROTOCOL.md),
    // recorded by the harness as the tool's error: seen in a device run's raw call data.
    private fun dismissed() = result("Error: the user closed this question request", isError = true,
        errorCode = "cancelled", errorName = "UserQuestionError")

    // user-questions/src/index.ts `abortedQuestion`: the turn was stopped while the question was open.
    private fun stoppedWhileOpen() = result("Error: ask_user_question was aborted before the user answered", isError = true,
        errorCode = "ASK_ABORTED", errorName = "UserQuestionError")

    @Test
    fun `a dismissed or interrupted question set says so itself`() {
        val cancelled = dismissed()
        val q = card("ask_user_question", questions, cancelled) as QuestionCard
        assertEquals(QuestionOutcome.Cancelled, q.outcome)
        // The card states the outcome, so the transcript adds no red failure line under it.
        assertTrue(q.explainsFailure(cancelled))
        val aborted = stoppedWhileOpen()
        val stopped = card("ask_user_question", questions, aborted) as QuestionCard
        assertEquals(QuestionOutcome.Interrupted, stopped.outcome)
        assertTrue(stopped.explainsFailure(aborted))
    }

    @Test
    fun `the web client's own dismissal code is read the same way`() {
        // ui-user-questions slots.ts: `reject('ASK_CANCELLED')`, "the user cancelled ask_user_question".
        val web = result("Error: the user cancelled ask_user_question", isError = true,
            errorCode = "ASK_CANCELLED", errorName = "UserQuestionError")
        assertEquals(QuestionOutcome.Cancelled, (card("ask_user_question", questions, web) as QuestionCard).outcome)
    }

    @Test
    fun `a timed question the agent moved past reads as carried on`() {
        // ask-question-row.client.spec.tsx, PENDING_RESULT.
        val q = card("ask_user_question", questions, result("""{"pending":true,"callId":"c1"}""")) as QuestionCard
        assertEquals(QuestionOutcome.CarriedOn, q.outcome)
    }

    @Test
    fun `questions whose ids repeat get the generic card`() {
        assertTrue(card("ask_user_question", """{"questions":[{"id":"a","question":"x"},{"id":"a","question":"y"}]}""") is ToolCardView.GenericCard)
    }

    // ---- Subagents -------------------------------------------------------------------------

    // tool-subagent/src/index.ts: description and prompt are required; the result renders
    // `started subagent ${subagentId}`.
    private val subagentArgs = """{"description":"Audit the tool cards","prompt":"Read ToolCards.kt and list gaps.","model":"deepseek-v4"}"""

    @Test
    fun `a subagent call names its task and model and the child it started`() {
        val started = card("subagent", subagentArgs, result("started subagent sess-child-1")) as SubagentCard
        assertEquals("Audit the tool cards", started.title)
        assertEquals("deepseek-v4", started.model)
        assertEquals(SubagentPhase.Started, started.phase)
        assertEquals("sess-child-1", started.childId)
        assertTrue(started.coversOutput())
    }

    @Test
    fun `a subagent call is starting until its result and failed on an error`() {
        assertEquals(SubagentPhase.Starting, (card("subagent", subagentArgs) as SubagentCard).phase)
        val failed = card("subagent", subagentArgs, result("Error: subagent tool requires a calling agent", isError = true)) as SubagentCard
        assertEquals(SubagentPhase.Failed, failed.phase)
        assertNull(failed.childId)
    }

    @Test
    fun `a background subagent job is not a child the sheet can open`() {
        // control-details-model.ts: `started background subagent job <id>` names a job.
        val job = card("subagent", subagentArgs, result("started background subagent job job-7")) as SubagentCard
        assertEquals(SubagentPhase.Started, job.phase)
        assertNull(job.childId)
    }

    @Test
    fun `a backend that answers inline keeps its reply in the output`() {
        val replied = card("subagent", subagentArgs, result("Found three gaps.")) as SubagentCard
        assertEquals(SubagentPhase.Replied, replied.phase)
        assertFalse(replied.coversOutput())
    }

    @Test
    fun `a subagent descriptor gives the child's label and model`() {
        // subagent/src/descriptor.ts: a continuable descriptor carries label, agentProvider and agentModel.
        val facts = subagentDescriptorFacts(WireJson.parseToJsonElement(
            """{"version":3,"mode":"continuable","provider":"spawn","label":"Audit the tool cards","agentProvider":"deepseek","agentModel":"deepseek-v4"}""",
        ))
        assertEquals("Audit the tool cards", facts.label)
        assertEquals("deepseek-v4", facts.model)
    }

    // ---- Skills and images -----------------------------------------------------------------

    @Test
    fun `a skill call shows the skill and the first line of what it says`() {
        // skill/skill/src/index.ts, renderSkillContent.
        val body = listOf(
            "<skill_content name=\"pdf\">",
            "<skill_resources>",
            "Resources for this skill are managed by provider \"fs\".",
            "</skill_resources>",
            "",
            "<skill_instructions>",
            "# PDF tools",
            "",
            "Read, merge and split PDF files.",
            "</skill_instructions>",
            "</skill_content>",
        ).joinToString("\n")
        val skill = card("skill", """{"name":"pdf"}""", result(body)) as SkillCard
        assertEquals("pdf", skill.name)
        assertEquals("PDF tools", skill.description)
        assertEquals(SkillPhase.Loaded, skill.phase)
        assertEquals(SkillPhase.Loading, (card("skill", """{"name":"pdf"}""") as SkillCard).phase)
        assertEquals(SkillPhase.Failed, (card("skill", """{"name":"pdf"}""", result("Error: invalid skill name", isError = true)) as SkillCard).phase)
    }

    @Test
    fun `a skill's front matter description wins over its first heading`() {
        assertEquals("Read and fill PDF forms.", skillDescription("---\nname: pdf\ndescription: Read and fill PDF forms.\n---\n# PDF"))
    }

    @Test
    fun `a read image card shows the recorded path and the image facts`() {
        // image-card-model.ts: meta carries the resolved path; the content carries the attachment.
        val content = WireJson.parseToJsonElement(
            """[{"type":"text","text":"<path>/w/shot.png</path>\n<type>image</type>\n<content>\nimage/png\n</content>"},
               {"type":"image","attachment":{"attachmentId":"att-1","mediaType":"image/png","bytes":20480,"width":1280,"height":720}}]""",
        )
        val res = ToolResultNode(20, "c1", content, false, 1, 1, obj("""{"path":"/w/shot.png"}"""))
        val image = card("read_image", """{"file_path":"shot.png"}""", res) as ImageCard
        assertEquals("/w/shot.png", image.path)
        assertEquals(1280, image.images.single().width)
        assertEquals(20480, image.images.single().bytes)
    }

    // ---- Jobs, terminals, the language server ----------------------------------------------

    @Test
    fun `job_list rows read the command with its state and id`() {
        // ui-tool/tests/details-models.client.spec.ts: 'j1 [bash] running — Build'.
        val jobs = card("job_list", "{}", result("j1 [bash] running — Build")) as DetailsCard
        val row = jobs.items.single()
        assertEquals("Build", row.title)
        assertEquals("j1", row.subtitle)
        assertEquals(DetailState.Running, row.state)
        assertEquals(DetailLabel.Kind, row.fields.single().label)
        // The shell is named as a person would: Bash, and for `pwsh` PowerShell.
        assertEquals(DetailValue.Text("Bash", technical = false), row.fields.single().value)
        val pwsh = (card("job_list", "{}", result("j2 [pwsh] running — Build")) as DetailsCard).items.single()
        assertEquals(DetailValue.Text("PowerShell", technical = false), pwsh.fields.single().value)
        assertTrue((card("job_list", "{}", result("(no background jobs)")) as DetailsCard).items.isEmpty())
    }

    @Test
    fun `job_output shows the output in the terminal style with its state`() {
        // details-models.client.spec.ts with tool-jobs' statusLine: `[status: <s>, <detail>]`.
        val done = card("job_output", """{"job_id":"j1"}""", result("hello\n[status: completed, exit code: 0]")) as DetailsCard
        assertEquals("hello", done.items.single().code)
        assertEquals("exit code: 0", done.items.single().description)
        assertEquals(DetailState.Completed, done.items.single().state)
        val cut = card("job_output", """{"job_id":"j1"}""", result("hello\n[output truncated]\n[status: completed]")) as DetailsCard
        assertTrue(cut.truncated)
        assertEquals("hello", cut.items.single().code)
    }

    @Test
    fun `job_kill reads as a request or as already finished`() {
        val asked = card("job_kill", """{"job_id":"j1","reason":"stop"}""", result("requested cancellation of job j1")) as DetailsCard
        assertEquals(DetailState.CancelRequested, asked.items.single().state)
        assertEquals("stop", asked.items.single().description)
        val late = card("job_kill", """{"job_id":"j1"}""", result("job j1 had already finished completed")) as DetailsCard
        assertEquals(DetailState.AlreadyFinished, late.items.single().state)
        assertTrue(card("job_kill", """{"job_id":"j1"}""", result("unexpected")) is ToolCardView.GenericCard)
    }

    @Test
    fun `terminal tools read the harness's own renderings`() {
        // tool-terminal/src/render.ts: renderSpawn, renderRead, renderList.
        val opened = card("terminal_open", "{}", result("started terminal session pty-1 [type: shell]\nready")) as DetailsCard
        assertEquals("pty-1", opened.items.single().title)
        assertEquals("ready", opened.items.single().code)
        val read = card("terminal_read", """{"sessionId":"pty-1"}""", result("line\n[lines: 0-1 of 2]\n[output truncated]")) as DetailsCard
        assertEquals("line", read.items.single().code)
        assertTrue(read.truncated)
        val list = card("terminal_list", "{}", result("pty-1 (build) [shell] running pid=12\npty-2 [shell] exited code=0 signal=null")) as DetailsCard
        assertEquals(listOf("build", "pty-2"), list.items.map { it.title })
        assertEquals(DetailState.Exited, list.items[1].state)
        val signal = card("terminal_signal", """{"sessionId":"pty-1"}""", result("delivered SIGINT to foreground process group 12")) as DetailsCard
        assertEquals(DetailState.SignalDelivered, signal.items.single().state)
        val closed = card("terminal_close", """{"sessionId":"pty-1"}""", result("terminal session pty-1 was already closing")) as DetailsCard
        assertEquals(DetailState.Closing, closed.items.single().state)
    }

    @Test
    fun `lsp lists locations and shows hover text`() {
        // details-models.client.spec.ts: findReferences locations, "No results.", and hover.
        val args = """{"file_path":"src/main.ts","line":2,"character":3,"operation":"findReferences"}"""
        val refs = card("lsp", args, result("src/a.ts:2:3\nC:\\src\\a.ts:8:9")) as DetailsCard
        assertEquals(listOf("src/a.ts", "C:\\src\\a.ts"), refs.items.map { it.title })
        assertTrue((card("lsp", args, result("No results.")) as DetailsCard).items.isEmpty())
        val hover = card("lsp", args.replace("findReferences", "hover"), result("hover text")) as DetailsCard
        assertEquals("hover text", hover.items.single().description)
    }

    // ---- Goals, schedules, deliverables ----------------------------------------------------

    // tool-goal/src/index.ts, goalValue: { goal: { id, revision, objective, phase, roundsStarted, maxGoalRounds }, activation }.
    private val goalValue = """{"goal":{"id":"g1","revision":2,"objective":"Ship 0.24.0","phase":"active","roundsStarted":1,"maxGoalRounds":5},"activation":"armed"}"""

    @Test
    fun `setting or changing a goal is one line because the goal card follows`() {
        val created = card("create_goal", """{"objective":"Ship 0.24.0"}""", result(goalValue)) as DetailsCard
        assertTrue(created.headerOnly)
        assertFalse(created.hasBody())
        assertEquals("Ship 0.24.0", created.summary)
        assertEquals(DetailState.GoalActive, created.items.single().state)
        // While it runs, the objective comes from the call itself.
        val running = card("create_goal", """{"objective":"Ship 0.24.0"}""") as DetailsCard
        assertEquals("Ship 0.24.0", running.summary)
        val paused = card("update_goal", """{"goal_id":"g1","revision":2,"action":"pause"}""") as DetailsCard
        assertEquals(DetailState.GoalPaused, paused.items.single().state)
    }

    @Test
    fun `checking the goal shows its state and rounds`() {
        val view = card("get_goal", "{}", result(goalValue.replace("\"armed\"", "\"disarmed\""))) as DetailsCard
        val goal = view.items.single()
        assertEquals(DetailState.GoalWaiting, goal.state)
        assertEquals(DetailValue.Text("1 / 5", false), goal.fields.single { it.label == DetailLabel.Rounds }.value)
        assertTrue((card("get_goal", "{}", result("""{"goal":null}""")) as DetailsCard).items.isEmpty())
        assertTrue(card("create_goal", """{"objective":"x"}""", result("not-json")) is ToolCardView.GenericCard)
    }

    @Test
    fun `a new reminder reads its title time and repeat rule`() {
        // details-models.client.spec.ts, "keeps recorded dates readable".
        val at = """{"id":"schedule-1","prompt":"Review the build","kind":"at","scheduledAt":"2026-09-10T09:00:00.000Z","state":"scheduled","deliveryMode":"session-local"}"""
        val made = card("schedule_create", "{}", result(at)) as DetailsCard
        val item = made.items.single()
        assertEquals("Review the build", item.title)
        assertEquals(DetailState.Scheduled, item.state)
        assertEquals(DetailValue.Repeat(ScheduleRepeat.Once), item.fields.single { it.label == DetailLabel.Repeats }.value)
        val weekly = scheduleItem(WireJson.parseToJsonElement(
            """{"id":"s2","prompt":"p","title":"Standup","kind":"weekly","weekdays":[1,3],"time":"09:00:00.000","timeZone":"Asia/Bangkok","scheduledAt":"2026-09-14T02:00:00.000Z","state":"overdue","deliveryMode":"host"}""",
        ))!!
        assertEquals("Standup", weekly.title)
        assertEquals(DetailState.Overdue, weekly.state)
        assertEquals(DetailValue.Repeat(ScheduleRepeat.Weekly(listOf(1, 3), "09:00", "Asia/Bangkok")), weekly.fields[1].value)
    }

    @Test
    fun `a reminder the harness would not have written gets the generic card`() {
        // details-models.client.spec.ts: an invalid scheduledAt is declined.
        val bad = """{"id":"s","prompt":"x","kind":"at","scheduledAt":"invalid","state":"scheduled","deliveryMode":"session-local"}"""
        assertTrue(card("schedule_create", "{}", result(bad)) is ToolCardView.GenericCard)
    }

    @Test
    fun `deleting and listing reminders`() {
        val deleted = card("schedule_delete", "{}", result("""{"id":"schedule-1","deleted":true}""")) as DetailsCard
        assertEquals(DetailState.Deleted, deleted.items.single().state)
        val none = card("schedule_list", "{}", result("[]")) as DetailsCard
        assertEquals(DetailsTool.ScheduleList, none.tool)
        assertTrue(none.items.isEmpty())
    }

    @Test
    fun `sharing files is one line because each file gets its own card below`() {
        // deliverables/tool-present/src/index.ts: renders `Presented <path>` per file and appends
        // `deliverables/presented`, which the transcript draws as a card per file.
        val shared = card("present", """{"files":[{"path":"out/report.docx","description":"The report"}]}""", result("Presented out/report.docx")) as DetailsCard
        assertTrue(shared.headerOnly)
        assertEquals("report.docx", shared.summary)
    }

    // ---- Shell: background, failures, exit codes -------------------------------------------

    // shell/tool-bash/src/index.ts parameters; ui-tool/tests/terminal-card.client.spec.tsx shellArgs.
    private fun shellArgs(extra: String = "") = """{"command":"ls -la","description":"List files"$extra}"""

    @Test
    fun `a background command keeps the terminal card with no output and the job it started`() {
        // terminal-card.client.spec.tsx: 'started background job bash-1\n[exit code: 7]' with meta.cwd '/recorded'.
        val launched = buildToolCardView(
            call("bash", shellArgs(""","run_in_background":true,"workdir":"/requested"""")),
            result("started background job bash-1\n[exit code: 7]", meta = obj("""{"cwd":"/recorded"}""")),
            running = false,
        ) as ToolCardView.TerminalCard
        assertTrue(launched.background)
        assertEquals("bash-1", launched.jobId)
        assertEquals("/recorded", launched.cwd)
        // The acknowledgement is not output, and the launch has no exit status of its own.
        assertNull(launched.output)
        assertNull(launched.exitCode)
        assertFalse(launched.failed)
        assertTrue(launched.coversOutput())
    }

    @Test
    fun `a background command still starting has no job yet`() {
        val starting = card("pwsh", shellArgs(""","run_in_background":true""")) as ToolCardView.TerminalCard
        assertTrue(starting.background)
        assertEquals(true, starting.running)
        assertNull(starting.jobId)
    }

    @Test
    fun `a command that exits non zero is ordinary output with its exit code`() {
        // shell/tool-bash/src/render.ts: "Non-zero exits are reported, not errored": the result is
        // not an error, and the marker `[exit code: N]` closes the text.
        val res = result("npm ERR! test failed\n[exit code: 1]")
        val term = card("bash", shellArgs(), res) as ToolCardView.TerminalCard
        assertEquals("npm ERR! test failed", term.output)
        assertEquals(1, term.exitCode)
        assertFalse(term.failed)
        assertNull(toolFailureOf(res))
    }

    @Test
    fun `a shell call that failed before running keeps its card and says why`() {
        val res = result("Error: run_in_background is disabled for this deployment\nmore detail", isError = true)
        val term = card("bash", shellArgs(), res) as ToolCardView.TerminalCard
        assertTrue(term.failed)
        assertEquals("ls -la", term.title)
        assertNull(term.exitCode)
        // The app's own "Failed:" goes in front, so the tool's "Error:" is not said twice.
        assertEquals(ToolFailure.Failed("run_in_background is disabled for this deployment"), toolFailureOf(res))
    }

    @Test
    fun `a persistent shell shows its output without an invented exit status`() {
        // The persistent-shell providers omit `description` (ToolCardMapping's shellCall).
        val term = card("bash", """{"command":"cd app"}""", result("ok")) as ToolCardView.TerminalCard
        assertEquals("ok", term.output)
        assertNull(term.exitCode)
    }

    @Test
    fun `a failed edit keeps the diff it attempted`() {
        val edit = card("edit", """{"file_path":"/w/a.kt","old_string":"val a = 1","new_string":"val a = 2"}""",
            result("Error: old_string not found", isError = true)) as ToolCardView.DiffCard
        assertEquals("val a = 1", edit.diffs.single().oldText)
        assertEquals("val a = 2", edit.diffs.single().newText)
        val editor = card("str_replace_editor", """{"command":"str_replace","path":"/w/a.kt","old_str":"x","new_str":"y"}""", result("ok"))
        assertTrue(editor is ToolCardView.DiffCard)
    }

    @Test
    fun `an interrupted call reads as stopped not failed`() {
        // ui-tool tool-call-model.ts: error.code 'interrupted' is the stopped state.
        assertEquals(ToolFailure.Interrupted, toolFailureOf(result("Error: interrupted", isError = true, errorCode = "interrupted")))
    }

    @Test
    fun `a command cut short by the Stop button reads as stopped not failed`() {
        // The raw call data of a pwsh `Start-Sleep -Seconds 600` stopped on a device (the
        // harness's `toolAbortedResult`): isError, "Error: tool call aborted", error { AbortError, ABORTED }.
        val res = result("Error: tool call aborted", isError = true, errorCode = "ABORTED", errorName = "AbortError")
        assertEquals(ToolFailure.Interrupted, toolFailureOf(res))
        assertFalse(callFailed(call("pwsh", shellArgs()), res))
        // The card still shows what ran, and its own dot is the row's: stopped, not failed.
        val term = card("pwsh", shellArgs(), res) as ToolCardView.TerminalCard
        assertTrue(term.stopped)
        assertFalse(term.failed)
        assertEquals("ls -la", term.title)
    }

    @Test
    fun `an auto review refusal carries the reviewer's reason on one line`() {
        val res = result("Error: denied", isError = true, errorCode = "AUTO_REVIEW_DENIED",
            errorName = "AutoReviewDeniedError", errorReason = "Deletes files\noutside the workspace")
        assertEquals(ToolFailure.Denied(PermissionDenial.AutoReviewRejected, "Deletes files outside the workspace"), toolFailureOf(res))
    }

    @Test
    fun `the built in gate's denials are still read from their code`() {
        val res = result("Error: reworded", isError = true, errorCode = "APPROVAL_REJECTED_BY_USER", errorName = "DshMobilePermissionDenied")
        assertEquals(ToolFailure.Denied(PermissionDenial.Rejected, null), toolFailureOf(res))
    }

    // ---- Names, titles and the generic card ------------------------------------------------

    @Test
    fun `tool and event identifiers read as words`() {
        assertEquals("List subagent models", humanizeIdentifier("list_subagent_models"))
        assertEquals("Plugin acme sync run", humanizeIdentifier("plugin:acme/sync-run"))
        assertEquals("Workspace changes", humanizeIdentifier("workspace/changes"))
    }

    @Test
    fun `a tool with no view of its own gets a readable title and its subject`() {
        val row = toolRowModel("list_subagent_models", """{"provider":"deepseek","description":"See what can run"}""", cwd = null)
        assertEquals("List subagent models", row.title)
        assertEquals("See what can run", row.summary)
    }

    @Test
    fun `powershell rows say PowerShell`() {
        assertEquals("PowerShell", toolRowModel("pwsh", """{"command":"Get-ChildItem","description":"List files"}""", null).title)
        assertEquals("Bash", toolRowModel("bash", """{"command":"ls","description":"List files"}""", null).title)
    }

    @Test
    fun `every tool from the brief has a title of its own`() {
        val tools = listOf(
            "todo_write", "ask_user_question", "subagent", "skill", "read_image", "job_list", "job_output", "job_kill",
            "create_goal", "get_goal", "update_goal", "schedule_create", "schedule_list", "schedule_update", "schedule_delete",
            "terminal_open", "terminal_read", "terminal_list", "terminal_signal", "terminal_close", "terminal_send", "present", "lsp",
        )
        tools.forEach { assertNotNull(it, toolTitle(it)) }
        assertNull(toolTitle("mcp__github__search"))
    }

    @Test
    fun `an unknown tool keeps the generic card with its arguments`() {
        val generic = card("mcp__github__search", """{"q":"cards"}""", result("3 hits")) as ToolCardView.GenericCard
        assertEquals("""{"q":"cards"}""", generic.rawInput)
        assertFalse(generic.coversOutput())
        assertEquals(1, com.labteto.dshmobile.ui.components.topLevelFieldCount(generic.rawInput!!))
    }

    @Test
    fun `turn failure codes map to plain causes`() {
        assertEquals(TurnFailure.RateLimit, turnFailureOf("RATE_LIMIT"))
        assertEquals(TurnFailure.Quota, turnFailureOf("QUOTA"))
        assertEquals(TurnFailure.AccountQuota, turnFailureOf("ACCOUNT_QUOTA"))
        assertEquals(TurnFailure.ContextFull, turnFailureOf("CONTEXT_WINDOW_EXCEEDED"))
        assertEquals(TurnFailure.EmptyReply, turnFailureOf("EMPTY_RESPONSE"))
        assertEquals(TurnFailure.BadKey, turnFailureOf("INVALID_CREDENTIAL"))
        assertEquals(TurnFailure.ProcessExited, turnFailureOf("INTERRUPTED"))
        assertEquals(TurnFailure.Unknown, turnFailureOf("UNKNOWN"))
        assertEquals(TurnFailure.Unknown, turnFailureOf(null))
    }

    @Test
    fun `the raw call data keeps arguments result meta and error facts`() {
        val raw = toolRawJson(
            call("bash", shellArgs()),
            result("Error: x", isError = true, meta = obj("""{"cwd":"/w"}"""), errorCode = "AUTO_REVIEW_DENIED", errorName = "AutoReviewDeniedError", errorReason = "why"),
        )
        val parsed = WireJson.parseToJsonElement(raw) as JsonObject
        assertEquals(JsonPrimitive("bash"), parsed["name"])
        assertEquals("ls -la", ((parsed["arguments"] as JsonObject)["command"] as JsonPrimitive).content)
        assertEquals("/w", ((parsed["meta"] as JsonObject)["cwd"] as JsonPrimitive).content)
        assertEquals("why", ((parsed["error"] as JsonObject)["reason"] as JsonPrimitive).content)
    }

    @Test
    fun `the preview bound cuts the new cards too`() {
        val many = (1..20).joinToString(",") { """{"content":"t$it","status":"pending"}""" }
        val todo = todoCard(obj("""{"todos":[$many]}"""), result("x", isError = true), null)!!
        val clipped = clipCardForPreview(todo)
        assertTrue(clipped.clipped)
        assertEquals(PREVIEW_MAX_ITEMS, (clipped.view as TodoCard).lines.size)
    }
}
