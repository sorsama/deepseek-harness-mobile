package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Plugin-inventory DTOs, ported from `packages/host/plugin-inventory/src/types.ts`.
 *
 * The inventory is read-only by design: `pluginInventory/list` is the namespace's entire surface —
 * there is no enable/disable call anywhere in the harness. Which plugins load is decided by
 * `cordis.patch.yml`, and the settings that configure them go through `settings.*`, a document
 * this app has no editor for. So a phone can see the composition and nothing more, which is
 * exactly what the harness's own "Plugin list" tab offers.
 */

/** Where a plugin has got to in the Cordis loader's lifecycle. Absent means it never mounted. */
@Serializable
enum class PluginFiberPhase {
    @SerialName("pending")
    PENDING,

    @SerialName("loading")
    LOADING,

    @SerialName("active")
    ACTIVE,

    @SerialName("failed")
    FAILED,

    @SerialName("unloading")
    UNLOADING,
}

/** One row of `pluginInventory/list`. */
@Serializable
data class PluginInventoryEntry(
    /** The loader-tree entry id — stable, and what a `cordis.patch.yml` override targets. */
    @SerialName("entryId") val entryId: String,
    /** The exact module specifier, e.g. `@deepseek-ai/dsh-client-ui-plan`. */
    @SerialName("moduleName") val moduleName: String,
    /** Effective enablement, already folded through any disabled ancestor group. */
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("fiberPhase") val fiberPhase: PluginFiberPhase? = null,
)

/** Value of `pluginInventory/list`. */
@Serializable
data class PluginInventorySnapshot(
    @SerialName("entries") val entries: List<PluginInventoryEntry> = emptyList(),
)
