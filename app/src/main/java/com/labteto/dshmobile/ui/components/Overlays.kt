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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
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
                    .heightIn(max = plateMaxHeight),
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

