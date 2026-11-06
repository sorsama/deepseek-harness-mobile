package com.labteto.dshmobile.data

/*
 * Order arithmetic for the sessions list, kept free of Android so it can be tested on the JVM.
 *
 * The harness moves things with "insert X before Y" (a null Y means the end), so every move here is
 * expressed the same way: take the id out, put it back in front of an anchor. The store applies the
 * result optimistically and sends the anchor; the drawer's drag uses the same functions to decide
 * where a lifted row would land.
 */

/** How a reorder ended, for the surface that has to say so when it did not go through. */
sealed interface MoveOutcome {
    /** The host took it. */
    data object Moved : MoveOutcome

    /** The order already was what was asked for; nothing was sent. */
    data object Unchanged : MoveOutcome

    /** An earlier move failed and rolled the list back, so this one, made against an order that never happened, was not sent. */
    data object Superseded : MoveOutcome

    /** The host refused it or could not be reached; the list is back where it was. [reason] is the host's own words, if any. */
    data class Failed(val reason: String?) : MoveOutcome
}

/**
 * [order] with [id] taken out and put back directly in front of [beforeId].
 *
 * A null or unknown [beforeId] appends, which is also what the host does with an absent anchor. An
 * [id] that is not in [order] changes nothing, and so does an anchor equal to [id] itself.
 */
internal fun moveBefore(order: List<String>, id: String, beforeId: String?): List<String> {
    if (id !in order || beforeId == id) return order
    val without = order.filterNot { it == id }
    val at = beforeId?.let { without.indexOf(it) }?.takeIf { it >= 0 } ?: without.size
    return without.toMutableList().apply { add(at, id) }
}

/**
 * The anchor to send for [id] once it sits where [order] puts it: the id it now precedes, or null
 * when it is last (which the host reads as "append").
 */
internal fun anchorAfterMove(order: List<String>, id: String): String? {
    val at = order.indexOf(id)
    return if (at < 0) null else order.getOrNull(at + 1)
}

/**
 * [order] with [id] moved [step] places (negative is up). Null when [id] is absent or the move
 * would leave the list, so a "Move up" on the first row is a no-op rather than a wrap-around.
 */
internal fun moveByStep(order: List<String>, id: String, step: Int): List<String>? {
    val from = order.indexOf(id)
    if (from < 0 || step == 0) return null
    val to = from + step
    if (to !in order.indices) return null
    return order.toMutableList().apply {
        removeAt(from)
        add(to, id)
    }
}

/**
 * Apply a reader's saved order to a list that has since gained or lost members.
 *
 * Saved ids that still exist keep their saved order; ids the reader has never placed come first, in
 * the order they were given, because a session that just appeared is what they are looking for.
 * Saved ids that no longer exist drop out.
 */
internal fun applySavedOrder(defaultOrder: List<String>, saved: List<String>): List<String> {
    if (saved.isEmpty()) return defaultOrder
    val present = defaultOrder.toHashSet()
    val savedPresent = saved.filter { it in present }.distinct()
    val savedSet = savedPresent.toHashSet()
    return defaultOrder.filter { it !in savedSet } + savedPresent
}
