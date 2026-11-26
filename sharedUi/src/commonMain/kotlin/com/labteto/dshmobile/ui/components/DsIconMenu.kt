package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * An icon button that opens a menu: a [DsIconButton] that drops a [DsMenuPopup].
 *
 * It exists so that an icon which opens a menu (sort, more) and an icon which does something
 * (search, settings) are the same control. Built on a bare `clickable` box, the menu anchors drew a
 * square press highlight next to their neighbours' circular one; here the anchor *is* a
 * `DsIconButton`, so the look, the 48dp target, the press scale, the haptic tick and the button role
 * all come with it. [bordered] and [selected] are passed straight through for a menu that sits next
 * to an input.
 */
@Composable
fun DsIconMenu(
    icon: ImageVector,
    contentDescription: String,
    items: List<MenuItem>,
    modifier: Modifier = Modifier,
    tint: Color = DsTheme.colors.labelTertiary,
    iconSize: Dp = 20.dp,
    bordered: Boolean = false,
    selected: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        DsIconButton(
            icon = icon,
            contentDescription = contentDescription,
            onClick = { expanded = true },
            tint = tint,
            iconSize = iconSize,
            bordered = bordered,
            selected = selected,
        )
        DsMenuPopup(expanded = expanded, onDismiss = { expanded = false }, items = items)
    }
}
