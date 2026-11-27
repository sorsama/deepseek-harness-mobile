package com.labteto.dshmobile.ui.screens.main

import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.labteto.dshmobile.ui.addons.uiAddonsComposer
import com.labteto.dshmobile.ui.addons.uiAddonsContentTop
import com.labteto.dshmobile.ui.adaptive.ListControl
import com.labteto.dshmobile.ui.adaptive.TabletopSplit
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.ui.media.sampleSizeFor
import com.labteto.dshmobile.ui.rememberHostsStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.ImageLimitsView
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.input.AppShortcut
import com.labteto.dshmobile.ui.input.DropHighlight
import com.labteto.dshmobile.ui.input.LocalShortcutRouter
import com.labteto.dshmobile.ui.input.attachmentDropTarget
import com.labteto.dshmobile.ui.input.clipboardFiles
import com.labteto.dshmobile.ui.LocalSessionStore
import com.labteto.dshmobile.ui.screens.main.commands.CommandContext
import com.labteto.dshmobile.ui.screens.main.commands.CommandDecorations
import com.labteto.dshmobile.ui.screens.main.commands.CommandUiSpec
import com.labteto.dshmobile.ui.screens.main.commands.PopupSelectSheet
import com.labteto.dshmobile.ui.screens.main.commands.RewindDecoration
import com.labteto.dshmobile.ui.screens.main.commands.SlashMenu
import com.labteto.dshmobile.ui.screens.main.commands.SlashRow
import com.labteto.dshmobile.ui.screens.main.commands.activeSlash
import com.labteto.dshmobile.ui.components.AnimatedPresence
import com.labteto.dshmobile.ui.theme.DsTheme
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.io.asSource
import kotlinx.io.buffered
import com.labteto.dshmobile.core.session.readImageBounded
import com.labteto.dshmobile.core.wire.dto.ImageRejection

