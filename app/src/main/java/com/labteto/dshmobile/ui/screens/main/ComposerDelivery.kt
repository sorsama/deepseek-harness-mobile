package com.labteto.dshmobile.ui.screens.main

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
    if (requested == "steer" && !running) "queue" else requested
