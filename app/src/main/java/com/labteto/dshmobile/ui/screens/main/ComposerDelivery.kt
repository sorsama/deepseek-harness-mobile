package com.labteto.dshmobile.ui.screens.main

internal const val DELIVERY_QUEUE = "queue"
internal const val DELIVERY_STEER = "steer"

internal fun normalizeDeliveryMode(mode: String): String =
    if (mode == DELIVERY_STEER) DELIVERY_STEER else DELIVERY_QUEUE

/**
 * The delivery a prompt actually asks the host for.
 *
 * `steer` splices into a turn that is already running, and the host refuses to steer an idle
 * session. The `+` sheet keeps Steer selectable even with nothing running so the choice is always
 * the same full pill, and a send made in that state has to go out as an ordinary queued prompt
 * instead of a refusal the reader cannot act on. The same rule covers a Steer picked mid-turn
 * when the turn ended before send was pressed.
 */
internal fun promptDelivery(requested: String, running: Boolean): String =
    if (requested == DELIVERY_STEER && !running) DELIVERY_QUEUE else requested
