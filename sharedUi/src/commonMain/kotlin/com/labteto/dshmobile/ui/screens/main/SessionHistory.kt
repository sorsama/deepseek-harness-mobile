package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.UserMessageNode

/**
 * One message the reader sent, as the picker lists it: what they said and where the transcript
 * keeps it.
 *
 * `seq` is the node's sequence, which is also the transcript row's key — so a pick can be turned
 * into a scroll target without the picker knowing anything about how rows group.
 */
internal data class HistoryEntry(
    val seq: Long,
    val text: String,
    val hasImage: Boolean,
    val timeMs: Long?,
)

/**
 * The messages the reader themselves sent, newest first — the same order a reversed transcript
 * reads and the order the picker opens on.
 *
 * Only their own words. A reply is not one message in this app: the harness records every step of
 * a turn separately, so a single exchange can leave dozens of assistant nodes against one prompt —
 * in a long session, hundreds against a few dozen. Listing both sides buries the prompts the reader
 * is looking for under machinery they were watching scroll past moments ago, which is precisely the
 * scroll they opened this to avoid. The transcript is still where replies are read; this is a list
 * of what was asked.
 *
 * The same visibility the transcript uses decides what is listed, so the picker can never offer a
 * prompt the transcript does not draw: [renderableChatNodes] drops the nodes a rewind withdrew, the
 * injected context the harness sends on the reader's behalf, and the structural events that carry a
 * turn's bookkeeping rather than anything anyone said. Injected context in particular is not the
 * reader's own words — a skill body, a system note — and it would be a lie about who wrote it.
 *
 * A prompt with no text still belongs here when it carried an image: a screenshot pasted with a
 * one-line request is something the reader sent, and dropping it would leave a gap in the list at
 * the exact place they remember sending something.
 *
 * Only what is loaded. A session opens on its last sixty messages, and in a tool-heavy session
 * that is a handful of prompts; the sheet pages further back on request, the way the transcript
 * does, rather than this pretending the rest of the log is here.
 */
internal fun historyEntries(
    nodes: List<ChatNode>,
    running: Boolean,
    eventTimes: Map<Long, Long> = emptyMap(),
): List<HistoryEntry> = renderableChatNodes(nodes, running)
    .mapNotNull { node ->
        if (node !is UserMessageNode || node.isInjectedContext) return@mapNotNull null
        val text = node.displayText().trim()
        val hasImage = node.blocks.any { it.kind == IMAGE_BLOCK_KIND }
        if (text.isEmpty() && !hasImage) {
            null
        } else {
            HistoryEntry(node.seq, text, hasImage, eventTimes[node.seq])
        }
    }
    .asReversed()

/**
 * A prompt is its own row — only tool calls and injected context group — so the row carries it.
 *
 * The sheet lists prompts from the same loaded nodes the rows are built from, so a miss means the
 * message went away under an open sheet (a rewind, a session switch), not that it is still to load.
 */
internal fun historyRowIndex(rows: List<TranscriptRow>, seq: Long): Int? =
    rows.indexOfFirst { it.seq == seq }.takeIf { it >= 0 }

/**
 * A picker query against a prompt: every word of the query appears somewhere in it, in any order.
 *
 * Not the model sheet's subsequence match. That one is built for short identifiers, where "dsv4"
 * should find "deepseek-v4"; against a paragraph of prose nearly any short word is a subsequence,
 * so "fix" would keep almost every prompt and the filter would hide nothing.
 */
internal fun historyMatches(query: String, text: String): Boolean {
    val haystack = text.lowercase()
    return query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { it in haystack }
}

private const val IMAGE_BLOCK_KIND = "image"
