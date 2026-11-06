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

/** Dispatch-start and settlement share one child row; recursion follows declared parent ids. */
@Composable
internal fun PtcChildren(parent: String, events: List<OtherNode>, ancestors: Set<String> = emptySet()) {
    if (parent in ancestors || ancestors.size >= 12) return
    val colors = DsTheme.colors
    val children = remember(events, parent) {
        events.filter { it.type in setOf("tool/ptc-dispatch-start", "tool/ptc-dispatch") }
            .mapNotNull { it.data as? JsonObject }
            .filter { (it["parentCallId"] as? JsonPrimitive)?.contentOrNull == parent }
            .groupBy { (it["subCallId"] as? JsonPrimitive)?.contentOrNull }
    }
    children.forEach { (id, records) ->
        if (id == null) return@forEach
        val record = records.last()
        var expanded by remember(id) { mutableStateOf(false) }
        DisclosureRow(title = (record["name"] as? JsonPrimitive)?.contentOrNull ?: id,
            expanded = expanded, onToggle = { expanded = !expanded }) {
            Column(Modifier.padding(start = DsSpacing.medium), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DsSpacing.xsmall)) {
                record["arguments"]?.let { args ->
                    val code = (args as? JsonObject)?.get("code") as? JsonPrimitive
                    if (code != null) {
                        SelectionContainer {
                            Text(
                                code.content,
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
                    JsonDisclosure("JSON", args)
                }
                record["content"]?.let { JsonDisclosure("JSON", it) }
                PtcChildren(id, events, ancestors + parent)
            }
        }
    }
}
