package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.preset.PresetComposition
import com.labteto.dshmobile.core.preset.PresetEntry
import com.labteto.dshmobile.core.preset.PresetEntryState
import com.labteto.dshmobile.core.preset.parsePresetComposition
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.AgentPresetDocument
import com.labteto.dshmobile.core.wire.dto.AgentPresetEntry
import com.labteto.dshmobile.data.HarnessFeature
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsTextAction
import com.labteto.dshmobile.ui.components.rememberCopier
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the page has to show: the read is in flight, failed, or came back. */
private sealed interface CompositionLoad {
    data object Loading : CompositionLoad
    data class Failed(val message: String) : CompositionLoad
    data object Missing : CompositionLoad
    data object Unsupported : CompositionLoad
    data class Ready(val document: AgentPresetDocument, val composition: PresetComposition) : CompositionLoad
}

/**
 * What one preset is made of, read only.
 *
 * This is the official client's "View configuration": harness 0.2.1 declares a preset as a list of
 * plugin rows and answers `agentPresets/read` with that list as YAML, and nothing accepts YAML
 * back (a preset changes by installing a plugin bundle). So this page lists the rows, says whether
 * each one always mounts, is off or depends on a condition, and keeps the exact YAML one tap away
 * with Copy. Text the page does not understand stays visible in that YAML.
 */
