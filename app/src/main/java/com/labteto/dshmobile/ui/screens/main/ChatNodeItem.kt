package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.CommandNode
import com.labteto.dshmobile.core.session.CompactionNode
import com.labteto.dshmobile.core.session.DeveloperMessageNode
import com.labteto.dshmobile.core.session.GoalNode
import com.labteto.dshmobile.core.session.OtherNode
import com.labteto.dshmobile.core.session.PlanModeNode
import com.labteto.dshmobile.core.session.RetryNode
import com.labteto.dshmobile.core.session.SubagentNode
import com.labteto.dshmobile.core.session.TitleNode
import com.labteto.dshmobile.core.session.TodoNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.session.TurnErrorNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.session.WorkflowNode
import com.labteto.dshmobile.ui.components.AttachmentImage
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DisclosureState
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.HoldAction
import com.labteto.dshmobile.ui.components.HoldMenu
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.components.SelectTextDialog
import com.labteto.dshmobile.ui.components.clickableWithHold
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.ThinkingPanel
import com.labteto.dshmobile.ui.components.ToolCard
import com.labteto.dshmobile.ui.components.UserBubble
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Everything one transcript row needs that is not on the node itself. */
internal data class ChatNodeContext(
    val nodes: List<ChatNode>,
    val running: Boolean,
    val cwd: String?,
    /** Host account home, used only to abbreviate a leftover home-rooted path as `~`. */
    val home: String? = null,
    val onOpenSubagent: (String) -> Unit,
    val onBranchFrom: (Long) -> Unit,
    val onFeedback: (Long, Boolean) -> Unit,
    val eventTimes: Map<Long, Long> = emptyMap(),
    /** Puts a sent prompt's text back in the composer; null where there is no composer. */
    val onEditPrompt: ((String) -> Unit)? = null,
    /**
     * Rewinds the session to a user message, by sequence and preview. Null unless the rewind
     * plugin is installed and the session is idle.
     */
    val onRewindTo: ((Long, String) -> Unit)? = null,
    /** Adds an `@` mention of a workspace path to the composer; null where there is no composer. */
    val onMention: ((path: String, folder: Boolean) -> Unit)? = null,
    /**
     * Where assistant messages keep their open/closed state so it survives a reply settling. Null
     * falls back to state local to the row, which is right where the rows are short-lived.
     */
    val assistantMemory: AssistantMemory? = null,
)

/**
 * One node of the conversation. The `when` is exhaustive over [ChatNode] on purpose: a harness that
 * grows a new event type still renders, because the fold produces an `OtherNode` rather than
 * dropping it, and this shows it rather than a gap in the transcript.
 */
