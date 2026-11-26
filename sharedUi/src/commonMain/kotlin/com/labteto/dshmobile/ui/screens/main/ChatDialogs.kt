package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.inkFieldFrame
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** A single-field rename dialog, shared by the session and workspace rename paths. */
@Composable
internal fun RenameDialog(
    initial: String,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    multiline: Boolean = false,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    DsDialog(title = title, onDismiss = onDismiss) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().inkFieldFrame(),
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            placeholder = { Text(title, style = DsType.std14) },
            colors = dialogTextFieldColors(),
            shape = DsShapes.control,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
            )
            Spacer(Modifier.width(DsSpacing.small))
            DsButton(
                text = stringResource(R.string.common_save),
                onClick = { onConfirm(text.trim()) },
                variant = DsButtonVariant.Info,
                enabled = text.isNotBlank(),
            )
        }
    }
}

/** A destructive confirmation with an explanatory body. */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    DsDialog(
        title = title,
        onDismiss = onDismiss,
        body = { Text(body, style = DsType.std14, color = DsTheme.colors.labelSecondary) },
        actions = {
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
            )
            DsButton(text = confirmLabel, onClick = onConfirm, variant = DsButtonVariant.Danger)
        },
    )
}

/**
 * "Stop and archive": the session has work running, so archiving stops it first.
 *
 * Opened two ways: straight from Archive when the app already knows work is running, or after the
 * host refused a plain archive as `workspace/session-active`. Either way it lists the work, says
 * the stopped work will not resume by itself, and confirming archives with `stopActivity`.
 */
@Composable
internal fun ArchiveBusyDialog(
    busy: com.labteto.dshmobile.data.ArchiveOutcome.Busy,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = DsTheme.colors
    DsDialog(
        title = stringResource(R.string.archive_busy_title),
        onDismiss = onDismiss,
        body = {
            Text(stringResource(R.string.archive_busy_body), style = DsType.std14, color = colors.labelSecondary)
            if (busy.activity.isNotEmpty()) ArchiveActivityList(busy.activity)
            Text(stringResource(R.string.archive_busy_after), style = DsType.std14, color = colors.labelSecondary)
        },
        actions = {
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
            )
            DsButton(
                text = stringResource(R.string.archive_busy_confirm),
                onClick = onConfirm,
                variant = DsButtonVariant.Danger,
            )
        },
    )
}

/** Longest stretch of an activity label shown; a job's command line can run to kilobytes. */
private const val ARCHIVE_LABEL_CHARS = 160

/** Items listed under one kind before the rest are counted instead. */
private const val ARCHIVE_ITEMS_SHOWN = 4

/**
 * The running work, one block per kind. The host's own label goes under the kind in the monospace
 * style commands use elsewhere; a kind or an item with no label is shown without one.
 */
@Composable
private fun ArchiveActivityList(activity: List<com.labteto.dshmobile.core.wire.dto.SessionActivity>) {
    val colors = DsTheme.colors
    // Commands read left to right whatever the interface language is.
    val commandStyle = DsType.mono12.copy(textDirection = TextDirection.Ltr)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.control)
            .background(colors.bgLayer1)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        activity.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (entry.kind) {
                        "turn" -> stringResource(R.string.archive_activity_turn)
                        "subagent" -> stringResource(R.string.archive_activity_subagent)
                        "job" -> stringResource(R.string.archive_activity_job)
                        "schedule" -> stringResource(R.string.archive_activity_schedule)
                        // A family this build does not know: the host's own word beats saying nothing.
                        else -> entry.kind
                    },
                    style = DsType.small13,
                    color = colors.labelPrimary,
                )
                val labelled = entry.items.mapNotNull { it.label?.trim()?.takeIf(String::isNotEmpty) }
                labelled.take(ARCHIVE_ITEMS_SHOWN).forEach { label ->
                    Text(
                        label.take(ARCHIVE_LABEL_CHARS),
                        style = commandStyle,
                        color = colors.labelSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val hidden = labelled.size - ARCHIVE_ITEMS_SHOWN
                if (hidden > 0) {
                    Text(
                        stringResource(R.string.archive_activity_more, hidden),
                        style = DsType.caption11,
                        color = colors.labelCaption,
                    )
                }
            }
        }
    }
}
