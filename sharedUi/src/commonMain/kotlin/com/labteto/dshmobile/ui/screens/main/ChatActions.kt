package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.labteto.dshmobile.core.wire.dto.CommandSubmitAttachment
import com.labteto.dshmobile.data.CommandOutcome
import com.labteto.dshmobile.data.PermissionSwitchResult
import com.labteto.dshmobile.data.PromptOutcome
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.chat_attachment_still_uploading
import com.labteto.dshmobile.shared.resources.chat_attachment_upload_failed
import com.labteto.dshmobile.shared.resources.err_command_failed
import com.labteto.dshmobile.shared.resources.err_command_images_host
import com.labteto.dshmobile.shared.resources.err_command_no_images
import com.labteto.dshmobile.shared.resources.err_command_unknown
import com.labteto.dshmobile.shared.resources.panel_failed
import com.labteto.dshmobile.shared.resources.permission_unconfirmed
import com.labteto.dshmobile.ui.LocalSessionStore
import com.labteto.dshmobile.ui.components.fillTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The two counters the chat screen and its host both move: the composer's focus request and the sends the transcript
 * lands on. Held for the life of the screen ([rememberChatActions]), as the screen's own state was before it split.
 */
@Stable
class ChatTicks {
    /** Bumped to move focus into the composer's field, after something outside it filled the draft. */
    var focus by mutableIntStateOf(0)

    /** Bumped each time the reader sends: [ChatTranscript] lands on their message. */
    var sent by mutableIntStateOf(0)
}

/**
 * What the chat says when something it sent did not go, in the reader's language, read while composing: the toasts are
 * written when a call comes back, where no string resource can be looked up ([fillTemplate]).
 */
class ChatCopy internal constructor(
    internal val commandFailed: String,
    internal val commandUnknown: String,
    internal val permissionUnconfirmed: String,
    internal val uploadFailed: String,
    internal val stillUploading: String,
    internal val commandNoImages: String,
    internal val commandImagesHost: String,
    internal val failed: String,
    internal val images: ImageErrorCopy,
)

@Composable
private fun rememberChatCopy(): ChatCopy {
    val commandFailed = stringResource(Res.string.err_command_failed)
    val commandUnknown = stringResource(Res.string.err_command_unknown)
    val permissionUnconfirmed = stringResource(Res.string.permission_unconfirmed)
    val uploadFailed = stringResource(Res.string.chat_attachment_upload_failed)
    val stillUploading = stringResource(Res.string.chat_attachment_still_uploading)
    val commandNoImages = stringResource(Res.string.err_command_no_images)
    val commandImagesHost = stringResource(Res.string.err_command_images_host)
    val failed = stringResource(Res.string.panel_failed)
    val images = rememberImageErrorCopy()
    return remember(
        commandFailed, commandUnknown, permissionUnconfirmed, uploadFailed, stillUploading, commandNoImages,
        commandImagesHost, failed, images,
    ) {
        ChatCopy(
            commandFailed, commandUnknown, permissionUnconfirmed, uploadFailed, stillUploading, commandNoImages,
            commandImagesHost, failed, images,
        )
    }
}

/**
 * The chat's send path and the few verbs around it, the same on every platform: what the composer's send button, its
 * hold menu, Enter and the `/` menu do with a draft ([send]), Stop ([stop]), the permission chip ([setPermission]) and
 * how a command's outcome is told ([report]).
 *
 * Built again on every composition by [rememberChatActions], as the chat screen's local functions were: each one reads
 * the composer, the store and the host's hooks as they are now. The counters it moves live in [ChatTicks], which
 * outlives the compositions.
 *
 * [dispatchDecorated] is the host's chance to open its own UI for a bare `/name` before anything is sent (Android's
 * command decorations: a picker for `/permission`, `/rewind` and the like); true means it took the line.
 */
