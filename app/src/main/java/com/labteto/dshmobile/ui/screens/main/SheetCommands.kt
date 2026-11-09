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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.DELIVERY_QUEUE
import com.labteto.dshmobile.connection.DELIVERY_STEER
import com.labteto.dshmobile.core.wire.dto.CommandDescriptor
import com.labteto.dshmobile.core.wire.dto.SkillEntry
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsSearchField
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** The two catalogs this sheet can show. The keys are what [DsSegmented] tracks. */
private enum class SheetCatalog(val key: String) {
    Commands("commands"),
    Skills("skills"),
    ;

    companion object {
        fun of(key: String): SheetCatalog = entries.firstOrNull { it.key == key } ?: Commands
    }
}

/**
 * The `+` sheet: everything you can add to a message that is not the message.
 *
 * Commands come from the harness's own per-session catalog rather than a hardcoded list. When the
 * harness exposes no catalog the section says so instead of inventing entries — a menu that offers
 * a command the host will reject is worse than a menu that admits it does not know.
 *
 * **Shape.** A harness can publish dozens of commands and a machine can carry dozens of skills, and
 * the old sheet put both behind one endless scroll: the search field scrolled out of reach exactly
 * when the catalog got big enough to need it, and there was no way to tell where commands stopped
 * and skills began. Now the two catalogs are tabs with live counts, the tabs and the search are
 * pinned above the list, and only the list scrolls — so a search can be refined without losing the
 * field, and a tab with matches says so even while the other one is open.
 *
 * The attach actions and the send-mode choice moved to a footer bar for the same reason: they are
 * per-message settings, not catalog entries, and pinning them keeps the catalog from being pushed
 * off the bottom by chrome that never changes.
 *
 * The sheet used to be a Column of sections with two separately-capped LazyColumns inside it; a full
 * catalog made the outer Column taller than the screen, so the title sat under the status bar and the
 * last rows were cut off below the nav bar. Everything scrolls together or — as now — exactly one
 * region scrolls inside a bounded sheet, and the sheet keeps the system-bar insets it is handed.
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
     * image parts ride `subagents/prompt` normally. The control still says why instead of vanishing.
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
    var tab by remember { mutableStateOf(SheetCatalog.Commands) }
    val filteredCommands = remember(commands, query) { commands.filterByQuery(query) { it.name to it.description } }
    val filteredSkills = remember(skills, query) { skills.filterByQuery(query) { it.name to it.description } }
    val searchable = commands.size + skills.size > 12
    // A session with no skills must not be able to open a Skills tab that cannot be left; the same
    // holds for a catalog whose skills all disappear while that tab is showing.
    val active = if (tab == SheetCatalog.Skills && skills.isEmpty()) SheetCatalog.Commands else tab

    DsBottomSheet(
        title = stringResource(R.string.chat_composer_commands),
        onDismiss = {
            haptics.tap()
            onDismiss()
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            val commandsLabel = stringResource(R.string.chat_composer_commands)
            val skillsLabel = stringResource(R.string.skills_title)
            // Counts are of what the query matches: a search that only hits skills then says so on
            // the tab the reader is not looking at, instead of leaving them on an empty list.
            DsSegmented(
                segments = listOfNotNull(
                    DsSegment(SheetCatalog.Commands.key, "$commandsLabel (${filteredCommands.size})"),
                    if (skills.isEmpty()) {
                        null
                    } else {
                        DsSegment(SheetCatalog.Skills.key, "$skillsLabel (${filteredSkills.size})")
                    },
                ),
                selectedKey = active.key,
                onSelect = { key -> tab = SheetCatalog.of(key) },
                modifier = Modifier.fillMaxWidth(),
                role = Role.Tab,
                stretch = true,
            )

            if (searchable) {
                Spacer(Modifier.height(DsSpacing.small))
                DsSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.common_search),
                )
            }
            Spacer(Modifier.height(DsSpacing.small))

            // The only scrolling region. `fill = false` lets a short catalog leave a short sheet,
            // while a long one is capped at the sheet's height and scrolls inside it.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                when (active) {
                    SheetCatalog.Commands -> when {
                        !commandsAvailable -> item {
                            SheetEmptyText(stringResource(R.string.chat_commands_unavailable))
                        }
                        filteredCommands.isEmpty() -> item {
                            SheetEmptyText(
                                if (query.isBlank()) {
                                    stringResource(R.string.chat_commands_empty)
                                } else {
                                    stringResource(R.string.command_popup_no_matches)
                                },
                            )
                        }
                        else -> items(filteredCommands, key = { "c:" + it.name }) { command ->
                            SheetRow(
                                leading = { GlyphTile(FeatherIcons.Terminal) },
                                // The canonical invocation is what the user types, so it leads the
                                // row; the localized label used to replace it for four built-ins,
                                // which made the same list mix "/compact" with translated names.
                                title = command.line,
                                subtitle = listOfNotNull(
                                    localizedCommandLabel(command.name),
                                    command.description.takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                trailing = command.input?.hint,
                                onClick = {
                                    onDismiss()
                                    // A bare command runs immediately; one that takes an argument
                                    // prefills the composer so the argument can be typed where the
                                    // hint is visible.
                                    if (command.input == null) {
                                        onRunCommand(command.line)
                                    } else {
                                        onPrefillDraft(command.draftPrefix)
                                    }
                                },
                            )
                        }
                    }

                    SheetCatalog.Skills -> if (filteredSkills.isEmpty()) {
                        item { SheetEmptyText(stringResource(R.string.command_popup_no_matches)) }
                    } else {
                        items(filteredSkills, key = { "s:" + it.name }) { skill ->
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
            }

            // Attach and send mode: settings for the next message, kept out of the catalog's way.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                DsIconButton(
                    icon = Icons.Filled.Image,
                    contentDescription = stringResource(R.string.chat_composer_attach),
                    onClick = {
                        onDismiss()
                        onAttach()
                    },
                    enabled = canAttach,
                    bordered = true,
                )
                // Harness 0.1.3 takes any file, not only pictures. The bytes go up as soon as one is
                // picked and the message cites the receipt, so the control stays enabled exactly when
                // the image one is: both need an open session to upload against.
                DsIconButton(
                    icon = Icons.Filled.AttachFile,
                    contentDescription = stringResource(R.string.chat_composer_attach_file),
                    onClick = {
                        onDismiss()
                        onAttachFile()
                    },
                    enabled = canAttach && fileAttachEnabled,
                    bordered = true,
                )
                Spacer(Modifier.weight(1f))
                // Steering splices into a turn that is already running. With an idle agent it still
                // selects: the caption below says there is nothing to steer yet, and a send in that
                // state falls back to a queued prompt.
                DsSegmented(
                    segments = listOf(
                        DsSegment(DELIVERY_QUEUE, stringResource(R.string.chat_composer_queue)),
                        DsSegment(DELIVERY_STEER, stringResource(R.string.chat_composer_steer)),
                    ),
                    selectedKey = if (mode == DELIVERY_STEER) DELIVERY_STEER else DELIVERY_QUEUE,
                    onSelect = onModeChange,
                )
            }
            // Why a control is dead, in the same place the mode's own explanation goes, rather than
            // as a subtitle that would have to repeat itself on both attach rows.
            if (!canAttach) {
                Text(
                    stringResource(R.string.err_attachment_failed),
                    style = DsType.caption11,
                    color = colors.warnLabel,
                )
            } else if (!fileAttachEnabled) {
                Text(
                    stringResource(R.string.err_file_subagent_unsupported),
                    style = DsType.caption11,
                    color = colors.warnLabel,
                )
            }
            Text(
                stringResource(
                    when {
                        !running && mode == DELIVERY_STEER -> R.string.chat_composer_steer_idle
                        mode == DELIVERY_STEER -> R.string.chat_composer_mode_steer_hint
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
        }
    }
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
