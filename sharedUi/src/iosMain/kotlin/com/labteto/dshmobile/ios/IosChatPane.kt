package com.labteto.dshmobile.ios

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.connection.DEFAULT_WORK_STEP_DISPLAY
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.effectiveWorkingDirectory
import com.labteto.dshmobile.ui.LocalSessionStore
import com.labteto.dshmobile.ui.adaptive.ListControl
import com.labteto.dshmobile.ui.screens.main.AssistantMemory
import com.labteto.dshmobile.ui.screens.main.ChatNodeContext
import com.labteto.dshmobile.ui.screens.main.ChatTab
import com.labteto.dshmobile.ui.screens.main.ChatTopBar
import com.labteto.dshmobile.ui.screens.main.Composer
import com.labteto.dshmobile.ui.screens.main.ComposerKey
import com.labteto.dshmobile.ui.screens.main.ChatTranscript
import com.labteto.dshmobile.ui.screens.main.RowDisclosureMemory
import com.labteto.dshmobile.ui.screens.main.TranscriptMemory
import com.labteto.dshmobile.ui.screens.main.WorkStepPresentation
import com.labteto.dshmobile.ui.screens.main.basename
import com.labteto.dshmobile.ui.screens.main.transcriptRows
import com.labteto.dshmobile.ui.screens.main.workStepPolicyFor
import com.labteto.dshmobile.ui.theme.DsTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where `-DshChatScroll` leaves the transcript once a session is open: at its newest message, or paged back once. */
internal enum class ChatScroll { Tail, Older }

/**
 * The chat pane on iOS (#141): the open session's transcript under the shared top bar, with the shared composer below
 * it. The transcript is the shared [ChatTranscript] (M5c), the bar the Android [ChatTopBar] and the composer the Android
 * [Composer] (M6a), all over the shared store. What iOS does not have yet is left out rather than drawn dead: in the bar
 * the model picker (the chip states the model), the preset, subagent and team chips, history, details and the tabs; in
 * the composer the `+` sheet, the `@` picker, attachments and dictation. Sending is M6b: until then the send button is
 * drawn disabled (no `onSend`), while typing, the permission chip and Stop (a running turn, `store.cancelTurn`) work. A
 * reply's Branch and feedback buttons and a subagent card's tap are left out too (null in [ChatNodeContext]). The draft is
 * the store's per-session one, as on Android, so it survives a session switch.
 *
 * The composer stays in the column's flow, capped at 60% of the pane as Android caps it in flow. Android's lifted
 * drawer (two lines and more float over the transcript) comes with the chat screen's shared content in M6b.
 *
 * With no session open the pane is [empty] (the Connected page).
 *
 * [scroll] and [onReport] are the debug launch arguments `-DshOpenSession` and `-DshChatScroll`: once the session's
 * history is in and its transcript laid out the pane writes `DSH_CHAT_RESULT opened rows=… session=… more=…` (`more`
 * is the store's `hasMore` for that history, which is what puts a Load older row at the top). With [ChatScroll.Older] it
 * then drags the transcript up to the oldest loaded message as a reader's finger does ([dragToOldestLoaded]); the
 * transcript's own trigger asks for the page before it, and once that has landed the pane drags on to the top of what is
 * loaded and writes `DSH_CHAT_RESULT older rows=… session=… more=… failed=… asked=…`, `asked` being how many pages the
 * transcript asked for. Once the composer has been laid out it writes `DSH_CHAT_RESULT composer chars=… lines=… send=…`:
 * the draft's length, the lines the editor paints and whether send can be pressed. [initialDraft] (`-DshComposerDraft`)
 * is put in the first opened session's composer as if typed.
 */
