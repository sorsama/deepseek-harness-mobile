package com.labteto.dshmobile.ui.screens.main

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.labteto.dshmobile.ui.components.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.labteto.dshmobile.ui.components.HoverTooltip
import com.labteto.dshmobile.ui.components.horizontalWheelScroll
import com.labteto.dshmobile.ui.input.ComposerKeyOutcome
import com.labteto.dshmobile.ui.input.clipboardFiles
import com.labteto.dshmobile.ui.input.decideComposerKey
import com.labteto.dshmobile.ui.input.insertNewline
import com.labteto.dshmobile.ui.input.isPasteChord
import com.labteto.dshmobile.ui.input.toComposerKeyPress
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
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
import com.labteto.dshmobile.ui.components.ContextMeter
import com.labteto.dshmobile.ui.components.ContextMeterBarHeight
import com.labteto.dshmobile.ui.components.ContextMeterBarWidth
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.DsFullScreenDialog
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
    /**
     * Drawn as the lifted drawer over the transcript rather than in the column's flow. Only the
     * elevation differs: in flow the card sits on the conversation, lifted it floats above it, and
     * a card that floats without a shadow reads as a card whose bottom edge was cut off.
     */
    lifted: Boolean = false,
    /**
     * How many lines the editor is painting right now. The chat screen lifts the composer at two,
     * so the count that decides the drawer is the one the person can see — wrapped lines included —
     * and not the number of hard breaks in the string.
     */
    onLineCountChange: (Int) -> Unit = {},
    /**
     * Picks a file to send with this message, for the full-screen page's own attach button. Null
     * (the default) hides it there; the composer card reaches the same picker through its `+` sheet.
     */
    onAttachFile: (() -> Unit)? = null,
    /**
     * Called when Ctrl+V pastes a copied image or file instead of text. Returns true when it took
     * them (they are in the tray), false to let the field paste whatever it can.
     */
    onPasteFiles: ((List<Uri>) -> Boolean)? = null,
) {
    var editor by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(draft, androidx.compose.ui.text.TextRange(cursor))) }
    androidx.compose.runtime.LaunchedEffect(draft, cursor) {
        if (editor.text != draft || editor.selection.end != cursor) editor = editor.copy(text = draft, selection = androidx.compose.ui.text.TextRange(cursor.coerceIn(0, draft.length)))
    }
    val colors = DsTheme.colors
    // A hardware keyboard changes what Enter means: it sends, and Shift+Enter adds a line. The
    // configuration reports one while a keyboard is attached (a Chromebook always has one) and
    // none on a bare phone, so the soft keyboard's behaviour is never touched.
    val hardwareKeyboard = LocalConfiguration.current.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
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
            if (!hardwareKeyboard) keyboard?.show()
        }
    }

    // What the drawer and the full-screen button both hang off: how many lines the draft paints.
    // `TextField` offers no layout callback (only `BasicTextField` does), so the count is measured
    // here with the same style and the same width the field is painting at, and the width is read
    // off the field itself rather than guessed from the screen. Wrapped lines count, so one long
    // pasted paragraph on a narrow phone reaches two exactly as a draft with a hard break does.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    var fieldWidthPx by remember { mutableStateOf(0) }
    // A Material text field pads 16dp on each side before it lays any text out.
    val textWidthPx = fieldWidthPx - with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.roundToPx() }
    val lineCount = remember(draft, textWidthPx, measurer) {
        if (textWidthPx <= 0 || draft.isEmpty()) {
            1
        } else {
            runCatching {
                measurer.measure(
                    text = androidx.compose.ui.text.AnnotatedString(draft),
                    style = DsType.std14,
                    maxLines = 8,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = textWidthPx),
                ).lineCount
            }.getOrDefault(1).coerceAtLeast(1)
        }
    }
    var fullScreen by remember { mutableStateOf(false) }
    // The page rises in and sinks away rather than swapping in on one frame. `fullScreen` is what
    // the person asked for; the page itself stays composed until its exit has finished.
    //
    // The entry is started by hand, once the page's window reports it is up. A window only reaches
    // the screen a few frames after it is composed, and an enter that starts with the composition
    // plays out unseen: the page then looks like it simply appeared. Closing needs no wait, the
    // window is already there.
    val pageState = remember { MutableTransitionState(false) }
    var pageWindowReady by remember { mutableStateOf(false) }
    val pageComposed = fullScreen || pageState.currentState || pageState.targetState
    androidx.compose.runtime.LaunchedEffect(fullScreen) {
        if (fullScreen) {
            if (!pageState.currentState) androidx.compose.runtime.snapshotFlow { pageWindowReady }.first { it }
            pageState.targetState = true
        } else {
            pageState.targetState = false
        }
    }
    val expanded = lineCount > 1
    androidx.compose.runtime.LaunchedEffect(lineCount) { onLineCountChange(lineCount) }

    // Closing the page gives the field its keyboard back. A field that kept focus while another
    // window owned the keyboard ignores a bare focus request and stays closed, and one that the
    // page took focus from needs both, so ask for both. It waits for the page's window to be gone
    // (the exit animation included): the field cannot take the keyboard from a window still on top.
    var pageWasOpen by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(pageComposed) {
        if (pageWasOpen && !pageComposed && enabled) {
            runCatching { fieldFocus.requestFocus() }
            if (!hardwareKeyboard) keyboard?.show()
        }
        pageWasOpen = pageComposed
    }

    // One clear-then-deliver path for the send button, the hold menu and the full-screen page. The
    // field only follows the draft a frame later, and an IME update landing in that frame (the
    // keyboard finishing its composing word) carried the sent text back in, so it is cleared here
    // as well as through the draft.
    fun submitDraft(deliver: (String) -> Unit) {
        val text = currentDraft
        editor = androidx.compose.ui.text.input.TextFieldValue("")
        currentOnDraftChange("")
        haptics.confirm()
        deliver(text)
    }

    // Every edit, wherever it is typed, goes through the reference-aware one: a draft holding `@`
    // chips keeps its chip ranges adjusted when a character is inserted, and the page must not be
    // the one place that wrecks them.
    val applyEdit: (androidx.compose.ui.text.input.TextFieldValue) -> Unit = { edited ->
        val normalized = atomicReferenceEdit(editor.text, edited, references)
        editor = normalized
        onDraftChange(normalized.text)
        onCursorChange(normalized.selection.end)
    }

    // What is attached lives in a tray directly above the card, not inside it: the tray is measured
    // first and counts in the block's height, so the cap below still leaves the card its action row.
    // The card is drawn over the tray's lower edge, which is what makes the two read as one unit
    // with the tray sliding out from behind the card.
    TrayAboveCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        tray = { AttachmentTray(attachments, onRemoveAttachment, onRetryAttachment) },
    ) {
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = DsShapes.composer,
        color = colors.composerCard,
        border = BorderStroke(1.dp, if (fieldFocused) colors.accent else colors.borderL2),
        shadowElevation = if (lifted) 12.dp else 0.dp,
    ) {
        BoxWithConstraints {
            // Reserve the action row before measuring any draft content, including attachments.
            // The row below is unweighted, so this column measures it first at its full height and
            // hands the editor only what is left; the editor scrolls inside that. 0.13.0 fixed
            // exactly this bug (`ca3e66e`), 0.19.0's redesign deleted the weights (`37ed212`), and
            // every composer that ChatScreen bounded to the post-keyboard space then measured the
            // action row at zero height. See #87.
            //
            // A weight needs a bound to divide. With no maximum there is no space "left over", and
            // a weight would hand the editor zero height instead of its natural lines, so an
            // unbounded caller (the layout tests, a preview) keeps the plain wrapping column.
            val bounded = constraints.hasBoundedHeight
            Column(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Column(
                    Modifier
                        .then(if (bounded) Modifier.weight(1f, fill = false) else Modifier)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                if (references.isNotEmpty()) {
                    val referenceScroll = rememberScrollState()
                    Row(
                        Modifier.fillMaxWidth().horizontalWheelScroll(referenceScroll).horizontalScroll(referenceScroll),
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
                    onValueChange = applyEdit,
                    visualTransformation = remember(references, colors.accent, colors.accentTertiary) {
                        ReferenceTransformation(references, androidx.compose.ui.text.SpanStyle(color = colors.accent, background = colors.accentTertiary))
                    },
                    // The height is decided by the column above, which gives the action row its
                    // space first; the field takes what is left and scrolls past it. The minimum
                    // only guarantees a line to tap when the drawer is at its cap.
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .fillMaxWidth()
                        .onSizeChanged { fieldWidthPx = it.width }
                        .focusRequester(fieldFocus)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            // Paste: a copied image or file goes to the attachment tray, anything with
                            // text in it is left to the field's own paste.
                            if (event.type == KeyEventType.KeyDown && isPasteChord(event)) {
                                val files = onPasteFiles
                                if (files != null && files(clipboardFiles(clipboard, context))) return@onPreviewKeyEvent true
                            }
                            when (decideComposerKey(event.toComposerKeyPress(), hardwareKeyboard)) {
                                ComposerKeyOutcome.Send -> {
                                    if (canSend) submitDraft { currentOnSend(it) }
                                    true
                                }
                                ComposerKeyOutcome.Newline -> {
                                    applyEdit(insertNewline(editor))
                                    true
                                }
                                ComposerKeyOutcome.Swallow -> true
                                ComposerKeyOutcome.Default -> false
                            }
                        },
                    // No `keyboardActions` here. The field is multi-line, so it carries the default IME
                    // action and the on-screen return key inserts a newline — which means an
                    // `onSend` action could never fire, and the one that used to sit here never did.
                    // The send key is the button. With a hardware keyboard Enter and Ctrl/Cmd+Enter
                    // send and Shift+Enter adds a line (see decideComposerKey); the soft keyboard is
                    // never answered there, so a phone is exactly as it was.
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
                    // A paragraph in a card two thumb-heights tall is where people give up editing,
                    // so a draft long enough to lift the drawer also gets a page of its own. The
                    // slot only exists while that is true: a one-line message keeps the composer
                    // people already know, and the row below is already full at that point.
                    trailingIcon = if (expanded) {
                        {
                            DsIconButton(
                                icon = Icons.Filled.OpenInFull,
                                contentDescription = stringResource(R.string.chat_composer_expand),
                                onClick = { fullScreen = true },
                            )
                        }
                    } else {
                        null
                    },
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

                    // The flexible space between the controls and send. The chip is the thing that
                    // lives here; the context meter only borrows what the chip leaves over, and only
                    // once the window is filling up (see [ChipThenMeter] and [showsContextMeter]).
                    val contextUsed = contextPressure?.usedRatio?.let { (it * 100).toInt() }
                    ChipThenMeter(
                        modifier = Modifier.weight(1f),
                        chip = {
                            PermissionChip(
                                select = permissions,
                                pending = pendingPermission,
                                enabled = controlsEnabled,
                                onPick = onPermissionPick,
                            )
                        },
                        meter = if (showsContextMeter(contextPressure) && contextUsed != null) {
                            {
                                val used = stringResource(R.string.chat_context_used, contextUsed)
                                ContextMeter(
                                    contextBreakdown,
                                    contextPressure,
                                    Modifier.semantics { contentDescription = used },
                                )
                            }
                        } else {
                            null
                        },
                    )

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
                        onClick = { submitDraft { currentOnSend(it) } },
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
                                ) { submitDraft { text -> sendWith(text, "steer") } },
                                HoldAction(
                                    stringResource(R.string.hold_send_queue),
                                    Icons.Outlined.Schedule,
                                    detail = stringResource(R.string.chat_composer_mode_queue_hint),
                                ) { submitDraft { text -> sendWith(text, "queue") } },
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

    // The page edits the value the field is holding, not a copy of it: closing it puts the caret
    // back where the person left it, and a send from the page takes the same clear-then-deliver
    // path the send button does, so the drawer behind it cannot keep a draft that was already sent.
    // It stays composed through its exit animation, which is why this keys on `pageComposed`.
    if (pageComposed) {
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { pageWindowReady = false } }
        FullScreenComposer(
            visibleState = pageState,
            onWindowReady = { pageWindowReady = true },
            value = editor,
            onValueChange = applyEdit,
            enabled = enabled,
            canSend = canSend,
            attachments = attachments,
            onRemoveAttachment = onRemoveAttachment,
            onRetryAttachment = onRetryAttachment,
            onAddReference = onAddReference,
            onAttachFile = onAttachFile,
            permissions = permissions,
            pendingPermission = pendingPermission,
            onPermissionPick = onPermissionPick,
            onSend = {
                submitDraft { text ->
                    currentOnSend(text)
                    fullScreen = false
                }
            },
            onDismiss = { fullScreen = false },
        )
    }
}

/**
 * The composer with a page to itself, for a prompt too long to read in a card.
 *
 * It exists because of #87's other half: once a draft is several lines the card is no place to
 * write in — it is capped by the conversation above and the keyboard below — while the send row is
 * the thing that must never move. Here the draft gets every pixel between the bars, the keyboard
 * insets come from [DsFullScreenDialog] (the app's one full-screen window, see `WindowRulesTest`),
 * and the send row sits above the keyboard rather than under it.
 *
 * The page takes the composer's own value and change handler, so there is only ever one draft:
 * typing here is typing there, and dismissing is not a save.
 */
@Composable
private fun FullScreenComposer(
    visibleState: MutableTransitionState<Boolean>,
    onWindowReady: () -> Unit,
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    attachments: List<PendingAttachment>,
    onRemoveAttachment: (Int) -> Unit,
    onRetryAttachment: (Int) -> Unit,
    onAddReference: () -> Unit,
    onAttachFile: (() -> Unit)?,
    permissions: PermissionSelect?,
    pendingPermission: String?,
    onPermissionPick: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var viewportPx by remember { mutableStateOf(0) }
    // Opened to be typed in, so the keyboard comes up with the page rather than a tap later.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // The frame wait is the app's own habit around a field that appears with its window (see
        // DsSearchField): a field that has not been measured yet still takes focus.
        androidx.compose.runtime.withFrameNanos { }
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    // A field scrolls to its caret when the *selection* changes, not when it is measured, and this
    // one is measured twice — once with the page, once more when the keyboard takes its height. The
    // page therefore used to open with a long draft showing its middle. Every time the viewport
    // settles, the caret steps one character and back: the field scrolls to where the person was
    // typing, and nothing else moves.
    androidx.compose.runtime.LaunchedEffect(viewportPx) {
        if (viewportPx <= 0 || value.text.isEmpty()) return@LaunchedEffect
        val caret = value.selection.end.coerceIn(0, value.text.length)
        onValueChange(value.copy(selection = androidx.compose.ui.text.TextRange(if (caret > 0) caret - 1 else 1)))
        androidx.compose.runtime.withFrameNanos { }
        onValueChange(value.copy(selection = androidx.compose.ui.text.TextRange(caret)))
    }
    DsFullScreenDialog(onDismissRequest = onDismiss, visibleState = visibleState, onWindowReady = onWindowReady) {
        // Every action the page has sits in this one bar, icon only: back on the leading edge, then
        // reference, attach, permission and send on the trailing edge. The card behind the page
        // spells its controls out ("Workspace Write", "Send message"); on a page the words cost the
        // room the draft needs, and the draft is the whole reason the page exists. The glyphs are
        // the composer's own, so nothing has to be learned twice.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DsSpacing.small, vertical = DsSpacing.tiny),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            DsIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                onClick = onDismiss,
            )
            Text(
                stringResource(R.string.chat_composer_fullscreen_title),
                style = DsType.std14,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CircleAction(
                icon = FeatherIcons.AtSign,
                description = stringResource(R.string.harness_add_reference),
                size = 40,
                background = colors.bgLayer3,
                tint = colors.labelPrimary,
                enabled = enabled,
                onClick = onAddReference,
                haptic = { haptics.tap() },
            )
            if (onAttachFile != null) {
                CircleAction(
                    icon = Icons.Filled.AttachFile,
                    description = stringResource(R.string.chat_composer_attach_file),
                    size = 40,
                    background = colors.bgLayer3,
                    tint = colors.labelPrimary,
                    enabled = enabled,
                    onClick = onAttachFile,
                    haptic = { haptics.tap() },
                )
            }
            // The same control as the card's chip, without the label it cannot afford here. Its
            // content description still names the current preset, so the mode is not lost to a
            // screen reader even though it is to a glance.
            PermissionChip(
                select = permissions,
                pending = pendingPermission,
                enabled = enabled,
                onPick = onPermissionPick,
                iconOnly = true,
            )
            CircleAction(
                icon = Icons.Filled.ArrowUpward,
                description = stringResource(R.string.chat_composer_send),
                size = 40,
                background = if (canSend) colors.buttonInfoFill else colors.buttonPrimaryDimmed,
                tint = if (canSend) colors.onAccent else colors.labelTertiary,
                enabled = canSend,
                onClick = onSend,
            )
        }
        // What is going to be sent with this message stays visible on the page too: attaching from
        // here and then having to go back to see whether it took would be its own small surprise.
        // The editor is drawn as the composer's card, and the tray hangs off its top edge the same
        // way it does on the card, so the two places read as one control.
        TrayAboveCard(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            tray = { AttachmentTray(attachments, onRemoveAttachment, onRetryAttachment) },
            fillCard = true,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = DsShapes.composer,
                color = colors.composerCard,
                border = BorderStroke(1.dp, colors.borderL2),
            ) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewportPx = it.height }
                        .focusRequester(focus)
                        // The page is for long text, so plain Enter keeps adding a line here; the
                        // send chord still sends, and Escape (the window's) goes back to the card.
                        .onPreviewKeyEvent { event ->
                            when (decideComposerKey(event.toComposerKeyPress(), hardwareKeyboard = false)) {
                                ComposerKeyOutcome.Send -> {
                                    if (canSend) onSend()
                                    true
                                }
                                ComposerKeyOutcome.Swallow -> true
                                else -> false
                            }
                        },
                    enabled = enabled,
                    placeholder = {
                        Text(
                            stringResource(R.string.chat_composer_hint),
                            style = DsType.std14,
                            color = colors.labelTertiary,
                        )
                    },
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
    /** The full-screen page's bar: the shield alone, with the preset name only in its description. */
    iconOnly: Boolean = false,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    if (select == null) return
    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<String?>(null) }
    val chipInteraction = remember { MutableInteractionSource() }
    val effective = pending ?: select.currentValue
    val option = select.options.firstOrNull { it.value == effective }
    val label = permissionPresetLabel(effective, option?.name)

    Box {
        if (iconOnly) {
            // No room for the preset name in a bar of icons, so the shield carries the mode twice
            // over: the colour a glance can read, and a description a screen reader can.
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.bgLayer3)
                    .clickable(interactionSource = chipInteraction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled) {
                        haptics.tap()
                        menuOpen = true
                    }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                HoverTooltip(label, chipInteraction) {
                Icon(
                    Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = permissionPresetTint(effective, option?.name, colors),
                    modifier = Modifier.size(18.dp),
                )
                }
            }
        } else {
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
                    // 10dp, not 12: on a 360dp phone (a Flip's inner screen) the row leaves the chip
                    // 144dp, and "Workspace Write" in the Android 17 font is 145dp of chip at 12dp, so
                    // it read "Workspace Wri…" with nothing else in the row to blame. The Android 15
                    // font is about 2dp narrower, which is why a test on that image alone says it fits.
                    .padding(horizontal = 10.dp)
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

/**
 * The composer's meter is 44dp wide and 6dp tall, so a fill under 6/44 of the window is narrower
 * than the bar is tall and draws as nothing: what is left is a bare grey pill that looks like a
 * stray element. Below this the bar has nothing to say, and the details panel (which has the room
 * for a label) still shows the exact figure.
 */
internal const val ContextMeterMinRatio = ContextMeterBarHeight.toFloat() / ContextMeterBarWidth

/** Whether the composer's row carries the context meter for [pressure] at all. */
internal fun showsContextMeter(pressure: ContextPressureView?): Boolean =
    (pressure?.usedRatio ?: 0f) >= ContextMeterMinRatio

/**
 * The flexible slot of the action row: the permission [chip] first, the context [meter] after it.
 *
 * The chip decides what the row is for, so it is measured with the whole slot and keeps its full
 * label whenever that label fits. The meter then takes the slot's far edge (beside send) only if a
 * whole bar fits in what the chip left, and is dropped otherwise, never squeezed to a sliver.
 * Letting both share the slot as siblings of the row is what used to cut "Workspace Write" down to
 * "Works…" on a phone, with the meter's 52dp taken off the chip before it was measured.
 *
 * It is subcomposed, and the bar's width is a constant, so a meter that does not fit is never
 * composed rather than composed and left unplaced: an unplaced node still answers to a screen
 * reader (and to a test) with "62% of context used" for a bar nobody can see.
 */
@Composable
private fun ChipThenMeter(
    modifier: Modifier,
    chip: @Composable () -> Unit,
    meter: (@Composable () -> Unit)?,
) {
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        // A box around each, so a slot is always one measurable even when its content emits nothing.
        val chipPlaceable = subcompose(ChipMeterSlot.Chip) { Box { chip() } }.first().measure(loose)
        val gap = DsSpacing.small.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else chipPlaceable.width
        val meterFits = meter != null &&
            chipPlaceable.width + gap + ContextMeterBarWidth.dp.roundToPx() <= width
        val meterPlaceable = if (meterFits) {
            subcompose(ChipMeterSlot.Meter) { Box { meter?.invoke() } }.first().measure(Constraints())
        } else {
            null
        }
        val height = maxOf(chipPlaceable.height, meterPlaceable?.height ?: 0)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            chipPlaceable.placeRelative(0, (height - chipPlaceable.height) / 2)
            meterPlaceable?.placeRelative(width - meterPlaceable.width, (height - meterPlaceable.height) / 2)
        }
    }
}

