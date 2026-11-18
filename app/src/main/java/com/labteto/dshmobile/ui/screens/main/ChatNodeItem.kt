package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.labteto.dshmobile.ui.components.clickable
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
import androidx.compose.runtime.CompositionLocalProvider
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
import com.labteto.dshmobile.core.session.unwrapToolText
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.session.TurnErrorNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.session.WorkflowNode
import com.labteto.dshmobile.ui.components.AttachmentImage
import com.labteto.dshmobile.ui.components.CallStateMark
import com.labteto.dshmobile.ui.components.LeftToRight
import com.labteto.dshmobile.ui.components.LocalCodeOwner
import com.labteto.dshmobile.ui.components.OutputViewerDialog
import com.labteto.dshmobile.ui.components.DisclosureRow
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
import com.labteto.dshmobile.ui.components.SubagentCard
import com.labteto.dshmobile.ui.components.ToolCard
import com.labteto.dshmobile.ui.components.ToolCardActions
import com.labteto.dshmobile.ui.components.ToolCardView
import com.labteto.dshmobile.ui.components.UserBubble
import com.labteto.dshmobile.ui.components.controlIcon
import com.labteto.dshmobile.ui.components.controlSummary
import com.labteto.dshmobile.ui.components.hasBody
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
    /**
     * The host's display name for each permission preset, by wire value. A "you changed permission"
     * line names the preset the way the composer's chip does, which for a deployment that renamed a
     * shipped preset means the name it chose. Empty falls back to the id.
     */
    val permissionNames: Map<String, String> = emptyMap(),
    /**
     * The work-step level and the reader's taps on rows (Settings > Chat > Work steps). The default
     * is the transcript as it looked before the setting existed.
     */
    val presentation: WorkStepPresentation = WorkStepPresentation.Default,
    /**
     * The live state of each background job by id, for a background command's card, and how to open
     * one in the Jobs sheet. Empty and null where there is no jobs list (the subagent sheet, the
     * Trajectory ledger): the card then shows the command and draws no control.
     */
    val jobStates: Map<String, com.labteto.dshmobile.core.wire.dto.JobStatus> = emptyMap(),
    val onOpenJob: ((String) -> Unit)? = null,
    /** Whether each known subagent is working now, by child id, for a `subagent` call's card. */
    val subagentRunning: Map<String, Boolean> = emptyMap(),
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
            // What went wrong in words chosen by the failure's code; the provider's message and the
            // code itself stay one tap away under Technical details.
            "error" -> TurnFailureRow(node.failureCode, node.failureMessage)
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

        is TurnErrorNode -> TurnFailureRow(node.code, node.message)

        // A command the reader ran is their action, not the agent's: a muted system line, not a
        // terminal row. A node that is not theirs keeps the row it always had.
        is CommandNode -> {
            val line = remember(node, context.nodes) { systemLineFor(node, context.nodes) }
            if (line != null) SystemLineRow(line, context.permissionNames) else CommandRow(node, context)
        }

        is WorkflowNode -> WorkflowRow(node.data, node.seq, context)

        is DeveloperMessageNode -> ToolSetChangeRow(node)

        is TitleNode -> Text(node.title, style = DsType.caption11, color = colors.labelTertiary)
        // The child's own descriptor, at the top of its conversation: which subagent this is and what
        // it runs on, rather than the bare word "Subagents".
        is SubagentNode -> SubagentDescriptorRow(node)
        // Unknown event types stay visible — that is the compatibility contract — but the
        // structural ones are not "unknown", they are bookkeeping, and printing `step/start` /
        // `step/end` between every tool call buried the actual work in noise. A record the harness
        // marked skippable (its `plugin:` records) is not "unknown" either; see drawsInTranscript.
        is OtherNode -> if (node.drawsInTranscript()) {
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
                // An event this build has no words for: its type in words, not its wire form.
                else -> humanizeIdentifier(node.type)
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
    // The pill for a stopped turn is drawn once, by the turn's own end, below its tool calls.
    val stoppedTurns = remember(context.nodes) { turnsStoppedByTurnEnd(context.nodes) }
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
                        // The first line beside the title, from Standard up, once the thought is done.
                        preview = if (context.presentation.policy.settledReasoningPreview && !streaming && !expanded) {
                            remember(block.text) { firstLinePreview(block.text.orEmpty()) }
                        } else null,
                        // A thought is finished once something follows it in the message, even while
                        // the reply after it is still being written. The block's identity is its
                        // message's state key plus its index, which replay and reconnects keep.
                        content = {
                            ReasoningContent(
                                block.text.orEmpty(),
                                complete = !streaming || index < node.blocks.lastIndex,
                                blockId = "$stateKey#$index",
                            )
                        },
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
        if (node.showsStoppedPill(stoppedTurns)) {
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
    val result = remember(context.nodes, node.callId) {
        context.nodes.filterIsInstance<ToolResultNode>().firstOrNull { it.callId == node.callId }
    }

    // Remembered: in the flat levels every call on screen builds its card, and the arguments it
    // parses can be large. The inputs are exactly what the card is derived from.
    val running = result == null && context.running
    // A task-list write lists what changed since the write before it, so it needs that list.
    val previousTodos = if (node.name == "todo_write") {
        remember(context.nodes, node.seq) { previousTodoList(context.nodes, node.seq) }
    } else null
    val card = remember(node, result, running, context.cwd, context.home, previousTodos) {
        buildToolCardView(
            call = node, result = result, running = running, cwd = context.cwd, home = context.home,
            previousTodos = previousTodos,
        )
    }
    // The row header is derived here rather than taken from the card: only this layer knows the
    // tool's name and the session's cwd, which is what turns an absolute path into `app\build.gradle.kts`.
    val baseRow = toolRowModel(
        toolName = node.name,
        argumentsJson = node.arguments,
        cwd = context.cwd,
        // Derived from the call itself now: 0.1.2 sends no presenter title to prefer over it.
        viewTitle = null,
    )
    // A tool with a view of its own has a title of its own, in the reader's language, and a summary
    // the view derives (`3 of 7 done`, `Waiting for your answer`) where it has one.
    val ownTitle = toolTitle(node.name)?.let { toolTitleText(it) }
    val controlSummary = card.controlSummary()?.let(::rowSummaryText)
    val row = baseRow.copy(
        title = ownTitle ?: baseRow.title,
        // A summary the app wrote (`3 of 7 done`) is in the reader's language. One taken from the
        // call (a command, a path, a pattern, a url) is technical text: isolated left to right so a
        // right-to-left row cannot reorder it.
        summary = controlSummary
            ?: baseRow.summary?.let { if (baseRow.variant == ToolRowVariant.Other) it else technicalDisplay(it) },
    )
    val icon = card.controlIcon() ?: baseRow.variant.featherIcon()
    val failure = remember(result) { toolFailureOf(result) }
    // The card's own body, or what the transcript draws under it (Output, an image, nested calls).
    // A tool that takes no arguments has no body of its own, and used to draw an empty box.
    val images = remember(result?.content) { toolResultImages(result?.content) }
    val hasBody = remember(card, result, failure, images, context.nodes) {
        card.hasBody() || images.isNotEmpty() || showsOutputRow(card, result, failure) ||
            hasPtcChildren(context.nodes.filterIsInstance<OtherNode>(), node.callId)
    }
    val actions = toolCardActions(card, context)
    // Open or closed by the work-step level; a tap overrides it for this row only (see
    // RowDisclosureMemory). However it was opened, only the start of a long body is laid out: the
    // bounds are the same for a row the reader tapped, and "Show all" opens the rest in the
    // full-screen viewer, which is lazy by line. (Opening the whole of a megabyte of output as one
    // text is what made a hand-opened row freeze.)
    val disclosure = rememberRowDisclosure(context.presentation, "tool:${node.callId}", node.seq)
    val expanded = disclosure.open
    val clippedCard = remember(card) { clipCardForPreview(card) }
    var viewer by remember(node.callId) { mutableStateOf<OutputViewerRequest?>(null) }
    var holdOpen by remember(node.callId) { mutableStateOf(false) }
    var rawOpen by remember(node.callId) { mutableStateOf(false) }
    val holdLabel = stringResource(R.string.hold_tool_actions)
    // The leading slot carries the outcome: a red dot for a failed call, an amber one for a call the
    // turn's stop cut short, the tool glyph otherwise. The card's own dot and the group's failed count
    // read the same function, so they cannot disagree with this row.
    val state = toolOutcome(node.name, result, context.running).disclosureState()
    val startedAt = context.eventTimes[node.seq]
    val endedAt = result?.let { context.eventTimes[it.seq] }
    val elapsed = if (startedAt != null && endedAt != null) {
        com.labteto.dshmobile.ui.components.formatDurationMs((endedAt - startedAt).coerceAtLeast(0))
    } else null
    // The blocks of this call remember whether the reader wrapped them under the call's id.
    CompositionLocalProvider(LocalCodeOwner provides node.callId) {
    ToolCard(
        view = clippedCard.view,
        expanded = expanded,
        onToggle = disclosure.toggle,
        titleOverride = row.title,
        summaryOverride = row.summary,
        iconOverride = icon,
        state = state,
        onHold = { holdOpen = true },
        holdLabel = holdLabel,
        actions = actions,
        expandable = hasBody,
        // The summary opens with the figures (progress, then what changed): an open row, and a row
        // with nothing to open, give it a second line rather than cutting them.
        summaryMaxLines = if (expanded || !hasBody) 2 else 1,
        // How long it took, in the row itself, not as a caption floating above the card.
        trailingText = if (compact) null else elapsed,
        header = if (compact) ({
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp)
                .clickableWithHold(holdLabel, onHold = { holdOpen = true }, role = Role.Button) { if (hasBody) disclosure.toggle() }
                .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(icon, null, Modifier.size(14.dp), tint = colors.labelTertiary)
                Text(row.title, style = DsType.small13, color = colors.labelSecondary)
                Text(row.summary.orEmpty(), Modifier.weight(1f), style = DsType.caption11,
                    color = colors.labelCaption, maxLines = if (expanded || !hasBody) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (expanded && elapsed != null) Text(elapsed, style = DsType.caption11, color = colors.labelCaption)
                // The outcome as a shape with a spoken name (the bare "!", "…", "✓" and "■" read as
                // punctuation to a screen reader, and done and stopped looked alike).
                CallStateMark(state)
                if (hasBody) {
                    val chevron by animateFloatAsState(if (expanded) 90f else 0f, DsAnimations.chevron, label = "toolChevron")
                    Icon(FeatherIcons.ChevronRight, null, Modifier.size(14.dp).graphicsLayer { rotationZ = chevron }, tint = colors.labelTertiary)
                }
            }
        }) else null,
    )
    if (holdOpen) {
        ToolHoldMenu(node, result, row.filePath, context, onDismiss = { holdOpen = false }, onShowRaw = { rawOpen = true })
    }
    if (rawOpen) {
        val raw = remember(node, result) { toolRawJson(node, result) }
        RawJsonDialog(raw) { rawOpen = false }
    }
    viewer?.let { request ->
        OutputViewerDialog(title = request.title, contentKey = request.key, onDismiss = { viewer = null }, load = request.load)
    }
    AnimatedVisibility(
        visible = expanded && hasBody,
        enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
        exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
    ) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // What the call returned, not the file as it is now: the durable attachment survives the
        // file changing or being deleted, and shares the transcript's decoded-image cache.
        images.forEach { ref ->
            AttachmentImage(ref = ref, contentDescription = ref.name)
        }
        // The text the tool returned, not the protocol JSON around it. A generic card has no output
        // of its own, so the text sits right under the input; the specialised cards (a terminal, a
        // diff, a read) already present the result in their body, so there it stays one row down.
        // A card that says all its result does (a task list, a job's state, a question's answers)
        // gets no Output row at all; the raw result is in the hold menu.
        // A failed call whose text is only the failure line's own words (an unknown tool's
        // `Error: unknown tool "x"`) is not drawn twice.
        val repeatsFailure = remember(result, failure) { outputRepeatsFailure(result, failure) }
        result?.content?.takeIf { !card.coversOutput() && !repeatsFailure }?.let { content ->
            if (card is com.labteto.dshmobile.ui.components.ToolCardView.GenericCard) {
                ToolResultOutput(
                    content,
                    label = stringResource(R.string.chat_output_placeholder),
                    viewerTitle = row.title,
                )
            } else {
                ToolResultOutputRow(
                    stringResource(R.string.chat_output_placeholder),
                    content,
                    viewerTitle = row.title,
                )
            }
        }
        // The result's `meta` is what the cards are built from, so it is not repeated under them as
        // raw JSON; the hold menu's "Show raw JSON" and Trajectory's raw events still have it.
        val others = remember(context.nodes) { context.nodes.filterIsInstance<OtherNode>() }
        PtcChildren(node.callId, others, presentation = context.presentation)
        // The card body was cut to the inline bounds: say so, and let the reader open all of it in the
        // viewer. The text is built from the whole card, on a background thread, only when they do.
        if (clippedCard.clipped) {
            ClippedNote { viewer = OutputViewerRequest(row.title, "card:${node.callId}") { cardPlainText(card) } }
        }
    } }
    }
    // The dot is colour-only, so the words stay, and the card above keeps what the call tried (the
    // command, the diff). The line says why it did not happen: the tool's own failure text, cut to
    // its first line, or for a refusal the reason in the reader's own words, chosen by its code
    // (permissionDenialOf: the built-in runtime's gate, and the harness's Auto review). A card that
    // states its own outcome (a question the reader closed) needs no second line.
    if (failure != null && !card.explainsFailure(result)) ToolFailureLine(failure)
}

