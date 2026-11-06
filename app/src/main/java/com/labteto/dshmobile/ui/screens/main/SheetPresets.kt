package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.AgentPresetListValue
import com.labteto.dshmobile.core.wire.dto.AgentPresetTrust
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * The agent-preset picker (design 14's "Agent preset" row, opened).
 *
 * A preset determines which tools an agent has, so the harness pins it for the life of a session
 * and refuses a change once work has started. Rather than surfacing that as a failure after the
 * fact, the rows go inert with an explanation whenever the session is no longer blank.
 */
@Composable
internal fun PresetsSheet(
    presets: AgentPresetListValue?,
    currentPreset: String?,
    sessionBlank: Boolean,
    store: SessionStore,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    DsBottomSheet(
        title = stringResource(R.string.presets_title),
        subtitle = if (sessionBlank) null else stringResource(R.string.presets_locked),
        onDismiss = {
            haptics.tap()
            onDismiss()
        },
    ) {
        val entries = presets?.presets.orEmpty()
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.presets_empty),
                style = DsType.small13,
                color = colors.labelTertiary,
            )
            return@DsBottomSheet
        }
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            entries.forEach { entry ->
                val selected = entry.id == currentPreset
                val selectable = sessionBlank && entry.broken == null
                val fill by animateColorAsState(
                    targetValue = if (selected) colors.accentTertiary else colors.bgModulePlatform,
                    animationSpec = DsAnimations.tabSwapColor,
                    label = "presetCardFill",
                )
                val stroke by animateColorAsState(
                    targetValue = if (selected) colors.accent else colors.borderL1,
                    animationSpec = DsAnimations.tabSwapColor,
                    label = "presetCardStroke",
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = DsShapes.block,
                    color = fill,
                    border = BorderStroke(1.dp, stroke),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = selectable) {
                                haptics.selection()
                                scope.launch {
                                    if (store.selectAgentPreset(entry.id)) onDismiss()
                                }
                            }
                            .heightIn(min = 60.dp)
                            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.displayName(),
                                    style = DsType.rowTitle,
                                    color = when {
                                        selected -> colors.accent
                                        selectable -> colors.labelPrimary
                                        else -> colors.labelTertiary
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                when (entry.trust) {
                                    AgentPresetTrust.SYSTEM -> R.string.presets_system
                                    AgentPresetTrust.USER -> R.string.presets_user
                                    // A tier this build cannot name is better left unlabelled than
                                    // mislabelled as something the person authored themselves.
                                    AgentPresetTrust.UNKNOWN -> null
                                }?.let { trustLabel ->
                                    Spacer(Modifier.width(DsSpacing.xsmall))
                                    DsPill(text = stringResource(trustLabel))
                                }
                                if (entry.isDefault) {
                                    Spacer(Modifier.width(DsSpacing.tiny))
                                    DsPill(text = stringResource(R.string.presets_default))
                                }
                            }
                            entry.displayDescription()?.let {
                                Text(
                                    it,
                                    style = DsType.small13,
                                    color = colors.labelTertiary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            entry.broken?.let {
                                Text(
                                    stringResource(R.string.presets_broken, it),
                                    style = DsType.caption11,
                                    color = colors.warnLabel,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (selected) {
                            Spacer(Modifier.width(DsSpacing.small))
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = stringResource(R.string.presets_current),
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
