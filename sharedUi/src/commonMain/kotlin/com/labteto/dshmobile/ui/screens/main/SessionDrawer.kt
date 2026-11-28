package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.connection.ConnectionPhase
import com.labteto.dshmobile.data.MoveOutcome
import com.labteto.dshmobile.data.QuestionOutcome
import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.data.anchorAfterMove
import com.labteto.dshmobile.data.failureNotice
import com.labteto.dshmobile.data.moveByStep
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.chatlist_archived
import com.labteto.dshmobile.shared.resources.chatlist_archived_none
import com.labteto.dshmobile.shared.resources.chatlist_empty
import com.labteto.dshmobile.shared.resources.chatlist_group_other
import com.labteto.dshmobile.shared.resources.chatlist_pinned
import com.labteto.dshmobile.shared.resources.chatlist_refresh
import com.labteto.dshmobile.shared.resources.chatlist_refresh_failed
import com.labteto.dshmobile.shared.resources.chatlist_reorder_blocked
import com.labteto.dshmobile.shared.resources.chatlist_reorder_failed
import com.labteto.dshmobile.shared.resources.chatlist_reorder_project_failed
import com.labteto.dshmobile.shared.resources.chatlist_reorder_projects
import com.labteto.dshmobile.shared.resources.chatlist_reorder_scope
import com.labteto.dshmobile.shared.resources.chatlist_search_empty
import com.labteto.dshmobile.shared.resources.chatlist_search_refine
import com.labteto.dshmobile.shared.resources.common_search
import com.labteto.dshmobile.shared.resources.ink_chat_sessions
import com.labteto.dshmobile.shared.resources.questions_answer_refused
import com.labteto.dshmobile.shared.resources.questions_answer_unsent
import com.labteto.dshmobile.shared.resources.settings_title
import com.labteto.dshmobile.ui.LocalSessionStore
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.PlatformBackHandler
import com.labteto.dshmobile.ui.components.PullToRefreshIndicator
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.components.rememberPullToRefresh
import com.labteto.dshmobile.ui.input.AppShortcut
import com.labteto.dshmobile.ui.input.LocalShortcutRouter
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Base nonblank prefix and Show more step for a compact workspace in the project view.
 */
internal const val WORKSPACE_PREVIEW_LIMIT = 5

/** The quick filters above the list. */
internal enum class DrawerFilter { All, Running, NeedsYou, Archived }

// ---------------------------------------------------------------------------
// What the drawer needs from the platform it runs on
// ---------------------------------------------------------------------------

/**
 * The drawer's own preferences, kept on the phone per harness: the order of the list, the order of the
 * sessions that belong to no workspace, and which project headers are folded shut.
 *
 * Android keeps them in `HostsStore` (DataStore). Each flow gives what is saved, then every change.
 */
interface SessionDrawerPrefs {
    /** The saved order as written, normalized by the reader with [normalizeSessionSort]. */
    val sessionSort: Flow<String>
    suspend fun setSessionSort(value: String)

    /** The sessions in no workspace, in the order arranged on this phone, for the harness [hostKey]. */
    fun ungroupedOrder(hostKey: String?): Flow<List<String>>
    suspend fun setUngroupedOrder(hostKey: String, ids: List<String>)

    /** The project headers folded shut on this phone, for the harness [hostKey]. */
    fun collapsedProjects(hostKey: String?): Flow<Set<String>>
    suspend fun setProjectCollapsed(hostKey: String, projectKey: String, collapsed: Boolean)
}

/**
 * The local calendar, for the list's date headings.
 *
 * A day is an opaque number: two instants on the same local day give the same one, and [label] turns it
 * back into words. Android answers with `java.time` in the device's zone and the app's locale.
 */
interface DrawerCalendar {
    /** The local day [epochMs] falls on. */
    fun dayOf(epochMs: Long): Long

    /** "Today", "Yesterday", or the date in the locale's medium style. */
    @Composable
    fun label(day: Long): String
}

/**
 * Which of the drawer's dialogs are open. The host holds it, because its own verbs (a project being
 * started from the new-session dialog) close a dialog too.
 */
