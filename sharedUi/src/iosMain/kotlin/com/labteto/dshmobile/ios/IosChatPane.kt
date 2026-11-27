package com.labteto.dshmobile.ios

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.labteto.dshmobile.connection.DEFAULT_WORK_STEP_DISPLAY
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.session.effectiveWorkingDirectory
import com.labteto.dshmobile.ui.LocalSessionStore
import com.labteto.dshmobile.ui.adaptive.ListControl
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.screens.main.AssistantMemory
import com.labteto.dshmobile.ui.screens.main.ChatContent
import com.labteto.dshmobile.ui.screens.main.ChatNodeContext
import com.labteto.dshmobile.ui.screens.main.ChatTab
import com.labteto.dshmobile.ui.screens.main.ChatTopBar
import com.labteto.dshmobile.ui.screens.main.ComposerKey
import com.labteto.dshmobile.ui.screens.main.RowDisclosureMemory
import com.labteto.dshmobile.ui.screens.main.TranscriptMemory
import com.labteto.dshmobile.ui.screens.main.WorkStepPresentation
import com.labteto.dshmobile.ui.screens.main.basename
import com.labteto.dshmobile.ui.screens.main.rememberChatActions
import com.labteto.dshmobile.ui.screens.main.transcriptRows
import com.labteto.dshmobile.ui.screens.main.workStepPolicyFor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Where `-DshChatScroll` leaves the transcript once a session is open: at its newest message, or paged back once. */
internal enum class ChatScroll { Tail, Older }

/**
 * What the debug launch arguments ask the chat pane to do once a session is open (#141 M6b): [prompt] is
 * `-DshPrompt`, put in the composer as typed; with [send] (`-DshSendPrompt`) it is sent the way the send button sends
 * it. [pressStop] (`-DshPressStop`) presses Stop once the reply is streaming and [awaitStop] has returned (the script
 * says so after its mid-stream screenshot). [focusComposer] (`-DshFocusComposer`) moves focus into the composer, which
 * brings the software keyboard up.
 */
internal class ChatDrive(
    val prompt: String? = null,
    val send: Boolean = false,
    val pressStop: Boolean = false,
    val awaitStop: (suspend () -> Unit)? = null,
    val focusComposer: Boolean = false,
)

/**
 * The chat pane on iOS (#141): the shared [ChatContent] (since M6b), the chat Android draws, under the shared top bar.
 * The transcript, the docks, the composer and the send path are the same code as Android's; the composer lifts into a
 * drawer over the transcript from two wrapped lines, and the column rests on the software keyboard (the view
 * controller leaves the keyboard to the insets, see [MainViewController]). What iOS does not have yet is left out
 * rather than drawn dead: in the bar the model picker (the chip states the model), the preset, subagent and team
 * chips, history, details and the tabs; in the composer the `+` sheet, the `@` picker, attachments and dictation; the
 * `/` menu, approvals and questions (M7), and the connection banner (M7). Sending, Stop and the permission chip work.
 * A reply's Branch and feedback buttons and a subagent card's tap are left out too (null in [ChatNodeContext]). The
 * draft is the store's per-session one, as on Android, so it survives a session switch.
 *
 * With no session open the pane is [empty] (the Connected page).
 *
 * [scroll], [onReport], [initialDraft] and [drive] are the debug launch arguments `-DshOpenSession`, `-DshChatScroll`,
 * `-DshComposerDraft` and the ones [ChatDrive] names. Once the session's history is in and its transcript laid out the
 * pane writes `DSH_CHAT_RESULT opened rows=… session=… more=…` (`more` is the store's `hasMore` for that history,
 * which is what puts a Load older row at the top). Once the composer has been laid out it writes
 * `DSH_CHAT_RESULT composer chars=… lines=… send=…`: the draft's length, the lines the editor paints and whether
 * pressing send would send. With [ChatScroll.Older] it then drags the transcript up to the oldest loaded message as a
 * reader's finger does ([dragToOldestLoaded]); the transcript's own trigger asks for the page before it, and once that
 * has landed the pane drags on to the top of what is loaded and writes `DSH_CHAT_RESULT older rows=… session=… more=…
 * failed=… asked=…`, `asked` being how many pages the transcript asked for. A [ChatDrive] writes its own lines, in this
 * order: `keyboard ime=… composer=… window=… above=…` (pixels: the keyboard's height, the composer block's bottom and
 * the window's height, and whether the block ends above the keyboard), `sent chars=… session=…`, `streaming chars=…`
 * (the first streamed text of the reply), then `replied chars=… state=idle` once the reply has settled, or with
 * [ChatDrive.pressStop] `stopped state=idle turn=… chars=…` (the turn's end and the text the reply kept).
 */
