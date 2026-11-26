package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The "By project" list: projects in the harness's workspace order, each under a header that folds,
 * with its own sessions in the workspace's manual order. The sessions that belong to no workspace are
 * the last group.
 *
 * [blocks] are live while a unit is held. A group with no row that [passes] the filter is left out.
 * A workspace in [compactWindows] shows only its window, with Show more and Show less
 * ([onShowMore] gets how many rows are shown now). [rows] lays out one top-level session and whatever
 * is open under it. [onStep] is the screen reader's "Move up" and "Move down" for a project.
 */
internal fun LazyListScope.workspaceGroups(
    blocks: List<SessionBlock>,
    passes: (SessionRow) -> Boolean,
    otherName: String,
    isCollapsed: (blockKey: String) -> Boolean,
    onCollapse: (blockKey: String, collapsed: Boolean) -> Unit,
    reorder: ReorderState,
    projectsDraggable: Boolean,
    onStep: (group: String, order: List<String>, id: String, step: Int) -> Boolean,
    compactWindows: Map<String, WorkspaceWindow>,
    onShowMore: (blockKey: String, shown: Int) -> Unit,
    onShowLess: (blockKey: String) -> Unit,
    rows: LazyListScope.(root: SessionRow, block: SessionBlock) -> Unit,
) {
    blocks.forEach { block ->
        val passing = block.roots.filter(passes)
        if (passing.isEmpty()) return@forEach
        val title = block.workspace?.let { it.title.ifBlank { basename(it.path) } } ?: otherName
        val collapsed = isCollapsed(block.key)
        val heldProject = reorder.groupKey == PROJECTS_GROUP && reorder.draggedId == block.key
        item(key = projectKey(block.key)) {
            val workspaceBlocks = blocks.filter { !it.isOther }
            val order = workspaceBlocks.map { it.key }
            val at = order.indexOf(block.key)
            ProjectHeader(
                title = title,
                count = passing.size,
                collapsed = collapsed,
                onToggle = { onCollapse(block.key, !collapsed) },
                reorder = reorder,
                blockKey = block.key,
                modifier = if (heldProject) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                drag = if (projectsDraggable && !block.isOther && at >= 0) {
                    RowDrag(
                        onPickUp = { reorder.start(block.key, PROJECTS_GROUP, order) },
                        canMoveUp = at > 0,
                        canMoveDown = at < order.lastIndex,
                        onStep = { step -> onStep(PROJECTS_GROUP, order, block.key, step) },
                    )
                } else {
                    null
                },
            )
        }
        if (!collapsed && !heldProject) {
            val window = compactWindows[block.key]
            if (window == null) {
                passing.forEach { root -> rows(root, block) }
            } else {
                workspaceSessionItems(
                    key = block.key,
                    window = window,
                    onMore = { onShowMore(block.key, window.rows.size) },
                    onCollapse = { onShowLess(block.key) },
                    controlsEnabled = !reorder.active,
                ) { root -> rows(root, block) }
            }
        }
    }
}

/**
 * A project header in the "By project" list: a fold chevron, the project's name, how many sessions
 * it shows, and (when projects can be reordered) its own grip.
 */
@Composable
internal fun ProjectHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    reorder: ReorderState,
    blockKey: String,
    drag: RowDrag?,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val chevron by animateFloatAsState(
        targetValue = if (collapsed) 0f else 90f,
        animationSpec = DsAnimations.chevron,
        label = "projectChevron",
    )
    val moveUp = stringResource(R.string.chatlist_move_up)
    val moveDown = stringResource(R.string.chatlist_move_down)
    val toggleLabel = stringResource(if (collapsed) R.string.chatlist_project_expand else R.string.chatlist_project_collapse, title)
    val held = drag != null && reorder.draggedId == blockKey && reorder.groupKey == PROJECTS_GROUP
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = DsSpacing.small)
            .reorderPresentation(reorder, if (drag != null) blockKey else null, blockKey)
            .clip(DsShapes.row)
            .background(if (held) colors.bgLayer2 else Color.Transparent)
            .then(if (held) Modifier.border(1.dp, colors.borderL3, DsShapes.row) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = DsSizes.touchTarget)
                .clickable(role = Role.Button, onClickLabel = toggleLabel) {
                    haptics.tap()
                    onToggle()
                }
                .semantics {
                    if (drag != null) {
                        customActions = buildList {
                            if (drag.canMoveUp) add(CustomAccessibilityAction(moveUp) { drag.onStep(-1) })
                            if (drag.canMoveDown) add(CustomAccessibilityAction(moveDown) { drag.onStep(1) })
                        }
                    }
                }
                .padding(horizontal = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = chevron },
            )
            Text(
                title.uppercase(),
                style = DsType.label12,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            DsPill(text = count.toString())
        }
        if (drag != null) DragHandle(reorder, drag.onPickUp)
    }
}

/** A date group heading: the mock's quiet uppercase "TODAY". */
@Composable
internal fun DateHeading(text: String, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Text(
        text.uppercase(),
        style = DsType.label12,
        color = colors.labelTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    )
}
