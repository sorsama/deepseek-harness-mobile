package com.labteto.dshmobile.ui.screens.main

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.data.QuestionCardState
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.*
import com.labteto.dshmobile.ui.theme.*
import kotlinx.serialization.builtins.ListSerializer

/**
 * The timed question surface (design 10's takeover, timed variant).
 *
 * Several questions can be in the air at once — one on screen and the rest parked — so the surface
 * opens with a horizontal strip of parked cards and then gives the newest one the body. The
 * countdown is part of the card's chrome: it says how long the agent will wait and offers the one
 * action that buys more time, without ever becoming a second place to answer.
 *
 * The question card itself (header, options, footer) is the shared `QuestionsPanel`; only the
 * chrome around it, the waiting line and the answered reply card are this file's.
 */
@Composable
internal fun ModernQuestions(store: SessionStore, sessionId: String?, modifier: Modifier = Modifier) {
    val available by store.timedQuestionsAvailable.collectAsStateWithLifecycle()
    if (available == false) return
    val offline = stringResource(R.string.common_offline)
    val cards by store.questionSessions.cards.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    val drafts = remember(appContext) {
        QuestionDraftStore(appContext.getSharedPreferences(QUESTION_DRAFT_FILE, Context.MODE_PRIVATE))
    }
    val hostKey = store.activeHostKey
    // A settled card has nothing left to answer, so what was typed into it has no further use. This
    // also catches a draft written a moment after its card was answered.
    LaunchedEffect(cards, hostKey) {
        cards.filter { it.state == "settled" }.forEach { drafts.drop(questionDraftKey(hostKey, it.key)) }
    }
    // Once a launch: whatever a crash or a card that never came back left behind, once it is old
    // enough that no timed question can still be waiting on it.
    LaunchedEffect(drafts) {
        drafts.sweep(liveKeys = cards.mapTo(mutableSetOf()) { questionDraftKey(hostKey, it.key) })
    }
    val sessionCards = cards.filter { it.sessionId == sessionId }
    var selected by remember(store.activeHostKey, sessionId) { mutableStateOf<String?>(null) }
    val newest = sessionCards.lastOrNull { it.state != "settled" && !it.hidden }?.key
    LaunchedEffect(newest) { if (newest != null) selected = newest }
    val shown = sessionCards.firstOrNull { it.key == selected && !it.hidden }
    if (sessionCards.isEmpty()) return
    Column(
        modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        val otherCards = sessionCards.filter { it.key != shown?.key }
        if (otherCards.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                otherCards.forEach { card ->
                    ParkedQuestionChip(
                        card = card,
                        onClick = {
                            store.questionSessions.hide(card.key, false)
                            selected = card.key
                        },
                    )
                }
            }
        }
        shown?.let { card ->
            key(store.activeHostKey, card.key) {
                val draftKey = questionDraftKey(store.activeHostKey, card.key)
                val saved = remember(draftKey) {
                    runCatching {
                        WireJson.decodeFromString(
                            ListSerializer(QuestionDraft.serializer()),
                            drafts.read(draftKey)!!,
                        )
                    }.getOrNull()
                }
                DisposableEffect(card.key) {
                    onDispose { store.questionSessions.focus(card.key, false) }
                }
                if (card.state == "settled" || card.queued) {
                    QuestionReplyCard(card, onHide = { store.questionSessions.hide(card.key, true) })
                } else {
                    QuestionsPanel(
                        requestKey = draftKey, questions = card.questions,
                        dismissIsLocal = true, enabled = card.ready, revision = "${card.state}/${card.ready}", initialDrafts = saved,
                        onDraftsChange = {
                            store.questionSessions.hold(card.key)
                            drafts.save(draftKey, WireJson.encodeToString(ListSerializer(QuestionDraft.serializer()), it))
                        },
                        onSubmit = {
                            val problem = store.questionSessions.answer(card.key, it)
                            // Taken: the answer is the host's now, and the draft has nothing to keep.
                            if (problem == null) drafts.drop(draftKey)
                            problem?.let { message -> if (message == "Disconnected") offline else message }
                        },
                        // Dismissing is the reader saying they are done with it.
                        onDismiss = { store.questionSessions.hide(card.key, true); drafts.drop(draftKey); null },
                        onFocusChange = { store.questionSessions.focus(card.key, it) },
                        statusContent = { QuestionWaitHeader(card, onHold = { store.questionSessions.hold(card.key) }) },
                    )
                }
            }
        }
    }
}

