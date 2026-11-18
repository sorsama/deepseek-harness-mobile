package com.labteto.dshmobile.ui.screens.harnesssettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.settings.DraftResult
import com.labteto.dshmobile.core.settings.FieldFailure
import com.labteto.dshmobile.core.settings.FieldKind
import com.labteto.dshmobile.core.settings.FieldProblem
import com.labteto.dshmobile.core.settings.FieldTraits
import com.labteto.dshmobile.core.settings.HarnessSettingsController
import com.labteto.dshmobile.core.settings.HarnessSettingsState
import com.labteto.dshmobile.core.settings.SettingsFailure
import com.labteto.dshmobile.core.settings.SettingsField
import com.labteto.dshmobile.core.settings.TextAction
import com.labteto.dshmobile.core.settings.TextExit
import com.labteto.dshmobile.core.settings.draftFromChoice
import com.labteto.dshmobile.core.settings.draftFromList
import com.labteto.dshmobile.core.settings.draftFromSelection
import com.labteto.dshmobile.core.settings.draftFromText
import com.labteto.dshmobile.core.settings.fieldText
import com.labteto.dshmobile.core.settings.formatNumber
import com.labteto.dshmobile.core.settings.keepsFocusOnDone
import com.labteto.dshmobile.core.settings.textAction
import com.labteto.dshmobile.core.settings.valuesEquivalent
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsIconButton
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.ToggleRow
import com.labteto.dshmobile.ui.components.inkFieldFrame
import com.labteto.dshmobile.ui.screens.main.FeatureDiagnostics
import com.labteto.dshmobile.ui.screens.main.dialogTextFieldColors
import com.labteto.dshmobile.ui.screens.main.technicalDisplay
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * One field of one namespace, drawn for its kind and saved the way the Settings screen saves: a
 * switch or a choice the moment it is touched, a text or number box when its keyboard says Done
 * or it loses focus. A refusal puts the previous value back and says why, next to the field.
 *
 * Everything the field shows comes from the controller's state: while a write is out the field
 * reads as what it is becoming, and once the harness answers it reads as what the harness holds.
 */
@Composable
internal fun SettingsFieldControl(
    field: SettingsField,
    words: FieldText,
    state: HarnessSettingsState,
    controller: HarnessSettingsController,
    hostLabel: String,
) {
    val label = words.labelRes?.let { stringResource(it) } ?: words.label ?: field.key
    val hint = words.hint?.let { res -> words.hintArg?.let { stringResource(res, technicalDisplay(it)) } ?: stringResource(res) }
        ?: field.description
    val unit = words.unit?.let { stringResource(it) }
    val busy = field.id in state.writing
    val shown = if (busy) state.writing[field.id] ?: field.baseValue else field.value
    val failure = state.failures[field.id]
    val onSet: (JsonElement) -> Unit = { controller.set(field, it) }
    val onReset: () -> Unit = { controller.reset(field) }
    val onDismiss: () -> Unit = { controller.dismissFailure(field.id) }

    when (val kind = field.kind) {
        FieldKind.Toggle -> {
            ToggleRow(label, (shown as? JsonPrimitive)?.booleanOrNull == true, hint, enabled = state.writable) {
                val on = (shown as? JsonPrimitive)?.booleanOrNull == true
                onSet(JsonPrimitive(!on))
            }
            // A switch flipped off and on leaves an explicit override equal to the default: say so, and offer the way back.
            OverrideLine(label, field.overridden, state.writable, busy, onReset)
            FailureLine(failure)
        }
        is FieldKind.Number, is FieldKind.Text -> TextualField(
            field, label, hint, unit, words.traits, words.technical, state.writable, failure, onSet, onReset, onDismiss,
        )
        is FieldKind.Choice -> ChoiceField(field, kind, label, hint, shown, state.writable, busy, failure, onSet, onReset)
        is FieldKind.StringList -> StringListField(field, label, hint, shown, state.writable, failure, onSet, onReset)
        is FieldKind.ChoiceList -> ChoiceListField(field, kind, label, hint, shown, state.writable, failure, onSet, onReset)
        FieldKind.Secret -> GenericSecretField(field, label, hint, state, controller, hostLabel)
        is FieldKind.ReadOnly -> ReadOnlyField(label, hint, shown)
    }
}

