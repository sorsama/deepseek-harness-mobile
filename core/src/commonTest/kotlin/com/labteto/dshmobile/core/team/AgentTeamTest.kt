package com.labteto.dshmobile.core.team

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `agentTeam` projection as harness 0.2.1-rc.2 publishes it. Payloads are the wire views the
 * harness's own tests assert, in `packages/experimental/agent-team/tests/projection-events.spec.ts`:
 * the "projects durable roster and non-deleted task views" case (two members, an in-progress task
 * and a blocked one), the live-registry case (a fresh Lead with no tasks) and the failure case.
 */
class AgentTeamTest {

    /** projection-events.spec.ts: the view after member, claim, blocked task and a deleted task-3. */
    private val boardView = """
        {"members":[
          {"id":"root","name":"lead","role":"lead","phase":"active"},
          {"id":"child-a","name":"worker-a","role":"teammate","phase":"active"}
        ],
        "tasks":[
          {"id":"task-1","revision":2,"subject":"subject","description":"description","status":"in_progress",
           "blockedBy":[],"writeScopes":["src"],"ownerName":"worker-a","ready":false,"writeScopeWarnings":[]},
          {"id":"task-2","revision":1,"subject":"subject","description":"description","status":"pending",
           "blockedBy":["task-1"],"writeScopes":["src/web"],"ready":false,
           "writeScopeWarnings":["write scopes overlap with task-1"]}
        ]}
    """.trimIndent()

    /** projection-events.spec.ts: `sessionProjections.snapshot(lead).values['agentTeam']` for a new Lead. */
    private val freshLead = """{"members":[{"id":"lead-1","name":"lead","role":"lead","phase":"active"}],"tasks":[]}"""

    private fun parse(text: String): AgentTeam = assertNotNull(parseAgentTeam(Json.parseToJsonElement(text)))

    @Test
    fun theHarnessBoardViewDecodesEveryField() {
        val team = parse(boardView)
        assertEquals(listOf("lead", "worker-a"), team.members.map { it.name })
        assertEquals(TeamRole.LEAD, team.members[0].role)
        assertEquals(TeamRole.TEAMMATE, team.members[1].role)
        assertEquals(TeamMemberPhase.ACTIVE, team.members[1].phase)
        val claimed = team.tasks[0]
        assertEquals("task-1", claimed.id)
        assertEquals(2L, claimed.revision)
        assertEquals(TeamTaskStatus.IN_PROGRESS, claimed.status)
        assertEquals("worker-a", claimed.ownerName)
        assertEquals(listOf("src"), claimed.writeScopes)
        val blocked = team.tasks[1]
        assertNull(blocked.ownerName)
        assertFalse(blocked.ready)
        assertEquals(listOf("task-1"), blocked.blockedBy)
        assertEquals(listOf("write scopes overlap with task-1"), blocked.writeScopeWarnings)
        assertNull(team.failure)
        assertTrue(team.hasContent)
    }

    @Test
    fun aFreshLeadHasOnlyItselfAndNoContent() {
        val team = parse(freshLead)
        assertEquals(1, team.members.size)
        assertTrue(team.teammates.isEmpty())
        assertFalse(team.hasContent)
        assertEquals(0, teamOpenTaskCount(team))
    }

    @Test
    fun anAbsentOrNonObjectValueMeansNoTeam() {
        assertNull(parseAgentTeam(null))
        assertNull(parseAgentTeam(JsonNull))
        assertNull(parseAgentTeam(JsonPrimitive("agentTeam")))
    }

    @Test
    fun aFailureIsKeptBesideTheLastValidState() {
        // projection-events.spec.ts "publishes the failure with the last valid roster and tasks".
        val team = parse(
            """{"members":[{"id":"root","name":"lead","role":"lead","phase":"active"},
               {"id":"child-a","name":"worker-a","role":"teammate","phase":"provisioning"}],
               "tasks":[{"id":"task-1","revision":1,"subject":"subject","description":"description",
               "status":"pending","blockedBy":[],"writeScopes":[],"ready":true,"writeScopeWarnings":[]}],
               "failure":"unsupported Agent Teams event version 1"}""",
        )
        assertEquals("unsupported Agent Teams event version 1", team.failure)
        assertEquals(2, team.members.size)
        assertEquals(1, team.tasks.size)
    }

    @Test
    fun unknownWordsAndBrokenRowsDoNotSinkTheView() {
        val team = parse(
            """{"members":[{"name":"no-id"},{"id":"m2","name":"x","role":"observer","phase":"sleeping"}],
               "tasks":[{"subject":"no id"},{"id":"t9","status":"archived","subject":"later"},
               {"id":"t3","status":"deleted"}]}""",
        )
        assertEquals(listOf("m2"), team.members.map { it.id })
        assertEquals(TeamRole.UNKNOWN, team.members[0].role)
        assertEquals(TeamMemberPhase.UNKNOWN, team.members[0].phase)
        assertEquals(TeamMemberStatus.INACTIVE, teamMemberStatus(team.members[0], running = true))
        // Deleted tombstones are left out, as the host's own view does.
        assertEquals(listOf("t9"), team.tasks.map { it.id })
        assertEquals(TeamTaskGroup.OTHER, teamTaskGroup(team.tasks[0]))
    }