class ChatActions internal constructor(
    private val store: SessionStore,
    private val composer: ComposerDraft,
    private val toast: (String) -> Unit,
    /** The copy the toasts are written in. Public so the host's own refusals (a picked image) read the same. */
    val copy: ChatCopy,
    private val ticks: ChatTicks,
    private val scope: CoroutineScope,
    private val dispatchDecorated: (String) -> Boolean,
) {
    /** Passed to the composer: a new value moves focus into its field. */
    val focusTick: Int get() = ticks.focus

    /** Passed to the transcript: a new value is a send to land on. */
    val sentTick: Int get() = ticks.sent

    /** Moves focus into the composer's field and brings the keyboard up, after something filled the draft. */
    fun focusComposer() {
        ticks.focus++
    }

    /** What the composer's Stop does: ends the running turn (a continuable child's through its parent). */
    fun stop() {
        scope.launch { store.cancelTurn() }
    }

    /** The composer's permission chip: switch the preset, and say so when the switch failed or went unconfirmed. */
    fun setPermission(value: String) {
        scope.launch { reportPermission(store.setPermissionPreset(value)) }
    }

    fun report(outcome: CommandOutcome) {
        when (outcome) {
            is CommandOutcome.Ok -> outcome.text?.takeIf { it.isNotBlank() }?.let { toast(it) }
            is CommandOutcome.Unknown -> toast(fillTemplate(copy.commandUnknown, outcome.line))
            is CommandOutcome.Failed -> toast(fillTemplate(copy.commandFailed, outcome.message))
        }
    }

    /** Say so when a permission switch failed or went unconfirmed; the chip already shows the rest. */
    fun reportPermission(result: PermissionSwitchResult) {
        when (result) {
            is PermissionSwitchResult.Failed -> report(result.outcome)
            is PermissionSwitchResult.Unconfirmed -> toast(copy.permissionUnconfirmed)
            else -> Unit
        }
    }

    /** [deliveryOverride] is a mode picked for this message alone, from holding send. */
    fun send(text: String, deliveryOverride: String? = null) {
        val attachments = composer.attachments
        if (composer.preparing || composer.submitting) { composer.text = text; return }
        ticks.sent++
        if (attachments.isEmpty() && dispatchDecorated(text)) return
        // Steering an idle session is refused by the host, so a Steer chosen while nothing runs
        // (or chosen mid-turn that has since ended) goes out as a normal queued prompt.
        val delivery = promptDelivery(deliveryOverride ?: composer.mode, store.currentConversation.value?.running == true)
        val targetId = composer.key.sessionId
        val targetHost = composer.key.host
        val pending = attachments.toList()
        if (text.isBlank() && pending.isEmpty()) return
        val images = pending.filterIsInstance<PendingAttachment.Image>()
        val files = pending.filterIsInstance<PendingAttachment.File>()
        // A file without a receipt cannot be cited. The send affordance already waits for the
        // chips, but a keyboard send lands here too.
        if (files.any { it.state !is FileUploadState.Ready }) {
            composer.text = text
            toast(
                if (files.any { it.state is FileUploadState.Failed }) copy.uploadFailed
                else copy.stillUploading,
            )
            return
        }
        val receipts = files.mapNotNull { it.receiptId }
        // A slash line that names a registered command is not a message: `session/prompt` would
        // hand it to the model verbatim, so it has to be recognised here and written through the
        // command gateway. A miss falls through to the prompt path — that is how skills work.
        when (val submission = adjudicate(text, store.commands.value, pending.size, store.commandAttachmentsSupported)) {
            is Submission.Refused -> {
                // Nothing is sent and nothing is dropped. The composer clears the draft on its way
                // here, so put it back, and leave the attachments alone — a refusal the user cannot
                // act on without re-picking every one is not much of a refusal.
                composer.text = text
                val message = when (submission.reason) {
                    RefusalReason.COMMAND_TAKES_NO_ATTACHMENTS -> copy.commandNoImages
                    RefusalReason.HOST_TOO_OLD -> copy.commandImagesHost
                }
                toast(fillTemplate(message, submission.command))
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
                    } catch (e: CancellationException) { composer.restoreRejected(text, pending); throw e }
                    catch (e: Exception) { composer.restoreRejected(text, pending); toast(e.message ?: copy.failed) }
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
                        toast(
                            if (outcome is PromptOutcome.Rejected) imageRejectionText(copy.images, outcome.rejection, store.imageLimits.value, outcome.reason)
                            else (outcome as PromptOutcome.Failed).message,
                        )
                    }
                    } catch (e: CancellationException) { composer.restoreRejected(text, pending); throw e }
                    catch (e: Exception) { composer.restoreRejected(text, pending); toast(e.message ?: copy.failed) }
                    finally { composer.submitting = false }
                }
            }
        }
    }
}

/**
 * The [ChatActions] of [composer] for this composition. [toast] says a short message (the screen's toast host);
 * [dispatchDecorated] is the host's command decorations, none by default.
 */
@Composable
fun rememberChatActions(
    composer: ComposerDraft,
    toast: (String) -> Unit,
    dispatchDecorated: (String) -> Boolean = { false },
): ChatActions {
    val store = LocalSessionStore.current
    val scope = rememberCoroutineScope()
    val ticks = remember { ChatTicks() }
    val copy = rememberChatCopy()
    return ChatActions(store, composer, toast, copy, ticks, scope, dispatchDecorated)
}
