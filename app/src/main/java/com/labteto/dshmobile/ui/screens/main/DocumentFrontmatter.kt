package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.MarkdownText
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor

internal data class Frontmatter(val fields: List<Pair<String, String>>, val body: String)

/** Safe YAML values only; malformed, oversized and unterminated headers stay visible as source. */
internal fun documentFrontmatter(text: String): Frontmatter? {
    val match = Regex("\\A\\uFEFF?---[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n(?:---|\\.\\.\\.)[ \\t]*(?:\\r?\\n|$)").find(text) ?: return null
    val header = match.groupValues[1]
    if (header.length > 16_384) return null
    return runCatching {
        val options = LoaderOptions().apply { maxAliasesForCollections = 0; codePointLimit = 16_384; nestingDepthLimit = 16; isAllowDuplicateKeys = false }
        val value = Yaml(SafeConstructor(options)).load<Any?>(header) as? Map<*, *> ?: return null
        fun display(value: Any?, depth: Int = 0): String {
            require(depth <= 16)
            return when(value) {
                null -> "null"
                is Map<*, *> -> value.entries.joinToString("\n") { "${it.key}: ${display(it.value, depth + 1)}" }
                is List<*> -> value.joinToString(", ") { display(it, depth + 1) }
                else -> value.toString()
            }
        }
        Frontmatter(value.entries.map { it.key.toString() to display(it.value) }, text.substring(match.range.last + 1))
    }.getOrNull()
}

@Composable
internal fun DocumentMarkdown(text: String) {
    val frontmatter = remember(text) { documentFrontmatter(text) }
    var expanded by remember(text) { mutableStateOf(false) }
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Column {
        frontmatter?.let { metadata ->
            DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    animationSpec = DsAnimations.chevron,
                    label = "frontmatterChevron",
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { haptics.tap(); expanded = !expanded }
                        .heightIn(min = DsSpacing.touchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).rotate(rotation),
                        tint = colors.labelTertiary,
                    )
                    Text(
                        stringResource(R.string.harness_frontmatter),
                        style = DsType.label12,
                        color = colors.labelTertiary,
                        modifier = Modifier.weight(1f).padding(start = DsSpacing.small),
                    )
                    Text(
                        metadata.fields.size.toString(),
                        style = DsType.mono12,
                        color = colors.labelCaption,
                    )
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
                    exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        metadata.fields.forEach { (key, value) ->
                            Column {
                                Text(key, style = DsType.caption11, color = colors.labelTertiary)
                                Text(value, style = DsType.std14, color = colors.labelPrimary)
                            }
                        }
                    }
                }
            }
        }
        MarkdownText(frontmatter?.body ?: text)
    }
}
