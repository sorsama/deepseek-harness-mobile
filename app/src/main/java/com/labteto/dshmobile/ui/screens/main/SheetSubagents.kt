package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.wire.dto.SubagentListEntry
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.EmptyHero
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.MascotMark
import com.labteto.dshmobile.ui.components.MascotState
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.media.LocalAttachmentScope
import com.labteto.dshmobile.ui.components.LocalFileOpener
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * The subagent catalog (design 16), with the selected child's transcript inline.
 *
 * Rows are cards rather than a flat list: each one carries the mascot avatar, the child's name,
 * its state, and the mode tag that says whether it can take another turn. Selecting a card opens
 * the child's transcript and follow-up composer inside that card, so the list and the thing it
 * opened stay one object — the design's accordion, without inventing a second navigation level.
 *
 * Only continuable children accept messages; one-shot children and any child whose parent is
 * offline are read-only, which the sheet states rather than offering an input that would be
 * rejected.
 */
@Composable
internal fun SubagentsSheet(
    store: SessionStore,
    entries: List<SubagentListEntry>,
    conversation: ConversationSnapshot?,
    mode: String?,
    onDismiss: () -> Unit,
) {
    DisposableEffect(store) { onDispose { store.closeSubagentTranscript() } }
    var childPanel by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val childId = conversation?.sessionId
    val connection by store.connectionState.collectAsStateWithLifecycle()
    LaunchedEffect(connection.phase) {
        if (connection.phase == com.labteto.dshmobile.connection.ConnectionPhase.CONNECTED && childId != null) {
            store.openSubagentTranscript(childId)
        }
    }
    var draft by remember(childId) { mutableStateOf("") }
    var sending by remember(childId) { mutableStateOf(false) }
    var delivery by remember(childId) { mutableStateOf("queue") }
    val queueOperation = remember(childId) { androidx.compose.runtime.mutableStateOf(false) }
    val childRunning = entries
        .firstOrNull { subagentId(it) == childId }
        ?.let { subagentRunning(it) } == true
    fun sendChild() {
        val text = draft
        val id = childId
        if (text.isBlank() || id == null || sending || queueOperation.value) return
        sending = true
        scope.launch {
            try {
                // The same rule as the main composer: a Steer chosen while the child is idle is
                // sent as a queued follow-up, because the host refuses to steer an idle child.
                if (store.promptSubagent(id, text, promptDelivery(delivery, childRunning)) && draft == text) draft = ""
            } finally {
                sending = false
            }
        }
    }
    val queues by store.sessionQueues.collectAsStateWithLifecycle()
    val selectedInList = entries.any { subagentId(it) == childId }

    DsBottomSheet(
        title = stringResource(R.string.subagents_title),
        subtitle = entries.size.takeIf { it > 0 }?.toString(),
        onDismiss = {
            haptics.tap()
            onDismiss()
        },
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            if (entries.isEmpty()) {
                EmptyHero(
                    headline = stringResource(R.string.subagents_empty),
                    subtitle = null,
                )
            }
            entries.forEach { entry ->
                val id = subagentId(entry)
                val selected = id != null && id == childId
                SubagentCard(
                    entry = entry,
                    selected = selected,
                    onClick = {
                        if (id != null) {
                            haptics.selection()
                            scope.launch { store.openSubagentTranscript(id) }
                        }
                    },
                ) {
                    if (selected && conversation != null) {
                        ChildBody(
                            child = conversation,
                            store = store,
                            mode = mode,
                            running = childRunning,
                            draft = draft,
                            onDraftChange = { draft = it },
                            sending = sending,
                            delivery = delivery,
                            onDeliveryChange = {
                                haptics.selection()
                                delivery = it
                            },
                            queueOperation = queueOperation,
                            queues = queues[conversation.sessionId].orEmpty(),
                            sendChild = {
                                haptics.confirm()
                                sendChild()
                            },
                            onOpenWorkspace = { childPanel = true },
                            onInterrupt = {
                                childId?.let { cid -> scope.launch { store.interruptSubagent(cid) } }
                            },
                        )
                    }
                }
            }
            // A child opened from somewhere other than this catalog still needs its transcript;
            // without a card to put it in, it gets the same body as a block of its own.
            if (conversation != null && !selectedInList) {
                Column(Modifier.padding(top = DsSpacing.small)) {
                    ChildBody(
                        child = conversation,
                        store = store,
                        mode = mode,
                        running = childRunning,
                        draft = draft,
                        onDraftChange = { draft = it },
                        sending = sending,
                        delivery = delivery,
                        onDeliveryChange = {
                            haptics.selection()
                            delivery = it
                        },
                        queueOperation = queueOperation,
                        queues = queues[conversation.sessionId].orEmpty(),
                        sendChild = {
                            haptics.confirm()
                            sendChild()
                        },
                        onOpenWorkspace = { childPanel = true },
                        onInterrupt = {
                            childId?.let { cid -> scope.launch { store.interruptSubagent(cid) } }
                        },
                    )
                }
            }
        }
    }
    if (childPanel && childId != null) {
        WorkspacePanels(
            store,
            store.panels.get(ComposerKey(store.activeHostKey.orEmpty(), childId)),
            onDismiss = { childPanel = false },
        )
    }
}

