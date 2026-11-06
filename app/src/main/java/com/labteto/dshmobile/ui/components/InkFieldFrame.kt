package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * The Ink input's frame, for a Material `TextField` that has to stay a `TextField` (a label, several
 * lines, a trailing slot).
 *
 * The kit's `.inp` is a 12dp plate with a 1dp hairline that turns accent when focused. Material's
 * filled `TextField` draws neither: its default shape rounds only the top corners at 6dp and it
 * underlines instead of outlining, so a rename dialog, a queue edit and a search field in the same
 * app looked like three different inputs. Give the field `shape = DsShapes.control`, colours with a
 * transparent indicator (`dialogTextFieldColors`), and this modifier, and it matches the others.
 */
@Composable
fun Modifier.inkFieldFrame(): Modifier {
    val colors = DsTheme.colors
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.hasFocus }
        .border(1.dp, if (focused) colors.accent else colors.borderL2, DsShapes.control)
}
