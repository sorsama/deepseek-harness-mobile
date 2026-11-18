package com.labteto.dshmobile.core.team

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The experimental Agent Teams view, read from the Lead session's `agentTeam` projection.
 *
 * Ported from `packages/experimental/agent-team/src/projection.ts` (`teamProjectionSchema`, the
 * wire view) and `src/types.ts` in harness 0.2.1-rc.2. The projection is the feature's whole
 * client-facing protocol: the host registers no Team RPC, and the official web panel is
 * read-only. A host without the Agent Teams bundle switched on publishes no such key, which is
 * what [parseAgentTeam] answers null for.
 */
const val AGENT_TEAM_PROJECTION: String = "agentTeam"

/** `TeamMemberProjection.role`. */
enum class TeamRole { LEAD, TEAMMATE, UNKNOWN }

/** `TeamMemberPhase`: the durable lifecycle; live activity comes from the member's session. */
enum class TeamMemberPhase { PROVISIONING, ACTIVE, FAILED, UNKNOWN }

/** `TeamTaskStatus`. Views omit `deleted` tombstones, and so does [parseAgentTeam]. */
enum class TeamTaskStatus { PENDING, IN_PROGRESS, COMPLETED, UNKNOWN }

/** One roster row. The wire view carries no description or model; see [TeamMemberRow]. */
data class TeamMember(
    val id: String,
    val name: String,
    val role: TeamRole,
    val phase: TeamMemberPhase,
    val error: String? = null,
)

/** One task on the shared board (`TeamTaskView`). */
data class TeamTask(
    val id: String,
    val revision: Long,
    val subject: String,
    val description: String,
    val status: TeamTaskStatus,
    val blockedBy: List<String> = emptyList(),
    val writeScopes: List<String> = emptyList(),
    /** The owner's roster name (`lead` for the Lead); absent while nobody has claimed the task. */
    val ownerName: String? = null,
    /** Pending with every blocker completed: the host's own readiness, not recomputed here. */
    val ready: Boolean = false,
    /** Advisory overlap notes in the host's own words (English), e.g. `write scopes overlap with task-1`. */
    val writeScopeWarnings: List<String> = emptyList(),
)

/** The whole projection value. [failure] is a rejected persisted record shown above the last valid state. */
data class AgentTeam(
    val members: List<TeamMember>,
    val tasks: List<TeamTask>,
    val failure: String? = null,
) {
    val teammates: List<TeamMember> get() = members.filter { it.role != TeamRole.LEAD }

    /** Whether the team has anything beyond the implicit Lead: a teammate or a task. */
    val hasContent: Boolean get() = teammates.isNotEmpty() || tasks.isNotEmpty()
}

/**
 * Decode an `agentTeam` projection value. Null when the value is absent or not an object, which
 * is how a host without Agent Teams looks. Rows without an id are dropped, as are deleted tasks;
 * unknown enum words decode as `UNKNOWN` rather than failing the whole view.
 */
fun parseAgentTeam(value: JsonElement?): AgentTeam? {
    val root = value as? JsonObject ?: return null
    val members = (root["members"] as? JsonArray).orEmpty().mapNotNull { parseMember(it) }
    val tasks = (root["tasks"] as? JsonArray).orEmpty().mapNotNull { parseTask(it) }
    return AgentTeam(members = members, tasks = tasks, failure = root.string("failure"))
}

private fun parseMember(element: JsonElement): TeamMember? {
    val obj = element as? JsonObject ?: return null
    val id = obj.string("id")?.takeIf { it.isNotEmpty() } ?: return null
    return TeamMember(
        id = id,
        name = obj.string("name")?.takeIf { it.isNotEmpty() } ?: id,
        role = when (obj.string("role")) {
            "lead" -> TeamRole.LEAD
            "teammate" -> TeamRole.TEAMMATE
            else -> TeamRole.UNKNOWN
        },
        phase = when (obj.string("phase")) {
            "provisioning" -> TeamMemberPhase.PROVISIONING
            "active" -> TeamMemberPhase.ACTIVE
            "failed" -> TeamMemberPhase.FAILED
            else -> TeamMemberPhase.UNKNOWN
        },
        error = obj.string("error")?.takeIf { it.isNotBlank() },
    )
}

