package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.markdown.SyntaxToken
import com.labteto.dshmobile.core.markdown.highlightCode
import com.labteto.dshmobile.core.markdown.isHighlighted
import com.labteto.dshmobile.ui.theme.DsColors
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

// ============================================================================================
// Code in tool cards
//
// A phone cannot show 80 columns, and breaking a build log or a diff mid-sentence at 40 destroys the
// alignment that makes it readable. So a block of code in a tool card does not wrap: it scrolls
// sideways as one piece (every line moves together, the line-number or +/- gutter stays put), a
// fading edge says there is more to the side, and a "Wrap lines" switch in the block's action row
// turns wrapping on for prose that reads better that way.
//
// Code is left to right whatever the app's language is: paths, commands and diff columns reorder
// into nonsense when a right-to-left base direction reaches them.
// ============================================================================================

/**
 * Which code blocks the reader switched to wrapped text. Held for as long as the app runs, not
 * stored: a block's key is its tool call's id and its own name, so it survives the row scrolling out
 * of the list and back, and a restart starts every block scrolling again.
 */
@Stable
class CodeWrapMemory {
    private val wrapped = mutableStateMapOf<String, Boolean>()

    fun isWrapped(key: String): Boolean = wrapped[key] == true

    fun toggle(key: String) {
        wrapped[key] = !isWrapped(key)
    }

    companion object {
        /** The one memory the app uses, so every host of a transcript shares it. */
        val Shared = CodeWrapMemory()
    }
}

val LocalCodeWrapMemory = staticCompositionLocalOf { CodeWrapMemory.Shared }

/** The tool call whose card is being drawn: the first part of each of its blocks' wrap keys. */
val LocalCodeOwner = compositionLocalOf { "" }

/** The memory key of one block of one call. */
internal fun codeWrapKey(owner: String, block: String): String = "$owner/$block"

/** A block's wrap choice and the way to flip it. */
@Stable
class CodeWrapState(val wrapped: Boolean, val toggle: () -> Unit)

@Composable
fun rememberCodeWrap(blockId: String): CodeWrapState {
    val memory = LocalCodeWrapMemory.current
    val key = codeWrapKey(LocalCodeOwner.current, blockId)
    val wrapped = memory.isWrapped(key)
    return remember(memory, key, wrapped) { CodeWrapState(wrapped) { memory.toggle(key) } }
}

/** Past this many characters on a line a phone cannot show it whole, and the wrap switch is offered. */
internal const val WRAP_THRESHOLD_CHARS = 36

/** Whether any line of [text] is longer than [threshold]; a scan that stops at the first one. */
internal fun hasLongLine(text: String, threshold: Int = WRAP_THRESHOLD_CHARS): Boolean {
    var run = 0
    for (ch in text) {
        if (ch == '\n') run = 0 else if (++run > threshold) return true
    }
    return false
}

private val SideFadeWidth = 28.dp

/**
 * [content] in a row that scrolls sideways and fades into [fadeColor] at each edge there is more
 * beyond. The fade is painted over the content, not laid out beside it, so it adds no size. A
 * horizontal drag scrolls this; a vertical one goes through to the transcript, and a drag that starts
 * outside it (the screen edge, for the drawer) never reaches it.
 */