/**
 * One child as a card: mascot avatar, name, state line, mode tag, and the accordion chevron.
 * [content] is the opened transcript and composer, shown under the header when selected.
 */
@Composable
private fun SubagentCard(
    entry: SubagentListEntry,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = DsTheme.colors
    val running = subagentRunning(entry)
    val rotation by animateFloatAsState(
        targetValue = if (selected) 180f else 0f,
        animationSpec = DsAnimations.chevron,
        label = "subagentChevron",
    )
    val modeLabel = when (entry) {
        is SubagentListEntry.ChildOneShot -> stringResource(R.string.subagents_oneshot)
        is SubagentListEntry.ChildExternal -> stringResource(R.string.alpha2_external)
        is SubagentListEntry.ChildContinuable -> stringResource(R.string.subagents_continuable)
        else -> null
    }
    // An external identity has no local session and a diagnostic is only a note: neither has a
    // transcript to open, so neither is drawn as a control.
    val external = !subagentOpenable(entry)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgModulePlatform,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) colors.accent else colors.borderL1,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !external, role = Role.Button, onClick = onClick)
                    .heightIn(min = 64.dp)
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                MascotMark(
                    size = 30.dp,
                    state = when {
                        running -> MascotState.Working
                        else -> MascotState.Idle
                    },
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        subagentLabel(entry) ?: subagentId(entry).orEmpty(),
                        style = DsType.rowTitle,
                        color = colors.labelPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(if (running) R.string.subagents_running else R.string.subagents_inactive),
                        style = DsType.small13,
                        color = if (running) colors.accent else colors.labelTertiary,
                        maxLines = 1,
                    )
                }
                modeLabel?.let { DsPill(text = it) }
                if (!external) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(if (selected) R.string.ux_b_expanded else R.string.ux_b_collapsed),
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
                    )
                }
            }
            AnimatedVisibility(
                visible = selected,
                enter = expandVertically(DsAnimations.expand) + fadeIn(DsAnimations.fade),
                exit = shrinkVertically(DsAnimations.expand) + fadeOut(DsAnimations.fade),
            ) {
                Column(Modifier.padding(bottom = DsSpacing.small)) { content() }
            }
        }
    }
}