@Stable
class SessionDrawerDialogs {
    var newSession by mutableStateOf(false)
    var newWorkspace by mutableStateOf(false)
    var workspaces by mutableStateOf(false)
}

/**
 * The built-in harness's project verbs: Android only, and only while that harness is the one connected.
 * The drawer shows none of them when the host passes null.
 */
class DrawerProjects(
    /** The overflow menu's Projects entry. */
    val onOpen: () -> Unit,
    /** Rows at the foot of the new-session dialog: the ways to start a project there. */
    val newSessionRows: @Composable ColumnScope.() -> Unit,
    /**
     * What the workspace list adds to each row, and the dialogs those rows open. Composed inside that
     * dialog, so whatever it remembers lives as long as the list; [onDismiss] closes the list.
     */
    val workspaceExtras: @Composable (onDismiss: () -> Unit) -> WorkspaceListExtras,
)

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
 * Three orders, one preference ([SessionDrawerPrefs.sessionSort]):
 *  - **Manual** follows the harness's own workspace order under date headings, and a session can be
 *    moved to a new place *within its workspace* (the harness cannot move a session between
 *    workspaces): "Move session" in its hold menu lifts it, then a drag places it. Sessions in no
 *    workspace are ordered on the phone.
 *  - **Last updated** is newest first under date headings.
 *  - **By project** groups under collapsible workspace headers, same order inside each, same move;
 *    a project is moved by holding its header and dragging.
 *
 * It reads the store from [LocalSessionStore]. What only a platform can answer comes in as parameters:
 * [prefs] for the phone-side preferences, [calendar] for the date headings, [announceMove] to tell a
 * screen reader where a moved row landed (its 1-based position and the size of its group),
 * [onOpenAutomations] (null hides the entry) and [projects] (null hides the built-in harness's
 * project verbs). [features] says what else the platform has: Settings (the gear and the harness
 * pill open it), or, where there is none yet, a Disconnect entry that calls [onDisconnect]. The
 * Android host is `ChatListDrawer` in `:app`; the iOS one is `IosSessionDrawer`.
 */
