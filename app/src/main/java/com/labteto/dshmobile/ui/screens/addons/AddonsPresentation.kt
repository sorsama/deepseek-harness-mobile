package com.labteto.dshmobile.ui.screens.addons

import com.labteto.dshmobile.core.uisync.UiSyncStoredItem
import com.labteto.dshmobile.core.uisync.UiSyncTarget
import com.labteto.dshmobile.core.wire.dto.ManagedPlugin
import com.labteto.dshmobile.core.wire.dto.titleFor

// The Add-ons page's rules, without a device: how a slot reads to a person, which choices an item
// offers, what a package is called, and when the picker has to stack. The composables stay thin.

/**
 * Where an item sits in the harness app, in words a person knows. The ids the harness uses
 * (`plugins.row.config`) are for developers; a person never sees them as the main text.
 */
internal enum class AddonSlot {
    /** Drawn over the whole chat (a pet, a floating panel). */
    OVERLAY,

    /** A button beside the message box. */
    MESSAGE_BOX,

    /** A button in the header of a session. */
    SESSION_HEADER,

    /** An extra control on a plugin's row in the plugin list. */
    PLUGIN_ROW,

    /** A button at the bottom of the sidebar. */
    SIDEBAR_FOOTER,

    /** A slot this app has no words for. */
    OTHER,
}

/** The slot [slot] (the harness's own id) names, or [AddonSlot.OTHER] when this app has no words for it. */
internal fun addonSlot(slot: String): AddonSlot = when (slot) {
    "shell.overlay" -> AddonSlot.OVERLAY
    "conversation.input.left", "conversation.input.right" -> AddonSlot.MESSAGE_BOX
    "conversation.session.header.actions" -> AddonSlot.SESSION_HEADER
    "plugins.row.config" -> AddonSlot.PLUGIN_ROW
    "sidebar.footer.action" -> AddonSlot.SIDEBAR_FOOTER
    else -> AddonSlot.OTHER
}

/**
 * The small, selectable line under an item that tells it apart from a sibling: the harness's own
 * id of its slot when the app has no words for the slot, else (two items of the same title in the
 * same kind of slot) the item's own cell. Null when the words above already tell them apart.
 */
internal fun addonIdLine(item: UiSyncStoredItem, siblings: List<UiSyncStoredItem>): String? {
    val kind = addonSlot(item.slot)
    if (kind == AddonSlot.OTHER) return item.slot
    val twin = siblings.any { it.id != item.id && it.displayTitle == item.displayTitle && addonSlot(it.slot) == kind }
    return if (twin) item.cell.ifBlank { item.slot } else null
}

/**
 * One way to set an item, as the picker offers it. [stores] is what choosing it writes to the
 * harness; the four phone-capable choices store themselves, and [SHOW_ON_DESKTOP] stores
 * [UiSyncTarget.BOTH] so the phone's default for the item stays as the plugin set it.
 */
internal enum class AddonOption(val stores: UiSyncTarget) {
    BOTH(UiSyncTarget.BOTH),
    PHONE(UiSyncTarget.PHONE),
    DESKTOP(UiSyncTarget.DESKTOP),
    OFF(UiSyncTarget.OFF),

    /** The only way "on" reads for an item whose slot has no place on the phone. */
    SHOW_ON_DESKTOP(UiSyncTarget.BOTH),
}

/** The choices an item offers: all four, or only "Show on desktop" and "Off" when its slot has no place on the phone. */
internal fun addonOptions(phoneCanShow: Boolean): List<AddonOption> =
    if (phoneCanShow) listOf(AddonOption.BOTH, AddonOption.PHONE, AddonOption.DESKTOP, AddonOption.OFF)
    else listOf(AddonOption.SHOW_ON_DESKTOP, AddonOption.OFF)

/**
 * Which choice reads as picked for a stored [target]. Without a phone slot, `desktop` reads as shown
 * (it is: the desktop draws it) and `phone` as off (nothing can show it), so no stored value leaves
 * the picker blank.
 */
