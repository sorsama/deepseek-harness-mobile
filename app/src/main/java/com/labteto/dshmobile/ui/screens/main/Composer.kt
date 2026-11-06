package com.labteto.dshmobile.ui.screens.main

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.input.key.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.components.pressScale
import com.labteto.dshmobile.ui.components.HoldAction
import com.labteto.dshmobile.ui.components.HoldMenu
import com.labteto.dshmobile.ui.components.clickableWithHold
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.ContextBreakdownView
import com.labteto.dshmobile.core.wire.dto.ContextPressureView
import com.labteto.dshmobile.core.wire.dto.FULL_ACCESS_PRESET
import com.labteto.dshmobile.core.wire.dto.EncodedImageAttachment
import com.labteto.dshmobile.core.wire.dto.FileAttachmentRef
import com.labteto.dshmobile.core.wire.dto.PermissionSelect
import com.labteto.dshmobile.core.wire.dto.displayPermissionPreset
import com.labteto.dshmobile.ui.components.ContextMeter
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.skeleton
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * Something picked and waiting to be sent with the next message.
 *
 * Two kinds, because the harness carries them two ways. An [Image] rides the prompt as bytes,
 * so it is held here fully encoded. A [File] is uploaded the moment it is picked — harness
 * 0.1.3 streams it to the host verbatim — and what the prompt carries is the receipt that upload
 * answered with, so the chip tracks the upload rather than the bytes.
 */
internal sealed interface PendingAttachment {
    /** Display name, when the picker had one. */
    val name: String?

    /**
     * A picked image, held with its decoded preview.
     *
     * [bytes], [width] and [height] are the encoded size and intrinsic dimensions the picker already
     * had to learn to admit the image; keeping them means the *next* pick can be measured against
     * the message's running totals without decoding everything already attached a second time.
     */
    data class Image(
        val mediaType: String,
        val base64: String,
        val preview: ImageBitmap?,
        val bytes: Int,
        val width: Int,
        val height: Int,
        override val name: String? = null,
    ) : PendingAttachment {
        /** The wire form `session/prompt` carries. */
        fun encoded(): EncodedImageAttachment = EncodedImageAttachment(mediaType, base64, name)
    }

    /** A picked file and the state of its upload. [id] is what a progress callback keys on. */
    data class File(
        val id: String,
        val uri: Uri,
        override val name: String,
        /** Size as the provider reported it; `-1` when it would not say. */
        val size: Long,
        val state: FileUploadState,
    ) : PendingAttachment {
        /** The receipt to cite, once the upload has one. */
        val receiptId: String? get() = (state as? FileUploadState.Ready)?.receiptId
    }
}

/** Where one file's upload stands. */
internal sealed interface FileUploadState {
    /** Bytes are still going up; [sent] is the running count. */
    data class Uploading(val sent: Long) : FileUploadState

    /** The host holds the file and minted a receipt for it. */
    data class Ready(val receiptId: String, val file: FileAttachmentRef) : FileUploadState

    /** The upload did not complete; the chip offers a retry. */
    data class Failed(val message: String) : FileUploadState
}

/**
 * The message composer, laid out like the harness's own: the `+` and the permission chip on the
 * left, the send affordance on the right.
 *
 * The model selector is deliberately *not* here — it moved to the top bar, which leaves this row
 * for the two controls you change mid-conversation and keeps the composer from wrapping on a
 * narrow phone.
 */
