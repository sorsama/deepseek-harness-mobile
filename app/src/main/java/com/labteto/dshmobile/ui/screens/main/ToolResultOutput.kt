package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.session.readToolResult
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.rememberCopier
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.serialization.json.JsonElement

/**
 * What a tool call returned, as text.
 *
 * The result body is protocol (`[{ "type": "text", "text": "..." }]`, sometimes wrapped again), and
 * the useful part is the text inside it. This shows that text, with Copy copying it; the original
 * JSON is one explicit tap away ("Show raw JSON") and has its own copy. A result that is not text at
 * all (a real object) is shown pretty-printed, so nothing it says is hidden.
 *
 * The unwrapping itself lives in core ([readToolResult]) so it is tested without a device.
 *
 * @param label a caption in front of the actions, for when nothing else says this is the output.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolResultOutput(content: JsonElement, modifier: Modifier = Modifier, label: String? = null) {
    val reading = remember(content) { readToolResult(content) } ?: return
    var showRaw by remember(content) { mutableStateOf(false) }
    val copy = rememberCopier()
    val raw = reading.rawJson
    val hasText = reading.text.isNotBlank()
    if (!hasText && raw == null) return
    val shown = if (showRaw && raw != null) raw else reading.text
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        // Wraps rather than clips: "Copy raw JSON" and "Hide raw JSON" side by side are wider than a
        // phone in several of the app's languages.
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
                    color = DsTheme.colors.labelCaption,
                    modifier = Modifier.padding(top = 2.dp, end = DsSpacing.tiny),
                )
            }
            if (showRaw && raw != null) {
                JsonAction(stringResource(R.string.tool_output_hide_raw)) { showRaw = false }
                JsonAction(stringResource(R.string.tool_output_copy_raw)) { copy(raw) }
            } else {
                if (hasText) JsonAction(stringResource(R.string.common_copy)) { copy(reading.text) }
                if (raw != null) JsonAction(stringResource(R.string.tool_output_show_raw)) { showRaw = true }
            }
        }
        if (shown.isNotBlank()) JsonCode(shown)
    }
}

/**
 * [ToolResultOutput] behind an "Output" row that starts closed, for a card whose own body already
 * presents the result (a terminal, a diff, a read): the text is there when wanted without being
 * said twice.
 */
@Composable
internal fun ToolResultOutputRow(title: String, content: JsonElement) {
    var expanded by remember(content) { mutableStateOf(false) }
    DisclosureRow(title = title, expanded = expanded, onToggle = { expanded = !expanded }) {
        ToolResultOutput(content, Modifier.padding(start = DsSpacing.medium))
    }
}