/**
 * What the full-screen viewer is to show. [key] says when [load] would give another text; [load] runs
 * on a background thread, so a text that needs building (a whole diff, a result to unwrap) costs
 * nothing until the reader asks for it.
 */
internal class OutputViewerRequest(val title: String, val key: Any, val load: () -> String)

/** What a call's card can open, from what the transcript knows about jobs and subagents. */
@Composable
private fun toolCardActions(card: ToolCardView, context: ChatNodeContext): ToolCardActions = when (card) {
    is ToolCardView.TerminalCard -> {
        val jobId = card.jobId
        val status = jobId?.let { context.jobStates[it] }
        val open = context.onOpenJob
        ToolCardActions(
            // Only a job the Jobs list still has can open there.
            openJob = if (jobId != null && status != null && open != null) ({ open(jobId) }) else null,
            jobState = status?.let { jobStatusLabel(it) },
            jobRunning = status == com.labteto.dshmobile.core.wire.dto.JobStatus.RUNNING ||
                status == com.labteto.dshmobile.core.wire.dto.JobStatus.STOPPING,
        )
    }
    is SubagentCard -> {
        val child = card.childId
        ToolCardActions(
            openSubagent = child?.let { id -> { context.onOpenSubagent(id) } },
            subagentRunning = child?.let { context.subagentRunning[it] },
        )
    }
    else -> ToolCardActions.None
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
    if (summaryText.isNullOrBlank()) {
        // A summary the harness sent without text: keep the row as the marker of where the context
        // was compacted, but give it no chevron, since there is nothing to open.
        DisclosureRow(
            title = stringResource(R.string.chat_compaction),
            summary = stringResource(R.string.chat_compaction_summary),
            icon = FeatherIcons.Archive,
        )
        return
    }
    DisclosureRow(
        title = stringResource(R.string.chat_compaction),
        summary = stringResource(R.string.chat_compaction_summary),
        icon = FeatherIcons.Archive,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        MarkdownText(summaryText)
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
private fun CommandRow(node: CommandNode, context: ChatNodeContext) {
    val colors = DsTheme.colors
    val data = node.data as? JsonObject
    val name = data?.get("name").asString() ?: node.kind
    val text = data?.get("text").asString()
    val disclosure = rememberRowDisclosure(context.presentation, "cmd:${node.seq}", node.seq)
    DisclosureRow(
        title = "/$name",
        summary = text?.let(::rowSummaryText),
        icon = FeatherIcons.Terminal,
        expanded = disclosure.open,
        onToggle = disclosure.toggle,
    ) {
        // Bounded however the row was opened; "Show all" opens the whole text in the viewer.
        val full = remember(node.data) { node.data.toString() }
        val shown = remember(full) { clipPreview(full) }
        var viewer by remember(node.seq) { mutableStateOf(false) }
        // A command's own text is technical: kept left to right in a right-to-left row.
        LeftToRight {
            Text(
                shown,
                style = DsType.caption11.copy(fontFamily = DsType.codeFont),
                color = colors.labelCaption,
                modifier = Modifier.padding(start = 28.dp, top = 2.dp),
            )
        }
        if (shown.length != full.length) ClippedNote { viewer = true }
        if (viewer) OutputViewerDialog(title = "/$name", contentKey = node.seq, onDismiss = { viewer = false }, load = { full })
    }
}

@Composable
private fun WorkflowRow(
    data: kotlinx.serialization.json.JsonElement,
    seq: Long,
    context: ChatNodeContext,
) {
    val onOpenMember = context.onOpenSubagent
    val colors = DsTheme.colors
    val obj = data as? JsonObject ?: return
    val name = obj["name"].asString()
    val status = obj["status"].asString() ?: obj["stopReason"].asString() ?: obj["outcome"].asString()
    val members = remember(data) { parseWorkflowMembers(data) }
    val disclosure = rememberRowDisclosure(context.presentation, "wf:$seq", seq)
    DisclosureRow(
        title = stringResource(R.string.workflow_title),
        summary = listOfNotNull(name, workflowStatusLabel(status)).joinToString(" · ").ifEmpty { null },
        icon = FeatherIcons.GitBranch,
        expanded = disclosure.open,
        onToggle = disclosure.toggle,
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
    // A failure whose text is itself a block list or envelope is unwrapped like any other output.
    fun fromText(value: String?): String? = value?.let(::unwrapToolText)?.trim()?.takeIf { it.isNotEmpty() }

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


/** What the chat says for a call the built-in runtime's command check did not run. */
internal fun permissionDenialText(denial: com.labteto.dshmobile.core.permission.PermissionDenial): Int = when (denial) {
    com.labteto.dshmobile.core.permission.PermissionDenial.SubagentCannotAsk -> R.string.subagent_command_denied
    com.labteto.dshmobile.core.permission.PermissionDenial.Rejected -> R.string.command_denied_rejected
    com.labteto.dshmobile.core.permission.PermissionDenial.Cancelled -> R.string.command_denied_cancelled
    com.labteto.dshmobile.core.permission.PermissionDenial.NobodyToAsk -> R.string.command_denied_nobody
    com.labteto.dshmobile.core.permission.PermissionDenial.TextTooLarge -> R.string.command_denied_too_large
    com.labteto.dshmobile.core.permission.PermissionDenial.Other -> R.string.command_denied_other
    com.labteto.dshmobile.core.permission.PermissionDenial.AutoReviewRejected -> R.string.chat_tool_auto_review_denied
}
