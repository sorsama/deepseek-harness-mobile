package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Permission-preset DTOs, ported from
 * `packages/interaction/permission-presets/src/types.ts` (v0.1.1-rc.2).
 *
 * The read side is the `permissions` session projection — no RPC. The write side is the
 * `/permission <value>` slash command. An absent projection key means no permission service is
 * composed on the harness, and clients hide the control entirely.
 */

/**
 * The derived "matches no preset" state. It may arrive as [PermissionSelect.currentValue] but is
 * never a valid switch target, so clients filter it out of the option list.
 */
const val CUSTOM_PRESET = "custom"

/** One switchable preset: its wire id, the harness's display name, and an optional blurb. */
@Serializable
data class PresetOption(
    @SerialName("value") val value: String,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String? = null,
)

/** The whole `permissions` projection payload: every option plus the effective one. */
@Serializable
data class PermissionSelect(
    @SerialName("options") val options: List<PresetOption> = emptyList(),
    @SerialName("currentValue") val currentValue: String,
) {
    /** The options a client may switch to — [CUSTOM_PRESET] is derived, never selectable. */
    val selectable: List<PresetOption> get() = options.filterNot { it.value == CUSTOM_PRESET }

    /** The option matching [currentValue], or null when the effective value is `custom`. */
    val current: PresetOption? get() = options.firstOrNull { it.value == currentValue }
}

/**
 * The harness's own preset label transform (`ui-permission-presets/src/client/presentation.ts`):
 * the full-access preset gets a friendlier name, everything else is its kebab id in Title Case.
 * The preset table is deployment-configurable, so labels are always derived from the wire — never
 * from a local id-to-string map.
 */
fun displayPermissionPreset(value: String, name: String = value): String = when (value) {
    FULL_ACCESS_PRESET -> "Full access"
    else -> titleCasePreset(name)
}

/** The preset id whose capability is broad enough to warrant a confirmation step. */
const val FULL_ACCESS_PRESET = "danger-full-access"

/** `workspace-write` -> `Workspace Write`; already-spaced names pass through unchanged. */
private fun titleCasePreset(name: String): String = name
    .split('-', '_')
    .filter { it.isNotEmpty() }
    .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
