package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

// ---------------------------------------------------------------------------
// Interaction takeovers: sandbox-approval and plan review. The ask_user_question
// composer has its own file. These mirror the harness composer-takeover panels
// (amber strip, floating capsule, outline/primary pill actions), restyled to the
// Ink kit: a card floating over the composer with the mascot's attention colour.
// ---------------------------------------------------------------------------

/** The tallest the approval reason grows before it scrolls; the buttons under it never move. */
private val ApprovalReasonMaxHeight = 150.dp

/** A command the agent wants to run gets more room than a sentence before it scrolls. */
private val ApprovalCommandMaxHeight = 220.dp

/**
 * Sandbox escalation / approval request panel.
 *
 * A request from the built-in harness's "Ask before commands" gate names a command, a program or
 * a path ([approvalPromptOf]); the panel asks its own question and shows that text whole, in
 * monospace, selectable, scrolling inside the card. Any other request keeps the harness's wording.
 *
 * @param requestKey identifies the request shown, so the next one in a queue is felt arriving.
 * @param waitingAfter requests of this session queued behind this one.
 */
@Composable
fun ApprovalPanel(
    toolName: String,
    reason: String?,
    onAllow: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    rawReason: String? = reason,
    requestKey: String = "",
    waitingAfter: Int = 0,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    // The turn is blocked on this card, so its arrival is felt as well as seen.
    androidx.compose.runtime.LaunchedEffect(requestKey) { haptics.attention() }
    val prompt = androidx.compose.runtime.remember(toolName, rawReason, reason) {
        com.labteto.dshmobile.core.permission.approvalPromptOf(toolName, rawReason, reason)
    }
    if (prompt.detail != null) {
        CommandApprovalPanel(prompt, requestKey, waitingAfter, onAllow, onReject, modifier)
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.approvalCard,
        color = colors.bgLayer1,
        border = BorderStroke(1.dp, colors.warnBorder),
        shadowElevation = 2.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.warnTertiary)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = colors.warn,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.approval_title),
                    style = DsType.small13Strong,
                    color = colors.warnText,
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                // The reason is the command, and a heredoc or a long commit message runs to dozens
                // of lines. It scrolls inside a capped region and takes only the height the buttons
                // leave over, so Allow and Reject are always laid out and the composer below the
                // card keeps its room. The plan card above does the same with its own text.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = ApprovalReasonMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        stringResource(R.string.approval_reason, prompt.text ?: toolName),
                        style = DsType.std14,
                        color = colors.labelSecondary,
                    )
                }
                if (waitingAfter > 0) WaitingLine(waitingAfter)
                // Full-size buttons: the one decision the turn is waiting on gets the biggest
                // targets on the card, with Reject away from the thumb's rest position.
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    DsButton(
                        text = stringResource(R.string.approval_allow_once),
                        onClick = { haptics.confirm(); onAllow() },
                        variant = DsButtonVariant.Attention,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        DsButton(
                            text = stringResource(R.string.approval_reject),
                            onClick = { haptics.reject(); onReject() },
                            variant = DsButtonVariant.Danger,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The command, program or path of an "Ask before commands" request, and the two answers.
 *
 * The text is never cut without a way to see it: it wraps (so no line runs off the side) and
 * scrolls inside a box capped well below the window, so Allow and Reject always stay above the
 * composer. When it is taller than the box, "Show all" opens it whole in a sheet that has the two
 * answers too. It can be selected, to copy a part of it. A command reads left to right in every
 * language.
 */
@Composable
private fun CommandApprovalPanel(
    prompt: com.labteto.dshmobile.core.permission.ApprovalPrompt,
    requestKey: String,
    waitingAfter: Int,
    onAllow: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var showAll by rememberSaveable(requestKey) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // The card is not height-bound by the screen around it, so the box keeps to a fraction of the
    // window: a short window (landscape, a desktop window, large text) keeps room for the buttons.
    val windowHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val cap = minOf(ApprovalCommandMaxHeight, windowHeight * 0.25f)
    val question = approvalQuestion(prompt)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.approvalCard,
        color = colors.bgLayer1,
        border = BorderStroke(1.dp, colors.warnBorder),
        shadowElevation = 2.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.warnTertiary)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = colors.warn, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    question,
                    style = DsType.small13Strong,
                    color = colors.warnText,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                CommandText(prompt.detail.orEmpty(), Modifier.heightIn(max = cap), scroll)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (prompt.shortened) {
                            Text(stringResource(R.string.approval_shortened), style = DsType.caption11, color = colors.warnLabel)
                        }
                        if (waitingAfter > 0) WaitingLine(waitingAfter)
                    }
                    // Taller than the box: the rest is a scroll away, or all of it in the sheet.
                    if (scroll.maxValue > 0) {
                        DsButton(
                            text = stringResource(R.string.approval_show_all),
                            onClick = {
                                haptics.tap()
                                showAll = true
                            },
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                    }
                }
                ApprovalAnswers(onAllow = onAllow, onReject = onReject)
            }
        }
    }
    if (showAll) {
        DsBottomSheet(title = question, onDismiss = { showAll = false }) {
            val hide = LocalDsSheetHide.current
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                CommandText(prompt.detail.orEmpty(), Modifier.weight(1f, fill = false), rememberScrollState())
                if (prompt.shortened) {
                    Text(stringResource(R.string.approval_shortened), style = DsType.caption11, color = colors.warnLabel)
                }
                ApprovalAnswers(
                    onAllow = { hide { showAll = false; onAllow() } },
                    onReject = { hide { showAll = false; onReject() } },
                    modifier = Modifier.padding(bottom = DsSpacing.small),
                )
            }
        }
    }
}