@Composable
internal fun ChatNodeItem(node: ChatNode, context: ChatNodeContext) {
    val colors = DsTheme.colors
    when (node) {
        // Turn boundaries are structure, not content — the transcript shows the work, not the frame.
        is TurnStartNode -> Unit

        is UserMessageNode -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            node.blocks.filter { it.kind == "image" }.forEach { block ->
                parseImageRef(block)?.let { ref ->
                    AttachmentImage(ref = ref, contentDescription = ref.name)
                }
            }
            // A file is a chip, not a preview: the host stores it verbatim and the model reads it
            // through its file tools, so there is nothing to render but what it is called.
            node.blocks.filter { it.kind == "file" }.forEach { block ->
                parseFileRef(block)?.let { ref ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FileChip(name = ref.name, bytes = ref.bytes)
                    }
                }
            }
            val text = node.displayText()
            if (text.isNotBlank()) {
                // Injected context is not the reader's turn: it goes in a collapsed disclosure row
                // so the conversation still reads as a conversation. The predicate lives on the
                // node (core) because it is a wire fact about `source.kind`, not a UI choice.
                when {
                    node.isRewindMarker -> RewindMarkerRow()
                    node.isInjectedContext -> InjectedContextRow(node, text)
                    else -> UserMessageBubble(node, text, context)
                }
            }
        }

        is AssistantMessageNode -> AssistantMessage(node, context)

        is ToolCallNode -> ToolCallRow(node, context)

        // Rendered inside the matching ToolCallNode's card; not a standalone row.
        is ToolResultNode -> Unit

        is TurnEndNode -> when (node.reasonKind) {
            "completed" -> Unit
            "aborted", "interrupted" -> DsPill(text = stringResource(R.string.chat_stopped), warn = true)
            "error" -> Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(StateDotState.Error, size = 8.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.chat_error_turn) + node.reasonDetail?.let { " · $it" }.orEmpty(),
                    style = DsType.small13,
                    color = colors.error,
                )
            }
            "max-tokens" -> DsPill(text = stringResource(R.string.chat_max_tokens), warn = true)
            else -> Unit
        }

        is TodoNode -> parseTodos(node.todos)?.let { TodoDock(it) }

        is GoalNode -> parseGoal(node.data)?.let { GoalSummary(it) }

        is PlanModeNode -> DsPill(
            text = stringResource(if (node.active) R.string.plan_mode_on else R.string.plan_mode_off),
            warn = true,
        )

        is CompactionNode -> CompactionRow(node)

        is RetryNode -> {
            val delayMs = (node.data as? JsonObject)?.let { obj ->
                obj["delayMs"].asLong() ?: obj["ms"].asLong() ?: obj["providerRetryAfterMs"].asLong()
            }
            val label = if (delayMs != null && delayMs > 0) {
                stringResource(R.string.chat_retry_scheduled, (delayMs / 1000).toInt().coerceAtLeast(1))
            } else {
                // A retry with no stated delay used to read "Loading…", which says nothing about
                // what happened; four of them in a row before a failure is a story worth telling.
                stringResource(R.string.chat_retrying)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(StateDotState.Warning, size = 8.dp)
                Spacer(Modifier.width(6.dp))
                Text(label, style = DsType.caption11, color = colors.labelTertiary)
            }
        }

        is TurnErrorNode -> Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(StateDotState.Error, size = 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.chat_error_turn) + " · " + node.message,
                style = DsType.small13,
                color = colors.error,
            )
            node.code?.let { Text(" · $it", style = DsType.caption11, color = colors.labelTertiary) }
        }

        is CommandNode -> CommandRow(node)

        is WorkflowNode -> WorkflowRow(node.data, context.onOpenSubagent)

        is DeveloperMessageNode -> ToolSetChangeRow(node)

        is TitleNode -> Text(node.title, style = DsType.caption11, color = colors.labelTertiary)
        is SubagentNode -> Text(
            stringResource(R.string.subagents_title),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        // Unknown event types stay visible — that is the compatibility contract — but the
        // structural ones are not "unknown", they are bookkeeping, and printing `step/start` /
        // `step/end` between every tool call buried the actual work in noise.
        is OtherNode -> if (node.type !in STRUCTURAL_EVENT_TYPES) {
            // The turn's settled file changes, listed the way the web client lists them. A payload
            // this build cannot read falls through to the raw disclosure like any other event.
            val changesTurn = if (node.type == "workspace/changes") changesTurn(node.data) else null
            if (changesTurn != null) {
                ChangedFilesRow(context.nodes, changesTurn, context.cwd, context.onMention)
            } else if (node.type == "deliverables/presented") {
                val open = com.labteto.dshmobile.ui.components.LocalFileOpener.current
                ((node.data as? JsonObject)?.get("files") as? JsonArray)?.forEach { file ->
                    val obj = file as? JsonObject
                    val path = obj?.get("path").asString()
                    if (!path.isNullOrBlank()) androidx.compose.material3.OutlinedCard(onClick = { open(path) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(basename(path), style = DsType.std14)
                            Text(path, style = DsType.caption11)
                            obj?.get("description").asString()?.let { Text(it, style = DsType.small13) }
                        }
                    }
                }
            } else JsonDisclosure(when (node.type) {
                "system/message", "request/context" -> stringResource(R.string.system_prompt)
                "image/offload" -> stringResource(R.string.image_offload)
                else -> node.type
            }, node.data)
        }
    }
}

/** Event types that carry no user-facing content; they frame the transcript rather than fill it. */
internal val STRUCTURAL_EVENT_TYPES = setOf(
    "step/start",
    "step/end",
    "session/end-seed",
    "session/title-llm-request",
    "agent/inbox/spliced",
    "assistant/chunk",
    // A model attempt that settled without a message (harness 0.1.3): replay data, not content.
    "assistant/attempt",
)

/**
 * One stored file in a message: its display name and exact size, nothing more.
 *
 * There is no preview and no download — the harness keeps the bytes for the agent's file tools,
 * and the reference the log carries is a content digest rather than a path or a URL. The chip
 * says what was sent, which is all a transcript needs.
 */