@Composable
internal fun PresetCompositionPage(
    entry: AgentPresetEntry,
    store: SessionStore,
    onClose: () -> Unit,
) {
    val offline = stringResource(R.string.common_offline)
    var attempt by remember { mutableIntStateOf(0) }
    var load by remember(entry.id) { mutableStateOf<CompositionLoad>(CompositionLoad.Loading) }
    LaunchedEffect(entry.id, attempt) {
        load = CompositionLoad.Loading
        val api = store.apiForHost(store.activeHostKey)
        if (api == null) {
            load = CompositionLoad.Failed(offline)
            return@LaunchedEffect
        }
        load = when (val result = store.featureCall(HarnessFeature.PRESET_COMPOSITION, api) { api.agentPresetRead(entry.id) }) {
            is RpcResult.Ok -> {
                val composition = withContext(Dispatchers.Default) { parsePresetComposition(result.value.content) }
                CompositionLoad.Ready(result.value, composition)
            }
            is RpcResult.Err -> when {
                store.presetCompositionAvailable.value == false -> CompositionLoad.Unsupported
                result.error.code == "agent-preset/not-found" -> CompositionLoad.Missing
                else -> CompositionLoad.Failed(result.error.message)
            }
        }
    }
    val copy = rememberCopier()
    val current = load
    FeaturePage(
        title = entry.displayName(),
        subtitle = stringResource(R.string.preset_comp_subtitle),
        onClose = onClose,
        onRefresh = { attempt++ },
    ) {
        when (current) {
            CompositionLoad.Loading -> {
                FeatureProgress()
                Text(
                    stringResource(R.string.preset_comp_loading),
                    style = DsType.small13,
                    color = DsTheme.colors.labelTertiary,
                )
            }
            CompositionLoad.Missing -> Notice(stringResource(R.string.preset_comp_missing))
            CompositionLoad.Unsupported -> Notice(stringResource(R.string.preset_comp_unsupported))
            is CompositionLoad.Failed -> {
                Notice(stringResource(R.string.preset_comp_failed, current.message))
                FeatureAction(stringResource(R.string.preset_comp_retry)) { attempt++ }
            }
            is CompositionLoad.Ready -> CompositionBody(entry, current, onCopyAll = { copy(current.document.content) })
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = DsType.std14, color = DsTheme.colors.labelSecondary)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompositionBody(entry: AgentPresetEntry, ready: CompositionLoad.Ready, onCopyAll: () -> Unit) {
    val colors = DsTheme.colors
    val entries = ready.composition.entries
    // Counted once per document rather than on every recomposition.
    val counts = remember(ready.composition) {
        Triple(
            entries.size,
            entries.count { it.state is PresetEntryState.Conditional },
            entries.count { it.state is PresetEntryState.Disabled },
        )
    }
    DsCard {
        ready.document.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = DsType.std14, color = colors.labelSecondary)
        }
        Text(
            technicalDisplay(entry.id),
            style = DsType.mono12,
            color = colors.labelTertiary,
        )
        // The counts are words and numbers, so they read the same without colour.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            DsPill(text = stringResource(R.string.preset_comp_plugins, counts.first))
            if (counts.second > 0) DsPill(text = stringResource(R.string.preset_comp_conditional, counts.second))
            if (counts.third > 0) DsPill(text = stringResource(R.string.preset_comp_off, counts.third))
        }
    }
    FeatureAction(stringResource(R.string.preset_comp_copy_all), onClick = onCopyAll)
    if (entries.isEmpty()) {
        Text(
            stringResource(R.string.preset_comp_empty),
            style = DsType.std14,
            color = colors.labelSecondary,
        )
        // A document in a shape this build does not split is still shown whole.
        if (ready.composition.preamble.isNotBlank()) YamlBlock(ready.composition.preamble)
        return
    }
    Text(
        stringResource(R.string.preset_comp_raw),
        style = DsType.label12,
        color = colors.labelTertiary,
        modifier = Modifier.semantics { heading() },
    )
    entries.forEachIndexed { index, row -> EntryCard(index, row) }
    // Anything before the first entry (a comment) is part of the document too.
    if (ready.composition.preamble.isNotBlank()) YamlBlock(ready.composition.preamble)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryCard(index: Int, row: PresetEntry) {
    val colors = DsTheme.colors
    var expanded by remember(row.text) { mutableStateOf(false) }
    val title = row.id ?: row.module ?: stringResource(R.string.preset_comp_entry_unnamed, index + 1)
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Text(
                technicalDisplay(title),
                style = DsType.rowTitle,
                color = colors.labelPrimary,
            )
            DsPill(
                text = stringResource(
                    when (row.state) {
                        PresetEntryState.Enabled -> R.string.preset_comp_always_on
                        PresetEntryState.Disabled -> R.string.preset_comp_state_off
                        is PresetEntryState.Conditional -> R.string.preset_comp_state_conditional
                    },
                ),
                warn = row.state !is PresetEntryState.Enabled,
            )
        }
        // The module is the second line only when it is not already the title.
        row.module?.takeIf { it != title }?.let { module ->
            Text(
                technicalDisplay(module),
                style = DsType.mono12.copy(textDirection = TextDirection.Ltr),
                color = colors.labelTertiary,
            )
        }
        (row.state as? PresetEntryState.Conditional)?.let {
            Text(
                stringResource(R.string.preset_comp_condition, technicalDisplay(it.expression)),
                style = DsType.small13,
                color = colors.labelSecondary,
            )
        }
        DisclosureRow(
            title = stringResource(R.string.preset_comp_settings),
            expanded = expanded,
            onToggle = { expanded = !expanded },
        ) {
            YamlBlock(row.text, Modifier.padding(start = DsSpacing.medium))
        }
    }
}

/** The YAML as written: the start of a long one with "Show all", and Copy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YamlBlock(text: String, modifier: Modifier = Modifier) {
    var showAll by remember(text) { mutableStateOf(false) }
    val copy = rememberCopier()
    val shown = if (showAll) text else clipPreview(text)
    val clipped = !showAll && shown.length != text.length
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            DsTextAction(stringResource(R.string.common_copy), onClick = { copy(text) })
            if (clipped) {
                DsTextAction(stringResource(R.string.work_steps_show_all), onClick = { showAll = true })
            }
        }
        SelectionContainer {
            Text(
                shown,
                style = DsType.mono12.copy(textDirection = TextDirection.Ltr),
                color = DsTheme.colors.labelSecondary,
            )
        }
    }
}
