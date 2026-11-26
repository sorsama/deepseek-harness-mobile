package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.data.archiveStopDialogFor
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.HoldAction
import com.labteto.dshmobile.ui.components.HoldMenu
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.clickableWithHold
import com.labteto.dshmobile.ui.components.mascotPainter
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.components.rememberCopier
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a row needs to be moved: how to pick it up, and the two TalkBack steps. Null on a row that cannot move. */
internal class RowDrag(
    val onPickUp: () -> Unit,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val onStep: (Int) -> Boolean,
)

/**
 * The grip a row is dragged by.
 *
 * It sits *beside* the row's tap-and-hold area, never inside it, so a tap on the row opens the
 * session, a hold on the row opens its menu, and only the grip can lift the row. It is hidden from
 * the accessibility tree because it has no action a screen reader can take; the row carries "Move
 * up" and "Move down" instead.
 */
@Composable
internal fun DragHandle(state: ReorderState, onPickUp: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Box(
        modifier = modifier
            .width(DsSizes.touchTarget - DsSpacing.small)
            .heightIn(min = DsSizes.touchTarget)
            .reorderHandle(state, onPickUp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            FeatherIcons.GripVertical,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * One session row: the mascot avatar, its title, a status line, and how long ago it changed.
 *
 * [depth] indents the row under whatever spawned it, and a row with [childCount] subagents carries
 * a disclosure chevron. [index] drives the cheap arrival stagger — the first few rows rise in, the
 * rest arrive with the list, so scrolling never waits on an animation.
 *
 * A row that can be reordered ([drag] non-null) shows a grip at its end. The grip is outside the
 * row's own tap-and-hold area, so the hold menu and the tap keep working untouched; the same moves
 * are offered to a screen reader as "Move up" and "Move down" custom actions on the row. [blockKey]
 * is the block the row belongs to, which decides whether it dims while something else is held.
 * [archived] rows are the ones in the Archived filter, where the hold menu offers Restore rather than
 * Archive.
 */
@Composable
internal fun SessionRowItem(
    session: SessionRow,
    index: Int,
    isCurrent: Boolean,
    store: SessionStore,
    scope: CoroutineScope,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    reorder: ReorderState? = null,
    blockKey: String? = null,
    drag: RowDrag? = null,
    archived: Boolean = false,
    onNotice: (String) -> Unit = {},
    depth: Int = 0,
    childCount: Int = 0,
    childrenExpanded: Boolean = false,
    onToggleChildren: () -> Unit = {},
    /** Whether the session is pinned; null when the harness has no pinning to offer. */
    pinned: Boolean? = null,
) {
    val colors = DsTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var archiveConfirmOpen by remember { mutableStateOf(false) }
    val copy = rememberCopier()
    val restoreFailed = stringResource(R.string.chatlist_restore_failed)
    val moveUpLabel = stringResource(R.string.chatlist_move_up)
    val moveDownLabel = stringResource(R.string.chatlist_move_down)
    val held = reorder != null && drag != null && reorder.draggedId == session.sessionId
    var archiveBusy by remember { mutableStateOf<com.labteto.dshmobile.data.ArchiveOutcome.Busy?>(null) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (childrenExpanded) 90f else 0f,
        animationSpec = DsAnimations.chevron,
        label = "sessionChevron",
    )
    val avatarTint = when {
        session.pendingInteraction != null -> colors.warn
        session.running -> colors.accent
        isCurrent -> colors.accent
        else -> colors.labelDimmed
    }
    val status = statusLine(session)

    Box {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = (depth * 18).dp)
                .arrival(index)
                // Raised, dimmed or following the finger: see reorderPresentation. Before the clip and
                // fill so the whole drawn row moves, not just its contents.
                .then(
                    if (reorder != null) {
                        Modifier.reorderPresentation(reorder, if (drag != null) session.sessionId else null, blockKey)
                    } else {
                        Modifier
                    },
                )
                .clip(DsShapes.row)
                .background(
                    when {
                        held -> colors.bgLayer2
                        isCurrent -> colors.sidebarNavActive
                        else -> Color.Transparent
                    },
                )
                .then(if (held) Modifier.border(1.dp, colors.borderL3, DsShapes.row) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickableWithHold(
                        holdLabel = stringResource(R.string.hold_session_actions),
                        // The hold gesture's own haptic fires inside clickableWithHold; adding another
                        // here would buzz twice for one press.
                        onHold = { menuOpen = true },
                        role = Role.Button,
                        onClick = onOpen,
                    )
                    .semantics {
                        if (drag != null) {
                            customActions = buildList {
                                if (drag.canMoveUp) add(CustomAccessibilityAction(moveUpLabel) { drag.onStep(-1) })
                                if (drag.canMoveDown) add(CustomAccessibilityAction(moveDownLabel) { drag.onStep(1) })
                            }
                        }
                    }
                    .padding(start = DsSpacing.small, end = if (drag != null) 0.dp else DsSpacing.small, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SessionAvatar(avatarTint, if (depth > 0) 26.dp else 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = displaySessionTitle(session),
                        style = DsType.rowTitle,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = status,
                        style = DsType.small13,
                        color = if (session.pendingInteraction != null) colors.warnLabel else colors.labelTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (childCount > 0) {
                    Box(
                        Modifier
                            .size(DsSpacing.touchTarget)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onToggleChildren),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.chatlist_subagents),
                            tint = colors.labelTertiary,
                            modifier = Modifier
                                .size(18.dp)
                                .graphicsLayer { rotationZ = chevronRotation },
                        )
                    }
                }
                Text(
                    relativeTime(session.updatedAt),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                    maxLines = 1,
                )
            }
            if (drag != null && reorder != null) DragHandle(reorder, drag.onPickUp)
        }

        if (menuOpen) {
            HoldMenu(
                onDismiss = { menuOpen = false },
                preview = displaySessionTitle(session),
                actions = buildList {
                    // `session/rename` resolves the ordinary agent behind the session, and a child's
                    // is owned by subagent routing: the host refuses it. A child's title belongs to
                    // its creator.
                    if (session.origin != "subagent") {
                        add(HoldAction(stringResource(R.string.chatlist_session_rename), Icons.Outlined.Edit) { renameOpen = true })
                    }
                    add(
                        HoldAction(stringResource(R.string.chatlist_session_fork), Icons.AutoMirrored.Outlined.CallSplit) {
                            scope.launch { store.forkSession(session.sessionId) }
                        },
                    )
                    if (pinned != null) {
                        add(
                            HoldAction(
                                stringResource(if (pinned) R.string.chatlist_session_unpin else R.string.chatlist_session_pin),
                                Icons.Outlined.PushPin,
                            ) { scope.launch { store.setPinned(session.sessionId, !pinned) } },
                        )
                    }
                    add(HoldAction(stringResource(R.string.chatlist_session_copy_id), Icons.Outlined.ContentCopy) { copy(session.sessionId) })
                    if (archived) {
                        // Already archived: the verb that makes sense here is the one that undoes it.
                        add(
                            HoldAction(stringResource(R.string.archived_restore), Icons.Outlined.Restore) {
                                scope.launch {
                                    if (!store.unarchiveSession(session.sessionId)) onNotice(restoreFailed)
                                }
                            },
                        )
                    } else {
                        add(
                            HoldAction(stringResource(R.string.chatlist_session_archive), Icons.Outlined.Archive) {
                                // Work this row is known to be running goes straight to "Stop and archive";
                                // otherwise the plain confirm, and the host's refusal still has its say.
                                val stop = archiveStopDialogFor(session.sessionId, store.knownActivityOf(session))
                                if (stop != null) archiveBusy = stop else archiveConfirmOpen = true
                            },
                        )
                    }
                },
            )
        }
    }

    // The action is gone for a child; the dialog is the same refused write reached another way,
    // so it stays closed there too.
    if (renameOpen && session.origin != "subagent") {
        RenameDialog(
            initial = session.title.orEmpty(),
            title = stringResource(R.string.chatlist_session_rename),
            onDismiss = { renameOpen = false },
            onConfirm = {
                scope.launch { store.renameSession(session.sessionId, it) }
                renameOpen = false
            },
        )
    }

    if (archiveConfirmOpen) {
        ConfirmDialog(
            title = stringResource(R.string.chatlist_session_archive),
            body = displaySessionTitle(session),
            confirmLabel = stringResource(R.string.common_archive),
            onDismiss = { archiveConfirmOpen = false },
            onConfirm = {
                archiveConfirmOpen = false
                scope.launch {
                    (store.archiveSession(session.sessionId) as? com.labteto.dshmobile.data.ArchiveOutcome.Busy)
                        ?.let { archiveBusy = it }
                }
            },
        )
    }

    archiveBusy?.let { busy ->
        ArchiveBusyDialog(
            busy = busy,
            onDismiss = { archiveBusy = null },
            onConfirm = {
                archiveBusy = null
                scope.launch { store.archiveSession(busy.sessionId, stopActivity = true) }
            },
        )
    }
}