private enum class ChipMeterSlot { Chip, Meter }

// ---------------------------------------------------------------------------
// Attachments: the tray above the card
// ---------------------------------------------------------------------------

/**
 * How far the card is drawn over the tray's lower edge: the card's own corner radius. The tray then
 * shows around the card's rounded top corners and reads as sitting behind it, with the card on top.
 */
private val TrayOverlap = 20.dp

/** The tray's top corners match the card's, so the two make one outline. */
private val TrayShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

/** One chip's height, which is also the touch target of the remove button inside it. */
private val ChipHeight = 48.dp

/**
 * What the tray adds above the card once something is attached: 8dp, one 48dp chip, 8dp. The chat
 * screen adds it to the composer's caps, so a card keeps the room it has without a tray.
 */
internal val AttachmentTrayHeight = 8.dp + ChipHeight + 8.dp

/**
 * A [tray] directly above a [card], the card drawn over the tray's lower [TrayOverlap].
 *
 * The tray is measured first and the card gets what is left, so a tall tray can never take the
 * card's own room: the composer's action row lives in the card and is the thing that must never
 * lose its height (#87). The tray's height is whatever its animation reports, so while it unfolds
 * the card simply rides up with it. With [fillCard] the card takes all of the bounded room that is
 * left, which is how the full-screen page lays its editor out.
 *
 * Each slot is wrapped in a `Box` because an empty or fully collapsed `AnimatedVisibility` emits no
 * layout node, and this policy reads exactly two.
 */