/** The question a gated request asks, in the app's words. */
@Composable
private fun approvalQuestion(prompt: com.labteto.dshmobile.core.permission.ApprovalPrompt): String = when (prompt.kind) {
    com.labteto.dshmobile.core.permission.ApprovalPrompt.Kind.FileChange -> stringResource(R.string.approval_file_title)
    com.labteto.dshmobile.core.permission.ApprovalPrompt.Kind.Command ->
        if (prompt.toolName == "bash") stringResource(R.string.approval_command_title) else stringResource(R.string.approval_tool_title, prompt.toolName)
    else -> stringResource(R.string.approval_tool_title, prompt.toolName)
}

/** The command in monospace, left to right, wrapping, selectable, scrolling inside [modifier]'s bounds. */
@Composable
private fun CommandText(text: String, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth().then(modifier),
        shape = DsShapes.code,
        color = colors.bgLayer2,
        border = BorderStroke(1.dp, colors.borderL1),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
        ) {
            androidx.compose.foundation.text.selection.SelectionContainer(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                Text(
                    text,
                    style = DsType.mono13.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                    color = colors.labelPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Reject at the start, Allow at the end, both full size: the decision the turn waits on. */
@Composable
private fun ApprovalAnswers(onAllow: () -> Unit, onReject: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberDsHaptics()
    Row(modifier.fillMaxWidth().padding(top = DsSpacing.xsmall), horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        DsButton(
            text = stringResource(R.string.approval_reject),
            onClick = { haptics.reject(); onReject() },
            variant = DsButtonVariant.Danger,
            allowWrap = true,
            modifier = Modifier.weight(1f),
        )
        DsButton(
            text = stringResource(R.string.approval_allow_once),
            onClick = { haptics.confirm(); onAllow() },
            variant = DsButtonVariant.Attention,
            allowWrap = true,
            modifier = Modifier.weight(1f),
        )
    }
}

/** "2 more requests are waiting", under the request shown. */
@Composable
private fun WaitingLine(count: Int) {
    Text(
        androidx.compose.ui.res.pluralStringResource(R.plurals.approval_more_waiting, count, count),
        style = DsType.caption11,
        color = DsTheme.colors.labelTertiary,
    )
}

/**
 * Plan-review takeover: the plan itself, then approve, refuse, or take it to the chat.
 *
 * A plan review rides the ordinary question channel but is a different decision, so it gets the
 * card built for it rather than a generic multiple choice. [planReviewOf] decides which — and
 * declines anything this card could not answer in full, because the card answers one question and
 * the host refuses a batch shorter than the request it resolves.
 */
@Composable
internal fun PlanReviewPanel(
    review: PlanReview,
    busy: Boolean,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onDiscuss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.approvalCard,
        color = colors.bgLayer1,
        border = BorderStroke(1.dp, colors.accentBorder),
        shadowElevation = 2.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.accentTertiary)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MascotMark(size = 28.dp, state = MascotState.Working)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        stringResource(R.string.plan_review_title),
                        style = DsType.small13Strong,
                        color = colors.accent,
                    )
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownText(review.plan)
                }
                DsButton(
                    text = stringResource(R.string.plan_review_approve),
                    onClick = { haptics.confirm(); onApprove() },
                    enabled = !busy,
                    variant = DsButtonVariant.Info,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    // "Chat about it" dismisses the request rather than answering it with the
                    // refusal, which is what the harness's own card does — declining picks a stated
                    // option, whereas wanting to talk first is not one of the choices on offer.
                    DsButton(
                        text = stringResource(R.string.plan_review_discuss),
                        onClick = onDiscuss,
                        enabled = !busy,
                        variant = DsButtonVariant.Outline,
                        modifier = Modifier.weight(1f),
                    )
                    // Only when the asker offered a second option. A refusal it never named is not
                    // one this card can send: the host checks every selected label against the
                    // question's own options and refuses one it does not recognise.
                    if (review.decline != null) {
                        DsButton(
                            text = stringResource(R.string.plan_review_decline),
                            onClick = { haptics.reject(); onDecline() },
                            enabled = !busy,
                            variant = DsButtonVariant.Danger,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
