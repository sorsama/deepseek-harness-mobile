package com.labteto.dshmobile.ui.screens.main.commands

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.CommandDescriptor
import com.labteto.dshmobile.core.wire.dto.SkillEntry
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.main.GlyphTile
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** The `/token` under the cursor, when the draft is a slash line being typed. */
internal data class SlashQuery(val query: String)

/**
 * The slash line being typed, or null.
 *
 * The web client's `/` source only opens when the slash is the first character of the draft and
 * nothing but the token follows the cursor — a `/` mid-sentence is a path or a fraction, and a
 * slash line already carrying an argument has moved past the menu. Mirrored here so `/rewind` and
 * `/compact` offer a menu while `see /tmp/x` does not.
 */
internal fun activeSlash(text: String, cursor: Int): SlashQuery? {
    if (!text.startsWith("/")) return null
    val head = text.take(cursor.coerceIn(0, text.length))
    if (head.any { it.isWhitespace() }) return null
    if (text.length > head.length && !text[head.length].isWhitespace()) return null
    return SlashQuery(head.drop(1))
}

/** One row of the menu: a host command or a skill, both invoked as `/name`. */
internal sealed interface SlashRow {
    val name: String
    val description: String

    data class Command(val descriptor: CommandDescriptor) : SlashRow {
        override val name get() = descriptor.name
        override val description get() = descriptor.description
    }

    data class Skill(val entry: SkillEntry) : SlashRow {
        override val name get() = entry.name
        override val description get() = entry.description
    }
}

/**
 * Commands first, then skills; name-prefix matches before name-substring matches. The web's `/`
 * source matches the typed token against names (and its built-in rows' localized titles), never
 * descriptions — a description is explanation, not an alias, and matching it made `rew` offer
 * `/hypercompact` because its blurb says "request".
 */
internal fun slashRows(query: String, commands: List<CommandDescriptor>, skills: List<SkillEntry>): List<SlashRow> {
    val q = query.lowercase()
    fun rank(name: String): Int = when {
        q.isEmpty() -> 0
        name.lowercase().startsWith(q) -> 0
        name.lowercase().contains(q) -> 1
        else -> -1
    }
    val rows = commands.map { SlashRow.Command(it) } + skills.map { SlashRow.Skill(it) }
    return rows.map { it to rank(it.name) }
        .filter { it.second >= 0 }
        .sortedBy { it.second }
        .map { it.first }
}

/**
 * The `/` autocomplete above the composer: what the harness's own composer shows as you type a
 * slash line. A tap on a bare command submits it (and a decorated one opens its picker); a tap on
 * an argument-taking command or a skill completes the token and leaves the cursor after it.
 *
 * Styled as the top edge of a sheet: the Ink card surface, a bordered glyph per row, and the
 * command's argument hint in monospace at the trailing edge so the list reads as a menu, not as
 * three lines of grey text.
 */
@Composable
internal fun SlashMenu(
    query: SlashQuery,
    commands: List<CommandDescriptor>,
    skills: List<SkillEntry>,
    onPick: (SlashRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val rows = remember(query, commands, skills) { slashRows(query.query, commands, skills) }
    if (rows.isEmpty()) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.medium),
        shape = DsShapes.block,
        color = colors.bgLayer2,
        border = BorderStroke(1.dp, colors.borderL1),
        shadowElevation = 6.dp,
    ) {
        LazyColumn(
            Modifier.heightIn(max = 260.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = DsSpacing.tiny),
        ) {
            itemsIndexed(rows, key = { _, row -> (if (row is SlashRow.Skill) "s:" else "c:") + row.name }) { index, row ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(DsShapes.row)
                        .clickable {
                            haptics.tap()
                            onPick(row)
                        }
                        .heightIn(min = 46.dp)
                        .padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                ) {
                    GlyphTile(if (row is SlashRow.Skill) FeatherIcons.Zap else FeatherIcons.Terminal)
                    Column(Modifier.weight(1f)) {
                        Text(
                            "/${row.name}",
                            style = DsType.std14Strong,
                            color = colors.labelPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (row.description.isNotBlank()) {
                            Text(
                                row.description,
                                style = DsType.caption11,
                                color = colors.labelTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val trailing = when (row) {
                        is SlashRow.Command -> row.descriptor.input?.hint
                        is SlashRow.Skill -> stringResource(R.string.skills_title)
                    }
                    trailing?.let {
                        Spacer(Modifier.width(DsSpacing.xsmall))
                        Text(it, style = DsType.mono12, color = colors.labelCaption, maxLines = 1)
                    }
                }
                if (index != rows.lastIndex) {
                    HorizontalDivider(
                        color = colors.borderL1,
                        modifier = Modifier.padding(start = 44.dp),
                    )
                }
            }
        }
    }
}
