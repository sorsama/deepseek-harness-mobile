package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.clearSelectionOnTap
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * How close to the far end of the list — the oldest message — a reader must get before the next
 * page is fetched.
 *
 * Deliberately not zero: firing a row or two early means the page is on its way before the reader
 * reaches the end of what is loaded, so scrolling back stays continuous rather than stopping dead
 * at a spinner.
 *
 * In reverse layout this no longer has a second job. The list is anchored at the newest message,
 * older ones extend away from that anchor, and a page arriving at the far end cannot move anything
 * the reader is looking at — which is what the pre-0.11.2 comment here was working around.
 */
private const val LOAD_OLDER_THRESHOLD = 2

/**
 * How many pages the transcript may fetch on its own before it needs to be asked.
 *
 * Paging used to be automatic without limit while the transcript was shorter than the screen. That
 * reads as reasonable and is not: a page is counted in *events*, and most events — chunk deltas,
 * tool traffic, turn boundaries — render nothing at all. A session whose log is mostly machinery
 * therefore never fills the screen however much is loaded, so the fill loop pulled the entire
 * history in, four thousand events at a time, re-folding everything already held on each pass until
 * the heap gave out.
 *
 * One extra page is the whole of what the fill is for. A page carries up to sixty messages, which
 * is several screens' worth already; if it still does not reach the bottom of the viewport then the
 * session's log is mostly machinery, and pulling more of it is buying thousands more events for a
 * row or two. Past that the reader asks, via the row at the head of the list.
 */
private const val MAX_AUTO_PAGES = 1

/** Pages fetched back-to-back to get past a rewind's hidden range before a row appears. */
private const val MAX_CUT_PAGES = 12

/**
 * What a [ChatTranscript] has already done, kept above the tab swap that disposes it.
 *
 * Plain fields rather than state: they are only read inside effects, and an effect that changed a
 * snapshot value here would recompose the screen for nothing.
 */
internal class TranscriptMemory {
    /** The session whose newest message the list was last scrolled to on open. */
    var scrolledForSession: String? = null

    /** The last send tick the transcript followed to the bottom. */
    var handledSentTick: Int = 0
}

/**
 * The conversation itself.
 *
 * The list is laid out in reverse: the newest row is index 0, pinned at the bottom of the
 * viewport, and older rows extend upward from it. That is what makes following a streaming reply
 * free. A normally-ordered list anchors on its *first* visible row, so a tail row growing 20 times
 * a second displaces every row after it, and each displacement was being animated — the text under
 * the reader's eyes jittered, and a scroll was needed every frame just to stay at the bottom.
 * Reversed, the growing row is the anchor: it extends in place, nothing else moves, and no scroll
 * happens at all.
 *
 * It also makes reading history mid-turn stable for free once the reader is past the tail row: their
 * anchor sits above index 0, so the tail can grow without touching the viewport, and a page of older
 * messages lands at the far end. Scrolled up *inside* a tail row that is taller than the screen the
 * anchor is still index 0, and the row grows away from its fixed bottom edge — the text under the
 * reader's eyes keeps sliding toward the newest line. That case is handled by freezing the row list
 * while following is off (see `frozenRows` below).
 */
