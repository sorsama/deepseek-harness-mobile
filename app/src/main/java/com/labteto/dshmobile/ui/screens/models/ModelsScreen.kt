package com.labteto.dshmobile.ui.screens.models

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.models.EditorResult
import com.labteto.dshmobile.models.EditorWork
import com.labteto.dshmobile.models.KeyProblem
import com.labteto.dshmobile.models.ModelsController
import com.labteto.dshmobile.models.ModelsNotice
import com.labteto.dshmobile.models.ModelsPhase
import com.labteto.dshmobile.models.ModelsState
import com.labteto.dshmobile.models.ModelsTarget
import com.labteto.dshmobile.models.PendingSend
import com.labteto.dshmobile.models.ProviderFamily
import com.labteto.dshmobile.models.ProviderRow
import com.labteto.dshmobile.models.ProviderStatus
import com.labteto.dshmobile.models.baseUrlLooksValid
import com.labteto.dshmobile.models.keyProblemOf
import com.labteto.dshmobile.models.status
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.DsFullScreenDialog
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.DsSearchField
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.NoCopyTextToolbar
import com.labteto.dshmobile.ui.components.SecureContent
import com.labteto.dshmobile.ui.components.SectionHeader
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.clickable
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.screens.main.technicalDisplay
import com.labteto.dshmobile.ui.screens.runtime.DialogButtons
import com.labteto.dshmobile.ui.screens.runtime.rememberPageVisibility
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * Models and API keys: the providers the harness knows, whether each can answer, and a key field
 * for each. Everything is written to the harness and kept there; see [ModelsController].
 *
 * The page is its own window with `FLAG_SECURE` ([SecureContent]), so a key on screen is not in
 * the recents thumbnail or a screenshot. The key's text lives only in this composition: never in
 * a saved-state bundle, the controller's state, a log line or the clipboard.
 *
 * [target] is resolved once when the page opens: the connected harness, or the built-in one
 * reached on its own before the app connects to it.
 */
