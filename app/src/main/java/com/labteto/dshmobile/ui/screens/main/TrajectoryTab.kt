package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.labteto.dshmobile.ui.components.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.TrajectoryRecord
import com.labteto.dshmobile.core.session.TrajectoryTimeline
import com.labteto.dshmobile.core.session.TurnTiming
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.session.deriveTrajectoryTimeline
import com.labteto.dshmobile.core.session.isToolAbortError
import com.labteto.dshmobile.core.session.lineDiff
import com.labteto.dshmobile.core.wire.dto.SessionStatsView
import com.labteto.dshmobile.core.wire.dto.TokenUsageView
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.ToolCardView
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.components.formatTokens
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The turn-by-turn ledger: what the agent did, in order, with its inputs and outputs.
 *
 * Where the Chat tab shows the conversation as it reads, this shows it as it *ran* — every tool
 * call with its arguments and result, grouped by turn. Design 12's timeline: a fixed 30dp rail with
 * the tool glyphs threaded on one hairline, the verb and its argument on the reading line, and the
 * elapsed time or diff size right-aligned where the eye lands last. Turn headers carry the step
 * count and the turn's own duration, so a 30-step turn is answerable at a glance, and under them one
 * stacked bar and a legend of where that turn's time went (model, tools, waiting for approval).
 * Tapping a turn opens its records; tapping a model reply or a tool call opens the record inspector
 * ([TrajectorySheet]). A card above the turns adds the loaded session up.
 *
 * Timings come from the journal's event times, which the fold keeps on the wire envelope rather
 * than on the nodes; a session with no journal (or events without times) simply omits them.
 */