/** The label of a field and, beside it, the pill that says it differs from the harness's default. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FieldHeader(label: String, overridden: Boolean) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        Text(
            label,
            style = DsType.std14Strong,
            color = DsTheme.colors.labelPrimary,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        if (overridden) DsPill(stringResource(R.string.hs_overridden), modifier = Modifier.align(Alignment.CenterVertically))
    }
}

/**
 * A button that is at least 48dp tall and wraps its words at large text sizes. [description] is what
 * a screen reader says when the visible words are not enough on their own ("Reset to default" for
 * which field?).
 */
@Composable
internal fun SettingsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: DsButtonVariant = DsButtonVariant.Outline,
    description: String? = null,
    icon: ImageVector? = null,
) {
    DsButton(
        text = text,
        onClick = onClick,
        icon = icon,
        modifier = modifier
            .heightIn(min = DsSpacing.touchTarget)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        enabled = enabled,
        variant = variant,
        allowWrap = true,
    )
}

// ------------------------------------------------------------------------------ text and number

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextualField(
    field: SettingsField,
    label: String,
    hint: String?,
    unit: String?,
    traits: FieldTraits,
    technical: Boolean,
    writable: Boolean,
    failure: FieldFailure?,
    onSet: (JsonElement) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val focusManager = LocalFocusManager.current
    var text by rememberSaveable(field.ns, field.key) { mutableStateOf(fieldText(field.value)) }
    var edited by rememberSaveable(field.ns, field.key) { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }

    // The harness's value moved (a save landed, a refresh, a reset): show it, unless a draft is being typed.
    LaunchedEffect(field.value) { if (!edited) text = fieldText(field.value) }
    // A refusal puts the previous value back.
    LaunchedEffect(failure?.serial) {
        if (failure != null) {
            text = fieldText(field.value)
            edited = false
        }
    }

    val draft = field.draftFromText(text, traits)
    val problem = (draft as? DraftResult.Invalid)?.problem
    val showProblem = edited && problem != null
    // What Done and leaving the field do is decided in core (`textAction`): a valid edit is sent, an
    // empty box never is (Done keeps it with its message, leaving the field puts the saved value back),
    // and going back to the default is the Reset button, never a side effect of an empty box.
    fun leave(exit: TextExit) {
        when (val action = field.textAction(text, traits, edited, exit)) {
            is TextAction.Send -> {
                edited = false
                onSet(action.value)
            }
            TextAction.Restore -> {
                edited = false
                text = fieldText(field.value)
            }
            TextAction.Keep -> Unit
        }
    }

    // Leaving the page with the keyboard still up (Back, a swipe) is leaving the field: a valid edit is saved.
    val latestLeave by rememberUpdatedState { leave(TextExit.Dispose) }
    DisposableEffect(Unit) { onDispose { latestLeave() } }

    val whole = (field.kind as? FieldKind.Number)?.let { it.whole || traits.whole }
    val keyboard = when {
        field.kind is FieldKind.Number -> if (whole == true) KeyboardType.Number else KeyboardType.Decimal
        technical -> KeyboardType.Uri
        else -> KeyboardType.Text
    }
    val textStyle = if (technical) DsType.mono13.copy(textDirection = TextDirection.Ltr) else DsType.std14

    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        FieldHeader(label, field.overridden)
        TextField(
            value = text,
            onValueChange = {
                text = it
                edited = true
                if (failure != null) onDismiss()
            },
            modifier = Modifier
                .fillMaxWidth()
                .inkFieldFrame()
                .onFocusChanged { state ->
                    if (hadFocus && !state.isFocused) leave(TextExit.Blur)
                    hadFocus = state.isFocused
                },
            enabled = writable,
            singleLine = true,
            isError = showProblem,
            textStyle = textStyle,
            suffix = unit?.let { { Text(it, style = DsType.small13, color = colors.labelTertiary) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val keepFocus = field.keepsFocusOnDone(text, traits, edited)
                leave(TextExit.Done)
                // An empty box stays focused with its message beside it; the next tap elsewhere puts the saved value back.
                if (!keepFocus) focusManager.clearFocus()
            }),
            colors = dialogTextFieldColors(),
            shape = DsShapes.control,
        )
        if (showProblem && problem != null) {
            Text(
                problemText(problem, field.kind, traits),
                style = DsType.small13,
                color = colors.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else if (hint != null) {
            Text(hint, style = DsType.small13, color = colors.labelTertiary)
        }
        FailureLine(failure)
        if (field.overridden && writable) {
            SettingsButton(
                text = stringResource(R.string.hs_reset),
                onClick = {
                    edited = false
                    text = fieldText(field.baseValue)
                    onReset()
                },
                variant = DsButtonVariant.Ghost,
                description = stringResource(R.string.hs_reset_for, label),
            )
        }
    }
}

/**
 * The marker and the way back for a control that has no label row of its own (a switch): a word,
 * "Changed", and "Reset to default", only while the value carries an override. The word is the
 * marker, never a colour alone, and the override counts even when it equals the deployment's value.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverrideLine(label: String, overridden: Boolean, writable: Boolean, busy: Boolean, onReset: () -> Unit) {
    if (!overridden) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        DsPill(stringResource(R.string.hs_overridden), modifier = Modifier.align(Alignment.CenterVertically))
        if (writable) {
            SettingsButton(
                text = stringResource(R.string.hs_reset),
                onClick = onReset,
                enabled = !busy,
                variant = DsButtonVariant.Ghost,
                description = stringResource(R.string.hs_reset_for, label),
            )
        }
    }
}

// ----------------------------------------------------------------------------------------- choices

@Composable
private fun ChoiceField(
    field: SettingsField,
    kind: FieldKind.Choice,
    label: String,
    hint: String?,
    shown: JsonElement?,
    writable: Boolean,
    busy: Boolean,
    failure: FieldFailure?,
    onSet: (JsonElement) -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        FieldHeader(label, field.overridden)
        hint?.let { Text(it, style = DsType.small13, color = DsTheme.colors.labelTertiary) }
        kind.options.forEach { option ->
            val selected = valuesEquivalent(option.value, shown)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DsSpacing.touchTarget)
                    .selectable(selected = selected, enabled = writable, role = Role.RadioButton) {
                        if (!selected) {
                            (field.draftFromChoice(option.value) as? DraftResult.Write)?.let { onSet(it.value) }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null, enabled = writable, colors = radioColors())
                Spacer(Modifier.width(DsSpacing.medium))
                Text(option.label, style = DsType.std14, color = DsTheme.colors.labelPrimary, modifier = Modifier.weight(1f))
            }
        }
        FailureLine(failure)
        if (field.overridden && writable) {
            SettingsButton(
                text = stringResource(R.string.hs_reset),
                onClick = onReset,
                enabled = !busy,
                variant = DsButtonVariant.Ghost,
                description = stringResource(R.string.hs_reset_for, label),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceListField(
    field: SettingsField,
    kind: FieldKind.ChoiceList,
    label: String,
    hint: String?,
    shown: JsonElement?,
    writable: Boolean,
    failure: FieldFailure?,
    onSet: (JsonElement) -> Unit,
    onReset: () -> Unit,
) {
    val chosen = (shown as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>()
    var problem by remember { mutableStateOf<FieldProblem?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        FieldHeader(label, field.overridden)
        hint?.let { Text(it, style = DsType.small13, color = DsTheme.colors.labelTertiary) }
        kind.options.forEach { option ->
            val checked = chosen.any { valuesEquivalent(it, option.value) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DsSpacing.touchTarget)
                    .toggleable(value = checked, enabled = writable, role = Role.Checkbox) { on ->
                        val next = kind.options.map { it.value }.filter { value ->
                            if (valuesEquivalent(value, option.value)) on else chosen.any { valuesEquivalent(it, value) }
                        }
                        when (val draft = field.draftFromSelection(next)) {
                            is DraftResult.Write -> {
                                problem = null
                                onSet(draft.value)
                            }
                            is DraftResult.Invalid -> problem = draft.problem
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = writable, colors = checkboxColors())
                Spacer(Modifier.width(DsSpacing.medium))
                Text(option.label, style = DsType.std14, color = DsTheme.colors.labelPrimary, modifier = Modifier.weight(1f))
            }
        }
        problem?.let { Text(problemText(it, field.kind, FieldTraits()), style = DsType.small13, color = DsTheme.colors.error) }
        FailureLine(failure)
        if (field.overridden && writable) {
            SettingsButton(
                stringResource(R.string.hs_reset), onReset, variant = DsButtonVariant.Ghost,
                description = stringResource(R.string.hs_reset_for, label),
            )
        }
    }
}

// ------------------------------------------------------------------------------------ string list

@Composable
private fun StringListField(
    field: SettingsField,
    label: String,
    hint: String?,
    shown: JsonElement?,
    writable: Boolean,
    failure: FieldFailure?,
    onSet: (JsonElement) -> Unit,
    onReset: () -> Unit,
) {
    val colors = DsTheme.colors
    val items = (shown as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    var draft by rememberSaveable(field.ns, field.key) { mutableStateOf("") }
    var problem by remember { mutableStateOf<FieldProblem?>(null) }

    fun commit(next: List<String>) {
        when (val result = field.draftFromList(next)) {
            is DraftResult.Write -> {
                problem = null
                onSet(result.value)
            }
            is DraftResult.Invalid -> problem = result.problem
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        FieldHeader(label, field.overridden)
        hint?.let { Text(it, style = DsType.small13, color = colors.labelTertiary) }
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget), verticalAlignment = Alignment.CenterVertically) {
                Text(technicalDisplay(item), style = DsType.mono13, color = colors.labelPrimary, modifier = Modifier.weight(1f))
                DsIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.hs_remove_item, item),
                    onClick = { commit(items - item) },
                    enabled = writable,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).inkFieldFrame(),
                enabled = writable,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.hs_new_item), style = DsType.std14, color = colors.labelCaption) },
                textStyle = DsType.std14,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (draft.isNotBlank()) {
                        commit(items + draft)
                        draft = ""
                    }
                }),
                colors = dialogTextFieldColors(),
                shape = DsShapes.control,
            )
            SettingsButton(
                text = stringResource(R.string.hs_add),
                onClick = {
                    commit(items + draft)
                    draft = ""
                },
                enabled = writable && draft.isNotBlank(),
            )
        }
        problem?.let { Text(problemText(it, field.kind, FieldTraits()), style = DsType.small13, color = colors.error) }
        FailureLine(failure)
        if (field.overridden && writable) {
            SettingsButton(
                stringResource(R.string.hs_reset), onReset, variant = DsButtonVariant.Ghost,
                description = stringResource(R.string.hs_reset_for, label),
            )
        }
    }
}

// ------------------------------------------------------------------------------------- read only

/** A field this app cannot edit: its name, its value as the harness holds it, and why it is only shown. */
@Composable
private fun ReadOnlyField(label: String, hint: String?, shown: JsonElement?) {
    val colors = DsTheme.colors
    val value = when (shown) {
        null, JsonNull -> "-"
        is JsonPrimitive -> shown.contentOrNull.orEmpty()
        else -> shown.toString()
    }
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny), modifier = Modifier.padding(vertical = DsSpacing.small)) {
        Text(label, style = DsType.std14Strong, color = colors.labelPrimary)
        Text(
            technicalDisplay(value),
            style = DsType.mono13.copy(textDirection = TextDirection.Ltr),
            color = colors.labelSecondary,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
        hint?.let { Text(it, style = DsType.small13, color = colors.labelTertiary) }
        Text(stringResource(R.string.hs_readonly_note), style = DsType.caption11, color = colors.labelTertiary)
    }
}

