package com.labteto.dshmobile.ui.screens.main

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.HoverTooltip
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.clickableWithHold
import com.labteto.dshmobile.ui.components.pressScale
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.settings.LanguageOptions
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.voice.AndroidSpeechEngine
import com.labteto.dshmobile.ui.voice.DictationController
import com.labteto.dshmobile.ui.voice.DictationHost
import com.labteto.dshmobile.ui.voice.DictationInterruption
import com.labteto.dshmobile.ui.voice.DictationLanguageChoice
import com.labteto.dshmobile.ui.voice.DictationLanguages
import com.labteto.dshmobile.ui.voice.DictationNotice
import com.labteto.dshmobile.ui.voice.DictationProblem
import com.labteto.dshmobile.ui.voice.DictationSignal
import com.labteto.dshmobile.ui.voice.DictationState
import com.labteto.dshmobile.ui.voice.HandlerDictationClock
import com.labteto.dshmobile.ui.voice.SpeechEngine
import com.labteto.dshmobile.ui.voice.dictationChunk
import com.labteto.dshmobile.ui.voice.insertDictation
import com.labteto.dshmobile.ui.voice.languageAttempts
import com.labteto.dshmobile.ui.voice.noticeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * What the composer needs to offer dictation. Null (the default) means no microphone at all: the
 * setting is off, or the screen is a preview or a test that has no speech service.
 *
 * The chat screen builds this from the app's settings; the composer never reads settings itself.
 */
@Immutable
internal class DictationConfig(
    /** The stored language choice (see `DictationLanguageChoice`): null follows the app. */
    val languageChoice: String?,
    /** The per-app language override, null for the system's. */
    val appLanguage: String?,
    /** Whether the one-time note on where speech goes was already shown. */
    val noticeSeen: Boolean,
    /** Changes with the session the composer writes to; a take never outlives it. */
    val sessionKey: Any?,
    /** Bumped by the keyboard shortcut; each new value is one press. */
    val toggleTick: Int,
    val onNoticeSeen: () -> Unit,
    val onLanguageChoice: (String?) -> Unit,
    /** Replaces the phone's recogniser: tests bring a scripted one. */
    val engineFactory: ((Context) -> SpeechEngine)? = null,
) : ComposerVoiceSource {
    /** The shared composer's view of [rememberComposerDictation]: the same controls, behind its slots. */
    @Composable
    override fun rememberVoice(enabled: Boolean, onInsert: (spoken: String) -> Unit): ComposerVoice? {
        val dictation = rememberComposerDictation(this, enabled, onInsert) ?: return null
        val style = pendingDictationStyle(DsTheme.colors.accent)
        return ComposerVoice(
            cancel = dictation.cancel,
            pending = { base, draft, cursor -> pendingDictationTransformation(base, dictation.state, draft, cursor, style) },
            stripVisible = dictation.stripVisible(),
            micButton = { DictationMicButton(dictation) },
            strip = { DictationStrip(dictation) },
        )
    }

    override fun insert(draft: String, cursor: Int, spoken: String): TextFieldValue? =
        insertDictation(draft, cursor, spoken)?.let { TextFieldValue(it.text, TextRange(it.cursor)) }
}

/** What the composer draws and calls; rebuilt whenever the dictation changes. */
@Immutable
internal class ComposerDictation(
    val state: DictationState,
    val notice: DictationNotice?,
    /** The microphone was tapped. The first time ever, this shows the note before anything else. */
    val onTap: () -> Unit,
    /** The microphone was held: the language sheet. */
    val onHold: () -> Unit,
    /** Discards the take in progress. */
    val cancel: () -> Unit,
    val openAppSettings: () -> Unit,
)

private class PermissionAnswer(val granted: Boolean, val blocked: Boolean)