@Composable
internal fun FileChip(name: String, bytes: Long, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Row(
        modifier = modifier
            .clip(DsShapes.block)
            .background(colors.bgModulePlatform)
            .border(1.dp, colors.borderL3, DsShapes.block)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            tint = colors.labelSecondary,
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(
                name,
                style = DsType.small13,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(fileSizeText(bytes), style = DsType.caption11, color = colors.labelTertiary)
        }
    }
}

// ---------------------------------------------------------------------------
// User messages
// ---------------------------------------------------------------------------

/**
 * The reader's own message, and what holding it offers.
 *
 * Copying it back is the point (issue #60): a prompt sent with a mistake in it, then stopped, used
 * to be stuck in the transcript, and retyping it on a phone keyboard was the only way to fix it.
 * "Edit as new message" goes one step further and puts it straight in the composer; the rewind,
 * when the plugin is there, withdraws it too, so the corrected one replaces it instead of following.
 */
@Composable
private fun UserMessageBubble(node: UserMessageNode, text: String, context: ChatNodeContext) {
    var menuOpen by remember(node.seq) { mutableStateOf(false) }
    var selecting by remember(node.seq) { mutableStateOf(false) }
    val copy = com.labteto.dshmobile.ui.components.rememberCopier()
    UserBubble(
        text = text,
        onHold = { menuOpen = true },
        holdLabel = stringResource(R.string.hold_message_actions),
    )
    if (menuOpen) {
        val actions = buildList {
            add(HoldAction(stringResource(R.string.common_copy), Icons.Outlined.ContentCopy) { copy(text) })
            add(HoldAction(stringResource(R.string.hold_select_text), Icons.Outlined.TextFields) { selecting = true })
            context.onEditPrompt?.let { edit ->
                add(
                    HoldAction(
                        stringResource(R.string.hold_edit_as_new),
                        Icons.Outlined.Edit,
                        detail = stringResource(R.string.hold_edit_as_new_detail),
                    ) { edit(text) },
                )
            }
            context.onRewindTo?.let { rewind ->
                add(
                    HoldAction(
                        stringResource(R.string.hold_rewind_here),
                        Icons.AutoMirrored.Outlined.Undo,
                        detail = stringResource(R.string.hold_rewind_here_detail),
                    ) { rewind(node.seq, node.previewText) },
                )
            }
        }
        HoldMenu(onDismiss = { menuOpen = false }, actions = actions, preview = text)
    }
    if (selecting) SelectTextDialog(text) { selecting = false }
}

// ---------------------------------------------------------------------------
// Assistant messages
// ---------------------------------------------------------------------------

@Composable
private fun AssistantMessage(node: AssistantMessageNode, context: ChatNodeContext) {
    val colors = DsTheme.colors
    val isLast = context.nodes.lastOrNull()?.seq == node.seq
    // A message the harness marked as a cancelled turn's prefix arrives before that turn's end,
    // so `running` is still true for a frame. Without this the last thing the user sees after
    // tapping stop is the answer apparently still being written.
    val streaming = context.running && isLast && !node.interrupted
    val knownToolCallIds = remember(context.nodes) {
        context.nodes.filterIsInstance<ToolCallNode>().mapTo(mutableSetOf()) { it.callId }
    }
    // Keyed on the message's identity across its settling, not on `seq`: a streaming message's
    // sequence number is replaced by the durable one when the reply finishes, and state keyed on it
    // folded the reader's open thinking panel shut at that moment. See [assistantStateKey].
    val stateKey = assistantStateKey(node)
    val memory = context.assistantMemory
    val reasoningExpanded = remember(memory, stateKey) {
        memory?.reasoningFor(stateKey) ?: mutableStateMapOf<Int, Boolean>()
    }
    val actionsVisibleState = remember(memory, stateKey) {
        memory?.actionsFor(stateKey) ?: mutableStateOf(false)
    }
    var actionsVisible by actionsVisibleState
    // Where the streaming caret goes: the last block that renders prose. The caret is a plain
    // glyph appended to the markdown source because the renderer owns the inline spans; it is the
    // same character the web client draws, so a reply being written looks like it is being typed.
    val lastTextBlock = remember(node.blocks) {
        node.blocks.indexOfLast { it.kind == "text" && !it.text.isNullOrBlank() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !streaming) { actionsVisible = !actionsVisible },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        node.blocks.forEachIndexed { index, block ->
            when (block.kind) {
                "text" -> if (!block.text.isNullOrBlank()) {
                    // The live reply's last prose block carries the shared blinking caret. It is
                    // the renderer's own component, so it sits inline after the final line instead
                    // of being glued onto the markdown source as a glyph.
                    MarkdownText(
                        block.text.orEmpty(),
                        streamingCaret = streaming && index == lastTextBlock,
                    )
                }
                "reasoning" -> if (!block.text.isNullOrBlank() || streaming) {
                    val expanded = reasoningExpanded[index] ?: false
                    ThinkingPanel(
                        text = block.text.orEmpty(),
                        expanded = expanded,
                        onToggle = { reasoningExpanded[index] = !expanded },
                        streaming = streaming,
                        content = { ReasoningContent(block.text.orEmpty(), streaming) },
                    )
                }
                // Show partial arguments until the durable tool card takes over. The list filter
                // uses this same predicate so a tool-only streaming message can reach this row.
                "tool-call" -> if (node.showsToolPreview(block, context.running, knownToolCallIds)) {
                    val progress = toolRowModel(block.toolName.orEmpty(), block.argumentsJson, context.cwd)
                    Text(listOfNotNull(progress.title, progress.summary).joinToString(" · "), style = DsType.small13, color = colors.labelSecondary)
                }
                "tool-result" -> Unit
                "image" -> parseImageRef(block)?.let { ref ->
                    AttachmentImage(ref = ref, contentDescription = ref.name)
                }
                "file" -> parseFileRef(block)?.let { ref -> FileChip(name = ref.name, bytes = ref.bytes) }
                else -> block.text?.let {
                    Text(it, style = DsType.caption11, color = colors.labelTertiary)
                }
            }
        }
        if (node.interrupted) {
            DsPill(text = stringResource(R.string.chat_stopped), warn = true)
        }
        AnimatedVisibility(
            visible = actionsVisible && !streaming,
            enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
            exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
        ) {
            MessageActionsRow(node, context)
        }
    }
}