// ------------------------------------------------------------------------------ problems, failures

/** The sentence for what is wrong with a typed value, with the limits in it. */
@Composable
internal fun problemText(problem: FieldProblem, kind: FieldKind?, traits: FieldTraits): String {
    val number = kind as? FieldKind.Number
    val min = listOfNotNull(number?.min, traits.min).maxOrNull()
    val max = listOfNotNull(number?.max, traits.max).minOrNull()
    val whole = number?.whole == true || traits.whole
    return when (problem) {
        // An empty number is asked for in the words the box already uses for a wrong one.
        FieldProblem.Required -> stringResource(if (number != null) R.string.hs_err_number else R.string.hs_err_required)
        FieldProblem.NotANumber -> stringResource(R.string.hs_err_number)
        FieldProblem.NotWhole -> stringResource(R.string.hs_err_whole)
        is FieldProblem.NotMultiple -> stringResource(R.string.hs_err_step, formatBound(problem.step))
        is FieldProblem.TooSmall ->
            if (max != null && whole) stringResource(R.string.hs_err_range, formatBound(problem.min), formatBound(max))
            else stringResource(R.string.hs_err_min, formatBound(problem.min))
        is FieldProblem.TooLarge ->
            if (min != null && whole) stringResource(R.string.hs_err_range, formatBound(min), formatBound(problem.max))
            else stringResource(R.string.hs_err_max, formatBound(problem.max))
        is FieldProblem.TooShort -> stringResource(R.string.hs_err_min_length, problem.min.toString())
        is FieldProblem.TooLong -> stringResource(R.string.hs_err_max_length, problem.max.toString())
        FieldProblem.WrongFormat -> stringResource(R.string.hs_err_format)
        FieldProblem.NotAnUrl -> stringResource(R.string.models_endpoint_invalid)
        FieldProblem.NotAnOption -> stringResource(R.string.hs_err_option)
        is FieldProblem.TooFewItems -> stringResource(R.string.hs_err_min_items, problem.min.toString())
        is FieldProblem.TooManyItems -> stringResource(R.string.hs_err_max_items, problem.max.toString())
        FieldProblem.KeyFormat -> stringResource(R.string.models_key_format)
    }
}