@Composable
private fun TrayAboveCard(
    tray: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    fillCard: Boolean = false,
    card: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box { tray() }
            Box { card() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val overlap = TrayOverlap.roundToPx()
        val trayPlaceable = measurables[0].measure(constraints.copy(minHeight = 0))
        // Only the part of the tray above the card takes layout room; a tray still unfolding is
        // shorter than the overlap for a moment and then sits wholly behind the card.
        val trayShown = if (trayPlaceable.height == 0) 0 else trayPlaceable.height - minOf(overlap, trayPlaceable.height)
        val bounded = constraints.hasBoundedHeight
        val room = if (bounded) (constraints.maxHeight - trayShown).coerceAtLeast(0) else Constraints.Infinity
        val cardPlaceable = measurables[1].measure(
            constraints.copy(minHeight = if (fillCard && bounded) room else 0, maxHeight = room),
        )
        val width = maxOf(trayPlaceable.width, cardPlaceable.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, trayShown + cardPlaceable.height) {
            trayPlaceable.place(0, 0)
            cardPlaceable.place(0, trayShown)
        }
    }
}

/**
 * Everything picked for this message, in one horizontally scrolling strip that unfolds above the
 * composer card when the first file arrives and folds back into it when the last one leaves. Each
 * chip also eases in and out on its own, so adding the third file does not make the first two jump.
 *
 * The tray keeps showing the last non-empty list while it folds away: the list itself is already
 * empty by then, and an empty row folding shut would look like the chips vanishing first.
 */
