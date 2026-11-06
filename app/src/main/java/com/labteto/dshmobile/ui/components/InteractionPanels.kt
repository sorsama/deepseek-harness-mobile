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
import androidx.compose.ui.Alignment
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

/** Sandbox escalation / approval request panel. */
@Composable
fun ApprovalPanel(
    toolName: String,
    reason: String?,
    onAllow: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    // The turn is blocked on this card, so its arrival is felt as well as seen.
    androidx.compose.runtime.LaunchedEffect(Unit) { haptics.attention() }
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
                        stringResource(R.string.approval_reason, reason ?: toolName),
                        style = DsType.std14,
                        color = colors.labelSecondary,
                    )
                }
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
