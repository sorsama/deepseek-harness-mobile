package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsSearchField
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.components.mascotPainter
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The archive: every session the reader has put away, searchable, reachable only by bringing one
 * back.
 *
 * Ink layout: a search plate, then the sessions grouped the way the design groups them — this
 * week, this month, and then older months by name — as hairline-separated rows with the mascot at
 * rest beside each title. The row's only action is restore; archiving changes nothing about the
 * session, so destructive actions stay out of this surface (the host has no session-delete API).
 */
@Composable
internal fun ArchivedSessions(store: SessionStore) {
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val archived by store.archivedSessionIds.collectAsStateWithLifecycle()
    val workspaces by store.workspaces.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberDsHaptics()
    val colors = DsTheme.colors
    LaunchedEffect(store.activeHostKey, archived) { store.refreshSessions() }

    DsSearchField(query, { query = it }, stringResource(R.string.ink_files_archived_search))

    val rows = sessions
        .filter { it.sessionId in archived }
        .filter { query.isBlank() || it.title.orEmpty().contains(query, true) || it.cwd.orEmpty().contains(query, true) }
        .sortedByDescending { it.updatedAt }

    if (failed) {
        // The restore that failed, said as that, not as a panel that could not load.
        Text(stringResource(R.string.chatlist_restore_failed), style = DsType.small13, color = colors.error)
    }
    if (rows.isEmpty()) {
        EmptyHero(
            // Nothing archived is its own state. The screen's title is not an answer to "is it empty?".
            headline = stringResource(if (query.isBlank()) R.string.chatlist_archived_none else R.string.archived_empty),
            subtitle = null,
            showPreview = false,
        )
        return
    }

    Text(
        stringResource(R.string.archived_count, rows.size),
        style = DsType.label12,
        color = colors.labelTertiary,
        modifier = Modifier.padding(start = DsSpacing.tiny),
    )

    val locale = LocalConfiguration.current.locales[0]
    val groups = archiveGroups(
        rows = rows,
        locale = locale,
        thisWeek = stringResource(R.string.ink_files_this_week),
        thisMonth = stringResource(R.string.ink_files_this_month),
    )
    groups.forEach { group ->
        Text(
            group.label,
            style = DsType.label12,
            color = colors.labelTertiary,
            modifier = Modifier.padding(start = DsSpacing.tiny, top = DsSpacing.small),
        )
        group.rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = colors.borderL1)
            ArchiveRow(
                session = row,
                folder = workspaces.firstOrNull { row.sessionId in it.sessionIds }?.title?.ifBlank { null }
                    ?: row.cwd?.let { basenameOf(it) },
                busy = busy == row.sessionId,
                enabled = busy == null,
                onRestore = {
                    busy = row.sessionId
                    failed = false
                    scope.launch {
                        try {
                            val restored = store.unarchiveSession(row.sessionId)
                            failed = !restored
                            if (restored) haptics.confirm() else haptics.reject()
                        } finally { busy = null }
                    }
                },
            )
        }
    }
}

@Composable
private fun ArchiveRow(
    session: SessionRow,
    folder: String?,
    busy: Boolean,
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = DsSpacing.tiny, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        StaticMascot(30.dp)
        Column(Modifier.weight(1f)) {
            Text(
                session.title?.takeIf { it.isNotBlank() } ?: session.sessionId,
                style = DsType.rowTitle,
                color = colors.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    relativeTime(session.updatedAt),
                    folder?.let { technicalDisplay(it) },
                ).joinToString(" · "),
                style = DsType.small13,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // One 48dp slot that holds either the spinner or the button, so the row does not change width
        // while a restore runs. (The button is already 48dp; it needs no second size on top.)
        Box(Modifier.size(DsSpacing.touchTarget), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), color = colors.accent, strokeWidth = 2.dp)
            } else {
                DsIconButton(
                    icon = Icons.Outlined.Restore,
                    contentDescription = stringResource(R.string.archived_restore),
                    onClick = onRestore,
                    enabled = enabled,
                    tint = colors.labelSecondary,
                )
            }
        }
    }
}

private data class ArchiveGroup(val label: String, val rows: List<SessionRow>)

/**
 * Buckets the archive the way the mock does: this week, this month, then one heading per older
 * month, newest first. Month names come from the active locale; the two relative buckets receive
 * their already-translated labels from the caller.
 */
private fun archiveGroups(
    rows: List<SessionRow>,
    locale: Locale,
    thisWeek: String,
    thisMonth: String,
): List<ArchiveGroup> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val weekStart = today.minusDays(7)
    val monthFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    val groups = LinkedHashMap<String, MutableList<SessionRow>>()
    rows.forEach { row ->
        val date = runCatching { Instant.ofEpochMilli(row.updatedAt).atZone(zone).toLocalDate() }.getOrNull()
        val label = when {
            date == null -> thisMonth
            !date.isBefore(weekStart) && !date.isAfter(today) -> thisWeek
            YearMonth.from(date) == YearMonth.from(today) -> thisMonth
            else -> monthFormatter.format(date)
        }
        groups.getOrPut(label) { mutableListOf() }.add(row)
    }
    return groups.map { (label, entries) -> ArchiveGroup(label, entries) }
}

/** The mascot at rest, drawn without the shared mark's idle float: an archived list may hold many rows and none of them should keep an animation alive. */
@Composable
private fun StaticMascot(size: androidx.compose.ui.unit.Dp) {
    val colors = DsTheme.colors
    Image(
        painter = mascotPainter(),
        contentDescription = null,
        colorFilter = ColorFilter.tint(colors.labelDimmed),
        modifier = Modifier.size(size * 0.82f).alpha(0.8f),
    )
}

/** Last path segment, so a row can name the project rather than print the whole path twice. */
private fun basenameOf(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
