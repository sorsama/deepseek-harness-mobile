package com.labteto.dshmobile.ui.screens.main

import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.goal_phase_active
import com.labteto.dshmobile.shared.resources.goal_phase_blocked
import com.labteto.dshmobile.shared.resources.goal_phase_complete
import com.labteto.dshmobile.shared.resources.goal_phase_paused
import com.labteto.dshmobile.shared.resources.jobs_completed
import com.labteto.dshmobile.shared.resources.jobs_failed
import com.labteto.dshmobile.shared.resources.jobs_killed
import com.labteto.dshmobile.shared.resources.jobs_running
import com.labteto.dshmobile.shared.resources.jobs_stopping
import com.labteto.dshmobile.shared.resources.preset_code_desc
import com.labteto.dshmobile.shared.resources.preset_code_name
import com.labteto.dshmobile.shared.resources.preset_cordis_desc
import com.labteto.dshmobile.shared.resources.preset_cordis_name
import com.labteto.dshmobile.shared.resources.preset_minimal_desc
import com.labteto.dshmobile.shared.resources.preset_minimal_name
import com.labteto.dshmobile.shared.resources.preset_standard_desc
import com.labteto.dshmobile.shared.resources.preset_standard_name
import com.labteto.dshmobile.shared.resources.workflow_completed
import com.labteto.dshmobile.shared.resources.workflow_failed
import com.labteto.dshmobile.shared.resources.workflow_running
import com.labteto.dshmobile.core.wire.dto.AgentPresetEntry
import com.labteto.dshmobile.core.wire.dto.AgentPresetListValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetTrust
import com.labteto.dshmobile.core.wire.dto.GoalPhase
import com.labteto.dshmobile.core.wire.dto.JobStatus
import com.labteto.dshmobile.core.wire.dto.SubagentListEntry
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsTheme

/** Shared label and status mappings for the chat surface. */

internal fun goalPhaseLabelRes(phase: GoalPhase): StringResource = when (phase) {
    GoalPhase.ACTIVE -> Res.string.goal_phase_active
    GoalPhase.PAUSED -> Res.string.goal_phase_paused
    GoalPhase.BLOCKED -> Res.string.goal_phase_blocked
    GoalPhase.COMPLETE -> Res.string.goal_phase_complete
    // A phase from a newer harness: show the goal with no phase word rather than an invented one.
    GoalPhase.UNKNOWN -> Res.string.goal_phase_active
}

/** A goal's phase in words, for the goal dock and the session details. */
@Composable
fun goalPhaseLabel(phase: GoalPhase): String = stringResource(goalPhaseLabelRes(phase))

fun todoStatusDot(status: String): StateDotState = when (status) {
    "completed" -> StateDotState.Done
    "in_progress" -> StateDotState.Running
    else -> StateDotState.Idle
}

fun jobStatusDot(status: JobStatus): StateDotState = when (status) {
    JobStatus.RUNNING, JobStatus.STOPPING -> StateDotState.Running
    JobStatus.COMPLETED -> StateDotState.Done
    JobStatus.KILLED, JobStatus.FAILED -> StateDotState.Error
    // Neither running nor settled as far as this build can tell, so claim neither.
    JobStatus.UNKNOWN -> StateDotState.Idle
}

/** A job's lifecycle in words, or null for a state this build cannot name. */
@Composable
fun jobStatusLabel(status: JobStatus): String? = when (status) {
    JobStatus.RUNNING -> stringResource(Res.string.jobs_running)
    JobStatus.STOPPING -> stringResource(Res.string.jobs_stopping)
    JobStatus.COMPLETED -> stringResource(Res.string.jobs_completed)
    JobStatus.KILLED -> stringResource(Res.string.jobs_killed)
    JobStatus.FAILED -> stringResource(Res.string.jobs_failed)
    JobStatus.UNKNOWN -> null
}

@Composable
fun workflowStatusLabel(status: String?): String? = when (status) {
    "running" -> stringResource(Res.string.workflow_running)
    "completed" -> stringResource(Res.string.workflow_completed)
    "failed", "error", "cancelled" -> stringResource(Res.string.workflow_failed)
    else -> null
}

