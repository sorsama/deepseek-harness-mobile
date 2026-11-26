package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.data.WorkspaceRow
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.inkFieldFrame
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What a platform adds to the workspace list while it is open: actions at the end of each row, and the
 * dialogs those actions open. Android's built-in harness adds Files, zip and copy for a project.
 */
internal class WorkspaceListExtras(
    val rowActions: @Composable (WorkspaceRow) -> Unit,
    val dialogs: @Composable () -> Unit,
)

/** Where a new session starts: a workspace, or the harness's home directory. */
@Composable
internal fun NewSessionDialog(
    workspaces: List<WorkspaceRow>,
    homeCwd: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    /** Rows after the home directory; on Android's built-in harness, the ways to start a project there. */
    extraRows: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    DsDialog(title = stringResource(R.string.chatlist_new_session_in), onDismiss = onDismiss) {
        if (workspaces.isEmpty()) {
            Text(
                stringResource(R.string.chatlist_no_workspaces),
                style = DsType.std14,
                color = colors.labelSecondary,
            )
        }
        workspaces.forEach { workspace ->
            SheetRow(
                title = workspace.title.ifBlank { basename(workspace.path) },
                subtitle = workspace.path,
                onClick = { onPick(workspace.workspaceId) },
            )
        }
        SheetRow(
            title = stringResource(R.string.chatlist_home_directory),
            subtitle = homeCwd,
            onClick = { onPick(null) },
        )
        extraRows?.invoke(this)
    }
}

@Composable
internal fun NewWorkspaceDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var pathText by remember { mutableStateOf("") }
    DsDialog(title = stringResource(R.string.chatlist_new_workspace), onDismiss = onDismiss) {
        TextField(
            value = pathText,
            onValueChange = { pathText = it },
            modifier = Modifier.fillMaxWidth().inkFieldFrame(),
            placeholder = { Text(stringResource(R.string.chatlist_workspace_path), style = DsType.std14) },
            singleLine = true,
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
                onClick = { onCreate(pathText.trim()) },
                variant = DsButtonVariant.Info,
                enabled = pathText.isNotBlank(),
            )
        }
    }
}

/**
 * Workspace rename and removal, the two verbs the old drawer had on a workspace header.
 *
 * The mock's flat session list has no header to hold them, so they live behind the overflow menu
 * instead of disappearing: a workspace is still a thing with a name and a registration.
 */
@Composable
internal fun WorkspacesDialog(
    workspaces: List<WorkspaceRow>,
    store: SessionStore,
    scope: CoroutineScope,
    /** What the platform adds to the list (see [WorkspaceListExtras]); given this dialog's dismiss. */
    extras: (@Composable (onDismiss: () -> Unit) -> WorkspaceListExtras)? = null,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    var renaming by remember { mutableStateOf<WorkspaceRow?>(null) }
    val added = extras?.invoke(onDismiss)
    var deleting by remember { mutableStateOf<WorkspaceRow?>(null) }
    DsDialog(title = stringResource(R.string.ink_chat_workspaces), onDismiss = onDismiss) {
        if (workspaces.isEmpty()) {
            Text(
                stringResource(R.string.chatlist_no_workspaces),
                style = DsType.std14,
                color = colors.labelSecondary,
            )
        }
        workspaces.forEach { workspace ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        workspace.title.ifBlank { basename(workspace.path) },
                        style = DsType.std14Strong,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        technicalDisplay(workspace.path),
                        style = DsType.mono12,
                        color = colors.labelTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                added?.rowActions?.invoke(workspace)
                DsIconButton(
                    icon = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.chatlist_workspace_rename),
                    onClick = { renaming = workspace },
                    tint = colors.labelSecondary,
                    iconSize = 18.dp,
                )
                DsIconButton(
                    icon = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.chatlist_workspace_delete),
                    onClick = { deleting = workspace },
                    tint = colors.error,
                    iconSize = 18.dp,
                )
            }
        }
    }
    added?.dialogs?.invoke()
    renaming?.let { workspace ->
        RenameDialog(
            initial = workspace.title,
            title = stringResource(R.string.chatlist_workspace_rename),
            onDismiss = { renaming = null },
            onConfirm = {
                scope.launch { store.renameWorkspace(workspace.workspaceId, it) }
                renaming = null
            },
        )
    }
    deleting?.let { workspace ->
        ConfirmDialog(
            title = stringResource(R.string.chatlist_workspace_delete),
            body = stringResource(R.string.chatlist_workspace_delete_confirm),
            confirmLabel = stringResource(R.string.common_remove),
            onDismiss = { deleting = null },
            onConfirm = {
                scope.launch { store.deleteWorkspace(workspace.workspaceId) }
                deleting = null
            },
        )
    }
}
