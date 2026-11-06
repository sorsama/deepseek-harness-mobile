package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.wire.dto.SubagentListEntry

/**
 * What the plan switch shows: the position the reader asked for until the host's own state
 * catches up, and the host's state otherwise.
 */
internal fun planSwitchChecked(observed: Boolean, pending: Boolean?): Boolean = pending ?: observed

/** Whether a requested plan position can be let go: the host now reports exactly that. */
internal fun planPendingSettled(observed: Boolean?, pending: Boolean?): Boolean =
    pending != null && observed == pending

/**
 * Whether a subagent has a transcript this phone can open.
 *
 * Only a one-shot or continuable child has a local session behind it. An external identity has
 * none to browse, and a diagnostic is a note about a child that could not be listed, so tapping
 * either would load nothing; those rows are shown as facts, not controls.
 */
internal fun subagentOpenable(entry: SubagentListEntry): Boolean = when (entry) {
    is SubagentListEntry.ChildOneShot, is SubagentListEntry.ChildContinuable -> true
    else -> false
}
