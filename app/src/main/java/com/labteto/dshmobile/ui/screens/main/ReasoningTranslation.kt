package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.media.LocalAttachmentScope
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Source stays visible until an explicit request completes; absent plugins add no controls. */
@Composable
internal fun ReasoningContent(text: String, streaming: Boolean) {
    val owner = LocalAttachmentScope.current
    key(owner, text) { ReasoningContentState(text, streaming) }
}

@Composable
private fun ReasoningContentState(text: String, streaming: Boolean) {
    val store = rememberSessionStore()
    val owner = LocalAttachmentScope.current
    val api = remember(owner) { store.apiForHost(owner?.first) }
    val scope = rememberCoroutineScope()
    val haptics = rememberDsHaptics()
    val colors = DsTheme.colors
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    var limits by remember(owner) { mutableStateOf<TranslationLimits?>(null) }
    var translated by remember(owner, text) { mutableStateOf<String?>(null) }
    var showTranslation by remember(owner, text) { mutableStateOf(false) }
    var busy by remember(owner, text) { mutableStateOf(false) }
    var failed by remember(owner, text) { mutableStateOf(false) }
    LaunchedEffect(api) {
        try { limits = (api?.translationLimits() as? RpcResult.Ok)?.value }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { limits = null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        SelectionContainer {
            Text((if (showTranslation) translated ?: text else text).trimEnd(),
                style = DsType.small13,
                color = colors.labelTertiary, softWrap = true)
        }
        val metadata = limits
        if (!streaming && api != null && owner != null && metadata != null &&
            metadata.maxTextChars >= 2 && metadata.preferences.provider in metadata.availableProviders && text.isNotBlank()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    if (busy) stringResource(R.string.common_loading) else if (showTranslation)
                        stringResource(R.string.alpha2_original) else stringResource(R.string.alpha2_translate, metadata.preferences.provider),
                    style = DsType.small13Strong,
                    color = if (busy) colors.labelTertiary else colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(DsShapes.pill)
                        .clickable(enabled = !busy, role = Role.Button) {
                            if (translated != null) {
                                haptics.selection()
                                showTranslation = !showTranslation
                            } else {
                                haptics.tap()
                                scope.launch {
                                    busy = true; failed = false
                                    try {
                                        val target = metadata.preferences.targetLanguage.takeUnless { it == "auto" } ?: language
                                        val parts = mutableListOf<String>()
                                        for (fragment in translationFragments(text, metadata.maxTextChars)) {
                                            parts += api.translateReasoning(owner.second, fragment, target, metadata.preferences.provider).featureValue()
                                        }
                                        translated = parts.joinToString("")
                                        showTranslation = true
                                        haptics.confirm()
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { failed = true; haptics.reject() }
                                    finally { busy = false }
                                }
                            }
                        }
                        .heightIn(min = 32.dp)
                        .padding(horizontal = DsSpacing.small),
                )
            }
            if (failed) Text(stringResource(R.string.common_error), color = colors.error, style = DsType.caption11)
        }
    }
}
