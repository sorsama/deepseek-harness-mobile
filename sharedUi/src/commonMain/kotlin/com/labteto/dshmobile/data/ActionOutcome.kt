package com.labteto.dshmobile.data

/**
 * How a one-shot action on a session ended, for a surface that has to say so in its own words.
 *
 * The store's older actions reported a failure only through the chat screen's banner. A surface drawn
 * over the chat, like the details panel, hides that banner, so a refused rename or a failed stop
 * looked like a button that does nothing. These actions now hand the result back as well.
 */
sealed interface ActionOutcome<out T> {
    /** The host did it. [value] is whatever the action produced, such as a fork's new session id. */
    data class Done<out T>(val value: T) : ActionOutcome<T>

    /** It did not happen. [message] is the host's own reason, when it gave one. */
    data class Failed(val message: String? = null) : ActionOutcome<Nothing>
}

/** True when the action went through. */
val ActionOutcome<*>.succeeded: Boolean get() = this is ActionOutcome.Done

/** The longest host reason a notice will carry; a toast is a line or two, not a stack trace. */
private const val MAX_REASON_CHARS = 120

/**
 * The text of a failure notice: our own plain sentence, then the host's reason when it gave one.
 *
 * "Couldn't rename this chat." becomes "Couldn't rename this chat. Session is busy." The reason is
 * trimmed, collapsed to one line, and cut short when it runs long.
 */
fun failureNotice(label: String, reason: String?): String {
    val oneLine = reason?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" ")
    if (oneLine.isNullOrEmpty()) return label
    val detail = if (oneLine.length <= MAX_REASON_CHARS) oneLine else oneLine.take(MAX_REASON_CHARS - 1).trimEnd() + "…"
    return "$label $detail"
}
