package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme

/**
 * Centered empty state: whale mark, hero headline, optional subtitle, a mono
 * "Preview" pill, and suggestion chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyHero(
    headline: String,
    subtitle: String?,
    chips: List<String> = emptyList(),
    onChipClick: (String) -> Unit = {},
) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WhaleMark(Modifier.size(64.dp))
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
        Text(
            "Preview",
            style = DsType.xsmall12.copy(fontFamily = DsType.codeFont, color = colors.accent),
            color = colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(colors.accentTertiary)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        if (chips.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chips.forEach { chip ->
                    DsPill(text = chip, onClick = { onChipClick(chip) })
                }
            }
        }
    }
}

/** 64dp accentTertiary disc with the DeepSeek Mobile logo mark. */
/** The product mark on a tinted disc. Shared so a screen can use it without the whole hero. */
@Composable
internal fun WhaleMark(modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    Box(modifier.clip(CircleShape).background(colors.accentTertiary), contentAlignment = Alignment.Center) {
        // The launcher vector keeps the mark inside the adaptive-icon safe zone
        // (~46% of the 108dp canvas), so oversize the image to fill the disc.
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(100.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun EmptyHeroPreview() {
    DshTheme {
        EmptyHero(
            headline = "Nothing running yet",
            subtitle = "Ask the harness anything, or pick a suggestion below.",
            chips = listOf("Summarize this repo", "Run the test suite", "Explain a diff"),
            onChipClick = {},
        )
    }
}
