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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.subagent_command_denied
import com.labteto.dshmobile.shared.resources.command_denied_rejected
import com.labteto.dshmobile.shared.resources.command_denied_cancelled
import com.labteto.dshmobile.shared.resources.command_denied_nobody
import com.labteto.dshmobile.shared.resources.command_denied_too_large
import com.labteto.dshmobile.shared.resources.command_denied_other
import com.labteto.dshmobile.shared.resources.chat_tool_auto_review_denied
import com.labteto.dshmobile.shared.resources.chat_error_turn
import com.labteto.dshmobile.shared.resources.chat_tool_failed
import com.labteto.dshmobile.shared.resources.chat_tool_failed_label
import com.labteto.dshmobile.shared.resources.chat_tool_raw_title
import com.labteto.dshmobile.shared.resources.chat_tool_reason_label
import com.labteto.dshmobile.shared.resources.chat_tool_stopped
import com.labteto.dshmobile.shared.resources.chat_tool_subagent_conversation
import com.labteto.dshmobile.shared.resources.chat_tool_subagent_model
import com.labteto.dshmobile.shared.resources.chat_tool_title_ask
import com.labteto.dshmobile.shared.resources.chat_tool_title_goal_create
import com.labteto.dshmobile.shared.resources.chat_tool_title_goal_update
import com.labteto.dshmobile.shared.resources.chat_tool_title_goal_view
import com.labteto.dshmobile.shared.resources.chat_tool_title_job_kill
import com.labteto.dshmobile.shared.resources.chat_tool_title_job_output
import com.labteto.dshmobile.shared.resources.chat_tool_title_lsp
import com.labteto.dshmobile.shared.resources.chat_tool_title_present
import com.labteto.dshmobile.shared.resources.chat_tool_title_read_image
import com.labteto.dshmobile.shared.resources.chat_tool_title_schedule_create
import com.labteto.dshmobile.shared.resources.chat_tool_title_schedule_delete
import com.labteto.dshmobile.shared.resources.chat_tool_title_schedule_list
import com.labteto.dshmobile.shared.resources.chat_tool_title_schedule_update
import com.labteto.dshmobile.shared.resources.chat_tool_title_skill
import com.labteto.dshmobile.shared.resources.chat_tool_title_subagent
import com.labteto.dshmobile.shared.resources.chat_tool_title_terminal_close
import com.labteto.dshmobile.shared.resources.chat_tool_title_terminal_list
import com.labteto.dshmobile.shared.resources.chat_tool_title_terminal_open
import com.labteto.dshmobile.shared.resources.chat_tool_title_terminal_read
import com.labteto.dshmobile.shared.resources.chat_tool_title_terminal_send
import com.labteto.dshmobile.shared.resources.chat_tool_title_terminal_signal
import com.labteto.dshmobile.shared.resources.chat_tool_title_todo
import com.labteto.dshmobile.shared.resources.chat_turn_error_code_label
import com.labteto.dshmobile.shared.resources.chat_turn_failed_account_quota
import com.labteto.dshmobile.shared.resources.chat_turn_failed_bad_key
import com.labteto.dshmobile.shared.resources.chat_turn_failed_context
import com.labteto.dshmobile.shared.resources.chat_turn_failed_empty
import com.labteto.dshmobile.shared.resources.chat_turn_failed_missing_key
import com.labteto.dshmobile.shared.resources.chat_turn_failed_network
import com.labteto.dshmobile.shared.resources.chat_turn_failed_process
import com.labteto.dshmobile.shared.resources.chat_turn_failed_quota
import com.labteto.dshmobile.shared.resources.chat_turn_failed_rate_limit
import com.labteto.dshmobile.shared.resources.chat_turn_failed_server
import com.labteto.dshmobile.shared.resources.chat_turn_failed_signed_out
import com.labteto.dshmobile.shared.resources.chat_turn_failed_timeout
import com.labteto.dshmobile.shared.resources.chat_turn_failed_unknown
import com.labteto.dshmobile.shared.resources.common_close
import com.labteto.dshmobile.shared.resources.jobs_title
import com.labteto.dshmobile.shared.resources.tool_output_copy_raw
import com.labteto.dshmobile.shared.resources.ux_a_details
import com.labteto.dshmobile.core.permission.PermissionDenial
import com.labteto.dshmobile.core.session.SubagentNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
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
fun toolTitleText(title: ToolTitle): String = stringResource(
    when (title) {
        ToolTitle.TodoWrite -> Res.string.chat_tool_title_todo
        ToolTitle.AskUser -> Res.string.chat_tool_title_ask
        ToolTitle.Subagent -> Res.string.chat_tool_title_subagent
        ToolTitle.Skill -> Res.string.chat_tool_title_skill
        ToolTitle.ReadImage -> Res.string.chat_tool_title_read_image
        ToolTitle.JobList -> Res.string.jobs_title
        ToolTitle.JobOutput -> Res.string.chat_tool_title_job_output
        ToolTitle.JobKill -> Res.string.chat_tool_title_job_kill
        ToolTitle.TerminalOpen -> Res.string.chat_tool_title_terminal_open
        ToolTitle.TerminalRead -> Res.string.chat_tool_title_terminal_read
        ToolTitle.TerminalList -> Res.string.chat_tool_title_terminal_list
        ToolTitle.TerminalSignal -> Res.string.chat_tool_title_terminal_signal
        ToolTitle.TerminalClose -> Res.string.chat_tool_title_terminal_close
        ToolTitle.TerminalSend -> Res.string.chat_tool_title_terminal_send
        ToolTitle.Lsp -> Res.string.chat_tool_title_lsp
        ToolTitle.GoalCreate -> Res.string.chat_tool_title_goal_create
        ToolTitle.GoalView -> Res.string.chat_tool_title_goal_view
        ToolTitle.GoalUpdate -> Res.string.chat_tool_title_goal_update
        ToolTitle.ScheduleCreate -> Res.string.chat_tool_title_schedule_create
        ToolTitle.ScheduleList -> Res.string.chat_tool_title_schedule_list
        ToolTitle.ScheduleUpdate -> Res.string.chat_tool_title_schedule_update
        ToolTitle.ScheduleDelete -> Res.string.chat_tool_title_schedule_delete
        ToolTitle.Present -> Res.string.chat_tool_title_present
    },
)

