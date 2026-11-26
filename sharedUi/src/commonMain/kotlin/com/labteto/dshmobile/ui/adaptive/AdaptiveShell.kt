package com.labteto.dshmobile.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsTheme

/** What the chat's top bar offers for the chat list, which depends on where the list lives. */
enum class ListControl {
    /** The list is a drawer: the button opens it. */
    OpenDrawer,

    /** The list is a pane and showing: the button hides it. */
    Collapse,

    /** The list is a pane and hidden: the button brings it back. */
    Expand,

    /** The list is half of a hinge split and always there: no button. */
    None,
}

/** The share of a phone's width the chat list drawer takes; the rest is the strip you tap to go back. */
private const val DrawerWidthFraction = 0.9f

/**
 * The frame the main screen sits in: where the chat list, the chat and Details go for a [ShellPlan].
 *
 *  - [ShellKind.Compact]: a modal drawer holds the list, the chat fills the window. Today's phone
 *    layout, untouched, and what every posture without room for panes falls back to.
 *  - [ShellKind.ListDocked]: the list is a permanent pane at the start, the chat takes the rest.
 *  - [ShellKind.ThreePane]: list, chat, and (while [detailsShown]) Details at the end.
 *
 * The regions arrive as slots and are each composed once, here, in whichever place the plan puts
 * them. The list and the chat are held in `movableContentOf`, so a fold, an unfold or a resize that
 * changes the plan moves a pane without rebuilding it: the transcript keeps its scroll position, the
 * list its search and folds, an open sheet stays open and the composer keeps what was typed. (The
 * docked Details pane is not a different pane in the next plan, so it is simply composed where it is.)
 *
 * Window insets are applied by the panes themselves (they were written to draw edge to edge). Side
 * by side, each of them would pad the system bar at *both* of its edges, which is right only at the
 * edge of the window, so the shell tells every pane which inset it must not apply again.
 */
@Composable
fun AdaptiveShell(
    plan: ShellPlan,
    drawerState: DrawerState,
    /** Whether the user wants the docked list showing (ignored where the list cannot collapse). */
    listShown: Boolean,
    /** Whether the user wants the docked Details pane showing. */
    detailsShown: Boolean,
    /** The chat list. The flag says whether it is a docked pane (true) or inside the drawer (false). */
    list: @Composable (docked: Boolean) -> Unit,
    /** The docked Details pane; only composed while the plan has one and [detailsShown]. */
    details: @Composable () -> Unit,
    chat: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentList by rememberUpdatedState(list)
    val currentChat by rememberUpdatedState(chat)
    val listSlot = remember { movableContentOf<Boolean> { docked -> currentList(docked) } }
    val chatSlot = remember { movableContentOf { currentChat() } }

    if (plan.kind == ShellKind.Compact) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            modifier = modifier,
            drawerContent = {
                // The sheet supplies the system back handling, predictive back included: the drawer
                // shrinks toward its edge as the back gesture is dragged, and back with the drawer open
                // closes the drawer instead of leaving the app.
                ModalDrawerSheet(
                    drawerState = drawerState,
                    drawerShape = RectangleShape,
                    drawerContainerColor = DsTheme.colors.sidebar,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.fillMaxWidth(DrawerWidthFraction),
                ) { listSlot(false) }
            },
        ) { chatSlot() }
        return
    }

    val colors = DsTheme.colors
    val showList = plan.listDocked && (listShown || !plan.listCanCollapse)
    val showDetails = plan.detailsDockable && detailsShown
    val sides = WindowInsets.safeDrawing
    Row(modifier.fillMaxSize()) {
        if (showList) {
            Box(
                Modifier
                    .width(plan.listWidth)
                    .fillMaxHeight()
                    // The list is the first pane, so it pads the start edge of the window and leaves the end.
                    .consumeWindowInsets(sides.only(WindowInsetsSides.End)),
            ) { listSlot(true) }
            if (plan.listGap > 0.dp) {
                // A hinge between the list and the chat: nothing is drawn on it, and nothing sits on it.
                Box(Modifier.width(plan.listGap).fillMaxHeight().background(colors.bgBase))
            } else {
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.borderL2))
            }
        }
        var chatInsets = WindowInsets(0, 0, 0, 0)
        if (showList) chatInsets = chatInsets.add(sides.only(WindowInsetsSides.Start))
        if (showDetails) chatInsets = chatInsets.add(sides.only(WindowInsetsSides.End))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .consumeWindowInsets(chatInsets),
        ) { chatSlot() }
        if (showDetails) {
            Box(
                Modifier
                    .width(plan.detailsWidth)
                    .fillMaxHeight()
                    .consumeWindowInsets(sides.only(WindowInsetsSides.Start)),
            ) { details() }
        }
    }
}
