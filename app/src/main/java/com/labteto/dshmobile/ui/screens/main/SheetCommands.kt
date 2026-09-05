package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.CommandDescriptor
import com.labteto.dshmobile.core.wire.dto.SkillEntry
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The `+` sheet: everything you can add to a message that is not the message.
 *
 * Commands come from the harness's own per-session catalog rather than a hardcoded list, because
 * which commands exist depends on the deployment and on the session's agent preset. When the
 * harness exposes no catalog the section says so instead of inventing entries — a menu that offers
 * a command the host will reject is worse than a menu that admits it does not know.
 */
@Composable
internal fun CommandSheet(
    commands: List<CommandDescriptor>,
    commandsAvailable: Boolean,
    skills: List<SkillEntry>,
    mode: String,
    running: Boolean,
    canAttach: Boolean,
    onModeChange: (String) -> Unit,
    onAttach: () -> Unit,
    onAttachFile: () -> Unit,
    onRunCommand: (String) -> Unit,
    onPrefillDraft: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    var query by remember { mutableStateOf("") }
    val filteredCommands = remember(commands, query) { commands.filterByQuery(query) { it.name to it.description } }
    val filteredSkills = remember(skills, query) { skills.filterByQuery(query) { it.name to it.description } }
    val searchable = commands.size + skills.size > 12

    DsBottomSheet(title = stringResource(R.string.chat_composer_commands), onDismiss = onDismiss) {
        // Attach ------------------------------------------------------------
        SheetRow(
            leading = {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    tint = colors.labelSecondary,
                    modifier = Modifier.size(20.dp),
                )
            },
            title = stringResource(R.string.chat_composer_attach),
            subtitle = if (canAttach) null else stringResource(R.string.err_attachment_failed),
            enabled = canAttach,
            onClick = {
                onDismiss()
                onAttach()
            },
        )
        // Harness 0.1.3 takes any file, not only pictures. The bytes go up as soon as one is
        // picked and the message cites the receipt, so the row stays enabled exactly when the
        // image one is: both need an open session to upload against.
        SheetRow(
            leading = {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = colors.labelSecondary,
                    modifier = Modifier.size(20.dp),
                )
            },
            title = stringResource(R.string.chat_composer_attach_file),
            subtitle = null,
            enabled = canAttach,
            onClick = {
                onDismiss()
                onAttachFile()
            },
        )

        // Send mode ---------------------------------------------------------
        SectionHeader(stringResource(R.string.chat_composer_mode))
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsPill(
                text = stringResource(R.string.chat_composer_queue),
                selected = mode != "steer",
                onClick = { onModeChange("queue") },
            )
            // Steering splices into a turn that is already running; with an idle agent there is
            // nothing to steer, so the option stays visible but inert rather than silently failing.
            DsPill(
                text = stringResource(R.string.chat_composer_steer),
                selected = mode == "steer",
                onClick = if (running) ({ onModeChange("steer") }) else null,
            )
        }
        Text(
            stringResource(
                when {
                    !running && mode == "steer" -> R.string.chat_composer_steer_idle
                    mode == "steer" -> R.string.chat_composer_mode_steer_hint
                    else -> R.string.chat_composer_mode_queue_hint
                },
            ),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )

        if (searchable) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.common_search), style = DsType.std14) },
                colors = dialogTextFieldColors(),
            )
        }

        // Commands ----------------------------------------------------------
        SectionHeader(stringResource(R.string.chat_composer_commands))
        when {
            !commandsAvailable -> Text(
                stringResource(R.string.chat_commands_unavailable),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            filteredCommands.isEmpty() -> Text(
                stringResource(R.string.chat_commands_empty),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            else -> LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(filteredCommands, key = { it.name }) { command ->
                    SheetRow(
                        title = command.line,
                        subtitle = command.description.ifBlank { null },
                        trailing = command.input?.hint,
                        onClick = {
                            onDismiss()
                            // A bare command runs immediately; one that takes an argument prefills
                            // the composer so the argument can be typed where the hint is visible.
                            if (command.input == null) {
                                onRunCommand(command.line)
                            } else {
                                onPrefillDraft(command.draftPrefix)
                            }
                        },
                    )
                }
            }
        }

        // Skills ------------------------------------------------------------
        if (filteredSkills.isNotEmpty()) {
            SectionHeader(stringResource(R.string.skills_title))
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(filteredSkills, key = { it.name }) { skill ->
                    SheetRow(
                        title = "/${skill.name}",
                        subtitle = skill.description.ifBlank { null },
                        trailing = if (!skill.modelInvocable) {
                            stringResource(R.string.skills_user_only)
                        } else {
                            null
                        },
                        onClick = {
                            onDismiss()
                            onPrefillDraft("/${skill.name} ")
                        },
                    )
                }
            }
        }
    }
}

/** One tappable row of a sheet: title, optional subtitle, optional trailing hint. */
@Composable
internal fun SheetRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(DsSpacing.medium))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = DsType.std14Strong,
                color = if (enabled) colors.labelPrimary else colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(DsSpacing.small))
            Text(
                trailing,
                style = DsType.caption11.copy(fontFamily = DsType.codeFont),
                color = colors.labelCaption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Some hints are long enough to crowd out the description they sit beside
                // (`/goal` advertises five alternatives), so the hint yields, not the name.
                modifier = Modifier.widthIn(max = 120.dp),
            )
        }
    }
}

/** Case-insensitive contains over a row's name and description. */
private inline fun <T> List<T>.filterByQuery(query: String, selector: (T) -> Pair<String, String>): List<T> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return this
    return filter { item ->
        val (name, description) = selector(item)
        name.lowercase().contains(needle) || description.lowercase().contains(needle)
    }
}