@Composable
fun ModelsScreen(
    target: suspend () -> ModelsTarget?,
    onClose: () -> Unit,
    onChanged: () -> Unit = {},
) {
    val page = rememberPageVisibility(onClose)
    val scope = rememberCoroutineScope()
    var controller by remember { mutableStateOf<ModelsController?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    var hostLabel by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val resolved = target()
        if (resolved == null) {
            unreachable = true
        } else {
            hostLabel = resolved.hostLabel
            controller = ModelsController(resolved.port, scope, resolved.cleartext, onChanged = onChanged).also { it.load() }
        }
    }

    DsFullScreenDialog(onDismissRequest = page::hide, visibleState = page.state, onWindowReady = page::show) {
        SecureContent()
        val colors = DsTheme.colors
        val toast = rememberDsToast()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                onClick = page::hide,
                tint = colors.labelPrimary,
            )
            Spacer(Modifier.width(DsSpacing.small))
            Text(
                stringResource(R.string.models_keys_title),
                style = DsType.title17,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DsSpacing.comfortable),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(bottom = DsSpacing.xlarge),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
            ) {
                val c = controller
                when {
                    unreachable -> Notice(stringResource(R.string.common_offline), warning = true)
                    c == null -> Loading()
                    else -> {
                        val state by c.state.collectAsState()
                        ModelsBody(state, c, hostLabel, toast.second)
                    }
                }
            }
        }
        DsToastHost(toast, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ModelsBody(state: ModelsState, controller: ModelsController, hostLabel: String, toast: (String) -> Unit) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    Text(stringResource(R.string.models_intro), style = DsType.std14, color = colors.labelSecondary)
    if (state.cleartext) Notice(stringResource(R.string.models_cleartext_plate), warning = true)

    // A save, a removal: said once, felt once.
    val noticeText = state.notice?.let { noticeText(it) }
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            haptics.confirm()
            toast(noticeText)
            controller.dismissNotice()
        }
    }

    when (val phase = state.phase) {
        ModelsPhase.Loading -> Loading()
        ModelsPhase.Unsupported -> Notice(stringResource(R.string.models_unsupported), warning = false)
        is ModelsPhase.Failed -> {
            Notice(stringResource(R.string.models_load_failed), warning = true, detail = phase.message)
            DsButton(stringResource(R.string.common_retry), onClick = controller::load, variant = DsButtonVariant.Outline)
        }
        ModelsPhase.Ready -> {
            val directory = state.directory ?: return
            if (!directory.writable) Notice(stringResource(R.string.models_read_only), warning = false)
            directory.credentialError?.let { Notice(stringResource(R.string.models_credentials_unknown), warning = true, detail = it) }
            if (directory.rows.isEmpty()) Notice(stringResource(R.string.models_empty), warning = false)
            if (directory.yours.isNotEmpty()) {
                SectionHeader(stringResource(R.string.models_section_yours))
                directory.yours.forEach { row -> ProviderCard(row, state, controller, hostLabel) }
            }
            if (directory.addable.isNotEmpty()) {
                SectionHeader(stringResource(R.string.models_section_add))
                Text(stringResource(R.string.models_add_hint), style = DsType.small13, color = colors.labelTertiary)
                var query by remember { mutableStateOf("") }
                if (directory.addable.size > 8) DsSearchField(query, { query = it }, stringResource(R.string.common_search))
                val shown = directory.addable
                    .filter { query.isBlank() || it.displayName.contains(query.trim(), ignoreCase = true) || it.provider.contains(query.trim(), ignoreCase = true) }
                // One plate for the whole catalog: a card per entry made a long list of identical boxes.
                if (shown.isNotEmpty()) Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = DsShapes.block,
                    color = DsTheme.colors.bgLayer1,
                    border = BorderStroke(1.dp, DsTheme.colors.borderL2),
                ) {
                    Column {
                        shown.forEachIndexed { index, row ->
                            if (index > 0) androidx.compose.material3.HorizontalDivider(color = DsTheme.colors.borderL1, thickness = 1.dp)
                            ProviderCard(row, state, controller, hostLabel, grouped = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun noticeText(notice: ModelsNotice): String = when (notice) {
    is ModelsNotice.Saved -> stringResource(if (notice.restartNeeded) R.string.models_saved_restart else R.string.models_saved, notice.displayName)
    is ModelsNotice.KeyRemoved -> stringResource(R.string.models_key_removed, notice.displayName)
    is ModelsNotice.ProviderRemoved -> stringResource(R.string.models_provider_removed, notice.displayName)
}

/** One provider: its state in a line, and, once opened, its editor in the same card. */
@Composable
private fun ProviderCard(row: ProviderRow, state: ModelsState, controller: ModelsController, hostLabel: String, grouped: Boolean = false) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val open = state.editor?.provider == row.provider
    val status = row.status
    val statusText = statusText(status)
    val canOpen = !row.isAccount && row.settingsNs.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(DsAnimations.expand),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = if (grouped) androidx.compose.ui.graphics.RectangleShape else DsShapes.block,
            color = if (grouped && !open) androidx.compose.ui.graphics.Color.Transparent else colors.bgLayer1,
            border = if (grouped) null else BorderStroke(1.dp, if (open) colors.accent else colors.borderL2),
        ) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (grouped) DsSpacing.touchTarget else DsSpacing.touchTarget + DsSpacing.small)
                        .let { base ->
                            if (!canOpen) base else base.clickable(role = Role.Button, onClickLabel = row.displayName) {
                                haptics.tap()
                                if (open) controller.close() else controller.open(row.provider)
                            }
                        }
                        .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small)
                        .semantics(mergeDescendants = true) { stateDescription = statusText },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StateDot(
                        when (status) {
                            ProviderStatus.Ready, ProviderStatus.SignedIn -> StateDotState.Done
                            ProviderStatus.NeedsKey -> StateDotState.Warning
                            ProviderStatus.NotActive -> StateDotState.Warning
                            ProviderStatus.NotSetUp -> StateDotState.Idle
                        },
                        size = 8.dp,
                    )
                    Spacer(Modifier.width(DsSpacing.compact))
                    Column(Modifier.weight(1f)) {
                        Text(row.displayName, style = DsType.std14Strong, color = colors.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!grouped || status != ProviderStatus.NotSetUp) Text(
                            statusText,
                            style = DsType.small13,
                            color = when (status) {
                                ProviderStatus.Ready, ProviderStatus.SignedIn -> colors.success
                                ProviderStatus.NeedsKey, ProviderStatus.NotActive -> colors.warnLabel
                                ProviderStatus.NotSetUp -> colors.labelTertiary
                            },
                        )
                    }
                    if (canOpen) {
                        Icon(
                            DsIcons.ExpandMore,
                            contentDescription = null,
                            tint = colors.labelTertiary,
                            modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f),
                        )
                    }
                }
                row.error?.let { error ->
                    Text(
                        stringResource(R.string.models_provider_error, error),
                        style = DsType.caption11,
                        color = colors.warnText,
                        modifier = Modifier.padding(start = DsSpacing.comfortable, end = DsSpacing.comfortable, bottom = DsSpacing.small),
                    )
                }
                AnimatedVisibility(
                    visible = open,
                    enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
                    exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
                ) {
                    val editor = state.editor
                    if (editor != null && editor.provider == row.provider) {
                        ProviderEditor(row, state, controller, hostLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(status: ProviderStatus): String = stringResource(
    when (status) {
        ProviderStatus.Ready -> R.string.models_status_ready
        ProviderStatus.NeedsKey -> R.string.models_status_needs_key
        ProviderStatus.SignedIn -> R.string.models_status_signed_in
        ProviderStatus.NotActive -> R.string.models_status_not_active
        ProviderStatus.NotSetUp -> R.string.models_status_not_set_up
    },
)

/** The open provider's key field, address, buttons and last result. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderEditor(row: ProviderRow, state: ModelsState, controller: ModelsController, hostLabel: String) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val editor = state.editor ?: return
    // The key's only home: this composition. Not saveable on purpose; it is gone with the editor.
    var key by remember(row.provider) { mutableStateOf("") }
    var shown by remember(row.provider) { mutableStateOf(false) }
    var endpointOpen by remember(row.provider) { mutableStateOf(row.baseUrl != null) }
    var confirmRemoveKey by remember { mutableStateOf(false) }
    var confirmRemoveProvider by remember { mutableStateOf(false) }
    DisposableEffect(row.provider) { onDispose { key = "" } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(row.provider) { runCatching { focus.requestFocus() } }

    val busy = editor.work != null
    val problem = keyProblemOf(key)
    val canSave = controller.canSave(key)
    fun save() {
        if (!controller.canSave(key)) return
        haptics.tap()
        controller.save(key)
    }
    // Felt when the harness refuses; the success is felt with the notice.
    LaunchedEffect(editor.result) { if (editor.result is EditorResult.Failed || editor.result == EditorResult.Conflict) haptics.reject() }
    LaunchedEffect(editor.result) { if (editor.result is EditorResult.Checked) haptics.confirm() }

    Column(
        Modifier.fillMaxWidth().padding(start = DsSpacing.comfortable, end = DsSpacing.comfortable, bottom = DsSpacing.comfortable),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        if (row.keyStored) Text(stringResource(R.string.models_key_saved_line), style = DsType.small13, color = colors.labelSecondary)
        if (!row.keyWritable) {
            Text(stringResource(R.string.models_key_from_env), style = DsType.small13, color = colors.labelTertiary)
        } else {
            val toolbar = LocalTextToolbar.current
            val safeToolbar = remember(toolbar) { NoCopyTextToolbar(toolbar) }
            val clipboard = LocalClipboardManager.current
            CompositionLocalProvider(LocalTextToolbar provides safeToolbar) {
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        key = it
                        controller.keyEdited()
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    enabled = !busy,
                    singleLine = true,
                    label = { Text(stringResource(R.string.models_key_label)) },
                    placeholder = {
                        Text(
                            stringResource(if (row.keyStored) R.string.models_key_placeholder_saved else R.string.models_key_placeholder),
                            style = DsType.body15,
                            color = colors.labelCaption,
                        )
                    },
                    isError = problem != null,
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    // A password field to the keyboard: no suggestions, no learning, no autocorrect.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    trailingIcon = {
                        DsIconButton(
                            icon = if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(if (shown) R.string.models_key_hide else R.string.models_key_show),
                            onClick = {
                                haptics.tap()
                                shown = !shown
                            },
                            tint = colors.labelTertiary,
                        )
                    },
                    shape = DsShapes.control,
                    textStyle = DsType.mono13.copy(color = colors.labelPrimary),
                    colors = fieldColors(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DsButton(
                    text = stringResource(R.string.models_key_paste),
                    icon = DsIcons.ContentPaste,
                    onClick = {
                        haptics.tap()
                        clipboard.getText()?.text?.let {
                            key = it.trim()
                            controller.keyEdited()
                        }
                    },
                    enabled = !busy,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
            problem?.let {
                Text(
                    stringResource(if (it == KeyProblem.Blank) R.string.models_key_blank else R.string.models_key_format),
                    style = DsType.small13,
                    color = colors.error,
                )
            }
        }

        if (row.family != ProviderFamily.Other) {
            if (!endpointOpen) {
                DsButton(
                    text = stringResource(R.string.models_endpoint_toggle),
                    onClick = {
                        haptics.tap()
                        endpointOpen = true
                    },
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                    allowWrap = true,
                )
            }
            AnimatedVisibility(
                visible = endpointOpen,
                enter = fadeIn(DsAnimations.fade) + expandVertically(DsAnimations.expand),
                exit = fadeOut(DsAnimations.fade) + shrinkVertically(DsAnimations.expand),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                    OutlinedTextField(
                        value = editor.baseUrlDraft,
                        onValueChange = controller::setBaseUrl,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.models_endpoint_label)) },
                        placeholder = { Text(row.defaultBaseUrl ?: "https://", style = DsType.body15, color = colors.labelCaption, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        isError = !baseUrlLooksValid(editor.baseUrlDraft),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        shape = DsShapes.control,
                        textStyle = DsType.mono13.copy(color = colors.labelPrimary),
                        colors = fieldColors(),
                    )
                    Text(
                        if (!baseUrlLooksValid(editor.baseUrlDraft)) stringResource(R.string.models_endpoint_invalid)
                        else row.defaultBaseUrl?.let { stringResource(R.string.models_endpoint_default, technicalDisplay(it)) }
                            ?: stringResource(R.string.models_endpoint_hint),
                        style = DsType.caption11,
                        color = if (!baseUrlLooksValid(editor.baseUrlDraft)) colors.error else colors.labelTertiary,
                    )
                }
            }
        }

        EditorResultLine(editor.result, editor.work)

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = DsSpacing.tiny),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            DsButton(
                text = stringResource(if (editor.work == EditorWork.Saving) R.string.models_saving else R.string.models_save),
                onClick = { save() },
                enabled = canSave,
                size = DsButtonSize.Small,
            )
            if (controller.canCheck()) {
                DsButton(
                    text = stringResource(if (editor.work == EditorWork.Checking) R.string.models_checking else R.string.models_check),
                    onClick = {
                        haptics.tap()
                        controller.check(key)
                    },
                    enabled = !busy && problem == null,
                    variant = DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                )
            }
            if (row.keyStored && row.keyWritable) {
                DsButton(
                    text = stringResource(if (editor.work == EditorWork.Removing) R.string.models_removing else R.string.models_remove_key),
                    onClick = {
                        haptics.tap()
                        confirmRemoveKey = true
                    },
                    enabled = !busy,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
            if (row.removable) {
                DsButton(
                    text = stringResource(R.string.models_remove_provider),
                    onClick = {
                        haptics.tap()
                        confirmRemoveProvider = true
                    },
                    enabled = !busy,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
        }
    }

    if (editor.askCleartext != null) {
        DsDialog(
            title = stringResource(R.string.models_cleartext_title), onDismiss = { controller.answerCleartext(send = false, key = "") },
            body = {
                Text(stringResource(R.string.models_cleartext_body, technicalDisplay(hostLabel)), style = DsType.std14, color = colors.labelSecondary)
            },
            actions = {
                DialogButtons(
                    dismiss = stringResource(R.string.common_cancel),
                    confirm = stringResource(R.string.models_cleartext_send),
                    danger = true,
                    onDismiss = { controller.answerCleartext(send = false, key = "") },
                    onConfirm = {
                        haptics.tap()
                        controller.answerCleartext(send = true, key = key)
                    },
                )
            },
        )
    }
    if (confirmRemoveKey) {
        DsDialog(
            title = stringResource(R.string.models_remove_key_title, row.displayName), onDismiss = { confirmRemoveKey = false },
            body = {
                Text(stringResource(R.string.models_remove_key_body, row.displayName), style = DsType.std14, color = colors.labelSecondary)
            },
            actions = {
                DialogButtons(
                    dismiss = stringResource(R.string.common_cancel),
                    confirm = stringResource(R.string.models_remove_key),
                    danger = true,
                    onDismiss = { confirmRemoveKey = false },
                    onConfirm = {
                        confirmRemoveKey = false
                        haptics.reject()
                        controller.removeKey()
                    },
                )
            },
        )
    }
    if (confirmRemoveProvider) {
        DsDialog(
            title = stringResource(R.string.models_remove_provider_title, row.displayName), onDismiss = { confirmRemoveProvider = false },
            body = {
                Text(stringResource(R.string.models_remove_provider_body), style = DsType.std14, color = colors.labelSecondary)
            },
            actions = {
                DialogButtons(
                    dismiss = stringResource(R.string.common_cancel),
                    confirm = stringResource(R.string.models_remove_provider),
                    danger = true,
                    onDismiss = { confirmRemoveProvider = false },
                    onConfirm = {
                        confirmRemoveProvider = false
                        haptics.reject()
                        controller.removeProvider()
                    },
                )
            },
        )
    }
}

@Composable
private fun EditorResultLine(result: EditorResult?, work: EditorWork?) {
    val colors = DsTheme.colors
    val text = when (result) {
        null -> null
        is EditorResult.Checked -> if (result.count == 0) stringResource(R.string.models_check_none) else stringResource(R.string.models_check_found, result.count)
        is EditorResult.Failed -> stringResource(R.string.models_failed, result.message)
        EditorResult.Conflict -> stringResource(R.string.models_conflict)
    }
    if (work != null && result == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), color = colors.accent, strokeWidth = 2.dp)
        }
    }
    if (text != null) {
        Text(
            text,
            style = DsType.small13,
            color = if (result is EditorResult.Checked) colors.success else colors.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun fieldColors() = DsTheme.colors.let { colors ->
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.bgLayer2,
        unfocusedContainerColor = colors.bgLayer2,
        disabledContainerColor = colors.bgLayer1,
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.borderL2,
        disabledBorderColor = colors.borderL1,
        cursorColor = colors.accent,
        focusedTextColor = colors.labelPrimary,
        unfocusedTextColor = colors.labelPrimary,
        focusedLabelColor = colors.accent,
        unfocusedLabelColor = colors.labelTertiary,
        errorBorderColor = colors.error,
        errorLabelColor = colors.error,
        errorContainerColor = colors.bgLayer2,
    )
}

@Composable
private fun Loading() {
    val colors = DsTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = DsSpacing.medium)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = colors.accent, strokeWidth = 2.dp)
        Spacer(Modifier.width(DsSpacing.small))
        Text(stringResource(R.string.common_loading), style = DsType.std14, color = colors.labelTertiary)
    }
}

/** A plate of information; amber when it asks for care. */
@Composable
internal fun Notice(text: String, warning: Boolean, detail: String? = null) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.buttonLarge,
        color = if (warning) colors.warnTertiary else colors.bgLayer1,
        border = BorderStroke(1.dp, if (warning) colors.warnBorder else colors.borderL2),
    ) {
        Row(Modifier.padding(DsSpacing.medium), verticalAlignment = Alignment.Top) {
            if (warning) {
                Icon(DsIcons.Warning, contentDescription = null, tint = colors.warnLabel, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Spacer(Modifier.width(DsSpacing.small))
            }
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Text(text, style = DsType.small13, color = if (warning) colors.warnLabel else colors.labelSecondary)
                detail?.let { Text(technicalDisplay(it), style = DsType.caption11, color = colors.labelTertiary) }
            }
        }
    }
}
