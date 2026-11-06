package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsSearchField
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.clickableWithHold
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The prompts the reader sent in this session, so they can jump back to something asked a hundred
 * screens ago without dragging through the replies it produced.
 *
 * One row per prompt, because a prompt is the only unit here with a stable identity. Rows are
 * hairline-separated and the timestamp leads as a micro label, so a long list reads as a log rather
 * than as cards.
 *
 * The transcript is the only place the rows exist, and the sheet has no access to its layout, so a
 * pick is reported as the node's sequence and [ChatTranscript] turns that into a scroll.
 */
@Composable
internal fun HistorySheet(
    entries: List<HistoryEntry>,
    hasMore: Boolean,
    loadingOlder: Boolean,
    loadOlderFailed: Boolean,
    onLoadOlder: () -> Unit,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    /** Puts a listed prompt back in the composer; null offers only the copy. */
    onEdit: ((String) -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var query by remember { mutableStateOf("") }
    var held by remember { mutableStateOf<HistoryEntry?>(null) }
    val shown = remember(entries, query) {
        if (query.isBlank()) entries else entries.filter { historyMatches(query, it.text) }
    }
    DsBottomSheet(
        title = stringResource(R.string.chat_history_title),
        subtitle = if (entries.isEmpty()) null else entries.size.toString(),
        onDismiss = {
            haptics.tap()
            onDismiss()
        },
    ) {
        if (entries.size > SEARCH_FROM) DsSearchField(query, { query = it }, stringResource(R.string.common_search))
        when {
            // Nothing loaded yet is not "nothing sent" while there is history left to page in.
            entries.isEmpty() -> if (!hasMore) {
                Box(Modifier.fillMaxWidth().padding(vertical = DsSpacing.medium)) {
                    EmptyHero(headline = stringResource(R.string.chat_history_empty), subtitle = null)
                }
            }
            shown.isEmpty() -> Text(
                stringResource(R.string.chat_history_no_match),
                style = DsType.small13,
                color = colors.labelSecondary,
            )
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                itemsIndexed(shown, key = { _, entry -> entry.seq }) { index, entry ->
                    HistoryRow(
                        entry = entry,
                        onClick = {
                            haptics.tap()
                            onPick(entry.seq)
                        },
                        // An image-only prompt has no words to copy or edit, so it keeps the tap alone.
                        onHold = if (entry.text.isNotEmpty()) ({ held = entry }) else null,
                    )
                    if (index != shown.lastIndex) {
                        HorizontalDivider(color = colors.borderL1)
                    }
                }
            }
        }
        if (hasMore) {
            LoadOlderRow(
                loading = loadingOlder,
                failed = loadOlderFailed,
                offerManual = true,
                onRetry = onLoadOlder,
            )
        }
    }
    held?.let { entry ->
        PromptHoldMenu(
            text = entry.text,
            onEdit = onEdit?.let { edit -> { text: String -> onDismiss(); edit(text) } },
            onDismiss = { held = null },
        )
    }
}

/** One prompt: when it was sent, then enough of it to recognise. */
@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit, onHold: (() -> Unit)?) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .then(
                if (onHold != null) {
                    Modifier.clickableWithHold(stringResource(R.string.hold_message_actions), onHold = onHold, onClick = onClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(horizontal = DsSpacing.xsmall, vertical = DsSpacing.small),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        // The log's left column: a quote glyph and the timestamp, so the row is scannable by when
        // as well as by what.
        Icon(
            FeatherIcons.MessageSquare,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
        )
        Column(Modifier.weight(1f)) {
            entry.timeMs?.let { timestamp ->
                Text(
                    relativeTime(timestamp),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
                Spacer(Modifier.height(2.dp))
            }
            val text = entry.text.ifEmpty { stringResource(R.string.chat_history_image) }
            Text(
                text,
                style = DsType.std14,
                color = colors.labelPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.hasImage && entry.text.isNotEmpty()) {
                Spacer(Modifier.height(DsSpacing.tiny))
                DsPill(text = stringResource(R.string.chat_history_image))
            }
        }
    }
}

/**
 * Below this many prompts the whole list fits in a thumb-flick, so a search field would cost more
 * attention than it saves.
 */
private const val SEARCH_FROM = 8