/**
 * Per-message actions, revealed on tap rather than always shown — a transcript with a row of icons
 * under every message reads as clutter, and these are all occasional.
 */
@Composable
private fun MessageActionsRow(node: AssistantMessageNode, context: ChatNodeContext) {
    val copy = com.labteto.dshmobile.ui.components.rememberCopier()
    Row(
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIcon(Icons.Filled.ContentCopy, stringResource(R.string.chat_copy_message)) {
            copy(node.plainText)
        }
        ActionIcon(Icons.AutoMirrored.Outlined.CallSplit, stringResource(R.string.chat_branch_message)) {
            context.onBranchFrom(node.seq)
        }
        ActionIcon(Icons.Filled.ThumbUp, stringResource(R.string.chat_feedback_up)) {
            context.onFeedback(node.seq, true)
        }
        ActionIcon(Icons.Filled.ThumbDown, stringResource(R.string.chat_feedback_down)) {
            context.onFeedback(node.seq, false)
        }
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    // A full touch target around an 18dp glyph. The 28dp icons with 4dp between them were close
    // enough that copy and branch were easy to swap by accident.
    com.labteto.dshmobile.ui.components.DsIconButton(
        icon = icon,
        contentDescription = label,
        onClick = onClick,
        tint = DsTheme.colors.labelTertiary,
        iconSize = 18.dp,
    )
}

// ---------------------------------------------------------------------------
// Tool calls
// ---------------------------------------------------------------------------

@Composable
internal fun ToolCallRow(node: ToolCallNode, context: ChatNodeContext, compact: Boolean = false) {
    val colors = DsTheme.colors
    val result = context.nodes
        .filterIsInstance<ToolResultNode>()
        .firstOrNull { it.callId == node.callId }

    val card = buildToolCardView(
        call = node,
        result = result,
        running = result == null && context.running,
        cwd = context.cwd,
        home = context.home,
    )
    // The row header is derived here rather than taken from the card: only this layer knows the
    // tool's name and the session's cwd, which is what turns an absolute path into `app\build.gradle.kts`.
    val row = toolRowModel(
        toolName = node.name,
        argumentsJson = node.arguments,
        cwd = context.cwd,
        // Derived from the call itself now: 0.1.2 sends no presenter title to prefer over it.
        viewTitle = null,
    )
    var expanded by remember(node.callId) { mutableStateOf(false) }
    var holdOpen by remember(node.callId) { mutableStateOf(false) }
    val holdLabel = stringResource(R.string.hold_tool_actions)
    // The leading slot carries the outcome: a red dot for a failed call, the tool glyph otherwise.
    val state = when {
        result?.isError == true -> DisclosureState.Error
        result == null && context.running -> DisclosureState.Running
        else -> DisclosureState.Idle
    }
    val startedAt = context.eventTimes[node.seq]
    val endedAt = result?.let { context.eventTimes[it.seq] }
    val elapsed = if (startedAt != null && endedAt != null) {
        com.labteto.dshmobile.ui.components.formatDurationMs((endedAt - startedAt).coerceAtLeast(0))
    } else null
    if (!compact && elapsed != null) Text(elapsed, style = DsType.caption11, color = colors.labelCaption)
    ToolCard(
        view = card,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        titleOverride = row.title,
        summaryOverride = row.summary,
        iconOverride = row.variant.featherIcon(),
        state = state,
        onHold = { holdOpen = true },
        holdLabel = holdLabel,
        header = if (compact) ({
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp)
                .clickableWithHold(holdLabel, onHold = { holdOpen = true }, role = Role.Button) { expanded = !expanded }
                .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(if (node.name == "read_image") FeatherIcons.Image else row.variant.featherIcon(), null, Modifier.size(14.dp), tint = colors.labelTertiary)
                Text(row.title, style = DsType.small13, color = colors.labelSecondary)
                Text(row.summary.orEmpty(), Modifier.weight(1f), style = DsType.caption11,
                    color = colors.labelCaption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (expanded && elapsed != null) Text(elapsed, style = DsType.caption11, color = colors.labelCaption)
                Text(when (state) { DisclosureState.Error -> "!"; DisclosureState.Running -> "…"; else -> "✓" },
                    style = DsType.caption11, color = if (state == DisclosureState.Error) colors.error else colors.labelTertiary)
                val chevron by animateFloatAsState(if (expanded) 90f else 0f, DsAnimations.chevron, label = "toolChevron")
                Icon(FeatherIcons.ChevronRight, null, Modifier.size(14.dp).graphicsLayer { rotationZ = chevron }, tint = colors.labelTertiary)
            }
        }) else null,
    )
    if (holdOpen) ToolHoldMenu(node, result, row.filePath, context) { holdOpen = false }
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
        exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
    ) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // What the call returned, not the file as it is now: the durable attachment survives the
        // file changing or being deleted, and shares the transcript's decoded-image cache.
        val images = remember(result?.content) { toolResultImages(result?.content) }
        images.forEach { ref ->
            AttachmentImage(ref = ref, contentDescription = ref.name)
        }
        result?.content?.let { JsonDisclosure(stringResource(R.string.chat_output_placeholder), it) }
        result?.meta?.let { JsonDisclosure(node.name, it) }
        PtcChildren(node.callId, context.nodes.filterIsInstance<OtherNode>())
    } }
    if (result?.isError == true) {
        // The dot is colour-only, so the word stays — but without a second dot beside it.
        //
        // The call's own failure text, not a generic apology: "Something went wrong" told the
        // reader nothing they could act on, while the result body carries the command's stderr,
        // the refused path, or the tool's own message. The generic string is only the fallback
        // for a result that genuinely carries no prose.
        val failure = remember(result?.content) { toolFailureMessage(result?.content) }
        Text(
            failure ?: stringResource(R.string.common_error),
            style = DsType.caption11,
            color = colors.error,
            modifier = Modifier.padding(start = 26.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Compaction / commands / workflow
// ---------------------------------------------------------------------------

@Composable
private fun CompactionRow(node: CompactionNode) {
    val summaryText = remember(node.seq) {
        runCatching {
            val array = (node.data as? JsonObject)?.get("summary") as? JsonArray
            array?.mapNotNull { (it as? JsonObject)?.get("text").asString() }?.joinToString("\n")
        }.getOrNull()
    }
    var expanded by remember(node.seq) { mutableStateOf(false) }
    DisclosureRow(
        title = stringResource(R.string.chat_compaction),
        summary = stringResource(R.string.chat_compaction_summary),
        icon = FeatherIcons.Archive,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        if (!summaryText.isNullOrBlank()) MarkdownText(summaryText)
    }
}

/** Where a rewind cut the conversation: everything above is what the model sees now. */
@Composable
private fun RewindMarkerRow() {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = colors.borderL1)
        Text(
            stringResource(R.string.rewind_marker),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = colors.borderL1)
    }
}

@Composable
private fun CommandRow(node: CommandNode) {
    val colors = DsTheme.colors
    val data = node.data as? JsonObject
    val name = data?.get("name").asString() ?: node.kind
    val text = data?.get("text").asString()
    var expanded by remember(node.seq) { mutableStateOf(false) }
    DisclosureRow(
        title = "/$name",
        summary = text,
        icon = FeatherIcons.Terminal,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Text(
            node.data.toString(),
            style = DsType.caption11.copy(fontFamily = DsType.codeFont),
            color = colors.labelCaption,
            modifier = Modifier.padding(start = 28.dp, top = 2.dp),
        )
    }
}

@Composable
private fun WorkflowRow(
    data: kotlinx.serialization.json.JsonElement,
    onOpenMember: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val obj = data as? JsonObject ?: return
    val name = obj["name"].asString()
    val status = obj["status"].asString() ?: obj["stopReason"].asString() ?: obj["outcome"].asString()
    val members = remember(data) { parseWorkflowMembers(data) }
    var expanded by remember(data) { mutableStateOf(false) }
    DisclosureRow(
        title = stringResource(R.string.workflow_title),
        summary = listOfNotNull(name, workflowStatusLabel(status)).joinToString(" · ").ifEmpty { null },
        icon = FeatherIcons.GitBranch,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        members.forEach { member ->
            val memberChildId = member.childId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 2.dp)
                    .then(
                        if (memberChildId != null) {
                            Modifier.clickable { onOpenMember(memberChildId) }
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateDot(workflowMemberDot(member.status), size = 8.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    member.label ?: memberChildId.orEmpty(),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                member.status?.let {
                    Text(
                        workflowStatusLabel(it) ?: it,
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * The readable failure text out of a `tool/result` body, or null when it carries none.
 *
 * `ToolResultNode.content` is the result body, `[ { type: "text", text: "Error: …" }, … ]`, in
 * both session formats: the fold unwraps the `tool-result` part that format v3 nested it in. The
 * walk still recurses through any `content` array it meets and takes the first `text` part at any
 * depth, so a tool that nests its own parts, or a container kind this build has not seen, still
 * yields its prose rather than the generic string.
 *
 * A bare string body is accepted too: some tools answer with one.
 */
internal fun toolFailureMessage(content: JsonElement?): String? {
    fun fromText(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** First prose part at or below [element], depth-first, or null. */
    fun walk(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> fromText(element.contentOrNull)
        is JsonArray -> element.firstNotNullOfOrNull { walk(it) }
        is JsonObject -> {
            val type = element["type"]?.jsonPrimitive?.contentOrNull
            // A container part (tool-result, or a nesting this build has not seen) is descended
            // into; a text part ends the search; anything else — an image, a structured blob — is
            // skipped rather than dumped at the reader.
            when {
                type == "text" || (type == null && element.containsKey("text")) ->
                    fromText(element["text"]?.jsonPrimitive?.contentOrNull)
                element.containsKey("content") -> walk(element["content"])
                // A flat error object: take its human-readable field.
                else -> fromText(
                    (element["message"] ?: element["error"])?.jsonPrimitive?.contentOrNull,
                )
            }
        }
        else -> null
    }

    return walk(content)
}

/**
 * Harness-injected context, folded into one disclosure row.
 *
 * Rendered as a collapse rather than a bubble because it is not the reader's turn: a system
 * prompt, an agent-instruction block or a skill invocation put on the user's side of the
 * conversation reads as though they had typed it. The row states the source kind so the reader can
 * tell which kind of context it is without expanding it.
 */
@Composable
private fun InjectedContextRow(node: UserMessageNode, text: String) {
    var expanded by remember(node.seq) { mutableStateOf(false) }
    DisclosureRow(
        title = stringResource(R.string.chat_context_title),
        summary = node.sourceKind ?: text.take(80),
        icon = FeatherIcons.Info,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        MarkdownText(text)
    }
}

/**
 * A change to the agent's tool set, harness 0.1.7's `developer/message`. It is context the model
 * sees rather than anything a person said, so it sits in the same collapsed row as other injected
 * context; the summary names the tools so the row is informative without opening it.
 */
@Composable
private fun ToolSetChangeRow(node: DeveloperMessageNode) {
    var expanded by remember(node.seq) { mutableStateOf(false) }
    val summary = (node.addedTools.map { "+$it" } + node.removedTools.map { "−$it" }).joinToString(", ")
    DisclosureRow(
        title = stringResource(R.string.chat_context_title),
        summary = stringResource(R.string.chat_context_tools) + " · " + summary,
        icon = FeatherIcons.Info,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Text(summary, style = DsType.small13, color = DsTheme.colors.labelSecondary)
    }
}