@Composable
internal fun TrajectoryTab(
    conversation: ConversationSnapshot?,
    stats: SessionStatsView?,
    usage: TokenUsageView?,
    cwd: String?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    /** Extra room at each side, so a wide pane keeps the ledger to a reading width. */
    gutter: Dp = 0.dp,
    loadingOlder: Boolean = false,
    loadOlderFailed: Boolean = false,
    onLoadOlder: () -> Unit = {},
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val journal = conversation?.journal.orEmpty()
    val nodes = remember(conversation?.journal, conversation?.nodes) {
        if (conversation?.journal.isNullOrEmpty()) conversation?.nodes.orEmpty()
        else com.labteto.dshmobile.core.session.EventFold(conversation!!.sessionId).fold(
            conversation.journal.map { it.copy(surfaceOp = null, surfaceIntent = null) },
        ).nodes
    }
    val eventTimes = remember(journal) { journal.associate { it.seq to it.time } }
    // Structural markers are dropped before grouping, not just drawn as nothing: an empty row
    // still costs the list's spacing, and a turn of tool calls would gain a gap around each one.
    var rawOpen by rememberSaveable { mutableStateOf(false) }
    // Where the time went, for every turn, derived once per journal change and off the main thread:
    // a long session has thousands of records, and the previous result stays on screen until the
    // next one lands. The key is the journal's shape rather than the list itself, because the journal
    // is append-only and comparing two long lists element by element would cost as much as the work.
    val journalKey = Triple(journal.size, journal.firstOrNull()?.seq, journal.lastOrNull()?.seq)
    var derived by remember { mutableStateOf<TrajectoryTimeline?>(null) }
    LaunchedEffect(journalKey) {
        derived = withContext(Dispatchers.Default) { deriveTrajectoryTimeline(journal) }
    }
    val timeline = derived ?: TrajectoryTimeline.EMPTY
    val turnTimings = remember(timeline) { timeline.turns.associateBy { it.turn } }
    val recordIndex = remember(timeline) { timeline.recordIndex() }
    var sheet by remember { mutableStateOf<TrajectorySheetState?>(null) }
    val groups = remember(nodes, eventTimes) {
        groupByTurn(nodes.filterNot { it is com.labteto.dshmobile.core.session.OtherNode && it.type in STRUCTURAL_EVENT_TYPES })
            .map { (turn, turnNodes) ->
                LedgerTurn(
                    turn = turn,
                    nodes = turnNodes,
                    rows = ledgerRows(turnNodes),
                    durationMs = turnDuration(turnNodes, eventTimes),
                )
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp + gutter, end = 16.dp + gutter, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // The ledger shows what the transcript has loaded, and Chat pages history in as you
        // scroll up. Nothing scrolls the ledger's top into a fetch, so earlier turns were simply
        // missing here until Chat had been scrolled back far enough. This row asks for them.
        if (conversation?.hasMore == true) {
            item(key = "load-older") {
                Box(Modifier.padding(bottom = DsSpacing.small)) {
                    LoadOlderRow(loading = loadingOlder, failed = loadOlderFailed, offerManual = true, onRetry = onLoadOlder)
                }
            }
        }
        if (groups.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(top = DsSpacing.xxlarge)) {
                    EmptyHero(
                        headline = stringResource(R.string.trajectory_empty),
                        subtitle = stringResource(R.string.ink_panel_trajectory_empty_hint),
                    )
                }
            }
        }
        // The loaded session at a glance, above the turns it adds up.
        if (timeline.turns.isNotEmpty()) {
            item(key = "timing-summary") {
                SessionTimingCard(timeline.totals, partial = conversation?.hasMore == true)
            }
        }
        groups.forEach { turn ->
            item(key = "turn-${turn.turn}") {
                val timing = turnTimings[turn.turn]
                TurnHeader(
                    turn = turn,
                    timing = timing,
                    onOpen = { sheet = TrajectorySheetState.Turn(turn.turn) },
                )
            }
            items(
                count = turn.rows.size,
                key = { index -> "n-${turn.rows[index].seq}" },
            ) { index ->
                val row = turn.rows[index]
                val first = index == 0
                val last = index == turn.rows.lastIndex
                when (row) {
                    is LedgerRow.Node -> TrajectoryRow(
                        node = row.node,
                        siblings = turn.nodes,
                        cwd = cwd,
                        eventTimes = eventTimes,
                        recordIndex = recordIndex,
                        onOpenRecord = { key -> sheet = TrajectorySheetState.Record(key, fromTurn = null) },
                        first = first,
                        last = last,
                    )
                    is LedgerRow.Context -> ContextLedgerRow(
                        nodes = row.nodes,
                        cwd = cwd,
                        first = first,
                        last = last,
                    )
                }
            }
        }
        if (stats != null || usage != null) {
            item(key = "totals") {
                Spacer(Modifier.width(8.dp))
                TrajectoryTotals(stats, usage)
            }
        }
        // Every event as the host logged it, for the rare reader who needs the wire. Collapsed by
        // default and after the totals: open, it is hundreds of rows, and with no heading the
        // ledger above seemed to turn into a debug dump halfway down.
        if (journal.isNotEmpty()) {
            item(key = "raw-header") {
                DisclosureRow(
                    title = stringResource(R.string.trajectory_tab_raw),
                    summary = journal.size.toString(),
                    icon = FeatherIcons.List,
                    expanded = rawOpen,
                    onToggle = {
                        haptics.tap()
                        rawOpen = !rawOpen
                    },
                    modifier = Modifier.padding(top = DsSpacing.medium),
                )
            }
            if (rawOpen) {
                journal.forEach { event ->
                    item(key = "raw-${event.seq}") {
                        JsonDisclosure(
                            "#${event.seq} · ${event.type} · ${
                                java.text.DateFormat.getTimeInstance().format(java.util.Date(event.time))
                            }",
                            kotlinx.serialization.json.buildJsonObject {
                                put("data", event.data)
                                event.surfaceIntent?.let { put("surfaceOp", it) }
                                event.sourceEventSeqs?.let { seqs ->
                                    put(
                                        "sourceEventSeqs",
                                        kotlinx.serialization.json.JsonArray(seqs.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                                    )
                                }
                                event.ignorable?.let { put("ignorable", kotlinx.serialization.json.JsonPrimitive(it)) }
                            },
                        )
                    }
                }
            }
        }
    }
    sheet?.let { state ->
        TrajectorySheet(
            state = state,
            timeline = timeline,
            records = recordIndex,
            journal = journal,
            onNavigate = { sheet = it },
            onDismiss = { sheet = null },
        )
    }
}

