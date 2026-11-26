package com.labteto.dshmobile.ui.screens.main

import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.ui.theme.DsTheme

/** The colours of a text field in a dialog: the dialog's plate, the accent cursor, no underline. */
@Composable
fun dialogTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = DsTheme.colors.bgLayer2,
    unfocusedContainerColor = DsTheme.colors.bgLayer2,
    disabledContainerColor = DsTheme.colors.bgLayer1,
    // No underline: the field's outline comes from `inkFieldFrame`, so it reads as the same plate as
    // every other input in the app. The default underline on a rounded plate is clipped to a stub.
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    cursorColor = DsTheme.colors.accent,
)
