package com.labteto.dshmobile.ui.screens.harnesssettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.settings.DraftResult
import com.labteto.dshmobile.core.settings.FieldFailure
import com.labteto.dshmobile.core.settings.FieldProblem
import com.labteto.dshmobile.core.settings.FieldTraits
import com.labteto.dshmobile.core.settings.HarnessSettingsController
import com.labteto.dshmobile.core.settings.HarnessSettingsState
import com.labteto.dshmobile.core.settings.SettingsField
import com.labteto.dshmobile.core.settings.secretDraft
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.NoCopyTextToolbar
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.screens.main.ConfirmDialog
import com.labteto.dshmobile.ui.screens.main.technicalDisplay
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.serialization.json.JsonPrimitive

/**
 * Entry for one value that never comes back from the harness: a key. The stored value is not shown,
 * only whether there is one; a new one can be typed or pasted, and the old one removed.
 *
 * The same pattern as the Models screen's key field: the text is masked, never offered to the
 * clipboard, kept in this composition only (not in saved state, not in a view model), and a link
 * that crosses the network unencrypted asks first. The screen that hosts it holds `SecureContent`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SecretControl(
    label: String,
    status: String,
    hint: String?,
    /** False when the value is not this app's to change; [notWritableText] says why instead of a field. */
    writable: Boolean,
    notWritableText: String?,
    canRemove: Boolean,
    busy: Boolean,
    failure: FieldFailure?,
    cleartext: Boolean,
    hostLabel: String,
    removeTitle: String,
    removeBody: String,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onDismissFailure: () -> Unit,
    /** Set when other settings read the same credential: said under the status and again before a removal. */
    sharedNote: String? = null,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    // The key's only home: this composition. Not saveable on purpose; it is gone with the page.
    var draft by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var askCleartext by remember { mutableStateOf(false) }
    var cleartextAccepted by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var wasBusy by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { draft = "" } }

    // A save that landed empties the box; one that was refused keeps what was typed, so it can be fixed.
    LaunchedEffect(busy) {
        if (wasBusy && !busy && failure == null) draft = ""
        wasBusy = busy
    }
    LaunchedEffect(failure?.serial) { if (failure != null) haptics.reject() }

    val problem = (secretDraft(draft) as? DraftResult.Invalid)?.problem.takeIf { draft.isNotEmpty() }
    val canSave = writable && !busy && draft.isNotEmpty() && problem == null

    fun save() {
        if (!canSave) return
        if (cleartext && !cleartextAccepted) {
            askCleartext = true
            return
        }
        haptics.tap()
        onSave(draft.trim())
    }

    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text(label, style = DsType.std14Strong, color = colors.labelPrimary)
        Text(
            status,
            style = DsType.small13,
            color = colors.labelSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        sharedNote?.let { Text(it, style = DsType.small13, color = colors.warnLabel) }
        if (!writable) {
            notWritableText?.let { Text(it, style = DsType.small13, color = colors.labelTertiary) }
        } else {
            hint?.let { Text(it, style = DsType.small13, color = colors.labelTertiary) }
            val toolbar = LocalTextToolbar.current
            val safeToolbar = remember(toolbar) { NoCopyTextToolbar(toolbar) }
            val clipboard = LocalClipboardManager.current
            CompositionLocalProvider(LocalTextToolbar provides safeToolbar) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        if (failure != null) onDismissFailure()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    placeholder = {
                        Text(
                            stringResource(R.string.models_key_placeholder),
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
                    colors = secretFieldColors(),
                )
            }
            problem?.let {
                Text(
                    // Only spaces is the one problem the Models screen words as its own sentence.
                    if (it == FieldProblem.Required) stringResource(R.string.models_key_blank) else problemText(it, null, FieldTraits()),
                    style = DsType.small13,
                    color = colors.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            FailureLine(failure)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                SettingsButton(
                    text = stringResource(if (busy) R.string.models_saving else R.string.models_save),
                    onClick = { save() },
                    enabled = canSave,
                    variant = DsButtonVariant.Primary,
                )
                SettingsButton(
                    text = stringResource(R.string.models_key_paste),
                    onClick = {
                        haptics.tap()
                        clipboard.getText()?.text?.let {
                            draft = it.trim()
                            if (failure != null) onDismissFailure()
                        }
                    },
                    enabled = !busy,
                    variant = DsButtonVariant.Ghost,
                    icon = DsIcons.ContentPaste,
                )
                if (canRemove) {
                    SettingsButton(
                        text = stringResource(R.string.hs_remove),
                        onClick = {
                            haptics.tap()
                            confirmRemove = true
                        },
                        enabled = !busy,
                        variant = DsButtonVariant.Ghost,
                        description = stringResource(R.string.hs_remove_item, label),
                    )
                }
            }
        }
    }

    if (askCleartext) {
        ConfirmDialog(
            title = stringResource(R.string.models_cleartext_title),
            body = stringResource(R.string.models_cleartext_body, technicalDisplay(hostLabel)),
            confirmLabel = stringResource(R.string.models_cleartext_send),
            onDismiss = { askCleartext = false },
            onConfirm = {
                askCleartext = false
                cleartextAccepted = true
                save()
            },
        )
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = removeTitle,
            body = if (sharedNote == null) removeBody else "$removeBody\n\n$sharedNote",
            confirmLabel = stringResource(R.string.hs_remove),
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                haptics.reject()
                onRemove()
            },
        )
    }
}

/**
 * A secret field of a namespace the app has no page for: the value goes in through `settings/mutate`
 * (the harness strips it from every answer) and only "set or not" ever comes back.
 */
@Composable
internal fun GenericSecretField(
    field: SettingsField,
    label: String,
    hint: String?,
    state: HarnessSettingsState,
    controller: HarnessSettingsController,
    hostLabel: String,
) {
    SecretControl(
        label = label,
        status = stringResource(if (field.secretSet) R.string.hs_secret_set else R.string.hs_secret_unset),
        hint = hint,
        writable = state.writable,
        notWritableText = stringResource(R.string.hs_read_only),
        canRemove = field.secretSet && state.writable,
        busy = field.id in state.writing,
        failure = state.failures[field.id],
        cleartext = controller.cleartext,
        hostLabel = hostLabel,
        removeTitle = stringResource(R.string.hs_secret_remove_title),
        removeBody = stringResource(R.string.hs_secret_remove_body),
        onSave = { controller.set(field, JsonPrimitive(it)) },
        onRemove = { controller.reset(field) },
        onDismissFailure = { controller.dismissFailure(field.id) },
    )
}

@Composable
private fun secretFieldColors() = DsTheme.colors.let { colors ->
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
        errorBorderColor = colors.error,
        errorContainerColor = colors.bgLayer2,
    )
}