/**
 * The line under a call that did not do what it set out to: why, in plain words. The tool's own
 * failure text is kept to its first line here; all of it is in the output and the hold menu.
 */
@Composable
fun ToolFailureLine(failure: ToolFailure) {
    val colors = DsTheme.colors
    Column(Modifier.padding(start = 26.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (failure) {
            ToolFailure.Interrupted -> Text(stringResource(Res.string.chat_tool_stopped), style = DsType.caption11, color = colors.warnLabel)
            is ToolFailure.Denied -> {
                Text(stringResource(permissionDenialText(failure.denial)), style = DsType.caption11, color = colors.error)
                if (failure.denial == PermissionDenial.AutoReviewRejected) {
                    // Auto review's reason is the harness's own text.
                    failure.reason?.let {
                        LabeledTechnicalText(stringResource(Res.string.chat_tool_reason_label), it, DsType.caption11, colors.labelTertiary)
                    }
                }
            }
            // The tool's own words are English text from the tool (a path, a command, a quoted name).
            // The app's "Failed:" follows the layout; the tool's text is its own left-to-right
            // paragraph after it, so a Windows path that wraps still reads in order in Arabic.
            is ToolFailure.Failed -> {
                val message = failure.message
                if (message != null) {
                    LabeledTechnicalText(stringResource(Res.string.chat_tool_failed_label), message, DsType.caption11, colors.error)
                } else {
                    Text(stringResource(Res.string.chat_tool_failed), style = DsType.caption11, color = colors.error)
                }
            }
        }
    }
}

/** Why a turn failed, in plain words; the provider's own message and the code stay under Details. */
@Composable
fun TurnFailureRow(code: String?, message: String?) {
    val colors = DsTheme.colors
    var open by remember(code, message) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(StateDotState.Error, size = 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(Res.string.chat_error_turn) + " · " + stringResource(turnFailureText(turnFailureOf(code))),
                style = DsType.small13,
                color = colors.error,
            )
        }
        val providerMessage = message?.trim()?.takeIf { it.isNotEmpty() }
        if (providerMessage != null || code != null) {
            DisclosureRow(
                title = stringResource(Res.string.ux_a_details),
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
                        LabeledTechnicalText(stringResource(Res.string.chat_turn_error_code_label), it, DsType.caption11, colors.labelTertiary)
                    }
                }
            }
        }
    }
}

