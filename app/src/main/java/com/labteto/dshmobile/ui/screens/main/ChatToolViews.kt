package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.permission.PermissionDenial
import com.labteto.dshmobile.core.session.SubagentNode
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.rememberCopier
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** A control tool's row title, in the reader's language. */
@Composable
internal fun toolTitleText(title: ToolTitle): String = stringResource(
    when (title) {
        ToolTitle.TodoWrite -> R.string.chat_tool_title_todo
        ToolTitle.AskUser -> R.string.chat_tool_title_ask
        ToolTitle.Subagent -> R.string.chat_tool_title_subagent
        ToolTitle.Skill -> R.string.chat_tool_title_skill
        ToolTitle.ReadImage -> R.string.chat_tool_title_read_image
        ToolTitle.JobList -> R.string.jobs_title
        ToolTitle.JobOutput -> R.string.chat_tool_title_job_output
        ToolTitle.JobKill -> R.string.chat_tool_title_job_kill
        ToolTitle.TerminalOpen -> R.string.chat_tool_title_terminal_open
        ToolTitle.TerminalRead -> R.string.chat_tool_title_terminal_read
        ToolTitle.TerminalList -> R.string.chat_tool_title_terminal_list
        ToolTitle.TerminalSignal -> R.string.chat_tool_title_terminal_signal
        ToolTitle.TerminalClose -> R.string.chat_tool_title_terminal_close
        ToolTitle.TerminalSend -> R.string.chat_tool_title_terminal_send
        ToolTitle.Lsp -> R.string.chat_tool_title_lsp
        ToolTitle.GoalCreate -> R.string.chat_tool_title_goal_create
        ToolTitle.GoalView -> R.string.chat_tool_title_goal_view
        ToolTitle.GoalUpdate -> R.string.chat_tool_title_goal_update
        ToolTitle.ScheduleCreate -> R.string.chat_tool_title_schedule_create
        ToolTitle.ScheduleList -> R.string.chat_tool_title_schedule_list
        ToolTitle.ScheduleUpdate -> R.string.chat_tool_title_schedule_update
        ToolTitle.ScheduleDelete -> R.string.chat_tool_title_schedule_delete
        ToolTitle.Present -> R.string.chat_tool_title_present
    },
)

/**
 * The line under a call that did not do what it set out to: why, in plain words. The tool's own
 * failure text is kept to its first line here; all of it is in the output and the hold menu.
 */
@Composable
internal fun ToolFailureLine(failure: ToolFailure) {
    val colors = DsTheme.colors
    Column(Modifier.padding(start = 26.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (failure) {
            ToolFailure.Interrupted -> Text(stringResource(R.string.chat_tool_stopped), style = DsType.caption11, color = colors.warnLabel)
            is ToolFailure.Denied -> {
                Text(stringResource(permissionDenialText(failure.denial)), style = DsType.caption11, color = colors.error)
                if (failure.denial == PermissionDenial.AutoReviewRejected) {
                    // Auto review's reason is the harness's own text.
                    failure.reason?.let {
                        LabeledTechnicalText(stringResource(R.string.chat_tool_reason_label), it, DsType.caption11, colors.labelTertiary)
                    }
                }
            }
            // The tool's own words are English text from the tool (a path, a command, a quoted name).
            // The app's "Failed:" follows the layout; the tool's text is its own left-to-right
            // paragraph after it, so a Windows path that wraps still reads in order in Arabic.
            is ToolFailure.Failed -> {
                val message = failure.message
                if (message != null) {
                    LabeledTechnicalText(stringResource(R.string.chat_tool_failed_label), message, DsType.caption11, colors.error)
                } else {
                    Text(stringResource(R.string.chat_tool_failed), style = DsType.caption11, color = colors.error)
                }
            }
        }
    }
}

/** Why a turn failed, in plain words; the provider's own message and the code stay under Details. */
@Composable
internal fun TurnFailureRow(code: String?, message: String?) {
    val colors = DsTheme.colors
    var open by remember(code, message) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(StateDotState.Error, size = 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.chat_error_turn) + " · " + stringResource(turnFailureText(turnFailureOf(code))),
                style = DsType.small13,
                color = colors.error,
            )
        }
        val providerMessage = message?.trim()?.takeIf { it.isNotEmpty() }
        if (providerMessage != null || code != null) {
            DisclosureRow(
                title = stringResource(R.string.ux_a_details),
                icon = FeatherIcons.Info,
                expanded = open,
                onToggle = { open = !open },
            ) {
                Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // The provider's message and code are English text from outside the app: one
                    // left-to-right paragraph each, never mixed into a translated sentence.
                    providerMessage?.let {
                        Text(it, style = DsType.caption11.copy(textDirection = TextDirection.Ltr), color = colors.labelTertiary)
                    }
                    code?.let {
                        LabeledTechnicalText(stringResource(R.string.chat_turn_error_code_label), it, DsType.caption11, colors.labelTertiary)
                    }
                }
            }
        }
    }
}

internal fun turnFailureText(failure: TurnFailure): Int = when (failure) {
    TurnFailure.RateLimit -> R.string.chat_turn_failed_rate_limit
    TurnFailure.Quota -> R.string.chat_turn_failed_quota
    TurnFailure.AccountQuota -> R.string.chat_turn_failed_account_quota
    TurnFailure.ContextFull -> R.string.chat_turn_failed_context
    TurnFailure.EmptyReply -> R.string.chat_turn_failed_empty
    TurnFailure.BadKey -> R.string.chat_turn_failed_bad_key
    TurnFailure.MissingKey -> R.string.chat_turn_failed_missing_key
    TurnFailure.Network -> R.string.chat_turn_failed_network
    TurnFailure.Timeout -> R.string.chat_turn_failed_timeout
    TurnFailure.Server -> R.string.chat_turn_failed_server
    TurnFailure.ProcessExited -> R.string.chat_turn_failed_process
    TurnFailure.SignedOut -> R.string.chat_turn_failed_signed_out
    TurnFailure.Unknown -> R.string.chat_turn_failed_unknown
}

/** A call's raw protocol data ([toolRawJson]), opened from its hold menu. */
@Composable
internal fun RawJsonDialog(json: String, onDismiss: () -> Unit) {
    val copy = rememberCopier()
    DsDialog(
        title = stringResource(R.string.chat_tool_raw_title),
        onDismiss = onDismiss,
        body = { JsonCode(technicalDisplay(json)) },
        actions = {
            DsButton(text = stringResource(R.string.common_close), onClick = onDismiss, variant = DsButtonVariant.Ghost)
            Spacer(Modifier.width(8.dp))
            DsButton(
                text = stringResource(R.string.tool_output_copy_raw),
                onClick = { copy(json); onDismiss() },
                variant = DsButtonVariant.Info,
            )
        },
    )
}

/**
 * The `subagent/descriptor` record at the top of a subagent's own conversation: which child this
 * is and the model it runs on. It is that child's record, read in its own transcript, so there is
 * nothing for it to open.
 */
@Composable
internal fun SubagentDescriptorRow(node: SubagentNode) {
    val facts = remember(node.data) { subagentDescriptorFacts(node.data) }
    val model = facts.model?.let { stringResource(R.string.chat_tool_subagent_model, technicalDisplay(it)) }
    DisclosureRow(
        title = stringResource(R.string.chat_tool_title_subagent),
        summary = listOfNotNull(facts.label, model).joinToString(" · ").ifEmpty { stringResource(R.string.chat_tool_subagent_conversation) },
        icon = FeatherIcons.GitBranch,
    )
}
