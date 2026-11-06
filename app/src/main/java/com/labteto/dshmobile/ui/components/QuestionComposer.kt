package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswer
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionItem
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionOption
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * The `ask_user_question` composer — a port of the harness web client's question card
 * (`packages/client/ui-user-questions/src/client/QuestionComposer.tsx`), one question at a time
 * with a batch submit at the end.
 *
 * The card collapses to its title strip. That is the harness's own rc.7 addition, and it earns its
 * place here twice over: this panel sits between the transcript and the composer rather than
 * replacing an input bar, so a question with a long detail and six options otherwise buries the
 * very conversation it is asking about.
 */

/** Which validation complaint the footer is showing, if any. */
private enum class Complaint { Unanswered, Incomplete }

/**
 * @param requestKey the request's rpcId: the identity drafts are keyed on, so a replay of the same
 *   request does not wipe a half-filled batch.
 * @param onSubmit sends the batch; returns null when the harness took it, otherwise the message the
 *   footer should show — a refusal leaves the host's wait open, so it has to be said out loud.
 * @param onDismiss ends the request unanswered, with the same return contract.
 */
@Composable
internal fun QuestionsPanel(
    requestKey: String,
    questions: List<AskUserQuestionItem>,
    onSubmit: suspend (AskUserQuestionAnswer) -> String?,
    onDismiss: suspend () -> String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dismissIsLocal: Boolean = false,
    revision: String = "",
    initialDrafts: List<QuestionDraft>? = null,
    onDraftsChange: (List<QuestionDraft>) -> Unit = {},
    statusContent: (@Composable () -> Unit)? = null,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()

    // Keyed on the request rather than on the question list: a reconnect replays the same rpcId
    // with an equal-but-new payload, and a half-filled batch has to survive that. Collapse is a
    // view preference, so it also survives a rotation — but not a genuinely new request.
    var index by remember(requestKey) { mutableIntStateOf(0) }
    var drafts by remember(requestKey) { mutableStateOf(initialDrafts?.takeIf { it.size == questions.size } ?: questions.map { QuestionDraft() }) }
    var minimized by rememberSaveable(requestKey) { mutableStateOf(false) }
    var busy by remember(requestKey) { mutableStateOf(false) }
    var complaint by remember(requestKey) { mutableStateOf<Complaint?>(null) }
    var failure by remember(requestKey) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(revision) { busy = false }
    val bodyScroll = rememberScrollState()
    var expanded by remember(requestKey) { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale

    val question = questions.getOrNull(index) ?: return
    val draft = drafts.getOrElse(index) { QuestionDraft() }
    val multiSelect = question.multiSelect == true
    val options = question.options.orEmpty()

    fun clearFeedback() {
        complaint = null
        failure = null
    }

    fun send(block: suspend () -> String?) {
        busy = true
        clearFeedback()
        scope.launch {
            // On success the latch stays closed: the store drops the request the moment the host
            // takes the answer, so this card leaves the composition rather than re-arming for a
            // second submit the host would refuse as `not-pending`. Only a failure hands it back —
            // and a failure is the one case where the host's wait is still open to answer.
            val refusal = block()
            if (refusal != null) {
                busy = false
                failure = refusal
            }
        }
    }

    fun submit(values: List<QuestionDraft>) {
        val missing = firstIncomplete(values)
        if (missing >= 0) {
            index = missing
            complaint = Complaint.Incomplete
            return
        }
        send { onSubmit(encodeAnswers(questions, values)) }
    }

    fun continueFlow() {
        if (!draft.answered()) {
            complaint = Complaint.Unanswered
            return
        }
        if (index < questions.lastIndex) {
            index += 1
            clearFeedback()
        } else {
            submit(drafts)
        }
    }

    fun updateDraft(update: (QuestionDraft) -> QuestionDraft) {
        drafts = drafts.mapIndexed { at, value -> if (at == index) update(value) else value }
        onDraftsChange(drafts)
        clearFeedback()
    }

    fun skipQuestion() {
        val next = drafts.mapIndexed { at, value ->
            if (at == index) QuestionDraft(skipped = true) else value
        }
        drafts = next
        onDraftsChange(next)
        clearFeedback()
        if (index < questions.lastIndex) index += 1 else submit(next)
    }

    @Composable
    fun Card(cap: Dp, compact: Boolean, inSheet: Boolean) {
        // Read the window where the input actually lives: an expanded sheet has its own window.
        // Window changes must also release/reacquire the pause even if the field keeps its focus.
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        var fieldFocused by remember { mutableStateOf(false) }
        val reportFocus by rememberUpdatedState(onFocusChange)
        androidx.compose.runtime.LaunchedEffect(fieldFocused, windowFocused) {
            reportFocus(fieldFocused && windowFocused)
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { reportFocus(false) }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { fieldFocused = it.hasFocus }
                .then(if (compact) Modifier else Modifier.heightIn(max = cap))
                .animateContentSize(DsAnimations.expand),
            shape = DsShapes.approvalCard,
            color = colors.bgLayer1,
            border = BorderStroke(1.dp, colors.borderL2),
            shadowElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuestionHeader(
                    question = question,
                    index = index,
                    count = questions.size,
                    minimized = compact,
                    busy = if (dismissIsLocal) false else busy || !enabled,
                    onToggle = {
                        if (inSheet) { expanded = false; minimized = true }
                        else if (compact && !minimized) expanded = true
                        else minimized = !minimized
                    },
                    onExpand = if (inSheet || statusContent == null) null else ({ expanded = true }),
                    onDismiss = { send { onDismiss() } },
                )

                statusContent?.invoke()
                if (!compact) {
                    QuestionBody(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(bodyScroll),
                        question = question,
                        draft = draft,
                        options = options,
                        multiSelect = multiSelect,
                        busy = busy || !enabled,
                        lastQuestion = index == questions.lastIndex,
                        onChoose = { label ->
                            updateDraft { it.choose(label, multiSelect) }
                            if (!multiSelect) index = advanceFrom(index, questions.size)
                        },
                        onCustomChange = { text -> updateDraft { it.withCustom(text, multiSelect) } },
                        onContinue = { continueFlow() },
                    )

                    QuestionFooter(
                        index = index,
                        count = questions.size,
                        busy = busy || !enabled,
                        canContinue = draft.answered(),
                        feedback = when {
                            failure != null -> failure
                            complaint == Complaint.Unanswered -> stringResource(R.string.questions_error_unanswered)
                            complaint == Complaint.Incomplete -> stringResource(R.string.questions_error_incomplete)
                            else -> null
                        },
                        onPrevious = { index -= 1; clearFeedback() },
                        onNext = { index += 1; clearFeedback() },
                        onSkip = { skipQuestion() },
                        onContinue = { continueFlow() },
                    )
                }
            }
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // A useful body needs room after the header, status and fixed actions, including large fonts.
        // In tight chat/IME space show a named expand action instead of a clipped option fragment.
        val compact = minimized || (statusContent != null && maxHeight < 300.dp * fontScale)
        if (expanded) {
            DsButton(stringResource(R.string.ux_b_view_questions), { expanded = true }, variant = DsButtonVariant.Ghost)
            DsBottomSheet(
                title = stringResource(R.string.harness_questions), onDismiss = { expanded = false },
                trailing = { DsIconButton(Icons.Filled.Close, stringResource(R.string.common_close), { expanded = false }) },
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Card(minOf(maxHeight, 600.dp), compact = false, inSheet = true)
                }
            }
        } else {
            Card(if (statusContent == null) questionCardMaxHeight(maxHeight) else minOf(maxHeight, 420.dp), compact, inSheet = false)
        }
    }
}

