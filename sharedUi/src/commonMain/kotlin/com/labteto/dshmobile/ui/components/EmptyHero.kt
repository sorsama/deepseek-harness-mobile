package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme

/**
 * Centred empty state: mascot, headline, optional subtitle, an optional mono "Preview" pill, and
 * suggestion chips. The kit's empty screens are quiet — the mascot does the talking.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyHero(
    headline: String,
    subtitle: String?,
    chips: List<String> = emptyList(),
    onChipClick: (String) -> Unit = {},
    showPreview: Boolean = false,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    // A short fade-and-rise on arrival, so an empty screen settles instead of appearing in a frame.
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrival.animateTo(1f, tween(380, easing = DsAnimations.inkDecelerate)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = arrival.value
                translationY = (1f - arrival.value) * 12.dp.toPx()
            }
            .padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MascotMark(size = 72.dp)
        Text(
            headline,
            style = DsType.hero26,
            color = colors.labelPrimary,
            textAlign = TextAlign.Center,
        )
        subtitle?.let {
            Text(
                it,
                style = DsType.base16,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (showPreview) {
            Text(
                "Preview",
                style = DsType.mono12.copy(color = colors.accent),
                modifier = Modifier
                    .clip(DsShapes.chip)
                    .background(colors.accentTertiary)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (chips.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chips.forEach { chip ->
                    DsPill(text = chip, onClick = {
                        haptics.tap()
                        onChipClick(chip)
                    })
                }
            }
        }
    }
}

/**
 * The product mark on a tinted disc — kept for call sites that predate [MascotMark].
 * @see MascotMark
 */
@Composable
internal fun WhaleMark(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        MascotMark(size = 56.dp)
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun EmptyHeroPreview() {
    DshTheme(customization = com.labteto.dshmobile.ui.theme.ThemeCustomization.Default.copy(preference = com.labteto.dshmobile.ui.theme.ThemePreference.DARK)) {
        EmptyHero(
            headline = "Nothing running yet",
            subtitle = "Ask the harness anything, or pick a suggestion below.",
            chips = listOf("Summarize this repo", "Run the test suite", "Explain a diff"),
            onChipClick = {},
        )
    }
}