@Composable
internal fun ChatTranscript(
    conversation: ConversationSnapshot?,
    loading: Boolean,
    loadingOlder: Boolean,
    loadOlderFailed: Boolean,
    context: ChatNodeContext,
    listState: LazyListState,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Extra room at each side, so the rows keep to a reading width centred in a wide pane while the
     * list itself still spans the pane and scrolls from anywhere on it.
     */
    gutter: Dp = 0.dp,
    /** A message the reader picked out of the history sheet, still waiting to be scrolled to. */
    jumpToSeq: Long? = null,
    onJumpConsumed: () -> Unit = {},
    /** Fills the composer with a starter prompt from the empty state; null offers none. */
    onSuggestion: ((String) -> Unit)? = null,
    /** Bumped each time the reader sends: their own message is always worth landing on. */
    sentTick: Int = 0,
    /** Whether to follow the tail as a reply is written; off leaves new rows below the viewport. */
    followStreaming: Boolean = true,
    /**
     * What this transcript has already acted on. Held by the caller, because the tab swap disposes
     * this composition and a fresh one would otherwise take a session it had scrolled to, and a send
     * it had already followed, for new ones.
     */
    memory: TranscriptMemory = remember { TranscriptMemory() },
) {
    // Only the nodes that draw something: a zero-height item still costs its 4dp gap, and a turn's
    // worth of structural events stacks those gaps into a blank band under the chrome.
    //
    // Reversed here rather than at the call site: the fold's natural order is oldest-first, and
    // that is the order every other reader of `nodes` wants.
    val rows = remember(conversation?.nodes, context.running) {
        val nodes = conversation?.nodes.orEmpty()
        foldProcessRows(
            rows = groupToolRows(renderableChatNodes(nodes, context.running)),
            // Read from the unfiltered nodes: a turn start draws no row, so the row list alone
            // cannot say where one turn ends and the next begins.
            turnStarts = nodes.filterIsInstance<TurnStartNode>().map { it.seq }.sorted(),
            running = context.running,
        ).asReversed()
    }
    val hasMore = conversation?.hasMore == true
    val sessionId = conversation?.sessionId

    // Following the tail is free only while the tail is one growing row. The layout keeps the first
    // visible *key* anchored, and in a reversed list that key is the bottom row: insert a row below
    // it — a tool card, the next message segment, a question card, the reader's own message — and
    // the new row takes index 0 while the viewport holds the old bottom row exactly where it was,
    // leaving the arrival one scroll past the edge. Nothing animated it into place either, so the
    // reader watching a reply arrives at a dead screen until they drag.
    //
    // Pin index 0 again while the reader is at the bottom. `requestScrollToItem` is the one call
    // that overrides the key anchoring for the coming measure; it is instant and schedules no
    // animation, so it cannot race the next delta the way a per-frame scroll would.
    //
    // Following is intent, not position: the anchoring above moves the first visible index to 1 on
    // its own when a row lands below, and reading that as "the reader scrolled away" would switch
    // following off at the exact moment it is needed. Only a scroll session — a drag, or the fling
    // that follows one, both of which `isScrollInProgress` covers — moves the reader on purpose, so
    // where the list comes to rest at the end of one is the whole of the signal.
    //
    // Primed from the position rather than assumed: the list state is hoisted above the tab swap
    // and outlives this composition. Today the session-open scroll below lands every new
    // composition on the tail anyway, so this only matters if that scroll ever stops doing so.
    fun atTail(): Boolean =
        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
    var followTail by remember(sessionId) { mutableStateOf(true) }

    // A reply is one row, and a long one is taller than the screen. Scrolled up inside that row the
    // reader's anchor is still index 0, and the row keeps growing away from its fixed bottom edge:
    // every delta shifts the text under their eyes toward the newest line, so a long answer cannot
    // be read until it settles. Scrolled past it (index > 0) the reversed layout already holds
    // still. Freezing the row list while following is off covers the first case too: the page stops
    // where the reader left it, and the tail catches up in one step when they come back down.
    var frozenRows by remember(sessionId) { mutableStateOf<List<TranscriptRow>?>(null) }
    val displayRows = frozenRows?.let { frozen ->
        // Paging adds rows at the far (oldest) end after the freeze, and those must still appear,
        // or a reader scrolling to the top of the frozen page meets a Load older row that never
        // resolves. Rows are newest-first, so anything older than the snapshot's oldest seq belongs
        // below it.
        val oldest = frozen.lastOrNull()?.seq ?: return@let rows
        frozen + rows.filter { it.seq < oldest }
    } ?: rows
    val itemCount = displayRows.size + if (hasMore) 1 else 0

    // Opening a session lands on its newest message. In reverse layout that is index 0, which is
    // also where the list starts, so this only has to undo a position inherited from the session
    // that was open before. Following the tail is handled just below, where the anchoring the
    // reversal buys is overridden only when it would otherwise cost the reader the newest row.
    //
    // The marker lives in [memory], not here. Leaving for the Trajectory or Files tab disposes this
    // composition, and a marker that went with it made coming back look like opening a new session:
    // the list jumped to the newest message however far up the reader had been.
    LaunchedEffect(sessionId) {
        if (sessionId == memory.scrolledForSession) return@LaunchedEffect
        memory.scrolledForSession = sessionId
        if (itemCount > 0) listState.scrollToItem(0)
    }

    LaunchedEffect(listState, sessionId) {
        followTail = atTail()
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            followTail = atTail()
        }
    }
    // Captured where the reader came to rest, not on every delta: the effect is keyed on the
    // following flag alone, so `rows` here is the page they left.
    LaunchedEffect(followTail) {
        frozenRows = if (followTail) null else rows
    }
    LaunchedEffect(itemCount, followTail, followStreaming) {
        // itemCount rather than the rows themselves: a delta changes the text of a row, not how
        // many rows there are, and only the arrival of a row can land the tail out of view.
        // followStreaming is the user's choice: off leaves the arrival below the viewport and lets
        // the jump pill report it instead.
        if (followStreaming && followTail && itemCount > 0 && !listState.isScrollInProgress) {
            listState.requestScrollToItem(0)
        }
    }

    // Reaching the oldest loaded message pulls the next page. The guard matters: this effect sits
    // above the `loading` early return, so without it the trigger would fire against an empty list
    // and race the initial history fetch. It also re-arms once a page lands, which is what fills
    // the first screen when a session opens on fewer messages than the viewport holds.
    //
    // Two different things want a page, and only one of them is safe to repeat without limit.
    // Scrolling back to the oldest loaded row is the reader asking, and can page as far back as
    // they care to go.
    // Filling a screen that the transcript does not yet cover is the app asking, and is bounded by
    // MAX_AUTO_PAGES — a page that adds thousands of events and no visible rows would otherwise
    // keep the app asking forever.
    var autoPages by rememberSaveable(sessionId) { mutableIntStateOf(0) }
    val canPage = hasMore && !loading && !loadingOlder && !loadOlderFailed
    val autoPagingExhausted = hasMore && !loading && autoPages >= MAX_AUTO_PAGES

    // A rewind hides everything from its target through its marker. A page that lands entirely
    // inside that range adds events and no rows, so a tap on "Load older" looks like it did
    // nothing. When the oldest row loaded — the top of the transcript — is a rewind divider and a
    // page just landed without changing the row count, keep paging (bounded) until something
    // above the cut appears.
    val rowCount = displayRows.size
    val dividerOnTop = (displayRows.lastOrNull() as? TranscriptRow.Message)?.node.let { it is UserMessageNode && it.isRewindMarker }
    var lastPagedRowCount by remember(sessionId) { mutableIntStateOf(-1) }
    var cutPages by remember(sessionId) { mutableIntStateOf(0) }
    LaunchedEffect(rowCount, loadingOlder, hasMore, dividerOnTop) {
        if (loadingOlder || !hasMore || loadOlderFailed || !dividerOnTop) return@LaunchedEffect
        if (lastPagedRowCount == -1) { lastPagedRowCount = rowCount; return@LaunchedEffect }
        if (rowCount != lastPagedRowCount) { lastPagedRowCount = rowCount; cutPages = 0; return@LaunchedEffect }
        if (cutPages >= MAX_CUT_PAGES) return@LaunchedEffect
        cutPages++
        onLoadOlder()
    }
    LaunchedEffect(listState, sessionId, canPage) {
        if (!canPage) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val covered = info.visibleItemsInfo.sumOf { it.size }
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            // The oldest row is at the *end* of a reversed list, so distance-to-history is measured
            // from the last visible index rather than the first.
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val remaining = if (last < 0) Int.MAX_VALUE else info.totalItemsCount - 1 - last
            remaining to (viewport > 0 && covered >= viewport)
        }.collect { (remaining, fillsViewport) ->
            if (remaining > LOAD_OLDER_THRESHOLD) return@collect
            if (!fillsViewport) {
                if (autoPages >= MAX_AUTO_PAGES) return@collect
                autoPages++
            }
            onLoadOlder()
        }
    }

    // A pick from the history sheet. The sheet knows only sequences; which row index a sequence owns
    // is decided by the grouping above, so the lookup happens here rather than there.
    //
    // Keyed on the pick alone. Re-running on every row change would restart a jump halfway through
    // whenever a running reply added a row, and the sheet only offers prompts that are loaded, so
    // there is no later page worth waiting for. The pick is consumed however the jump ends —
    // landed, missed, or cut short by the reader's own drag — so a leftover cannot fire later and
    // pull them back.
    //
    // Following the tail is switched off first, or the next row a running reply adds would pin the
    // tail back under a reader who deliberately left it. Picking the tail itself keeps it on.
    LaunchedEffect(jumpToSeq) {
        val seq = jumpToSeq ?: return@LaunchedEffect
        try {
            val index = historyRowIndex(displayRows, seq) ?: return@LaunchedEffect
            if (index == 0) {
                followTail = true
                listState.requestScrollToItem(0)
                return@LaunchedEffect
            }
            followTail = false
            // The prompt goes to the top of the viewport, not the bottom. A reversed list puts the
            // item it scrolls to at its start, which is the bottom edge: the prompt would sit on the
            // composer with the reply it asked for out of view below it, and the screen filled with
            // the turn before. Both steps are instant; the second needs the prompt measured, since
            // its height decides how far back to come, and clamps at the tail on its own.
            listState.scrollToItem(index)
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == seq } ?: return@LaunchedEffect
            val contentEnd = info.viewportEndOffset - info.afterContentPadding
            listState.scrollBy((item.offset + item.size - contentEnd).toFloat())
            // Read where the jump came to rest rather than leaving it to the scroll-session watcher:
            // both scrolls start and finish inside one frame, so it never sees them. A newest prompt
            // whose turn is shorter than the screen stops at the tail, and a reader left at the tail
            // with following off would watch the next reply land out of view.
            followTail = atTail()
        } finally {
            onJumpConsumed()
        }
    }

    if (loading) {
        TranscriptSkeleton(modifier)
        return
    }

    // Rows that arrived below the reader since they last left the tail, counted against the
    // newest row they had seen. Paging adds rows too, but at the far end, so it cannot inflate this.
    var seenNewestKey by remember(sessionId) { mutableStateOf<Any?>(null) }
    // The streaming row's key is shared by every reply in turn, so it cannot mark a place.
    val newestKey = rows.firstNotNullOfOrNull { row -> transcriptRowKey(row).takeIf { it != STREAMING_ROW_KEY } }
    LaunchedEffect(followTail, newestKey, followStreaming) {
        if (!followTail) return@LaunchedEffect
        if (followStreaming) {
            seenNewestKey = newestKey
            return@LaunchedEffect
        }
        // Following is off, so nothing moves the viewport onto the arrival and "seen" is a question
        // about the screen again. Wait for the row to be measured before judging, or the arrival
        // that just landed below the viewport would be counted as already read.
        withFrameNanos { }
        if (atTail()) seenNewestKey = newestKey
    }
    // A step the reader saw can fold into its turn's Process row when the turn answers, which is
    // exactly while they are away; that row then stands in for it, or the count drops to nothing
    // just as the reply lands.
    val unseen = remember(rows, seenNewestKey) {
        if (seenNewestKey == null) 0
        else rows.indexOfFirst { row ->
            transcriptRowKey(row) == seenNewestKey ||
                (row is TranscriptRow.Process && row.rows.any { transcriptRowKey(it) == seenNewestKey })
        }.coerceAtLeast(0)
    }
    val awayFromTail by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 240 }
    }
    val jumpScope = rememberCoroutineScope()
    // Sending from halfway up the history used to leave the new message, and the reply to it, out
    // of view below. A send is the clearest possible "take me to the end".
    LaunchedEffect(sentTick) {
        // A tick this transcript already followed is history: the effect starts afresh whenever the
        // tab swap rebuilds the screen, and replaying an old send yanked the reader to the bottom.
        if (sentTick == 0 || sentTick == memory.handledSentTick) return@LaunchedEffect
        memory.handledSentTick = sentTick
        followTail = true
        if (listState.firstVisibleItemIndex > JUMP_SNAP_ROWS) listState.scrollToItem(JUMP_SNAP_ROWS)
        listState.animateScrollToItem(0)
    }

    // A tap anywhere on the transcript ends a text selection made in it (issue #90); see the modifier.
    Box(modifier.fillMaxSize().clearSelectionOnTap()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp + gutter, vertical = 10.dp),
        // Newest first, so the row the viewport anchors on is the one that grows.
        reverseLayout = true,
        // Still bottom-aligned: a transcript shorter than the viewport belongs above the composer,
        // not pinned under the tab strip with the empty half below it. The alignment is in visual
        // space, not the reversed one, so this reads the same as it always did — and it only has
        // any effect while the content is shorter than the viewport, which is exactly when no row
        // is growing under anyone's eyes.
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
    ) {
        if (displayRows.isEmpty()) {
            item(key = "empty") {
                // A blank page with a blinking cursor is the hardest place to start from on a
                // phone keyboard, so a new session offers a few starters. They fill the composer
                // rather than sending, so each one is a draft to adjust, not a commitment.
                val suggestions = listOf(
                    stringResource(R.string.chat_suggest_overview),
                    stringResource(R.string.chat_suggest_tests),
                    stringResource(R.string.chat_suggest_changes),
                )
                EmptyHero(
                    headline = stringResource(R.string.chat_empty_title),
                    subtitle = stringResource(R.string.chat_empty_hint),
                    chips = if (onSuggestion != null) suggestions else emptyList(),
                    onChipClick = { onSuggestion?.invoke(it) },
                )
            }
        } else {
            items(
                displayRows,
                // A streaming row's seq is minted past the durable cursor and is explicitly not
                // stable across folds, so keying on it destroyed and rebuilt the row the moment the
                // settlement landed — a visible pop at the end of every reply. One constant key
                // instead: there is only ever one provisional row, and it is the same row before
                // and after it settles.
                key = ::transcriptRowKey,
            ) { row ->
                val node = (row as? TranscriptRow.Message)?.node
                val streaming = node is AssistantMessageNode && node.streaming
                // Rows fade in as they land, and nothing slides. Every line a streaming reply adds
                // pushes the rows above it up; a placement animation made them trail that push on
                // a spring while the reply moved at once, so a tool card would ride over the text
                // being written for a moment on every new line.
                //
                // A reply that just settled is a new key (the streaming key gives way to its seq),
                // so the list would fade it in from nothing: every finished reply flashed to a
                // ghost of itself and back. Its text has been on screen the whole time, so assistant
                // rows take no arrival animation at all.
                val arrival = when {
                    streaming || node is AssistantMessageNode -> Modifier
                    else -> Modifier.animateItem(placementSpec = null)
                }
                Column(arrival) {
                    when (row) {
                        is TranscriptRow.Message -> ChatNodeItem(node = row.node, context = context)
                        is TranscriptRow.Tools -> ToolGroupRow(row.calls, context)
                        is TranscriptRow.Context -> ContextGroupRow(row.nodes, context)
                        is TranscriptRow.Process -> ProcessRow(row, context)
                    }
                }
            }
        }
        if (hasMore) {
            // The far end of a reversed list is the oldest message, so the paging row goes last.
            item(key = "load-older") {
                LoadOlderRow(
                    loading = loadingOlder,
                    failed = loadOlderFailed,
                    offerManual = autoPagingExhausted,
                    onRetry = onLoadOlder,
                )
            }
        }
    }

    // The way back down. Reading history mid-turn is supported on purpose (see the anchoring notes
    // above), but on a long session the only way back used to be dragging through everything in
    // between. Shown only once the reader is clearly away, so it does not flicker in on a nudge.
    AnimatedVisibility(
        visible = awayFromTail && displayRows.isNotEmpty(),
        enter = DsAnimations.popEnter,
        exit = DsAnimations.popExit,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
    ) {
        JumpToLatestButton(
            unseen = unseen,
            running = context.running,
            onClick = {
                followTail = true
                // With following off the flag above is already true at the tail, so the effect that
                // marks the newest row seen does not rerun; landing on it clears the count here.
                seenNewestKey = newestKey
                jumpScope.launch {
                    // A long way back is mostly rows nobody needs to watch go by: land near the tail
                    // first, then animate the last stretch so the motion still reads as "down".
                    if (listState.firstVisibleItemIndex > JUMP_SNAP_ROWS) listState.scrollToItem(JUMP_SNAP_ROWS)
                    listState.animateScrollToItem(0)
                }
            },
        )
    }
    }
}