@Composable
fun SessionDrawer(
    prefs: SessionDrawerPrefs,
    calendar: DrawerCalendar,
    dialogs: SessionDrawerDialogs,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAutomations: (() -> Unit)?,
    announceMove: (position: Int, size: Int) -> Unit,
    projects: DrawerProjects?,
    features: MainFeatures = MainFeatures.Android,
    onDisconnect: () -> Unit = {},
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val store = LocalSessionStore.current
    val automationAvailable by store.automationAvailable.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

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
    val sessionSort by prefs.sessionSort.collectAsStateWithLifecycle(initialValue = SORT_MANUAL)
    val sort = normalizeSessionSort(sessionSort)
    val sortByRecency = sort == SORT_UPDATED
    val byProject = sort == SORT_PROJECT

    // Per-host, per-phone state the harness has no place for: the order of sessions that belong to no
    // workspace, and which project headers the reader folded shut.
    val hostKey = connection.host?.let { "${it.baseUrl}|${it.id}" }
    val savedUngrouped by remember(hostKey) { prefs.ungroupedOrder(hostKey) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // What the reader just arranged, shown until the saved copy catches up so a drop never flickers.
    var ungroupedOverride by remember(hostKey) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(savedUngrouped) {
        if (ungroupedOverride != null && ungroupedOverride == savedUngrouped) ungroupedOverride = null
    }
    val savedCollapsed by remember(hostKey) { prefs.collapsedProjects(hostKey) }
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
    // Keyboard shortcuts that act on this list: Ctrl+N opens the new-session picker, Ctrl+F opens
    // the search (and takes focus again when it is already open). The shell reveals the list first.
    val shortcutRouter = LocalShortcutRouter.current
    var searchFocusTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(shortcutRouter) {
        shortcutRouter.events.collect { shortcut ->
            when (shortcut) {
                AppShortcut.NewSession -> dialogs.newSession = true
                AppShortcut.SearchSessions -> {
                    searchOpen = true
                    searchFocusTick++
                }
                else -> Unit
            }
        }
    }
    // Subagent disclosure is independent of compact workspace expansion and keyed by host.
    val collapsed = remember(hostKey) { mutableStateMapOf<String, Boolean>() }
    val hapticsTap = { haptics.tap() }

    // ---- Pull to refresh -------------------------------------------------------------------
    // The store keeps this list current by itself: pushes carry most changes and the fallback
    // re-reads on open, on resume and every so often. This is the manual version — a person who
    // wants to be sure, and the only refresh that says so where the gesture happened. The read is
    // quiet: a failure lands as a toast in the drawer, not as a banner on the chat underneath it.
    val refreshLabel = stringResource(Res.string.chatlist_refresh)
    val refreshFailedLabel = stringResource(Res.string.chatlist_refresh_failed)
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
    // A row lifted from its menu has no finger holding it, so it can outlive the conditions that let
    // it move: the filter, the search, the order or the connection changing underneath it puts it back.
    LaunchedEffect(reorderEnabled) { if (!reorderEnabled && reorder.active) reorder.cancel() }
    // Back puts a lifted row down before it does anything else.
    PlatformBackHandler(enabled = reorder.active) { reorder.onGestureEnd() }
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
        // A lifted row is put down where it stands before anything else opens.
        if (reorder.active) reorder.onGestureEnd()
        // Close first. Waiting for the session to load before the drawer moved made every switch
        // feel like a stall; the transcript shows its own skeleton.
        hapticsTap()
        onClose()
        scope.launch { store.openSession(sessionId) }
    }

    // ---- Reordering: what a drop, or a "Move up" from TalkBack, actually does ---------------------
    val moveFailedText = stringResource(Res.string.chatlist_reorder_failed)
    val projectMoveFailedText = stringResource(Res.string.chatlist_reorder_project_failed)
    val staysInProjectText = stringResource(Res.string.chatlist_reorder_blocked)
    val otherName = stringResource(Res.string.chatlist_group_other)
    val answerRefused = stringResource(Res.string.questions_answer_refused)
    val answerUnsent = stringResource(Res.string.questions_answer_unsent)

    /** What to tell the person about an approval answer, or null when the harness took it. */
    fun refusalOf(outcome: QuestionOutcome): String? = when (outcome) {
        is QuestionOutcome.Accepted -> null
        // The template's one `%1$s`, filled as `String.format` filled it (the reason is known only later).
        is QuestionOutcome.Refused -> answerRefused.replace("%1\$s", outcome.reason)
        is QuestionOutcome.Unsent -> answerUnsent
    }
    // The list's own keys, so the held unit can be found and measured: a session row, or a project
    // header. Each unit's keys also include what hangs under it, so a neighbour's height is its own.
    val blockOf = remember(liveBlocks) {
        liveBlocks.flatMap { block -> block.roots.map { it.sessionId to block } }.toMap()
    }
    // Date headings as they stood when a row was lifted; see the date groups below.
    var frozenDays by remember { mutableStateOf<List<Long>?>(null) }
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
                                runCatching { prefs.setUngroupedOrder(key, drop.order) }.isSuccess
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
        announceMove(next.indexOf(id) + 1, next.size)
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
                onClick = if (features.settings) ({ hapticsTap(); onOpenSettings() }) else null,
            )
            if (features.settings) {
                DsIconButton(
                    icon = DsIcons.SettingsOutlined,
                    contentDescription = stringResource(Res.string.settings_title),
                    onClick = onOpenSettings,
                    tint = colors.labelTertiary,
                    iconSize = 20.dp,
                )
            }
        }

        // ---- Hero title, workspace · model, quick actions --------------------------------------
        // The title is never cut: in a narrow pane it steps down a size, then the order moves into the
        // More menu, then the actions move under the title (DrawerTitleRow).
        DrawerTitleRow(
            title = stringResource(Res.string.ink_chat_sessions),
            subtitle = listOfNotNull(
                workspacePath?.takeIf { it.isNotBlank() }?.let { technicalDisplay(it) },
                modelLabel,
            ).joinToString(" · "),
            actionCount = 3,
            foldedActionCount = 2,
        ) { sortFolded ->
            val pickSort: (String) -> Unit = { next -> scope.launch { prefs.setSessionSort(next) } }
            // Search, sort and more are one family: bare 48dp icon buttons with a circular press, all
            // outline glyphs. (Sort and more used to be a bare clickable box, so their press was a
            // rectangle next to search's circle.)
            DsIconButton(
                icon = FeatherIcons.Search,
                contentDescription = stringResource(Res.string.common_search),
                onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) query = ""
                },
                tint = colors.labelTertiary,
                iconSize = 20.dp,
                selected = searchOpen,
            )
            if (!sortFolded) SortButton(sort, pickSort)
            MoreMenu(
                automationAvailable = automationAvailable != false && onOpenAutomations != null,
                onNewSession = { dialogs.newSession = true },
                onNewWorkspace = { dialogs.newWorkspace = true },
                onWorkspaces = { dialogs.workspaces = true },
                onProjects = projects?.onOpen,
                onAutomations = onOpenAutomations ?: {},
                onRefresh = refresh::start,
                onDisconnect = if (features.disconnect) onDisconnect else null,
                // With no room for the Sort button its three orders are in this menu instead.
                sort = if (sortFolded) sort else null,
                onSort = pickSort,
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
        val projectsHint = stringResource(Res.string.chatlist_reorder_projects)
        val hintNow = when (val group = reorder.groupKey) {
            null -> ""
            PROJECTS_GROUP -> projectsHint
            else -> stringResource(
                Res.string.chatlist_reorder_scope,
                blocks.firstOrNull { it.key == group }?.workspace?.let { it.title.ifBlank { basename(it.path) } }
                    ?: otherName,
            )
        }
        DrawerFilterRow(
            filter = filter,
            onFilter = { filter = it },
            allCount = listable.size,
            runningCount = runningCount,
            needsYouCount = needsYouCount,
            holding = reorder.active,
            hint = hintNow,
            pressing = reorder.atBoundary,
            // A row lifted from its menu waits for a drag; this is how it is put down without one.
            onDone = reorder::onGestureEnd,
        )

        // ---- The one session that needs a person ----------------------------------------------
        AnimatedVisibility(visible = needsYouSession != null && query.isBlank()) {
            val session = needsYouSession
            if (session != null) {
                Column(Modifier.padding(top = DsSpacing.small)) {
                    NeedsYouCard(
                        session = session,
                        approvalTool = approval?.toolName,
                        // The command alone, not "Run this command?" in front of it.
                        approvalReason = approval?.let {
                            val prompt = com.labteto.dshmobile.core.permission.approvalPromptOf(it.toolName, it.rawReason, it.reason)
                            prompt.detail ?: prompt.text
                        },
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

        DrawerSearch(
            open = searchOpen,
            query = query,
            onQueryChange = { query = it },
            focusTick = searchFocusTick,
            contentSearchAvailable = contentSearchAvailable,
        )

        Spacer(Modifier.height(DsSpacing.small))

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                // A lifted row is placed by a drag on it; a finger on the list would scroll it out from
                // under the row. The list still scrolls by itself at its edges, to reach farther rows.
                userScrollEnabled = !reorder.active,
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
                            stringResource(Res.string.common_search).uppercase(),
                            style = DsType.label12,
                            color = colors.labelTertiary,
                            modifier = Modifier.padding(vertical = DsSpacing.xsmall),
                        )
                    }
                    if (searchHits.items.isEmpty()) {
                        item(key = "search-empty") {
                            Text(
                                stringResource(Res.string.chatlist_search_empty),
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
                                stringResource(Res.string.chatlist_search_refine),
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
                        DateHeading(stringResource(Res.string.chatlist_archived), Modifier.padding(top = DsSpacing.xsmall))
                    }
                    if (archivedSessions.isEmpty()) {
                        item(key = "archived-empty") {
                            // Nothing archived is its own state, not "nothing matches": there is no
                            // query on this list for anything to have failed to match.
                            Text(
                                stringResource(Res.string.chatlist_archived_none),
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
                // cannot be moved (no "Move session" in their menu, no Move up or Move down).
                if (filter == DrawerFilter.All && pinnedRows.isNotEmpty()) {
                    item(key = "pinned-header") {
                        DateHeading(
                            stringResource(Res.string.chatlist_pinned),
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
                    workspaceGroups(
                        blocks = liveBlocks,
                        passes = ::passes,
                        otherName = otherName,
                        isCollapsed = ::isProjectCollapsed,
                        onCollapse = { blockKey, next ->
                            collapsedNow[blockKey] = next
                            hostKey?.let { key ->
                                scope.launch { prefs.setProjectCollapsed(key, blockKey, next) }
                            }
                        },
                        reorder = reorder,
                        projectsDraggable = projectsDraggable,
                        onStep = ::stepUnit,
                        compactWindows = compactWindows,
                        onShowMore = { blockKey, shown -> workspaceExpansion.showMore(hostKey, blockKey, shown) },
                        onShowLess = { blockKey -> workspaceExpansion.collapse(hostKey, blockKey) },
                    ) { root, block -> rowIndex = rootRows(root, block, rowIndex) }
                } else {
                    // Date groups, in the order the reader chose. A heading only opens a new group when
                    // the day actually changes, so manual order never repeats "Today" down the list.
                    // While a row is held the headings stay where they were: its day is not its
                    // neighbours', and headings that jumped about under the finger would make the
                    // drag impossible to follow. They settle once it lands.
                    val frozen = frozenDays
                    var lastDay: Long? = null
                    var rowIndex = 0
                    var dayGroups = 0
                    visibleRoots.forEachIndexed { position, root ->
                        val day = frozen?.getOrNull(position) ?: calendar.dayOf(root.updatedAt)
                        if (day != lastDay) {
                            lastDay = day
                            // Keyed by the group's own index, not the date: manual order can return to a
                            // day it has already opened, and duplicating a LazyColumn key throws.
                            val group = dayGroups++
                            item(key = "day-$group") {
                                DateHeading(
                                    calendar.label(day),
                                    Modifier
                                        .padding(top = DsSpacing.small)
                                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                                        .reorderPresentation(reorder, null, null),
                                )
                            }
                        }
                        val block = if (sortByRecency) null else blockOf[root.sessionId]
                        rowIndex = rootRows(root, block, rowIndex) {
                            frozenDays = visibleRoots.map { calendar.dayOf(it.updatedAt) }
                        }
                    }
                }

                if (visibleRoots.isEmpty() && (filter != DrawerFilter.All || pinnedRows.isEmpty())) {
                    item(key = "empty") {
                        Text(
                            stringResource(if (filter == DrawerFilter.All) Res.string.chatlist_empty else Res.string.chatlist_search_empty),
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
            onClick = { hapticsTap(); dialogs.newSession = true },
        )
        Spacer(Modifier.height(DsSpacing.small))
    }

    if (dialogs.newSession) {
        NewSessionDialog(
            workspaces = workspaces,
            homeCwd = hostInfo?.home,
            extraRows = projects?.newSessionRows,
            onPick = { workspaceId ->
                dialogs.newSession = false
                scope.launch {
                    store.createSession(workspaceId = workspaceId)
                    onClose()
                }
            },
            onDismiss = { dialogs.newSession = false },
        )
    }

    if (dialogs.newWorkspace) {
        NewWorkspaceDialog(
            onDismiss = { dialogs.newWorkspace = false },
            onCreate = { path ->
                scope.launch {
                    store.createWorkspace(path)
                    dialogs.newWorkspace = false
                }
            },
        )
    }

    if (dialogs.workspaces) {
        WorkspacesDialog(
            workspaces = workspaces,
            store = store,
            scope = scope,
            extras = projects?.workspaceExtras,
            onDismiss = { dialogs.workspaces = false },
        )
    }
}

/** The lazy-list key of a session row. Also what [ReorderState] looks a lifted session up by. */
internal fun rowKey(sessionId: String) = "row-$sessionId"

/** The lazy-list key of a project header. */
internal fun projectKey(blockKey: String) = "project-$blockKey"

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
fun displaySessionTitle(session: SessionRow): String =
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

fun sessionTitle(session: SessionRow): String {
    val title = session.title?.takeIf { it.isNotBlank() }
    val folder = session.cwd?.takeIf { it.isNotBlank() }?.let { basename(it) }?.takeIf { it.isNotBlank() }
    return title ?: folder ?: session.sessionId
}
