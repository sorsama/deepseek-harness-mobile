package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsSearchField
import com.labteto.dshmobile.core.wire.dto.ModelCatalogModel
import com.labteto.dshmobile.core.wire.dto.SessionModelsValue
import com.labteto.dshmobile.data.ActionOutcome
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.data.failureNotice
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsSegment
import com.labteto.dshmobile.ui.components.DsSegmented
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * Model picker, grouped by provider (design 14's "Model" sheet).
 *
 * Two decisions live here, and they are not peers: which model, and — for a model that reasons —
 * how hard. The chosen card carries the accent border and a filled check, and the effort control
 * sits inside it, so the tiers belong visibly to the model they configure rather than floating
 * under every row.
 */
@Composable
internal fun ModelsSheet(
    models: SessionModelsValue?,
    store: SessionStore,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var query by remember { mutableStateOf("") }
    // The model the sheet is switching to, from tap until the host answers, and why the last switch
    // did not go through. Both are drawn here: the sheet sits over the chat's banner.
    var pendingKey by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val switchFailedLabel = stringResource(R.string.models_switch_failed)
    fun choose(provider: String, model: String, effort: String?) {
        if (pendingKey != null) return
        pendingKey = "$provider/$model"
        failure = null
        scope.launch {
            try {
                val outcome = store.selectModel(provider, model, effort, report = false)
                if (outcome is ActionOutcome.Failed) failure = failureNotice(switchFailedLabel, outcome.message)
            } finally {
                pendingKey = null
            }
        }
    }
    DsBottomSheet(
        title = stringResource(R.string.ink_panel_model_title),
        onDismiss = {
            haptics.tap()
            onDismiss()
        },
        trailing = {
            DsButton(
                text = stringResource(R.string.common_done),
                onClick = {
                    haptics.tap()
                    onDismiss()
                },
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        },
    ) {
        if (models == null) {
            Text(stringResource(R.string.common_loading), style = DsType.std14, color = colors.labelTertiary)
            return@DsBottomSheet
        }
        if (models.groups.sumOf { it.models.size } > 4) DsSearchField(query, { query = it }, stringResource(R.string.common_search))
        if (models.groups.none { group -> group.models.any { modelSearchMatches(query, "${group.id} ${group.name} ${it.id} ${it.name}") } }) {
            Text(stringResource(R.string.ux_b_no_models), style = DsType.small13, color = colors.labelSecondary)
        }
        val current = models.current
        if (!models.routable) {
            Text(
                stringResource(R.string.models_unroutable),
                style = DsType.small13,
                color = colors.warnText,
            )
        }
        failure?.let {
            Text(it, style = DsType.small13, color = colors.error)
        }
        Column(
            modifier = Modifier
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            val multiProvider = models.groups.count { group -> group.models.any { modelSearchMatches(query, "${group.id} ${group.name} ${it.id} ${it.name}") } } > 1
            models.groups.forEach { group ->
                val matching = group.models.filter { modelSearchMatches(query, "${group.id} ${group.name} ${it.id} ${it.name}") }
                if (matching.isEmpty()) return@forEach
                // The provider recedes: it names the shelf, and the models on it are the choice.
                // With one provider the heading would only repeat the sheet title.
                if (multiProvider) {
                    Text(
                        group.name,
                        style = DsType.label12,
                        color = colors.labelTertiary,
                        modifier = Modifier.padding(
                            start = DsSpacing.tiny,
                            top = DsSpacing.small,
                            bottom = DsSpacing.tiny,
                        ),
                    )
                }
                matching.forEach { model ->
                    val isCurrent = current.provider == group.id && current.model == model.id
                    // A selection recorded without an effort runs on the adapter's default. The
                    // host's own picker resolves that default when a model is chosen and shows it
                    // as the current tier; an app that sent nothing and drew nothing would show a
                    // reasoning model with no tier at all after a switch made here.
                    val effectiveEffort = current.reasoningEffort ?: model.reasoning?.defaultEffort
                    val switching = pendingKey == "${group.id}/${model.id}"
                    ModelCard(
                        model = model,
                        selected = isCurrent,
                        selectedEffort = effectiveEffort.takeIf { isCurrent },
                        providerName = group.name,
                        busy = pendingKey != null,
                        switching = switching,
                        onSelect = {
                            // Tapping the model already in use must not re-send it: the host
                            // would put the effort back to the model's default.
                            if (modelPickAllowed(isCurrent, pendingKey != null)) {
                                haptics.selection()
                                choose(group.id, model.id, model.reasoning?.defaultEffort)
                            }
                        },
                        onSelectEffort = { effort ->
                            if (effortPickAllowed(effort, effectiveEffort, pendingKey != null)) {
                                haptics.selection()
                                choose(group.id, model.id, effort)
                            }
                        },
                    )
                }
            }
            models.failures.forEach { failure ->
                Text(
                    stringResource(R.string.err_model_unavailable, "${failure.name}: ${failure.message}"),
                    style = DsType.caption11,
                    color = colors.warnText,
                    modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = DsSpacing.tiny),
                )
            }
        }
    }
}

/**
 * One model as a card: name, the harness's own one-line blurb, and — once it is the live one — the
 * reasoning tiers beneath. The card state animates, so picking a model slides the tint across
 * rather than blinking a different surface into existence.
 */
@Composable
private fun ModelCard(
    model: ModelCatalogModel,
    selected: Boolean,
    selectedEffort: String?,
    providerName: String,
    /** A switch is in flight somewhere in the list; every card holds still until it lands. */
    busy: Boolean,
    /** This card is the one being switched to, so it shows progress where the check will go. */
    switching: Boolean,
    onSelect: () -> Unit,
    onSelectEffort: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val efforts = model.reasoning?.efforts.orEmpty()
    val fill by animateColorAsState(
        targetValue = if (selected) colors.accentTertiary else colors.bgModulePlatform,
        animationSpec = DsAnimations.tabSwapColor,
        label = "modelCardFill",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.borderL1,
        animationSpec = DsAnimations.tabSwapColor,
        label = "modelCardStroke",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = fill,
        border = BorderStroke(1.dp, stroke),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // `selectable` rather than `clickable`: the tint carries the state visually,
                    // so the selection has to reach assistive tech some other way.
                    .selectable(selected = selected, enabled = !busy, role = Role.RadioButton, onClick = onSelect)
                    .heightIn(min = 60.dp)
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.name,
                        style = DsType.rowTitle,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        modelDescription(model, selected, providerName),
                        style = DsType.small13,
                        color = colors.labelTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(DsSpacing.small))
                if (switching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                } else {
                    SelectedBadge(selected = selected)
                }
            }
            if (selected && efforts.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(
                        start = DsSpacing.medium,
                        end = DsSpacing.medium,
                        bottom = DsSpacing.medium,
                    ),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Text(
                        stringResource(R.string.models_reasoning_effort).uppercase(),
                        style = DsType.label12,
                        color = colors.labelTertiary,
                    )
                    DsSegmented(
                        segments = efforts.map { DsSegment(it.id, it.name) },
                        selectedKey = selectedEffort,
                        onSelect = onSelectEffort,
                        enabled = !busy,
                        stretch = true,
                        minSegmentHeight = 36.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** The design's filled accent check: a tick in a circle, not a stranded glyph at the far edge. */
@Composable
private fun SelectedBadge(selected: Boolean) {
    val colors = DsTheme.colors
    val fill by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.bgModulePlatform,
        animationSpec = DsAnimations.tabSwapColor,
        label = "modelBadgeFill",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.borderL3,
        animationSpec = DsAnimations.tabSwapColor,
        label = "modelBadgeStroke",
    )
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, stroke, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.models_current),
                tint = colors.onAccent,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** The harness's own blurb when it sent one, else a truthful one-line state. */
@Composable
private fun modelDescription(model: ModelCatalogModel, selected: Boolean, providerName: String): String {
    model.description?.takeIf { it.isNotBlank() }?.let { return it }
    return if (selected) stringResource(R.string.ink_panel_model_default)
    else stringResource(R.string.ink_panel_model_available, providerName)
}

/** Whether a tap on a model card should switch models: not the one in use, and not mid-switch. */
internal fun modelPickAllowed(isCurrent: Boolean, busy: Boolean): Boolean = !isCurrent && !busy

/** Whether a tap on an effort tier should send: a different tier from the one in force, and not mid-switch. */
internal fun effortPickAllowed(picked: String, inForce: String?, busy: Boolean): Boolean =
    !busy && picked != inForce

internal fun modelSearchMatches(query: String, text: String): Boolean = query.lowercase().split(Regex("\\s+")).all { word ->
    var index = 0
    text.lowercase().forEach { if (index < word.length && it == word[index]) index++ }
    index == word.length
}
