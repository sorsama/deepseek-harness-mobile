package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp

/** Display only. Never use this value as an identifier, editable value or persisted draft. */
internal fun technicalDisplay(value: String): String = "\u2066$value\u2069"

/**
 * A line of the app's own words followed by text that came from outside it: a tool's failure
 * message, a provider's error code, a model name. [label] ("Failed:") is the reader's language and
 * follows the layout direction; [value] is one left-to-right run in its own `Text`, which starts
 * after the label and, when it wraps, wraps as a left-to-right paragraph.
 *
 * Why not one string with the value isolated (`technicalDisplay`): an isolate keeps a single line in
 * order, but a right-to-left paragraph that wraps an isolated run lays the second line out from the
 * right edge, so `old_string was not found in "C:` ends one line and `\demo\...` starts the next,
 * beside a label that reads the other way. The value needs its own paragraph, not a mark in the
 * label's.
 *
 * The row is read as one text by a screen reader.
 */
@Composable
internal fun LabeledTechnicalText(
    label: String,
    value: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    labelColor: Color = color,
) {
    Row(
        modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = style, color = labelColor)
        TechnicalRun(value, style, color)
    }
}

/** The value of a [LabeledTechnicalText]: wraps within what the label leaves, left to right. */
@Composable
private fun RowScope.TechnicalRun(value: String, style: TextStyle, color: Color) {
    Text(
        value,
        style = style.copy(textDirection = TextDirection.Ltr),
        color = color,
        // As wide as it needs and no wider: a short value sits right beside its label.
        modifier = Modifier.weight(1f, fill = false),
    )
}
