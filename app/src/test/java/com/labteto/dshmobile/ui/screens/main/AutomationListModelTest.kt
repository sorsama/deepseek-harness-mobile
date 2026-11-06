package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.wire.dto.AutomationTask
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class AutomationListModelTest {
    // Harness 0.2.2 moved the delivery Session into `target` and replaced the `inactive` status with
    // `paused|ended`; the legacy top-level `sessionId`/`inactive` entry keeps a 0.2.1 host readable.
    private val active = AutomationTask(
        id = "active", kind = "rrule", title = "Morning report", prompt = "Summarize the weather",
        scheduledAt = "2026-10-07T08:00:00Z", status = "active",
        target = buildJsonObject { put("kind", "session"); put("sessionId", "Session-Alpha") },
        involvedSessionIds = listOf("Session-Alpha"), updatedAt = "2026-10-07T07:00:00Z",
    )
    private val paused = AutomationTask(
        id = "paused", kind = "at", title = "Evening digest", prompt = "Review the calendar",
        scheduledAt = "2026-10-06T20:00:00Z", status = "paused",
        target = buildJsonObject { put("kind", "session"); put("sessionId", "Session-Beta") },
        involvedSessionIds = listOf("Session-Beta"),
    )
    private val ended = paused.copy(id = "ended", title = "Finished review", status = "ended")
    private val legacy = AutomationTask(
        id = "legacy", kind = "daily", title = "Legacy reminder", prompt = "Old wire catalog",
        scheduledAt = "2026-10-05T09:00:00Z", sessionId = "Session-Gamma", status = "inactive",
    )
    private val tasks = listOf(active, paused, ended, legacy)
    private val nonActive = listOf(paused, ended, legacy)

    @Test fun `search matches title prompt and delivery session case insensitively and trims whitespace`() {
        listOf("  MORNING  ", "WEATHER", "session-ALPHA").forEach { query ->
            assertEquals(listOf(active), automationListModel(tasks, query).tasks)
        }
        assertEquals(listOf(legacy), automationListModel(tasks, "session-GAMMA").tasks)
        assertEquals(tasks, automationListModel(tasks, "  ").tasks)
        assertTrue(automationListModel(tasks, "missing").tasks.isEmpty())
    }

    @Test fun `all active and inactive filters follow the paused ended vocabulary`() {
        assertEquals(tasks, automationListModel(tasks, statusFilter = AutomationStatusFilter.ALL).tasks)
        assertEquals(listOf(active), automationListModel(tasks, statusFilter = AutomationStatusFilter.ACTIVE).tasks)
        // INACTIVE is a predicate: 0.2.2's paused/ended and a 0.2.1 host's inactive all count.
        assertEquals(nonActive, automationListModel(tasks, statusFilter = AutomationStatusFilter.INACTIVE).tasks)
        assertEquals(listOf(paused, ended), automationListModel(tasks, "CALENDAR", AutomationStatusFilter.INACTIVE).tasks)
        assertTrue(automationListModel(tasks, "CALENDAR", AutomationStatusFilter.ACTIVE).tasks.isEmpty())
    }

    @Test fun `session scoping follows the delivery target and involved sessions`() {
        assertTrue(active.belongsToSession("Session-Alpha"))
        assertFalse(active.belongsToSession("Session-Beta"))
        assertTrue(legacy.belongsToSession("Session-Gamma"))
        // A `new_once` target delivers to a reserved Session but still belongs to the Session that
        // created it, which the harness lists in `involvedSessionIds`.
        val reserved = paused.copy(
            target = buildJsonObject { put("kind", "new_once"); put("sessionId", "reserved") },
            involvedSessionIds = listOf("Session-Emitting", "reserved"),
        )
        assertTrue(reserved.belongsToSession("Session-Emitting"))
        assertTrue(reserved.belongsToSession("reserved"))
        // A `new` target names no delivery Session at all.
        val fresh = paused.copy(
            target = buildJsonObject { put("kind", "new") },
            involvedSessionIds = listOf("Session-Emitting"),
        )
        assertNull(fresh.sessionIdEffective)
        assertTrue(fresh.belongsToSession("Session-Emitting"))
        assertFalse(fresh.belongsToSession("Session-Beta"))
    }

    @Test fun `populated results have no empty state`() {
        AutomationStatusFilter.entries.forEach { filter ->
            assertNull(automationListModel(tasks, statusFilter = filter).emptyState)
        }
    }

    @Test fun `empty catalog shows no tasks even with a search`() {
        assertEquals(AutomationEmptyState.NO_TASKS, automationListModel(emptyList()).emptyState)
        assertEquals(AutomationEmptyState.NO_TASKS, automationListModel(emptyList(), "report").emptyState)
        assertEquals(AutomationEmptyState.NO_TASKS,
            automationListModel(emptyList(), statusFilter = AutomationStatusFilter.ACTIVE).emptyState)
    }

    @Test fun `nonempty catalog with no search or status matches shows no matches`() {
        assertEquals(AutomationEmptyState.NO_MATCHES, automationListModel(tasks, "absent").emptyState)
        assertEquals(AutomationEmptyState.NO_MATCHES,
            automationListModel(listOf(paused), statusFilter = AutomationStatusFilter.ACTIVE).emptyState)
        assertEquals(AutomationEmptyState.NO_MATCHES,
            automationListModel(listOf(active), "report", AutomationStatusFilter.INACTIVE).emptyState)
    }

    @Test fun `inactive filter with empty search has its own empty state including an empty catalog`() {
        listOf(emptyList(), listOf(active)).forEach { catalog ->
            assertEquals(AutomationEmptyState.NO_INACTIVE_TASKS,
                automationListModel(catalog, "  ", AutomationStatusFilter.INACTIVE).emptyState)
        }
        assertEquals(AutomationEmptyState.NO_TASKS,
            automationListModel(emptyList(), "report", AutomationStatusFilter.INACTIVE).emptyState)
    }
}