/**
 * Builds the dictation for one composer, or null when the phone has no recognition service.
 *
 * It owns the pieces that only a screen can: the system's microphone dialog, the lifecycle and the
 * window focus (a take ends when the app leaves the foreground or another window covers this one,
 * and nothing starts from a screen that is not in front), the session key and the hapticks. It also
 * draws the two dialogs dictation has: the one-time note and the language sheet.
 *
 * @param enabled false while the composer cannot send (no session); a running take is discarded.
 * @param onInsert puts the recognised text into the draft at its caret. The only way text arrives.
 */
@Composable
internal fun rememberComposerDictation(
    config: DictationConfig?,
    enabled: Boolean,
    onInsert: (String) -> Unit,
): ComposerDictation? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val windowInfo = LocalWindowInfo.current
    val haptics = rememberDsHaptics()
    val activity = remember(context) { context.findComponentActivity() }
    val currentOnInsert by rememberUpdatedState(onInsert)
    val currentConfig by rememberUpdatedState(config)

    // Built once for the composer. The factory is read through a holder: a caller that rebuilds its
    // lambda on every composition must not get a new recogniser, and with it a new controller,
    // which would end a take that is running.
    val currentFactory by rememberUpdatedState(config?.engineFactory)
    val engine = remember(context, config != null) {
        config?.let { (currentFactory ?: { c -> AndroidSpeechEngine(c) })(context) }
    }
    val attempts = remember(config?.languageChoice, config?.appLanguage) {
        languageAttempts(config?.languageChoice, config?.appLanguage)
    }

    val holder = remember { arrayOfNulls<DictationController>(1) }
    var answer by remember { mutableStateOf<PermissionAnswer?>(null) }
    var startRequested by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var languageOpen by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // After a refusal, "no rationale to show" is how Android says the dialog will not come back.
        val blocked = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        answer = PermissionAnswer(granted, blocked)
    }

    // Read through a holder so the host, and with it the controller, is not rebuilt when the
    // haptics object is: a new controller would end a take that is running.
    val currentHaptics by rememberUpdatedState(haptics)
    val host = remember(context, lifecycle, windowInfo, permissionLauncher) {
        object : DictationHost {
            override fun microphoneAllowed() =
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            override fun requestMicrophone() {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            // In front and focused: not in the background, not behind the lock screen, no dialog on top.
            override fun canListen() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && windowInfo.isWindowFocused

            override fun insert(text: String) = currentOnInsert(text)

            override fun signal(signal: DictationSignal) {
                when (signal) {
                    DictationSignal.Started -> currentHaptics.toggle(true)
                    DictationSignal.Stopped, DictationSignal.Cancelled -> currentHaptics.toggle(false)
                    DictationSignal.Inserted -> currentHaptics.confirm()
                    DictationSignal.Failed -> currentHaptics.reject()
                }
            }
        }
    }

    val controller = remember(engine, host, config?.sessionKey, attempts) {
        engine?.takeIf { it.available }?.let { DictationController(it, host, HandlerDictationClock(), attempts) }
            .also { holder[0] = it }
    }
    // The composer leaving the screen, or the session changing under it, ends the take and gives
    // the recogniser back.
    DisposableEffect(controller) { onDispose { controller?.dispose() } }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) controller?.interrupt(DictationInterruption.Background)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(controller) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            if (!focused) controller?.interrupt(DictationInterruption.WindowCovered)
        }
    }
    LaunchedEffect(controller, enabled) { if (!enabled) controller?.cancel() }

    // The answer to the system dialog, and a start asked for from a dialog of ours, both arrive
    // while the activity is not yet resumed and focused again. Waiting keeps "never listen from a
    // screen that is not in front" true without losing the tap.
    LaunchedEffect(answer) {
        val given = answer ?: return@LaunchedEffect
        lifecycle.withResumed { }
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        answer = null
        holder[0]?.onPermissionResult(given.granted, given.blocked)
    }
    LaunchedEffect(startRequested) {
        if (!startRequested) return@LaunchedEffect
        lifecycle.withResumed { }
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        startRequested = false
        holder[0]?.toggle()
    }

    val state = controller?.state?.collectAsState()?.value ?: DictationState.Idle
    val notice = controller?.notice?.collectAsState()?.value
    LaunchedEffect(notice?.serial) {
        val shown = notice ?: return@LaunchedEffect
        delay(shown.problem.noticeMillis())
        controller?.clearNotice(shown.serial)
    }

    val tap: () -> Unit = {
        val cfg = currentConfig
        val live = holder[0]
        if (cfg != null && live != null) {
            if (live.state.value == DictationState.Idle && !cfg.noticeSeen) noteOpen = true else live.toggle()
        }
    }
    val currentTap by rememberUpdatedState(tap)
    var lastTick by remember { mutableIntStateOf(config?.toggleTick ?: 0) }
    LaunchedEffect(config?.toggleTick) {
        val tick = config?.toggleTick ?: return@LaunchedEffect
        if (tick != lastTick) {
            lastTick = tick
            currentTap()
        }
    }

    if (noteOpen && config != null) {
        DsDialog(
            title = stringResource(R.string.voice_notice_title),
            onDismiss = { noteOpen = false },
            body = { Text(stringResource(R.string.voice_notice_body), style = DsType.std14, color = DsTheme.colors.labelSecondary) },
            actions = {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { noteOpen = false },
                    variant = DsButtonVariant.Ghost,
                )
                DsButton(
                    text = stringResource(R.string.voice_notice_continue),
                    onClick = {
                        noteOpen = false
                        config.onNoticeSeen()
                        startRequested = true
                    },
                )
            },
        )
    }
    if (languageOpen && config != null) {
        DictationLanguageSheet(
            current = config.languageChoice,
            onPick = { choice -> config.onLanguageChoice(choice) },
            onDismiss = { languageOpen = false },
        )
    }

    if (controller == null) return null
    return ComposerDictation(
        state = state,
        notice = notice,
        onTap = tap,
        onHold = { languageOpen = true },
        cancel = { controller.cancel() },
        openAppSettings = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }
        },
    )
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

