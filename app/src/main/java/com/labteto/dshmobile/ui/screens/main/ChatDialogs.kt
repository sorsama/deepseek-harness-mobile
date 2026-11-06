package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
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
    DsDialog(title = title, onDismiss = onDismiss) {
        Text(body, style = DsType.std14, color = DsTheme.colors.labelSecondary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
            )
            Spacer(Modifier.width(DsSpacing.small))
            DsButton(text = confirmLabel, onClick = onConfirm, variant = DsButtonVariant.Danger)
        }
    }
}

/**
 * Harness 0.1.7 refused to archive a session because work is still running in it. Names that work
 * and offers to stop it; confirming archives again with `stopActivity`, which stops it first.
 */
@Composable
internal fun ArchiveBusyDialog(
    busy: com.labteto.dshmobile.data.ArchiveOutcome.Busy,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val names = busy.activity.map { activity ->
        when (activity.kind) {
            "turn" -> stringResource(R.string.archive_activity_turn)
            "subagent" -> stringResource(R.string.archive_activity_subagent)
            "job" -> stringResource(R.string.archive_activity_job)
            "schedule" -> stringResource(R.string.archive_activity_schedule)
            // A family this build does not know: the host's own word beats saying nothing.
            else -> activity.kind
        }
    }.distinct()
    ConfirmDialog(
        title = stringResource(R.string.archive_busy_title),
        body = stringResource(R.string.archive_busy_body, names.joinToString(", ").ifEmpty { "\u2026" }),
        confirmLabel = stringResource(R.string.archive_busy_confirm),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