/** One turn of the ledger: its nodes, and the rows drawn for them. */
private data class LedgerTurn(
    val turn: Int,
    val nodes: List<ChatNode>,
    val rows: List<LedgerRow>,
    val durationMs: Long?,
)

/** A ledger row: one node, or a run of harness context drawn as Chat draws it. */
private sealed interface LedgerRow {
    val seq: Long
    data class Node(val node: ChatNode) : LedgerRow { override val seq get() = node.seq }
    data class Context(val nodes: List<ChatNode>) : LedgerRow { override val seq get() = nodes.first().seq }
}

/** The turn's nodes with each run of harness context folded into one row. */
private fun ledgerRows(nodes: List<ChatNode>): List<LedgerRow> {
    val out = mutableListOf<LedgerRow>()
    var run = mutableListOf<ChatNode>()
    fun flush() { if (run.isNotEmpty()) { out += LedgerRow.Context(run.toList()); run = mutableListOf() } }
    nodes.forEach { node -> if (isHarnessContext(node)) run += node else { flush(); out += LedgerRow.Node(node) } }
    flush()
    return out
}

/** The span between the first and last event of a turn, when the journal carries both. */
private fun turnDuration(nodes: List<ChatNode>, eventTimes: Map<Long, Long>): Long? {
    val times = nodes.mapNotNull { eventTimes[it.seq] }
    if (times.size < 2) return null
    return (times.max() - times.min()).coerceAtLeast(0)
}

/**
 * What the harness put in front of the model rather than what anyone typed: its system prompt,
 * the request context, and the runtime, skill and time snapshots it injects as user messages.
 * Chat folds these into one "Context" row; the ledger used to print each as a `> ` line, which
 * read as though the person had written a page of system text before every turn.
 */
private fun isHarnessContext(node: ChatNode): Boolean =
    (node is UserMessageNode && node.isInjectedContext && node.sourceKind != com.labteto.dshmobile.core.session.REWIND_SOURCE_KIND) ||
        (node is com.labteto.dshmobile.core.session.OtherNode && node.type in setOf("system/message", "request/context"))

// ---------------------------------------------------------------------------
// Timeline chrome
// ---------------------------------------------------------------------------

/**
 * A turn's header: its name, its step count and duration, and, when the journal recorded where its
 * time went, one stacked bar and the legend that spells it out.
 *
 * The whole block is one 48dp+ button that opens the turn's records. The bar is not tapped on its own:
 * a 12dp strip is no touch target, and "open this turn" is the one thing a tap on any part of it means.
 */
@Composable
private fun TurnHeader(turn: LedgerTurn, timing: TurnTiming?, onOpen: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val steps = turn.nodes.count { it is ToolCallNode }
    // The turn's own start-to-end time when the harness recorded both, else the span of its events.
    val duration = timing?.wallMs ?: turn.durationMs
    val meta = buildList {
        add(pluralStringResource(R.plurals.trajectory_step_count, steps, steps))
        if (duration != null) add(formatDurationMs(duration)) else if (timing?.open == true) add(stringResource(R.string.jobs_running))
    }.joinToString(" · ")
    val openLabel = stringResource(R.string.traj_overview_open, turn.turn)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DsSpacing.small, bottom = DsSpacing.xsmall)
            .clip(DsShapes.row)
            .then(
                if (timing != null) {
                    Modifier.clickable(onClickLabel = openLabel, role = Role.Button) {
                        haptics.tap()
                        onOpen()
                    }
                } else {
                    Modifier
                },
            )
            .heightIn(min = DsSpacing.touchTarget)
            .padding(vertical = DsSpacing.xsmall),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.trajectory_turn, turn.turn),
                style = DsType.rowTitle,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                meta,
                style = DsType.mono12,
                color = colors.accent,
                maxLines = 1,
            )
            if (timing != null) {
                Spacer(Modifier.width(DsSpacing.xsmall))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.labelCaption,
                )
            }
        }
        if (timing != null && timing.slices.isNotEmpty()) {
            TimingBar(timing.slices)
            TimingLegend(timing.slices)
        }
    }
}

