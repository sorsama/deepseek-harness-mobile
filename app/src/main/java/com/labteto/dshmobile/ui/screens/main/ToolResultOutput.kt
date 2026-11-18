package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.ToolResultReading
import com.labteto.dshmobile.core.session.readToolResult
import com.labteto.dshmobile.ui.components.CodeText
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsProgress
import com.labteto.dshmobile.ui.components.OutputViewerDialog
import com.labteto.dshmobile.ui.components.WrapToggle
import com.labteto.dshmobile.ui.components.hasLongLine
import com.labteto.dshmobile.ui.components.rememberCodeWrap
import com.labteto.dshmobile.ui.components.rememberCopier
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a tool call returned, as text.
 *
 * The result body is protocol (`[{ "type": "text", "text": "..." }]`, sometimes wrapped again), and
 * the useful part is the text inside it. This shows that text, with Copy copying it. The original
 * JSON is for whoever is debugging, so it lives in the call's hold menu ("Show raw JSON") rather than
 * beside every output. A result that is not text at all (a real object) is shown pretty-printed, so
 * nothing it says is hidden.
 *
 * Only the start is laid out, always: [clipPreview]'s bounds apply however the row was opened, so a
 * result of megabytes is never one `Text`. "Show all" opens the whole of it in the full-screen viewer,
 * which is lazy by line. The lines do not wrap and scroll sideways together; "Wrap lines" turns
 * wrapping on for prose.
 *
 * The unwrapping itself lives in core ([readToolResult]) so it is tested without a device.
 *
 * @param label a caption in front of the actions, for when nothing else says this is the output.
 * @param blockId which of the call's blocks this is, for remembering whether it wraps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolResultOutput(
    content: JsonElement,
    modifier: Modifier = Modifier,
    label: String? = null,
    blockId: String = "output",
    /** What the viewer is titled; the call's own title where there is one. */
    viewerTitle: String = stringResource(R.string.chat_output_placeholder),
) {
    val colors = DsTheme.colors
    // A result of megabytes is read off the main thread: unwrapping its protocol layers walks all of it.
    val huge = remember(content) { isHugeResult(content) }
    var read by remember(content) { mutableStateOf<ToolResultReading?>(if (huge) null else readToolResult(content)) }
    if (huge) LaunchedEffect(content) { read = withContext(Dispatchers.Default) { readToolResult(content) } }
    val reading = read
    if (reading == null) {
        // Still being read: a thin bar for a result that is a megabyte or more, nothing for one with no text.
        if (huge) DsProgress(modifier.padding(vertical = DsSpacing.small))
        return
    }
    val copy = rememberCopier()
    val full = reading.text
    if (full.isBlank()) return
    val shown = remember(full) { clipPreview(full) }
    val clipped = shown.length != full.length
    var viewer by remember(content) { mutableStateOf(false) }
    val wrap = rememberCodeWrap(blockId)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        // Wraps rather than clips, for a long label in some of the app's languages.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            if (label != null) {
                // The actions' text sits at the top of their 32dp tap area; 2dp lines the caption's
                // middle up with theirs.
                Text(
                    label,
                    style = DsType.caption11,
                    color = colors.labelCaption,
                    modifier = Modifier.padding(top = 2.dp, end = DsSpacing.tiny),
                )
            }
            JsonAction(stringResource(R.string.common_copy)) { copy(full) }
            if (hasLongLine(shown)) WrapToggle(wrap)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(DsShapes.row)
                .background(colors.codeBlockBg)
                .border(1.dp, colors.borderL1, DsShapes.row)
                // 12dp inside the transcript's 16dp: the scrolling text starts 28dp from the screen
                // edge, clear of the edge band the drawer and the Details panel are swiped from.
                .padding(12.dp),
        ) {
            CodeText(shown, blockId = blockId, fadeColor = colors.codeBlockBg, wrap = wrap, showToggle = false)
        }
        if (clipped) ClippedNote { viewer = true }
    }
    if (viewer) {
        OutputViewerDialog(title = viewerTitle, contentKey = content, onDismiss = { viewer = false }, load = { full })
    }
}

/**
 * Whether a result's text is long enough that reading it should not hold up a frame: a first
 * look at the text parts, counting characters, never copying them.
 */
internal fun isHugeResult(content: JsonElement, limit: Int = HUGE_RESULT_CHARS): Boolean {
    var seen = 0L
    fun walk(element: JsonElement?, depth: Int) {
        if (seen > limit || depth > 4) return
        when (element) {
            is JsonPrimitive -> if (element.isString) seen += element.content.length
            is JsonArray -> element.forEach { walk(it, depth + 1) }
            is JsonObject -> element.values.forEach { walk(it, depth + 1) }
            else -> Unit
        }
    }
    walk(content, 0)
    return seen > limit
}

/** Past this many characters a result is read on a background thread. */
internal const val HUGE_RESULT_CHARS = 200_000

/** "Showing the start of a long result", with the action that opens the rest in the viewer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClippedNote(onShowAll: () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.work_steps_clipped),
            style = DsType.caption11,
            color = DsTheme.colors.labelCaption,
            modifier = Modifier.padding(top = 2.dp),
        )
        JsonAction(stringResource(R.string.work_steps_show_all), onShowAll)
    }
}

/**
 * [ToolResultOutput] behind an "Output" row that starts closed, for a card whose own body already
 * presents the result (a terminal, a diff, a read): the text is there when wanted without being
 * said twice.
 */
@Composable
internal fun ToolResultOutputRow(
    title: String,
    content: JsonElement,
    blockId: String = "output",
    viewerTitle: String = title,
) {
    var expanded by remember(content) { mutableStateOf(false) }
    DisclosureRow(title = title, expanded = expanded, onToggle = { expanded = !expanded }) {
        ToolResultOutput(content, Modifier.padding(start = DsSpacing.medium), blockId = blockId, viewerTitle = viewerTitle)
    }
}

