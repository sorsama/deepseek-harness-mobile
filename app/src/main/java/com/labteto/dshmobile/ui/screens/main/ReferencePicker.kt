package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.data.SessionStore
import com.labteto.dshmobile.data.HarnessFeature
import com.labteto.dshmobile.ui.components.*
import com.labteto.dshmobile.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

internal data class ReferencePick(val mention: String, val label: String, val kind: String, val target: String)
internal data class AtQuery(val start: Int, val end: Int, val query: String)
internal fun activeReference(text: String, cursor: Int): AtQuery? {
    val before = text.take(cursor.coerceIn(0, text.length))
    val match = Regex("(?:^|\\s)(@\"([^\"]*)|@([^\\s]*))$").find(before) ?: return null
    val token = match.groupValues[1]
    return AtQuery(before.length - token.length, before.length, match.groupValues[2].ifEmpty { match.groupValues[3] })
}
internal fun fileMention(path: String, directory: Boolean): String? {
    if (path.any { it.code < 32 || it.code in 127..159 || it == '"' }) return null
    val target = path.trimEnd('/') + if (directory) "/" else ""
    return if (target.any(Char::isWhitespace)) "@\"$target\"" else "@$target"
}

internal fun referenceKindIcon(kind: String) = when (kind) {
    "folder" -> FeatherIcons.Folder
    "session" -> FeatherIcons.MessageSquare
    else -> FeatherIcons.FileText
}

@Composable
internal fun referenceKindLabel(kind: String): String = stringResource(when (kind) {
    "folder" -> R.string.ux_b_folder
    "session" -> R.string.ux_b_session
    else -> R.string.ux_b_file
})

/**
 * The `@` reference picker (design 15's file list).
 *
 * The sheet filters the host's own file and session references as the token after `@` is typed.
 * Rows are the Ink file row: a glyph tile, the path in monospace (it is a path, not prose), and the
 * kind underneath; a folder carries a browse chevron that narrows the query instead of inserting
 * the mention.
 */
@Composable
internal fun ReferencePicker(store: SessionStore, composer: ComposerDraft, initial: AtQuery, onClose: () -> Unit) {
    val sessionsAvailable by store.sessionReferencesAvailable.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(initial.query) }
    var rows by remember { mutableStateOf<List<ReferencePick>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    fun replaceQuery(value: String) { query = value; rows = emptyList(); loading = true; failed = false }
    LaunchedEffect(query, composer.key, revision) {
        rows = emptyList(); loading = true; failed = false
        delay(180)
        val api = store.apiForHost(composer.key.host)
        if (api == null) { failed = true; loading = false; return@LaunchedEffect }
        try {
            // Each source is independent: a failed session lookup must not discard usable files.
            try {
                when (val files = api.fileReferencesList(composer.key.sessionId, query)) {
                    is RpcResult.Ok -> rows = (files.value as? JsonArray).orEmpty().mapNotNull { item ->
                        val obj = item as? JsonObject ?: return@mapNotNull null
                        val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val folder = obj["kind"]?.jsonPrimitive?.contentOrNull == "directory"
                        fileMention(path, folder)?.let { ReferencePick(it, path, if (folder) "folder" else "file", path) }
                    }
                    is RpcResult.Err -> failed = true
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
            if (store.sessionReferencesAvailable.value != false) {
                try {
                    when (val sessions = store.featureCall(HarnessFeature.SESSION_REFERENCES, api) {
                        api.sessionReferenceCandidates(composer.key.sessionId, query)
                    }) {
                        is RpcResult.Ok -> rows = rows + sessions.value.map {
                            ReferencePick(it.mention, it.displayTitle ?: it.label, "session", it.sessionId.orEmpty())
                        }
                        is RpcResult.Err -> if (sessions.error.classifyCapability().code != "capability-unavailable") failed = true
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
            }
        } finally { loading = false }
    }
    DsBottomSheet(
        title = stringResource(R.string.harness_references),
        onDismiss = {
            haptics.tap()
            onClose()
        },
        trailing = {
            DsIconButton(
                Icons.Filled.Close,
                stringResource(R.string.common_close),
                {
                    haptics.tap()
                    onClose()
                },
            )
        },
    ) {
        DsSearchField(query, ::replaceQuery, stringResource(R.string.common_search))
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.borderL1)
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            if (failed) DsCard {
                Text(stringResource(R.string.ux_b_reference_error), style = DsType.small13, color = colors.error)
                DsButton(stringResource(R.string.common_retry), { rows = emptyList(); loading = true; revision++ }, variant = DsButtonVariant.Outline)
            }
            val visible = rows.filter { it.kind != "session" || sessionsAvailable != false }
            if (!loading && !failed && visible.isEmpty()) EmptyHero(
                stringResource(if (query.isBlank()) R.string.ux_b_no_references else R.string.ux_b_no_matches), null,
            )
            visible.forEach { row ->
                val type = referenceKindLabel(row.kind)
                val selectLabel = stringResource(R.string.ux_b_select_reference, type, row.label)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).heightIn(min = DsSpacing.touchTarget)
                            .clip(DsShapes.row)
                            .clickable {
                                haptics.selection()
                                composer.insertReference(initial.start, initial.end, row.mention, row.label, row.kind, row.target)
                                onClose()
                            }
                            .semantics(mergeDescendants = true) { contentDescription = selectLabel }
                            .padding(horizontal = DsSpacing.xsmall, vertical = DsSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                    ) {
                        GlyphTile(referenceKindIcon(row.kind))
                        Column(Modifier.weight(1f)) {
                            Text(
                                technicalDisplay(row.label),
                                style = DsType.mono13,
                                color = colors.labelPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(type, style = DsType.caption11, color = colors.labelTertiary, maxLines = 1)
                        }
                    }
                    if (row.kind == "folder") {
                        DsIconButton(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            stringResource(R.string.ux_b_browse_folder, row.label),
                            { replaceQuery(row.target.trimEnd('/') + "/") },
                        )
                    }
                }
            }
        }
    }
}