@Composable
internal fun IosChatPane(
    onOpenSessions: (() -> Unit)?,
    scroll: ChatScroll?,
    onReport: ((String) -> Unit)?,
    initialDraft: String? = null,
    drive: ChatDrive = ChatDrive(),
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
    val sessions by store.sessions.collectAsState()
    val connection by store.connectionState.collectAsState()
    val models by store.models.collectAsState()
    val pendingApproval by store.pendingApproval.collectAsState()
    val pendingQuestions by store.pendingQuestions.collectAsState()

    val session = sessions.firstOrNull { it.sessionId == id }
    val title = session?.title ?: session?.cwd?.let { basename(it) } ?: id
    val listState = rememberLazyListState()
    val memory = remember { TranscriptMemory() }
    val assistantMemory = remember(id) { AssistantMemory() }
    // iOS has no Settings yet, so the work-step level is the default one; taps are remembered per session, as on Android.
    val presentation = remember(id) { WorkStepPresentation(workStepPolicyFor(DEFAULT_WORK_STEP_DISPLAY), RowDisclosureMemory()) }
    val eventTimes = remember(conversation?.journal) { conversation?.journal?.associate { it.seq to it.time }.orEmpty() }
    val context = ChatNodeContext(
        nodes = conversation?.nodes.orEmpty(),
        running = conversation?.running == true,
        cwd = effectiveWorkingDirectory(conversation?.projections, session?.cwd),
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
        val text = drive.prompt ?: initialDraft
        if (!seeded && text != null) {
            seeded = true
            composer.text = text
            composer.selection = text.length
        }
    }
    val toast = rememberDsToast()
    val actions = rememberChatActions(composer, toast.second)
    // The report reads the actions of the composition it runs in, not the one it started in.
    val currentActions by rememberUpdatedState(actions)
    val composerLines = remember(id) { mutableIntStateOf(1) }
    val composerHeight = remember(id) { mutableIntStateOf(0) }
    val composerBottom = remember(id) { mutableIntStateOf(0) }
    val running = conversation?.running == true

    // The keyboard's height and the window's, read only when a scenario asks about the keyboard.
    val imeBottom = remember { mutableIntStateOf(0) }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    if (drive.focusComposer) {
        val ime = WindowInsets.ime.getBottom(LocalDensity.current)
        SideEffect { imeBottom.intValue = ime }
    }

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
            // What the send button would do now: send, unless the draft is empty or a send is still on its way.
            val canSend = composer.text.isNotBlank() && !composer.submitting && !composer.preparing
            onReport(
                "DSH_CHAT_RESULT composer chars=${composer.text.length} lines=${composerLines.intValue} " +
                    "send=${if (canSend) "on" else "off"}",
            )
            if (scroll == ChatScroll.Older) {
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
                return@LaunchedEffect
            }
            if (drive.focusComposer) {
                // As a tap on the field: focus moves in and the software keyboard comes up. The report waits for the
                // keyboard to have risen and the column to have settled on it (the inset still for ten frames).
                currentActions.focusComposer()
                withTimeoutOrNull(KEYBOARD_WAIT_MS) { snapshotFlow { imeBottom.intValue > 0 }.first { it } }
                var still = 0
                var last = -1
                var frames = 0
                while (still < 10 && frames < MAX_SETTLE_FRAMES) {
                    withFrameNanos { }
                    frames++
                    val ime = imeBottom.intValue
                    still = if (ime == last && ime > 0) still + 1 else 0
                    last = ime
                }
                val ime = imeBottom.intValue
                val bottom = composerBottom.intValue
                val above = ime > 0 && bottom <= windowHeight - ime + 1
                onReport(
                    "DSH_CHAT_RESULT keyboard ime=$ime composer=$bottom window=$windowHeight " +
                        "above=${if (above) "yes" else "no"}",
                )
            }
            val prompt = drive.prompt
            if (prompt != null && drive.send) {
                val settledBefore = opened.nodes.count { it is AssistantMessageNode && !it.streaming }
                // What the send button does: the field empties and the draft goes to the send path.
                composer.text = ""
                composer.selection = 0
                currentActions.send(prompt)
                onReport("DSH_CHAT_RESULT sent chars=${prompt.length} session=$id")
                val streaming = withTimeoutOrNull(REPLY_WAIT_MS) {
                    store.currentConversation.first { c ->
                        c?.sessionId == id && c.nodes.any { it is AssistantMessageNode && it.streaming && it.plainText.isNotEmpty() }
                    }
                }
                val streamedText = streaming?.nodes?.lastOrNull { it is AssistantMessageNode && it.streaming }
                    ?.let { (it as AssistantMessageNode).plainText }
                onReport("DSH_CHAT_RESULT streaming chars=${streamedText?.length ?: 0} session=$id")
                if (drive.pressStop) {
                    // The script takes its mid-stream screenshot first, then says so.
                    drive.awaitStop?.invoke()
                    currentActions.stop()
                    val ended = withTimeoutOrNull(REPLY_WAIT_MS) {
                        store.currentConversation.first { c -> c?.sessionId == id && !c.running && c.nodes.lastOrNull() is TurnEndNode }
                    }
                    val turnEnd = ended?.nodes?.lastOrNull() as? TurnEndNode
                    val kept = ended?.nodes?.lastOrNull { it is AssistantMessageNode && !it.streaming } as? AssistantMessageNode
                    onReport(
                        "DSH_CHAT_RESULT stopped state=${if (ended != null) "idle" else "running"} " +
                            "turn=${turnEnd?.reasonKind ?: "none"} chars=${kept?.plainText?.length ?: 0}",
                    )
                } else {
                    val settled = withTimeoutOrNull(REPLY_WAIT_MS) {
                        store.currentConversation.first { c ->
                            c?.sessionId == id && !c.running &&
                                c.nodes.count { it is AssistantMessageNode && !it.streaming } > settledBefore
                        }
                    }
                    val reply = settled?.nodes?.lastOrNull { it is AssistantMessageNode && !it.streaming } as? AssistantMessageNode
                    onReport(
                        "DSH_CHAT_RESULT replied chars=${reply?.plainText?.length ?: 0} " +
                            "state=${if (settled != null) "idle" else "running"} session=$id",
                    )
                }
            }
        }
    }

    ChatContent(
        composer = composer,
        actions = actions,
        toast = toast,
        nodeContext = context,
        chatListState = listState,
        transcriptMemory = memory,
        onLoadOlder = { olderAsked.intValue++ },
        onComposerLines = { composerLines.intValue = it },
        onComposerBlockHeight = { composerHeight.intValue = it },
        composerBlockModifier = Modifier.onGloballyPositioned {
            composerBottom.intValue = it.boundsInWindow().bottom.roundToInt()
        },
        topBar = {
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
        },
    )
}

/** How long `-DshChatScroll older` waits for the page the transcript asked for before it reports what it has. */
private const val OLDER_PAGE_WAIT_MS = 40_000L

/** How long a sent prompt's reply may take to start, and to end (the demo's slow reply takes about 18 s). */
private const val REPLY_WAIT_MS = 60_000L

/** How long `-DshFocusComposer` waits for the software keyboard to come up before it reports what it sees. */
private const val KEYBOARD_WAIT_MS = 15_000L

/** A bound on the wait for the column to settle on the keyboard: about five seconds of frames. */
private const val MAX_SETTLE_FRAMES = 300

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

/** The pause between two looks at the stop signal [ChatDrive.awaitStop] waits for. */
internal const val STOP_SIGNAL_POLL_MS = 250L

/** Waits until [present] says the signal is there, at most [timeoutMs]; a script that never sends it is not waited on. */
internal suspend fun awaitSignal(timeoutMs: Long, present: () -> Boolean) {
    withTimeoutOrNull(timeoutMs) {
        while (!present()) delay(STOP_SIGNAL_POLL_MS)
    }
}