/** A parked question: what it is about and whether it is still live, as one tappable chip. */
@Composable
private fun ParkedQuestionChip(card: QuestionCardState, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val live = card.state != "settled" && !card.queued
    val label = stringResource(if (live) R.string.ux_b_view_questions else R.string.ux_b_view_answers)
    Surface(
        onClick = {
            haptics.selection()
            onClick()
        },
        shape = DsShapes.block,
        color = colors.bgLayer1,
        border = BorderStroke(1.dp, colors.borderL2),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            if (live) {
                StateDot(StateDotState.Warning, size = 8.dp)
            } else {
                StateDot(StateDotState.Done, size = 8.dp)
            }
            Column(Modifier.widthIn(max = 240.dp)) {
                Text(
                    label,
                    style = DsType.caption11,
                    color = if (live) colors.warnLabel else colors.labelTertiary,
                    maxLines = 1,
                )
                Text(
                    card.questions.firstOrNull()?.question.orEmpty(),
                    style = DsType.small13,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The counted wait: status word, the remaining time, and the one-verb hold. */
@Composable
private fun QuestionWaitHeader(card: QuestionCardState, onHold: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val status = questionWaitStatus(card)
    val seconds = countdownSeconds(card.remainingMs ?: 0)
    val locale = LocalConfiguration.current.locales[0]
    val label = when (status) {
        QuestionWaitStatus.Countdown -> pluralStringResource(R.plurals.ux_b_countdown_seconds, seconds, countdownNumber(seconds, locale))
        QuestionWaitStatus.Paused -> stringResource(R.string.ux_b_paused)
        QuestionWaitStatus.Held -> stringResource(R.string.ux_b_held)
        QuestionWaitStatus.Continued -> stringResource(R.string.ux_b_continued)
        QuestionWaitStatus.Queued -> stringResource(R.string.harness_reply_queued)
        QuestionWaitStatus.Settled -> stringResource(R.string.harness_answered)
        QuestionWaitStatus.Waiting -> stringResource(if (card.ready) R.string.ux_b_held else R.string.common_loading)
    }
    val announcement = if (status == QuestionWaitStatus.Countdown) stringResource(R.string.ux_b_countdown_description) else label
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        // Countdown ticks stay visual. Only a meaningful status change alters this live region.
        StateDot(
            when (status) {
                QuestionWaitStatus.Countdown -> StateDotState.Running
                QuestionWaitStatus.Paused, QuestionWaitStatus.Held -> StateDotState.Warning
                QuestionWaitStatus.Settled -> StateDotState.Done
                else -> StateDotState.Idle
            },
            size = 8.dp,
        )
        Text(
            label,
            style = DsType.caption11Strong,
            color = if (status == QuestionWaitStatus.Countdown) colors.accent else colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clearAndSetSemantics {
                contentDescription = announcement
                liveRegion = LiveRegionMode.Polite
            },
        )
        if (card.remainingMs != null && !card.held && card.state != "continued") {
            DsButton(
                stringResource(R.string.harness_take_time),
                onClick = {
                    haptics.tap()
                    onHold()
                },
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
    }
}

/** The answered card: what was asked, what was answered, and the way back into the answers. */
@Composable
private fun QuestionReplyCard(card: QuestionCardState, onHide: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var expanded by remember(card.key) { mutableStateOf(false) }
    val state = stringResource(if (card.queued && card.state != "settled") R.string.harness_reply_queued else R.string.harness_answered)
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        Row(
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DsPill(state)
                Text(
                    card.questions.firstOrNull()?.question.orEmpty(),
                    style = DsType.small13,
                    color = colors.labelPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DsIconButton(
                Icons.Filled.Close,
                stringResource(R.string.questions_dismiss),
                {
                    haptics.tap()
                    onHide()
                },
            )
        }
        val firstAnswer = card.answers.orEmpty().firstOrNull { it.id == card.questions.firstOrNull()?.id }
        val summary = firstAnswer?.let { it.selected + listOfNotNull(it.custom?.takeIf(String::isNotBlank)) }.orEmpty()
        Text(
            if (summary.isEmpty()) stringResource(R.string.ux_b_skipped) else summary.joinToString(" · "),
            style = DsType.small13,
            color = colors.labelSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        DsButton(
            stringResource(R.string.ux_b_view_answers),
            onClick = {
                haptics.tap()
                expanded = true
            },
            variant = DsButtonVariant.Ghost,
        )
    }
    if (expanded) {
        DsBottomSheet(
            title = stringResource(R.string.ux_b_view_answers),
            subtitle = state,
            onDismiss = { expanded = false },
            trailing = {
                DsIconButton(
                    Icons.Filled.Close,
                    stringResource(R.string.common_close),
                    { expanded = false },
                )
            },
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                card.questions.forEach { question ->
                    val answer = card.answers.orEmpty().firstOrNull { it.id == question.id }
                    val values = answer?.let { it.selected + listOfNotNull(it.custom?.takeIf(String::isNotBlank)) }.orEmpty()
                    DsCard {
                        question.header?.let {
                            Text(it.uppercase(), style = DsType.label12, color = colors.labelTertiary)
                        }
                        Text(question.question, style = DsType.std14Strong, color = colors.labelPrimary)
                        question.detail?.takeIf(String::isNotBlank)?.let { MarkdownText(it) }
                        Text(
                            stringResource(R.string.ux_b_answer).uppercase(),
                            style = DsType.label12,
                            color = colors.labelTertiary,
                        )
                        Text(
                            if (values.isEmpty()) stringResource(R.string.ux_b_skipped) else values.joinToString(" · "),
                            style = DsType.std14,
                            color = colors.labelSecondary,
                        )
                    }
                }
            }
        }
    }
}