/** A limit as a person writes it: `50`, not `50.0`. */
internal fun formatBound(value: Double): String = formatNumber(value)

/** What went wrong with the last save of a field, in words, with the harness's own message folded away. */
@Composable
internal fun FailureLine(failure: FieldFailure?) {
    if (failure == null) return
    FailureText(failure.failure)
}

@Composable
internal fun FailureText(failure: SettingsFailure) {
    val (text, detail) = when (failure) {
        is SettingsFailure.Rejected -> stringResource(R.string.hs_fail_rejected) to failure.detail
        SettingsFailure.Conflict -> stringResource(R.string.hs_fail_conflict) to null
        is SettingsFailure.Unreachable -> stringResource(R.string.hs_fail_unreachable) to failure.detail
        SettingsFailure.Unavailable -> stringResource(R.string.hs_unsupported) to null
    }
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        Text(
            text,
            style = DsType.small13,
            color = DsTheme.colors.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        FeatureDiagnostics(detail)
    }
}

@Composable
internal fun radioColors() = RadioButtonDefaults.colors(
    selectedColor = DsTheme.colors.accent,
    unselectedColor = DsTheme.colors.labelTertiary,
    disabledSelectedColor = DsTheme.colors.labelDimmed,
    disabledUnselectedColor = DsTheme.colors.labelDimmed,
)

@Composable
internal fun checkboxColors() = CheckboxDefaults.colors(
    checkedColor = DsTheme.colors.accent,
    checkmarkColor = DsTheme.colors.onAccent,
    uncheckedColor = DsTheme.colors.labelTertiary,
    disabledCheckedColor = DsTheme.colors.labelDimmed,
    disabledUncheckedColor = DsTheme.colors.labelDimmed,
)

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