/**
 * The left rail every row shares: one hairline down the row through the middle of the glyph column,
 * cut at the first and last row of a turn so it starts and stops at the glyphs it threads.
 */
private fun Modifier.timelineRail(first: Boolean, last: Boolean, lineColor: Color): Modifier = drawBehind {
    val x = 15.dp.toPx()
    val startY = if (first) size.height / 2f else 0f
    val endY = if (last) size.height / 2f else size.height
    if (endY > startY) {
        drawLine(
            color = lineColor,
            start = Offset(x, startY),
            end = Offset(x, endY),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/** The glyph column: fixed width so every rail lines up, centred so the rail meets the glyph. */
@Composable
private fun GlyphColumn(icon: @Composable () -> Unit) {
    Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) { icon() }
}

/** The 28dp rounded glyph holder of the design's timeline. */
@Composable
private fun StepGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    running: Boolean = false,
    error: Boolean = false,
    stopped: Boolean = false,
) {
    val colors = DsTheme.colors
    val fill = when {
        error -> colors.errorTertiary
        stopped -> colors.warnTertiary
        running -> colors.accentTertiary
        else -> colors.bgLayer3
    }
    val stroke = when {
        error -> colors.errorBorder
        stopped -> colors.warnBorder
        running -> colors.accentBorder
        else -> colors.borderL3
    }
    val tint = when {
        error -> colors.error
        stopped -> colors.warnText
        running -> colors.accent
        else -> colors.labelSecondary
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(DsShapes.chip)
            .background(fill)
            .border(1.dp, stroke, DsShapes.chip),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = tint)
    }
}

/** A row with no expandable body: one line on the rail, used for messages and context. */
@Composable
private fun StaticRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    first: Boolean,
    last: Boolean,
    text: @Composable () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .timelineRail(first, last, colors.borderL2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphColumn { StepGlyph(icon) }
        Spacer(Modifier.width(DsSpacing.small))
        Box(Modifier.weight(1f).padding(vertical = DsSpacing.xsmall)) { text() }
    }
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

