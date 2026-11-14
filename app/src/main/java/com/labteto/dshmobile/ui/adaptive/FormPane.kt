package com.labteto.dshmobile.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The widest a single-column form (Connect, Pair, Settings) grows before it centres. */
val FormWidthMax = 640.dp

/**
 * Holds a one-column screen to [FormWidthMax] and centres it, so a tablet or an unfolded foldable
 * shows a form the width a form should be rather than fields a metre wide. Below that width it is
 * transparent: the content gets the whole window, exactly as before.
 */
@Composable
fun FormPane(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = FormWidthMax).fillMaxHeight()) { content() }
    }
}
