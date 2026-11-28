package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.mascotPainter
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * What the leading slot of a session row says about its session.
 *
 * The mascot is the app's one expressive element, so a row only gets it when its session is worth
 * noticing: something is running, or something is waiting for the person. Every other row is
 * [Quiet], a small dot in the same slot, so the titles line up whichever rows have the mascot.
 */
internal enum class RowLead { NeedsYou, Running, Quiet }

/**
 * The slot's content for [session]. An archived row is always [RowLead.Quiet]: it is a record, not
 * something that is happening. A session that is both running and waiting for a person is
 * [RowLead.NeedsYou], because that is the one that needs an action.
 *
 * Being the open session is not a reason for a mascot: the row's own highlight already says so.
 */
internal fun rowLeadOf(session: SessionRow, archived: Boolean = false): RowLead = when {
    archived -> RowLead.Quiet
    session.pendingInteraction != null -> RowLead.NeedsYou
    session.running -> RowLead.Running
    else -> RowLead.Quiet
}

/** Whether a row draws the mascot at all: true only for a session that is running or needs the person. */
internal fun rowShowsMascot(session: SessionRow, archived: Boolean = false): Boolean =
    rowLeadOf(session, archived) != RowLead.Quiet

/** How big the dot of a quiet row is. The same as the harness pill's, so the two read as one family. */
private val QuietDotSize = 7.dp

/**
 * The leading slot of a session row: the mascot (amber when the person is needed, accent while it
 * runs) or, for a quiet row, a small idle dot. Always [size] square, so the text beside it starts
 * at the same place on every row.
 */
@Composable
internal fun SessionRowLead(session: SessionRow, size: Dp, archived: Boolean = false) {
    val colors = DsTheme.colors
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        when (rowLeadOf(session, archived)) {
            RowLead.NeedsYou -> SessionAvatar(colors.warn, size)
            RowLead.Running -> SessionAvatar(colors.accent, size)
            RowLead.Quiet -> StateDot(StateDotState.Idle, size = QuietDotSize)
        }
    }
}

/** The mascot avatar at row scale: a static tinted mark, not an animated one per visible row. */
@Composable
internal fun SessionAvatar(tint: Color, size: Dp) {
    Image(
        painter = mascotPainter(),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.size(size),
    )
}
