package com.labteto.dshmobile.ui.screens.main

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.media.sampleSizeFor
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswer
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswerItem
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionOption
import com.labteto.dshmobile.data.QuestionOutcome
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.ApprovalPanel
import com.labteto.dshmobile.ui.components.ConnectionBanner
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.PlanReviewPanel
import com.labteto.dshmobile.ui.components.planReviewOf
import com.labteto.dshmobile.ui.components.QuestionsPanel
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsTheme
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import kotlinx.coroutines.launch

/**
 * The chat surface: chrome, transcript or trajectory, the persistent docks, and the composer.
 *
 * Everything below the tabs stays outside the tab swap on purpose — you can keep typing, and keep
 * answering an approval, while reading the trajectory, and the keyboard-attached surface never
 * animates out from under the cursor.
 */
@Composable
fun ChatScreen(
    onOpenDetails: () -> Unit,
    onOpenDrawer: () -> Unit,
    detailsOpen: Boolean,
) {
    val store = rememberSessionStore()
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    val context = LocalContext.current
    val toast = rememberDsToast()

    val conversation by store.currentConversation.collectAsStateWithLifecycle()
    val currentSessionId by store.currentSessionId.collectAsStateWithLifecycle()
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val toolViews by store.toolViews.collectAsStateWithLifecycle()
    val models by store.models.collectAsStateWithLifecycle()
    val skills by store.skills.collectAsStateWithLifecycle()
    val commands by store.commands.collectAsStateWithLifecycle()
    val commandsAvailable by store.commandsAvailable.collectAsStateWithLifecycle()
    val subagents by store.subagents.collectAsStateWithLifecycle()
    val subagentConversation by store.subagentConversation.collectAsStateWithLifecycle()
    val subagentMode by store.subagentMode.collectAsStateWithLifecycle()
    val connectionError by store.connectionError.collectAsStateWithLifecycle()
    val loadingOlder by store.loadingOlder.collectAsStateWithLifecycle()
    val loadOlderFailed by store.loadOlderFailed.collectAsStateWithLifecycle()
    val pendingApproval by store.pendingApproval.collectAsStateWithLifecycle()
    val pendingQuestions by store.pendingQuestions.collectAsStateWithLifecycle()
    val permissions by store.permissions.collectAsStateWithLifecycle()
    val pendingPermission by store.pendingPermission.collectAsStateWithLifecycle()
    val agentPresets by store.agentPresets.collectAsStateWithLifecycle()
    val sessionStats by store.sessionStats.collectAsStateWithLifecycle()
    val tokenUsage by store.tokenUsage.collectAsStateWithLifecycle()
    val contextBreakdown by store.contextBreakdown.collectAsStateWithLifecycle()
    val contextPressure by store.contextPressure.collectAsStateWithLifecycle()
    val imageLimits by store.imageLimits.collectAsStateWithLifecycle()

    val currentSession = sessions.firstOrNull { it.sessionId == currentSessionId }
    val title = currentSession?.title
        ?: currentSession?.cwd?.let { basename(it) }
        ?: currentSessionId.orEmpty()

    var draft by rememberSaveable(currentSessionId) { mutableStateOf("") }
    var mode by rememberSaveable(currentSessionId) { mutableStateOf("queue") }
    var tab by rememberSaveable { mutableStateOf(ChatTab.Chat) }
    val attachments = remember(currentSessionId) { mutableStateListOf<PendingAttachment>() }

    var sheet by remember { mutableStateOf<ChatSheet?>(null) }

    // Hoisted above the tab swap so each view keeps its own scroll position across switches.
    val chatListState = rememberLazyListState()
    val trajectoryListState = rememberLazyListState()

    val commandFailed = stringResource(R.string.err_command_failed)
    val unknownCommand = stringResource(R.string.err_command_unknown)

    fun report(outcome: com.labteto.dshmobile.data.CommandOutcome) {
        when (outcome) {
            is com.labteto.dshmobile.data.CommandOutcome.Ok ->
                outcome.text?.takeIf { it.isNotBlank() }?.let { toast.second(it) }
            is com.labteto.dshmobile.data.CommandOutcome.Unknown ->
                toast.second(unknownCommand.format(outcome.line))
            is com.labteto.dshmobile.data.CommandOutcome.Failed ->
                toast.second(commandFailed.format(outcome.message))
        }
    }

    val answerRefused = stringResource(R.string.questions_answer_refused)
    val answerUnsent = stringResource(R.string.questions_answer_unsent)

    /**
     * What to tell the user about a question response, or null when the harness took it.
     *
     * A refusal is worth naming rather than swallowing: the host's wait stays open and the tool
     * call that opened it stays blocked, so a card that quietly did nothing would leave the session
     * stuck with no explanation.
     */
    fun refusalOf(outcome: QuestionOutcome): String? = when (outcome) {
        is QuestionOutcome.Accepted -> null
        is QuestionOutcome.Refused -> answerRefused.format(outcome.reason)
        is QuestionOutcome.Unsent -> answerUnsent
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val resolver = context.contentResolver
            val mediaType = resolver.getType(uri)
            val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val limits = imageLimits
            // The host publishes its own attachment bounds; checking them here means a rejection
            // surfaces before the round trip rather than as a failed turn.
            if (bytes == null || bytes.isEmpty() || mediaType == null ||
                (limits != null && !limits.accepts(mediaType, bytes.size)) ||
                (limits == null && bytes.size > 5_242_880)
            ) {
                toast.second(context.getString(R.string.err_attachment_failed))
                return@launch
            }
            attachments.add(
                PendingAttachment(
                    mediaType = mediaType,
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    preview = decodePreview(bytes),
                ),
            )
        }
    }

    fun send(text: String) {
        val pending = attachments.toList()
        if (text.isBlank() && pending.isEmpty()) return
        // A slash line that names a registered command is not a message: `session.prompt` would
        // hand it to the model verbatim, so it has to be recognised here and written through the
        // command gateway. A miss falls through to the prompt path — that is how skills work.
        val submission = adjudicate(text, commands, pending.isNotEmpty())
        attachments.clear()
        if (submission is Submission.Command) {
            scope.launch { report(store.runCommand(submission.line)) }
            return
        }
        scope.launch {
            if (pending.isEmpty()) {
                store.prompt(text, mode)
            } else {
                // The prompt API takes one image per call; the text rides the first so the message
                // stays a single turn rather than fragmenting across attachments.
                pending.forEachIndexed { index, attachment ->
                    store.promptWithImage(
                        text = if (index == 0) text else "",
                        mode = mode,
                        mediaType = attachment.mediaType,
                        base64Data = attachment.base64,
                        name = attachment.name,
                    )
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        // The activity draws edge to edge, so every top-level surface has to consume the insets
        // itself or the chrome ends up underneath the status bar. safeDrawing covers the status
        // bar, the gesture area and the keyboard in one modifier.
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            ChatTopBar(
                title = title,
                running = conversation?.running == true,
                models = models,
                agentPresetLabel = currentSession?.agentPreset?.let { agentPresetLabel(it, agentPresets) },
                subagentCount = subagents.size,
                detailsOpen = detailsOpen,
                tab = tab,
                onOpenDrawer = onOpenDrawer,
                onOpenModels = { sheet = ChatSheet.Models },
                onOpenPresets = {
                    scope.launch { store.refreshAgentPresets() }
                    sheet = ChatSheet.Presets
                },
                onOpenSubagents = { sheet = ChatSheet.Subagents },
                onOpenDetails = onOpenDetails,
                onTabChange = { tab = it },
            )

            connectionError?.let { ConnectionBanner(it) }
            if (conversation?.gap == true) {
                ConnectionBanner(stringResource(R.string.common_reconnecting))
            }

            val nodeContext = ChatNodeContext(
                nodes = conversation?.nodes ?: emptyList(),
                toolViews = toolViews,
                running = conversation?.running == true,
                cwd = currentSession?.cwd,
                onOpenSubagent = { childId ->
                    scope.launch { store.openSubagentTranscript(childId) }
                    sheet = ChatSheet.Subagents
                },
                onBranchFrom = { seq -> scope.launch { currentSessionId?.let { store.forkSession(it, seq) } } },
                onFeedback = { _, positive ->
                    scope.launch { report(store.runCommand(if (positive) "/feedback +1" else "/feedback -1")) }
                },
            )

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
                modifier = Modifier.weight(1f),
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
                        onLoadOlder = { scope.launch { store.loadOlder() } },
                    )
                    ChatTab.Trajectory -> TrajectoryTab(
                        conversation = conversation,
                        stats = sessionStats,
                        usage = tokenUsage,
                        cwd = currentSession?.cwd,
                        listState = trajectoryListState,
                    )
                }
            }

            conversation?.let { conv ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    parseTodos(conv.projections["todos"])?.let { TodoDock(it) }
                    parseGoal(conv.projections["goal"])?.let { GoalBar(it, store) }
                    QueueDock(conv.queue, store)
                }
            }

            // Server-initiated requests take over the bottom of the screen: they block the turn,
            // so burying them behind a scroll would strand the session.
            val approval = pendingApproval
            if (approval != null && approval.sessionId == currentSessionId) {
                ApprovalPanel(
                    toolName = approval.toolName,
                    reason = approval.reason,
                    onAllow = {
                        scope.launch { store.respondApproval(approval.sessionId, approval.approvalId, true) }
                    },
                    onReject = {
                        scope.launch { store.respondApproval(approval.sessionId, approval.approvalId, false) }
                    },
                )
            }
            val questions = pendingQuestions
            if (questions != null && questions.sessionId == currentSessionId) {
                var planBusy by remember(questions.rpcId) { mutableStateOf(false) }
                // A plan review rides the question channel but is a different decision, so it gets
                // the card built for it. The narrowing decides which — and hands back anything the
                // card could not answer in full, because the card answers one question and the host
                // refuses an answer batch shorter than the request it resolves.
                val review = remember(questions.rpcId) { planReviewOf(questions.items) }
                if (review != null) {
                    fun settle(block: suspend () -> QuestionOutcome) {
                        planBusy = true
                        scope.launch {
                            refusalOf(block())?.let {
                                planBusy = false
                                toast.second(it)
                            }
                        }
                    }
                    fun decide(option: AskUserQuestionOption) = settle {
                        store.answerQuestions(
                            questions.sessionId,
                            AskUserQuestionAnswer(
                                listOf(AskUserQuestionAnswerItem(review.id, listOf(option.label))),
                            ),
                        )
                    }
                    PlanReviewPanel(
                        review = review,
                        busy = planBusy,
                        onApprove = { decide(review.approve) },
                        onDecline = { review.decline?.let { decide(it) } },
                        // Wanting to talk it over first is not one of the options the asker stated,
                        // so it ends the request rather than answering it with the refusal.
                        onDiscuss = {
                            draft = ""
                            settle { store.dismissQuestions(questions.sessionId) }
                        },
                    )
                } else {
                    QuestionsPanel(
                        requestKey = questions.rpcId,
                        questions = questions.items,
                        onSubmit = { answer ->
                            refusalOf(store.answerQuestions(questions.sessionId, answer))
                        },
                        onDismiss = { refusalOf(store.dismissQuestions(questions.sessionId)) },
                    )
                }
            }

            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                attachments = attachments,
                onRemoveAttachment = { index -> attachments.removeAt(index) },
                permissions = permissions,
                pendingPermission = pendingPermission,
                onPermissionPick = { value -> scope.launch { report(store.setPermissionPreset(value)) } },
                contextBreakdown = contextBreakdown,
                contextPressure = contextPressure,
                running = conversation?.running == true,
                enabled = currentSessionId != null,
                onOpenSheet = { sheet = ChatSheet.Commands },
                onSend = ::send,
                onStop = { scope.launch { store.cancelTurn() } },
            )

            StatsFooter(stats = sessionStats, usage = tokenUsage)
        }
        DsToastHost(toast, modifier = Modifier.fillMaxWidth())
    }

    when (sheet) {
        ChatSheet.Commands -> CommandSheet(
            commands = commands,
            commandsAvailable = commandsAvailable,
            skills = skills,
            mode = mode,
            running = conversation?.running == true,
            canAttach = currentSessionId != null,
            onModeChange = { mode = it },
            onAttach = { imagePicker.launch("image/*") },
            onRunCommand = { line -> scope.launch { report(store.runCommand(line)) } },
            onPrefillDraft = { prefix -> draft = prefix },
            onDismiss = { sheet = null },
        )
        ChatSheet.Models -> ModelsSheet(models = models, store = store, onDismiss = { sheet = null })
        ChatSheet.Presets -> PresetsSheet(
            presets = agentPresets,
            currentPreset = currentSession?.agentPreset,
            sessionBlank = currentSession?.blank ?: false,
            store = store,
            onDismiss = { sheet = null },
        )
        ChatSheet.Subagents -> SubagentsSheet(
            store = store,
            entries = subagents,
            conversation = subagentConversation,
            mode = subagentMode,
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

/** Which sheet, if any, is open over the chat surface. */
private enum class ChatSheet { Commands, Models, Presets, Subagents }


/**
 * A thumbnail for the composer strip, decoded straight off the picked bytes.
 *
 * This one *does* need a bounds pass: the picker hands over raw bytes with no intrinsic size
 * attached, unlike an attachment reference that already carries its dimensions.
 */
private fun decodePreview(bytes: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, PREVIEW_WIDTH_PX)
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

/** The composer thumbnail is 56dp; decoding much past that is wasted memory. */
private const val PREVIEW_WIDTH_PX = 224
