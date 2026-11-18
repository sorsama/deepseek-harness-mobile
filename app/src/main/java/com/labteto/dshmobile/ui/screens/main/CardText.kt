package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.lineDiff
import com.labteto.dshmobile.ui.components.ContentBlockView
import com.labteto.dshmobile.ui.components.DetailItem
import com.labteto.dshmobile.ui.components.DetailsCard
import com.labteto.dshmobile.ui.components.ImageCard
import com.labteto.dshmobile.ui.components.QuestionCard
import com.labteto.dshmobile.ui.components.SearchMatches
import com.labteto.dshmobile.ui.components.SkillCard
import com.labteto.dshmobile.ui.components.SubagentCard
import com.labteto.dshmobile.ui.components.TodoCard
import com.labteto.dshmobile.ui.components.ToolCardView

/**
 * A tool card as plain text, for the full-screen viewer behind "Show all": everything the inline
 * preview cut, in reading order. It is built from the whole card (not the clipped one) and only when
 * the reader asks, on a background thread, so a card holding a megabyte of output costs nothing until
 * then. The viewer indexes it by line; nothing here lays anything out.
 *
 * Words the app itself says are not in it (the viewer is for what the tool produced): a diff line is
 * its `+`, `-` or space, then the line; a read line is its number, a tab and the line.
 */
internal fun cardPlainText(card: ToolCardView): String = buildString {
    when (card) {
        is ToolCardView.TerminalCard -> {
            card.title?.takeIf { it.isNotEmpty() }?.let { append("$ ").append(it).append('\n') }
            card.output?.let { append(it) }
        }
        is ToolCardView.DiffCard -> card.diffs.forEach { hunk ->
            append("--- ").append(hunk.path).append('\n')
            lineDiff(hunk.oldText, hunk.newText).forEach { append(it.kind).append(it.text).append('\n') }
        }
        is ToolCardView.SearchCard -> when (val matches = card.matches) {
            is SearchMatches.PathList -> matches.paths.forEach { append(it).append('\n') }
            is SearchMatches.FileMatches -> matches.files.forEach { file ->
                append(file.path).append('\n')
                file.matches.forEach { append("  ").append(it.lineNumber).append(": ").append(it.line).append('\n') }
            }
        }
        is ToolCardView.ReadCard -> card.lines.forEach { append(it.number).append('\t').append(it.text).append('\n') }
        is ToolCardView.GenericCard -> {
            card.rawInput?.takeIf { it.isNotBlank() && it.trim() != "{}" }?.let { append(it).append("\n\n") }
            card.locations?.forEach { append(it).append('\n') }
            card.content?.forEach { block ->
                when (block) {
                    is ContentBlockView.TextBlock -> append(block.text).append('\n')
                    is ContentBlockView.ReasoningBlock -> append(block.text).append('\n')
                    is ContentBlockView.ImageBlock -> Unit
                }
            }
        }
        is ToolCardView.WebCard -> when (val kind = card.kind) {
            is com.labteto.dshmobile.ui.components.WebCardKind.Search -> {
                kind.answer?.let { append(it).append("\n\n") }
                kind.sources.forEach { source ->
                    source.title?.let { append(it).append('\n') }
                    append(source.url).append('\n')
                    source.snippet?.let { append(it).append('\n') }
                    append('\n')
                }
            }
            is com.labteto.dshmobile.ui.components.WebCardKind.Fetch -> append(kind.url)
        }
        is TodoCard -> card.lines.forEach { append(it.content).append('\n') }
        is QuestionCard -> card.questions.forEach { q ->
            q.header?.let { append(it).append('\n') }
            append(q.question).append('\n')
            q.options.forEach { append("  - ").append(it).append('\n') }
            q.answers?.let { append("  => ").append(it.joinToString(", ")).append('\n') }
        }
        is SubagentCard -> {
            append(card.title).append('\n')
            card.prompt?.let { append('\n').append(it).append('\n') }
            card.reply?.let { append('\n').append(it).append('\n') }
        }
        is SkillCard -> append(card.name).append('\n').append(card.description.orEmpty())
        is ImageCard -> append(card.path)
        is DetailsCard -> card.items.forEach { appendItem(it) }
    }
}

private fun StringBuilder.appendItem(item: DetailItem) {
    if (item.title.isNotEmpty()) append(item.title).append('\n')
    item.subtitle?.let { append(it).append('\n') }
    item.description?.let { append(it).append('\n') }
    item.code?.let { append(it).append('\n') }
}
