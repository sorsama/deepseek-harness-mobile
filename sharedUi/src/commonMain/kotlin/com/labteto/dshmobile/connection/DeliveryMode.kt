package com.labteto.dshmobile.connection

/**
 * The two delivery modes a prompt sent while a turn is running can ask for.
 *
 * They are the values `session/prompt` and `subagents/prompt` take in their `delivery` field, and
 * the values [AppSettings.defaultDeliveryMode] stores. One home for both, because the `+` sheet,
 * the Settings row and the stored preference all name the same two modes.
 */
const val DELIVERY_QUEUE = "queue"
const val DELIVERY_STEER = "steer"

/**
 * [mode] read back as one the UI and the store can show: anything that is not `steer` is `queue`.
 *
 * A stale or hand-edited preference must never leave the composer or the Settings row with no
 * segment selected.
 */
fun normalizeDeliveryMode(mode: String): String =
    if (mode == DELIVERY_STEER) DELIVERY_STEER else DELIVERY_QUEUE
