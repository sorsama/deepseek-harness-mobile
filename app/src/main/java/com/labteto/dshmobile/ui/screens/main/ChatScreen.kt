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
import com.labteto.dshmobile.core.wire.dto.ImageLimitsView
import com.labteto.dshmobile.data.CommandOutcome
import com.labteto.dshmobile.data.PromptOutcome
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

    fun report(outcome: CommandOutcome) {
        when (outcome) {
            is CommandOutcome.Ok -> outcome.text?.takeIf { it.isNotBlank() }?.let { toast.second(it) }
            is CommandOutcome.Unknown -> toast.second(unknownCommand.format(outcome.line))
            is CommandOutcome.Failed -> toast.second(commandFailed.format(outcome.message))
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
            if (bytes == null || bytes.isEmpty() || mediaType == null) {
                // Nothing came back from the provider at all — a read failure, not a refusal.
                toast.second(context.getString(R.string.err_attachment_failed))
                return@launch
            }
            // The host publishes its own attachment bounds, and its defaults stand in until the
            // projection arrives. Checking them here means a refusal lands while the picture is
            // still in hand, and names the same limit the host would have named.
            val limits = imageLimits ?: ImageLimitsView()
            val pick = decodePick(bytes)
            val rejection = limits.admitBatch(
                pendingCount = attachments.size,
                pendingBytes = attachments.sumOf { it.bytes.toLong() },
                addedBytes = bytes.size,
            ) ?: limits.admitImage(
                declaredMediaType = mediaType,
                detectedMediaType = pick.detectedMediaType,
                bytes = bytes.size,
                width = pick.width,
                height = pick.height,
            )
            if (rejection != null) {
                toast.second(imageRejectionText(context, rejection, limits))
                return@launch
            }
            attachments.add(
                PendingAttachment(
                    mediaType = mediaType,
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    preview = pick.preview,
                    bytes = bytes.size,
                    width = pick.width,
                    height = pick.height,
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
        when (val submission = adjudicate(text, commands, pending.size, store.commandImagesSupported)) {
            is Submission.Refused -> {
                // Nothing is sent and nothing is dropped. The composer clears the draft on its way
                // here, so put it back, and leave the images alone — a refusal the user cannot act
                // on without re-picking every attachment is not much of a refusal.
                draft = text
                val message = when (submission.reason) {
                    RefusalReason.COMMAND_TAKES_NO_IMAGES -> R.string.err_command_no_images
                    RefusalReason.HOST_TOO_OLD -> R.string.err_command_images_host
                }
                toast.second(context.getString(message, submission.command))
            }

            is Submission.Command -> {
                attachments.clear()
                val images = pending.map { it.encoded() }
                scope.launch {
                    val outcome = store.runCommand(submission.line, images)
                    // An image-carrying command consumes its images only on success, as the
                    // harness client does: an error result is something to correct, and correcting
                    // it should not start with picking every picture again. A plain command that
                    // fails keeps today's behaviour, because its whole submission was the line.
                    // The restore only lands in a composer nobody has touched meanwhile — the call
                    // is in flight while the user can still type and pick.
                    if (images.isNotEmpty() && outcome is CommandOutcome.Failed) {
                        if (draft.isBlank()) draft = text
                        if (attachments.isEmpty()) attachments.addAll(pending)
                    }
                    report(outcome)
                }
            }

            is Submission.Prompt -> {
                attachments.clear()
                scope.launch {
                    // One call, whatever the count. The host admits a prompt's images as a single
                    // batch, and that batch is the only thing its per-message count and total-size
                    // bounds are measured against — sending one image per call made a single
                    // message into several and put both limits permanently out of reach.
                    val outcome = if (pending.isEmpty()) {
                        store.prompt(text, mode)
                    } else {
                        store.promptWithImages(text, mode, pending.map { it.encoded() })
                    }
                    if (outcome is PromptOutcome.Rejected) {
                        if (draft.isBlank()) draft = text
                        if (attachments.isEmpty()) attachments.addAll(pending)
                        toast.second(
                            imageRejectionText(context, outcome.rejection, imageLimits, outcome.reason),
                        )
                    }
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
            // The sheet only auto-runs commands that take no input at all, and a command that
            // takes no input takes no images either — so a pending attachment refuses here for
            // the same reason it refuses at the composer, rather than being silently dropped.
            onRunCommand = { line ->
                val name = line.removePrefix("/").substringBefore(' ')
                if (attachments.isEmpty()) {
                    scope.launch { report(store.runCommand(line)) }
                } else {
                    toast.second(context.getString(R.string.err_command_no_images, name))
                }
            },
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
 * What a bounds pass over the picked bytes tells us: the image's intrinsic size, the media type
 * its bytes actually are, and a thumbnail for the composer strip.
 *
 * A [width] of zero means the bytes did not parse as an image at all.
 */
private data class DecodedPick(
    val width: Int,
    val height: Int,
    val detectedMediaType: String?,
    val preview: ImageBitmap?,
)

/**
 * Measure and thumbnail a picked image in one pass.
 *
 * The bounds pass was always here for the thumbnail's sample size; it also answers the two
 * questions the host's admission asks — how large is this, and is it really the type its provider
 * claims — so the picker can refuse an image before spending a round trip on it rather than after.
 */
private fun decodePick(bytes: ByteArray): DecodedPick {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
    val width = bounds.outWidth.coerceAtLeast(0)
    val height = bounds.outHeight.coerceAtLeast(0)
    val preview = if (width <= 0) {
        null
    } else {
        runCatching {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(width, PREVIEW_WIDTH_PX)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }.getOrNull()
    }
    return DecodedPick(width, height, bounds.outMimeType, preview)
}

/** The composer thumbnail is 56dp; decoding much past that is wasted memory. */
private const val PREVIEW_WIDTH_PX = 224
