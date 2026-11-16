package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.dto.JobView
import com.labteto.dshmobile.core.wire.dto.SessionActivity
import com.labteto.dshmobile.core.wire.dto.SessionActivityItem
import com.labteto.dshmobile.core.wire.dto.SubagentListEntry
import com.labteto.dshmobile.core.wire.dto.WorkspaceArchiveValue
import com.labteto.dshmobile.core.wire.dto.WorkspaceSessionActiveDetails

/**
 * What this client already knows about work running in one session, shaped like the host's own
 * `workspace/session-active` details so one dialog can show either.
 *
 * The turn comes from the session row's `running` flag. Jobs and subagents are only known for the
 * session the store has open, so a drawer row for any other session passes empty lists and is known
 * to be busy only when its turn is running.
 *
 * An empty result means "nothing known", not "idle": the host still has the last word.
 */
internal fun knownArchiveActivity(
    turnRunning: Boolean,
    jobs: List<JobView> = emptyList(),
    subagents: List<SubagentListEntry> = emptyList(),
): List<SessionActivity> = buildList {
    if (turnRunning) add(SessionActivity(kind = "turn"))
    val runningSubagents = subagents.mapNotNull { entry ->
        if (!subagentIsRunning(entry)) return@mapNotNull null
        when (entry) {
            is SubagentListEntry.ChildExternal -> SessionActivityItem(entry.id, entry.label)
            is SubagentListEntry.ChildOneShot -> SessionActivityItem(entry.id, entry.label)
            is SubagentListEntry.ChildContinuable -> SessionActivityItem(entry.id, entry.label)
            else -> null
        }
    }
    if (runningSubagents.isNotEmpty()) add(SessionActivity(kind = "subagent", items = runningSubagents))
    val runningJobs = jobs.filter { it.finishedAt == null }.map { SessionActivityItem(it.id, it.label) }
    if (runningJobs.isNotEmpty()) add(SessionActivity(kind = "job", items = runningJobs))
}

private fun subagentIsRunning(entry: SubagentListEntry): Boolean = when (entry) {
    is SubagentListEntry.ChildExternal -> entry.activity == "running"
    is SubagentListEntry.ChildOneShot -> entry.activity == "running" || entry.activity == "active"
    is SubagentListEntry.ChildContinuable -> entry.activity == "running" || entry.activity == "active"
    else -> false
}

/**
 * The dialog to open when the person taps Archive: null for the ordinary confirm, or a
 * [ArchiveOutcome.Busy] for "Stop and archive" straight away when [known] says work is running.
 *
 * Either way the person confirms once, and nothing is archived before they do.
 */
internal fun archiveStopDialogFor(sessionId: String, known: List<SessionActivity>): ArchiveOutcome.Busy? =
    known.takeIf { it.isNotEmpty() }?.let { ArchiveOutcome.Busy(sessionId, it) }

/**
 * Fold the host's answer to `workspace/archiveSession` into an [ArchiveOutcome].
 *
 * Asking with [stopActivity] never ends in [ArchiveOutcome.Busy]: the host stops what is running
 * and archives, and a session that turned out to be idle just archives, so a screen that guessed
 * "busy" from stale local state still finishes with a normal archive.
 */
internal fun archiveOutcomeOf(
    sessionId: String,
    stopActivity: Boolean,
    result: RpcResult<WorkspaceArchiveValue>,
): ArchiveOutcome = when (result) {
    is RpcResult.Ok -> ArchiveOutcome.Archived
    is RpcResult.Err ->
        if (result.error.code == SESSION_ACTIVE_CODE && !stopActivity) {
            val details = runCatching {
                decodeFromJsonElement(WorkspaceSessionActiveDetails.serializer(), result.error.details)
            }.getOrNull()
            ArchiveOutcome.Busy(sessionId, details?.activity.orEmpty())
        } else {
            ArchiveOutcome.Failed
        }
}

/** Harness 0.1.7's refusal to archive a session with running work. */
internal const val SESSION_ACTIVE_CODE = "workspace/session-active"
