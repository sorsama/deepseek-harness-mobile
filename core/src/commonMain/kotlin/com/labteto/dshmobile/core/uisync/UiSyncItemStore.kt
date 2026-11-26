package com.labteto.dshmobile.core.uisync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The `ui-sync` row field that holds the per-item targets. */
const val UI_SYNC_TARGETS_FIELD: String = "targets"

/** The `ui-sync` row field the companion's pages record their items in (feature `item-store`). The app never writes it. */
const val UI_SYNC_ITEMS_FIELD: String = "items"

/** Whether an item with this target shows on the phone (this app on any device). */
val UiSyncTarget.showsOnPhone: Boolean get() = this == UiSyncTarget.BOTH || this == UiSyncTarget.PHONE

/** One plugin item as the host's item store keeps it, with the target it has now. */
data class UiSyncStoredItem(
    /** `<package>|<slot>|<id or key>`, the key under `targets`. */
    val id: String,
    /** npm package name of the plugin that registered it. */
    val pkg: String,
    val slot: String,
    /** The registration's list `id` or keyed `key`; empty for single and chain slots. */
    val cell: String,
    /** The registration's label, or null. */
    val title: String?,
    /** Where the phone shell shows it, or null when its slot is not lifted (it can only show on the desktop). */
    val placement: UiSyncPlacement?,
    /** The target it has while `targets` stores none. */
    val defaultTarget: UiSyncTarget,
    /** The UTC day (`YYYY-MM-DD`) a page last reported it. */
    val seen: String,
    /** `targets[id]` when that is a known target, else [defaultTarget]. */
    val target: UiSyncTarget,
) {
    /** The phone would draw it: its slot is lifted and its target allows the phone. */
    val showsOnPhone: Boolean get() = placement != null && target.showsOnPhone

    /**
     * What a list calls it: its own label, else a readable form of the last part of its cell
     * (`example-pet.treat` reads "Treat", `dsh-relay#relay` reads "Relay"), else of its slot
     * (`shell.overlay` reads "Shell overlay").
     */
    val displayTitle: String
        get() {
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val base = if (cell.isNotBlank()) cell.split('.', '#', ':', '/').lastOrNull { it.isNotBlank() } ?: cell else slot
            val words = base.map { if (it == '-' || it == '_' || it == '.') ' ' else it }
                .joinToString("").trim().replace(Regex(" +"), " ")
            if (words.isEmpty()) return id
            return words.replaceFirstChar { it.uppercaseChar() }
        }
}

/**
 * The `ui-sync` row's value, read the way `itemsFromSettings` in `dsh-ui-sync/protocol` reads it.
 *
 * Every client page of the companion (0.2.0, feature `item-store`) records the third-party items it
 * knows in field `items`, so the app can tell whether anything targets the phone, and list items in
 * Settings > Add-ons, before it loads a page.
 */
data class UiSyncItemStore(
    /** Every well-formed stored item, sorted by id. */
    val items: List<UiSyncStoredItem>,
    /**
     * Whether the row has an item store at all. A 0.1.0 companion keeps none: then nothing can be
     * known without a page, and the layer loads the way it did before the store existed.
     */
    val hasStore: Boolean,
) {
    /** The items the phone would draw. */
    val phone: List<UiSyncStoredItem> get() = items.filter { it.showsOnPhone }

    /**
     * Whether the add-on page is worth loading: some item would show on the phone. Always true for
     * a companion without an item store, which cannot say.
     */
    val worthLoading: Boolean get() = !hasStore || items.any { it.showsOnPhone }

    /** The items grouped by package, packages and items in a stable order. */
    val byPackage: List<Pair<String, List<UiSyncStoredItem>>>
        get() = items.groupBy { it.pkg }.toList().sortedBy { it.first.lowercase() }

    companion object {
        val Empty = UiSyncItemStore(emptyList(), hasStore = true)
    }
}

/**
 * Read the `ui-sync` row value ([value] of its `settings/describe` entry). An entry that is not an
 * object, or lacks the package, slot, cell, default target or day, is left out, as the reference
 * reader does; a placement it does not know reads as none.
 */
fun uiSyncItemStore(value: JsonElement?): UiSyncItemStore {
    val row = value as? JsonObject ?: return UiSyncItemStore(emptyList(), hasStore = false)
    val targets = (row[UI_SYNC_TARGETS_FIELD] as? JsonObject).orEmpty()
    val stored = row[UI_SYNC_ITEMS_FIELD]
    val entries = stored as? JsonObject ?: return UiSyncItemStore(emptyList(), hasStore = stored != null && stored !is JsonNull)
    val items = entries.mapNotNull { (id, raw) ->
        val entry = raw as? JsonObject ?: return@mapNotNull null
        if (id.isEmpty()) return@mapNotNull null
        val pkg = entry.text("package") ?: return@mapNotNull null
        val slot = entry.text("slot") ?: return@mapNotNull null
        val cell = entry.text("cell") ?: return@mapNotNull null
        val defaultTarget = UiSyncTarget.of(entry.text("defaultTarget")) ?: return@mapNotNull null
        val seen = entry.text("seen") ?: return@mapNotNull null
        UiSyncStoredItem(
            id = id,
            pkg = pkg,
            slot = slot,
            cell = cell,
            title = entry.text("title"),
            placement = UiSyncPlacement.of(entry.text("placement")),
            defaultTarget = defaultTarget,
            seen = seen,
            target = UiSyncTarget.of((targets[id] as? JsonPrimitive)?.takeIf { it.isString }?.content) ?: defaultTarget,
        )
    }.sortedBy { it.id }
    return UiSyncItemStore(items, hasStore = true)
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
