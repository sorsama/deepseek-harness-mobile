package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.data.MoveOutcome
import com.labteto.dshmobile.data.QuestionOutcome
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.data.WorkspaceRow
import com.labteto.dshmobile.data.anchorAfterMove
import com.labteto.dshmobile.data.failureNotice
import com.labteto.dshmobile.data.moveByStep
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsFilterChip
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIconMenu
import com.labteto.dshmobile.ui.components.DsSearchField
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.inkFieldFrame
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.HoldAction
import com.labteto.dshmobile.ui.components.HoldMenu
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.PullToRefreshIndicator
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.clickableWithHold
import com.labteto.dshmobile.ui.components.rememberCopier
import com.labteto.dshmobile.ui.components.mascotPainter
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.components.rememberPullToRefresh
import com.labteto.dshmobile.ui.components.relativeTime
import com.labteto.dshmobile.ui.rememberHostsStore
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Base nonblank prefix and Show more step for a compact workspace in the project view.
 */
internal const val WORKSPACE_PREVIEW_LIMIT = 5

/** The quick filters above the list. */
private enum class DrawerFilter { All, Running, NeedsYou, Archived }

/**
 * The sessions drawer: the Ink redesign's "Sessions" screen.
 *
 * The structure follows the mock — wordmark and harness status at the top, a hero title with the
 * current workspace and model, quick filters with live counts, the one session that needs a person,
 * a date-grouped list, and a "start a task" bar pinned to the bottom.
 *
 * What it keeps from the old drawer is all the behaviour: manual vs recency ordering, the search
 * field and content results, pinning, subagent nesting with its disclosure chevrons, archiving (now
 * a filter rather than a section at the foot), and the per-session hold menu. Workspace management
 * moved into the overflow menu, because the mock's flat list has no room for a workspace header —
 * but the verbs are all still there.
 *
 * Three orders, one preference ([HostsStore.sessionSort][com.labteto.dshmobile.connection.HostsStore.sessionSort]):
 *  - **Manual** follows the harness's own workspace order under date headings, and a session can be
 *    dragged by its grip to a new place *within its workspace* (the harness cannot move a session
 *    between workspaces). Sessions in no workspace are ordered on the phone.
 *  - **Last updated** is newest first under date headings.
 *  - **By project** groups under collapsible workspace headers, same order inside each, same drag.
 */
