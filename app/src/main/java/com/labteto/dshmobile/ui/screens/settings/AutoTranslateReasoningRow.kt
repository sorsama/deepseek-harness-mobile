package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.translationLimits
import com.labteto.dshmobile.translation.canTranslate
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.rememberReasoningTranslationEngine
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException

/** What Settings knows about translation on the host the app is connected to. */
internal enum class TranslationAvailability { Checking, NoHost, Available, Unavailable }

/**
 * The switch for automatic reasoning translation, with its cost stated and, when the connected host
 * cannot translate, a line saying so. The check is the manual Translate button's own
 * ([canTranslate] on `cotTranslation/limits`), and it is the same cached answer the transcript uses,
 * so opening Settings sends at most one probe.
 */
@Composable
internal fun AutoTranslateReasoningRow(checked: Boolean, onToggle: () -> Unit) {
    val store = rememberSessionStore()
    val engine = rememberReasoningTranslationEngine()
    val connection by store.connectionState.collectAsStateWithLifecycle()
    val phase = connection.phase
    var availability by remember { mutableStateOf(TranslationAvailability.Checking) }
    LaunchedEffect(phase) {
        val host = store.activeHostKey
        val client = store.apiForHost(host)
        if (host == null || client == null) {
            availability = TranslationAvailability.NoHost
            return@LaunchedEffect
        }
        availability = TranslationAvailability.Checking
        val limits = engine.limits(host) {
            try { (client.translationLimits() as? RpcResult.Ok)?.value }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { null }
        }
        availability = if (limits?.canTranslate() == true) TranslationAvailability.Available else TranslationAvailability.Unavailable
    }
    ToggleRow(
        label = stringResource(R.string.settings_auto_translate_reasoning),
        checked = checked,
        hint = stringResource(R.string.settings_auto_translate_reasoning_hint),
        onChange = onToggle,
    )
    val notice = when (availability) {
        TranslationAvailability.NoHost -> R.string.settings_auto_translate_reasoning_no_host
        TranslationAvailability.Unavailable -> R.string.settings_auto_translate_reasoning_unavailable
        else -> null
    }
    if (notice != null) {
        Text(stringResource(notice), style = DsType.caption11, color = DsTheme.colors.warnText)
    }
}
