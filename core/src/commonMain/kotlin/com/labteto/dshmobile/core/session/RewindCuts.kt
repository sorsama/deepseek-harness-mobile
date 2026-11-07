package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What a `dsh-rewind` cut hides from the transcript — the port of the plugin's client-side
 * `hiddenSeqsOf` (`dsh-rewind-plugin/lib/client.js`).
 *
 * The plugin's host half appends a marker `user/message` with a `surfaceOp: replace` whose range
 * the harness honours for the *model*, but the harness serves the shadowed events to clients
 * unchanged — so the browser hides them itself. The rule is read off the command ledger rather
 * than off `surfaceOp`, because the ledger is what the plugin's own UI keys on:
 *
 * - a `command/run` named `rewind` (or `undo`, which the plugin registers with the same handler)
 *   whose `command/done` is a `success` carrying a `sourceEventSeq` (the marker it appended) cuts
 *   `[target, marker]`, where `target` is the `@seq` in its args, and hides its own run and done
 *   rows;
 * - the `preview` and `__candidates` sub-invocations are housekeeping and hide only their own rows;
 * - any other rewind stays visible, as it does in the browser: a failure, or a target given
 *   without a mode, whose result is the usage text that says what to type next.
 *
 * A bare `/rewind`, or one naming its target by index, does cut the model's context — the host
 * rewinds to its latest message, or to the nth — but its args carry no `@seq`, so the ledger
 * cannot say what it withdrew. As in the browser, its rows hide and nothing else does.
 */
object RewindCuts {
    private val commandNames = setOf("rewind", "undo")

    /** Inclusive seq range of surface nodes a successful rewind withdrew. */
    data class Cut(val start: Long, val end: Long)

    /** The seqs [nodes] should not draw, given the rewinds recorded among them. */
    fun hiddenSeqs(nodes: List<ChatNode>): Set<Long> {
        val hidden = mutableSetOf<Long>()
        val spans = mutableListOf<Cut>()
        val runs = nodes.filterIsInstance<CommandNode>().filter { it.kind == "command/run" }
        val dones = nodes.filterIsInstance<CommandNode>().filter { it.kind == "command/done" }
            .associateBy { (it.data as? JsonObject)?.str("commandId") }
        for (run in runs) {
            val data = run.data as? JsonObject ?: continue
            if (data.str("name") !in commandNames) continue
            val args = data.str("args").orEmpty()
            val done = dones[data.str("commandId")]
            if (args.contains("preview") || args.contains("__candidates")) {
                hidden.add(run.seq)
                done?.let { hidden.add(it.seq) }
                continue
            }
            val outcome = done?.data as? JsonObject ?: continue
            if (outcome.str("kind") != "success") continue
            val marker = (outcome["sourceEventSeq"] as? JsonPrimitive)?.longOrNull ?: continue
            hidden.add(run.seq)
            hidden.add(done.seq)
            val target = targetSeqOf(args) ?: continue
            spans.add(Cut(target, marker))
        }
        val cuts = coalesce(spans)
        // Markers: each effective cut ends at the marker of the latest rewind in it, and that
        // marker is the divider the transcript draws. One inside a cut (an earlier rewind a later
        // one reached past) or one that ends no cut (a bare or index-form rewind) is an empty row
        // and hides.
        val dividers = cuts.mapTo(HashSet()) { it.end }
        for (node in nodes) {
            if (node is UserMessageNode && node.sourceKind == REWIND_SOURCE_KIND) {
                if (node.seq !in dividers) hidden.add(node.seq)
                continue
            }
            if (cuts.any { node.seq in it.start..it.end }) hidden.add(node.seq)
        }
        return hidden
    }

    /** The `@<seq>` target in a rewind's args, or null for an index or bare form. */
    fun targetSeqOf(args: String): Long? {
        val token = args.trim().split(Regex("\\s+")).firstOrNull() ?: return null
        if (!token.startsWith("@")) return null
        return token.drop(1).toLongOrNull()?.takeIf { it >= 0 }
    }

    /** Merge overlapping or adjacent spans so membership is one range test. */
    fun coalesce(spans: List<Cut>): List<Cut> {
        if (spans.size < 2) return spans
        val sorted = spans.sortedWith(compareBy({ it.start }, { it.end }))
        val merged = mutableListOf<Cut>()
        for (span in sorted) {
            val last = merged.lastOrNull()
            if (last != null && span.start <= last.end + 1) {
                merged[merged.lastIndex] = Cut(last.start, maxOf(last.end, span.end))
            } else {
                merged.add(span)
            }
        }
        return merged
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