@Composable
fun ChatListDrawer(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAutomations: () -> Unit = {},
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val store = rememberSessionStore()
    val automationAvailable by store.automationAvailable.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val hostsStore = rememberHostsStore()

    val sessions by store.sessions.collectAsStateWithLifecycle()
    val workspaces by store.workspaces.collectAsStateWithLifecycle()
    val archivedIds by store.archivedSessionIds.collectAsStateWithLifecycle()
    val searchResults by store.searchResults.collectAsStateWithLifecycle()
    val contentSearchAvailable by store.contentSearchAvailable.collectAsStateWithLifecycle()
    val currentSessionId by store.currentSessionId.collectAsStateWithLifecycle()
    val hostInfo by store.hostInfo.collectAsStateWithLifecycle()
    // Null against a harness without pinning (before 0.1.7), which hides the action.
    val pinnedIds by store.pinnedSessionIds.collectAsStateWithLifecycle()
    val pendingApproval by store.pendingApproval.collectAsStateWithLifecycle()
    val models by store.models.collectAsStateWithLifecycle()
    val connection by store.connectionState.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(DrawerFilter.All) }
    // Persisted, not remembered: the order you read your sessions in is a preference.
    val sessionSort by hostsStore.sessionSort.collectAsStateWithLifecycle(initialValue = SORT_MANUAL)
    val sort = normalizeSessionSort(sessionSort)
    val sortByRecency = sort == SORT_UPDATED
    val byProject = sort == SORT_PROJECT

    // Per-host, per-phone state the harness has no place for: the order of sessions that belong to no
    // workspace, and which project headers the reader folded shut.
    val hostKey = connection.host?.let { "${it.baseUrl}|${it.id}" }
    val savedUngrouped by remember(hostKey) { hostsStore.ungroupedOrder(hostKey) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // What the reader just arranged, shown until the saved copy catches up so a drop never flickers.
    var ungroupedOverride by remember(hostKey) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(savedUngrouped) {
        if (ungroupedOverride != null && ungroupedOverride == savedUngrouped) ungroupedOverride = null
    }
    val savedCollapsed by remember(hostKey) { hostsStore.collapsedProjects(hostKey) }
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val collapsedNow = remember(hostKey) { mutableStateMapOf<String, Boolean>() }
    fun isProjectCollapsed(key: String): Boolean = collapsedNow[key] ?: (key in savedCollapsed)

    val toast = rememberDsToast()
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val reorder = remember(listState, haptics, scope, density) {
        ReorderState(
            listState = listState,
            scope = scope,
            haptics = haptics,
            edgePx = with(density) { 72.dp.toPx() },
            maxStepPx = with(density) { 14.dp.toPx() },
        )
    }
    var newWorkspaceOpen by remember { mutableStateOf(false) }
    var newSessionOpen by remember { mutableStateOf(false) }
    var workspacesOpen by remember { mutableStateOf(false) }
    // Subagent disclosure is independent of compact workspace expansion and keyed by host.
    val collapsed = remember(hostKey) { mutableStateMapOf<String, Boolean>() }
    val hapticsTap = { haptics.tap() }

    // ---- Pull to refresh -------------------------------------------------------------------
    // The store keeps this list current by itself: pushes carry most changes and the fallback
    // re-reads on open, on resume and every so often. This is the manual version — a person who
    // wants to be sure, and the only refresh that says so where the gesture happened. The read is
    // quiet: a failure lands as a toast in the drawer, not as a banner on the chat underneath it.
    val refreshLabel = stringResource(R.string.chatlist_refresh)
    val refreshFailedLabel = stringResource(R.string.chatlist_refresh_failed)
    var refreshFailure by remember { mutableStateOf<String?>(null) }
    val refresh = rememberPullRefreshCycle(
        scope = scope,
        read = { store.refreshSessions(quiet = true) },
        onDone = { haptics.confirm() },
        onFailed = { reason ->
            haptics.reject()
            refreshFailure = failureNotice(refreshFailedLabel, reason)
        },
    )
    val refreshPhase by refresh.phase.collectAsStateWithLifecycle()
    val pull = rememberPullToRefresh(
        phase = refreshPhase,
        onRefresh = refresh::start,
        // A held row is being dragged by the finger; the pull would fight it for the same gesture.
        enabled = !reorder.active,
    )
    // The toast waits for the cross to leave: two floating plates in the same place would collide.
    LaunchedEffect(refreshFailure) {
        val failure = refreshFailure ?: return@LaunchedEffect
        delay(320)
        toast.second(failure)
        refreshFailure = null
    }

    LaunchedEffect(query) {
        delay(250)
        store.search(query.trim())
    }

    // Local matching is not debounced: it is a string comparison over a list already in memory, and
    // making someone wait a quarter second for it is what made search feel like it did nothing.
    val searchHits = remember(sessions, workspaces, archivedIds, query, searchResults) {
        deriveSearchResults(
            sessions = sessions,
            workspaces = workspaces,
            archivedIds = archivedIds,
            query = query,
            contentHits = searchResults,
        )
    }

    // Blank sessions are scratch space the harness reuses; subagent transcripts belong under their
    // parent, not as top-level rows.
    val listable = sessions.filter { it.sessionId !in archivedIds && !it.blank }
    val sessionsById = sessions.associateBy { it.sessionId }
    val archivedSessions = sessions.filter { it.sessionId in archivedIds }

    // Subagents nest under the session that spawned them. `origin` is the discriminator, not
    // `parentSessionId` — an ordinary fork sets a parent too, and a fork is a session in its own
    // right that belongs at the top level.
    val childrenByParent = remember(listable) { indexSubagents(listable, sessionsById) }
    val nestedIds = remember(childrenByParent) {
        childrenByParent.values.flatten().mapTo(HashSet()) { it.sessionId }
    }
    // Every session between the open one and the root, so its ancestors default to open.
    val openPath = remember(currentSessionId, sessionsById) {
        sessionOpenPath(currentSessionId, sessionsById)
    }
    val workspaceExpansion = rememberWorkspaceExpansion()
    // A newly opened target (including a search hit) reveals its lineage once. A subsequent manual
    // fold is respected; do not force a project open on every recomposition.
    val targetProjects = remember(workspaces, openPath) {
        workspaces.filter { workspace -> workspace.sessionIds.any { it in openPath } }.map { it.workspaceId }
    }
    LaunchedEffect(hostKey, currentSessionId, openPath, targetProjects) {
        openPath.forEach { collapsed.remove(it) }
        targetProjects.forEach { collapsedNow[it] = false }
    }
    fun isExpanded(sessionId: String): Boolean = collapsed[sessionId]?.not() ?: (sessionId in openPath)
    fun toggleChildren(sessionId: String) {
        collapsed[sessionId] = isExpanded(sessionId)
    }

    // Top-level rows grouped into blocks that share one order: each workspace's sessions in the
    // harness's order, then the sessions that belong to none, in the order saved on this phone.
    // Manual order is the blocks end to end, which is what `sessionSort = manual` means.
    val ungroupedOrder = ungroupedOverride ?: savedUngrouped
    val blocks = remember(listable, workspaces, nestedIds, ungroupedOrder) {
        buildSessionBlocks(listable, workspaces, nestedIds, ungroupedOrder)
    }
    val roots = remember(blocks, sortByRecency) {
        val ordered = flattenBlocks(blocks)
        if (sortByRecency) ordered.sortedByDescending(SessionRow::updatedAt) else ordered
    }
    SideEffect {
        if (byProject && filter == DrawerFilter.All && query.isBlank()) blocks.filterNot { it.isOther }.forEach { block ->
            workspaceExpansion.observeAttention(
                hostKey, block.key, workspaceAttentionBaseline(block.roots, childrenByParent), block.roots.size,
            )
        }
    }
    // While a unit is held, the blocks show its live order; the rest of the screen keeps `blocks`.
    val liveBlocks = when {
        !reorder.active -> blocks
        reorder.groupKey == PROJECTS_GROUP -> withLiveBlockOrder(blocks, reorder.liveOrder)
        else -> withLiveOrder(blocks, reorder.groupKey, reorder.liveOrder)
    }
    // A move only makes sense on the whole list: a filtered or searched view hides the neighbours a
    // row would be placed between, and an archived row is not part of any order.
    val reorderEnabled = !sortByRecency && filter == DrawerFilter.All && query.isBlank() &&
        connection.phase == ConnectionPhase.CONNECTED
    val runningCount = listable.count { it.running }
    val needsYouCount = listable.count { it.pendingInteraction != null }
    val pinnedRows = pinnedIds.orEmpty().mapNotNull { id -> listable.firstOrNull { it.sessionId == id } }

    /** Depth-first expansion of one top-level session, honouring each row's collapse state. */
    fun subtree(root: SessionRow): List<Pair<SessionRow, Int>> {
        val out = mutableListOf<Pair<SessionRow, Int>>()
        val visited = HashSet<String>()
        fun walk(row: SessionRow, depth: Int) {
            if (!visited.add(row.sessionId)) return
            out += row to depth
            val children = childrenByParent[row.sessionId].orEmpty()
            // A lifted session carries its subagents with it, but draws as one row: the children are
            // tucked away until it lands, so what is under the finger is a single, known height.
            val lifted = reorder.draggedId == row.sessionId && reorder.groupKey != PROJECTS_GROUP
            if (children.isEmpty() || !isExpanded(row.sessionId) || lifted) return
            val ordered = if (sortByRecency) children.sortedByDescending(SessionRow::updatedAt) else children
            ordered.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    fun openSession(sessionId: String) {
        // Close first. Waiting for the session to load before the drawer moved made every switch
        // feel like a stall; the transcript shows its own skeleton.
        hapticsTap()
        onClose()
        scope.launch { store.openSession(sessionId) }
    }

    // ---- Reordering: what a drop, or a "Move up" from TalkBack, actually does ---------------------
    val view = LocalView.current
    val moveFailedText = stringResource(R.string.chatlist_reorder_failed)
    val projectMoveFailedText = stringResource(R.string.chatlist_reorder_project_failed)
    val staysInProjectText = stringResource(R.string.chatlist_reorder_blocked)
    val moveAnnounceFormat = stringResource(R.string.chatlist_move_announce)
    val otherName = stringResource(R.string.chatlist_group_other)
    val answerRefused = stringResource(R.string.questions_answer_refused)
    val answerUnsent = stringResource(R.string.questions_answer_unsent)

    /** What to tell the person about an approval answer, or null when the harness took it. */
    fun refusalOf(outcome: QuestionOutcome): String? = when (outcome) {
        is QuestionOutcome.Accepted -> null
        is QuestionOutcome.Refused -> answerRefused.format(outcome.reason)
        is QuestionOutcome.Unsent -> answerUnsent
    }
    // The list's own keys, so the held unit can be found and measured: a session row, or a project
    // header. Each unit's keys also include what hangs under it, so a neighbour's height is its own.
    val blockOf = remember(liveBlocks) {
        liveBlocks.flatMap { block -> block.roots.map { it.sessionId to block } }.toMap()
    }
    // Date headings as they stood when a row was lifted; see the date groups below.
    var frozenDays by remember { mutableStateOf<List<LocalDate>?>(null) }
    LaunchedEffect(reorder.active) { if (!reorder.active) frozenDays = null }
    val unitKeys = HashMap<String, List<String>>().also { keys ->
        liveBlocks.forEach { block ->
            val rowKeys = ArrayList<String>()
            block.roots.forEach { root ->
                val own = subtree(root).map { (row, _) -> rowKey(row.sessionId) }
                keys[root.sessionId] = own
                rowKeys += own
            }
            // A folded project shows only its header, so that is all its unit is.
            keys[block.key] = projectUnitKeys(
                projectKey(block.key), rowKeys, block.key, byProject && !isProjectCollapsed(block.key),
            )
        }
    }
    SideEffect {
        reorder.itemKeyOf = { group, id -> if (group == PROJECTS_GROUP) projectKey(id) else rowKey(id) }
        reorder.keysOf = { id -> unitKeys[id].orEmpty() }
        reorder.onDropped = { drop ->
            if (drop.releasedOutside) toast.second(staysInProjectText)
            if (drop.changed) {
                scope.launch {
                    val anchor = anchorAfterMove(drop.order, drop.id)
                    val failure: String? = when (drop.group) {
                        PROJECTS_GROUP -> (store.moveWorkspace(drop.id, anchor) as? MoveOutcome.Failed)
                            ?.let { failureNotice(projectMoveFailedText, it.reason) }
                        // No workspace, so no host-side order to move within: the arrangement is
                        // this phone's, kept per host.
                        OTHER_BLOCK_KEY -> {
                            ungroupedOverride = drop.order
                            val kept = hostKey?.let { key ->
                                runCatching { hostsStore.setUngroupedOrder(key, drop.order) }.isSuccess
                            } ?: false
                            if (kept) null else moveFailedText.also { ungroupedOverride = null }
                        }
                        else -> (store.moveSession(drop.group, drop.id, anchor) as? MoveOutcome.Failed)
                            ?.let { failureNotice(moveFailedText, it.reason) }
                    }
                    if (failure != null) {
                        haptics.reject()
                        toast.second(failure)
                    }
                }
            }
        }
    }

    /** "Move up" / "Move down" for a screen reader: the same commit path as a drag, one place at a time. */
    fun stepUnit(group: String, order: List<String>, id: String, step: Int): Boolean {
        val next = moveByStep(order, id, step) ?: return false
        haptics.selection()
        reorder.onDropped(DropResult(id, group, next, changed = true, releasedOutside = false))
        view.announceForAccessibility(String.format(moveAnnounceFormat, next.indexOf(id) + 1, next.size))
        return true
    }

    val hostName = connection.host?.name
        ?: connection.host?.host
        ?: hostInfo?.home?.let { basename(it) }
        ?: "DSH"
    val workspacePath = sessionsById[currentSessionId]?.cwd
        ?: workspaces.firstOrNull()?.path
        ?: hostInfo?.home
    val modelLabel = models?.let { catalogue ->
        val current = catalogue.current
        catalogue.groups.firstOrNull { it.id == current.provider }
            ?.models?.firstOrNull { it.id == current.model }?.name
            ?: technicalDisplay(current.model)
    }

    val needsYouSession = remember(roots, sessionsById, pendingApproval) {
        val approvalSession = pendingApproval?.sessionId
        roots.firstOrNull { it.sessionId == approvalSession }
            ?: roots.firstOrNull { it.pendingInteraction != null }
    }
    val approval = pendingApproval?.takeIf { it.sessionId == needsYouSession?.sessionId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.sidebar)
            .safeDrawingPadding()
            .padding(horizontal = DsSpacing.comfortable),
    ) {
        // ---- Wordmark, harness status, settings ------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            MascotMark(state = MascotState.Idle, size = 30.dp)
            Text(
                "DSH",
                style = DsType.large20,
                color = colors.labelPrimary,
                modifier = Modifier.padding(start = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            HarnessPill(
                name = hostName,
                phase = connection.phase,
                onClick = { hapticsTap(); onOpenSettings() },
            )
            DsIconButton(
                icon = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.settings_title),
                onClick = onOpenSettings,
                tint = colors.labelTertiary,
                iconSize = 20.dp,
            )
        }

        // ---- Hero title, workspace · model, quick actions --------------------------------------
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ink_chat_sessions),
                    style = DsType.display24,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        workspacePath?.takeIf { it.isNotBlank() }?.let { technicalDisplay(it) },
                        modelLabel,
                    ).joinToString(" · "),
                    style = DsType.mono12,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // Search, sort and more are one family: bare 48dp icon buttons with a circular press, all
            // outline glyphs. (Sort and more used to be a bare clickable box, so their press was a
            // rectangle next to search's circle.)
            DsIconButton(
                icon = FeatherIcons.Search,
                contentDescription = stringResource(R.string.common_search),
                onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) query = ""
                },
                tint = colors.labelTertiary,
                iconSize = 20.dp,
                selected = searchOpen,
            )
            SortButton(sort) { next ->
                scope.launch { hostsStore.setSessionSort(next) }
            }
            MoreMenu(
                automationAvailable = automationAvailable != false,
                onNewSession = { newSessionOpen = true },
                onNewWorkspace = { newWorkspaceOpen = true },
                onWorkspaces = { workspacesOpen = true },
                onAutomations = onOpenAutomations,
                onRefresh = refresh::start,
            )
        }

        // ---- Quick filters. A horizontal row, because four counted chips cannot fit one phone
        // width at large type — the mock pushed the last one off the screen. The row scrolls and
        // fades at its trailing edge so there is never a chip half-cut with no way to reach it.
        // Each chip is drawn 36dp tall inside a 48dp touch target, so the row is 48dp and needs no
        // spacer above it.
        // While a row is held, the chips (which cannot be used mid-drag anyway) give way to a pill
        // that says what the held unit can and cannot do: the limit is the harness's (a session
        // cannot change workspace), so it is named rather than left to be discovered by a row that
        // will not go. It sits in the chips' own row, so it can never cover a session row.
        val projectsHint = stringResource(R.string.chatlist_reorder_projects)
        val scopeFormat = stringResource(R.string.chatlist_reorder_scope)
        val hintNow = when (val group = reorder.groupKey) {
            null -> ""
            PROJECTS_GROUP -> projectsHint
            else -> String.format(
                scopeFormat,
                blocks.firstOrNull { it.key == group }?.workspace?.let { it.title.ifBlank { basename(it.path) } }
                    ?: otherName,
            )
        }
        // Kept past the drop so the hint fades out with its words instead of emptying first.
        var hintShown by remember { mutableStateOf("") }
        if (hintNow.isNotEmpty()) hintShown = hintNow
        val chipsAlpha by animateFloatAsState(if (reorder.active) 0f else 1f, tween(140), label = "chipsAlpha")
        Box(contentAlignment = Alignment.Center) {
            FadingChipRow(fadeColor = colors.sidebar, modifier = Modifier.alpha(chipsAlpha)) {
                DsFilterChip(
                    label = stringResource(R.string.ink_chat_filter_all, listable.size),
                    selected = filter == DrawerFilter.All,
                    onClick = { filter = DrawerFilter.All },
                )
                DsFilterChip(
                    label = stringResource(R.string.ink_chat_filter_running, runningCount),
                    selected = filter == DrawerFilter.Running,
                    onClick = { filter = DrawerFilter.Running },
                )
                DsFilterChip(
                    label = stringResource(R.string.ink_chat_filter_needs_you, needsYouCount),
                    selected = filter == DrawerFilter.NeedsYou,
                    warn = needsYouCount > 0,
                    onClick = { filter = DrawerFilter.NeedsYou },
                )
                DsFilterChip(
                    label = stringResource(R.string.chatlist_archived),
                    selected = filter == DrawerFilter.Archived,
                    onClick = { filter = DrawerFilter.Archived },
                )
            }
            ReorderHint(visible = reorder.active, text = hintShown, pressing = reorder.atBoundary)
        }

        // ---- The one session that needs a person ----------------------------------------------
        AnimatedVisibility(visible = needsYouSession != null && query.isBlank()) {
            val session = needsYouSession
            if (session != null) {
                Column(Modifier.padding(top = DsSpacing.small)) {
                    NeedsYouCard(
                        session = session,
                        approvalTool = approval?.toolName,
                        approvalReason = approval?.reason,
                        onReview = { openSession(session.sessionId) },
                        onApprove = approval?.let {
                            approve@{
                                haptics.confirm()
                                // The result is not discarded: a refused or unsent approval leaves the
                                // host's wait open, and a card that quietly did nothing would read as
                                // a button that does nothing.
                                scope.launch {
                                    refusalOf(store.respondApproval(it.sessionId, it.approvalId, true))?.let { message ->
                                        haptics.reject()
                                        toast.second(message)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }

        // Search folds away rather than permanently occupying a row of a phone-height drawer.
        AnimatedVisibility(visible = searchOpen) {
            Column(modifier = Modifier.padding(top = DsSpacing.small)) {
                // Present only while search is open, so asking for focus here is "the toggle that
                // opened it was just tapped" — the keyboard comes up with the field.
                DsSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.chatlist_search_hint),
                    autoFocus = true,
                )
                // A capability note, not a failure: most harnesses ship with content search off.
                if (!contentSearchAvailable && query.isNotBlank()) {
                    Text(
                        stringResource(R.string.chatlist_search_content_off),
                        style = DsType.caption11,
                        color = colors.labelCaption,
                        modifier = Modifier.padding(top = DsSpacing.tiny),
                    )
                }
            }
        }

        Spacer(Modifier.height(DsSpacing.small))

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(pull.modifier)
                    // The list slides down to make room for the pill, so the indicator never sits
                    // on the first row; the pull lays it out, so a drag does not recompose the list.
                    .then(pull.followModifier)
                    // A pull is not reachable with a screen reader, so the same read is exposed as
                    // an action on the list (and in the overflow menu) with the same wording.
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(refreshLabel) {
                                refresh.start()
                                true
                            },
                        )
                    },
            ) {
                if (query.isNotBlank()) {
                    item(key = "search-header") {
                        Text(
                            stringResource(R.string.common_search).uppercase(),
                            style = DsType.label12,
                            color = colors.labelTertiary,
                            modifier = Modifier.padding(vertical = DsSpacing.xsmall),
                        )
                    }
                    if (searchHits.items.isEmpty()) {
                        item(key = "search-empty") {
                            Text(
                                stringResource(R.string.chatlist_search_empty),
                                style = DsType.std14,
                                color = colors.labelTertiary,
                                modifier = Modifier.padding(vertical = DsSpacing.small),
                            )
                        }
                    }
                    itemsIndexed(searchHits.items, key = { _, hit -> hit.session.sessionId }) { index, hit ->
                        SearchResultRow(hit, index, onOpen = { openSession(hit.session.sessionId) })
                    }
                    if (searchHits.hasMore) {
                        item(key = "search-more") {
                            Text(
                                stringResource(R.string.chatlist_search_refine),
                                style = DsType.caption11,
                                color = colors.labelCaption,
                                modifier = Modifier.padding(vertical = DsSpacing.xsmall),
                            )
                        }
                    }
                    return@LazyColumn
                }

                if (filter == DrawerFilter.Archived) {
                    item(key = "archived-header") {
                        DateHeading(stringResource(R.string.chatlist_archived), Modifier.padding(top = DsSpacing.xsmall))
                    }
                    if (archivedSessions.isEmpty()) {
                        item(key = "archived-empty") {
                            // Nothing archived is its own state, not "nothing matches": there is no
                            // query on this list for anything to have failed to match.
                            Text(
                                stringResource(R.string.chatlist_archived_none),
                                style = DsType.std14,
                                color = colors.labelTertiary,
                                modifier = Modifier.padding(vertical = DsSpacing.small),
                            )
                        }
                    }
                    itemsIndexed(
                        archivedSessions.sortedByDescending(SessionRow::updatedAt),
                        key = { _, session -> session.sessionId },
                    ) { index, session ->
                        SessionRowItem(
                            session = session,
                            index = index,
                            isCurrent = session.sessionId == currentSessionId,
                            store = store,
                            scope = scope,
                            reorder = reorder,
                            archived = true,
                            onNotice = { toast.second(it) },
                            pinned = null,
                            childCount = 0,
                            childrenExpanded = false,
                            onToggleChildren = {},
                            onOpen = { openSession(session.sessionId) },
                        )
                    }
                    return@LazyColumn
                }

                fun passes(session: SessionRow): Boolean = when (filter) {
                    DrawerFilter.Running -> session.running
                    DrawerFilter.NeedsYou -> session.pendingInteraction != null
                    else -> true
                }

                // The order on screen: the blocks end to end (live, while a unit is held), or newest
                // first when sorted by recency.
                val renderRoots = if (sortByRecency) roots else flattenBlocks(liveBlocks)
                val visibleRoots = renderRoots.filter(::passes)
                val projectsDraggable = reorderEnabled && byProject && liveBlocks.count { !it.isOther } > 1

                val compactWindows = liveBlocks.filter { usesCompactWorkspace(byProject, filter == DrawerFilter.All, it.isOther) }
                    .associate { block ->
                        block.key to compactWorkspaceWindow(
                            block.roots, workspaceExpansion.requested(hostKey, block.key), openPath, childrenByParent,
                            retainedAttention = workspaceExpansion.attention(hostKey, block.key),
                        )
                    }

                /** A partial prefix must never supply hidden neighbours to a reorder operation. */
                fun dragFor(block: SessionBlock, root: SessionRow, onPickUp: () -> Unit = {}): RowDrag? {
                    if (!canReorderWorkspace(reorderEnabled, compactWindows[block.key]) || sortByRecency || block.roots.size < 2) return null
                    val order = block.roots.map { it.sessionId }
                    val at = order.indexOf(root.sessionId)
                    if (at < 0) return null
                    return RowDrag(
                        onPickUp = {
                            onPickUp()
                            if (compactWindows[block.key] != null) {
                                workspaceExpansion.retainForDrag(hostKey, block.key, block.roots.size)
                            }
                            reorder.start(root.sessionId, block.key, order)
                        },
                        canMoveUp = at > 0,
                        canMoveDown = at < order.lastIndex,
                        onStep = { step -> stepUnit(block.key, order, root.sessionId, step) },
                    )
                }

                /** One root and whatever is open under it. Returns the next running row index. */
                fun LazyListScope.rootRows(root: SessionRow, block: SessionBlock?, firstIndex: Int, onPickUp: () -> Unit = {}): Int {
                    var index = firstIndex
                    subtree(root).forEach { (session, depth) ->
                        val rowIndex = index++
                        item(key = rowKey(session.sessionId)) {
                            val held = reorder.draggedId == session.sessionId
                            SessionRowItem(
                                session = session,
                                index = rowIndex,
                                isCurrent = session.sessionId == currentSessionId,
                                store = store,
                                scope = scope,
                                // A held row is placed by the finger, so the list's own slide must not
                                // also move it; everything else glides out of its way.
                                modifier = if (held) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                                reorder = reorder,
                                blockKey = block?.key,
                                drag = if (depth == 0 && block != null) dragFor(block, root, onPickUp) else null,
                                onNotice = { toast.second(it) },
                                depth = depth,
                                pinned = pinnedIds?.let { session.sessionId in it },
                                childCount = childrenByParent[session.sessionId].orEmpty().size,
                                childrenExpanded = isExpanded(session.sessionId),
                                onToggleChildren = { toggleChildren(session.sessionId) },
                                onOpen = { openSession(session.sessionId) },
                            )
                        }
                    }
                    return index
                }

                // Pinned sessions are a shortcut, not a second copy: they stay in their own group, and
                // since their place is the harness's pin list rather than an order of the reader's, they
                // have no handle.
                if (filter == DrawerFilter.All && pinnedRows.isNotEmpty()) {
                    item(key = "pinned-header") {
                        DateHeading(
                            stringResource(R.string.chatlist_pinned),
                            Modifier.padding(top = DsSpacing.xsmall).reorderPresentation(reorder, null, null),
                        )
                    }
                    itemsIndexed(pinnedRows, key = { _, session -> "pinned-${session.sessionId}" }) { index, session ->
                        SessionRowItem(
                            session = session,
                            index = index,
                            isCurrent = session.sessionId == currentSessionId,
                            store = store,
                            scope = scope,
                            reorder = reorder,
                            blockKey = null,
                            onNotice = { toast.second(it) },
                            pinned = true,
                            childCount = childrenByParent[session.sessionId].orEmpty().size,
                            childrenExpanded = isExpanded(session.sessionId),
                            onToggleChildren = { toggleChildren(session.sessionId) },
                            onOpen = { openSession(session.sessionId) },
                        )
                    }
                }

                if (byProject) {
                    // Projects, in the harness's workspace order, each with its own sessions in the
                    // workspace's manual order under a header you can fold. The sessions that belong to
                    // no workspace are the last group.
                    var rowIndex = 0
                    liveBlocks.forEach { block ->
                        val rows = block.roots.filter(::passes)
                        if (rows.isEmpty()) return@forEach
                        val title = block.workspace?.let { it.title.ifBlank { basename(it.path) } } ?: otherName
                        val collapsed = isProjectCollapsed(block.key)
                        val heldProject = reorder.groupKey == PROJECTS_GROUP && reorder.draggedId == block.key
                        item(key = projectKey(block.key)) {
                            val workspaceBlocks = liveBlocks.filter { !it.isOther }
                            val order = workspaceBlocks.map { it.key }
                            val at = order.indexOf(block.key)
                            ProjectHeader(
                                title = title,
                                count = rows.size,
                                collapsed = collapsed,
                                onToggle = {
                                    val next = !collapsed
                                    collapsedNow[block.key] = next
                                    hostKey?.let { key ->
                                        scope.launch { hostsStore.setProjectCollapsed(key, block.key, next) }
                                    }
                                },
                                reorder = reorder,
                                blockKey = block.key,
                                modifier = if (heldProject) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                                drag = if (projectsDraggable && !block.isOther && at >= 0) {
                                    RowDrag(
                                        onPickUp = { reorder.start(block.key, PROJECTS_GROUP, order) },
                                        canMoveUp = at > 0,
                                        canMoveDown = at < order.lastIndex,
                                        onStep = { step -> stepUnit(PROJECTS_GROUP, order, block.key, step) },
                                    )
                                } else {
                                    null
                                },
                            )
                        }
                        if (!collapsed && !heldProject) {
                            val window = compactWindows[block.key]
                            if (window == null) {
                                rows.forEach { root -> rowIndex = rootRows(root, block, rowIndex) }
                            } else {
                                workspaceSessionItems(
                                    key = block.key,
                                    window = window,
                                    onMore = { workspaceExpansion.showMore(hostKey, block.key, window.rows.size) },
                                    onCollapse = { workspaceExpansion.collapse(hostKey, block.key) },
                                    controlsEnabled = !reorder.active,
                                ) { root -> rowIndex = rootRows(root, block, rowIndex) }
                            }
                        }
                    }
                } else {
                    // Date groups, in the order the reader chose. A heading only opens a new group when
                    // the day actually changes, so manual order never repeats "Today" down the list.
                    // While a row is held the headings stay where they were: its day is not its
                    // neighbours', and headings that jumped about under the finger would make the
                    // drag impossible to follow. They settle once it lands.
                    val frozen = frozenDays
                    var lastDay: LocalDate? = null
                    var rowIndex = 0
                    var dayGroups = 0
                    visibleRoots.forEachIndexed { position, root ->
                        val day = frozen?.getOrNull(position) ?: dayOf(root.updatedAt)
                        if (day != lastDay) {
                            lastDay = day
                            // Keyed by the group's own index, not the date: manual order can return to a
                            // day it has already opened, and duplicating a LazyColumn key throws.
                            val group = dayGroups++
                            item(key = "day-$group") {
                                DateHeading(
                                    dayLabel(day),
                                    Modifier
                                        .padding(top = DsSpacing.small)
                                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                                        .reorderPresentation(reorder, null, null),
                                )
                            }
                        }
                        val block = if (sortByRecency) null else blockOf[root.sessionId]
                        rowIndex = rootRows(root, block, rowIndex) {
                            frozenDays = visibleRoots.map { dayOf(it.updatedAt) }
                        }
                    }
                }

                if (visibleRoots.isEmpty() && (filter != DrawerFilter.All || pinnedRows.isEmpty())) {
                    item(key = "empty") {
                        Text(
                            stringResource(if (filter == DrawerFilter.All) R.string.chatlist_empty else R.string.chatlist_search_empty),
                            style = DsType.std14,
                            color = colors.labelTertiary,
                            modifier = Modifier.padding(vertical = DsSpacing.large),
                        )
                    }
                }
            }

            // The pull indicator paints over the list's top edge; the toast still wins the top
            // centre, which is why a failed read waits for the pill to leave before it speaks.
            PullToRefreshIndicator(
                pull = pull,
                phase = refreshPhase,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            // A drop the harness refused, or an approval it did not take: said here, where the
            // person is looking, rather than on a chat banner behind the drawer.
            DsToastHost(toast, modifier = Modifier.align(Alignment.TopCenter), belowStatusBar = false)
        }

        // ---- Start a task ---------------------------------------------------------------------
        StartTaskBar(
            hostName = hostName,
            onClick = { hapticsTap(); newSessionOpen = true },
        )
        Spacer(Modifier.height(DsSpacing.small))
    }

    if (newSessionOpen) {
        NewSessionDialog(
            workspaces = workspaces,
            homeCwd = hostInfo?.home,
            onPick = { workspaceId ->
                newSessionOpen = false
                scope.launch {
                    store.createSession(workspaceId = workspaceId)
                    onClose()
                }
            },
            onDismiss = { newSessionOpen = false },
        )
    }

    if (newWorkspaceOpen) {
        NewWorkspaceDialog(
            onDismiss = { newWorkspaceOpen = false },
            onCreate = { path ->
                scope.launch {
                    store.createWorkspace(path)
                    newWorkspaceOpen = false
                }
            },
        )
    }

    if (workspacesOpen) {
        WorkspacesDialog(
            workspaces = workspaces,
            store = store,
            scope = scope,
            onDismiss = { workspacesOpen = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Header controls
// ---------------------------------------------------------------------------

/**
 * The connected harness, as the mock draws it: a status dot, the host's name, a caret.
 *
 * It opens Settings rather than a connection picker because the drawer is hosted inside the chat
 * route and has no route of its own to hand — tapping the harness should at least land where its
 * address and reconnect controls live. The caret says it goes somewhere.
 */
@Composable
private fun HarnessPill(name: String, phase: ConnectionPhase, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val dot = when (phase) {
        ConnectionPhase.CONNECTED -> StateDotState.Done
        ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING -> StateDotState.Warning
        ConnectionPhase.DISCONNECTED -> StateDotState.Idle
    }
    val label = if (phase == ConnectionPhase.DISCONNECTED) stringResource(R.string.common_offline) else name
    // Drawn 36dp (the same as a filter chip, so the two read as one family), inside a 48dp target.
    Row(
        modifier = Modifier
            .widthIn(max = 168.dp)
            .minimumInteractiveComponentSize()
            .height(DsSizes.chip)
            .clip(DsShapes.pillFull)
            .background(colors.bgLayer2)
            .border(1.dp, colors.borderL2, DsShapes.pillFull)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StateDot(dot, size = 7.dp)
        Text(
            label,
            style = DsType.small13Strong,
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * A row of chips that scrolls sideways and fades into [fadeColor] at its trailing edge, so the last
 * chip never looks chopped with no way to reach it. The fade runs toward the reading end, so it works
 * on both LTR and RTL.
 *
 * The fade is painted over the row, not laid out beside it. It used to be a second child of the [Box]
 * with `fillMaxHeight()`, and inside a [Box] a `fillMaxHeight()` child counts toward the box's own
 * size; a [Column] offers its unweighted children all the height that is left, so the "scroll
 * affordance" grew to the whole drawer. The session list and the start bar below it were then
 * measured at zero height and the drawer looked empty (issue #70). A draw-time overlay has no size
 * of its own to add.
 */
@Composable
internal fun FadingChipRow(
    fadeColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current ==
        androidx.compose.ui.unit.LayoutDirection.Rtl
    val fadeWidth = with(LocalDensity.current) { 32.dp.toPx() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val start = if (rtl) 0f else size.width - fadeWidth
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = if (rtl) listOf(fadeColor, Color.Transparent) else listOf(Color.Transparent, fadeColor),
                        startX = start,
                        endX = start + fadeWidth,
                    ),
                    topLeft = Offset(start, 0f),
                    size = Size(fadeWidth, size.height),
                )
            }
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The session-order control: an icon that opens the three orders, the current one checked.
 *
 * A [DsIconMenu], so it looks and presses exactly like the search button beside it.
 */
@Composable
private fun SortButton(sort: String, onPick: (sort: String) -> Unit) {
    DsIconMenu(
        icon = Icons.Outlined.SwapVert,
        contentDescription = stringResource(R.string.chatlist_sort_title),
        items = listOf(
            MenuItem(text = stringResource(R.string.chatlist_sort_manual), selected = sort == SORT_MANUAL) { onPick(SORT_MANUAL) },
            MenuItem(text = stringResource(R.string.chatlist_sort_updated), selected = sort == SORT_UPDATED) { onPick(SORT_UPDATED) },
            MenuItem(text = stringResource(R.string.chatlist_sort_project), selected = sort == SORT_PROJECT) { onPick(SORT_PROJECT) },
        ),
    )
}

/** New session, new workspace, workspace management, automations and a manual refresh. */
@Composable
private fun MoreMenu(
    automationAvailable: Boolean,
    onNewSession: () -> Unit,
    onNewWorkspace: () -> Unit,
    onWorkspaces: () -> Unit,
    onAutomations: () -> Unit,
    onRefresh: () -> Unit,
) {
    DsIconMenu(
        icon = Icons.Outlined.MoreVert,
        contentDescription = stringResource(R.string.ink_chat_more),
        items = buildList {
            add(MenuItem(stringResource(R.string.chatlist_new_session), icon = Icons.Filled.Add, onClick = onNewSession))
            add(MenuItem(stringResource(R.string.chatlist_new_workspace), icon = Icons.Outlined.Add, onClick = onNewWorkspace))
            add(MenuItem(stringResource(R.string.ink_chat_workspaces), icon = FeatherIcons.Folder, onClick = onWorkspaces))
            if (automationAvailable) {
                add(MenuItem(stringResource(R.string.harness_automations), icon = FeatherIcons.Zap, onClick = onAutomations))
            }
            // The pull is the fast path; this is the same read for TalkBack and for anyone who
            // would rather not drag. It runs the same cycle, so it animates the same pill.
            add(MenuItem(stringResource(R.string.chatlist_refresh), icon = Icons.Outlined.Refresh, onClick = onRefresh))
        },
    )
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

/** How many lines of a command the needs-you card shows before it stops offering a one-tap approval. */
private const val COMMAND_PREVIEW_LINES = 4

/** The lazy-list key of a session row. Also what [ReorderState] looks a lifted session up by. */
private fun rowKey(sessionId: String) = "row-$sessionId"

/** The lazy-list key of a project header. */
private fun projectKey(blockKey: String) = "project-$blockKey"

/** What a row needs to be moved: how to pick it up, and the two TalkBack steps. Null on a row that cannot move. */
private class RowDrag(
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
private fun DragHandle(state: ReorderState, onPickUp: () -> Unit, modifier: Modifier = Modifier) {
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
 * The one line of explanation shown while something is held: where it is allowed to go.
 *
 * It turns amber-bordered while the finger presses past the edge of that place, which is the moment
 * the limit is being met.
 */
@Composable
private fun ReorderHint(visible: Boolean, text: String, pressing: Boolean, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(160)),
    ) {
        Surface(
            shape = DsShapes.pillFull,
            color = colors.bgLayer3,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (pressing) colors.warnBorder else colors.borderL3),
            shadowElevation = 4.dp,
        ) {
            Text(
                text,
                style = DsType.small13Strong,
                color = if (pressing) colors.warnLabel else colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small).widthIn(max = 240.dp),
            )
        }
    }
}

/**
 * A project header in the "By project" list: a fold chevron, the project's name, how many sessions
 * it shows, and (when projects can be reordered) its own grip.
 */
@Composable
private fun ProjectHeader(
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
private fun DateHeading(text: String, modifier: Modifier = Modifier) {
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

/**
 * The one session that needs a person: mascot, amber label, its title, what the harness is asking
 * to run, and the two decisions.
 *
 * The card is the mock's pinned approval, not a second copy of the row: approving from here is the
 * whole reason it is pinned, so it carries the command and the buttons rather than a link to a row
 * further down.
 */
@Composable
private fun NeedsYouCard(
    session: SessionRow,
    approvalTool: String?,
    approvalReason: String?,
    onReview: () -> Unit,
    onApprove: (() -> Unit)?,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.warnSecondary),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MascotMark(state = MascotState.NeedsYou, size = 26.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.chatlist_needs_action).uppercase(),
                    style = DsType.label12,
                    color = colors.warnLabel,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    relativeTime(session.updatedAt),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                    maxLines = 1,
                )
            }
            Text(
                displaySessionTitle(session),
                style = DsType.rowTitle,
                color = colors.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val command = approvalReason?.takeIf { it.isNotBlank() } ?: approvalTool
            // A one-tap approval is only honest when the whole command is on screen. A long one (a
            // `rm -rf build && curl ... | sh` that shows only its first words) is shown over several
            // lines, and when even that cuts it off the card refuses to approve in one tap: it sends
            // the person to Review, where the full text is.
            var cutOff by remember(command) {
                // A first guess so an obviously long command never flashes a one-tap button; the
                // measured text corrects it either way a moment later.
                mutableStateOf(command != null && (command.length > 160 || command.count { it == '\n' } >= COMMAND_PREVIEW_LINES))
            }
            if (command != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = DsShapes.code,
                    color = colors.bgLayer2,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderL1),
                ) {
                    Text(
                        "$ " + command,
                        style = DsType.mono13,
                        color = colors.labelSecondary,
                        maxLines = COMMAND_PREVIEW_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { cutOff = it.hasVisualOverflow },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                if (cutOff && onApprove != null) {
                    Text(
                        stringResource(R.string.chatlist_approve_long),
                        style = DsType.caption11,
                        color = colors.warnLabel,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsButton(
                    text = stringResource(R.string.ink_chat_review),
                    onClick = onReview,
                    variant = if (cutOff) DsButtonVariant.Primary else DsButtonVariant.Outline,
                    modifier = Modifier.weight(1f),
                )
                if (onApprove != null && !cutOff) {
                    DsButton(
                        text = stringResource(R.string.ink_chat_approve),
                        onClick = onApprove,
                        variant = DsButtonVariant.Primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
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
private fun SessionRowItem(
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
                        add(HoldAction(stringResource(R.string.chatlist_session_archive), Icons.Outlined.Archive) { archiveConfirmOpen = true })
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
private fun SessionAvatar(tint: Color, size: androidx.compose.ui.unit.Dp) {
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
private fun Modifier.arrival(index: Int): Modifier {
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

// ---------------------------------------------------------------------------
// Search, start bar and dialogs
// ---------------------------------------------------------------------------

/**
 * One search result: the session's own name first, then where it lives, then the matching excerpt.
 * A result should name the thing you are about to open.
 */
@Composable
private fun SearchResultRow(hit: SearchHit, index: Int, onOpen: () -> Unit) {
    val colors = DsTheme.colors
    val avatarTint = when {
        hit.session.pendingInteraction != null -> colors.warn
        hit.session.running -> colors.accent
        else -> colors.labelDimmed
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .arrival(index)
            .clip(DsShapes.row)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = DsSpacing.small, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SessionAvatar(avatarTint, 28.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displaySessionTitle(hit.session),
                    style = DsType.rowTitle,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hit.session.origin == "subagent") {
                    Spacer(Modifier.width(DsSpacing.xsmall))
                    DsPill(text = stringResource(R.string.chatlist_subagents))
                }
            }
            hit.workspaceLabel.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = DsType.caption11,
                    color = colors.labelCaption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            hit.snippet?.let {
                Text(
                    text = it,
                    style = DsType.caption11,
                    color = colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            relativeTime(hit.session.updatedAt),
            style = DsType.caption11,
            color = colors.labelTertiary,
            maxLines = 1,
        )
    }
}

/** The bottom bar: a task starts from here, on this harness. */
@Composable
private fun StartTaskBar(hostName: String, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.pillFull,
        color = colors.bgLayer2,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderL2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = colors.labelSecondary, modifier = Modifier.size(22.dp))
            }
            Text(
                stringResource(R.string.ink_chat_start_task, hostName),
                style = DsType.std14,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = stringResource(R.string.chat_composer_send),
                    tint = colors.onAccent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun NewSessionDialog(
    workspaces: List<WorkspaceRow>,
    homeCwd: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
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
    }
}

@Composable
private fun NewWorkspaceDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
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
private fun WorkspacesDialog(
    workspaces: List<WorkspaceRow>,
    store: SessionStore,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    var renaming by remember { mutableStateOf<WorkspaceRow?>(null) }
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

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun dayOf(epochMs: Long): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> stringResource(R.string.ink_chat_today)
        today.minusDays(1) -> stringResource(R.string.ink_chat_yesterday)
        else -> dayFormatter.format(day)
    }
}

/**
 * Group subagent sessions under the visible session that spawned them.
 *
 * Keyed by immediate parent, so a subagent that spawned its own subagents nests as deeply as the
 * run actually went. Free function so the rules that are easy to get wrong — forks staying at the
 * top level, orphans re-attaching, cycles not hanging — can be tested without a device.
 */
internal fun indexSubagents(
    listable: List<SessionRow>,
    sessionsById: Map<String, SessionRow>,
): Map<String, List<SessionRow>> {
    val listableIds = listable.mapTo(HashSet()) { it.sessionId }

    /**
     * The nearest ancestor that is actually on screen.
     *
     * Usually the immediate parent. The walk exists for the case that used to lose rows entirely:
     * archiving a session, or the harness reusing a blank one, removes it from the list while its
     * subagents remain — those attach to the next ancestor up rather than vanishing with it. The
     * visited set guards against a lineage cycle, which would otherwise hang the drawer.
     */
    fun attachPoint(child: SessionRow): String? {
        val visited = hashSetOf(child.sessionId)
        var cursor = child.parentSessionId
        while (cursor != null && visited.add(cursor)) {
            if (cursor in listableIds) return cursor
            cursor = sessionsById[cursor]?.parentSessionId
        }
        return null
    }

    return listable
        .filter { it.origin == "subagent" }
        .mapNotNull { child -> attachPoint(child)?.let { it to child } }
        .groupBy({ it.first }, { it.second })
}

/** Display title: an explicit title, else the working directory's folder, else the id. */
internal fun displaySessionTitle(session: SessionRow): String =
    sessionTitle(session).let { if (session.title.isNullOrBlank()) technicalDisplay(it) else it }

/**
 * The slice of a workspace's sessions to draw while it is in preview mode.
 *
 * Kept for the unit tests that pin its ordering rules; the day-grouped drawer draws the whole list.
 * Order is preserved exactly as passed in — this function sorts nothing.
 */
internal fun visibleWorkspaceRoots(
    roots: List<SessionRow>,
    limit: Int,
    openPath: Set<String>,
): List<SessionRow> {
    if (limit <= 0 || roots.size <= limit) return roots
    val keep = roots.indices.take(limit).toMutableSet()
    roots.forEachIndexed { index, row -> if (row.sessionId in openPath) keep += index }
    return roots.filterIndexed { index, _ -> index in keep }
}

internal fun sessionTitle(session: SessionRow): String {
    val title = session.title?.takeIf { it.isNotBlank() }
    val folder = session.cwd?.takeIf { it.isNotBlank() }?.let { basename(it) }?.takeIf { it.isNotBlank() }
    return title ?: folder ?: session.sessionId
}