@Composable
internal fun IosChatPane(
    onOpenSessions: (() -> Unit)?,
    scroll: ChatScroll?,
    onReport: ((String) -> Unit)?,
    initialDraft: String? = null,
    empty: @Composable () -> Unit,
) {
    val store = LocalSessionStore.current
    val sessionId by store.currentSessionId.collectAsState()
    val id = sessionId
    if (id == null) {
        empty()
        return
    }
    val conversation by store.currentConversation.collectAsState()
    val loadingOlder by store.loadingOlder.collectAsState()
    val loadOlderFailed by store.loadOlderFailed.collectAsState()
    val sessions by store.sessions.collectAsState()
    val connection by store.connectionState.collectAsState()
    val models by store.models.collectAsState()
    val permissions by store.permissions.collectAsState()
    val pendingPermission by store.pendingPermission.collectAsState()
    val contextBreakdown by store.contextBreakdown.collectAsState()
    val contextPressure by store.contextPressure.collectAsState()
    val pendingApproval by store.pendingApproval.collectAsState()
    val pendingQuestions by store.pendingQuestions.collectAsState()
    val scope = rememberCoroutineScope()

    val session = sessions.firstOrNull { it.sessionId == id }
    val title = session?.title ?: session?.cwd?.let { basename(it) } ?: id
    // The conversation of the session asked for, not the one before it that is still on screen while it loads.
    val shown = conversation?.takeIf { it.sessionId == id }
    val listState = rememberLazyListState()
    val memory = remember { TranscriptMemory() }
    val assistantMemory = remember(id) { AssistantMemory() }
    // iOS has no Settings yet, so the work-step level is the default one; taps are remembered per session, as on Android.
    val presentation = remember(id) { WorkStepPresentation(workStepPolicyFor(DEFAULT_WORK_STEP_DISPLAY), RowDisclosureMemory()) }
    val eventTimes = remember(shown?.journal) { shown?.journal?.associate { it.seq to it.time }.orEmpty() }
    val context = ChatNodeContext(
        nodes = shown?.nodes.orEmpty(),
        running = shown?.running == true,
        cwd = effectiveWorkingDirectory(shown?.projections, session?.cwd),
        eventTimes = eventTimes,
        assistantMemory = assistantMemory,
        presentation = presentation,
    )

    // How many pages the transcript has asked for in this session: the `asked` of the `older` report line.
    val olderAsked = remember(id) { mutableIntStateOf(0) }

    // The session's draft, kept by the store per harness and session as Android keeps it (and saved with it).
    val hostKey = connection.host?.let { "${it.baseUrl}|${it.id}" }.orEmpty()
    val composer = remember(hostKey, id) { store.composers.get(ComposerKey(hostKey, id)) }
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(composer) {
        if (!seeded && initialDraft != null) {
            seeded = true
            composer.text = initialDraft
            composer.selection = initialDraft.length
        }
    }
    val composerLines = remember(id) { mutableIntStateOf(1) }
    val composerHeight = remember(id) { mutableIntStateOf(0) }
    val running = shown?.running == true
    // No send on iOS until M6b; the report says so, so a scenario can tell a disabled button from a missing one.
    val onSend: ((String) -> Unit)? = null

    if (onReport != null) {
        LaunchedEffect(id) {
            // The store is read through its flows, never through a StateFlow's `value` inside `snapshotFlow`: that value
            // is not Compose state, so a snapshotFlow reading only it is never run again. This wait used to be one, and a
            // launch whose effect started before the session's history arrived reported nothing at all.
            val opened = store.currentConversation.first { it?.sessionId == id && it.nodes.isNotEmpty() }!!
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
            fun rows(c: ConversationSnapshot) = transcriptRows(c.nodes, c.running, presentation.policy).size
            onReport("DSH_CHAT_RESULT opened rows=${rows(opened)} session=$id more=${opened.hasMore}")
            // The composer, once it has been laid out and its editor has measured the draft (two frames after).
            snapshotFlow { composerHeight.intValue > 0 }.first { it }
            withFrameNanos { }
            withFrameNanos { }
            onReport(
                "DSH_CHAT_RESULT composer chars=${composer.text.length} lines=${composerLines.intValue} " +
                    "send=${if (onSend != null) "on" else "off"}",
            )
            if (scroll != ChatScroll.Older) return@LaunchedEffect
            // A reader's drag up to the oldest loaded message. The transcript's own trigger asks for the page before it.
            dragToOldestLoaded(listState)
            val landed = withTimeoutOrNull(OLDER_PAGE_WAIT_MS) {
                combine(store.currentConversation, store.loadingOlder, store.loadOlderFailed) { c, loading, failed ->
                    c?.takeIf { it.sessionId == id && !loading && (it.nodes.size > opened.nodes.size || !it.hasMore || failed) }
                }.first { it != null }
            }
            // On up to the top of what is loaded now, as the reader would: the start of the session after its last page.
            if (landed != null) dragToOldestLoaded(listState)
            val now = store.currentConversation.value?.takeIf { it.sessionId == id } ?: opened
            onReport(
                "DSH_CHAT_RESULT older rows=${rows(now)} session=$id more=${now.hasMore} " +
                    "failed=${store.loadOlderFailed.value} asked=${olderAsked.intValue}",
            )
        }
    }

    val colors = DsTheme.colors
    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ChatTopBar(
                title = title,
                running = running,
                models = models,
                agentPresetLabel = null,
                subagentCount = 0,
                detailsOpen = false,
                tab = ChatTab.Chat,
                fileCount = 0,
                needsYou = pendingApproval?.sessionId == id || pendingQuestions?.sessionId == id,
                onOpenDrawer = { onOpenSessions?.invoke() },
                onOpenModels = {},
                onOpenHistory = {},
                onOpenPresets = {},
                onOpenSubagents = {},
                onOpenDetails = null,
                onTabChange = {},
                onOpenWorkspace = {},
                workspaceEnabled = false,
                historyEnabled = false,
                // The chip states the model; the picker is a later step.
                modelsEnabled = false,
                listControl = if (onOpenSessions != null) ListControl.OpenDrawer else ListControl.None,
                showTabs = false,
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Android's in-flow cap: 60% of what the keyboard left, never under the 180dp that fits a line of text and
                // the row of controls.
                val composerMax = (maxHeight * 0.6f).coerceAtLeast(180.dp)
                Column(Modifier.fillMaxSize()) {
                    ChatTranscript(
                        conversation = shown,
                        loading = shown == null,
                        loadingOlder = loadingOlder,
                        loadOlderFailed = loadOlderFailed,
                        context = context,
                        listState = listState,
                        onLoadOlder = {
                            olderAsked.intValue++
                            scope.launch { store.loadOlder() }
                        },
                        memory = memory,
                        modifier = Modifier.weight(1f),
                    )
                    Composer(
                        draft = composer.text,
                        onDraftChange = { composer.text = it },
                        cursor = composer.selection,
                        onCursorChange = { composer.selection = it },
                        attachments = emptyList(),
                        onRemoveAttachment = {},
                        onRetryAttachment = {},
                        permissions = permissions,
                        pendingPermission = pendingPermission,
                        onPermissionPick = { value -> scope.launch { store.setPermissionPreset(value) } },
                        contextBreakdown = contextBreakdown,
                        contextPressure = contextPressure,
                        running = running,
                        enabled = true,
                        onOpenSheet = null,
                        onSend = onSend,
                        onStop = { scope.launch { store.cancelTurn() } },
                        onAddReference = null,
                        onLineCountChange = { composerLines.intValue = it },
                        modifier = Modifier
                            .heightIn(max = composerMax)
                            .onSizeChanged { composerHeight.intValue = it.height },
                    )
                }
            }
        }
    }
}

