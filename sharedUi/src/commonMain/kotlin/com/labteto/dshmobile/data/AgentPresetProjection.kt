package com.labteto.dshmobile.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** One session's agent preset as of a position in its log. */
internal data class AgentPresetState(val asOfSeq: Int, val preset: String?)

/**
 * Decode one `agentPreset` projection value seen at [asOfSeq]: a JSON string is a preset and JSON
 * null clears it. Anything else, an absent key included, is no news and answers null, so a
 * baseline that omits the key or a malformed payload cannot erase the last known preset.
 */
internal fun agentPresetUpdate(asOfSeq: Int, value: JsonElement?): AgentPresetState? = when {
    value is JsonNull -> AgentPresetState(asOfSeq, null)
    value is JsonPrimitive && value.isString -> AgentPresetState(asOfSeq, value.content)
    else -> null
}

/**
 * Keep whichever state reflects the later log position.
 *
 * The list read and the control stream race: a `session/list` answer built before another
 * client's selection can arrive after that selection's control frame. Comparing positions keeps
 * the old preset from coming back. A tie goes to [incoming], the rule the open session's own
 * projections follow, so a re-read of the same position still lands.
 */
internal fun newerAgentPreset(held: AgentPresetState?, incoming: AgentPresetState): AgentPresetState =
    if (held == null || incoming.asOfSeq >= held.asOfSeq) incoming else held

/**
 * The state a `session/list` row at [asOfSeq] leaves behind, or null when there is still none.
 *
 * A top-level preset wins over the projection, as in `SessionSummary.agentPresetEffective`; no
 * harness sends one today. A projection that omits the key or carries a malformed value is no
 * news, exactly as on the control stream, so an unrelated list refresh cannot clear a preset the
 * stream delivered.
 */
internal fun listedAgentPreset(
    held: AgentPresetState?,
    asOfSeq: Int,
    topLevel: String?,
    projected: JsonElement?,
    cached: Boolean = false,
): AgentPresetState? {
    // Persisted-cache watermarks cannot outrank even an empty live baseline (-1).
    val seq = if (cached) Int.MIN_VALUE else asOfSeq
    val incoming = topLevel?.let { AgentPresetState(seq, it) } ?: agentPresetUpdate(seq, projected)
    return if (incoming == null) held else newerAgentPreset(held, incoming)
}
