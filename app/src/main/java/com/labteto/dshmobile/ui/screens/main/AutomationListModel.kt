package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.wire.dto.AutomationTask

/**
 * Status filter over the catalog.
 *
 * Harness 0.2.2 replaced the binary `active|inactive` status with `active|paused|ended`, so
 * "inactive" is now a predicate (anything not active) rather than a wire value; a 0.2.1 host's
 * `inactive` rows still match it.
 */
internal enum class AutomationStatusFilter {
    ALL, ACTIVE, INACTIVE,
}

internal enum class AutomationEmptyState { NO_TASKS, NO_MATCHES, NO_INACTIVE_TASKS }

internal data class AutomationListModel(
    val tasks: List<AutomationTask>,
    val emptyState: AutomationEmptyState?,
)

/**
 * Whether a task belongs on the page for one Session.
 *
 * Harness 0.2.2 moved the delivery binding into `target.sessionId` ([AutomationTask.sessionIdEffective])
 * and lists the creating/editing Sessions in `involvedSessionIds`; a task the model created with a
 * `new_once`/`new` target therefore delivers elsewhere but still belongs to the Session that asked
 * for it, so the page matches either.
 */
internal fun AutomationTask.belongsToSession(sessionId: String): Boolean =
    sessionIdEffective == sessionId || sessionId in involvedSessionIds

/** Filter the already session-scoped catalog, following the upstream empty-state precedence. */
internal fun automationListModel(
    tasks: List<AutomationTask>,
    search: String = "",
    statusFilter: AutomationStatusFilter = AutomationStatusFilter.ALL,
): AutomationListModel {
    val query = search.trim()
    val matches = tasks.filter { task ->
        val statusMatches = when (statusFilter) {
            AutomationStatusFilter.ALL -> true
            AutomationStatusFilter.ACTIVE -> task.status == "active"
            AutomationStatusFilter.INACTIVE -> task.status != "active"
        }
        statusMatches && (
            task.title.contains(query, ignoreCase = true) ||
                task.prompt.contains(query, ignoreCase = true) ||
                task.sessionIdEffective.orEmpty().contains(query, ignoreCase = true)
            )
    }
    val emptyState = when {
        matches.isNotEmpty() -> null
        statusFilter == AutomationStatusFilter.INACTIVE && query.isEmpty() -> AutomationEmptyState.NO_INACTIVE_TASKS
        tasks.isEmpty() -> AutomationEmptyState.NO_TASKS
        else -> AutomationEmptyState.NO_MATCHES
    }
    return AutomationListModel(matches, emptyState)
}
