package com.labteto.dshmobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Modal dialog: dim overlay (platform scrim ~ overlayMask), r24 bgLayer2 plate with a hairline
 * border. [content] receives a [ColumnScope] and scrolls when the dialog would outgrow the screen.
 *
 * A dialog is the easiest place for a button to disappear: the plate grows with its content, and a
 * long explanation, a list of approved builds or a keyboard could push the actions past the bottom
 * edge with nothing to scroll. The plate is therefore bounded to most of the safe area and its
 * content scrolls inside it; the system-bar and IME insets are applied in the dialog window itself.
 *
 * The window comes from [DsWindow], the only place that creates one: a hand-made Compose `Dialog`
 * is laid out inside the system bars on Android 15 and newer, which pushed a dialog's buttons
 * toward the gesture bar.
 */
@Composable
fun DsDialog(
    title: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    DsWindow(onDismissRequest = onDismiss, dimBehind = true) { insets ->
        Box(Modifier.fillMaxSize()) {
            // Tap-outside-to-dismiss, handled here instead of by the platform.
            //
            // `DialogProperties.dismissOnClickOutside` is on by default, but it is decided by
            // `Dialog.onTouchEvent` -> `Window.shouldCloseOnTouch`, which compares the touch with the
            // *decor view's* bounds. [DsWindow] makes the window cover the whole display (it has to:
            // a dialog window that fits inside the bars clips its own footer), so no touch can ever
            // land outside that view and the platform never reports one. Tapping the scrim of any
            // dialog in the app therefore did nothing — the new-session picker was just where it was
            // noticed. A layer under the plate restores the gesture; it is a sibling of the plate
            // rather than its parent, so the plate's own content is never dismissed by it.
            val dismiss by rememberUpdatedState(onDismiss)
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { dismiss() } },
            )
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(insets)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Leave a sliver of the page visible around the plate, and never more than the safe
                // area allows — the scroll region below then does the rest.
                val plateMaxHeight = maxHeight * 0.88f
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                        .heightIn(max = plateMaxHeight)
                        // The plate swallows taps so only the scrim around it dismisses. Without a
                        // pointer handler of its own the plate's blank padding would fall through to
                        // the scrim below and a tap on the card itself would close it. Child
                        // controls (rows, fields, scrolling) still win: they are hit first.
                        .pointerInput(Unit) { detectTapGestures { } },
                    shape = DsShapes.dialog,
                    color = colors.bgLayer2,
                    border = BorderStroke(1.dp, colors.borderL1),
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        title?.let {
                            Text(it, style = DsType.large20, color = colors.labelPrimary)
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            content = content,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Toast state pair: the current message ([State]) and a [show] lambda. The
 * message auto-clears 3s after the last [show] call.
 */
@Composable
fun rememberDsToast(): Pair<State<String?>, (String) -> Unit> {
    val flow = remember { MutableStateFlow<String?>(null) }
    val state = flow.collectAsState()
    val message = state.value
    LaunchedEffect(message) {
        if (message != null) {
            delay(3000)
            flow.value = null
        }
    }
    return state to { flow.value = it }
}

/**
 * Top-center toast plate driven by [rememberDsToast].
 *
 * It drops in from under the status bar and lifts away again, rather than blinking on and off:
 * a plate that appears in one frame over the top bar reads as a glitch in the chrome. The last
 * message is held while the exit runs, so the plate does not empty out as it leaves. Below the
 * status bar on purpose: the activity draws edge to edge, and a toast that starts at y = 0 hides
 * its first line behind the clock.
 */
@Composable
fun DsToastHost(
    state: Pair<State<String?>, (String) -> Unit>,
    modifier: Modifier = Modifier,
    /**
     * Keep clear of the status bar. False for a host placed inside a panel that is already below it,
     * where the extra inset would only push the plate down by the height of the clock.
     */
    belowStatusBar: Boolean = true,
) {
    val message = state.first.value
    var last by remember { mutableStateOf<String?>(null) }
    val shown = message ?: last
    SideEffect { if (message != null) last = message }
    Box(
        modifier.fillMaxWidth().then(if (belowStatusBar) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(160)) + slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)) { -it },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 2 },
        ) {
            Surface(
                shape = DsShapes.toast,
                color = DsTheme.colors.toastBg,
                shadowElevation = 6.dp,
                modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp),
            ) {
                Text(
                    shown.orEmpty(),
                    style = DsType.small13,
                    color = DsTheme.colors.onToast,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

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
internal fun DsMenuPopup(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuItem>) {
    val colors = DsTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = DsShapes.menu,
        containerColor = colors.bgLayer3,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, colors.borderL1),
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

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DsMenuPreview() {
    DshTheme {
        DsMenu(
            anchor = { DsButton("Menu", onClick = {}) },
            items = listOf(
                MenuItem("Open", icon = Icons.Filled.Edit, onClick = {}),
                MenuItem("Delete", danger = true, onClick = {}),
            ),
        )
    }
}