fun workflowMemberDot(status: String?): StateDotState = when (status) {
    "running" -> StateDotState.Running
    "completed" -> StateDotState.Done
    "failed", "error", "cancelled" -> StateDotState.Error
    else -> StateDotState.Idle
}

// ---------------------------------------------------------------------------
// Subagents
// ---------------------------------------------------------------------------

fun subagentId(entry: SubagentListEntry): String? = when (entry) {
    is SubagentListEntry.ChildExternal -> entry.id
    is SubagentListEntry.ChildOneShot -> entry.id
    is SubagentListEntry.ChildContinuable -> entry.id
    is SubagentListEntry.Diagnostic -> entry.id
    else -> null
}

fun subagentLabel(entry: SubagentListEntry): String? = when (entry) {
    is SubagentListEntry.ChildExternal -> entry.label
    is SubagentListEntry.ChildOneShot -> entry.label
    is SubagentListEntry.ChildContinuable -> entry.label
    is SubagentListEntry.Diagnostic -> entry.reason
    else -> null
}

fun subagentRunning(entry: SubagentListEntry): Boolean = when (entry) {
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
 * `ptc` is the id harness 0.2.x ships; `code` is the id older hosts used, kept as an alias so a
 * client talking to one of those still gets a translated row.
 */
internal fun builtInPresetStrings(id: String): Pair<StringResource, StringResource>? = when (id) {
    "standard" -> Res.string.preset_standard_name to Res.string.preset_standard_desc
    "ptc", "code" -> Res.string.preset_code_name to Res.string.preset_code_desc
    "minimal" -> Res.string.preset_minimal_name to Res.string.preset_minimal_desc
    "cordis" -> Res.string.preset_cordis_name to Res.string.preset_cordis_desc
    else -> null
}

/**
 * Whether a roster row is one of the shipped presets, whose copy the app owns.
 *
 * Harness 0.2.1 stopped sending `trust` on `agentPresets/list` — the roster is just
 * `{id, order, isDefault}` — so every shipped preset arrives [AgentPresetTrust.UNKNOWN], and a
 * `SYSTEM`-only gate left the picker showing raw ids (`standard`, `ptc`, …) with no descriptions
 * at all. An id is still proof enough: a roster holds one row per id, so a preset someone authored
 * can never answer to `standard`. `USER` stays theirs, and an unknown id has no translation to
 * reach for anyway.
 */
internal fun builtInPresetStrings(entry: AgentPresetEntry): Pair<StringResource, StringResource>? =
    if (entry.trust == AgentPresetTrust.USER) null else builtInPresetStrings(entry.id)

/**
 * What to call the preset a session is pinned to, given only its id.
 *
 * The roster is host-scoped and arrives on its own schedule, and the chip renders as soon as the
 * session does — so without this the top bar showed a raw wire id (`standard`) until something
 * happened to fetch the list. Falling back to the shipped name for a shipped id is right in every
 * deployment that has not replaced that preset, and corrects itself the moment the roster lands.
 */
@Composable
fun agentPresetLabel(id: String, roster: AgentPresetListValue?): String {
    val entry = roster?.presets?.firstOrNull { it.id == id }
    if (entry != null) return entry.displayName()
    return builtInPresetStrings(id)?.let { stringResource(it.first) } ?: id
}

/**
 * What to call a preset: the app's own translation for a shipped one, else whatever the deployment
 * named it, else the raw id.
 */
@Composable
fun AgentPresetEntry.displayName(): String =
    builtInPresetStrings(this)?.let { stringResource(it.first) }
        ?: name?.takeIf { it.isNotBlank() }
        ?: id

/** The preset's one-line summary, translated for the shipped four. Null when there is none. */
@Composable
fun AgentPresetEntry.displayDescription(): String? =
    builtInPresetStrings(this)?.let { stringResource(it.second) }
        ?: description?.takeIf { it.isNotBlank() }

// ---------------------------------------------------------------------------
// Shared field styling
// ---------------------------------------------------------------------------

// `dialogTextFieldColors` moved to :sharedUi (DialogFields.kt) with the session list's dialogs.

/** A field that sits *inside* a composer card: no plate and no outline of its own, the card is the frame. */
@Composable
fun composerFieldColors() = TextFieldDefaults.colors(
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