    @Test
    fun memberStatusFollowsTheWebPanel() {
        val active = TeamMember("c", "worker", TeamRole.TEAMMATE, TeamMemberPhase.ACTIVE)
        assertEquals(TeamMemberStatus.RUNNING, teamMemberStatus(active, running = true))
        assertEquals(TeamMemberStatus.INACTIVE, teamMemberStatus(active, running = false))
        val provisioning = active.copy(phase = TeamMemberPhase.PROVISIONING)
        assertEquals(TeamMemberStatus.PROVISIONING, teamMemberStatus(provisioning, running = true))
        val failed = active.copy(phase = TeamMemberPhase.FAILED, error = "provider failed")
        assertEquals(TeamMemberStatus.FAILED, teamMemberStatus(failed, running = false))
    }

    @Test
    fun rosterRowsMarkTheLeadAsCurrentAndOnlyOpenCatalogTeammates() {
        val team = parse(boardView).let {
            it.copy(
                members = it.members + TeamMember("child-b", "worker-b", TeamRole.TEAMMATE, TeamMemberPhase.PROVISIONING) +
                    TeamMember("child-c", "worker-c", TeamRole.TEAMMATE, TeamMemberPhase.ACTIVE),
            )
        }
        // roster.ts records a teammate's description as its subagentCatalog label; child-c is
        // active but not (yet) in the catalog, so there is no address to open it by.
        val rows = teamMemberRows(
            team,
            currentSessionId = "root",
            running = { it == "child-a" },
            continuableLabels = mapOf("child-a" to "Review the diff", "child-b" to "Write tests"),
        )
        val (lead, a, b, c) = rows
        assertTrue(lead.isCurrent)
        assertFalse(lead.openable)
        assertEquals(TeamMemberStatus.RUNNING, a.status)
        assertTrue(a.openable)
        assertEquals("Review the diff", a.description)
        assertEquals(1, a.openTasks)
        assertEquals(TeamMemberStatus.PROVISIONING, b.status)
        assertFalse(b.openable)
        assertFalse(c.openable)
        assertNull(c.description)
    }

    @Test
    fun aLabelThatOnlyRepeatsTheNameIsNotADescription() {
        val team = parse(boardView)
        val rows = teamMemberRows(team, "root", { false }, mapOf("child-a" to "worker-a"))
        assertNull(rows[1].description)
    }

    @Test
    fun theBoardGroupsByStateInWorkOrder() {
        val task = TeamTask("x", 1, "s", "d", TeamTaskStatus.PENDING)
        val board = listOf(
            task.copy(id = "done-1", status = TeamTaskStatus.COMPLETED),
            task.copy(id = "blocked-1", blockedBy = listOf("work-1")),
            task.copy(id = "ready-1", ready = true),
            task.copy(id = "work-1", status = TeamTaskStatus.IN_PROGRESS),
            task.copy(id = "ready-2", ready = true),
        )
        val sections = groupTeamTasks(board)
        assertEquals(
            listOf(TeamTaskGroup.IN_PROGRESS, TeamTaskGroup.READY, TeamTaskGroup.BLOCKED, TeamTaskGroup.COMPLETED),
            sections.map { it.group },
        )
        // Board order is kept inside a section.
        assertEquals(listOf("ready-1", "ready-2"), sections[1].tasks.map { it.id })
        assertTrue(teamTaskSectionStartsCollapsed(TeamTaskGroup.COMPLETED))
        assertFalse(teamTaskSectionStartsCollapsed(TeamTaskGroup.BLOCKED))
        assertEquals(4, teamOpenTaskCount(AgentTeam(emptyList(), board)))
    }

    @Test
    fun anEmptyBoardHasNoSections() {
        assertTrue(groupTeamTasks(emptyList()).isEmpty())
    }

    @Test
    fun aBlockedTaskSaysWhatItWaitsOn() {
        val team = parse(boardView)
        val blocked = team.tasks[1]
        val waiting = teamTaskWaitingOn(blocked, team.tasks)
        assertEquals(listOf(TeamTaskBlocker("task-1", "subject", done = false)), waiting)
        // An in-progress task is not waiting, whatever its list says.
        assertTrue(teamTaskWaitingOn(team.tasks[0], team.tasks).isEmpty())
    }

    @Test
    fun aDeletedDependencyStillCountsAsNotDone() {
        // task-view.ts `taskReady`: a blocker must be found and completed; a tombstone is neither.
        val task = TeamTask("t2", 1, "s", "d", TeamTaskStatus.PENDING, blockedBy = listOf("gone", "t1"))
        val board = listOf(task, TeamTask("t1", 3, "first", "d", TeamTaskStatus.COMPLETED))
        assertEquals(
            listOf(TeamTaskBlocker("gone", null, done = false), TeamTaskBlocker("t1", "first", done = true)),
            teamTaskBlockers(task, board),
        )
        assertEquals(listOf("gone"), teamTaskWaitingOn(task, board).map { it.id })
    }

    @Test
    fun theHostOverlapWarningNamesItsTask() {
        // task-view.ts `projectTaskView`: warnings.add(`write scopes overlap with ${other.id}`).
        assertEquals("task-1", teamWriteOverlapTaskId("write scopes overlap with task-1"))
        assertNull(teamWriteOverlapTaskId("something else entirely"))
    }

    @Test
    fun theOwnerIsFoundByRosterName() {
        val team = parse(boardView)
        assertEquals("child-a", teamTaskOwner(team, team.tasks[0])?.id)
        assertNull(teamTaskOwner(team, team.tasks[1]))
        val leadOwned = team.tasks[0].copy(ownerName = "lead")
        assertEquals(TeamRole.LEAD, teamTaskOwner(team, leadOwned)?.role)
    }
}