internal fun addonOptionOf(target: UiSyncTarget, phoneCanShow: Boolean): AddonOption =
    if (phoneCanShow) {
        when (target) {
            UiSyncTarget.BOTH -> AddonOption.BOTH
            UiSyncTarget.PHONE -> AddonOption.PHONE
            UiSyncTarget.DESKTOP -> AddonOption.DESKTOP
            UiSyncTarget.OFF -> AddonOption.OFF
        }
    } else {
        when (target) {
            UiSyncTarget.BOTH, UiSyncTarget.DESKTOP -> AddonOption.SHOW_ON_DESKTOP
            UiSyncTarget.PHONE, UiSyncTarget.OFF -> AddonOption.OFF
        }
    }

/**
 * A package's name for a person: the title the plugin gives itself when the harness's plugin list
 * has one ([titles] by module name), else a readable form of the npm name.
 */
internal fun addonPackageTitle(pkg: String, titles: Map<String, String>): String =
    titles[pkg]
        ?: titles.entries.firstOrNull { it.key.startsWith("$pkg/") }?.value
        ?: readableAddonPackage(pkg)

/**
 * The titles of the harness's plugin list, by module name, in the first of [languageTags] a plugin
 * translates. A plugin without a title is left out (the caller falls back to the npm name).
 */
internal fun addonTitleMap(plugins: List<ManagedPlugin>, languageTags: List<String>): Map<String, String> {
    val titles = LinkedHashMap<String, String>()
    plugins.forEach { plugin ->
        val title = plugin.meta?.titleFor(languageTags) ?: return@forEach
        // A plugin that gives no title of its own is listed under its package name: not a title.
        if (technicalForm(title) == technicalForm(plugin.moduleName)) return@forEach
        titles.putIfAbsent(plugin.moduleName, title)
    }
    return titles
}

/** A name with its case, scope and leading `dsh-` taken off, so a title can be compared with the package it came from. */
private fun technicalForm(name: String): String =
    name.trim().lowercase().replace(Regex("^@[^/]+/"), "").removePrefix("dsh-")

/** Words kept in capitals when a package name is turned into words (`ui` reads "UI", not "Ui"). */
private val ACRONYMS = setOf("ui", "ux", "api", "ai", "id", "url", "cli", "mcp", "css", "html", "sdk", "tts", "stt")

/**
 * `dsh-ui-sync-example-pet` reads "UI Sync Example Pet", `@deepseek-ai/dsh-relay` reads "Relay": the
 * scope and the leading `dsh-` are noise, dashes and underscores become spaces, each word starts
 * with a capital. Display only; never an identifier.
 */
internal fun readableAddonPackage(pkg: String): String {
    val name = pkg.substringAfterLast('/').removePrefix("dsh-").ifBlank { pkg.substringAfterLast('/') }
    val words = name.split('-', '_', '.', ' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return pkg
    return words.joinToString(" ") { word ->
        if (word.lowercase() in ACRONYMS) word.uppercase() else word.replaceFirstChar { it.uppercaseChar() }
    }
}

/**
 * Whether every label fits on one line with the segments side by side, so the picker can stay a
 * row. Segments share the track in proportion to their labels, so what has to fit is the labels
 * with their padding, the gaps between segments, and the track's own padding and border.
 * All sizes are pixels. A phone with a big font or a long translation fails this and stacks the
 * choices instead, where a label can take the whole width and wrap rather than be cut.
 */
internal fun addonPickerFitsOneLine(
    labelWidths: List<Int>,
    available: Int,
    segmentPadding: Int,
    gap: Int,
    chrome: Int,
): Boolean {
    if (labelWidths.isEmpty()) return true
    val needed = labelWidths.sumOf { it + 2 * segmentPadding } + gap * (labelWidths.size - 1) + chrome
    return needed <= available
}
