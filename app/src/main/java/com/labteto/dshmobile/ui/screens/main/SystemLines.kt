package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.BidiFormatter
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.CommandNode
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.serialization.json.JsonObject

/**
 * Things the reader did that are not chat messages, told as quiet system lines.
 *
 * A slash command is the user's own action: the harness logs it as a `command/run` (and a
 * `command/done` when it settles), and the only source it ever stamps on one is `{ kind: "user" }`.
 * Drawn as a terminal row (`>_ /permission`, then a second `>_ /command/done` row) it read like
 * something the agent had done. These lines separate the two: the agent's rows are unchanged, and
 * what the reader did reads as a muted line of its own.
 *
 * Only the wording lives here as data. The text, icon and colours are decided by [SystemLineRow].
 */
internal sealed interface SystemLine {
    /**
     * `/permission <preset>`. [preset] is the wire value; null when the node does not carry it, which
     * reads as the plain "You changed the permission".
     */
    data class PermissionChanged(val preset: String?) : SystemLine

    /** `/permission` that the harness refused (an unknown preset, say). */
    data object PermissionFailed : SystemLine

    /** `/compact`. The summary it produces stays the agent's own row. */
    data object Compacted : SystemLine

    /** `/plan` and `/plan off`, read the way the harness reads them: `off` leaves, anything else enters. */
    data class PlanMode(val on: Boolean) : SystemLine

    /** Any other command the reader ran; [command] is `/name`. */
    data class Ran(val command: String) : SystemLine

    /** [Ran], when the harness reported an error. */
    data class RanFailed(val command: String) : SystemLine
}

private const val COMMAND_RUN = "command/run"
private const val COMMAND_DONE = "command/done"

private val CommandNode.fields: JsonObject? get() = data as? JsonObject

/** The id that pairs a `command/run` with the `command/done` that settles it. */
internal val CommandNode.commandId: String? get() = fields?.get("commandId").asString()

internal val CommandNode.isRun: Boolean get() = kind == COMMAND_RUN
internal val CommandNode.isDone: Boolean get() = kind == COMMAND_DONE

/** The command's name without the slash, or null when the event does not carry one. */
internal val CommandNode.commandName: String? get() = fields?.get("name").asString()?.takeIf { it.isNotBlank() }

/** What the reader typed after the name, as the host recorded it; null when it did not record it. */
private val CommandNode.commandArgs: String? get() = fields?.get("args").asString()

/**
 * Whether the reader issued this command.
 *
 * The host stamps `source: { kind: "user" }` on every run it logs, and an older log may carry no
 * source at all, so both count. A kind this build has never seen is not claimed as "you".
 */
internal val CommandNode.isUserIssued: Boolean
    get() {
        val source = fields?.get("source") as? JsonObject ?: return true
        val kind = source["kind"].asString()
        return kind == null || kind == "user"
    }

/** Whether the harness reported this `command/done` as a failure. */
private val CommandNode.failed: Boolean get() = fields?.get("kind").asString() == "error"

private val CommandNode.doneText: String? get() = fields?.get("text").asString()

/**
 * The line for a command the reader ran, or null when the node should keep its ordinary row — it is
 * not a run, or not the reader's.
 *
 * [nodes] is searched for the `command/done` that settled the run, so the outcome can colour the
 * wording; a run that has not settled yet reads as having gone through.
 */
internal fun systemLineFor(run: CommandNode, nodes: List<ChatNode>): SystemLine? {
    if (!run.isRun || !run.isUserIssued) return null
    val name = run.commandName ?: return null
    val done = run.commandId?.let { id ->
        nodes.firstOrNull { it is CommandNode && it.isDone && it.seq > run.seq && it.commandId == id } as CommandNode?
    }
    val command = "/$name"
    val failed = done?.failed == true
    return when (name) {
        "permission" -> when {
            failed -> SystemLine.PermissionFailed
            else -> permissionLine(run, done)
        }
        "compact" -> if (failed) SystemLine.RanFailed(command) else SystemLine.Compacted
        "plan" -> if (failed) SystemLine.RanFailed(command) else SystemLine.PlanMode(run.commandArgs?.trim() != "off")
        else -> if (failed) SystemLine.RanFailed(command) else SystemLine.Ran(command)
    }
}

