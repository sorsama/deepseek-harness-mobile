package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** Expansion is presentation only, independent of the harness's whole-project collapse preference. */
internal class WorkspaceExpansion(initial: Map<String, Int> = emptyMap()) {
    private val counts = mutableStateMapOf<String, Int>().apply { putAll(initial) }
    private fun key(host: String?, workspace: String) = "${host.orEmpty().length}:${host.orEmpty()}$workspace"
    fun requested(host: String?, workspace: String): Int = counts[key(host, workspace)] ?: 0
    private fun attentionKey(host: String?, workspace: String) = "attention:${key(host, workspace)}"
    fun attention(host: String?, workspace: String): Int = counts[attentionKey(host, workspace)] ?: 0
    fun observeAttention(host: String?, workspace: String, baseline: Int, total: Int = Int.MAX_VALUE) {
        val next = maxOf(attention(host, workspace), baseline).coerceAtMost(total)
        if (next != attention(host, workspace)) counts[attentionKey(host, workspace)] = next
    }
    fun showMore(host: String?, workspace: String, visible: Int) {
        counts[key(host, workspace)] = visible + WORKSPACE_PREVIEW_LIMIT
    }
    fun collapse(host: String?, workspace: String) {
        counts.remove(key(host, workspace))
        counts.remove(attentionKey(host, workspace))
    }
    fun retainForDrag(host: String?, workspace: String, visible: Int) {
        counts[key(host, workspace)] = maxOf(requested(host, workspace), visible)
    }

    companion object {
        val saver = Saver<WorkspaceExpansion, HashMap<String, Int>>(
            save = { HashMap(it.counts) },
            restore = { WorkspaceExpansion(it) },
        )
    }
}

@Composable
internal fun rememberWorkspaceExpansion(): WorkspaceExpansion = rememberSaveable(saver = WorkspaceExpansion.saver) {
    WorkspaceExpansion()
}

/** Production lazy section: keeps row/subagent rendering owned by the drawer, never copies it. */
internal fun LazyListScope.workspaceSessionItems(
    key: String,
    window: WorkspaceWindow,
    onMore: () -> Unit,
    onCollapse: () -> Unit,
    controlsEnabled: Boolean = true,
    renderRoot: LazyListScope.(SessionRow) -> Unit,
) {
    window.rows.forEach { renderRoot(it) }
    if (window.remaining > 0 || window.canCollapse) {
        item(key = workspaceControlsKey(key)) {
            Column {
                if (window.remaining > 0) {
                    DsButton(
                        text = stringResource(R.string.chatlist_sessions_more, window.remaining),
                        onClick = onMore,
                        variant = DsButtonVariant.Ghost,
                        enabled = controlsEnabled,
                        modifier = Modifier.testTag("workspace-more-$key"),
                    )
                    Text(
                        stringResource(R.string.chatlist_sessions_reorder_hint),
                        style = DsType.caption11,
                        color = DsTheme.colors.labelCaption,
                        modifier = Modifier.padding(horizontal = DsSpacing.small),
                    )
                }
                if (window.canCollapse) {
                    DsButton(
                        text = stringResource(R.string.chatlist_sessions_collapse),
                        onClick = onCollapse,
                        variant = DsButtonVariant.Ghost,
                        enabled = controlsEnabled,
                        modifier = Modifier.testTag("workspace-collapse-$key"),
                    )
                }
            }
        }
    }
}
