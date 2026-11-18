package com.labteto.dshmobile.ui.components

/**
 * View models for the purpose-built cards of the harness's control tools: the task list, questions,
 * subagents, skills, images and the record-style tools (jobs, terminals, goals, schedules, language
 * server, deliverables). Pure data like [ToolCardView]'s other members; the words are chosen where
 * they are drawn ([ChatToolCards.kt]), so nothing here is a string a reader sees untranslated except
 * what the tool itself recorded (a command, a path, a task's own text).
 *
 * The derivations live in `ChatToolMapping.kt` and are a port of the harness web client's view
 * models (`packages/client/ui-tool/src/client/tool/models`).
 */

/** Where one to-do stands. */
enum class TodoMark { Pending, InProgress, Completed }

/** How a to-do changed against the list written before it. */
enum class TodoChange { Added, StatusChanged, Moved, Removed }

/** One to-do as a card lists it. [previous] is its old mark when [change] is [TodoChange.StatusChanged]. */
data class TodoLine(
    val content: String,
    val mark: TodoMark,
    val change: TodoChange? = null,
    val previous: TodoMark? = null,
)

/**
 * A `todo_write` call.
 *
 * The list itself is drawn by the transcript's own to-do node, which the tool appends as it runs, so
 * a recorded write lists only what changed ([lines]). A write the harness did not record (it failed)
 * left no such node, so its card lists everything it attempted, with [recorded] false.
 */
data class TodoCard(
    val done: Int,
    val total: Int,
    /** The first task in progress, for the header. */
    val active: String?,
    /** How many more tasks are in progress beside [active]. */
    val activeExtra: Int,
    val added: Int,
    val updated: Int,
    val removed: Int,
    /** No earlier list was written in this conversation. */
    val initial: Boolean,
    val recorded: Boolean,
    val lines: List<TodoLine>,
) : ToolCardView

/** What became of an `ask_user_question` call. */
enum class QuestionOutcome {
    /** Still waiting for the reader. */
    Waiting,

    /** Answered (some answers may have been skipped). */
    Answered,

    /** The reader dismissed the questions (`cancelled` from this app, `ASK_CANCELLED` from the web client). */
    Cancelled,

    /** The turn was stopped while they were open (`ASK_ABORTED`, or any abort form of the harness). */
    Interrupted,

    /** Nobody answered in time (`ASK_TIMED_OUT`). */
    TimedOut,

    /** The agent carried on without waiting; the answer may still come later. */
    CarriedOn,

    /** Settled, but the result is not one this build can pair with the questions. */
    Settled,
}

/** One question and, once there is one, the answer given to it (empty when it was skipped). */
data class QuestionLine(
    val question: String,
    val header: String? = null,
    val options: List<String> = emptyList(),
    val answers: List<String>? = null,
)

data class QuestionCard(
    val questions: List<QuestionLine>,
    val outcome: QuestionOutcome,
    val answered: Int = 0,
    val total: Int = questions.size,
) : ToolCardView

/** How far a `subagent` call got, from its own result. [Stopped] is a call the turn's stop cut short. */
enum class SubagentPhase { Starting, Started, Replied, Failed, Stopped }

/**
 * A `subagent` call: the child it started. [childId] is what the Subagents sheet opens it by; it is
 * known once the call has settled.
 */
data class SubagentCard(
    val title: String,
    val prompt: String?,
    val model: String?,
    val phase: SubagentPhase,
    val childId: String? = null,
    /** A backend that answers in the result itself (an older harness): the child's reply. */
    val reply: String? = null,
) : ToolCardView

/** How far a `skill` call got. */
enum class SkillPhase { Loading, Loaded, Failed }

/** A `skill` call: which skill was loaded, and the first line of what it says. */
data class SkillCard(val name: String, val description: String?, val phase: SkillPhase) : ToolCardView

/** The recorded facts of one image a tool returned. */
data class ImageFacts(val width: Int, val height: Int, val bytes: Int, val mediaType: String)

/** A `read_image` call. The image itself is drawn from the result's attachment under the card. */
data class ImageCard(val path: String, val images: List<ImageFacts>) : ToolCardView

/** Which record-style tool a [DetailsCard] is, for its title and its empty and count wording. */
enum class DetailsTool {
    JobList, JobOutput, JobKill,
    TerminalOpen, TerminalRead, TerminalList, TerminalSignal, TerminalClose,
    Lsp,
    GoalView, GoalCreate, GoalUpdate,
    ScheduleCreate, ScheduleUpdate, ScheduleList, ScheduleDelete,
    Present,
}

/** A recorded state, named in the reader's language where it is drawn. */
enum class DetailState {
    Running, Stopping, Completed, Failed, Killed, Exited,
    Started, CancelRequested, AlreadyFinished, SignalDelivered, Closed, Closing,
    Scheduled, Overdue, Deleted,
    GoalActive, GoalWaiting, GoalPaused, GoalBlocked, GoalComplete, GoalCleared,
    Pending, InProgress,
    /** A state word this build does not know; [DetailItem.stateRaw] carries it as recorded. */
    Other,
}

/** The label of one recorded field. */
enum class DetailLabel { Type, Pid, ExitCode, Signal, ProcessGroup, Lines, Rounds, Reason, When, Repeats, Location, Kind }

/** A field's value: recorded text, or a schedule's repeat rule in words. */
sealed interface DetailValue {
    data class Text(val text: String, val technical: Boolean = false) : DetailValue
    data class Repeat(val rule: ScheduleRepeat) : DetailValue
}

/** How often a schedule fires, in the shape the harness records it. */
sealed interface ScheduleRepeat {
    data object Once : ScheduleRepeat
    data class Every(val seconds: Long) : ScheduleRepeat
    data class Daily(val time: String, val zone: String) : ScheduleRepeat
    data class Weekly(val days: List<Int>, val time: String, val zone: String) : ScheduleRepeat
    data class Cron(val expression: String, val zone: String) : ScheduleRepeat
}

data class DetailField(val label: DetailLabel, val value: DetailValue)

/** One recorded entity: a job, a terminal, a location, a schedule, a goal. */
data class DetailItem(
    val title: String,
    /** The title is technical text (a command, an id, a path): drawn left to right and in mono. */
    val technicalTitle: Boolean = false,
    val subtitle: String? = null,
    val state: DetailState? = null,
    val stateRaw: String? = null,
    val fields: List<DetailField> = emptyList(),
    val description: String? = null,
    /** Output in the terminal card's style. */
    val code: String? = null,
)

/**
 * A record-style tool's result as rows, the port of the web client's `ToolDetailsModel`.
 *
 * [summary] is the header's subject when it is recorded text (a job id, a schedule's title); null
 * lets the header say a count or the state instead. [truncated] is the tool's own "output
 * truncated" marker.
 */
data class DetailsCard(
    val tool: DetailsTool,
    val items: List<DetailItem>,
    val summary: String? = null,
    val truncated: Boolean = false,
    /** A goal or schedule call whose own node in the transcript already shows it: header only. */
    val headerOnly: Boolean = false,
) : ToolCardView
