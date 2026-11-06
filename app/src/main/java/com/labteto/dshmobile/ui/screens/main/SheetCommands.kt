package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The `+` sheet: everything you can add to a message that is not the message.
 *
 * Commands come from the harness's own per-session catalog rather than a hardcoded list. When the
 * harness exposes no catalog the section says so instead of inventing entries — a menu that offers
 * a command the host will reject is worse than a menu that admits it does not know.
 *
 * The sheet is one scroll surface. It used to be a Column of sections with two separately-capped
 * LazyColumns inside it; a full command + skill catalog made the outer Column taller than the
 * screen, so the title sat under the status bar and the last rows were cut off below the nav bar
 * with no way to reach them. Everything now scrolls together, and the sheet content keeps the
 * system-bar insets the sheet hands it.
 */
@Composable
internal fun CommandSheet(
    commands: List<CommandDescriptor>,
    commandsAvailable: Boolean,
    skills: List<SkillEntry>,
    mode: String,
    running: Boolean,
    canAttach: Boolean,
    /**
     * False in a subagent child's chat: the host refuses every file upload for a subagent, while
     * image parts ride `subagents/prompt` normally. The row still says why instead of vanishing.
     */
    fileAttachEnabled: Boolean = true,
    onModeChange: (String) -> Unit,
    onAttach: () -> Unit,
    onAttachFile: () -> Unit,
    onRunCommand: (String) -> Unit,
    onPrefillDraft: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var query by remember { mutableStateOf("") }
    val filteredCommands = remember(commands, query) { commands.filterByQuery(query) { it.name to it.description } }
    val filteredSkills = remember(skills, query) { skills.filterByQuery(query) { it.name to it.description } }
    val searchable = commands.size + skills.size > 12

    DsBottomSheet(
        title = stringResource(R.string.chat_composer_commands),
        onDismiss = {
            haptics.tap()
            onDismiss()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            // Attach ------------------------------------------------------------
            SheetRow(
                leading = { GlyphTile(Icons.Filled.Image) },
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
                leading = { GlyphTile(Icons.Filled.AttachFile) },
                title = stringResource(R.string.chat_composer_attach_file),
                subtitle = if (fileAttachEnabled) null else stringResource(R.string.err_file_subagent_unsupported),
                enabled = canAttach && fileAttachEnabled,
                onClick = {
                    onDismiss()
                    onAttachFile()
                },
            )

            // Send mode ---------------------------------------------------------
            SheetSectionLabel(stringResource(R.string.chat_composer_mode))
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                DsPill(
                    text = stringResource(R.string.chat_composer_queue),
                    selected = mode != "steer",
                    onClick = {
                        haptics.selection()
                        onModeChange("queue")
                    },
                )
                // Steering splices into a turn that is already running; with an idle agent there is
                // nothing to steer, so the option stays visible but inert rather than silently failing.
                DsPill(
                    text = stringResource(R.string.chat_composer_steer),
                    selected = mode == "steer",
                    onClick = if (running) {
                        {
                            haptics.selection()
                            onModeChange("steer")
                        }
                    } else {
                        null
                    },
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
            // The per-message version of this choice is a hold on send, which nothing else points at.
            if (running) {
                Text(
                    stringResource(R.string.hold_send_tip),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }

            if (searchable) {
                Spacer(Modifier.heightIn(min = DsSpacing.xsmall))
                DsTextField(
                    value = query,
                    onValueChange = { query = it.replace('\n', ' ').replace('\r', ' ') },
                    placeholder = stringResource(R.string.common_search),
                    leadingIcon = {
                        Icon(
                            FeatherIcons.Search,
                            contentDescription = null,
                            tint = colors.labelTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    trailingIcon = if (query.isEmpty()) null else {
                        {
                            androidx.compose.material3.IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.ux_b_clear_search),
                                    tint = colors.labelTertiary,
                                )
                            }
                        }
                    },
                )
            }

            // Commands ----------------------------------------------------------
            SheetSectionLabel(stringResource(R.string.chat_composer_commands))
            when {
                !commandsAvailable -> SheetEmptyText(stringResource(R.string.chat_commands_unavailable))
                filteredCommands.isEmpty() -> SheetEmptyText(stringResource(R.string.chat_commands_empty))
                else -> Column {
                    filteredCommands.forEach { command ->
                        SheetRow(
                            leading = { GlyphTile(FeatherIcons.Terminal) },
                            // The canonical invocation is what the user types, so it leads the row;
                            // the localized label used to replace it for four built-ins, which made
                            // the same list mix "/compact" with translated names.
                            title = command.line,
                            subtitle = listOfNotNull(
                                localizedCommandLabel(command.name),
                                command.description.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
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
                SheetSectionLabel(stringResource(R.string.skills_title))
                Column {
                    filteredSkills.forEach { skill ->
                        SheetRow(
                            leading = { GlyphTile(FeatherIcons.Zap) },
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
            Spacer(Modifier.heightIn(min = DsSpacing.small))
        }
    }
}

/** The uppercase micro label the design uses to head a group inside a sheet. */
@Composable
internal fun SheetSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = DsType.label12,
        color = DsTheme.colors.labelTertiary,
        modifier = Modifier.padding(start = DsSpacing.tiny, top = DsSpacing.small),
    )
}

/** A one-line "there is nothing here, and why" note, in the sheet's quiet register. */
@Composable
private fun SheetEmptyText(text: String) {
    Text(
        text,
        style = DsType.small13,
        color = DsTheme.colors.labelTertiary,
        modifier = Modifier.padding(start = DsSpacing.tiny).padding(vertical = DsSpacing.tiny),
    )
}

/** The 28dp bordered glyph square that leads a sheet row in the Ink kit. */
@Composable
internal fun GlyphTile(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val colors = DsTheme.colors
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(DsShapes.chip)
            .background(colors.bgLayer3)
            .border(1.dp, colors.borderL2, DsShapes.chip),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = colors.labelSecondary)
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
    val haptics = rememberDsHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .clickable(enabled = enabled) {
                haptics.tap()
                onClick()
            }
            .heightIn(min = 52.dp)
            .padding(horizontal = DsSpacing.tiny, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        if (leading != null) {
            leading()
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
            Spacer(Modifier.width(DsSpacing.xsmall))
            Text(
                trailing,
                style = DsType.mono12,
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
        fuzzyContains(name, needle) || description.lowercase().contains(needle)
    }
}

/** The app's own label for the four commands it gives bespoke UI, or null for host-defined ones. */
@Composable
private fun localizedCommandLabel(name: String): String? = when (name) {
    "goal" -> stringResource(R.string.goal_title)
    "plan" -> stringResource(R.string.plan_mode_title)
    "export" -> stringResource(R.string.chat_export)
    "feedback" -> stringResource(R.string.feedback_send)
    else -> null
}