private fun parseTask(element: JsonElement): TeamTask? {
    val obj = element as? JsonObject ?: return null
    val id = obj.string("id")?.takeIf { it.isNotEmpty() } ?: return null
    val status = when (obj.string("status")) {
        "pending" -> TeamTaskStatus.PENDING
        "in_progress" -> TeamTaskStatus.IN_PROGRESS
        "completed" -> TeamTaskStatus.COMPLETED
        "deleted" -> return null
        else -> TeamTaskStatus.UNKNOWN
    }
    return TeamTask(
        id = id,
        revision = (obj["revision"] as? JsonPrimitive)?.longOrNull ?: 0L,
        subject = obj.string("subject").orEmpty(),
        description = obj.string("description").orEmpty(),
        status = status,
        blockedBy = obj.strings("blockedBy"),
        writeScopes = obj.strings("writeScopes"),
        ownerName = obj.string("ownerName")?.takeIf { it.isNotEmpty() },
        ready = (obj["ready"] as? JsonPrimitive)?.booleanOrNull ?: false,
        writeScopeWarnings = obj.strings("writeScopeWarnings"),
    )
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }

// ---------------------------------------------------------------------------------------------
// Roster
// ---------------------------------------------------------------------------------------------

/**
 * What a roster row says, as the official panel derives it: the durable phase, with `active`
 * split by the member session's live activity.
 */
enum class TeamMemberStatus { RUNNING, INACTIVE, PROVISIONING, FAILED }

fun teamMemberStatus(member: TeamMember, running: Boolean): TeamMemberStatus = when (member.phase) {
    TeamMemberPhase.ACTIVE -> if (running) TeamMemberStatus.RUNNING else TeamMemberStatus.INACTIVE
    TeamMemberPhase.PROVISIONING -> TeamMemberStatus.PROVISIONING
    TeamMemberPhase.FAILED -> TeamMemberStatus.FAILED
    // A phase from a newer harness: claim nothing is happening rather than invent a state.
    TeamMemberPhase.UNKNOWN -> TeamMemberStatus.INACTIVE
}

/**
 * One roster row ready to draw.
 *
 * [description] is the teammate's job, which the Team view does not carry: the host records it
 * as the child's `subagentCatalog` label (`roster.ts` passes `label: description` to
 * `startActivation`). [openable] is true only for an active teammate the Lead's catalog lists as
 * continuable, which is what opening its conversation through the subagent address needs.
 */
data class TeamMemberRow(
    val member: TeamMember,
    val status: TeamMemberStatus,
    val description: String?,
    val isCurrent: Boolean,
    val openable: Boolean,
    /** Tasks this member owns that are not completed yet. */
    val openTasks: Int,
)

/**
 * Build the roster rows for the Lead session [currentSessionId].
 *
 * @param running whether a member's session is running right now (the app's own session status).
 * @param continuableLabels the Lead's continuable children by id, with their catalog label.
 */
fun teamMemberRows(
    team: AgentTeam,
    currentSessionId: String?,
    running: (String) -> Boolean,
    continuableLabels: Map<String, String?>,
): List<TeamMemberRow> = team.members.map { member ->
    val isCurrent = member.id == currentSessionId
    val status = teamMemberStatus(member, running(member.id))
    TeamMemberRow(
        member = member,
        status = status,
        description = continuableLabels[member.id]?.takeIf { it.isNotBlank() && it != member.name },
        isCurrent = isCurrent,
        openable = !isCurrent &&
            member.role == TeamRole.TEAMMATE &&
            member.phase == TeamMemberPhase.ACTIVE &&
            member.id in continuableLabels,
        openTasks = team.tasks.count { it.ownerName == member.name && it.status != TeamTaskStatus.COMPLETED },
    )
}

