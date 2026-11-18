package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.core.session.OtherNode
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.serialization.json.*

/** Whether any nested call names [parent] as its parent, i.e. whether [PtcChildren] would draw a row. */
internal fun hasPtcChildren(events: List<OtherNode>, parent: String): Boolean = events.any { event ->
    (event.type == "tool/ptc-dispatch-start" || event.type == "tool/ptc-dispatch") &&
        ((event.data as? JsonObject)?.get("parentCallId") as? JsonPrimitive)?.contentOrNull == parent
}

/**
 * How a nested call ended, from its settlement record (`tool/ptc-dispatch`), through the function the
 * top-level rows use. Only a settlement carries `isError`, so a record that has not settled reads as
 * done here: a nested row shows a failure or a stop, and nothing for a call still going.
 */
internal fun ptcChildOutcome(record: JsonObject): ToolOutcome {
    val error = record["error"] as? JsonObject
    return toolOutcomeOfRecord(
        name = (record["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        settled = true,
        isError = (record["isError"] as? JsonPrimitive)?.booleanOrNull == true,
        errorCode = (error?.get("code") as? JsonPrimitive)?.contentOrNull,
        errorName = (error?.get("name") as? JsonPrimitive)?.contentOrNull,
        running = false,
    )
}

/** Dispatch-start and settlement share one child row; recursion follows declared parent ids. */
@Composable
internal fun PtcChildren(
    parent: String,
    events: List<OtherNode>,
    ancestors: Set<String> = emptySet(),
    presentation: WorkStepPresentation = WorkStepPresentation.Default,
    /** The enclosing row is a level-opened preview: list few children, clip their text. */
    clip: Boolean = false,
    onShowAll: () -> Unit = {},
) {
    if (parent in ancestors || ancestors.size >= 12) return
    val colors = DsTheme.colors
    val children = remember(events, parent) {
        events.filter { it.type in setOf("tool/ptc-dispatch-start", "tool/ptc-dispatch") }
            .mapNotNull { it.data as? JsonObject }
            .filter { (it["parentCallId"] as? JsonPrimitive)?.contentOrNull == parent }
            .groupBy { (it["subCallId"] as? JsonPrimitive)?.contentOrNull }
    }
    // A code run can dispatch hundreds of calls: a preview lists the first few, "Show all" the rest.
    val listed = if (clip) children.entries.take(PREVIEW_MAX_ITEMS) else children.entries
    if (listed.size < children.size) ClippedNote(onShowAll)
    listed.forEach { (id, records) ->
        if (id == null) return@forEach
        val record = records.last()
        // Nested calls follow the level like their parent; a tap on one overrides only that one.
        val nested = rememberRowDisclosure(presentation, "ptc:$id", Long.MAX_VALUE)
        val expanded = nested.open
        // Cut only while the enclosing row is a preview and this call has not been opened by a tap.
        val clipChild = clip && nested.preview
        DisclosureRow(title = (record["name"] as? JsonPrimitive)?.contentOrNull ?: id,
            state = ptcChildOutcome(record).disclosureState(),
            expanded = expanded, onToggle = nested.toggle) {
            Column(Modifier.padding(start = DsSpacing.medium), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DsSpacing.xsmall)) {
                record["arguments"]?.let { args ->
                    val code = (args as? JsonObject)?.get("code") as? JsonPrimitive
                    if (code != null) {
                        SelectionContainer {
                            Text(
                                if (clipChild) clipPreview(code.content) else code.content,
                                style = DsType.mono12,
                                color = colors.labelSecondary,
                                softWrap = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(DsShapes.chip)
                                    .background(colors.codeBlockBg)
                                    .border(1.dp, colors.borderL1, DsShapes.chip)
                                    .padding(DsSpacing.small),
                            )
                        }
                    }
                    if (clipChild && code != null && exceedsPreview(code.content)) ClippedNote(nested.showAll)
                    JsonDisclosure("JSON", args)
                }
                record["content"]?.let { ToolResultOutput(it, clip = clipChild, onShowAll = nested.showAll) }
                PtcChildren(id, events, ancestors + parent, presentation, clipChild, nested.showAll)
            }
        }
    }
}