/** The opened child: transcript, delivery choice, follow-up composer and its queue. */
@Composable
private fun ChildBody(
    child: ConversationSnapshot,
    store: SessionStore,
    mode: String?,
    running: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    delivery: String,
    onDeliveryChange: (String) -> Unit,
    queueOperation: androidx.compose.runtime.MutableState<Boolean>,
    queues: List<com.labteto.dshmobile.core.session.QueueItem>,
    sendChild: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onInterrupt: () -> Unit,
) {
    val colors = DsTheme.colors
    val childId = child.sessionId
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        if (child.nodes.isEmpty()) {
            Text(
                stringResource(R.string.common_loading),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(DsShapes.code)
                    .background(colors.bgLayer1)
                    .border(1.dp, colors.borderL1, DsShapes.code)
                    .padding(DsSpacing.small),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalAttachmentScope provides (store.activeHostKey.orEmpty() to child.sessionId),
                    LocalFileOpener provides { path ->
                        store.panels.get(ComposerKey(store.activeHostKey.orEmpty(), child.sessionId)).open(path)
                        onOpenWorkspace()
                    },
                ) {
                    val childContext = ChatNodeContext(
                        child.nodes,
                        child.running,
                        null,
                        onOpenSubagent = {},
                        onBranchFrom = {},
                        onFeedback = { _, _ -> },
                    )
                    child.nodes.forEach { node -> ChatNodeItem(node, childContext) }
                }
            }
        }

        // Delivery and the child's workspace, side by side: both are properties of talking to it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            if (mode == "continuable") {
                DsSegmented(
                    segments = listOf(
                        DsSegment("queue", stringResource(R.string.chat_queue_placement_queued)),
                        DsSegment("steer", stringResource(R.string.chat_queue_placement_steering)),
                    ),
                    selectedKey = delivery,
                    onSelect = onDeliveryChange,
                    enabled = !sending && !queueOperation.value,
                )
            }
            Spacer(Modifier.weight(1f))
            DsButton(
                text = stringResource(R.string.panel_workspace),
                icon = FeatherIcons.Folder,
                onClick = onOpenWorkspace,
                variant = DsButtonVariant.Outline,
                size = DsButtonSize.Small,
            )
        }
        // Steer stays selectable with the child idle, like the + sheet's pill; this says why the
        // next send will be queued instead of steering.
        if (mode == "continuable" && delivery == "steer" && !running) {
            Text(
                stringResource(R.string.chat_composer_steer_idle),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
        if (mode == "continuable") {
            QueueDock(
                queues,
                store,
                sessionId = childId,
                running = running,
                blocked = sending,
                operationState = queueOperation,
            )
            FollowUpComposer(
                draft = draft,
                onDraftChange = onDraftChange,
                placeholder = stringResource(R.string.ink_panel_subagent_followup),
                enabled = !sending && !queueOperation.value,
                running = running,
                onSend = sendChild,
                onInterrupt = onInterrupt,
            )
        } else {
            Text(
                stringResource(R.string.subagents_readonly),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
    }
}

/**
 * The follow-up box of design 16: one rounded input with its send disc, and a stop beside it while
 * the child is working. The field keeps the sheet's shared input colours.
 */
@Composable
private fun FollowUpComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    running: Boolean,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val canSend = draft.isNotBlank() && enabled
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.composer,
        color = colors.composerCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderL2),
    ) {
        Row(
            modifier = Modifier.padding(start = DsSpacing.medium, end = DsSpacing.xsmall, top = DsSpacing.tiny, bottom = DsSpacing.tiny),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed)) {
                            if (event.type == KeyEventType.KeyUp) onSend()
                            true
                        } else {
                            false
                        }
                    },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                placeholder = {
                    Text(placeholder, style = DsType.std14, color = colors.labelTertiary)
                },
                // Inside the composer card, so no plate or outline of its own (the card is the frame).
                colors = composerFieldColors(),
            )
            if (running) {
                DsButton(
                    text = stringResource(R.string.subagents_interrupt),
                    onClick = {
                        haptics.reject()
                        onInterrupt()
                    },
                    enabled = enabled,
                    variant = DsButtonVariant.Danger,
                    size = DsButtonSize.Small,
                )
            }
            SendDisc(enabled = canSend, onClick = onSend)
        }
    }
}

/** The 44dp accent disc that sends, as in the design's composer. */
@Composable
private fun SendDisc(enabled: Boolean, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (enabled) colors.accent else colors.bgLayer3)
            .clickable(enabled = enabled, role = Role.Button) {
                haptics.confirm()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.ArrowUpward,
            contentDescription = stringResource(R.string.chat_composer_send),
            tint = if (enabled) colors.onAccent else colors.labelDimmed,
            modifier = Modifier.size(20.dp),
        )
    }
}


