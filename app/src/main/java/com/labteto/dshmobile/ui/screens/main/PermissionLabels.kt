package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.CUSTOM_PRESET
import com.labteto.dshmobile.core.wire.dto.FULL_ACCESS_PRESET
import com.labteto.dshmobile.ui.theme.DsColors

/**
 * Localized permission-preset labels.
 *
 * The preset table is deployment-configurable on the harness, so a label always comes from the wire
 * — except for the presets the harness itself ships, whose ids are fixed (`read-only`,
 * `workspace-write`, `danger-full-access`, and the reserved `auto`, which a deployment can neither
 * rename nor define). Those get the app's own translations, matching the harness web client's
 * locale dictionaries; a deployment that renames a shipped preset keeps the name it chose.
 *
 * The core layer keeps its platform-agnostic [displayPermissionPreset] helper unchanged; the
 * localization belongs to the UI layer, so this file is the app's only label source.
 */

/** Which shipped preset a value resolves to, or null when the wire label must be kept. */
internal enum class ShippedPermissionPreset {
    ReadOnly,
    WorkspaceWrite,
    FullAccess,
    AutoReview,
}

/** The English label the harness ships for each shipped preset (its default `name`). */
private val SHIPPED_DEFAULT_NAMES = mapOf(
    ShippedPermissionPreset.ReadOnly to "Read Only",
    ShippedPermissionPreset.WorkspaceWrite to "Workspace Write",
    ShippedPermissionPreset.FullAccess to "Full access",
)

/**
 * Resolve a preset value + host name to a shipped preset worth localizing.
 *
 * Mirrors the harness web client's permission-preset guard: a shipped id is only localized while the
 * host still calls it by its id or by the shipped English label; a deployment-chosen name is kept
 * verbatim. `auto` is the one exception — the harness reserves the id itself (a configured preset
 * may not be named `auto`), so its label is always the app's.
 */
internal fun shippedPermissionPreset(value: String, name: String?): ShippedPermissionPreset? = when (value) {
    "read-only", "read", "readonly" -> shippedOrNull(name, value, ShippedPermissionPreset.ReadOnly)
    "workspace-write", "workspace", "write" -> shippedOrNull(name, value, ShippedPermissionPreset.WorkspaceWrite)
    FULL_ACCESS_PRESET -> shippedOrNull(name, value, ShippedPermissionPreset.FullAccess)
    "auto" -> ShippedPermissionPreset.AutoReview
    else -> null
}

private fun shippedOrNull(name: String?, id: String, shipped: ShippedPermissionPreset): ShippedPermissionPreset? =
    if (name.isNullOrBlank() || name == id || name == SHIPPED_DEFAULT_NAMES.getValue(shipped)) shipped else null

/**
 * The display label for a permission preset: the app's translation for a shipped one, the
 * harness's humanised wire label otherwise, and the shared "Custom" copy for the derived state.
 */
@Composable
internal fun permissionPresetLabel(value: String, name: String?): String = when (shippedPermissionPreset(value, name)) {
    ShippedPermissionPreset.ReadOnly -> stringResource(R.string.permission_preset_read_only)
    ShippedPermissionPreset.WorkspaceWrite -> stringResource(R.string.permission_preset_workspace_write)
    ShippedPermissionPreset.FullAccess -> stringResource(R.string.permission_preset_full_access)
    ShippedPermissionPreset.AutoReview -> stringResource(R.string.permission_preset_auto)
    null -> when {
        value == CUSTOM_PRESET -> stringResource(R.string.permission_custom)
        else -> humanisedPresetLabel(value, name)
    }
}

/**
 * The wire label, humanised the way the desktop client does: `workspace-write` ->
 * "Workspace Write"; already-spaced names pass through unchanged.
 *
 * Unlike the core helper this keeps a deployment-chosen name for every id, full access included:
 * a deployment that renamed the full-access preset keeps that name.
 */
internal fun humanisedPresetLabel(value: String, name: String?): String {
    val host = name?.takeIf { it.isNotBlank() } ?: value
    return titleCasePresetId(host)
}

private fun titleCasePresetId(name: String): String = name
    .split('-', '_')
    .filter { it.isNotEmpty() }
    .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }

/**
 * The shield's colour where the preset name has no room — the full-screen page's icon-only bar.
 *
 * There the colour is the whole message, so each shipped preset gets its own reading: read-only is
 * muted because nothing can change, the working preset takes the accent, full access the warning
 * amber it already had, and auto-review the green of "nobody has to watch this". A deployment's own
 * preset stays neutral on purpose: a colour would be the app claiming to know what it does, and it
 * cannot.
 */
internal fun permissionPresetTint(value: String, name: String?, colors: DsColors): Color =
    when (shippedPermissionPreset(value, name)) {
        ShippedPermissionPreset.ReadOnly -> colors.labelSecondary
        ShippedPermissionPreset.WorkspaceWrite -> colors.accent
        ShippedPermissionPreset.FullAccess -> colors.warnLabel
        ShippedPermissionPreset.AutoReview -> colors.success
        null -> colors.labelPrimary
    }