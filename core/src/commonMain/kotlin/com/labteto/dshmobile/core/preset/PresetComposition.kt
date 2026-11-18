package com.labteto.dshmobile.core.preset

/**
 * Reading an agent preset's composition, the way the official client's "View configuration" does.
 *
 * `agentPresets/read` answers with the preset's declared child plugin list rendered as block-style
 * entry-list YAML (`packages/preset/agent-preset-registry/src/index.ts`, `readDocument`; the shape is
 * pinned by `packages/preset/agent-preset-registry/tests/registry.spec.ts`, "renders a declaration as
 * entry-list YAML for reading, conditions included"). The harness accepts nothing back: a preset is
 * changed by installing a bundle patch through the plugin manager, never by writing this text.
 *
 * So this is read-only on purpose, and it is also lossless on purpose: [PresetComposition.entries]
 * keep every byte of the document in [PresetEntry.text], and a reader that does not understand a
 * line shows it rather than dropping it. [PresetComposition.render] puts the original text back.
 */
data class PresetComposition(
    /** Anything before the first top-level `- ` entry: a comment, or the whole text when it has no entries. */
    val preamble: String,
    val entries: List<PresetEntry>,
) {
    /** The document exactly as it was read. */
    fun render(): String = buildString {
        append(preamble)
        entries.forEach { append(it.text) }
    }
}

/** How one composed plugin row is switched on. */
sealed interface PresetEntryState {
    /** No `disabled` key, or `disabled: false`: the row always mounts. */
    data object Enabled : PresetEntryState

    /** `disabled: true`: the row never mounts. */
    data object Disabled : PresetEntryState

    /**
     * `disabled: !!js <expression>`: the row mounts unless the expression is true on the host.
     * [expression] is the text after the tag, as declared.
     */
    data class Conditional(val expression: String) : PresetEntryState
}

/** One plugin row of a preset's composition. */
data class PresetEntry(
    /** The row's `id`, when it declares one. */
    val id: String?,
    /** The row's `name`: the plugin package or module it mounts. */
    val module: String?,
    val state: PresetEntryState,
    /** This entry's YAML, verbatim, from its `- ` through its last line and line break. */
    val text: String,
)

/**
 * Split the composition YAML of one preset into its top-level entries.
 *
 * The harness writes the list in block style, so a new entry starts at a line that begins with `- `
 * in the first column. The text before the first such line stays in [PresetComposition.preamble].
 * A document in any other shape (an empty `[]`, a flow list) has no entries and is returned whole
 * in the preamble, still readable and still copyable.
 */
fun parsePresetComposition(yaml: String): PresetComposition {
    val lines = splitKeepingBreaks(yaml)
    val starts = lines.indices.filter { isEntryStart(lines[it]) }
    if (starts.isEmpty()) return PresetComposition(preamble = yaml, entries = emptyList())
    val preamble = lines.subList(0, starts.first()).joinToString("")
    val entries = starts.mapIndexed { i, first ->
        val end = starts.getOrNull(i + 1) ?: lines.size
        readEntry(lines.subList(first, end))
    }
    return PresetComposition(preamble, entries)
}

private fun isEntryStart(line: String): Boolean =
    line.startsWith("- ") || line.trimEnd('\r', '\n') == "-"

private fun splitKeepingBreaks(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<String>()
    var from = 0
    while (from < text.length) {
        val newline = text.indexOf('\n', from)
        val to = if (newline < 0) text.length else newline + 1
        out.add(text.substring(from, to))
        from = to
    }
    return out
}

private fun readEntry(lines: List<String>): PresetEntry {
    var id: String? = null
    var module: String? = null
    var state: PresetEntryState = PresetEntryState.Enabled
    lines.forEachIndexed { index, raw ->
        val line = raw.trimEnd('\r', '\n')
        // The first line carries the `- ` marker in front of the first key; every other key of the
        // entry sits two columns in. Deeper lines belong to a nested value, not to the row itself.
        val key = when {
            index == 0 -> line.removePrefix("-").removePrefix(" ")
            line.startsWith("  ") && !line.startsWith("   ") -> line.substring(2)
            else -> return@forEachIndexed
        }
        val colon = key.indexOf(':')
        if (colon <= 0) return@forEachIndexed
        val name = key.substring(0, colon)
        val value = key.substring(colon + 1).trim()
        when (name) {
            "id" -> id = id ?: unquote(value)
            "name" -> module = module ?: unquote(value)
            "disabled" -> state = stateOf(value)
        }
    }
    return PresetEntry(id, module, state, lines.joinToString(""))
}

private fun stateOf(value: String): PresetEntryState = when {
    value == "true" -> PresetEntryState.Disabled
    value == "false" || value.isEmpty() -> PresetEntryState.Enabled
    value.startsWith("!!js") -> PresetEntryState.Conditional(value.removePrefix("!!js").trim())
    else -> PresetEntryState.Conditional(unquote(value) ?: value)
}

private fun unquote(value: String): String? {
    if (value.isEmpty()) return null
    val first = value.first()
    if ((first == '\'' || first == '"') && value.length >= 2 && value.last() == first) {
        val inner = value.substring(1, value.length - 1)
        return if (first == '\'') inner.replace("''", "'") else inner
    }
    return value
}
