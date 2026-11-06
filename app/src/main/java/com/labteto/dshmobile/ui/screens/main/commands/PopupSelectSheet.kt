package com.labteto.dshmobile.ui.screens.main.commands

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.DsSearchField
import com.labteto.dshmobile.ui.screens.main.GlyphTile
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Resolve [UiText] against resources. */
@Composable
internal fun UiText.resolve(): String = when (this) {
    is UiText.Literal -> text
    is UiText.Res -> if (args.isEmpty()) {
        stringResource(id)
    } else {
        stringResource(id, *args.map { if (it is UiText) it.resolve() else it }.toTypedArray())
    }
    is UiText.Lines -> buildString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            append(line.resolve())
        }
    }
}

/** What a failed load or pick shows: the decoration's own wording, or the exception's message. */
private fun Exception.uiText(): UiText = (this as? CommandUiException)?.text ?: (message ?: toString()).ui()

/** The local search matches what a row says, as upstream's `filterOptions` does: label and detail. */
private fun SelectOption.matches(query: String): Boolean =
    listOfNotNull(label, detail).any { (it as? UiText.Literal)?.text?.lowercase()?.contains(query) == true }

/**
 * The shared popup-select shell: the phone's version of ui-commands' `PopupSelectController`
 * plus its overlay.
 *
 * One sheet serves every decoration. It loads the first step's rows through the spec, filters
 * them against a local search, and on a pick either closes (the step returned nothing) or shows
 * the next step. A step may carry its own loader, which replaces it when the data lands — that is
 * how rewind's mode step first shows "checking…" and then offers the file restore. Failures are
 * shown in place with a retry, never as a toast the user has to chase.
 *
 * The rows are the Ink sheet row: a bordered glyph, the option, its explanation under it, and the
 * badge (a candidate's time) in monospace at the trailing edge; hairlines keep a long list legible.
 */
@Composable
internal fun PopupSelectSheet(
    command: String,
    spec: CommandUiSpec.PopupSelect,
    context: CommandContext,
    /**
     * Opens on this step rather than on the spec's own list, for a flow entered from somewhere
     * that has already made the first pick, as holding a message makes rewind's.
     */
    initialStep: SelectStep? = null,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    var step by remember { mutableStateOf(initialStep) }
    var options by remember { mutableStateOf<List<SelectOption>>(emptyList()) }
    var note by remember { mutableStateOf<UiText?>(null) }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var search by remember { mutableStateOf("") }
    // Bumped by Retry, which restarts whichever load the open step owns.
    var revision by remember { mutableIntStateOf(0) }

    // First step: the spec's own options.
    LaunchedEffect(revision) {
        if (step != null) return@LaunchedEffect
        loading = true; error = null
        try {
            options = spec.options(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.uiText()
        } finally {
            loading = false
        }
    }
    // Later steps: rows are immediate; a loader, when present, swaps the step once it settles.
    LaunchedEffect(step, revision) {
        val current = step ?: return@LaunchedEffect
        options = current.options; note = current.note; search = ""
        val load = current.load ?: return@LaunchedEffect
        loading = true; error = null
        try {
            val next = load()
            if (step === current) { step = next.copy(load = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.uiText()
        } finally {
            loading = false
        }
    }

    // Single flight, as upstream's controller: the caller sets [submitting] before launching, so a
    // second tap landing before this coroutine starts cannot run a pick — a rewind — twice.
    suspend fun pick(option: SelectOption) {
        error = null
        try {
            // A step that answers null is done. It must not fall back to the first step's handler.
            val current = step
            val next = if (current != null) current.onSelect(option) else spec.onSelect(option, context)
            if (next == null) onDismiss() else step = next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.uiText()
        } finally {
            submitting = false
        }
    }

    val title = step?.title?.resolve() ?: "/$command"
    val visible = remember(options, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) options else options.filter { it.matches(q) }
    }
    val scope = rememberCoroutineScope()

    DsBottomSheet(
        title = title,
        onDismiss = {
            haptics.tap()
            onDismiss()
        },
        trailing = {
            DsIconButton(
                Icons.Filled.Close,
                stringResource(R.string.common_close),
                {
                    haptics.tap()
                    onDismiss()
                },
            )
        },
    ) {
        if (step == null && options.size > 8) DsSearchField(search, { search = it }, stringResource(R.string.common_search))
        if (loading || submitting) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.borderL1)
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            note?.let {
                Text(
                    it.resolve(),
                    style = DsType.small13,
                    color = colors.labelSecondary,
                    modifier = Modifier.padding(start = DsSpacing.tiny, top = DsSpacing.tiny),
                )
            }
            error?.let { message ->
                DsCard {
                    Text(message.resolve(), style = DsType.small13, color = colors.error)
                    DsButton(
                        stringResource(R.string.common_retry),
                        {
                            error = null
                            revision++
                        },
                        variant = DsButtonVariant.Outline,
                    )
                }
            }
            if (!loading && error == null && visible.isEmpty()) {
                val empty = spec.empty.takeIf { step == null && search.isBlank() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = DsSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    GlyphTile(FeatherIcons.Info)
                    Text(
                        empty?.resolve() ?: stringResource(R.string.command_popup_no_matches),
                        style = DsType.small13,
                        color = colors.labelTertiary,
                    )
                }
            }
            visible.forEachIndexed { index, option ->
                val clickable = option.enabled && !submitting
                Row(
                    Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget)
                        .clip(DsShapes.row)
                        .then(
                            if (clickable) {
                                Modifier.clickable {
                                    if (!submitting) {
                                        haptics.tap()
                                        submitting = true
                                        scope.launch { pick(option) }
                                    }
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = DsSpacing.tiny, vertical = DsSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                ) {
                    GlyphTile(FeatherIcons.Terminal)
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.label.resolve(),
                            style = DsType.std14Strong,
                            color = if (clickable) colors.labelPrimary else colors.labelTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        option.detail?.let {
                            Text(it.resolve(), style = DsType.caption11, color = colors.labelTertiary)
                        }
                    }
                    option.badge?.let {
                        Spacer(Modifier.width(DsSpacing.xsmall))
                        Text(it, style = DsType.mono12, color = colors.labelCaption, maxLines = 1)
                    }
                }
                if (index != visible.lastIndex) {
                    HorizontalDivider(
                        color = colors.borderL1,
                        modifier = Modifier.padding(start = 40.dp),
                    )
                }
            }
        }
    }
}
