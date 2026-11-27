package com.labteto.dshmobile.ios

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.connection.DEFAULT_WORK_STEP_DISPLAY
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * transcript is laid out the pane writes `DSH_CHAT_RESULT opened rows=… session=… more=…`, and with [ChatScroll.Older]
 * it then scrolls to the oldest loaded row, which pages in the next older page exactly as a reader's scroll does, waits
 * for it, shows the top of what is loaded and writes `DSH_CHAT_RESULT older rows=… session=… more=…`.
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

    if (onReport != null) {
        LaunchedEffect(id) {
            // Laid out with rows on screen, for the session asked for.
            val opened = snapshotFlow {
                val c = store.currentConversation.value?.takeIf { it.sessionId == id }
                if (c != null && listState.layoutInfo.visibleItemsInfo.isNotEmpty()) c else null
            }.first { it != null }!!
            fun rows() = transcriptRows(
                store.currentConversation.value?.nodes.orEmpty(),
                store.currentConversation.value?.running == true,
                presentation.policy,
            ).size
            onReport("DSH_CHAT_RESULT opened rows=${rows()} session=$id more=${opened.hasMore}")
            if (scroll != ChatScroll.Older) return@LaunchedEffect
            // The far end of the reversed list is the oldest loaded row: reaching it is what asks for the next page.
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
            val before = opened.nodes.size
            snapshotFlow {
                val c = store.currentConversation.value
                !store.loadingOlder.value && c != null && (c.nodes.size > before || !c.hasMore || store.loadOlderFailed.value)
            }.first { it }
            // Show the top of what is now loaded: the start of the session when that page was the last one.
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
            val after = store.currentConversation.value
            onReport(
                "DSH_CHAT_RESULT older rows=${rows()} session=$id more=${after?.hasMore} " +
                    "failed=${store.loadOlderFailed.value}",
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
                onLoadOlder = { scope.launch { store.loadOlder() } },
                memory = memory,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