/**
 * The strip that stays when the card is collapsed.
 *
 * The whole row is the toggle. The harness spends a 24px icon button on it, which is below a touch
 * target here and would put two of them side by side in a header only a phone-width wide; tapping
 * the title is the affordance a phone already expects. The chevron stays visible as a decoration —
 * [DisclosureRow] records the same lesson, that a hover-revealed affordance is an invisible one on
 * a touchscreen — while dismiss keeps its own button, because it is destructive and must not be
 * reachable by a stray tap on the title.
 *
 * The progress count rides here rather than in the footer as the web card has it: collapsing is the
 * only way to read the transcript while a batch is pending, so the strip has to say that something
 * is still waiting, and how much of it.
 */
@Composable
private fun QuestionHeader(
    question: AskUserQuestionItem,
    index: Int,
    count: Int,
    minimized: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onExpand: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val toggleLabel = stringResource(
        if (minimized) R.string.questions_expand else R.string.questions_collapse,
    )
    val expandedState = stringResource(if (minimized) R.string.ux_b_collapsed else R.string.ux_b_expanded)
    val rotation by animateFloatAsState(
        targetValue = if (minimized) 0f else 90f,
        animationSpec = DsAnimations.chevron,
        label = "questionCollapse",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = DsSpacing.touchTarget)
                .clickable(enabled = !busy, role = Role.Button, onClickLabel = toggleLabel, onClick = onToggle)
                .semantics(mergeDescendants = true) {
                    contentDescription = question.question
                    stateDescription = expandedState
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                question.header?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = DsType.caption11, color = colors.labelTertiary)
                }
                // Only while collapsed. Expanded, the question belongs to the scrolling body: it
                // is content, and the header is chrome that must stay small enough for the answer
                // controls below it to survive a cramped card. See [QuestionBody].
                if (minimized) {
                    Text(
                        question.question,
                        style = DsType.std14Strong,
                        color = colors.labelPrimary,
                        // A collapsed strip taller than the expanded card's header is not a collapse.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (count > 1) {
            Text(
                stringResource(R.string.questions_progress, index + 1, count),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            Spacer(Modifier.width(4.dp))
        }
        if (onExpand != null) DsIconButton(
            icon = Icons.Filled.OpenInFull,
            contentDescription = stringResource(R.string.ux_b_view_questions),
            onClick = onExpand,
        )
        DsIconButton(
            icon = Icons.Filled.Close,
            contentDescription = stringResource(R.string.questions_dismiss),
            onClick = onDismiss,
            enabled = !busy,
            iconSize = 18.dp,
        )
    }
}

