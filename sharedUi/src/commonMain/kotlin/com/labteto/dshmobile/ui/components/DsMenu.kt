package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/** One entry in a [DsMenu]. */
data class MenuItem(
    val text: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    /**
     * Non-null for a menu that picks one of several (a sort order, a language): the row gets a
     * leading slot that holds a check mark when true and stays empty when false, so the labels line
     * up whichever one is current. Ignored when [icon] is set.
     */
    val selected: Boolean? = null,
    val onClick: () -> Unit,
)

/**
 * Dropdown menu anchored to [anchor]; r12 bgLayer3 surface with h40 r10 cells,
 * hover fill, and danger rows in error/dangerHover.
 *
 * The anchor is given a 48dp touch target and announced as a button, whatever size it draws: an
 * 18dp overflow glyph was the whole target of the queue's menu.
 */
@Composable
fun DsMenu(
    anchor: @Composable () -> Unit,
    items: List<MenuItem>,
    /**
     * The press highlight's shape. A circle by default, because an anchor is almost always a bare
     * icon and a square ripple on a glyph reads as a rectangle button that lost its border; a
     * control-shaped anchor (a select, a button) passes [DsShapes.control].
     */
    anchorShape: Shape = CircleShape,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .defaultMinSize(minWidth = DsSpacing.touchTarget, minHeight = DsSpacing.touchTarget)
                .clip(anchorShape)
                .clickable(role = Role.Button) { expanded = true },
            contentAlignment = Alignment.Center,
        ) { anchor() }
        DsMenuPopup(expanded = expanded, onDismiss = { expanded = false }, items = items)
    }
}

/** The dropdown itself: shared by [DsMenu] and [DsIconMenu] so every menu in the app looks the same. */
@Composable
fun DsMenuPopup(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuItem>) {
    val colors = DsTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = DsShapes.menu,
        containerColor = colors.bgLayer3,
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderL1),
    ) {
        items.forEach { item ->
            DropdownMenuItem(
                text = {
                    Text(
                        item.text,
                        style = DsType.std14,
                        color = if (item.danger) colors.error else colors.labelPrimary,
                    )
                },
                leadingIcon = when {
                    item.icon != null -> {
                        {
                            Icon(
                                item.icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (item.danger) colors.error else colors.labelSecondary,
                            )
                        }
                    }
                    item.selected != null -> {
                        {
                            if (item.selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = colors.accent,
                                )
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                        }
                    }
                    else -> null
                },
                onClick = {
                    onDismiss()
                    item.onClick()
                },
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(DsShapes.control)
                    .then(if (item.selected != null) Modifier.semantics { selected = item.selected } else Modifier),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Preview
@Composable
private fun DsMenuPreview() {
    DshTheme {
        DsMenu(
            anchor = { DsButton("Menu", onClick = {}) },
            items = listOf(
                MenuItem("Open", icon = Icons.Filled.Check, onClick = {}),
                MenuItem("Delete", danger = true, onClick = {}),
            ),
        )
    }
}


