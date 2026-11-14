package com.labteto.dshmobile.ui.screens.main

import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import android.net.Uri
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.labteto.dshmobile.ui.adaptive.ListControl
import com.labteto.dshmobile.ui.adaptive.TabletopSplit
import com.labteto.dshmobile.ui.adaptive.readingGutter
import com.labteto.dshmobile.ui.adaptive.tabletopRegions
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.ui.media.sampleSizeFor
import com.labteto.dshmobile.ui.rememberHostsStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswer
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswerItem
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionOption
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.CommandSubmitAttachment
import com.labteto.dshmobile.core.wire.dto.ImageLimitsView
import com.labteto.dshmobile.data.CommandOutcome
import com.labteto.dshmobile.data.PermissionSwitchResult
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
import com.labteto.dshmobile.ui.input.AppShortcut
import com.labteto.dshmobile.ui.input.DropHighlight
import com.labteto.dshmobile.ui.input.LocalShortcutRouter
import com.labteto.dshmobile.ui.input.attachmentDropTarget
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.screens.main.commands.CommandContext
import com.labteto.dshmobile.ui.screens.main.commands.CommandDecorations
import com.labteto.dshmobile.ui.screens.main.commands.CommandUiSpec
import com.labteto.dshmobile.ui.screens.main.commands.PopupSelectSheet
import com.labteto.dshmobile.ui.screens.main.commands.RewindDecoration
import com.labteto.dshmobile.ui.screens.main.commands.SlashMenu
import com.labteto.dshmobile.ui.screens.main.commands.SlashRow
import com.labteto.dshmobile.ui.screens.main.commands.activeSlash
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.components.AnimatedPresence
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsType
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.labteto.dshmobile.core.session.readImageBounded
import com.labteto.dshmobile.core.wire.dto.ImageRejection

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
    /** A subagent the Details panel asked to show; the Subagents sheet opens on it. */
    subagentRequest: String? = null,
    /** Called once the request has been taken, so the same child can be asked for again later. */
    onSubagentRequestHandled: () -> Unit = {},
    /** What the top bar's leading button does: open the drawer, toggle a docked list, or nothing. */
    listControl: ListControl = ListControl.OpenDrawer,
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
) {
    val store = rememberSessionStore()
    val hostsStore = rememberHostsStore()
    val appSettings by hostsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    val context = LocalContext.current
    val toast = rememberDsToast()

    val conversation by store.currentConversation.collectAsStateWithLifecycle()
    val currentSessionId by store.currentSessionId.collectAsStateWithLifecycle()
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val models by store.models.collectAsStateWithLifecycle()
    val skills by store.skills.collectAsStateWithLifecycle()
    val commands by store.commands.collectAsStateWithLifecycle()
    val commandsAvailable by store.commandsAvailable.collectAsStateWithLifecycle()
    val subagents by store.subagents.collectAsStateWithLifecycle()
    val subagentConversation by store.subagentConversation.collectAsStateWithLifecycle()
    val subagentMode by store.subagentMode.collectAsStateWithLifecycle()
    // Null for an ordinary session, non-null while a subagent child is open. A child is the
    // harness's to drive, not the reader's: which controls still mean anything depends on it.
    val currentSubagent by store.currentSubagent.collectAsStateWithLifecycle()
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
    val quota by store.quotaStatus.collectAsStateWithLifecycle()
    val quotaSelection by store.modelSelection.collectAsStateWithLifecycle()
    val quotaProvider = store.quotaProvider()
    val quotaNow = rememberQuotaClock(store, Triple(currentSessionId, quotaProvider, quotaSelection))
    val quotaLimit = remember(conversation?.nodes) { conversation?.nodes?.let { com.labteto.dshmobile.core.usage.limitHit(it) } }

    val currentSession = sessions.firstOrNull { it.sessionId == currentSessionId }
    val workingDirectory = com.labteto.dshmobile.core.session.effectiveWorkingDirectory(conversation?.projections, currentSession?.cwd)
    val title = currentSession?.title
        ?: currentSession?.cwd?.let { basename(it) }
        ?: currentSessionId.orEmpty()

    val connection by store.connectionState.collectAsStateWithLifecycle()
    val hostKey = connection.host?.let { "${it.baseUrl}|${it.id}" }.orEmpty()
    val composer = remember(hostKey, currentSessionId) {
        store.composers.get(ComposerKey(hostKey, currentSessionId.orEmpty()))
    }
    var referencePicker by remember(composer.key) { mutableStateOf<AtQuery?>(null) }
    var draft by composer::text
    var sentTick by remember { mutableIntStateOf(0) }
    val mode by composer::mode
    LaunchedEffect(composer, appSettings.defaultDeliveryMode) {
        composer.applyDefaultMode(appSettings.defaultDeliveryMode)
    }
    var tab by rememberSaveable { mutableStateOf(ChatTab.Chat) }
    val attachments = composer.attachments

    var panelKey by remember { mutableStateOf<ComposerKey?>(null) }
    var feedback by remember { mutableStateOf<Triple<ComposerKey, String, Boolean>?>(null) }
    var sheet by remember { mutableStateOf<ChatSheet?>(null) }
    // A message chosen from the history sheet, waiting for the transcript to scroll to it. Held
    // here rather than inside the transcript because the pick is made in a sheet that outlives no
    // layout of its own, and it has to survive the sheet closing.
    var pendingJumpSeq by remember { mutableStateOf<Long?>(null) }
    // Each event's time by sequence, for the transcript's timestamps and the history sheet's. Built
    // once per journal rather than on every recomposition: a streaming reply recomposes this screen
    // many times a second without adding a single durable event.
    val eventTimes = remember(conversation?.journal) {
        conversation?.journal?.associate { it.seq to it.time }.orEmpty()
    }
    // The goal loop's round counter, which the projection does not carry; read once per journal.
    val goalRounds = remember(conversation?.journal) {
        goalRoundsStarted(conversation?.journal.orEmpty())
    }
    // A decorated command's picker (the phone's `popupSelect`), keyed by the command it serves.
    var popup by remember(composer.key) { mutableStateOf<Pair<String, CommandUiSpec.PopupSelect>?>(null) }
    // A message held and sent to rewind, by sequence and preview: rewind's picker, opened on its pick.
    var rewindFrom by remember(composer.key) { mutableStateOf<Pair<Long, String>?>(null) }
    // A held prompt waiting on the reader's say-so before it replaces a draft they had started.
    var replaceDraftWith by remember(composer.key) { mutableStateOf<String?>(null) }
    var composerFocusTick by remember { mutableIntStateOf(0) }

    // The Details panel's Subagents rows. The sheet closes the transcript stream when it goes, so
    // opening it here, and only here, leaves no stream behind.
    LaunchedEffect(subagentRequest) {
        val childId = subagentRequest ?: return@LaunchedEffect
        sheet = ChatSheet.Subagents
        // On the screen's own scope: clearing the request below restarts this effect, and a load
        // tied to it would be cancelled the moment it began.
        scope.launch { store.openSubagentTranscript(childId) }
        onSubagentRequestHandled()
    }

    // Hoisted above the tab swap so each view keeps its own scroll position across switches.
    val chatListState = rememberLazyListState()
    // What the transcript has already scrolled for, kept here for the same reason as its list state.
    val transcriptMemory = remember { TranscriptMemory() }
    // Which thinking panels are open, per message. One per open session: a reply settling replaces
    // its row, and the reader's open panel has to outlive that.
    val assistantMemory = remember(hostKey, currentSessionId) { AssistantMemory() }
    val trajectoryListState = rememberLazyListState()
    val filesListState = rememberLazyListState()
    // The session's changed files, shared by the Files tab label and the tab itself.
    val sessionFiles = remember(conversation?.nodes) { sessionChangedFiles(conversation?.nodes.orEmpty()) }

    val commandFailed = stringResource(R.string.err_command_failed)
    val unknownCommand = stringResource(R.string.err_command_unknown)

    fun report(outcome: CommandOutcome) {
        when (outcome) {
            is CommandOutcome.Ok -> outcome.text?.takeIf { it.isNotBlank() }?.let { toast.second(it) }
            is CommandOutcome.Unknown -> toast.second(unknownCommand.format(outcome.line))
            is CommandOutcome.Failed -> toast.second(commandFailed.format(outcome.message))
        }
    }

    val permissionUnconfirmed = stringResource(R.string.permission_unconfirmed)

    /** Say so when a permission switch failed or went unconfirmed; the chip already shows the rest. */
    fun reportPermission(result: PermissionSwitchResult) {
        when (result) {
            is PermissionSwitchResult.Failed -> report(result.outcome)
            is PermissionSwitchResult.Unconfirmed -> toast.second(permissionUnconfirmed)
            else -> Unit
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

    /**
     * Prepare picked, dropped or pasted images and add them to [target]'s tray. One path for all
     * three, so a dropped image is held to the same limits, and refused in the same words, as one
     * picked from the gallery.
     */
    fun addImages(target: ComposerDraft, uris: List<Uri>, limits: ImageLimitsView) {
        if (uris.isNotEmpty()) {
            target.preparing = true
            store.composers.scope.launch {
                val failures = mutableListOf<String>()
                try {
                    val existing = target.attachments.filterIsInstance<PendingAttachment.Image>()
                    val accepted = withContext(Dispatchers.IO) {
                        val selected = mutableListOf<PendingAttachment.Image>()
                        val admission = com.labteto.dshmobile.core.session.PhotoBatchAdmission(limits, existing.map { it.bytes })
                        for (uri in uris) {
                            try {
                                if (admission.full) {
                                    failures.add(imageRejectionText(context, ImageRejection.TOO_MANY, limits))
                                    continue
                                }
                                val resolver = context.contentResolver
                                val mediaType = resolver.getType(uri)
                                val bytes = (resolver.openInputStream(uri) ?: throw java.io.IOException("Unreadable image" )).use {
                                    readImageBounded(it, limits.maxImageBytes.coerceIn(0, Int.MAX_VALUE.toLong() - 1))
                                }
                                if (bytes == null) {
                                    failures.add(imageRejectionText(context, ImageRejection.TOO_LARGE, limits))
                                    continue
                                }
                                val pick = decodePick(bytes)
                                val rejection = admission.accept(
                                    mediaType.orEmpty(), pick.detectedMediaType, bytes.size, pick.width, pick.height,
                                )
                                if (rejection != null) {
                                    failures.add(imageRejectionText(context, rejection, limits))
                                    continue
                                }
                                selected.add(PendingAttachment.Image(
                                    mediaType = mediaType.orEmpty(), base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                                    preview = pick.preview, bytes = bytes.size, width = pick.width, height = pick.height,
                                ))
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { failures.add(context.getString(R.string.err_attachment_failed)) }
                        }
                        selected
                    }
                    target.attachments.addAll(accepted)
                    if (failures.isNotEmpty()) toast.second(context.getString(
                        R.string.photos_skipped, failures.size, failures.distinct().joinToString("; "),
                    ))
                } finally { target.preparing = false }
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val selection = store.composers.imagePickTarget
        store.composers.imagePickTarget = null
        if (selection != null) addImages(selection.first, uris, selection.second)
    }

    /** Replace one pending file by identity; a chip that was removed meanwhile is left removed. */
    fun updateFile(target: ComposerDraft, id: String, transform: (PendingAttachment.File) -> PendingAttachment.File) {
        val attachments = target.attachments
        val index = attachments.indexOfFirst { it is PendingAttachment.File && it.id == id }
        if (index >= 0) attachments[index] = transform(attachments[index] as PendingAttachment.File)
    }

    /**
     * Stream one picked file to the host and settle its chip.
     *
     * The upload starts the moment the file is picked, as the web client's does, so by the time
     * the message is sent the receipt is usually already there; the chip shows progress until it
     * is, and the send affordance waits for it.
     */
    fun startUpload(file: PendingAttachment.File, target: ComposerDraft = composer) {
        updateFile(target, file.id) { it.copy(state = FileUploadState.Uploading(0)) }
        store.composers.scope.launch {
            val result = store.uploadFile(
                name = file.name,
                targetSessionId = target.key.sessionId,
                targetHost = target.key.host,
                size = file.size,
                open = { runCatching { context.contentResolver.openInputStream(file.uri) }.getOrNull() },
                onProgress = { sent -> updateFile(target, file.id) { it.copy(state = FileUploadState.Uploading(sent)) } },
            )
            updateFile(target, file.id) {
                when (result) {
                    is RpcResult.Ok -> it.copy(state = FileUploadState.Ready(result.value.receiptId, result.value.file))
                    is RpcResult.Err -> it.copy(state = FileUploadState.Failed(result.error.message))
                }
            }
        }
    }

    /** Add one picked, dropped or pasted file to [target]'s tray and start its upload. */
    fun addFile(target: ComposerDraft, uri: Uri) {
        val (name, size) = describeDocument(context.contentResolver, uri)
        if (name == null) {
            toast.second(context.getString(R.string.err_file_read_failed))
            return
        }
        val file = PendingAttachment.File(
            id = UUID.randomUUID().toString(),
            uri = uri,
            name = name,
            size = size,
            state = FileUploadState.Uploading(0),
        )
        target.attachments.add(file)
        startUpload(file, target)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = store.composers.filePickTarget
        store.composers.filePickTarget = null
        if (uri != null && target != null) addFile(target, uri)
    }

    /**
     * Take files that arrived by drop or by paste: images go through the photo path, everything else
     * is uploaded as a file. A child session refuses files (the host refuses every subagent upload),
     * so there only images are taken, and the rest is said not to be.
     * Returns whether anything was taken.
     */
    fun attachArrivals(uris: List<Uri>): Boolean {
        if (uris.isEmpty() || currentSessionId == null || composer.preparing || composer.submitting) return false
        val (images, others) = uris.partition { context.contentResolver.getType(it)?.startsWith("image/") == true }
        addImages(composer, images, imageLimits ?: ImageLimitsView())
        if (others.isNotEmpty()) {
            if (currentSubagent == null) others.forEach { addFile(composer, it) } else toast.second(context.getString(R.string.err_attachment_failed))
        }
        return images.isNotEmpty() || (others.isNotEmpty() && currentSubagent == null)
    }

    /** What a decoration gets to work with: the open session's conversation, a command runner, the draft. */
    fun commandContext(sessionId: String, host: String): CommandContext = object : CommandContext {
        override val conversation get() = store.currentConversation.value
        override val draft get() = composer.text
        override suspend fun run(line: String) = store.runCommand(line, emptyList(), sessionId, host)
        override fun setDraft(text: String) { composer.text = text; composer.selection = text.length }
    }

    /**
     * The phone's `commandUi.decorate` dispatch: a bare `/name` whose decoration is available
     * opens that decoration's UI and submits nothing — exactly what the web client does before it
     * would otherwise hand the line to `commands/execute`. Anything else returns false and takes
     * the ordinary path, and so does a same-named command from a plugin the decoration is not a
     * port of.
     */
    fun dispatchDecorated(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/") || trimmed.any { it.isWhitespace() }) return false
        val name = trimmed.drop(1)
        val decoration = CommandDecorations.forName(name) ?: return false
        if (commands.none { it.name == name } || !decoration.recognizes(commands)) return false
        if (!decoration.available(conversation)) return false
        when (val ui = decoration.ui) {
            is CommandUiSpec.PopupSelect -> popup = name to ui
            is CommandUiSpec.Action -> scope.launch {
                try {
                    ui.run(commandContext(composer.key.sessionId, composer.key.host))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast.second(e.message ?: context.getString(R.string.panel_failed))
                }
            }
        }
        return true
    }

    /**
     * Put a sent prompt back in the composer to be corrected. An empty draft just takes it; one the
     * reader has started is asked about first, because replacing it would lose what they typed.
     */
    fun editPrompt(text: String) {
        if (draft.isBlank() || draft == text) {
            draft = text
            composer.selection = text.length
            composerFocusTick++
        } else {
            replaceDraftWith = text
        }
    }

    /** An `@` mention of a workspace path at the end of the draft, as the reference picker writes one. */
    fun mention(path: String, folder: Boolean) {
        val mention = fileMention(path, folder) ?: return
        val end = composer.text.length
        composer.insertReference(end, end, mention, path, if (folder) "folder" else "file", path)
        composerFocusTick++
    }

    /** [deliveryOverride] is a mode picked for this message alone, from holding send. */
    fun send(text: String, deliveryOverride: String? = null) {
        if (composer.preparing || composer.submitting) { draft = text; return }
        sentTick++
        if (attachments.isEmpty() && dispatchDecorated(text)) return
        // Steering an idle session is refused by the host, so a Steer chosen while nothing runs
        // (or chosen mid-turn that has since ended) goes out as a normal queued prompt.
        val delivery = promptDelivery(deliveryOverride ?: mode, conversation?.running == true)
        val targetId = composer.key.sessionId
        val targetHost = composer.key.host
        val pending = attachments.toList()
        if (text.isBlank() && pending.isEmpty()) return
        val images = pending.filterIsInstance<PendingAttachment.Image>()
        val files = pending.filterIsInstance<PendingAttachment.File>()
        // A file without a receipt cannot be cited. The send affordance already waits for the
        // chips, but a keyboard send lands here too.
        if (files.any { it.state !is FileUploadState.Ready }) {
            draft = text
            toast.second(
                context.getString(
                    if (files.any { it.state is FileUploadState.Failed }) R.string.chat_attachment_upload_failed
                    else R.string.chat_attachment_still_uploading,
                ),
            )
            return
        }
        val receipts = files.mapNotNull { it.receiptId }
        // A slash line that names a registered command is not a message: `session/prompt` would
        // hand it to the model verbatim, so it has to be recognised here and written through the
        // command gateway. A miss falls through to the prompt path — that is how skills work.
        when (val submission = adjudicate(text, commands, pending.size, store.commandAttachmentsSupported)) {
            is Submission.Refused -> {
                // Nothing is sent and nothing is dropped. The composer clears the draft on its way
                // here, so put it back, and leave the attachments alone — a refusal the user cannot
                // act on without re-picking every one is not much of a refusal.
                draft = text
                val message = when (submission.reason) {
                    RefusalReason.COMMAND_TAKES_NO_ATTACHMENTS -> R.string.err_command_no_images
                    RefusalReason.HOST_TOO_OLD -> R.string.err_command_images_host
                }
                toast.second(context.getString(message, submission.command))
            }

            is Submission.Command -> {
                composer.submitting = true
                attachments.clear()
                val submitted = images.map { it.encoded().asSubmit() } +
                    receipts.map { CommandSubmitAttachment.File(it) }
                store.composers.scope.launch {
                    try {
                    val outcome = store.runCommand(submission.line, submitted, targetId, targetHost)
                    // An attachment-carrying command consumes its attachments only on success, as
                    // the harness client does: an error result is something to correct, and
                    // correcting it should not start with picking every file again. A plain
                    // command that fails keeps today's behaviour, because its whole submission was
                    // the line. The restore only lands in a composer nobody has touched meanwhile —
                    // the call is in flight while the user can still type and pick.
                    if (outcome is CommandOutcome.Failed) {
                        composer.restoreRejected(text, pending)
                    }
                    report(outcome)
                    } catch (e: kotlinx.coroutines.CancellationException) { composer.restoreRejected(text, pending); throw e }
                    catch (e: Exception) { composer.restoreRejected(text, pending); toast.second(e.message ?: context.getString(R.string.panel_failed)) }
                    finally { composer.submitting = false }
                }
            }

            is Submission.Prompt -> {
                composer.submitting = true
                attachments.clear()
                store.composers.scope.launch {
                    try {
                    // One call, whatever the count. The host admits a prompt's images as a single
                    // batch, and that batch is the only thing its per-message count and total-size
                    // bounds are measured against — sending one image per call made a single
                    // message into several and put both limits permanently out of reach. Files
                    // ride the same call as receipts; a receipt the host refuses stays staged,
                    // so restoring the chips is enough to try again.
                    val outcome = if (pending.isEmpty()) {
                        store.prompt(text, delivery, targetId, targetHost)
                    } else {
                        store.promptWithAttachments(text, delivery, images.map { it.encoded() }, receipts, targetId, targetHost)
                    }
                    if (outcome !is PromptOutcome.Ok) {
                        composer.restoreRejected(text, pending)
                        toast.second(
                            if (outcome is PromptOutcome.Rejected) imageRejectionText(context, outcome.rejection, imageLimits, outcome.reason)
                            else (outcome as PromptOutcome.Failed).message,
                        )
                    }
                    } catch (e: kotlinx.coroutines.CancellationException) { composer.restoreRejected(text, pending); throw e }
                    catch (e: Exception) { composer.restoreRejected(text, pending); toast.second(e.message ?: context.getString(R.string.panel_failed)) }
                    finally { composer.submitting = false }
                }
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.labteto.dshmobile.ui.media.LocalAttachmentScope provides (composer.key.host to composer.key.sessionId),
        com.labteto.dshmobile.ui.components.LocalFileOpener provides { path: String ->
            store.panels.get(composer.key).open(path); panelKey = composer.key
        },
        com.labteto.dshmobile.ui.components.LocalFolderOpener provides { path: String ->
            store.panels.get(composer.key).browse(path); panelKey = composer.key
        },
    ) {
    // Files and text dropped from another window (the ChromeOS Files app, a browser, an app in the
    // next split) land in the tray and the draft, the same as picking them does.
    var dropActive by remember { mutableStateOf(false) }
    fun takeDrop(files: List<Uri>, text: String): Boolean {
        if (files.isNotEmpty()) return attachArrivals(files)
        if (text.isBlank() || currentSessionId == null) return false
        val at = composer.selection.coerceIn(0, draft.length)
        draft = draft.substring(0, at) + text + draft.substring(at)
        composer.selection = at + text.length
        composerFocusTick++
        return true
    }
    // Ctrl+K and Ctrl+/ move to the message box.
    val shortcutRouter = LocalShortcutRouter.current
    LaunchedEffect(shortcutRouter) {
        shortcutRouter.events.collect { if (it == AppShortcut.FocusComposer) composerFocusTick++ }
    }
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .attachmentDropTarget(
                enabled = currentSessionId != null,
                onActiveChange = { dropActive = it },
                onDrop = { files, text -> takeDrop(files, text) },
            ),
        color = colors.bgBase,
    ) {
        // The activity draws edge to edge, so every top-level surface has to consume the insets
        // itself or the chrome ends up underneath the status bar. safeDrawing covers the status
        // bar, the gesture area and the keyboard in one modifier.
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            ChatTopBar(
                title = title,
                running = conversation?.running == true,
                needsYou = pendingApproval?.sessionId == currentSessionId ||
                    pendingQuestions?.sessionId == currentSessionId,
                models = models,
                // A child session runs on the model its parent gave it, and the host refuses a
                // switch there; the chip states the model but offers no picker that could fail.
                modelsEnabled = currentSubagent == null,
                // The harness pins the preset once a session has started, so the chip — which
                // reads as a picker — is only offered while the session is still blank. After
                // that the preset is a fact, and the Details panel states it as one.
                agentPresetLabel = currentSession?.agentPreset
                    ?.takeIf {
                        // Pinning a preset resolves the ordinary agent, which a child's identity
                        // is not; the Details panel states the fact instead of offering a switch.
                        currentSubagent == null && agentPresets?.modeSelectionEnabled != false && currentSession.blank
                    }
                    ?.let { agentPresetLabel(it, agentPresets) },
                subagentCount = subagents.size,
                detailsOpen = detailsOpen,
                tab = tab,
                fileCount = sessionFiles.size,
                onOpenDrawer = onOpenDrawer,
                listControl = listControl,
                onOpenModels = { sheet = ChatSheet.Models },
                onOpenHistory = { sheet = ChatSheet.History },
                onOpenPresets = {
                    scope.launch { store.refreshAgentPresets() }
                    sheet = ChatSheet.Presets
                },
                onOpenSubagents = { sheet = ChatSheet.Subagents },
                onOpenDetails = onOpenDetails,
                onTabChange = { tab = it },
                onOpenWorkspace = { panelKey = composer.key },
                workspaceEnabled = currentSessionId != null,
                historyEnabled = currentSessionId != null,
                sessionOpen = currentSessionId != null,
            )

            AnimatedPresence(connectionError) {
                ConnectionBanner(
                    message = it,
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = { store.retryConnection() },
                )
            }
            AnimatedPresence(Unit.takeIf { conversation?.gap == true && connectionError == null }) {
                ConnectionBanner(stringResource(R.string.common_reconnecting), warning = true)
            }

            val nodeContext = ChatNodeContext(
                nodes = conversation?.nodes ?: emptyList(),
                eventTimes = eventTimes,
                running = conversation?.running == true,
                cwd = workingDirectory,
                onOpenSubagent = { childId ->
                    scope.launch { store.openSubagentTranscript(childId) }
                    sheet = ChatSheet.Subagents
                },
                onBranchFrom = { seq -> scope.launch { currentSessionId?.let { store.forkSession(it, seq) } } },
                onFeedback = { seq, positive ->
                    conversation?.nodes?.filterIsInstance<com.labteto.dshmobile.core.session.AssistantMessageNode>()
                        ?.firstOrNull { it.seq == seq }?.messageId?.let { feedback = Triple(composer.key, it, positive) }
                },
                // Lambdas rather than references, for the reason given at the composer's `onSend`:
                // a reference to a local function compares equal whatever composer it captured.
                onEditPrompt = if (currentSessionId != null) ({ text -> editPrompt(text) }) else null,
                // The rewind plugin's own picker refuses nothing while a turn runs, but withdrawing
                // the message a running turn is answering is not something to offer from a hold.
                onRewindTo = if (
                    currentSessionId != null && conversation?.running != true &&
                    commandsAvailable && RewindDecoration.recognizes(commands)
                ) {
                    { seq, preview -> rewindFrom = seq to preview }
                } else {
                    null
                },
                onMention = if (currentSessionId != null) ({ path, folder -> mention(path, folder) }) else null,
                assistantMemory = assistantMemory,
                permissionNames = remember(permissions) {
                    permissions?.options?.associate { it.value to it.name }.orEmpty()
                },
            )

            // Where this column starts in the window, for a tabletop split: the hinge is a fact about
            // the device, so it arrives in window coordinates and is turned into this column's own.
            var contentTopPx by remember { mutableIntStateOf(0) }
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { contentTopPx = it.positionInWindow().y.toInt() },
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
                            gutter = gutter,
                            onLoadOlder = { scope.launch { store.loadOlder() } },
                            jumpToSeq = pendingJumpSeq,
                            onJumpConsumed = { pendingJumpSeq = null },
                            sentTick = sentTick,
                            followStreaming = appSettings.followStreamingReply,
                            memory = transcriptMemory,
                            onSuggestion = if (currentSessionId != null) {
                                { text -> draft = text; composer.selection = text.length }
                            } else {
                                null
                            },
                        )
                        ChatTab.Trajectory -> TrajectoryTab(
                            conversation = conversation,
                            stats = sessionStats,
                            usage = tokenUsage,
                            cwd = workingDirectory,
                            listState = trajectoryListState,
                            gutter = gutter,
                            loadingOlder = loadingOlder,
                            loadOlderFailed = loadOlderFailed,
                            onLoadOlder = { scope.launch { store.loadOlder() } },
                        )
                        // The files this session touched, the list behind the tab's own count. It
                        // is a chat-owned view: the workspace browser is a separate screen opened
                        // from the folder button at the end of the same tab row.
                        ChatTab.Files -> SessionFilesTab(
                            files = sessionFiles,
                            cwd = workingDirectory,
                            listState = filesListState,
                            gutter = gutter,
                        )
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
                // so burying them behind a scroll would strand the session.
                val approval = pendingApproval?.takeIf { it.sessionId == currentSessionId }
                AnimatedPresence(approval, enter = DsAnimations.cardEnter, exit = DsAnimations.cardExit) { approval ->
                    // A refusal is said out loud here rather than swallowed, for the reason [refusalOf]
                    // gives: the host's wait — and the tool call behind it — stays open, and a panel
                    // that reported nothing would read as two buttons that do nothing.
                    fun decide(allow: Boolean) = scope.launch {
                        refusalOf(store.respondApproval(approval.sessionId, approval.approvalId, allow))
                            ?.let { toast.second(it) }
                    }
                    ApprovalPanel(
                        modifier = Modifier.padding(horizontal = gutter),
                        toolName = approval.toolName,
                        reason = approval.reason,
                        onAllow = { decide(true) },
                        onReject = { decide(false) },
                    )
                }
                val questions = pendingQuestions?.takeIf { it.sessionId == currentSessionId }
                AnimatedPresence(questions, enter = DsAnimations.cardEnter, exit = DsAnimations.cardExit) { questions ->
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
                            modifier = Modifier.padding(horizontal = gutter),
                            review = review,
                            busy = planBusy,
                            onApprove = { decide(review.approve) },
                            onDecline = { review.decline?.let { decide(it) } },
                            // Wanting to talk it over first is not one of the options the asker stated,
                            // so it ends the request rather than answering it with the refusal. The
                            // draft stays: what the reader typed is what they came to send next.
                            onDiscuss = {
                                settle { store.dismissQuestions(questions.sessionId) }
                            },
                        )
                    } else {
                        QuestionsPanel(
                            modifier = Modifier.padding(horizontal = gutter),
                            requestKey = questions.rpcId,
                            questions = questions.items,
                            onSubmit = { answer ->
                                refusalOf(store.answerQuestions(questions.sessionId, answer))
                            },
                            onDismiss = { refusalOf(store.dismissQuestions(questions.sessionId)) },
                        )
                    }
                }

                // Weighted questions are measured after the composer, so long questions cannot
                // consume its controls. The question area scrolls within its allotted height.
                ModernQuestions(store, currentSessionId, Modifier.weight(1f, fill = false).padding(horizontal = gutter))

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
                        .onSizeChanged { composerBlockPx = it.height },
                ) {
                    // The `/` autocomplete sits where the web's does: directly above the composer,
                    // only while the draft is a slash token being typed.
                    AnimatedPresence(activeSlash(draft, composer.selection)) { slash ->
                        SlashMenu(
                            query = slash,
                            commands = if (commandsAvailable) commands else emptyList(),
                            skills = skills,
                            onPick = { row ->
                                when (row) {
                                    is SlashRow.Command -> if (row.descriptor.input == null) {
                                        draft = ""
                                        send(row.descriptor.line)
                                    } else {
                                        draft = row.descriptor.draftPrefix
                                        composer.selection = draft.length
                                    }
                                    is SlashRow.Skill -> {
                                        draft = "/${row.entry.name} "
                                        composer.selection = draft.length
                                    }
                                }
                            },
                        )
                    }

                    // A child whose mode is `one-shot`, and one whose own descriptor has not arrived
                    // yet, refuses every prompt. Saying so above the composer beats a dead field that
                    // looks like a loading state: the transcript stays readable either way.
                    if (currentSubagent?.continuable == false) {
                        Text(
                            stringResource(R.string.subagents_readonly),
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
                        onLineCountChange = { composerLines = it },
                        references = composer.references,
                        onAddReference = {
                            referencePicker = activeReference(draft, composer.selection) ?: AtQuery(composer.selection, composer.selection, "")
                        },
                        onOpenReference = { reference ->
                            if (reference.kind == "session") scope.launch { store.openSession(reference.target) }
                            else {
                                val panel = store.panels.get(composer.key)
                                if (reference.kind == "folder") {
                                    panel.directory = reference.target
                                    panel.section = 0
                                    // Reopening the panel must load this target, not reuse the previous
                                    // directory's cached rows. Its existing launch effect loads null listings.
                                    panel.listing = null
                                } else panel.open(reference.target)
                                panelKey = composer.key
                            }
                        },
                        draft = draft,
                        onDraftChange = { draft = it },
                        cursor = composer.selection,
                        onCursorChange = { composer.selection = it; if (draft.take(it).endsWith("@")) referencePicker = activeReference(draft, it) },
                        attachments = attachments,
                        onRemoveAttachment = { index -> attachments.removeAt(index) },
                        onRetryAttachment = { index ->
                            (attachments.getOrNull(index) as? PendingAttachment.File)?.let { startUpload(it) }
                        },
                        // The permission preset is changed through `/permission`, a command the host
                        // resolves like any other agent operation — refused for a child session. No
                        // chip rather than a chip whose only outcome is an error.
                        permissions = permissions.takeIf { currentSubagent == null },
                        pendingPermission = pendingPermission,
                        onPermissionPick = { value -> scope.launch { reportPermission(store.setPermissionPreset(value)) } },
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
                        onOpenSheet = { sheet = ChatSheet.Commands },
                        // A lambda, not `::send`. The composer holds this through rememberUpdatedState,
                        // which keeps what it has when the new value is equal to it, and a reference to a
                        // local function equals every other reference to that function whatever it
                        // captured. Each session's `::send` compared equal to the first and was dropped, so
                        // the button went on sending with the attachment list of whichever session was
                        // open when this screen first composed. A lambda is rebuilt when what it captures
                        // changes and compares by identity, so the composer always holds the current one.
                        onSend = { text -> send(text) },
                        onSendWith = { text, delivery -> send(text, delivery) },
                        onStop = { scope.launch { store.cancelTurn() } },
                        focusTick = composerFocusTick,
                        // Ctrl+V with an image or a file on the clipboard attaches it; text is pasted as usual.
                        onPasteFiles = { uris -> attachArrivals(uris) },
                        // The page's attach button reaches the same picker the `+` sheet does, and a
                        // child session refuses attachments the way it refuses commands, so it gets
                        // no button rather than one that ends in an error.
                        onAttachFile = if (currentSubagent == null) {
                            {
                                if (!composer.preparing && store.composers.filePickTarget == null) {
                                    store.composers.filePickTarget = composer
                                    filePicker.launch(arrayOf("*/*"))
                                }
                            }
                        } else {
                            null
                        },
                    )

                    // The footer belongs under the card it describes and stays there while the card
                    // is in flow. Lifted, the drawer covers the space it would take, and a line of
                    // numbers under a floating card reads as a mistake; it comes back with the
                    // one-line composer.
                    if (!composerLifted) {
                        StatsFooter(
                            stats = sessionStats, usage = tokenUsage,
                            quotaText = usageFooterText(quota, quotaProvider, quotaLimit, quotaNow),
                        )
                    }
                }
            }
        }
        DropHighlight(visible = dropActive)
        DsToastHost(toast, modifier = Modifier.fillMaxWidth())
    }

    }
    referencePicker?.let { ReferencePicker(store, composer, it, onClose = { referencePicker = null }) }
    popup?.let { (name, spec) ->
        PopupSelectSheet(
            command = name,
            spec = spec,
            context = commandContext(composer.key.sessionId, composer.key.host),
            onDismiss = { popup = null },
        )
    }
    rewindFrom?.let { (seq, preview) ->
        val rewindContext = commandContext(composer.key.sessionId, composer.key.host)
        PopupSelectSheet(
            command = "rewind",
            spec = RewindDecoration.ui as CommandUiSpec.PopupSelect,
            context = rewindContext,
            onDismiss = { rewindFrom = null },
            initialStep = remember(seq) { RewindDecoration.fromMessage(rewindContext, seq, preview) },
        )
    }
    replaceDraftWith?.let { text ->
        ConfirmDialog(
            title = stringResource(R.string.hold_replace_draft_title),
            body = stringResource(R.string.hold_replace_draft_body),
            confirmLabel = stringResource(R.string.hold_replace_draft_confirm),
            onDismiss = { replaceDraftWith = null },
            onConfirm = {
                replaceDraftWith = null
                draft = text
                composer.selection = text.length
                composerFocusTick++
            },
        )
    }
    panelKey?.let { key ->
        WorkspacePanels(
            store,
            store.panels.get(key),
            onDismiss = { panelKey = null },
            // Mentioning closes the panel: the chip in the composer is where the reader goes next.
            onMention = { path, folder ->
                mention(path, folder)
                panelKey = null
            },
        )
    }
    feedback?.let { (key, id, positive) -> FeedbackDialog(store, key, id, positive) { feedback = null } }
    when (sheet) {
        ChatSheet.Commands -> CommandSheet(
            commands = commands,
            commandsAvailable = commandsAvailable,
            skills = skills,
            mode = mode,
            running = conversation?.running == true,
            canAttach = currentSessionId != null && !composer.preparing && !composer.submitting,
            // Files never reach a child: the host refuses every subagent upload, while image parts
            // ride `subagents/prompt` normally. The sheet states the reason instead of offering a
            // row that can only fail.
            fileAttachEnabled = currentSubagent == null,
            onModeChange = { composer.selectMode(it) },
            onAttach = {
                if (!composer.preparing && store.composers.imagePickTarget == null) {
                    store.composers.imagePickTarget = composer to (imageLimits ?: ImageLimitsView())
                    imagePicker.launch("image/*")
                }
            },
            onAttachFile = { store.composers.filePickTarget = composer; filePicker.launch(arrayOf("*/*")) },
            // The sheet only auto-runs commands that take no input at all, and a command that
            // takes no input takes no attachments either — so a pending attachment refuses here
            // for the same reason it refuses at the composer, rather than being silently dropped.
            onRunCommand = { line ->
                val name = line.removePrefix("/").substringBefore(' ')
                if (attachments.isEmpty()) {
                    if (!dispatchDecorated(line)) scope.launch { report(store.runCommand(line)) }
                } else {
                    toast.second(context.getString(R.string.err_command_no_images, name))
                }
            },
            // A picked command or skill goes in front of what the reader already typed, as its
            // argument, rather than replacing it.
            onPrefillDraft = { prefix ->
                val next = prefilledDraft(draft, prefix)
                draft = next
                composer.selection = next.length
                composerFocusTick++
            },
            onDismiss = { sheet = null },
        )
        ChatSheet.Models -> ModelsSheet(models = models, store = store, onDismiss = { sheet = null })
        ChatSheet.History -> HistorySheet(
            entries = remember(conversation?.nodes, conversation?.running, eventTimes) {
                historyEntries(
                    nodes = conversation?.nodes.orEmpty(),
                    running = conversation?.running == true,
                    eventTimes = eventTimes,
                )
            },
            hasMore = conversation?.hasMore == true,
            loadingOlder = loadingOlder,
            loadOlderFailed = loadOlderFailed,
            onLoadOlder = { scope.launch { store.loadOlder() } },
            onPick = { seq ->
                // Closing first: the scroll happens on the transcript's next composition, and
                // leaving the sheet over it would hide the very message the pick was for.
                pendingJumpSeq = seq
                sheet = null
                tab = ChatTab.Chat
            },
            onDismiss = { sheet = null },
            onEdit = if (currentSessionId != null) ({ text -> editPrompt(text) }) else null,
        )
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
private enum class ChatSheet { Commands, Models, History, Presets, Subagents }

/**
 * A picked document's display name and size, as its provider reports them.
 *
 * The name falls back to the last path segment when the provider offers none, and the size to
 * `-1` — the upload route accepts a chunked body, so an unknown length costs only the progress
 * ring. A null name means the provider answered nothing at all, which is a read failure.
 */
private fun describeDocument(resolver: android.content.ContentResolver, uri: android.net.Uri): Pair<String?, Long> {
    var name: String? = null
    var size = -1L
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
    }
    return (name ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }) to size
}


/**
 * What a bounds pass over the picked bytes tells us: the image's intrinsic size, the media type
 * its bytes actually are, and a thumbnail for the composer strip.
 *
 * A [width] of zero means the bytes did not parse as an image at all.
 */
internal data class DecodedPick(
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
internal fun decodePick(bytes: ByteArray): DecodedPick {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
    val width = bounds.outWidth.coerceAtLeast(0)
    val height = bounds.outHeight.coerceAtLeast(0)
    val preview = if (width <= 0) {
        null
    } else {
        runCatching {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(maxOf(width, height), PREVIEW_WIDTH_PX)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }.getOrNull()
    }
    return DecodedPick(if (preview == null) 0 else width, if (preview == null) 0 else height, bounds.outMimeType, preview)
}

/** The composer thumbnail is 56dp; decoding much past that is wasted memory. */
private const val PREVIEW_WIDTH_PX = 224

/** This height, held to [limit] when there is one: the room a composer has in the half below a hinge. */
private fun Dp.capTo(limit: Dp?): Dp = if (limit == null) this else minOf(this, limit)