internal fun turnFailureText(failure: TurnFailure): StringResource = when (failure) {
    TurnFailure.RateLimit -> Res.string.chat_turn_failed_rate_limit
    TurnFailure.Quota -> Res.string.chat_turn_failed_quota
    TurnFailure.AccountQuota -> Res.string.chat_turn_failed_account_quota
    TurnFailure.ContextFull -> Res.string.chat_turn_failed_context
    TurnFailure.EmptyReply -> Res.string.chat_turn_failed_empty
    TurnFailure.BadKey -> Res.string.chat_turn_failed_bad_key
    TurnFailure.MissingKey -> Res.string.chat_turn_failed_missing_key
    TurnFailure.Network -> Res.string.chat_turn_failed_network
    TurnFailure.Timeout -> Res.string.chat_turn_failed_timeout
    TurnFailure.Server -> Res.string.chat_turn_failed_server
    TurnFailure.ProcessExited -> Res.string.chat_turn_failed_process
    TurnFailure.SignedOut -> Res.string.chat_turn_failed_signed_out
    TurnFailure.Unknown -> Res.string.chat_turn_failed_unknown
}

/** What the chat says for a call the built-in runtime's command check did not run. */
internal fun permissionDenialText(denial: com.labteto.dshmobile.core.permission.PermissionDenial): StringResource = when (denial) {
    com.labteto.dshmobile.core.permission.PermissionDenial.SubagentCannotAsk -> Res.string.subagent_command_denied
    com.labteto.dshmobile.core.permission.PermissionDenial.Rejected -> Res.string.command_denied_rejected
    com.labteto.dshmobile.core.permission.PermissionDenial.Cancelled -> Res.string.command_denied_cancelled
    com.labteto.dshmobile.core.permission.PermissionDenial.NobodyToAsk -> Res.string.command_denied_nobody
    com.labteto.dshmobile.core.permission.PermissionDenial.TextTooLarge -> Res.string.command_denied_too_large
    com.labteto.dshmobile.core.permission.PermissionDenial.Other -> Res.string.command_denied_other
    com.labteto.dshmobile.core.permission.PermissionDenial.AutoReviewRejected -> Res.string.chat_tool_auto_review_denied
}

/**
 * Everything the harness recorded about one call, as pretty JSON: its name and arguments, and once
 * it settled, the result body, the durable `meta` the cards are built from, and the error facts.
 * This is where the raw metadata that used to sit under every card is still one hold away.
 */
fun toolRawJson(call: ToolCallNode, result: ToolResultNode?): String {
    val json = kotlinx.serialization.json.buildJsonObject {
        put("name", kotlinx.serialization.json.JsonPrimitive(call.name))
        put("callId", kotlinx.serialization.json.JsonPrimitive(call.callId))
        val args = runCatching { com.labteto.dshmobile.core.wire.WireJson.parseToJsonElement(call.arguments) }.getOrNull()
        put("arguments", args ?: kotlinx.serialization.json.JsonPrimitive(call.arguments))
        if (result != null) {
            put("isError", kotlinx.serialization.json.JsonPrimitive(result.isError))
            result.content?.let { put("content", it) }
            result.meta?.let { put("meta", it) }
            if (result.errorCode != null || result.errorName != null || result.errorReason != null) {
                put("error", kotlinx.serialization.json.buildJsonObject {
                    result.errorName?.let { put("name", kotlinx.serialization.json.JsonPrimitive(it)) }
                    result.errorCode?.let { put("code", kotlinx.serialization.json.JsonPrimitive(it)) }
                    result.errorReason?.let { put("reason", kotlinx.serialization.json.JsonPrimitive(it)) }
                })
            }
        }
    }
    return RAW_JSON.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), json)
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val RAW_JSON = kotlinx.serialization.json.Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** A call's raw protocol data ([toolRawJson]), opened from its hold menu. */
@Composable
fun RawJsonDialog(json: String, onDismiss: () -> Unit) {
    val copy = rememberCopier()
    DsDialog(
        title = stringResource(Res.string.chat_tool_raw_title),
        onDismiss = onDismiss,
        body = { JsonCode(technicalDisplay(json)) },
        actions = {
            DsButton(text = stringResource(Res.string.common_close), onClick = onDismiss, variant = DsButtonVariant.Ghost)
            Spacer(Modifier.width(8.dp))
            DsButton(
                text = stringResource(Res.string.tool_output_copy_raw),
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
fun SubagentDescriptorRow(node: SubagentNode) {
    val facts = remember(node.data) { subagentDescriptorFacts(node.data) }
    val model = facts.model?.let { stringResource(Res.string.chat_tool_subagent_model, technicalDisplay(it)) }
    DisclosureRow(
        title = stringResource(Res.string.chat_tool_title_subagent),
        summary = listOfNotNull(facts.label, model).joinToString(" · ").ifEmpty { stringResource(Res.string.chat_tool_subagent_conversation) },
        icon = FeatherIcons.GitBranch,
    )
}