/** How long `-DshChatScroll older` waits for the page the transcript asked for before it reports what it has. */
private const val OLDER_PAGE_WAIT_MS = 40_000L

/** A bound on [dragToOldestLoaded]: about ten seconds of frames, far more than the demo's longest page needs. */
private const val MAX_DRAG_FRAMES = 600

/**
 * Drags the transcript toward its oldest loaded message the way a finger does: one scroll session at the gesture's
 * priority ([MutatePriority.UserInput]) that moves a step each frame until the list has not moved for three frames.
 *
 * The transcript tells a reader who left the newest message from one who did not by watching
 * [LazyListState.isScrollInProgress] (a drag or its fling), and its paging trigger watches the oldest rows come into
 * view. A session spread over frames is seen by both, as a drag is. A jump done in one frame
 * ([LazyListState.scrollToItem]) is seen by neither watcher: the page is still asked for once the far rows are laid out,
 * but the transcript goes on following the tail and pins it again when the page lands, which is what the first version
 * of this scenario did (PR #156's first CI run). Three still frames, not one, so rows a landed page adds above are
 * composed before the drag decides it has reached the top.
 */
private suspend fun dragToOldestLoaded(state: LazyListState) {
    state.scroll(MutatePriority.UserInput) {
        var still = 0
        repeat(MAX_DRAG_FRAMES) {
            val info = state.layoutInfo
            val step = ((info.viewportEndOffset - info.viewportStartOffset) * 0.6f).coerceAtLeast(1f)
            // A positive delta moves toward the higher indices, which in this reversed list are the older messages.
            if (scrollBy(step) < 0.5f) {
                if (++still >= 3) return@scroll
            } else {
                still = 0
            }
            withFrameNanos { }
        }
    }
}
