package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.labteto.dshmobile.core.session.AssistantMessageNode

/**
 * What an assistant message's on-screen state hangs on: whether its thinking panels are open and
 * whether its action row is showing.
 *
 * Not `seq`. A message still being written gets a sequence number minted past the durable cursor,
 * which moves whenever any durable event lands and is replaced by the real one the moment the reply
 * settles. State keyed on it was thrown away at both points, so a reader who opened the thinking
 * panel while a reply streamed found it folded shut when the reply finished.
 *
 * The turn and step an attempt belongs to are the same before and after it settles, which makes
 * them the bridge. Without them, a streaming message falls back to a constant (there is only one)
 * and a durable one to its message id, then its sequence number.
 */
internal fun assistantStateKey(node: AssistantMessageNode): Any = when {
    node.turn != null && node.step != null -> "turn-${node.turn}/step-${node.step}"
    node.streaming -> "streaming"
    else -> node.messageId ?: node.seq
}

/**
 * The open/closed state of assistant messages, held above the rows.
 *
 * Keying `remember` on [assistantStateKey] is not enough on its own: the transcript's lazy list also
 * gives a streaming row a different key from the durable row it becomes, so the row's composition,
 * and everything remembered inside it, is replaced at the moment the reply settles. State that has
 * to survive that lives here instead, one entry per message identity, for as long as the session is
 * open.
 *
 * The maps are created on demand and held in a plain map: handing one out is not a snapshot write,
 * so it is safe from inside composition.
 */
@Stable
internal class AssistantMemory {
    private val reasoning = HashMap<Any, SnapshotStateMap<Int, Boolean>>()
    private val actions = HashMap<Any, MutableState<Boolean>>()

    /** Which reasoning blocks of the message are open, by block index. */
    fun reasoningFor(key: Any): SnapshotStateMap<Int, Boolean> =
        reasoning.getOrPut(key) { mutableStateMapOf() }

    /** Whether the message's copy/branch/feedback row is showing. */
    fun actionsFor(key: Any): MutableState<Boolean> =
        actions.getOrPut(key) { mutableStateOf(false) }
}
