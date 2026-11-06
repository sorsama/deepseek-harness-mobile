package com.labteto.dshmobile.ui.screens.main

import androidx.annotation.StringRes
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.AgentPresetEntry
import com.labteto.dshmobile.core.wire.dto.AgentPresetListValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetTrust
import com.labteto.dshmobile.core.wire.dto.GoalPhase
import com.labteto.dshmobile.core.wire.dto.JobStatus
import com.labteto.dshmobile.core.wire.dto.SubagentListEntry
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsTheme

/** Shared label and status mappings for the chat surface. */

@StringRes
internal fun goalPhaseLabelRes(phase: GoalPhase): Int = when (phase) {
    GoalPhase.ACTIVE -> R.string.goal_phase_active
    GoalPhase.PAUSED -> R.string.goal_phase_paused
    GoalPhase.BLOCKED -> R.string.goal_phase_blocked
    GoalPhase.COMPLETE -> R.string.goal_phase_complete
    // A phase from a newer harness: show the goal with no phase word rather than an invented one.
    GoalPhase.UNKNOWN -> R.string.goal_phase_active
}

internal fun todoStatusDot(status: String): StateDotState = when (status) {
    "completed" -> StateDotState.Done
    "in_progress" -> StateDotState.Running
    else -> StateDotState.Idle
}

internal fun jobStatusDot(status: JobStatus): StateDotState = when (status) {
    JobStatus.RUNNING, JobStatus.STOPPING -> StateDotState.Running
    JobStatus.COMPLETED -> StateDotState.Done
    JobStatus.KILLED, JobStatus.FAILED -> StateDotState.Error
    // Neither running nor settled as far as this build can tell, so claim neither.
    JobStatus.UNKNOWN -> StateDotState.Idle
}

/** A job's lifecycle in words, or null for a state this build cannot name. */
@Composable
internal fun jobStatusLabel(status: JobStatus): String? = when (status) {
    JobStatus.RUNNING -> stringResource(R.string.jobs_running)
    JobStatus.STOPPING -> stringResource(R.string.jobs_stopping)
    JobStatus.COMPLETED -> stringResource(R.string.jobs_completed)
    JobStatus.KILLED -> stringResource(R.string.jobs_killed)
    JobStatus.FAILED -> stringResource(R.string.jobs_failed)
    JobStatus.UNKNOWN -> null
}

@Composable
internal fun workflowStatusLabel(status: String?): String? = when (status) {
    "running" -> stringResource(R.string.workflow_running)
    "completed" -> stringResource(R.string.workflow_completed)
    "failed", "error", "cancelled" -> stringResource(R.string.workflow_failed)
    else -> null
}

internal fun workflowMemberDot(status: String?): StateDotState = when (status) {
    "running" -> StateDotState.Running
    "completed" -> StateDotState.Done
    "failed", "error", "cancelled" -> StateDotState.Error
    else -> StateDotState.Idle
}

// ---------------------------------------------------------------------------
// Subagents
// ---------------------------------------------------------------------------

internal fun subagentId(entry: SubagentListEntry): String? = when (entry) {
    is SubagentListEntry.ChildExternal -> entry.id
    is SubagentListEntry.ChildOneShot -> entry.id
    is SubagentListEntry.ChildContinuable -> entry.id
    is SubagentListEntry.Diagnostic -> entry.id
    else -> null
}

internal fun subagentLabel(entry: SubagentListEntry): String? = when (entry) {
    is SubagentListEntry.ChildExternal -> entry.label
    is SubagentListEntry.ChildOneShot -> entry.label
    is SubagentListEntry.ChildContinuable -> entry.label
    is SubagentListEntry.Diagnostic -> entry.reason
    else -> null
}

internal fun subagentRunning(entry: SubagentListEntry): Boolean = when (entry) {
    is SubagentListEntry.ChildExternal -> entry.activity == "running"
    is SubagentListEntry.ChildOneShot -> entry.activity == "running" || entry.activity == "active"
    is SubagentListEntry.ChildContinuable -> entry.activity == "running" || entry.activity == "active"
    else -> false
}

// ---------------------------------------------------------------------------
// Agent presets
// ---------------------------------------------------------------------------

/**
 * Localized copy for the four presets the harness ships.
 *
 * The host does *not* localize these. It reads `name`/`description` straight out of each preset's
 * `preset.yml`, and those files are written in Chinese — so `agentPreset.list` answers `标准模式`
 * whatever language the client is in. The harness's own web UI covers this by overriding the
 * built-ins with its own translations, and this is the same table for the same four ids.
 *
 * Keyed on `trust == SYSTEM` exactly as the reference is: a preset someone wrote themselves and
 * happened to call `standard` is theirs, and keeps the name they gave it.
 */
private fun builtInPresetStrings(id: String): Pair<Int, Int>? = when (id) {
    "standard" -> R.string.preset_standard_name to R.string.preset_standard_desc
    "code" -> R.string.preset_code_name to R.string.preset_code_desc
    "minimal" -> R.string.preset_minimal_name to R.string.preset_minimal_desc
    "cordis" -> R.string.preset_cordis_name to R.string.preset_cordis_desc
    else -> null
}

private fun builtInPresetStrings(entry: AgentPresetEntry): Pair<Int, Int>? =
    if (entry.trust == AgentPresetTrust.SYSTEM) builtInPresetStrings(entry.id) else null

/**
 * What to call the preset a session is pinned to, given only its id.
 *
 * The roster is host-scoped and arrives on its own schedule, and the chip renders as soon as the
 * session does — so without this the top bar showed a raw wire id (`standard`) until something
 * happened to fetch the list. Falling back to the shipped name for a shipped id is right in every
 * deployment that has not replaced that preset, and corrects itself the moment the roster lands.
 */
@Composable
internal fun agentPresetLabel(id: String, roster: AgentPresetListValue?): String {
    val entry = roster?.presets?.firstOrNull { it.id == id }
    if (entry != null) return entry.displayName()
    return builtInPresetStrings(id)?.let { stringResource(it.first) } ?: id
}

/**
 * What to call a preset: the app's own translation for a shipped one, else whatever the deployment
 * named it, else the raw id.
 */
@Composable
internal fun AgentPresetEntry.displayName(): String =
    builtInPresetStrings(this)?.let { stringResource(it.first) }
        ?: name?.takeIf { it.isNotBlank() }
        ?: id

/** The preset's one-line summary, translated for the shipped four. Null when there is none. */
@Composable
internal fun AgentPresetEntry.displayDescription(): String? =
    builtInPresetStrings(this)?.let { stringResource(it.second) }
        ?: description?.takeIf { it.isNotBlank() }

// ---------------------------------------------------------------------------
// Shared field styling
// ---------------------------------------------------------------------------

@Composable
internal fun dialogTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = DsTheme.colors.bgLayer2,
    unfocusedContainerColor = DsTheme.colors.bgLayer2,
    disabledContainerColor = DsTheme.colors.bgLayer1,
    // No underline: the field's outline comes from `inkFieldFrame`, so it reads as the same plate as
    // every other input in the app. The default underline on a rounded plate is clipped to a stub.
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    cursorColor = DsTheme.colors.accent,
)

/** A field that sits *inside* a composer card: no plate and no outline of its own, the card is the frame. */
@Composable
internal fun composerFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    cursorColor = DsTheme.colors.accent,
    focusedTextColor = DsTheme.colors.labelPrimary,
    unfocusedTextColor = DsTheme.colors.labelPrimary,
)