@Composable
private fun TrajectoryRow(
    node: ChatNode,
    siblings: List<ChatNode>,
    cwd: String?,
    eventTimes: Map<Long, Long>,
    recordIndex: Map<String, TrajectoryRecord>,
    onOpenRecord: (String) -> Unit,
    first: Boolean,
    last: Boolean,
) {
    val colors = DsTheme.colors
    when (node) {
        is UserMessageNode -> {
            val preview = node.previewText.trim()
            if (preview.isNotEmpty()) {
                StaticRow(icon = FeatherIcons.MessageSquare, first = first, last = last) {
                    Text(
                        preview,
                        style = DsType.small13,
                        color = colors.labelSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        is AssistantMessageNode -> ModelStepRow(node, recordIndex, onOpenRecord, first, last)
        is ToolCallNode -> {
            val result = siblings
                .filterIsInstance<ToolResultNode>()
                .firstOrNull { it.callId == node.callId }
            ToolLedgerRow(node, result, cwd, eventTimes, recordIndex["t:${node.callId}"], onOpenRecord, first, last)
        }
        // Step boundaries are already counted in the turn header; as rows they put a
        // `step/start` and a `step/end` around every tool call in the ledger. The changes marker
        // reads as the turn's changed files, as it does in Chat.
        is com.labteto.dshmobile.core.session.OtherNode -> when {
            node.type in STRUCTURAL_EVENT_TYPES -> Unit
            node.type == "workspace/changes" && changesTurn(node.data) != null ->
                StaticRow(icon = FeatherIcons.Edit3, first = first, last = last) {
                    ChangedFilesRow(siblings, changesTurn(node.data)!!, cwd)
                }
            else -> StaticRow(icon = FeatherIcons.List, first = first, last = last) {
                JsonDisclosure(node.type, node.data)
            }
        }
        else -> Unit
    }
}

/**
 * One model reply: the start of what it said, how long it took, and a tap that opens its inspector.
 *
 * A reply that was only tool calls has no text, and it used to have no row. It has one now, because
 * the reply is a record with a time to first token, a duration and a token count worth opening, and
 * the tool calls under it read as what it asked for.
 */
@Composable
private fun ModelStepRow(
    node: AssistantMessageNode,
    recordIndex: Map<String, TrajectoryRecord>,
    onOpenRecord: (String) -> Unit,
    first: Boolean,
    last: Boolean,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val turn = node.turn
    val step = node.step
    val record = if (!node.streaming && turn != null && step != null) recordIndex["m:$turn:$step"] else null
    val snippet = node.plainText.trim().take(240)
    if (snippet.isEmpty() && record == null) return
    val duration = record?.durationMs
    StaticRow(icon = FeatherIcons.MessageSquare, first = first, last = last) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (record != null) {
                        Modifier
                            .clip(DsShapes.row)
                            .clickable(role = Role.Button) {
                                haptics.tap()
                                onOpenRecord(record.key)
                            }
                    } else {
                        Modifier
                    },
                )
                .heightIn(min = DsSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Text(
                snippet.ifEmpty { stringResource(R.string.traj_insp_kind_model) },
                style = DsType.small13,
                color = colors.labelTertiary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (duration != null) {
                Text(formatDurationMs(duration), style = DsType.mono12, color = colors.labelTertiary, maxLines = 1)
            }
            if (record != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.labelCaption,
                )
            }
        }
    }
}

@Composable
private fun ContextLedgerRow(nodes: List<ChatNode>, cwd: String?, first: Boolean, last: Boolean) {
    val colors = DsTheme.colors
    StaticRow(icon = FeatherIcons.Layers, first = first, last = last) {
        ContextGroupRow(
            nodes,
            ChatNodeContext(nodes, false, cwd, onOpenSubagent = {}, onBranchFrom = {}, onFeedback = { _, _ -> }),
        )
        // ContextGroupRow keeps its own header; the row above only supplies the rail glyph.
        @Suppress("UNUSED_EXPRESSION")
        colors
    }
}

/**
 * One tool call: glyph on the rail, verb and argument on the reading line, and the outcome's own
 * measure — elapsed time, or the size of the edit — right-aligned where the design puts it.
 *
 * Tapping the row opens the record inspector, which shows the call's input and output with Show all
 * and Copy, its timing and its raw events; that replaces the short inline preview the row used to
 * unfold, which could show neither a long result nor how long the call waited for approval.
 */
@Composable
private fun ToolLedgerRow(
    call: ToolCallNode,
    result: ToolResultNode?,
    cwd: String?,
    eventTimes: Map<Long, Long>,
    record: TrajectoryRecord?,
    onOpenRecord: (String) -> Unit,
    first: Boolean,
    last: Boolean,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val row = remember(call.callId, cwd) { toolRowModel(call.name, call.arguments, cwd) }
    val running = result == null
    // A command the user stopped is its own outcome: not red, not "failed" (see isToolAbortError).
    val stopped = result != null && result.isError && isToolAbortError(result.errorCode, result.errorName)
    val failed = result != null && result.isError && !stopped
    val startedAt = eventTimes[call.seq]
    val endedAt = result?.let { eventTimes[it.seq] }
    val elapsed = if (startedAt != null && endedAt != null) {
        formatDurationMs((endedAt - startedAt).coerceAtLeast(0))
    } else {
        null
    }
    // An edit's size is its most useful fact, more than its duration: it says what the turn did to
    // the tree without opening a diff.
    val diff = remember(call.callId, result) {
        runCatching { buildToolCardView(call, result, running, cwd) }.getOrNull() as? ToolCardView.DiffCard
    }?.let { card -> diffStats(card.diffs) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .timelineRail(first, last, colors.borderL2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphColumn {
            StepGlyph(
                icon = row.variant.featherIcon(),
                running = running,
                error = failed,
                stopped = stopped,
            )
        }
        Spacer(Modifier.width(DsSpacing.small))
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(DsShapes.row)
                .then(
                    if (record != null) {
                        Modifier.clickable(role = Role.Button) {
                            haptics.tap()
                            onOpenRecord(record.key)
                        }
                    } else {
                        Modifier
                    },
                )
                .heightIn(min = DsSpacing.touchTarget)
                .padding(vertical = DsSpacing.small, horizontal = DsSpacing.tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The verb, then its argument in monospace: the design's whole reading line.
            Text(
                shellToolTitle(call.name) ?: row.title,
                style = DsType.std14Strong,
                color = if (failed) colors.error else colors.labelPrimary,
                maxLines = 1,
            )
            if (row.summary != null) {
                Spacer(Modifier.width(DsSpacing.xsmall))
                Text(
                    row.summary,
                    style = DsType.mono13,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            when {
                // The word and its icon, so a stop is never told by colour alone; an edit that was
                // stopped changed nothing, so its size is not shown either.
                stopped -> StoppedLabel()
                diff != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "+${diff.first}",
                        style = DsType.mono12,
                        color = colors.success,
                        maxLines = 1,
                    )
                    if (diff.second > 0) {
                        Spacer(Modifier.width(DsSpacing.xsmall))
                        Text(
                            "\u2212${diff.second}",
                            style = DsType.mono12,
                            color = colors.error,
                            maxLines = 1,
                        )
                    }
                }
                running -> Text(
                    stringResource(R.string.jobs_running),
                    style = DsType.mono12,
                    color = colors.accent,
                    maxLines = 1,
                )
                elapsed != null -> Text(
                    elapsed,
                    style = DsType.mono12,
                    color = colors.labelTertiary,
                    maxLines = 1,
                )
            }
            if (record != null) {
                Spacer(Modifier.width(DsSpacing.xsmall))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.labelCaption,
                )
            }
        }
    }
}

/** Added/removed line counts across a diff card's hunks. */
private fun diffStats(diffs: List<com.labteto.dshmobile.ui.components.DiffHunk>): Pair<Int, Int> {
    var added = 0
    var removed = 0
    diffs.forEach { hunk ->
        val lines = lineDiff(hunk.oldText, hunk.newText)
        added += lines.count { it.kind == '+' }
        removed += lines.count { it.kind == '-' }
    }
    return added to removed
}

@Composable
private fun TrajectoryTotals(stats: SessionStatsView?, usage: TokenUsageView?) {
    val colors = DsTheme.colors
    DsCard(
        modifier = Modifier.padding(top = DsSpacing.large),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        Text(
            stringResource(R.string.trajectory_tab_usage).uppercase(),
            style = DsType.label12,
            color = colors.labelTertiary,
        )
        stats?.let {
            Text(
                pluralStringResource(R.plurals.chat_stats_turn_count, it.turns, it.turns) + " · " +
                    pluralStringResource(R.plurals.trajectory_step_count, it.steps, it.steps),
                style = DsType.mono12,
                color = colors.labelSecondary,
            )
            Text(
                stringResource(
                    R.string.chat_stats_timing,
                    formatDurationMs(it.llmMs),
                    formatDurationMs(it.toolMs),
                ),
                style = DsType.mono12,
                color = colors.labelTertiary,
            )
            // Both numbers come from the first steps of a run; until they exist the line is left out.
            val ttftMs = it.meanTtftMs
            val rate = it.tokensPerSecond
            if (ttftMs != null && rate != null) {
                Text(
                    stringResource(
                        R.string.chat_stats_speed,
                        formatDurationMs(ttftMs),
                        String.format(java.util.Locale.US, "%.0f", rate),
                    ),
                    style = DsType.mono12,
                    color = colors.labelTertiary,
                )
            }
        }
        usage?.let {
            Text(
                stringResource(
                    R.string.trajectory_usage,
                    formatTokens(it.inputTokens),
                    formatTokens(it.outputTokens),
                    formatTokens(it.cacheReadTokens),
                    formatTokens(it.cacheWriteTokens),
                ),
                style = DsType.mono12,
                color = colors.labelTertiary,
            )
        }
    }
}
