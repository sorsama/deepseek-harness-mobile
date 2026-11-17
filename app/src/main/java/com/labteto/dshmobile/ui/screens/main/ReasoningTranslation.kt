package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.ui.components.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.translation.TranslationKey
import com.labteto.dshmobile.translation.canTranslate
import com.labteto.dshmobile.translation.looksAlreadyInTarget
import com.labteto.dshmobile.translation.resolveTargetLanguage
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.media.LocalAttachmentScope
import com.labteto.dshmobile.ui.rememberReasoningTranslationEngine
import com.labteto.dshmobile.ui.rememberSessionStore
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Whether Settings has automatic reasoning translation on. Provided once by the chat screen so the
 * transcript and the subagent sheet read one value; reasoning blocks never read the setting store
 * themselves.
 */
internal val LocalAutoTranslateReasoning = staticCompositionLocalOf { false }

/**
 * The body of an open reasoning block: the text, and the translation of it.
 *
 * This is composed only while the block is open and on screen. The transcript is a lazy list and
 * the thinking panel composes its body only when open, so a block that is folded, collapsed or
 * scrolled away is not in composition and nothing here runs for it. That is what keeps automatic
 * translation to what the reader can see: it starts a moment after the block settles on screen
 * and is cancelled the moment the block leaves it. The collapsed row's preview line is not part of
 * this and always shows the original text; see `firstLinePreview`.
 *
 * Source stays visible until a request completes; absent translation adds no controls.
 *
 * @param complete whether the thought is finished. A thought still being written is never sent.
 * @param blockId the block's stable identity in its session, for the translation cache.
 */
@Composable
internal fun ReasoningContent(text: String, complete: Boolean, blockId: String) {
    val owner = LocalAttachmentScope.current
    key(owner, text) { ReasoningContentState(text, complete, blockId) }
}

@Composable
private fun ReasoningContentState(text: String, complete: Boolean, blockId: String) {
    val store = rememberSessionStore()
    val engine = rememberReasoningTranslationEngine()
    val owner = LocalAttachmentScope.current
    val auto = LocalAutoTranslateReasoning.current
    // The client the block talks through. It is looked up again when the connection comes back, so a
    // block opened before a reconnect does not keep a closed client.
    val connection by store.connectionState.collectAsStateWithLifecycle()
    val phase = connection.phase
    val api = remember(owner, phase) { store.apiForHost(owner?.first) }
    val scope = rememberCoroutineScope()
    val haptics = rememberDsHaptics()
    val colors = DsTheme.colors
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    var limits by remember(owner, api) { mutableStateOf<TranslationLimits?>(null) }
    LaunchedEffect(api, owner) {
        val client = api
        val host = owner?.first
        limits = if (client == null || host == null) null else engine.limits(host) {
            try { (client.translationLimits() as? RpcResult.Ok)?.value }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { null }
        }
    }
    val metadata = limits
    val usable = complete && api != null && owner != null && metadata != null && metadata.canTranslate() && text.isNotBlank()
    // One key for the block as manual and automatic translation both see it.
    val translationKey = if (usable && owner != null && metadata != null) {
        remember(owner, blockId, text, metadata.preferences.provider, metadata.preferences.targetLanguage, language) {
            TranslationKey.of(
                host = owner.first,
                session = owner.second,
                block = blockId,
                targetLanguage = resolveTargetLanguage(metadata.preferences.targetLanguage, language),
                provider = metadata.preferences.provider,
                text = text,
            )
        }
    } else null

    var translated by remember(translationKey) { mutableStateOf(translationKey?.let(engine::peek)) }
    var showTranslation by remember(translationKey) { mutableStateOf(false) }
    var busy by remember(translationKey) { mutableStateOf(false) }
    var failed by remember(translationKey) { mutableStateOf(translationKey?.let(engine::hasFailed) ?: false) }

    // The request itself: the fragments of the thought, one after another, in one slot.
    suspend fun fetch(client: DshApiClient, session: String, meta: TranslationLimits, target: String): String {
        val parts = mutableListOf<String>()
        // Debug builds only, and only the block's identity: lets a person count what the app sends.
        if (com.labteto.dshmobile.BuildConfig.DEBUG) android.util.Log.i("DshTranslate", "request block=$blockId target=$target")
        for (fragment in translationFragments(text, meta.maxTextChars)) {
            parts += client.translateReasoning(session, fragment, target, meta.preferences.provider).featureValue()
        }
        return parts.joinToString("")
    }

    // Automatic translation. Keyed on the switch, so turning it off cancels this and with it any
    // request nobody else is waiting for; a block that left the screen has left composition.
    LaunchedEffect(translationKey, auto) {
        val k = translationKey ?: return@LaunchedEffect
        val client = api ?: return@LaunchedEffect
        val o = owner ?: return@LaunchedEffect
        val meta = metadata ?: return@LaunchedEffect
        engine.awaitReady()
        engine.peek(k)?.let {
            translated = it
            if (auto) showTranslation = true
            return@LaunchedEffect
        }
        if (!auto || failed) return@LaunchedEffect
        if (looksAlreadyInTarget(text, k.targetLanguage)) return@LaunchedEffect
        try {
            val result = engine.translateAfterRest(k, onRequest = { busy = true }) {
                fetch(client, o.second, meta, k.targetLanguage)
            }
            if (result != null) {
                translated = result
                showTranslation = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        } finally {
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        SelectionContainer {
            Text((if (showTranslation) translated ?: text else text).trimEnd(),
                style = DsType.small13,
                color = colors.labelTertiary, softWrap = true)
        }
        if (translationKey != null && api != null && owner != null && metadata != null) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    when {
                        busy -> stringResource(R.string.common_loading)
                        failed && translated == null -> stringResource(R.string.common_retry)
                        showTranslation -> stringResource(R.string.alpha2_original)
                        else -> stringResource(R.string.alpha2_translate, translationProviderLabel(metadata.preferences.provider))
                    },
                    style = DsType.small13Strong,
                    color = if (busy) colors.labelTertiary else colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(DsShapes.pill)
                        .clickable(enabled = !busy, role = Role.Button) {
                            val cached = translated ?: engine.peek(translationKey)
                            if (cached != null) {
                                // Translated already, by this block earlier or by the automatic pass:
                                // switching is instant and sends nothing.
                                haptics.selection()
                                translated = cached
                                showTranslation = !showTranslation
                            } else {
                                haptics.tap()
                                scope.launch {
                                    busy = true; failed = false
                                    try {
                                        translated = engine.translate(translationKey) {
                                            fetch(api, owner.second, metadata, translationKey.targetLanguage)
                                        }
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
            if (failed && translated == null) {
                Text(stringResource(R.string.reasoning_translation_failed), color = colors.error, style = DsType.caption11)
            }
        }
    }
}