@Composable
internal fun SideScroll(
    fadeColor: Color,
    modifier: Modifier = Modifier,
    /** The scroll position, for a caller that holds something still while the content scrolls. */
    state: ScrollState = rememberScrollState(),
    /** A width at the start that is held still (a gutter): the fading edge there starts after it. */
    fixedStart: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val fade = with(LocalDensity.current) { SideFadeWidth.toPx() }
    val inset = with(LocalDensity.current) { fixedStart.toPx() }
    Box(
        modifier
            .drawWithContent {
                drawContent()
                // "Forward" is toward the end of the line in the reading direction.
                val rightMore = if (ltr) state.canScrollForward else state.canScrollBackward
                val leftMore = if (ltr) state.canScrollBackward else state.canScrollForward
                if (rightMore) {
                    val x = size.width - fade
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, fadeColor), startX = x, endX = size.width),
                        topLeft = Offset(x, 0f),
                        size = Size(fade, size.height),
                    )
                }
                if (leftMore) {
                    drawRect(
                        Brush.horizontalGradient(listOf(fadeColor, Color.Transparent), startX = inset, endX = inset + fade),
                        topLeft = Offset(inset, 0f),
                        size = Size(fade, size.height),
                    )
                }
            }
            // Only a block that overflows takes horizontal drags. One that fits leaves them alone, so
            // the drawer still opens from a swipe that starts over it (the edge bands are outside
            // every block anyway: a block starts 28dp in).
            .horizontalScroll(state, enabled = state.maxValue > 0),
    ) { content() }
}

/** The "Wrap lines" switch of a block's action row: a 48dp target, and a check when it is on. */
@Composable
internal fun WrapToggle(state: CodeWrapState, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Row(
        modifier = modifier
            .heightIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.pill)
            .toggleable(value = state.wrapped, role = Role.Switch) {
                haptics.tap()
                state.toggle()
            }
            .padding(horizontal = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
    ) {
        Text(
            stringResource(R.string.code_wrap),
            style = DsType.small13Strong,
            color = colors.accent,
        )
        // The state is a shape, not only a colour: the check is there or it is not.
        Box(Modifier.size(14.dp)) {
            if (state.wrapped) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
        }
    }
}