/** Detail, options, and the free-form field — the only part of the card that scrolls. */
@Composable
private fun QuestionBody(
    question: AskUserQuestionItem,
    draft: QuestionDraft,
    options: List<AskUserQuestionOption>,
    multiSelect: Boolean,
    busy: Boolean,
    lastQuestion: Boolean,
    onChoose: (String) -> Unit,
    onCustomChange: (String) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The question itself leads the scrolling content rather than sitting in the fixed header.
        //
        // The card is a Column of header, weighted body and footer, and a Compose Column measures
        // its non-weighted children first — so whatever the header asks for, the footer takes from
        // what is left and the body divides the remainder. An unbounded question in the header
        // could therefore ask for the entire cap, and once a keyboard shrank the column and the cap
        // was recomputed, a prompt of a few lines did exactly that: the card rendered as its
        // question and nothing else, with no field to type in and no Submit or Skip to resolve it
        // with (#31). Down here the same text can be as long as it likes, because the body is the
        // part that scrolls.
        Text(
            question.question,
            style = DsType.large20,
            color = DsTheme.colors.labelPrimary,
        )

        question.detail?.takeIf { it.isNotBlank() }?.let { MarkdownText(it) }

        options.forEachIndexed { ordinal, option ->
            OptionRow(
                ordinal = ordinal + 1,
                option = option,
                selected = option.label in draft.selected,
                multiSelect = multiSelect,
                enabled = !busy,
                onClick = { onChoose(option.label) },
            )
        }

        CustomAnswerField(
            value = draft.custom,
            hasOptions = options.isNotEmpty(),
            multiSelect = multiSelect,
            enabled = !busy,
            lastQuestion = lastQuestion,
            onValueChange = onCustomChange,
            onContinue = onContinue,
        )
    }
}