/** How far from the tail a jump snaps before animating the rest of the way. */
private const val JUMP_SNAP_ROWS = 6

/** The lazy key of a transcript row; see the keying note in [ChatTranscript]. */
private fun transcriptRowKey(row: TranscriptRow): Any {
    val node = (row as? TranscriptRow.Message)?.node
    return if (node is AssistantMessageNode && node.streaming) STREAMING_ROW_KEY else row.seq
}

/** A round "back to the newest message" button, with a count of what arrived while away. */
@Composable
private fun JumpToLatestButton(unseen: Int, running: Boolean, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val label = if (unseen > 0) {
        pluralStringResource(R.plurals.chat_jump_latest_new, unseen, unseen)
    } else {
        stringResource(R.string.chat_jump_latest)
    }
    Surface(
        onClick = onClick,
        shape = DsShapes.pillFull,
        color = colors.bgLayer2,
        border = BorderStroke(1.dp, colors.borderL2),
        shadowElevation = 6.dp,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(horizontal = if (unseen > 0) 14.dp else 10.dp)
                .animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (running) StateDot(StateDotState.Running, size = 8.dp)
            if (unseen > 0) {
                Text(
                    pluralStringResource(R.plurals.chat_jump_latest_new, unseen, unseen),
                    style = DsType.small13Strong,
                    color = colors.accent,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Stable identity for the one provisional streaming row; see the keying note above. */
private const val STREAMING_ROW_KEY = "streaming-tail"

/**
 * Tail of the reversed list — visually the top of the transcript — while more history exists.
 *
 * Silent by default — paging is automatic, so an affordance would only invite a tap that does
 * nothing. It speaks up while fetching, and offers a retry when a page failed, because the scroll
 * trigger will not fire again on its own until the reader moves.
 *
 * [offerManual] is the third case: automatic paging has spent its budget on a session whose events
 * are mostly not messages, so the list may still be too short to scroll. Without a button there
 * would be nothing left to trigger a page, and the rest of the history would be unreachable. The
 * history sheet always passes it: nothing scrolls a sheet to its end to page for it.
 */
@Composable
internal fun LoadOlderRow(
    loading: Boolean,
    failed: Boolean,
    offerManual: Boolean,
    onRetry: () -> Unit,
) {
    val colors = DsTheme.colors
    when {
        failed -> DsButton(
            text = stringResource(R.string.chat_load_older_retry),
            onClick = onRetry,
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )

        offerManual && !loading -> DsButton(
            text = stringResource(R.string.chat_load_older),
            onClick = onRetry,
            variant = DsButtonVariant.Ghost,
            size = DsButtonSize.Small,
            modifier = Modifier.fillMaxWidth(),
        )

        loading -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = colors.labelTertiary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.chat_loading_older),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }

        else -> Spacer(Modifier.height(1.dp))
    }
}

/** Placeholder bubbles while a session's history loads, instead of an empty white screen. */
@Composable
private fun TranscriptSkeleton(modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(0.55f, 0.9f, 0.75f, 0.4f).forEach { fraction ->
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .skeleton(colors.bgLayer2, colors.hover),
            )
        }
    }
}