/** Forces left to right below it: code, paths and diff columns keep their order in right-to-left languages. */
@Composable
internal fun LeftToRight(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

/**
 * One block of code: lines that do not wrap and scroll sideways together, or wrapped when the reader
 * chose it. [fadeColor] is the surface the block sits on, which the fading edge melts into.
 */
@Composable
internal fun CodeText(
    text: AnnotatedString,
    blockId: String,
    fadeColor: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = DsType.mdCode,
    color: Color = DsTheme.colors.labelPrimary,
    wrap: CodeWrapState = rememberCodeWrap(blockId),
    /** False where the caller puts [WrapToggle] in an action row of its own. */
    showToggle: Boolean = true,
) {
    val long = remember(text) { hasLongLine(text.text) }
    // Left to right in both senses: the layout direction (below) puts the block at its left edge, and
    // the text direction keeps a line that starts with a right-to-left character (a comment, a
    // string) from being laid out as a right-to-left paragraph.
    val codeStyle = remember(style) { style.copy(textDirection = TextDirection.Ltr) }
    Column(modifier.fillMaxWidth()) {
        if (showToggle && long) WrapToggle(wrap, Modifier.align(Alignment.End))
        LeftToRight {
            if (wrap.wrapped) {
                SelectionContainer { Text(text, style = codeStyle, color = color, modifier = Modifier.fillMaxWidth()) }
            } else {
                SideScroll(fadeColor, Modifier.fillMaxWidth()) {
                    SelectionContainer { Text(text, style = codeStyle, color = color, softWrap = false) }
                }
            }
        }
    }
}

/** [CodeText] for plain text. */
@Composable
internal fun CodeText(
    text: String,
    blockId: String,
    fadeColor: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = DsType.mdCode,
    color: Color = DsTheme.colors.labelPrimary,
    wrap: CodeWrapState = rememberCodeWrap(blockId),
    showToggle: Boolean = true,
) {
    val annotated = remember(text) { AnnotatedString(text) }
    CodeText(annotated, blockId, fadeColor, modifier, style, color, wrap, showToggle)
}

/** One cell of a line's gutter: a line number, or the `+` and `-` of a diff. */
internal class GutterCell(val text: String, val color: Color)

/** One line of a block with a gutter: its cells, its code, and the colour the code is drawn in. */
internal class GutterLine(val cells: List<GutterCell>, val text: AnnotatedString, val color: Color)

/**
 * Code with a gutter that stays put while the code scrolls: a file read (line numbers) or a diff (two
 * numbers and a `+`/`-`). Scrolling, the lines move as one and the gutter does not; wrapped, a line's
 * number stays beside its first row.
 *
 * One row per source line holds both its gutter and its code ([GutterRow]), so line N's number is
 * beside line N whatever the lines contain. (They used to be two columns, the numbers in one and the
 * lines in the other, and a blank line was a shorter row in the code column than in the numbers:
 * the numbers drifted down by a fraction of a row for every blank line above.) A row is as tall as
 * its code, so a wrapped line leaves room for its continuation rows, and never shorter than one line
 * of text, so a blank line is a whole line in both.
 *
 * Scrolling sideways moves each row's code under its own gutter: the gutter is drawn over the code at
 * the scroll offset, with the block's own colour behind it, which keeps it where it is.
 */
@Composable
internal fun CodeGutterBlock(
    lines: List<GutterLine>,
    blockId: String,
    fadeColor: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = DsType.mdCode,
    wrap: CodeWrapState = rememberCodeWrap(blockId),
) {
    val columns = lines.maxOfOrNull { it.cells.size } ?: 0
    val long = remember(lines) { lines.any { hasLongLine(it.text.text, GUTTER_WRAP_THRESHOLD_CHARS) } }
    // A gutter column is as wide as its widest cell, in the code's own font: one monospaced advance
    // is about 0.6 of its size, and the size follows the reader's text scale.
    val density = LocalDensity.current
    val widths = remember(lines, style, density) {
        List(columns) { column ->
            val chars = lines.maxOfOrNull { it.cells.getOrNull(column)?.text?.length ?: 0 } ?: 0
            with(density) { (chars * 0.62f * style.fontSize.value).sp.toDp() }
        }
    }
    val rowHeight = rememberLineHeight(style)
    // A line that starts with a right-to-left character (a comment) is still laid out left to right.
    val codeStyle = remember(style) { style.copy(textDirection = TextDirection.Ltr) }
    // The gutter's whole width, for the fading edge to start after it.
    val gutterWidth = widths.fold(GutterToCode) { total, width -> total + width + GutterGap }
    Column(modifier.fillMaxWidth()) {
        if (long) WrapToggle(wrap, Modifier.align(Alignment.End))
        LeftToRight {
            if (wrap.wrapped) {
                SelectionContainer {
                    Column(Modifier.fillMaxWidth()) {
                        lines.forEach { line -> GutterRow(line, widths, codeStyle, rowHeight, fadeColor, scroll = null) }
                    }
                }
            } else {
                val scroll = rememberScrollState()
                SideScroll(fadeColor, Modifier.fillMaxWidth(), state = scroll, fixedStart = gutterWidth) {
                    SelectionContainer {
                        Column {
                            lines.forEach { line -> GutterRow(line, widths, codeStyle, rowHeight, fadeColor, scroll = scroll) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One source line: its gutter cells, then its code. The row is at least [minHeight] tall (one line of
 * text), which is what keeps a blank line from collapsing: an empty `Text` is laid out shorter than
 * one with something in it. With a [scroll] the code does not wrap and the gutter is held where it is
 * while the row scrolls sideways; without one the code wraps beside its gutter.
 */
@Composable
private fun GutterRow(
    line: GutterLine,
    widths: List<Dp>,
    style: TextStyle,
    minHeight: Dp,
    fadeColor: Color,
    scroll: ScrollState?,
) {
    Row(Modifier.heightIn(min = minHeight)) {
        Row(
            Modifier
                .zIndex(1f)
                .then(
                    if (scroll != null) {
                        // The row scrolls by `scroll.value` to the left; the gutter moves the same
                        // distance back, over the code, with a surface of its own so the code passes
                        // under it. Read while drawing, so scrolling recomposes nothing.
                        Modifier.graphicsLayer { translationX = scroll.value.toFloat() }.background(fadeColor)
                    } else Modifier,
                ),
        ) {
            DisableSelection {
                line.cells.forEachIndexed { index, cell -> GutterText(cell, widths[index], style) }
                Spacer(Modifier.width(GutterToCode))
            }
        }
        Text(
            line.text,
            style = style,
            color = line.color,
            softWrap = scroll == null,
            modifier = if (scroll == null) Modifier.weight(1f) else Modifier,
        )
    }
}

/** The height of one line of [style], laid out the way any line of text in it is. */
@Composable
private fun rememberLineHeight(style: TextStyle): Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    return remember(style, density, measurer) {
        with(density) { measurer.measure("0", style).size.height.toDp() }
    }
}

/** The air between a block's gutter and its code. */
private val GutterToCode = 8.dp

private const val GUTTER_WRAP_THRESHOLD_CHARS = 28

/** The space after each gutter cell, so two numbers and a `+` never run together. */
private val GutterGap = 6.dp

/** One gutter cell: right-aligned in [width], one line, then [GutterGap] of air. */
@Composable
private fun GutterText(cell: GutterCell?, width: Dp, style: TextStyle) {
    Text(
        cell?.text.orEmpty(),
        style = style,
        color = cell?.color ?: Color.Unspecified,
        textAlign = TextAlign.End,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.width(width + GutterGap).padding(end = GutterGap),
    )
}

/** The theme colour of one syntax token, or null for plain text. */
internal fun syntaxColor(token: SyntaxToken, tint: DsColors): Color? = when (token) {
    SyntaxToken.Comment -> tint.syntaxComment
    SyntaxToken.String -> tint.syntaxString
    SyntaxToken.Number -> tint.syntaxConstant
    SyntaxToken.Keyword -> tint.syntaxKeyword
    SyntaxToken.Function -> tint.syntaxFunction
    SyntaxToken.Type -> tint.syntaxConstant
    SyntaxToken.Plain -> null
}

/**
 * A file read as the lines of a gutter block: one per entry of [lines], numbered as the read recorded
 * them, blank lines and a blank last line included. This is the whole line model of a read card:
 * nothing is dropped, merged or added, so line N's number is beside line N's code.
 */
internal fun readGutterLines(lines: List<ReadLine>, lang: String?, colors: DsColors): List<GutterLine> {
    val styled = highlightedLines(lines.map { it.text }, lang, colors)
    return lines.mapIndexed { index, line ->
        GutterLine(
            listOf(GutterCell(line.number.toString(), colors.labelCaption)),
            styled[index],
            colors.labelPrimary,
        )
    }
}

/**
 * [lines] as one styled line each, coloured by the same lexer the Markdown code blocks use, with the
 * answer exactly as long as the question: a blank line is an entry, and so is a last one. A language
 * the lexer has no rules for (Markdown is one) comes back plain, with no work done.
 */
internal fun highlightedLines(lines: List<String>, lang: String?, tint: DsColors): List<AnnotatedString> {
    // A `\r` left at the end of a line by a CRLF file is a line break, not a character to draw.
    val clean = lines.map { it.removeSuffix("\r") }
    if (clean.isEmpty() || !isHighlighted(lang)) return clean.map { AnnotatedString(it) }
    val highlighted = highlightCode(clean.joinToString("\n"), lang)
    // The lexer answers one entry per line it was given. Were that ever not so, plain text is right
    // and a colour on the wrong line is not.
    if (highlighted.size != clean.size) return clean.map { AnnotatedString(it) }
    return highlighted.map { spans ->
        AnnotatedString.Builder().apply {
            spans.forEach { span ->
                val color = syntaxColor(span.token, tint)
                if (color == null) append(span.text) else withStyle(SpanStyle(color = color)) { append(span.text) }
            }
        }.toAnnotatedString()
    }
}