/** One offered choice: its marker, its label without the recommendation suffix, its description. */
@Composable
private fun OptionRow(
    ordinal: Int,
    option: AskUserQuestionOption,
    selected: Boolean,
    multiSelect: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val display = remember(option.label) { parseRecommendedLabel(option.label) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                haptics.selection()
                onClick()
            },
        shape = DsShapes.menu,
        color = if (selected) colors.accentTertiary else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.borderL3),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            // Top, not centre: a wrapped description would otherwise drift the marker down the
            // copy block and stop it reading as a control.
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OptionMarker(ordinal = ordinal, selected = selected, multiSelect = multiSelect)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        display.display,
                        style = DsType.base16Strong,
                        color = if (selected) colors.accent else colors.labelPrimary,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (display.recommended) {
                        Text(
                            stringResource(R.string.questions_recommended),
                            style = DsType.small13,
                            color = colors.accent,
                        )
                    }
                }
                option.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = DsType.small13, color = colors.labelTertiary)
                }
            }
        }
    }
}

/** The ordinal badge of a single choice, or the check box of a multiple one. */
@Composable
private fun OptionMarker(ordinal: Int, selected: Boolean, multiSelect: Boolean) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.size(22.dp),
        shape = if (multiSelect) DsShapes.chip else CircleShape,
        color = if (selected) colors.accent else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.borderL3),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.onAccent,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Text(
                    ordinal.toString(),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
        }
    }
}

/**
 * The free-form answer.
 *
 * Deliberately unfocused on arrival. The web card autofocuses the optionless textarea once per
 * question and keeps a ref so re-expanding never steals focus back; on a phone a focus also raises
 * the keyboard, which would cover the transcript the user collapsed the card to read — so the
 * cheapest way to honour that intent here is never to take focus at all.
 */
@Composable
private fun CustomAnswerField(
    value: String,
    hasOptions: Boolean,
    multiSelect: Boolean,
    enabled: Boolean,
    lastQuestion: Boolean,
    onValueChange: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val colors = DsTheme.colors
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().inkFieldFrame(),
        shape = DsShapes.control,
        enabled = enabled,
        placeholder = {
            Text(
                stringResource(R.string.questions_custom_placeholder),
                style = DsType.std14,
                color = colors.labelCaption,
            )
        },
        leadingIcon = if (!hasOptions) {
            null
        } else {
            {
                if (multiSelect) {
                    OptionMarker(ordinal = 0, selected = value.isNotBlank(), multiSelect = true)
                } else {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        // The web card continues on Enter and guards against the IME committing a composition as
        // one. An IME action never fires mid-composition, so the guard has no counterpart here.
        singleLine = hasOptions,
        minLines = if (hasOptions) 1 else 2,
        keyboardOptions = KeyboardOptions(
            imeAction = if (lastQuestion) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onContinue() },
            onNext = { onContinue() },
        ),
        // The same Ink plate as every other input: bgLayer2, a 12dp corner, and the outline from
        // `inkFieldFrame` rather than Material's underline.
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.bgLayer2,
            unfocusedContainerColor = colors.bgLayer2,
            disabledContainerColor = colors.bgLayer1,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/** Pager, the one feedback line, and the two actions that move the batch along. */
@Composable
private fun QuestionFooter(
    index: Int,
    count: Int,
    busy: Boolean,
    canContinue: Boolean,
    feedback: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
) {
    val colors = DsTheme.colors
    feedback?.let {
        Text(it, style = DsType.caption11, color = colors.error)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count > 1) {
            DsIconButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.questions_nav_previous),
                onClick = onPrevious,
                enabled = index > 0 && !busy,
                iconSize = 18.dp,
            )
            DsIconButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.questions_nav_next),
                onClick = onNext,
                enabled = index < count - 1 && !busy,
                iconSize = 18.dp,
            )
        }
        Spacer(Modifier.weight(1f))
        DsButton(
            text = stringResource(R.string.questions_skip),
            onClick = onSkip,
            enabled = !busy,
            variant = DsButtonVariant.Outline,
            size = DsButtonSize.Small,
        )
        Spacer(Modifier.width(8.dp))
        DsButton(
            text = when {
                busy -> stringResource(R.string.questions_submitting)
                index < count - 1 -> stringResource(R.string.questions_next)
                else -> stringResource(R.string.questions_submit)
            },
            onClick = onContinue,
            enabled = canContinue && !busy,
            variant = DsButtonVariant.Primary,
            size = DsButtonSize.Small,
        )
    }
}
