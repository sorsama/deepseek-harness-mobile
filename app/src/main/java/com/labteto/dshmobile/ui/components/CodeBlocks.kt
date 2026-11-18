package com.labteto.dshmobile.ui.components

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    content: @Composable () -> Unit,
) {
    val state = rememberScrollState()
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val fade = with(LocalDensity.current) { SideFadeWidth.toPx() }
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
                        Brush.horizontalGradient(listOf(fadeColor, Color.Transparent), startX = 0f, endX = fade),
                        topLeft = Offset(0f, 0f),
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
    Column(modifier.fillMaxWidth()) {
        if (showToggle && long) WrapToggle(wrap, Modifier.align(Alignment.End))
        LeftToRight {
            if (wrap.wrapped) {
                SelectionContainer { Text(text, style = style, color = color, modifier = Modifier.fillMaxWidth()) }
            } else {
                SideScroll(fadeColor, Modifier.fillMaxWidth()) {
                    SelectionContainer { Text(text, style = style, color = color, softWrap = false) }
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
 * numbers and a `+`/`-`). Scrolling, the lines move as one and the gutter does not; wrapped, each
 * line carries its own gutter so a wrapped line's number stays beside its first row.
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
    val gap = 8.dp
    Column(modifier.fillMaxWidth()) {
        if (long) WrapToggle(wrap, Modifier.align(Alignment.End))
        LeftToRight {
            if (wrap.wrapped) {
                SelectionContainer {
                    Column(Modifier.fillMaxWidth()) {
                        lines.forEach { line ->
                            Row(Modifier.fillMaxWidth()) {
                                DisableSelection {
                                    line.cells.forEachIndexed { index, cell -> GutterText(cell, widths[index], style) }
                                }
                                Spacer(Modifier.width(gap))
                                Text(line.text, style = style, color = line.color, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Row {
                        for (column in 0 until columns) {
                            Column(Modifier.width(widths[column] + GutterGap)) {
                                lines.forEach { line -> GutterText(line.cells.getOrNull(column), widths[column], style) }
                            }
                        }
                    }
                    Spacer(Modifier.width(gap))
                    SideScroll(fadeColor, Modifier.weight(1f)) {
                        SelectionContainer {
                            Column {
                                lines.forEach { line -> Text(line.text, style = style, color = line.color, softWrap = false) }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
 * [code] as one styled line per source line, coloured by the same lexer the Markdown code blocks use.
 * A language the lexer has no rules for comes back plain, with no work done.
 */
internal fun highlightedLines(code: String, lang: String?, tint: DsColors): List<AnnotatedString> {
    if (!isHighlighted(lang)) return code.split("\n").map { AnnotatedString(it) }
    return highlightCode(code, lang).map { spans ->
        AnnotatedString.Builder().apply {
            spans.forEach { span ->
                val color = syntaxColor(span.token, tint)
                if (color == null) append(span.text) else withStyle(SpanStyle(color = color)) { append(span.text) }
            }
        }.toAnnotatedString()
    }
}
