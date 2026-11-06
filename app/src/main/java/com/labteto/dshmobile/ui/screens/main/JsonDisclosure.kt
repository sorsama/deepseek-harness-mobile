package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.rememberCopier
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.serialization.json.*

/** JSON strings can contain embedded JSON; expand those without losing the original copy value. */
@Composable
internal fun JsonDisclosure(title: String, value: JsonElement, depth: Int = 0) {
    var expanded by remember(value) { mutableStateOf(false) }
    val open = com.labteto.dshmobile.ui.components.LocalFileOpener.current
    val colors = DsTheme.colors
    val copy = rememberCopier()
    DisclosureRow(title = title, expanded = expanded, onToggle = { expanded = !expanded }) {
        Column(Modifier.padding(start = DsSpacing.medium), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
            if (value is JsonObject) {
                // Only a complete durable reference can be read back: the host verifies the
                // supplied id, media type, byte length and dimensions against what it stored, so a
                // partial object (an id with guessed dimensions) is not worth a failing request.
                val ref = remember(value) {
                    runCatching {
                        kotlinx.serialization.json.Json.decodeFromJsonElement(
                            com.labteto.dshmobile.core.wire.dto.ImageAttachmentRef.serializer(),
                            value,
                        )
                    }.getOrNull()
                }
                if (ref != null) {
                    com.labteto.dshmobile.ui.components.AttachmentImage(ref = ref, contentDescription = ref.name)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                if (value is JsonPrimitive && value.isString && title in setOf("path", "absolutePath", "file_path", "filePath")) {
                    previewPath(value.content)?.let { path ->
                        JsonAction(stringResource(R.string.panel_preview)) { open(path) }
                    }
                }
                JsonAction(stringResource(R.string.common_copy)) { copy(value.toString()) }
            }
            when {
                depth >= 8 -> JsonCode(value.toString())
                value is JsonObject -> value.forEach { (name, child) -> JsonDisclosure(name, child, depth + 1) }
                value is JsonArray -> value.forEachIndexed { i, child -> JsonDisclosure(i.toString(), child, depth + 1) }
                value is JsonPrimitive && value.isString -> {
                    val parsed = remember(value) { runCatching { Json.parseToJsonElement(value.content) }.getOrNull() }
                    if (parsed is JsonObject || parsed is JsonArray) JsonDisclosure("JSON", parsed, depth + 1)
                    else JsonCode(value.content)
                }
                else -> JsonCode(value.toString())
            }
        }
    }
}

/** A compact text action in the Ink accent, for values inside a disclosure body. */
@Composable
private fun JsonAction(label: String, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Text(
        label,
        style = DsType.small13Strong,
        color = colors.accent,
        maxLines = 1,
        modifier = Modifier
            .clip(DsShapes.pill)
            .clickable(role = Role.Button) { haptics.tap(); onClick() }
            .heightIn(min = 32.dp)
            .padding(horizontal = DsSpacing.small),
    )
}

/** Raw JSON on the code-block surface, in the mono face. */
@Composable
private fun JsonCode(text: String) {
    val colors = DsTheme.colors
    SelectionContainer {
        Text(
            text,
            style = DsType.mono12,
            color = colors.labelSecondary,
            softWrap = true,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.chip)
                .padding(vertical = 2.dp),
        )
    }
}

internal fun fuzzyContains(name: String, query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    var index = 0
    for (character in name.lowercase()) {
        if (character == needle[index]) index++
        if (index == needle.length) return true
    }
    return false
}

/** A host file path is never interpreted as an Android path or an arbitrary external scheme. */
internal fun previewPath(value: String): String? {
    val raw = value.trim().removeSurrounding("<", ">")
    if (raw.isEmpty() || raw.startsWith('#')) return null
    if (raw.startsWith("file://")) return runCatching { java.net.URI(raw).path }.getOrNull()
    if (Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(raw)) return raw
    if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw)) return null
    return raw.substringBefore('#')
}
