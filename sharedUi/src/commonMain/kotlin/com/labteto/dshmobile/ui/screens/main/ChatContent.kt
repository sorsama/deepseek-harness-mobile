package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.subagents_readonly
import com.labteto.dshmobile.ui.LocalSessionStore
import com.labteto.dshmobile.ui.adaptive.TabletopSplit
import com.labteto.dshmobile.ui.adaptive.readingGutter
import com.labteto.dshmobile.ui.adaptive.tabletopRegions
import com.labteto.dshmobile.ui.components.AnimatedPresence
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * What the composer offers that only some hosts can back, each one left out when null (as the [Composer]'s own
 * nullable entries are): Android's `+` sheet, `@` picker, file attach, clipboard paste and dictation. iOS has none of
 * them yet (#141). [onCursorChange] hears the caret after the composer has stored it (Android opens the `@` picker
 * when one is typed); [onOpenReference] opens a reference chip; [onRetryAttachment] starts a failed upload again.
 */
class ChatComposerExtras(
    val onOpenSheet: (() -> Unit)? = null,
    val onAddReference: (() -> Unit)? = null,
    val onOpenReference: (DraftReference) -> Unit = {},
    val onCursorChange: ((Int) -> Unit)? = null,
    val onRetryAttachment: (Int) -> Unit = {},
    val onPasteFiles: (() -> Boolean)? = null,
    val onAttachFile: (() -> Unit)? = null,
    val dictation: ComposerVoiceSource? = null,
)

/**
 * The chat surface below its chrome, the same on every platform: the transcript (or another tab), the persistent
 * docks, and the composer, over the shared store ([LocalSessionStore]) and sending through [actions].
 *
 * Everything below the tabs stays outside the tab swap on purpose — you can keep typing, and keep
 * answering an approval, while reading the trajectory, and the keyboard-attached surface never
 * animates out from under the cursor.
 *
 * The column pads itself for the system bars and the software keyboard (`safeDrawing`), so the composer rests on the
 * keyboard on Android (the window resizes) and on iOS (the view controller reports the keyboard as an inset). From two
 * wrapped lines the composer lifts into a drawer over the conversation (c2608b3): the transcript then keeps the
 * drawer's measured height as room at its foot, so its newest row rests above the drawer and history scrolls under it.
 *
 * What only a host can provide comes in as slots, each empty by default: [topBar] (the shared [ChatTopBar] with the
 * host's entries, told whether to draw its view tabs: they fold away while the transcript is read back, and stay
 * while [tabsLocked] or on any view but the transcript), [notices] under it, the tabs other than the transcript ([otherTab]), the approvals and questions
 * above the composer ([requests], weighted questions included), the `/` menu over it ([slashMenu]), the composer's
 * extras ([composerExtras]), and [overlay] over the whole surface (Android's drop highlight). [surfaceModifier] follows
 * the surface's own `fillMaxSize` (Android's drop target), [contentModifier] the transcript area's measuring, and
 * [composerBlockModifier] the composer block's (the plugin add-on layer's trays). [footerQuotaText] is the usage line
 * under the run statistics.
 *
 * [onLoadOlder] is told each time the transcript asks for the page before its oldest row, besides the load itself;
 * [onComposerLines] and [onComposerBlockHeight] report the lines the editor paints and the composer block's height.
 */
