package com.labteto.dshmobile.ios

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.connection.DEFAULT_WORK_STEP_DISPLAY
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.effectiveWorkingDirectory
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.chatlist_open
import com.labteto.dshmobile.ui.LocalSessionStore
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.screens.main.AssistantMemory
import com.labteto.dshmobile.ui.screens.main.ChatNodeContext
import com.labteto.dshmobile.ui.screens.main.ChatTranscript
import com.labteto.dshmobile.ui.screens.main.RowDisclosureMemory
import com.labteto.dshmobile.ui.screens.main.TranscriptMemory
import com.labteto.dshmobile.ui.screens.main.WorkStepPresentation
import com.labteto.dshmobile.ui.screens.main.basename
import com.labteto.dshmobile.ui.screens.main.transcriptRows
import com.labteto.dshmobile.ui.screens.main.workStepPolicyFor
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource

/** Where `-DshChatScroll` leaves the transcript once a session is open: at its newest message, or paged back once. */
internal enum class ChatScroll { Tail, Older }

/**
 * The chat pane on iOS (M5c of #141): the open session's transcript, read only. It is the shared [ChatTranscript], the
 * one the Android chat draws, over the shared store: the same rows, cards, Markdown, paging and jump button. What iOS
 * does not have yet is everything the Android chat screen puts around it: the composer (M6), the top bar's model,
 * preset and details controls, the tabs, the sheets. So the header here is a title and, where the list is a drawer, the
 * button that opens it; a reply's Branch and feedback buttons and a subagent card's tap are left out (null in
 * [ChatNodeContext]), because there is nothing on iOS yet to carry them out.
 *
 * With no session open the pane is [empty] (the Connected page).
 *
 * [scroll] and [onReport] are the debug launch arguments `-DshOpenSession` and `-DshChatScroll`: once the session's
 * history is in and its transcript laid out the pane writes `DSH_CHAT_RESULT opened rows=… session=… more=…` (`more`
 * is the store's `hasMore` for that history, which is what puts a Load older row at the top). With [ChatScroll.Older] it
 * then drags the transcript up to the oldest loaded message as a reader's finger does ([dragToOldestLoaded]); the
 * transcript's own trigger asks for the page before it, and once that has landed the pane drags on to the top of what is
 * loaded and writes `DSH_CHAT_RESULT older rows=… session=… more=… failed=… asked=…`, `asked` being how many pages the
 * transcript asked for.
 */
@Composable
internal fun IosChatPane(
    onOpenSessions: (() -> Unit)?,
    scroll: ChatScroll?,
    onReport: ((String) -> Unit)?,
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

    if (onReport != null) {
        LaunchedEffect(id) {
            // The store is read through its flows, never through a StateFlow's `value` inside `snapshotFlow`: that value
            // is not Compose state, so a snapshotFlow reading only it is never run again. This wait used to be one, and a
            // launch whose effect started before the session's history arrived reported nothing at all.
            val opened = store.currentConversation.first { it?.sessionId == id && it.nodes.isNotEmpty() }!!
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
            fun rows(c: ConversationSnapshot) = transcriptRows(c.nodes, c.running, presentation.policy).size
            onReport("DSH_CHAT_RESULT opened rows=${rows(opened)} session=$id more=${opened.hasMore}")
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
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onOpenSessions != null) {
                    DsIconButton(
                        icon = FeatherIcons.Menu,
                        contentDescription = stringResource(Res.string.chatlist_open),
                        onClick = onOpenSessions,
                    )
                } else {
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    title,
                    style = DsType.rowTitle,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(color = colors.borderL1)
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