@Composable
internal fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<PendingAttachment>,
    onRemoveAttachment: (Int) -> Unit,
    onRetryAttachment: (Int) -> Unit,
    permissions: PermissionSelect?,
    pendingPermission: String?,
    onPermissionPick: (String) -> Unit,
    contextBreakdown: ContextBreakdownView?,
    contextPressure: ContextPressureView?,
    running: Boolean,
    enabled: Boolean,
    onOpenSheet: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    preparing: Boolean = false,
    /**
     * A send is on its way to the host. Send and the attach buttons wait for it, but the text field
     * does not: it used to be disabled with them, and a focused field that goes disabled loses its
     * focus and drops the keyboard, so every message needed a tap on the field before the next one.
     */
    submitting: Boolean = false,
    cursor: Int = draft.length,
    onCursorChange: (Int) -> Unit = {},
    references: List<DraftReference> = emptyList(),
    onOpenReference: (DraftReference) -> Unit = {},
    onAddReference: () -> Unit = {},
    /**
     * Sends with a delivery mode chosen for this one message, from holding send mid-turn. Null
     * leaves send with its tap alone.
     */
    onSendWith: ((text: String, mode: String) -> Unit)? = null,
    /** Bumped to move focus into the field, after something outside it fills the draft. */
    focusTick: Int = 0,
) {
    var editor by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(draft, androidx.compose.ui.text.TextRange(cursor))) }
    androidx.compose.runtime.LaunchedEffect(draft, cursor) {
        if (editor.text != draft || editor.selection.end != cursor) editor = editor.copy(text = draft, selection = androidx.compose.ui.text.TextRange(cursor.coerceIn(0, draft.length)))
    }
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    // A file that is still uploading has no receipt to cite yet, and one that failed never will;
    // the send affordance waits for the chips rather than sending a message that names neither.
    val attachmentsSettled = attachments.none { it is PendingAttachment.File && it.state !is FileUploadState.Ready }
    val canSend = enabled && !submitting && !preparing && (draft.isNotBlank() || attachments.isNotEmpty()) && attachmentsSettled
    // The buttons that start something wait for a send in flight; the field itself stays live.
    val controlsEnabled = enabled && !submitting
    val currentDraft by rememberUpdatedState(draft)
    val currentOnDraftChange by rememberUpdatedState(onDraftChange)
    val currentOnSend by rememberUpdatedState(onSend)
    var sendMenuOpen by remember { mutableStateOf(false) }
    // The mock's focused composer takes an accent hairline and a soft glow; the border is the part
    // a phone can show without a second shadow layer, so it is the part wired here.
    var fieldFocused by remember { mutableStateOf(false) }
    val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Text put back from the transcript is there to be edited, so the field takes focus and the
    // keyboard comes up with it rather than waiting for a tap on the field. Both, because a field
    // that kept focus after the keyboard was dismissed ignores a focus request and stays closed.
    androidx.compose.runtime.LaunchedEffect(focusTick) {
        if (focusTick > 0 && enabled) {
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .animateContentSize(),
        shape = DsShapes.composer,
        color = colors.composerCard,
        border = BorderStroke(1.dp, if (fieldFocused) colors.accent else colors.borderL2),
    ) {
        Box {
            Column(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                if (references.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        references.forEach { reference ->
                            val type = referenceKindLabel(reference.kind)
                            val openLabel = stringResource(R.string.ux_b_open_reference, type, reference.label)
                            Row(
                                Modifier.clip(DsShapes.cube).background(colors.hoverSolid),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    Modifier.heightIn(min = DsSpacing.touchTarget).widthIn(min = DsSpacing.touchTarget, max = 210.dp)
                                        .clickable { onOpenReference(reference) }
                                        .semantics(mergeDescendants = true) { contentDescription = openLabel }
                                        .padding(horizontal = DsSpacing.small),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                                ) {
                                    Icon(referenceKindIcon(reference.kind), null, Modifier.size(18.dp), tint = colors.labelSecondary)
                                    Text(technicalDisplay(reference.label), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = DsType.small13, color = colors.labelSecondary)
                                }
                                DsIconButton(Icons.Filled.Close, stringResource(R.string.ux_b_remove_reference, reference.label), {
                                    onDraftChange(draft.removeRange(reference.start, reference.end))
                                    onCursorChange(reference.start)
                                })
                            }
                        }
                    }
                }
                TextField(
                    value = editor,
                    onValueChange = {
                        val edited = atomicReferenceEdit(editor.text, it, references)
                        editor = edited
                        onDraftChange(edited.text)
                        onCursorChange(edited.selection.end)
                    },
                    visualTransformation = remember(references, colors.accent, colors.accentTertiary) {
                        ReferenceTransformation(references, androidx.compose.ui.text.SpanStyle(color = colors.accent, background = colors.accentTertiary))
                    },
                    // Measure the controls first, then give the editor the remaining height. With
                    // unbounded height, omit weight so the editor keeps its natural line height.
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed)) {
                            if (event.type == KeyEventType.KeyUp && canSend) {
                                val text = currentDraft
                                editor = androidx.compose.ui.text.input.TextFieldValue("")
                                currentOnDraftChange("")
                                currentOnSend(text)
                            }
                            true
                        } else false
                    },
                    // No `keyboardActions` here. The field is multi-line, so it carries the default IME
                    // action and the on-screen return key inserts a newline — which means an
                    // `onSend` action could never fire, and the one that used to sit here never did.
                    // The send key is the button; Ctrl/Cmd+Enter above is the shortcut.
                    enabled = enabled,
                    placeholder = {
                        Text(
                            // Mid-turn the composer's message steers the running turn, and the
                            // placeholder says so instead of offering the same "Message the agent"
                            // as an idle session. Both are the same field and the same button.
                            stringResource(
                                if (running) R.string.ink_chat_steer_hint else R.string.chat_composer_hint,
                            ),
                            style = DsType.std14,
                            color = colors.labelTertiary,
                        )
                    },
                    minLines = 1,
                    maxLines = 8,
                    textStyle = DsType.std14,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = colors.accent,
                        focusedTextColor = colors.labelPrimary,
                        unfocusedTextColor = colors.labelPrimary,
                    ),
                )

                if (preparing) Text(stringResource(R.string.photos_preparing), style = DsType.caption11)
                AnimatedVisibility(visible = attachments.isNotEmpty()) {
                    AttachmentStrip(attachments, onRemoveAttachment, onRetryAttachment)
                }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    CircleAction(
                        icon = Icons.Filled.Add,
                        description = stringResource(R.string.chat_composer_commands),
                        size = 44,
                        background = colors.bgLayer3,
                        tint = colors.labelPrimary,
                        enabled = controlsEnabled,
                        onClick = onOpenSheet,
                        haptic = { haptics.tap() },
                    )

                    CircleAction(
                        icon = FeatherIcons.AtSign,
                        description = stringResource(R.string.harness_add_reference),
                        size = 44,
                        background = colors.bgLayer3,
                        tint = colors.labelPrimary,
                        enabled = controlsEnabled,
                        onClick = onAddReference,
                        haptic = { haptics.tap() },
                    )

                    Box(Modifier.weight(1f)) {
                        PermissionChip(
                            select = permissions,
                            pending = pendingPermission,
                            enabled = controlsEnabled,
                            onPick = onPermissionPick,
                        )
                    }

                    ContextMeter(contextBreakdown, contextPressure)

                    // Send is always present; stop joins it while a turn runs.
                    //
                    // These used to share one slot, swapping on `running`, which meant a running
                    // session offered no way to send at all — the Queue and Steer modes in the + sheet
                    // were unreachable from the phone, and the on-screen return key inserts a newline,
                    // so there was nothing else to press. The host has always admitted
                    // `session/prompt` with `mode: queue|steer` mid-turn; only the button was missing.
                    //
                    // Stop keeps the right-hand position it had, so the gesture for stopping a turn is
                    // where it always was and send appears beside it rather than under the thumb
                    // already reaching for stop.
                    // The send button lights up as the draft becomes sendable rather than switching
                    // colour in one frame, and gives a small pop when it does, so the change that
                    // makes it pressable is noticed without looking for it.
                    val sendFill by animateColorAsState(
                        if (canSend) colors.buttonInfoFill else colors.buttonPrimaryDimmed,
                        DsAnimations.tabSwapColor,
                        label = "sendFill",
                    )
                    val sendTint by animateColorAsState(
                        if (canSend) colors.onAccent else colors.labelTertiary,
                        DsAnimations.tabSwapColor,
                        label = "sendTint",
                    )
                    val sendScale by animateFloatAsState(
                        if (canSend) 1f else 0.92f,
                        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
                        label = "sendScale",
                    )
                    fun submit(deliver: (String) -> Unit) {
                        val text = currentDraft
                        // Cleared here as well as through the draft: the field only follows the
                        // draft a frame later, and an IME update landing in that frame (the
                        // keyboard finishing its composing word) carried the sent text back in.
                        editor = androidx.compose.ui.text.input.TextFieldValue("")
                        currentOnDraftChange("")
                        haptics.confirm()
                        deliver(text)
                    }
                    // Mid-turn a message can steer the running turn or wait for it to end, and the
                    // sticky choice lives two taps away in the + sheet. Holding send makes the choice
                    // for this message alone, the way a hold anywhere else opens what a thing can do.
                    val sendWith = onSendWith?.takeIf { running && canSend }
                    CircleAction(
                        icon = Icons.Filled.ArrowUpward,
                        description = stringResource(R.string.chat_composer_send),
                        size = 44,
                        background = sendFill,
                        tint = sendTint,
                        enabled = canSend,
                        modifier = Modifier.graphicsLayer { scaleX = sendScale; scaleY = sendScale },
                        onClick = { submit { currentOnSend(it) } },
                        onLongClick = sendWith?.let { { sendMenuOpen = true } },
                        longClickLabel = stringResource(R.string.hold_send_label),
                    )
                    // A turn that ends under the open menu takes it away: steering an idle session
                    // is refused, and queueing is then just a send.
                    androidx.compose.runtime.LaunchedEffect(sendWith == null) { if (sendWith == null) sendMenuOpen = false }
                    if (sendMenuOpen && sendWith != null) {
                        HoldMenu(
                            onDismiss = { sendMenuOpen = false },
                            actions = listOf(
                                HoldAction(
                                    stringResource(R.string.hold_send_steer),
                                    Icons.Outlined.Bolt,
                                    detail = stringResource(R.string.chat_composer_mode_steer_hint),
                                ) { submit { text -> sendWith(text, "steer") } },
                                HoldAction(
                                    stringResource(R.string.hold_send_queue),
                                    Icons.Outlined.Schedule,
                                    detail = stringResource(R.string.chat_composer_mode_queue_hint),
                                ) { submit { text -> sendWith(text, "queue") } },
                            ),
                        )
                    }

                    // Grows its own width in and out, so the controls beside it slide over rather than
                    // jumping by a button's width the moment a turn starts or ends.
                    AnimatedVisibility(
                        visible = running,
                        enter = fadeIn(DsAnimations.fade) + expandHorizontally(DsAnimations.expand) + scaleIn(initialScale = 0.85f),
                        exit = fadeOut(DsAnimations.fade) + shrinkHorizontally(DsAnimations.expand) + scaleOut(targetScale = 0.85f),
                    ) {
                        CircleAction(
                            icon = null,
                            description = stringResource(R.string.chat_composer_stop),
                            size = 44,
                            background = colors.error,
                            tint = Color.White,
                            enabled = true,
                            onClick = {
                                haptics.reject()
                                onStop()
                            },
                        ) {
                            Box(
                                Modifier
                                    .size(11.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colors.bgBase),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The permission preset chip.
 *
 * Renders nothing when the projection key is absent: that means the harness composes no permission
 * service at all, and a dead control would be worse than none. Labels come from the wire, because
 * the preset table is deployment-configurable — mapping ids to local strings would mislabel any
 * deployment that renamed one.
 */
@Composable
private fun PermissionChip(
    select: PermissionSelect?,
    pending: String?,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    if (select == null) return
    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<String?>(null) }
    val effective = pending ?: select.currentValue
    val option = select.options.firstOrNull { it.value == effective }
    val label = if (option != null) {
        displayPermissionPreset(option.value, option.name)
    } else {
        stringResource(R.string.permission_custom)
    }

    Box {
        Row(
            modifier = Modifier
                // Drawn 36dp like every other chip, inside a 48dp target: 24dp of chip was the smallest
                // target in the composer for a setting that decides whether the agent can run commands
                // unasked.
                .minimumInteractiveComponentSize()
                .heightIn(min = DsSizes.chip)
                .clip(DsShapes.pillFull)
                .background(colors.bgLayer3)
                .border(1.dp, colors.borderL2, DsShapes.pillFull)
                // Never gated on `pending`: the switch in flight always ends (see PermissionSwitch),
                // but a chip that went dead while it waited was exactly how one that never ended
                // took the whole control with it. A second pick simply replaces the first.
                .clickable(enabled = enabled) {
                    haptics.tap()
                    menuOpen = true
                }
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = 12.dp)
                .then(
                    if (pending != null) {
                        Modifier.skeleton(colors.bgLayer2, colors.hover, DsShapes.pillFull)
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                Icons.Outlined.Shield,
                contentDescription = stringResource(R.string.permission_preset),
                tint = if (effective == FULL_ACCESS_PRESET) colors.warnLabel else colors.labelSecondary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                label,
                style = DsType.small13,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (menuOpen) {
            PermissionMenu(
                select = select,
                current = effective,
                onDismiss = { menuOpen = false },
                onPick = { value ->
                    menuOpen = false
                    haptics.selection()
                    // Full access removes the approval prompt entirely, so it gets an explicit
                    // acknowledgement the way the desktop client does.
                    if (value == FULL_ACCESS_PRESET) confirming = value else onPick(value)
                },
            )
        }
    }

    confirming?.let { target ->
        FullAccessConfirmDialog(
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                onPick(target)
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Attachments
// ---------------------------------------------------------------------------

@Composable
private fun AttachmentStrip(
    attachments: List<PendingAttachment>,
    onRemove: (Int) -> Unit,
    onRetry: (Int) -> Unit,
) {
    val colors = DsTheme.colors
    // Room for the remove target's overhang: it is 48dp around an 18dp badge that sits on the
    // tile's corner, so it reaches past the tile on the top and the end. The scroll region clips
    // what lies outside it, so the room is part of the scrolled content.
    Row(
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(top = RemoveOverhang, end = RemoveOverhang),
    ) {
        attachments.forEachIndexed { index, attachment ->
            Box {
                when (attachment) {
                    is PendingAttachment.Image -> Box(
                        Modifier
                            .size(56.dp)
                            .clip(DsShapes.block)
                            .background(colors.bgModulePlatform),
                    ) {
                        attachment.preview?.let {
                            Image(
                                bitmap = it,
                                contentDescription = attachment.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    is PendingAttachment.File -> FileAttachmentChip(attachment, onRetry = { onRetry(index) })
                }
                RemoveBadge(
                    description = stringResource(
                        if (attachment is PendingAttachment.File) R.string.chat_composer_remove_file
                        else R.string.chat_composer_remove_image,
                    ),
                    onClick = { onRemove(index) },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

/** The glyph is 18dp; the touch target around it is 48dp. */
private val RemoveBadgeSize = 18.dp

/** How far the 48dp target reaches past the tile's top and end edges, so the badge stays on the corner. */
private val RemoveOverhang = (DsSpacing.touchTarget - RemoveBadgeSize) / 2

/**
 * The small "x" on a pending attachment.
 *
 * The badge was the whole target at 18dp. It is now a 48dp target centred on the same corner spot,
 * pushed out past the tile's corner by [RemoveOverhang] so the visible badge has not moved.
 */
@Composable
private fun RemoveBadge(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Box(
        modifier = modifier
            .offset {
                val over = RemoveOverhang.roundToPx()
                // `offset` is not mirrored: the end edge is the left one in a right-to-left layout.
                androidx.compose.ui.unit.IntOffset(if (rtl) -over else over, -over)
            }
            .size(DsSpacing.touchTarget)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(RemoveBadgeSize)
                .clip(CircleShape)
                .background(colors.toastBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/**
 * One file in the strip: name, then either its size, its upload progress, or the failure.
 *
 * The chip is the same height as an image tile so the strip does not step. A failed upload is
 * retried by tapping the chip itself — the remove affordance stays where it is on every kind.
 */
@Composable
private fun FileAttachmentChip(file: PendingAttachment.File, onRetry: () -> Unit) {
    val colors = DsTheme.colors
    val failed = file.state is FileUploadState.Failed
    Row(
        modifier = Modifier
            .height(56.dp)
            .widthIn(min = 120.dp, max = 200.dp)
            .clip(DsShapes.block)
            .background(colors.bgModulePlatform)
            .border(1.dp, if (failed) colors.error else colors.borderL3, DsShapes.block)
            .clickable(enabled = failed, onClick = onRetry)
            .padding(start = 10.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val state = file.state) {
            is FileUploadState.Uploading -> CircularProgressIndicator(
                progress = {
                    if (file.size > 0) (state.sent.toFloat() / file.size).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier.size(18.dp),
                color = colors.accent,
                trackColor = colors.borderL3,
                strokeWidth = 2.dp,
            )
            else -> Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                tint = if (failed) colors.error else colors.labelSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Column {
            Text(
                file.name,
                style = DsType.small13,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (val state = file.state) {
                    is FileUploadState.Uploading -> stringResource(R.string.chat_attachment_uploading)
                    is FileUploadState.Ready -> fileSizeText(state.file.bytes)
                    is FileUploadState.Failed -> stringResource(R.string.chat_attachment_upload_failed)
                },
                style = DsType.caption11,
                color = if (failed) colors.error else colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared circular affordance
// ---------------------------------------------------------------------------

@Composable
private fun CircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    description: String,
    size: Int,
    background: Color,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** A hold, for the one button with a second thing to offer; null keeps the plain tap. */
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    /** Touch feedback for the plain tap; null where the caller already gives its own. */
    haptic: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val tap = {
        haptic?.invoke()
        onClick()
    }
    // The circle draws at [size], but what a thumb has to hit is never smaller than 48dp: the
    // plain `+` and `@` buttons are 40dp, and a miss on them opens nothing at all. The outer box
    // is the target and carries the click, the hold and the description; the circle inside keeps
    // its look. Described here rather than on the icon, because not every one of these has an
    // icon: the stop button draws a plain square through `content`, and while the description
    // hung off the icon that button announced nothing at all to a screen reader.
    Box(
        modifier = modifier
            .size(maxOf(size.dp, DsSpacing.touchTarget))
            .clip(CircleShape)
            .semantics { this.contentDescription = description }
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        enabled = enabled,
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClick = tap,
                    )
                } else {
                    // `clickable` has no hold, so this one takes both.
                    Modifier.clickableWithHold(
                        holdLabel = longClickLabel.orEmpty(),
                        onHold = onLongClick,
                        enabled = enabled,
                        role = androidx.compose.ui.semantics.Role.Button,
                        interactionSource = interaction,
                        onClick = tap,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .pressScale(interaction, 0.9f)
                .size(size.dp)
                .clip(CircleShape)
                .background(background),
        ) { CircleGlyph(icon, size, tint, content) }
    }
}

@Composable
private fun CircleGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    size: Int,
    tint: Color,
    content: (@Composable () -> Unit)?,
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        when {
            content != null -> content()
            icon != null -> Icon(
                icon,
                // The Surface above already carries it; repeating it here would have a screen
                // reader say the label twice.
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size((size * 0.46f).dp),
            )
        }
    }
}