@Composable
fun ChatContent(
    composer: ComposerDraft,
    actions: ChatActions,
    toast: Pair<State<String?>, (String) -> Unit>,
    nodeContext: ChatNodeContext,
    topBar: @Composable (tabsVisible: Boolean) -> Unit,
    surfaceModifier: Modifier = Modifier,
    tab: ChatTab = ChatTab.Chat,
    /**
     * True while a sheet or panel is open over the chat: the bar then keeps its view tabs, however far
     * back the transcript has been read.
     */
    tabsLocked: Boolean = false,
    /**
     * The widest the transcript and composer grow before they centre in the pane. Unspecified keeps
     * them as wide as the pane, which is every phone.
     */
    readingWidth: Dp = Dp.Unspecified,
    /**
     * Set while a half-open horizontal hinge cuts the window: the transcript ends at the hinge and
     * everything the reader acts on (the docks, an approval, the composer) sits below it. In window
     * coordinates, because the hinge belongs to the device, not to this column.
     */
    tabletop: TabletopSplit? = null,
    followStreamingReply: Boolean = true,
    // Hoisted above the tab swap so the transcript keeps its scroll position across switches.
    chatListState: LazyListState = rememberLazyListState(),
    // What the transcript has already scrolled for, kept here for the same reason as its list state.
    transcriptMemory: TranscriptMemory = remember { TranscriptMemory() },
    jumpToSeq: Long? = null,
    onJumpConsumed: () -> Unit = {},
    onLoadOlder: () -> Unit = {},
    composerExtras: ChatComposerExtras = ChatComposerExtras(),
    footerQuotaText: String = "",
    contentModifier: Modifier = Modifier,
    composerBlockModifier: Modifier = Modifier,
    onComposerLines: (Int) -> Unit = {},
    onComposerBlockHeight: (Int) -> Unit = {},
    notices: @Composable ColumnScope.() -> Unit = {},
    otherTab: @Composable (tab: ChatTab, gutter: Dp) -> Unit = { _, _ -> },
    requests: @Composable ColumnScope.(gutter: Dp) -> Unit = {},
    slashMenu: @Composable ColumnScope.() -> Unit = {},
    overlay: @Composable () -> Unit = {},
) {
    val store = LocalSessionStore.current
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors

    val conversation by store.currentConversation.collectAsStateWithLifecycle()
    val currentSessionId by store.currentSessionId.collectAsStateWithLifecycle()
    // Null for an ordinary session, non-null while a subagent child is open. A child is the
    // harness's to drive, not the reader's: which controls still mean anything depends on it.
    val currentSubagent by store.currentSubagent.collectAsStateWithLifecycle()
    val loadingOlder by store.loadingOlder.collectAsStateWithLifecycle()
    val loadOlderFailed by store.loadOlderFailed.collectAsStateWithLifecycle()
    val permissions by store.permissions.collectAsStateWithLifecycle()
    val pendingPermission by store.pendingPermission.collectAsStateWithLifecycle()
    val sessionStats by store.sessionStats.collectAsStateWithLifecycle()
    val tokenUsage by store.tokenUsage.collectAsStateWithLifecycle()
    val contextBreakdown by store.contextBreakdown.collectAsStateWithLifecycle()
    val contextPressure by store.contextPressure.collectAsStateWithLifecycle()
    // The goal loop's round counter, which the projection does not carry; read once per journal.
    val goalRounds = remember(conversation?.journal) {
        goalRoundsStarted(conversation?.journal.orEmpty())
    }
    val attachments = composer.attachments
    // The view tabs fold away while the reader goes back through older messages and return when they come
    // back toward the newest; the decision lives here, next to the transcript's scroll, and the bar only draws it.
    val tabsFold = rememberChatTabsCollapse(
        listState = chatListState,
        active = tab == ChatTab.Chat && !tabsLocked,
        resetKey = currentSessionId,
    )

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .then(surfaceModifier),
        color = colors.bgBase,
    ) {
        // The activity draws edge to edge, so every top-level surface has to consume the insets
        // itself or the chrome ends up underneath the status bar. safeDrawing covers the status
        // bar, the gesture area and the keyboard in one modifier.
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            topBar(chatTabsVisible(tabsFold.collapsed, tab, tabsLocked))

            notices()

            // Where this column starts in the window, for a tabletop split: the hinge is a fact about
            // the device, so it arrives in window coordinates and is turned into this column's own.
            var contentTopPx by remember { mutableIntStateOf(0) }
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { contentTopPx = it.positionInWindow().y.toInt() }
                    .then(contentModifier),
            ) {
                // Keep transcript/question space even with a long draft. On very short screens,
                // reserve enough for reference chips, a line of text and the fixed action row.
                // The attachment tray sits above the card inside the composer's own height, so it
                // would eat the card's room if the caps ignored it. It is added on top of them
                // instead: a card with a tray keeps exactly the room it has without one.
                val trayAllowance = if (attachments.isNotEmpty()) AttachmentTrayHeight else 0.dp
                // On a wide pane the transcript and everything under it keep to a comfortable line
                // length and centre. The lists keep the whole pane to scroll in and take the margin
                // as padding, so a drag in the margin still scrolls; the blocks that are not lists
                // take the same margin as padding of their own.
                val gutter = readingGutter(maxWidth, readingWidth)
                // A half-open horizontal hinge divides this column: the transcript above it, the
                // controls below it. Null (no hinge, or no room to honour it) lays the column out
                // as it always was.
                val regions = tabletop?.let {
                    tabletopRegions(
                        columnTop = with(LocalDensity.current) { contentTopPx.toDp() },
                        columnHeight = maxHeight,
                        split = it,
                    )
                }
                // The most the composer may take while it has to stay in the half below the hinge.
                val bottomHalf = regions?.let { (maxHeight - it.topHeight - it.gap).coerceAtLeast(180.dp) }
                val composerMaxHeight = (maxHeight * 0.6f).coerceAtLeast(180.dp).capTo(bottomHalf) + trayAllowance
                // The lifted drawer's cap: 45% of what the keyboard left, floored at the 180dp that
                // fits a reference row, a line of text and the action row. Smaller than the in-flow
                // cap on purpose — a drawer that takes more room than the conversation it covers is
                // not a drawer any more.
                val composerDrawerHeight = (maxHeight * 0.45f).coerceAtLeast(180.dp).capTo(bottomHalf) + trayAllowance
                // Two wrapped lines is where the drawer and the full-screen button both appear. The
                // composer measures that (it is the only thing that knows how the text wrapped);
                // this is only what it reported.
                var composerLines by remember { mutableIntStateOf(1) }
                val composerLifted = composerLines > 1
                val density = LocalDensity.current
                // The composer block is composed once, here, however it is shown. In flow the
                // transcript reserves exactly the height the block measured, so the conversation
                // still ends where it always ended; lifted it reserves nothing and the block floats
                // above the conversation rather than growing into it. Measuring is the point: a
                // reserved height that drifts from the card's is how the composer came to hide its
                // own action row (#87).
                var composerBlockPx by remember { mutableIntStateOf(0) }
                // Lifted, the block floats over the conversation instead of taking room from it, so
                // the newest row used to end underneath the drawer and no scroll could bring it out:
                // the list was already at its floor. Give the transcript the block's measured height
                // as a bottom inset, which is exactly the room the block took in flow — the tail
                // rests above the drawer, and history still scrolls under it. A tabletop split keeps
                // the transcript above the hinge, where the drawer cannot reach it.
                val transcriptBottomInset = if (composerLifted && regions == null) {
                    with(density) { composerBlockPx.toDp() }
                } else {
                    0.dp
                }
                Column(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        (
                            slideInHorizontally { width -> if (forward) width / 6 else -width / 6 } +
                                fadeIn(DsAnimations.fade)
                            )
                            .togetherWith(fadeOut(DsAnimations.fade)) using SizeTransform(clip = false)
                    },
                    modifier = if (regions != null) Modifier.height(regions.topHeight) else Modifier.weight(1f),
                    label = "chatTab",
                ) { current ->
                    when (current) {
                        ChatTab.Chat -> ChatTranscript(
                            conversation = conversation,
                            loading = conversation == null && currentSessionId != null,
                            loadingOlder = loadingOlder,
                            loadOlderFailed = loadOlderFailed,
                            context = nodeContext,
                            listState = chatListState,
                            modifier = Modifier.nestedScroll(tabsFold.connection),
                            gutter = gutter,
                            bottomInset = transcriptBottomInset,
                            onLoadOlder = {
                                onLoadOlder()
                                scope.launch { store.loadOlder() }
                            },
                            jumpToSeq = jumpToSeq,
                            onJumpConsumed = onJumpConsumed,
                            sentTick = actions.sentTick,
                            followStreaming = followStreamingReply,
                            memory = transcriptMemory,
                            onSuggestion = if (currentSessionId != null) {
                                { text -> composer.text = text; composer.selection = text.length }
                            } else {
                                null
                            },
                        )
                        else -> otherTab(current, gutter)
                    }
                }

                // Everything under the transcript. In flow it continues this column; with a tabletop
                // hinge it is the bottom half, anchored to the foot of the column so the docks sit
                // directly above the composer however much room the half has.
                val belowTranscript: @Composable ColumnScope.() -> Unit = {
                conversation?.let { conv ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp + gutter),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Each dock slides in and out on its own, so one arriving mid-turn eases the
                        // transcript up rather than shoving it by a whole row in a single frame.
                        AnimatedPresence(parseTodos(conv.projections["todos"])?.takeIf { it.isNotEmpty() }) { TodoDock(it) }
                        // A goal's controls are `goals/*` calls, refused for a child session; the
                        // bar states nothing the agent chat can act on, so it is not drawn there.
                        AnimatedPresence(parseGoal(conv.projections["goal"]).takeIf { currentSubagent == null }) {
                            GoalBar(it, store, roundsStarted = goalRounds)
                        }
                        // Queue mutations go through `session/updateQueue`, which the host accepts
                        // only for a live continuable child; a read-only agent's dock states what
                        // is pending without offering verbs that can only be refused.
                        AnimatedPresence(conv.queue.takeIf { it.isNotEmpty() }) {
                            QueueDock(
                                it,
                                store,
                                running = conv.running,
                                blocked = currentSubagent?.continuable == false,
                            )
                        }
                    }
                }

                // Server-initiated requests take over the bottom of the screen: they block the turn,
                // so burying them behind a scroll would strand the session. Weighted questions are
                // measured after the composer, so long questions cannot consume its controls.
                requests(gutter)

                // Reserved for the composer block drawn below: the conversation ends exactly where
                // the block starts, which is the space the composer took when it was a child of
                // this column. Lifted, it reserves nothing and the drawer floats over the
                // conversation instead.
                Spacer(Modifier.height(if (composerLifted) 0.dp else with(density) { composerBlockPx.toDp() }))
                }
                if (regions != null) {
                    // The hinge: a hairline where the device folds, so the two halves read as two
                    // surfaces rather than a transcript that stops for no reason.
                    val rule = colors.borderL2
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(regions.gap)
                            .drawBehind {
                                val y = size.height / 2f
                                drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                            },
                    )
                    Column(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.Bottom,
                        content = belowTranscript,
                    )
                } else {
                    belowTranscript()
                }
                }

                // The composer and the two things that belong directly above it, anchored to the
                // bottom of the conversation. Composed here once whatever state it is in; only its
                // cap and its placement change between the two.
                Column(
                    Modifier.fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = gutter)
                        .onSizeChanged {
                            composerBlockPx = it.height
                            onComposerBlockHeight(it.height)
                        }
                        .then(composerBlockModifier),
                ) {
                    // The `/` autocomplete sits where the web's does: directly above the composer,
                    // only while the draft is a slash token being typed.
                    slashMenu()

                    // A child whose mode is `one-shot`, and one whose own descriptor has not arrived
                    // yet, refuses every prompt. Saying so above the composer beats a dead field that
                    // looks like a loading state: the transcript stays readable either way.
                    if (currentSubagent?.continuable == false) {
                        Text(
                            stringResource(Res.string.subagents_readonly),
                            style = DsType.caption11,
                            color = colors.labelTertiary,
                            modifier = Modifier.padding(horizontal = DsSpacing.medium),
                        )
                    }

                    Composer(
                        // In flow the composer may grow to 60% of what the keyboard left, because
                        // the conversation above it still has the rest. Lifted it covers the
                        // conversation, so it stops at 45%: the drawer is a place to write, not a
                        // page that hides what is being answered.
                        modifier = Modifier.heightIn(max = if (composerLifted) composerDrawerHeight else composerMaxHeight),
                        lifted = composerLifted,
                        onLineCountChange = {
                            composerLines = it
                            onComposerLines(it)
                        },
                        references = composer.references,
                        onAddReference = composerExtras.onAddReference,
                        onOpenReference = composerExtras.onOpenReference,
                        draft = composer.text,
                        onDraftChange = { composer.text = it },
                        cursor = composer.selection,
                        onCursorChange = {
                            composer.selection = it
                            composerExtras.onCursorChange?.invoke(it)
                        },
                        attachments = attachments,
                        onRemoveAttachment = { index -> attachments.removeAt(index) },
                        onRetryAttachment = composerExtras.onRetryAttachment,
                        // The permission preset is changed through `/permission`, a command the host
                        // resolves like any other agent operation — refused for a child session. No
                        // chip rather than a chip whose only outcome is an error.
                        permissions = permissions.takeIf { currentSubagent == null },
                        pendingPermission = pendingPermission,
                        onPermissionPick = { value -> actions.setPermission(value) },
                        contextBreakdown = contextBreakdown,
                        contextPressure = contextPressure,
                        // Stop on a child is the subagent interrupt, which only exists for a
                        // continuable one; offering the button on a one-shot child promised a route
                        // the host does not have.
                        running = conversation?.running == true && (currentSubagent?.continuable != false),
                        // Only an ordinary session and a continuable child can be prompted; every
                        // other child refuses, and the notice above the composer says so.
                        enabled = currentSessionId != null && (currentSubagent?.continuable != false),
                        // A send in flight holds the buttons but not the text field, so the keyboard
                        // stays up between messages.
                        submitting = composer.submitting,
                        preparing = composer.preparing,
                        onOpenSheet = composerExtras.onOpenSheet,
                        // A lambda, not a function reference. The composer holds this through rememberUpdatedState,
                        // which keeps what it has when the new value is equal to it, and a reference to a
                        // local function equals every other reference to that function whatever it
                        // captured. Each session's `::send` compared equal to the first and was dropped, so
                        // the button went on sending with the attachment list of whichever session was
                        // open when this screen first composed. A lambda is rebuilt when what it captures
                        // changes and compares by identity, so the composer always holds the current one.
                        onSend = { text -> actions.send(text) },
                        onSendWith = { text, delivery -> actions.send(text, delivery) },
                        onStop = { actions.stop() },
                        focusTick = actions.focusTick,
                        onPasteFiles = composerExtras.onPasteFiles,
                        onAttachFile = composerExtras.onAttachFile,
                        dictation = composerExtras.dictation,
                    )

                    // The footer belongs under the card it describes and stays there while the card
                    // is in flow. Lifted, the drawer covers the space it would take, and a line of
                    // numbers under a floating card reads as a mistake; it comes back with the
                    // one-line composer.
                    if (!composerLifted) {
                        StatsFooter(
                            stats = sessionStats, usage = tokenUsage,
                            quotaText = footerQuotaText,
                        )
                    }
                }
            }
        }
        overlay()
        DsToastHost(toast, modifier = Modifier.fillMaxWidth())
    }
}

/** This height, held to [limit] when there is one: the room a composer has in the half below a hinge. */
private fun Dp.capTo(limit: Dp?): Dp = if (limit == null) this else minOf(this, limit)