/** The mascot avatar at row scale: a static tinted mark, not an animated one per visible row. */
@Composable
internal fun SessionAvatar(tint: Color, size: androidx.compose.ui.unit.Dp) {
    Image(
        painter = mascotPainter(),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.size(size),
    )
}

/** "Streaming · todos-api" / "Needs you" / "Idle · home". Real facts only. */
@Composable
private fun statusLine(session: SessionRow): String {
    val state = when {
        session.running -> stringResource(R.string.ink_chat_streaming)
        session.pendingInteraction != null -> stringResource(R.string.chatlist_needs_action)
        session.origin == "subagent" -> stringResource(R.string.chatlist_subagents)
        else -> stringResource(R.string.chat_status_idle)
    }
    val place = session.cwd?.takeIf { it.isNotBlank() }?.let { basename(it) }
    return listOfNotNull(state, place).joinToString(" · ")
}

/**
 * Cheap arrival stagger for the first rows of the list.
 *
 * A LazyColumn composes and recycles rows as it scrolls, so this only ever runs for the rows that
 * happen to be laid out first — which are also the only ones whose arrival anyone watches. Past
 * [DsAnimations.staggerDelay]'s cap the delay stops growing, so a long list still settles quickly.
 */
@Composable
internal fun Modifier.arrival(index: Int): Modifier {
    if (index > 7) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            tween(320, delayMillis = DsAnimations.staggerDelay(index), easing = DsAnimations.inkDecelerate),
        )
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 8.dp.toPx()
    }
}