/**
 * The chat surface on Android: the shared [ChatContent] (transcript, docks, composer and the send path, the same code
 * iOS draws) with what only this app has around it. Here are the top bar's entries (models, presets, history,
 * subagents, the team, details, the tabs), the trajectory and files tabs, the built-in runtime's and the model setup's
 * notices, approvals and questions, the `/` menu and the command decorations, the `@` picker, the image and file
 * pickers, files dropped or pasted, dictation, the plugin add-on layer's trays, and every sheet and dialog the chat
 * opens.
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
    val store = LocalSessionStore.current
    val hostsStore = rememberHostsStore()
    val appSettings by hostsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val toast = rememberDsToast()
    // The refusal copy in this screen's language, for a refusal written after a pick is read or a send returns.
    val imageErrorCopy = rememberImageErrorCopy()
    // Where the plugin add-on layer over the chat keeps its trays: below the top bar, above the composer.
    val addonsChrome = com.labteto.dshmobile.ui.addons.LocalUiAddonsChrome.current

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
    // A background command's card shows its job's state and opens it in the Jobs sheet.
    val jobs by store.jobs.collectAsStateWithLifecycle()
    val jobControls by store.jobControls.collectAsStateWithLifecycle()
    var openJobId by remember { mutableStateOf<String?>(null) }
    // Null for an ordinary session, non-null while a subagent child is open. A child is the
    // harness's to drive, not the reader's: which controls still mean anything depends on it.
    val currentSubagent by store.currentSubagent.collectAsStateWithLifecycle()
    // Experimental Agent Teams: null unless the host publishes this session's `agentTeam` projection.
    val agentTeam = rememberAgentTeam(conversation, isChild = currentSubagent != null)
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
    // A decorated command's picker (the phone's `popupSelect`), keyed by the command it serves.
    var popup by remember(composer.key) { mutableStateOf<Pair<String, CommandUiSpec.PopupSelect>?>(null) }
    // A message held and sent to rewind, by sequence and preview: rewind's picker, opened on its pick.
    var rewindFrom by remember(composer.key) { mutableStateOf<Pair<Long, String>?>(null) }
    // A held prompt waiting on the reader's say-so before it replaces a draft they had started.
    var replaceDraftWith by remember(composer.key) { mutableStateOf<String?>(null) }
    // Ctrl+M: start or stop dictation. Each value is one press.
    var dictationTick by remember { mutableIntStateOf(0) }

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
    // The work-step level, and the reader's taps on rows under it. A new level starts a new memory,
    // so changing the setting puts every row back to what the level says; a tap alone never
    // touches the setting (it lives only here).
    val workStepDisplay = com.labteto.dshmobile.connection.WorkStepDisplay.of(appSettings.workStepDisplay)
    val workStepPresentation = remember(hostKey, currentSessionId, workStepDisplay) {
        WorkStepPresentation(workStepPolicyFor(workStepDisplay), RowDisclosureMemory())
    }
    val trajectoryListState = rememberLazyListState()
    val filesListState = rememberLazyListState()
    // The session's changed files, shared by the Files tab label and the tab itself.
    val sessionFiles = remember(conversation?.nodes) { sessionChangedFiles(conversation?.nodes.orEmpty()) }

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
                                    failures.add(imageRejectionText(imageErrorCopy, ImageRejection.TOO_MANY, limits))
                                    continue
                                }
                                val resolver = context.contentResolver
                                val mediaType = resolver.getType(uri)
                                val bytes = (resolver.openInputStream(uri) ?: throw java.io.IOException("Unreadable image" )).use {
                                    readImageBounded(it, limits.maxImageBytes.coerceIn(0, Int.MAX_VALUE.toLong() - 1))
                                }
                                if (bytes == null) {
                                    failures.add(imageRejectionText(imageErrorCopy, ImageRejection.TOO_LARGE, limits))
                                    continue
                                }
                                val pick = decodePick(bytes)
                                val rejection = admission.accept(
                                    mediaType.orEmpty(), pick.detectedMediaType, bytes.size, pick.width, pick.height,
                                )
                                if (rejection != null) {
                                    failures.add(imageRejectionText(imageErrorCopy, rejection, limits))
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
                open = { runCatching { context.contentResolver.openInputStream(file.uri)?.asSource()?.buffered() }.getOrNull() },
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

    // The send path, Stop, the permission chip and the focus and send counters: shared with iOS. The command
    // decorations above are this platform's hook into it.
    val actions = rememberChatActions(composer, toast.second) { text -> dispatchDecorated(text) }

    /**
     * Put a sent prompt back in the composer to be corrected. An empty draft just takes it; one the
     * reader has started is asked about first, because replacing it would lose what they typed.
     */
    fun editPrompt(text: String) {
        if (draft.isBlank() || draft == text) {
            draft = text
            composer.selection = text.length
            actions.focusComposer()
        } else {
            replaceDraftWith = text
        }
    }

    /** An `@` mention of a workspace path at the end of the draft, as the reference picker writes one. */
    fun mention(path: String, folder: Boolean) {
        val mention = fileMention(path, folder) ?: return
        val end = composer.text.length
        composer.insertReference(end, end, mention, path, if (folder) "folder" else "file", path)
        actions.focusComposer()
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.labteto.dshmobile.ui.media.LocalAttachmentScope provides (composer.key.host to composer.key.sessionId),
        LocalAutoTranslateReasoning provides appSettings.autoTranslateReasoning,
        com.labteto.dshmobile.ui.components.LocalFileOpener provides { path: String ->
            store.panels.get(composer.key).open(path); panelKey = composer.key
        },
        com.labteto.dshmobile.ui.components.LocalFolderOpener provides { path: String ->
            store.panels.get(composer.key).browse(path); panelKey = composer.key
        },
        com.labteto.dshmobile.ui.components.LocalWebLinkOpener provides { url: String ->
            store.panels.get(composer.key).openBrowser(url); panelKey = composer.key
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
        actions.focusComposer()
        return true
    }
    // Ctrl+K and Ctrl+/ move to the message box.
    val shortcutRouter = LocalShortcutRouter.current
    LaunchedEffect(shortcutRouter) {
        shortcutRouter.events.collect {
            if (it == AppShortcut.FocusComposer) actions.focusComposer()
            if (it == AppShortcut.ToggleDictation) dictationTick++
        }
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
        presentation = workStepPresentation,
        permissionNames = remember(permissions) {
            permissions?.options?.associate { it.value to it.name }.orEmpty()
        },
        // By id and state only, so a job's progress line ticking does not recompose the
        // transcript.
        jobStates = remember(jobs.map { it.id to it.status }) { jobs.associate { it.id to it.status } },
        onOpenJob = { id -> openJobId = id },
        subagentRunning = remember(subagents) {
            subagents.mapNotNull { entry -> subagentId(entry)?.let { it to subagentRunning(entry) } }.toMap()
        },
    )

    ChatContent(
        composer = composer,
        actions = actions,
        toast = toast,
        nodeContext = nodeContext,
        surfaceModifier = Modifier.attachmentDropTarget(
            enabled = currentSessionId != null,
            onActiveChange = { dropActive = it },
            onDrop = { files, text -> takeDrop(files, text) },
        ),
        tab = tab,
        readingWidth = readingWidth,
        tabletop = tabletop,
        followStreamingReply = appSettings.followStreamingReply,
        chatListState = chatListState,
        transcriptMemory = transcriptMemory,
        jumpToSeq = pendingJumpSeq,
        onJumpConsumed = { pendingJumpSeq = null },
        contentModifier = Modifier.uiAddonsContentTop(addonsChrome),
        composerBlockModifier = Modifier.uiAddonsComposer(addonsChrome),
        footerQuotaText = usageFooterText(quota, quotaProvider, quotaLimit, quotaNow),
        topBar = {
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
                teamAvailable = agentTeam != null,
                onOpenTeam = { sheet = ChatSheet.Team },
            )
        },
        notices = {
            // No model to answer: the last turn's failure code said so, or the harness has no
            // provider it can use. Only while connected, since the way out is a harness call. Its
            // calm notice stands in for the raw error the harness reports for that same turn.
            val connected = connection.phase == com.labteto.dshmobile.connection.ConnectionPhase.CONNECTED
            val setupProblem = remember(conversation?.nodes) {
                conversation?.nodes?.let { com.labteto.dshmobile.core.usage.modelSetupProblem(it) }
            }
            val shownError = connectionError.takeIf { setupProblem == null || !connected }

            // When the harness is the one on this phone and it is down, its own notice says why
            // and offers Restart. The stream error and the plain "Reconnecting" strip would only
            // say half of that, in transport words, so they stand aside while it shows.
            com.labteto.dshmobile.ui.screens.runtime.BuiltInChatNotice {
                val folderGone = stringResource(R.string.projects_session_folder_gone)
                ChatConnectionBanners(
                    error = shownError,
                    reconnecting = conversation?.gap == true && connectionError == null,
                    onRetry = { store.retryConnection() },
                    // The session's folder was deleted or renamed (a project removed on the phone):
                    // said plainly rather than as the harness's working-directory error.
                    describe = { if (com.labteto.dshmobile.runtime.workspace.ProjectList.isMissingFolderError(it)) folderGone else it },
                )
            }

            // A bar pinned above the transcript: in view wherever the reader has scrolled to, and the
            // transcript simply gets less height while it is there.
            if (connected) com.labteto.dshmobile.ui.screens.models.ChatModelSetupNotice(setupProblem)

            // A command refused for want of a sandbox (SANDBOX_UNAVAILABLE): this session is on a
            // mode a phone cannot give it. On the built-in harness, one tap switches it.
            if (connected && currentSubagent == null) {
                com.labteto.dshmobile.ui.screens.runtime.SandboxRefusalNotice(
                    nodes = conversation?.nodes.orEmpty(),
                    permissions = permissions,
                    pending = pendingPermission,
                    onSwitch = { value -> actions.setPermission(value) },
                )
            }
        },
        otherTab = { current, gutter ->
            when (current) {
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
                // The transcript is the shared content's own.
                ChatTab.Chat -> Unit
            }
        },
        // What the harness waits on the reader for: shared with iOS since M7.
        requests = { gutter -> ChatRequests(gutter, toast.second) },
        slashMenu = {
            AnimatedPresence(activeSlash(draft, composer.selection)) { slash ->
                SlashMenu(
                    query = slash,
                    commands = if (commandsAvailable) commands else emptyList(),
                    skills = skills,
                    onPick = { row ->
                        when (row) {
                            is SlashRow.Command -> if (row.descriptor.input == null) {
                                draft = ""
                                actions.send(row.descriptor.line)
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
        },
        composerExtras = ChatComposerExtras(
            onOpenSheet = { sheet = ChatSheet.Commands },
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
            onCursorChange = { if (draft.take(it).endsWith("@")) referencePicker = activeReference(draft, it) },
            onRetryAttachment = { index ->
                (attachments.getOrNull(index) as? PendingAttachment.File)?.let { startUpload(it) }
            },
            // Ctrl+V with an image or a file on the clipboard attaches it; text is pasted as usual.
            onPasteFiles = { attachArrivals(clipboardFiles(clipboard, context)) },
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
            // The microphone: off by the setting, and absent where the phone has no
            // speech service. A take belongs to the session it was started in.
            dictation = if (appSettings.dictationEnabled) {
                DictationConfig(
                    languageChoice = appSettings.dictationLanguage,
                    appLanguage = appSettings.localeOverride,
                    noticeSeen = appSettings.dictationNoticeSeen,
                    sessionKey = composer.key,
                    toggleTick = dictationTick,
                    onNoticeSeen = { scope.launch { hostsStore.setSetting { it.copy(dictationNoticeSeen = true) } } },
                    onLanguageChoice = { choice -> scope.launch { hostsStore.setSetting { it.copy(dictationLanguage = choice) } } },
                )
            } else {
                null
            },
        ),
        overlay = { DropHighlight(visible = dropActive) },
    )

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
                actions.focusComposer()
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
    // The job a background command's card opened; the live row, so its state follows the job.
    openJobId?.let { id -> jobs.firstOrNull { it.id == id } }?.let { job ->
        JobOutputSheet(job = job, store = store, canStop = jobControls, onDismiss = { openJobId = null })
    }
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
                    if (!dispatchDecorated(line)) scope.launch { actions.report(store.runCommand(line)) }
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
                actions.focusComposer()
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
            workStepDisplay = workStepDisplay,
            onDismiss = { sheet = null },
        )
        ChatSheet.Team -> if (agentTeam != null) {
            AgentTeamSheet(
                team = agentTeam,
                rows = rememberTeamRows(agentTeam, currentSessionId, conversation?.running == true, subagents),
                // A teammate is a continuable child of this session: its conversation opens in the
                // Subagents sheet, the same way the Details panel's subagent rows open one.
                onOpenTeammate = { childId ->
                    sheet = ChatSheet.Subagents
                    scope.launch { store.openSubagentTranscript(childId) }
                },
                onDismiss = { sheet = null },
            )
        } else {
            LaunchedEffect(Unit) { sheet = null }
        }
        null -> Unit
    }
}

/** Which sheet, if any, is open over the chat surface. */
private enum class ChatSheet { Commands, Models, History, Presets, Subagents, Team }

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
