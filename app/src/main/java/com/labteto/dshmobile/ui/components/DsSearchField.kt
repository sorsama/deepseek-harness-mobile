package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** The kit's `.inp` height, and the floor the field keeps at large font scales. */
private val SearchFieldHeight = 48.dp

/** The kit's `.inp` side padding. */
private val SearchFieldInset = 14.dp

/**
 * The app's one search field — the kit's `.inp` in its search form (designs 23 and 24).
 *
 * Every search in the app is this field, so they look and behave the same wherever one is met: a
 * 48dp well with a 12dp radius, `bgLayer2` fill and a 1dp hairline that turns accent when focused;
 * 14dp side padding; proportional 15sp text with a `labelTertiary` placeholder; a leading search
 * glyph; and a trailing clear button that exists only while there is text. Clearing keeps focus, so
 * the next query can be typed straight away. The field is always a single line — pasted line breaks
 * become spaces — and the keyboard's action key is Search, which dismisses the keyboard and leaves
 * the query in place.
 *
 * It is a [BasicTextField] with its own decoration rather than a Material `TextField`: Material's
 * floating label, indicator line and container metrics cannot produce the kit's well, and four
 * screens each tuning a `TextField` toward it is how the app ended up with four different searches.
 *
 * @param placeholder shown while empty, and the field's accessibility name.
 * @param autoFocus take focus and raise the keyboard when the field first appears — for a field the
 * user opened on purpose (a search toggle), not one that is simply part of a page.
 * @param onSearch called after the Search action has dismissed the keyboard.
 */
@Composable
fun DsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    onSearch: () -> Unit = {},
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val style = DsType.body15.copy(color = colors.labelPrimary)
    val clearLabel = stringResource(R.string.ux_b_clear_search)

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            // A frame first: asking for focus before the field is attached throws, and an
            // animated entrance (the files search folds open) attaches it a moment late.
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.replace('\n', ' ').replace('\r', ' ')) },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SearchFieldHeight)
            .clip(DsShapes.buttonCapsule)
            .background(colors.bgLayer2)
            .border(1.dp, if (focused) colors.accent else colors.borderL2, DsShapes.buttonCapsule)
            .focusRequester(focus)
            .semantics { contentDescription = placeholder },
        textStyle = style,
        cursorBrush = SolidColor(colors.accent),
        singleLine = true,
        maxLines = 1,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(
            onSearch = {
                keyboard?.hide()
                focusManager.clearFocus()
                onSearch()
            },
        ),
        interactionSource = interaction,
        decorationBox = { inner ->
            val hasText = value.isNotEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SearchFieldHeight)
                    // The clear button is a full 48dp target and brings its own inset, so the
                    // trailing padding is only there while it is not.
                    .padding(start = SearchFieldInset, end = if (hasText) 0.dp else SearchFieldInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    FeatherIcons.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colors.labelTertiary,
                )
                Spacer(Modifier.width(DsSpacing.small))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (!hasText) {
                        Text(
                            placeholder,
                            style = style.copy(color = colors.labelTertiary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
                if (hasText) {
                    DsIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = clearLabel,
                        onClick = {
                            onValueChange("")
                            runCatching { focus.requestFocus() }
                        },
                        tint = colors.labelTertiary,
                        iconSize = 18.dp,
                    )
                }
            }
        },
    )
}
