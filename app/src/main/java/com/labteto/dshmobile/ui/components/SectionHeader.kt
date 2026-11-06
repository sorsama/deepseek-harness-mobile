package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme

/**
 * Section label row — the kit's `.lbl`: uppercase, letter-spaced, tertiary, with an optional
 * right-aligned accent action.
 *
 * The action keeps a 36dp pill target a thumb can find: this is where "Scan network" and "Clear"
 * live, and an 11sp word with 4dp of padding is not a control.
 */
@Composable
fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            style = DsType.label12,
            color = colors.labelTertiary,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                action,
                style = DsType.small13Strong,
                color = colors.accent,
                modifier = if (onAction != null) {
                    Modifier
                        .clip(DsShapes.pillFull)
                        .clickable(onClick = onAction)
                        .heightIn(min = 36.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 12.dp)
                } else {
                    Modifier.padding(horizontal = 4.dp)
                },
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SectionHeaderPreview() {
    DshTheme(customization = com.labteto.dshmobile.ui.theme.ThemeCustomization.Default.copy(preference = com.labteto.dshmobile.ui.theme.ThemePreference.DARK)) {
        SectionHeader(title = "Recent", action = "Clear", onAction = {})
    }
}
