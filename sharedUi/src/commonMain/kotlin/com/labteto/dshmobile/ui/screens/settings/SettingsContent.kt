package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.common_back
import com.labteto.dshmobile.shared.resources.settings_general
import com.labteto.dshmobile.shared.resources.settings_title
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import org.jetbrains.compose.resources.stringResource

/**
 * What the shared Settings page offers on the platform drawing it.
 *
 * Android keeps its own page — one screen with the Termux card, plugins, notifications, the update
 * checker and the data wipe — and calls [LanguageRow] and [AppearanceCard] in place, so this shell
 * is only what a platform without those cards needs. A row that cannot work is hidden here rather
 * than drawn dead: [language] is false on iOS until the shared resources can switch locale at
 * runtime (see docs/IOS.md).
 */
data class SettingsFeatures(
    val language: Boolean = true,
) {
    companion object {
        val Android = SettingsFeatures(language = true)
    }
}

/**
 * The Settings page, shared: a header, the language row and the appearance card.
 *
 * This is the iOS screen (and the shape Android's page has always had for these two blocks). Its
 * values come from [AppearanceSettings]; a change goes back through [onSet]. [hapticsPreview] is the
 * platform's own haptics panel, which needs a `Vibrator` on Android and is left empty on iOS.
 */
@Composable
fun SettingsContent(
    state: AppearanceSettings,
    onSet: ((AppearanceSettings) -> AppearanceSettings) -> Unit,
    onClose: () -> Unit,
    features: SettingsFeatures = SettingsFeatures(),
    hapticsPreview: @Composable () -> Unit = {},
) {
    val colors = DsTheme.colors
    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DsIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                    onClick = onClose,
                    tint = colors.labelPrimary,
                )
                Spacer(Modifier.width(DsSpacing.small))
                Text(
                    stringResource(Res.string.settings_title),
                    style = DsType.display24,
                    color = colors.labelPrimary,
                )
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
            ) {
                if (features.language) {
                    SettingsCard(stringResource(Res.string.settings_general)) {
                        LanguageRow(state, onSelect = { tag ->
                            onSet { it.copy(localeOverride = tag) }
                        })
                    }
                }
                AppearanceCard(state, onSet = onSet, hapticsPreview = hapticsPreview)
            }
        }
    }
}

