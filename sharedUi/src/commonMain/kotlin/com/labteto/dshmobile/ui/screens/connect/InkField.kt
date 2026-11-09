package com.labteto.dshmobile.ui.screens.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.connect_paste
import com.labteto.dshmobile.ui.components.DsIcons
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import org.jetbrains.compose.resources.stringResource

/**
 * The Ink text field.
 *
 * The app still has no shared `DsTextField` (the theme owner owns `ui/components`), so the connect
 * and pairing screens carry this one: a 12dp-radius `bgLayer2` well with a hairline border that
 * turns accent when focused, 14dp side padding, and a label rendered *above* the well instead of
 * Material's floating label. That is the kit's `.inp` — the label outside is what keeps the field
 * height fixed, which matters on these screens because the connect/manual and sign-in forms sit
 * next to pinned buttons and must not jump when focus moves.
 *
 * [trailing] is laid out inside the well, at the end of the first line: the paste button on the
 * sign-in field is the design's own placement, and keeping it in the text field's decoration means
 * it moves with the field in RTL.
 */
@Composable
fun InkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    /** Accessibility name when the visible [label] is omitted to save vertical space. */
    contentDescription: String? = null,
    mono: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 4,
    minHeight: Dp = 48.dp,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hint = label ?: contentDescription
    val style = (if (mono) DsType.mono13 else DsType.base16).copy(
        color = if (enabled) colors.labelPrimary else colors.labelCaption,
    )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        if (label != null) {
            Text(
                label,
                style = DsType.small13Strong,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(DsShapes.buttonCapsule)
                .background(if (enabled) colors.bgLayer2 else colors.bgLayer3)
                .border(1.dp, if (focused) colors.accent else colors.borderL2, DsShapes.buttonCapsule)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                // `this.` on purpose: the composable's own `contentDescription` parameter would
                // otherwise shadow the SemanticsPropertyReceiver extension property here.
                .semantics { if (hint != null) this.contentDescription = hint },
            textStyle = style,
            cursorBrush = SolidColor(colors.accent),
            singleLine = singleLine,
            maxLines = maxLines,
            enabled = enabled,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interaction,
            visualTransformation = visualTransformation,
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.medium),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                placeholder,
                                style = style.copy(color = colors.labelTertiary),
                                maxLines = if (singleLine) 1 else maxLines,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                    if (trailing != null) {
                        Spacer(Modifier.width(DsSpacing.small))
                        trailing()
                    }
                }
            },
        )
    }
}

/**
 * The kit's clipboard control: a 48dp square-ish disc that reads the clipboard and reports the
 * outcome through the haptic vocabulary instead of a toast — [rememberDsHaptics]' `confirm()` when
 * there was something to paste, `reject()` when the clipboard was empty or held only whitespace.
 *
 * Deliberately not [com.labteto.dshmobile.ui.components.DsIconButton]: that one fires a generic
 * press tick, and a paste is a decision that deserves its own texture.
 */
@Composable
fun InkPasteButton(
    onPaste: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val clipboard = LocalClipboardManager.current
    val description = stringResource(Res.string.connect_paste)
    Surface(
        onClick = {
            val text = clipboard.getText()?.text?.trim().orEmpty()
            if (text.isEmpty()) {
                haptics.reject()
            } else {
                onPaste(text)
                haptics.confirm()
            }
        },
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = colors.bgLayer3,
        contentColor = colors.labelPrimary,
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = DsIcons.ContentPaste,
                contentDescription = description,
                tint = colors.labelPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
