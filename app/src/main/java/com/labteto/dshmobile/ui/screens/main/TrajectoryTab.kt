package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.UserMessageNode
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
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The turn-by-turn ledger: what the agent did, in order, with its inputs and outputs.
 *
 * Where the Chat tab shows the conversation as it reads, this shows it as it *ran* — every tool
 * call with its arguments and result, grouped by turn. Design 12's timeline: a fixed 30dp rail with
 * the tool glyphs threaded on one hairline, the verb and its argument on the reading line, and the
 * elapsed time or diff size right-aligned where the eye lands last. Turn headers carry the step
 * count and the turn's own duration, so a 30-step turn is answerable at a glance.
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
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
        groups.forEach { turn ->
            item(key = "turn-${turn.turn}") {
                TurnHeader(turn)
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

@Composable
private fun TurnHeader(turn: LedgerTurn) {
    val colors = DsTheme.colors
    val steps = turn.nodes.count { it is ToolCallNode }
    val meta = buildList {
        add(pluralStringResource(R.plurals.trajectory_step_count, steps, steps))
        turn.durationMs?.let { add(formatDurationMs(it)) }
    }.joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DsSpacing.medium, bottom = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
private fun StepGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, running: Boolean = false, error: Boolean = false) {
    val colors = DsTheme.colors
    val fill = when {
        error -> colors.errorTertiary
        running -> colors.accentTertiary
        else -> colors.bgLayer3
    }
    val stroke = when {
        error -> colors.errorBorder
        running -> colors.accentBorder
        else -> colors.borderL3
    }
    val tint = when {
        error -> colors.error
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
        is AssistantMessageNode -> {
            val snippet = node.plainText.trim().take(240)
            if (snippet.isNotEmpty()) {
                StaticRow(icon = FeatherIcons.MessageSquare, first = first, last = last) {
                    Text(
                        snippet,
                        style = DsType.small13,
                        color = colors.labelTertiary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        is ToolCallNode -> {
            val result = siblings
                .filterIsInstance<ToolResultNode>()
                .firstOrNull { it.callId == node.callId }
            ToolLedgerRow(node, result, cwd, eventTimes, first, last)
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
 */
@Composable
private fun ToolLedgerRow(
    call: ToolCallNode,
    result: ToolResultNode?,
    cwd: String?,
    eventTimes: Map<Long, Long>,
    first: Boolean,
    last: Boolean,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val row = remember(call.callId, cwd) { toolRowModel(call.name, call.arguments, cwd) }
    var expanded by remember(call.callId) { mutableStateOf(false) }
    val running = result == null
    val failed = result?.isError == true
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
            )
        }
        Spacer(Modifier.width(DsSpacing.small))
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(DsShapes.row)
                .clickable(role = Role.Button) {
                    haptics.tap()
                    expanded = !expanded
                }
                .padding(vertical = DsSpacing.small, horizontal = DsSpacing.tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The verb, then its argument in monospace: the design's whole reading line.
            Text(
                row.title,
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
            val rotation by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = DsAnimations.chevron,
                label = "ledgerChevron",
            )
            Spacer(Modifier.width(DsSpacing.xsmall))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.ux_b_expanded else R.string.ux_b_collapsed),
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation },
                tint = colors.labelCaption,
            )
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = expanded,
        enter = androidx.compose.animation.expandVertically(DsAnimations.expand) +
            androidx.compose.animation.fadeIn(DsAnimations.fade),
        exit = androidx.compose.animation.shrinkVertically(DsAnimations.expand) +
            androidx.compose.animation.fadeOut(DsAnimations.fade),
    ) {
        // The body is indented past the rail so the reading line stays the only thing at the margin.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 30.dp + DsSpacing.small, top = DsSpacing.tiny, bottom = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Text(
                stringResource(R.string.chat_input_placeholder),
                style = DsType.caption11,
                color = colors.labelCaption,
            )
            JsonDisclosure(
                call.name,
                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(call.arguments) }
                    .getOrElse { kotlinx.serialization.json.JsonPrimitive(call.arguments) },
            )
            result?.content?.let { content ->
                Text(
                    stringResource(R.string.chat_output_placeholder),
                    style = DsType.caption11,
                    color = colors.labelCaption,
                )
                Text(
                    content.toString(),
                    style = DsType.mono12,
                    color = if (result.isError) colors.error else colors.labelTertiary,
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