// ---------------------------------------------------------------------------
// The microphone
// ---------------------------------------------------------------------------

/** The microphone's width in the field's trailing slot: the 48dp touch target. */
internal val DictationButtonSize = 48.dp

/**
 * The microphone, and the only control dictation adds to the composer's own row of controls.
 *
 * It lives in the message field's trailing slot rather than in the row of `+`, `@`, the permission
 * chip and send: at 360dp that row leaves the chip 144dp for "Workspace Write", and a fifth 48dp
 * button would take it to 88dp (see ComposerNarrowLayoutTest). The slot is what the field already
 * gives a button.
 *
 * What it is saying is never in colour alone: idle is an outlined microphone, listening is a stop
 * square on a filled disc with a ring that breathes with the voice, working is a spinner, and the
 * label a screen reader hears changes between "Start dictation" and "Stop dictation".
 */
@Composable
internal fun DictationMicButton(dictation: ComposerDictation, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val state = dictation.state
    val listening = state is DictationState.Listening
    val working = state is DictationState.Starting || state is DictationState.Finishing || state is DictationState.AskingPermission
    val description = stringResource(
        when {
            listening -> R.string.voice_stop
            working -> R.string.voice_in_progress
            else -> R.string.voice_start
        },
    )
    val level by animateFloatAsState((state as? DictationState.Listening)?.level ?: 0f, tween(90), label = "micLevel")
    val ring = colors.buttonInfoFill.copy(alpha = 0.28f)
    Box(
        modifier = modifier
            .size(DictationButtonSize)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .clickableWithHold(
                holdLabel = stringResource(R.string.voice_language_hold_label),
                onHold = dictation.onHold,
                enabled = !working,
                role = Role.Button,
                interactionSource = interaction,
                onClick = dictation.onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        HoverTooltip(description, interaction) {
            Box(
                Modifier
                    .pressScale(interaction, 0.9f)
                    .size(40.dp)
                    .drawBehind {
                        if (listening) {
                            // The ring grows from just outside the disc to the button's own edge
                            // (24dp), with the voice.
                            drawCircle(ring, radius = size.minDimension * 0.5f + 1.dp.toPx() + 3.dp.toPx() * level)
                        }
                    }
                    .clip(CircleShape)
                    .background(if (listening) colors.buttonInfoFill else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    working -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = colors.labelSecondary,
                        strokeWidth = 2.dp,
                    )
                    listening -> Icon(Icons.Filled.Stop, null, Modifier.size(20.dp), tint = colors.onAccent)
                    else -> Icon(Icons.Outlined.Mic, null, Modifier.size(20.dp), tint = colors.labelSecondary)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The strip under the message box
// ---------------------------------------------------------------------------

/** Whether the strip has anything to say: a take in progress, or a message to show. */
internal fun ComposerDictation.stripVisible(): Boolean =
    state is DictationState.Starting || state is DictationState.Listening || state is DictationState.Finishing || notice != null

/**
 * The line between the message box and the row of controls while a take runs, and for the few
 * seconds a message lives. It carries the state in words and a level indicator (so listening is
 * never only a colour), the Cancel that discards the take, and, for a permission that can only be
 * given back in the system settings, the way there.
 *
 * Live-region semantics make a screen reader say "Listening…" when it appears and the message
 * when one does, without taking focus.
 */
@Composable
internal fun DictationStrip(dictation: ComposerDictation, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val state = dictation.state
    val notice = dictation.notice
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = DictationButtonSize),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        if (state is DictationState.Starting || state is DictationState.Listening || state is DictationState.Finishing) {
            val level = (state as? DictationState.Listening)?.level ?: 0f
            if (state is DictationState.Finishing) {
                CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
            } else {
                LevelBars(level, colors.accent)
            }
            Text(
                stringResource(
                    when (state) {
                        is DictationState.Listening -> R.string.voice_listening
                        is DictationState.Finishing -> R.string.voice_finishing
                        else -> R.string.voice_starting
                    },
                ),
                style = DsType.small13,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            StripAction(stringResource(R.string.common_cancel), dictation.cancel)
        } else if (notice != null) {
            Text(
                stringResource(noticeText(notice.problem)),
                style = DsType.small13,
                color = colors.warnText,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = DsSpacing.small).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (notice.problem.offersSettings) {
                StripAction(stringResource(R.string.voice_open_settings), dictation.openAppSettings)
            }
        }
    }
}

private fun noticeText(problem: DictationProblem): Int = when (problem) {
    DictationProblem.NoMatch -> R.string.voice_err_no_match
    DictationProblem.Timeout -> R.string.voice_err_timeout
    DictationProblem.Network -> R.string.voice_err_network
    DictationProblem.Busy -> R.string.voice_err_busy
    DictationProblem.Audio -> R.string.voice_err_audio
    DictationProblem.Service -> R.string.voice_err_service
    DictationProblem.Language -> R.string.voice_err_language
    DictationProblem.Failed -> R.string.voice_err_failed
    DictationProblem.Interrupted -> R.string.voice_err_interrupted
    DictationProblem.PermissionDenied -> R.string.voice_perm_denied
    DictationProblem.PermissionBlocked -> R.string.voice_perm_blocked
}

/** A text action in the strip: a 48dp-tall target around its label, which is the state, not a colour. */
@Composable
private fun StripAction(label: String, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Box(
        Modifier
            .heightIn(min = DictationButtonSize)
            .clip(DsShapes.control)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DsSpacing.medium),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = DsType.small13, color = colors.accent, textDecoration = TextDecoration.Underline)
    }
}

/** Five bars that follow the voice. Decoration: the words next to them say what they show. */
@Composable
private fun LevelBars(level: Float, color: Color) {
    val animated by animateFloatAsState(level, tween(90), label = "stripLevel")
    Row(
        Modifier.height(24.dp).clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(0.55f, 0.85f, 1f, 0.8f, 0.5f).forEach { reach ->
            Box(
                Modifier
                    .width(4.dp)
                    .height((6f + 18f * animated * reach).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// What is being heard, in the message box
// ---------------------------------------------------------------------------

/**
 * Shows [chunk] in the field at its caret while it is still only a hypothesis, over whatever
 * [base] already draws (the reference chips).
 *
 * It changes what is painted, not what is held: the draft the field edits and the app saves is
 * untouched until the take ends, so cancelling, an error and a draft saved mid-take all see only
 * the text that was there. The pending text is italic and underlined as well as tinted, so it is
 * recognisable without the colour.
 */
internal class PendingDictationTransformation(
    private val base: VisualTransformation,
    private val at: Int,
    private val chunk: String,
    private val style: SpanStyle,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val inner = base.filter(text)
        val shown = inner.text
        val insertAt = inner.offsetMapping.originalToTransformed(at.coerceIn(0, text.length)).coerceIn(0, shown.length)
        val built = AnnotatedString.Builder().apply {
            append(shown.subSequence(0, insertAt))
            pushStyle(style)
            append(chunk)
            pop()
            append(shown.subSequence(insertAt, shown.length))
        }.toAnnotatedString()
        return TransformedText(
            built,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int {
                    val mapped = inner.offsetMapping.originalToTransformed(offset)
                    return if (mapped > insertAt) mapped + chunk.length else mapped
                }

                override fun transformedToOriginal(offset: Int): Int = when {
                    offset <= insertAt -> inner.offsetMapping.transformedToOriginal(offset)
                    // Inside the pending text, the caret is where the text will go.
                    offset <= insertAt + chunk.length -> inner.offsetMapping.transformedToOriginal(insertAt)
                    else -> inner.offsetMapping.transformedToOriginal(offset - chunk.length)
                }
            },
        )
    }
}

/** What the field paints while a take is running: the base, with the heard text in place, or just the base. */
internal fun pendingDictationTransformation(
    base: VisualTransformation,
    state: DictationState,
    draft: String,
    cursor: Int,
    style: SpanStyle,
): VisualTransformation {
    val heard = when (state) {
        is DictationState.Listening -> state.heard
        is DictationState.Finishing -> state.heard
        else -> return base
    }
    val chunk = dictationChunk(draft, cursor, heard) ?: return base
    return PendingDictationTransformation(base, chunk.at, chunk.text, style)
}

/** The style of text that has been heard but is not in the draft yet. */
internal fun pendingDictationStyle(accent: Color) =
    SpanStyle(color = accent, fontStyle = FontStyle.Italic, textDecoration = TextDecoration.Underline)

// ---------------------------------------------------------------------------
// Language
// ---------------------------------------------------------------------------

/** The name of a stored dictation language choice, as the picker and the Settings row show it. */
@Composable
internal fun dictationLanguageLabel(choice: String?): String = when {
    choice == null -> stringResource(R.string.voice_language_follow_app)
    choice == DictationLanguageChoice.DEVICE -> stringResource(R.string.voice_language_device)
    else -> LanguageOptions.firstOrNull { it.tag == choice }?.label ?: stringResource(R.string.voice_language_device)
}

/** The stored choices in the order the picker lists them: follow the app, the phone's, then the eleven. */
internal val DictationChoices: List<String?> =
    listOf(null, DictationLanguageChoice.DEVICE) + DictationLanguages.keys.sortedBy { tag ->
        LanguageOptions.indexOfFirst { it.tag == tag }
    }

/**
 * The sheet a hold on the microphone opens: where dictation listens for, remembered.
 *
 * The same choice is in Settings (Chat), which is the way to it for a keyboard.
 */
@Composable
private fun DictationLanguageSheet(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    DsBottomSheet(title = stringResource(R.string.voice_language_title), onDismiss = onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            DictationChoices.forEach { choice ->
                val selected = choice == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = DsSpacing.touchTarget)
                        .clip(DsShapes.row)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            haptics.selection()
                            onPick(choice)
                            onDismiss()
                        }
                        .padding(horizontal = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        dictationLanguageLabel(choice),
                        style = DsType.rowTitle,
                        color = colors.labelPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) Icon(Icons.Filled.Check, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
