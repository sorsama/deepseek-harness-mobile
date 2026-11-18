package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.screens.main.DictationChoices
import com.labteto.dshmobile.ui.screens.main.dictationLanguageLabel
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSizes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.voice.isKnownDictationChoice
import com.labteto.dshmobile.ui.voice.speechRecognitionAvailable

/**
 * The dictation switch and its language, in Settings, Chat.
 *
 * On a phone with no speech recognition service the switch is off and cannot be turned on, and the
 * line under it says why: the microphone is not in the message box, and nothing here would bring
 * it. Many phones without Google's services are like that.
 */
@Composable
internal fun DictationSettingsRows(settings: AppSettings, onSet: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val available = remember(context) { speechRecognitionAvailable(context) }
    ToggleRow(
        label = stringResource(R.string.voice_setting_title),
        checked = available && settings.dictationEnabled,
        hint = stringResource(if (available) R.string.voice_setting_hint else R.string.voice_unavailable_hint),
        enabled = available,
        onChange = { onSet { it.copy(dictationEnabled = !it.dictationEnabled) } },
    )
    if (available && settings.dictationEnabled) {
        DictationLanguageRow(settings.dictationLanguage.takeIf { isKnownDictationChoice(it) }) { choice ->
            onSet { it.copy(dictationLanguage = choice) }
        }
    }
}

/** The same dropdown as the app's own language row, for the language dictation listens for. */
@Composable
private fun DictationLanguageRow(current: String?, onSelect: (String?) -> Unit) {
    val colors = DsTheme.colors
    val labels = DictationChoices.associateWith { dictationLanguageLabel(it) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Text(
            stringResource(R.string.voice_language_title),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        DsMenu(
            anchorShape = DsShapes.control,
            anchor = {
                Row(
                    modifier = Modifier
                        .height(DsSizes.buttonCompact)
                        .widthIn(max = 200.dp)
                        .clip(DsShapes.control)
                        .background(colors.bgLayer2)
                        .border(1.dp, colors.borderL2, DsShapes.control)
                        .padding(horizontal = DsSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    Text(
                        labels[current].orEmpty(),
                        style = DsType.small13,
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = colors.labelSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            },
            items = DictationChoices.map { choice ->
                MenuItem(
                    text = labels[choice].orEmpty(),
                    icon = Icons.Filled.Check.takeIf { choice == current },
                ) { onSelect(choice) }
            },
        )
    }
}