@Composable
private fun AttachmentTray(
    attachments: List<PendingAttachment>,
    onRemove: (Int) -> Unit,
    onRetry: (Int) -> Unit,
) {
    val colors = DsTheme.colors
    val remembered = remember { arrayOfNulls<List<PendingAttachment>>(1) }
    if (attachments.isNotEmpty()) remembered[0] = attachments.toList()
    val shown = remembered[0].orEmpty()
    val liveCount by rememberUpdatedState(attachments.size)
    AnimatedVisibility(
        visible = attachments.isNotEmpty(),
        enter = DsAnimations.trayEnter,
        exit = DsAnimations.trayExit,
    ) {
        // Once the fold has finished the chips (and the encoded images behind them) are not kept.
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { if (liveCount == 0) remembered[0] = null }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = TrayShape,
            color = colors.bgLayer2,
            border = BorderStroke(1.dp, colors.borderL2),
        ) {
            val keys = remember(shown) { attachmentKeys(shown) }
            val trayState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyRow(
                state = trayState,
                modifier = Modifier.horizontalWheelScroll(trayState),
                contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp + TrayOverlap),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                itemsIndexed(shown, key = { index, _ -> keys[index] }) { index, attachment ->
                    AttachmentChip(
                        attachment = attachment,
                        // The tray outlives its list by an animation: a tap on a chip that is already
                        // folding away must not reach for an index that is no longer there.
                        onRemove = { if (index < liveCount) onRemove(index) },
                        onRetry = { if (index < liveCount) onRetry(index) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/**
 * Stable, unique keys for the lazy row, so a chip that stays keeps its place while its neighbours
 * come and go. A file's key is its upload id, which survives its state changing (uploading, ready,
 * failed). An image has no id, so it is keyed by identity; the suffix makes a duplicate impossible,
 * because a repeated key would crash the list.
 */
private fun attachmentKeys(items: List<PendingAttachment>): List<Any> {
    val seen = HashSet<Any>()
    return items.map { item ->
        val base: Any = when (item) {
            is PendingAttachment.File -> "file:${item.id}"
            is PendingAttachment.Image -> "image:${System.identityHashCode(item)}"
        }
        var key = base
        var n = 1
        while (!seen.add(key)) key = "$base#${n++}"
        key
    }
}

/**
 * One chip in the tray: what it is, and a remove button on its trailing edge.
 *
 * Every chip is [ChipHeight] tall so the tray does not step between kinds. The remove button sits
 * inside the chip as a full 48dp target around an 18dp badge, rather than overhanging the corner:
 * the lazy row clips what lies outside it, and a button half outside its parent is half out of reach
 * of a touch anyway.
 */
@Composable
private fun AttachmentChip(
    attachment: PendingAttachment,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val failed = (attachment as? PendingAttachment.File)?.state is FileUploadState.Failed
    Row(
        modifier = modifier
            .height(ChipHeight)
            .clip(DsShapes.control)
            .background(colors.bgModulePlatform)
            .border(1.dp, if (failed) colors.error else colors.borderL3, DsShapes.control),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (attachment) {
            is PendingAttachment.Image -> Box(Modifier.size(ChipHeight).background(colors.bgBase)) {
                attachment.preview?.let {
                    Image(
                        bitmap = it,
                        contentDescription = attachment.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            is PendingAttachment.File -> FileAttachmentBody(attachment, onRetry)
        }
        RemoveButton(
            description = stringResource(
                if (attachment is PendingAttachment.File) R.string.chat_composer_remove_file
                else R.string.chat_composer_remove_image,
            ),
            onClick = onRemove,
        )
    }
}

/** The glyph is 18dp; the touch target around it is 48dp. */
private val RemoveBadgeSize = 18.dp

/** The small "x" that takes a pending attachment out of the message. */
@Composable
private fun RemoveButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(DsSpacing.touchTarget)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        HoverTooltip(description, interaction) {
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
}

/**
 * A file chip's face: name, then either its size, its upload progress, or the failure. A failed
 * upload is retried by tapping the face itself; the remove button beside it stays where it is on
 * every kind.
 */
@Composable
private fun FileAttachmentBody(file: PendingAttachment.File, onRetry: () -> Unit) {
    val colors = DsTheme.colors
    val failed = file.state is FileUploadState.Failed
    Row(
        modifier = Modifier
            .widthIn(min = 96.dp, max = 200.dp)
            .fillMaxHeight()
            .clickable(enabled = failed, onClick = onRetry)
            .padding(start = 12.dp, end = 2.dp),
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
        // The circle is only an icon: name it for a mouse that rests on it. Inside the hit area,
        // so the tooltip anchors to the button and the wrapper takes no layout room.
        HoverTooltip(description, interaction) {
        Box(
            Modifier
                .pressScale(interaction, 0.9f)
                .size(size.dp)
                .clip(CircleShape)
                .background(background),
        ) { CircleGlyph(icon, size, tint, content) }
        }
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