/** The roster member that owns [task], matched by name as the host derives `ownerName`. */
fun teamTaskOwner(team: AgentTeam, task: TeamTask): TeamMember? =
    task.ownerName?.let { owner -> team.members.firstOrNull { it.name == owner } }

// ---------------------------------------------------------------------------------------------
// Task board
// ---------------------------------------------------------------------------------------------

/**
 * Where a task sits on a phone's single-column board. The web board marks the same four states
 * with a dot (ongoing, idle when ready, warning when blocked, done); here they are sections.
 */
enum class TeamTaskGroup { IN_PROGRESS, READY, BLOCKED, OTHER, COMPLETED }

fun teamTaskGroup(task: TeamTask): TeamTaskGroup = when (task.status) {
    TeamTaskStatus.IN_PROGRESS -> TeamTaskGroup.IN_PROGRESS
    TeamTaskStatus.PENDING -> if (task.ready) TeamTaskGroup.READY else TeamTaskGroup.BLOCKED
    TeamTaskStatus.COMPLETED -> TeamTaskGroup.COMPLETED
    TeamTaskStatus.UNKNOWN -> TeamTaskGroup.OTHER
}

/** One non-empty section of the board, in board order within the section. */
data class TeamTaskSection(val group: TeamTaskGroup, val tasks: List<TeamTask>)

/**
 * Group the board by state: work happening now first, then what can start, what waits, and what
 * is finished last. Empty sections are left out; the order inside a section is the host's.
 */
fun groupTeamTasks(tasks: List<TeamTask>): List<TeamTaskSection> {
    val byGroup = tasks.groupBy(::teamTaskGroup)
    return TeamTaskGroup.entries.mapNotNull { group ->
        byGroup[group]?.takeIf { it.isNotEmpty() }?.let { TeamTaskSection(group, it) }
    }
}

/** Whether a section starts collapsed: finished work is history, the rest is what to look at. */
fun teamTaskSectionStartsCollapsed(group: TeamTaskGroup): Boolean = group == TeamTaskGroup.COMPLETED

/** One dependency of a task, resolved against the board. [subject] is null for a task not on it. */
data class TeamTaskBlocker(val id: String, val subject: String?, val done: Boolean)

/**
 * The tasks [task] depends on, in its own order. A dependency missing from the board was deleted
 * (views omit tombstones); the host still counts it as not completed (`taskReady` in
 * `task-view.ts`), so it is reported as not done, with no subject.
 */
fun teamTaskBlockers(task: TeamTask, board: List<TeamTask>): List<TeamTaskBlocker> =
    task.blockedBy.map { id ->
        val other = board.firstOrNull { it.id == id }
        TeamTaskBlocker(
            id = id,
            subject = other?.subject?.takeIf { it.isNotBlank() },
            done = other?.status == TeamTaskStatus.COMPLETED,
        )
    }

/** The dependencies still holding [task] up; empty when it is not waiting on anything. */
fun teamTaskWaitingOn(task: TeamTask, board: List<TeamTask>): List<TeamTaskBlocker> =
    if (task.status == TeamTaskStatus.PENDING && !task.ready) teamTaskBlockers(task, board).filterNot { it.done } else emptyList()

private val overlapWarning = Regex("^write scopes overlap with (\\S+)$")

/**
 * The in-progress task a `writeScopeWarnings` entry names, so the warning can be said in the
 * reader's language. The host writes these in English only (`task-view.ts`:
 * `write scopes overlap with <taskId>`); anything else answers null and is shown as sent.
 */
fun teamWriteOverlapTaskId(warning: String): String? = overlapWarning.find(warning.trim())?.groupValues?.get(1)

/** How many tasks are not completed yet. */
fun teamOpenTaskCount(team: AgentTeam): Int = team.tasks.count { it.status != TeamTaskStatus.COMPLETED }
