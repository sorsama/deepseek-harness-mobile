package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswer
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionAnswerItem
import com.labteto.dshmobile.core.wire.dto.AskUserQuestionOption
import com.labteto.dshmobile.data.QuestionOutcome
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.common_reconnecting
import com.labteto.dshmobile.shared.resources.common_retry
import com.labteto.dshmobile.shared.resources.questions_answer_refused
import com.labteto.dshmobile.shared.resources.questions_answer_unsent
import com.labteto.dshmobile.ui.LocalSessionStore
import com.labteto.dshmobile.ui.components.AnimatedPresence
import com.labteto.dshmobile.ui.components.ApprovalPanel
import com.labteto.dshmobile.ui.components.ConnectionBanner
import com.labteto.dshmobile.ui.components.PlanReviewPanel
import com.labteto.dshmobile.ui.components.QuestionsPanel
import com.labteto.dshmobile.ui.components.fillTemplate
import com.labteto.dshmobile.ui.components.planReviewOf
import com.labteto.dshmobile.ui.theme.DsAnimations
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * What the harness is waiting on the reader for, above the composer: the open session's approval, its question batch
 * (or the plan review that rides the question channel), and its timed questions ([ModernQuestions]). It is
 * [ChatContent]'s `requests` slot on both platforms (Android's `ChatScreen` had it inline until iOS M7).
 *
 * Server-initiated requests take over the bottom of the screen: they block the turn, so burying them behind a scroll
 * would strand the session. [toast] says a refusal out loud: the host's wait (and the tool call behind it) stays open,
 * so a card that quietly did nothing would leave the session stuck with no explanation.
 */
@Composable
fun ColumnScope.ChatRequests(gutter: Dp, toast: (String) -> Unit) {
    val store = LocalSessionStore.current
    val scope = rememberCoroutineScope()
    val currentSessionId by store.currentSessionId.collectAsStateWithLifecycle()
    val pendingApproval by store.pendingApproval.collectAsStateWithLifecycle()
    val pendingQuestions by store.pendingQuestions.collectAsStateWithLifecycle()

    val answerRefused = stringResource(Res.string.questions_answer_refused)
    val answerUnsent = stringResource(Res.string.questions_answer_unsent)

    /**
     * What to tell the user about a question response, or null when the harness took it.
     *
     * A refusal is worth naming rather than swallowing: the host's wait stays open and the tool
     * call that opened it stays blocked, so a card that quietly did nothing would leave the session
     * stuck with no explanation.
     */
    fun refusalOf(outcome: QuestionOutcome): String? = when (outcome) {
        is QuestionOutcome.Accepted -> null
        // The template's one `%1$s`, filled as `String.format` filled it (the reason is known only later).
        is QuestionOutcome.Refused -> fillTemplate(answerRefused, outcome.reason)
        is QuestionOutcome.Unsent -> answerUnsent
    }

    val approval = pendingApproval?.takeIf { it.sessionId == currentSessionId }
    AnimatedPresence(approval, enter = DsAnimations.cardEnter, exit = DsAnimations.cardExit) { approval ->
        // A refusal is said out loud here rather than swallowed, for the reason [refusalOf]
        // gives: the host's wait — and the tool call behind it — stays open, and a panel
        // that reported nothing would read as two buttons that do nothing.
        fun decide(allow: Boolean) = scope.launch {
            refusalOf(store.respondApproval(approval.sessionId, approval.approvalId, allow))
                ?.let { toast(it) }
        }
        ApprovalPanel(
            modifier = Modifier.padding(horizontal = gutter),
            toolName = approval.toolName,
            reason = approval.reason,
            rawReason = approval.rawReason,
            requestKey = approval.approvalId,
            waitingAfter = approval.waitingAfter,
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
                        toast(it)
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
}

/**
 * The chat's connection strips, under its top bar ([ChatContent]'s `notices` slot): [error], the failure the store last
 * reported, in red with Retry ([onRetry]); and "Reconnecting" in amber while [reconnecting] (the open session's journal
 * has a hole, which its next baseline fills). [describe] words the error for the reader. Shared since iOS M7; on
 * Android the built-in harness's own notice stands in for both while it shows.
 */
@Composable
fun ChatConnectionBanners(
    error: String?,
    reconnecting: Boolean,
    onRetry: () -> Unit,
    describe: (String) -> String = { it },
) {
    AnimatedPresence(error) {
        ConnectionBanner(
            message = describe(it),
            actionLabel = stringResource(Res.string.common_retry),
            onAction = onRetry,
        )
    }
    AnimatedPresence(Unit.takeIf { reconnecting }) {
        ConnectionBanner(stringResource(Res.string.common_reconnecting), warning = true)
    }
}