/**
 * `/permission <preset>` switches; a bare `/permission` only lists the table, which is not a change.
 *
 * The preset comes from what the host recorded. A host that records no input is read from the
 * settlement (`preset workspace-write`); when neither says, the line falls back to the unnamed form.
 */
private fun permissionLine(run: CommandNode, done: CommandNode?): SystemLine {
    val args = run.commandArgs?.trim()
    return when {
        args == null -> SystemLine.PermissionChanged(done?.doneText?.let(PRESET_SETTLEMENT::matchEntire)?.groupValues?.get(1))
        args.isEmpty() -> SystemLine.Ran("/permission")
        else -> SystemLine.PermissionChanged(args.takeIf { PRESET_ID.matches(it) })
    }
}

private val PRESET_ID = Regex("""[^\s]+""")
private val PRESET_SETTLEMENT = Regex("""preset (\S+)""")

/**
 * The `command/done` rows that [systemLineFor] already tells inside their run's line.
 *
 * A reader's command is one event with two halves, and the second half used to draw a row of its
 * own (`>_ /command/done · preset read-only`). A done whose run is in the list folds into that run.
 * A done with no run to fold into — its page of history starts after the run — says nothing on its
 * own when it succeeded; a failure keeps its ordinary row, because that is the one line the reader
 * might need.
 */
internal fun foldedCommandDones(nodes: List<ChatNode>): Set<Long> {
    val commands = nodes.filterIsInstance<CommandNode>()
    if (commands.isEmpty()) return emptySet()
    val runs = commands.filter { it.isRun }
    val claimed = runs.filter { it.isUserIssued }.mapNotNullTo(mutableSetOf()) { it.commandId }
    val known = runs.mapNotNullTo(mutableSetOf()) { it.commandId }
    return commands.filter { done ->
        done.isDone && (done.commandId in claimed || (done.commandId !in known && !done.failed))
    }.mapTo(mutableSetOf()) { it.seq }
}

/**
 * One system line: a hairline, a small icon where one helps, the sentence, and a hairline.
 *
 * Centred and muted on purpose — it is the reader's own action, not a reply and not the agent's
 * work. It draws no click target and carries the sentence as its description.
 */
@Composable
internal fun SystemLineRow(
    line: SystemLine,
    presetNames: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val shown = when (line) {
        is SystemLine.PermissionChanged -> {
            val preset = line.preset
            if (preset == null) {
                Shown(stringResource(R.string.system_line_permission_changed_plain), Icons.Outlined.Shield, colors.labelTertiary)
            } else {
                val name = presetNames[preset]
                Shown(
                    stringResource(R.string.system_line_permission_changed, permissionPresetLabel(preset, name)),
                    Icons.Outlined.Shield,
                    permissionPresetTint(preset, name, colors),
                )
            }
        }
        SystemLine.PermissionFailed ->
            Shown(stringResource(R.string.system_line_permission_failed), Icons.Outlined.Shield, colors.labelTertiary)
        SystemLine.Compacted -> Shown(stringResource(R.string.system_line_compact))
        is SystemLine.PlanMode ->
            Shown(stringResource(if (line.on) R.string.system_line_plan_on else R.string.system_line_plan_off))
        is SystemLine.Ran -> Shown(stringResource(R.string.system_line_command, line.command.isolated()))
        is SystemLine.RanFailed -> Shown(stringResource(R.string.system_line_command_failed, line.command.isolated()))
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            // One node, one sentence: the hairlines and the icon are decoration.
            .clearAndSetSemantics { contentDescription = shown.text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = colors.borderL1)
        // Not weighted: the sentence is measured first, so a long one takes the room and the
        // hairlines give way, rather than the sentence being squeezed to a third of the width.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            shown.icon?.let { Icon(it, contentDescription = null, tint = shown.tint, modifier = Modifier.size(13.dp)) }
            Text(
                shown.text,
                style = DsType.caption11,
                color = colors.labelTertiary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        HorizontalDivider(modifier = Modifier.weight(1f), color = colors.borderL1)
    }
}

/** What [SystemLineRow] puts on screen for a line: the sentence, and the icon with its colour. */
private class Shown(val text: String, val icon: ImageVector? = null, val tint: Color = Color.Unspecified)

/**
 * A command name inside a translated sentence. `/xyz` is Latin text with a leading neutral
 * character, and in a right-to-left sentence the slash would drift to the wrong end of it.
 */
private fun String.isolated(): String = BidiFormatter.getInstance().unicodeWrap(this)
