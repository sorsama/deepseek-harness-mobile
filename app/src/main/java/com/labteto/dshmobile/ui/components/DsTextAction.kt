package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * A text-only action in the accent colour with a full 48dp touch target.
 *
 * For the small actions that sit beside content ("Copy", "Show all", "Show raw JSON"), where a filled
 * button would be too loud. The label wraps rather than clips, so it holds at a large font scale and
 * in a language whose word is long.
 */
@Composable
fun DsTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * What a screen reader says instead of [label] when the label alone does not say which item the
     * action belongs to (the same "View contents" on every row of a list).
     */
    contentDescription: String? = null,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Box(
        modifier = modifier
            .heightIn(min = DsSpacing.touchTarget)
            .widthIn(min = DsSpacing.touchTarget)
            .clip(DsShapes.pill)
            .clickable(enabled = enabled, role = Role.Button) {
                haptics.tap()
                onClick()
            }
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = DsSpacing.small),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = DsType.small13Strong,
            color = if (enabled) colors.accent else colors.labelDimmed,
            textAlign = TextAlign.Center,
            modifier = if (contentDescription != null) Modifier.clearAndSetSemantics {} else Modifier,
        )
    }
}
