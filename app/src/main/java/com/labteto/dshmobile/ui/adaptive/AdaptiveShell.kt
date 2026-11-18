package com.labteto.dshmobile.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    preferredListWidth: androidx.compose.ui.unit.Dp? = null,
    onListWidthChange: (androidx.compose.ui.unit.Dp) -> Unit = {},
    onListShownChange: (Boolean) -> Unit = {},
    /** Large-screen overlay uses the remembered pane width; phone remains 90% wide. */
    overlayListWidth: androidx.compose.ui.unit.Dp? = null,
    overlayList: Boolean = false,
    allowListReveal: Boolean = true,
    minimumListContentWidth: androidx.compose.ui.unit.Dp = 240.dp,
) {
    val currentList by rememberUpdatedState(list)
    val currentChat by rememberUpdatedState(chat)
    val listSlot = remember { movableContentOf<Boolean> { docked -> currentList(docked) } }
    val chatSlot = remember { movableContentOf { currentChat() } }

    if (plan.kind == ShellKind.Compact || overlayList) {
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
                    modifier = if (overlayListWidth != null) Modifier.width(overlayListWidth)
                        else Modifier.fillMaxWidth(DrawerWidthFraction),
                ) { listSlot(false) }
            },
        ) {
            if (overlayList && plan.detailsDockable && detailsShown) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().consumeWindowInsets(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.End),
                    )) { chatSlot() }
                    Box(Modifier.width(plan.detailsWidth).fillMaxHeight().consumeWindowInsets(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Start),
                    )) { details() }
                }
            } else chatSlot()
        }
        return
    }

    val colors = DsTheme.colors
    val showList = plan.listDocked && (listShown || !plan.listCanCollapse)
    val showDetails = plan.detailsDockable && detailsShown
    val sides = WindowInsets.safeDrawing
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val listWidth = resolveListWidth(plan, maxWidth, preferredListWidth, detailsShown, minimumListContentWidth)
        val density = LocalDensity.current
        val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
        val currentWidth by rememberUpdatedState(listWidth)
        val changeWidth by rememberUpdatedState(onListWidthChange)
        val changeShown by rememberUpdatedState(onListShownChange)
        val scope = rememberCoroutineScope()
        val animated = remember { Animatable(if (showList) listWidth.value else 0f) }
        var dragged by remember { mutableStateOf<Float?>(null) }
        var cancelledWidth by remember { mutableStateOf<Float?>(null) }
        var openingDrag by remember { mutableStateOf(false) }
        var shownIntent by remember { mutableStateOf(showList) }
        var settling by remember { mutableStateOf<Job?>(null) }
        LaunchedEffect(showList, listWidth) {
            shownIntent = showList
            if (dragged == null) animated.animateTo(if (showList) listWidth.value else 0f, spring())
        }
        val maximumWidth = resolveListWidth(plan, maxWidth, maxWidth, detailsShown, minimumListContentWidth).value.coerceAtLeast(0f)
        val visibleWidth = (if (!plan.listCanCollapse) listWidth.value else dragged ?: cancelledWidth ?: animated.value)
            .coerceIn(0f, maximumWidth).dp
        val visible = visibleWidth > 0.dp
        fun startDrag() {
            settling?.cancel()
            openingDrag = !shownIntent
            // Regrab the rendered boundary, even while a close is still settling.
            dragged = (dragged ?: cancelledWidth ?: animated.value).coerceIn(0f, maximumWidth)
            cancelledWidth = null
        }
        fun cancelDrag() {
            val width = dragged ?: return
            settling?.cancel()
            val target = if (shownIntent) listWidth.value else 0f
            // Invalidate the gesture immediately, but keep its rendered width until the animation owns it.
            cancelledWidth = width
            dragged = null
            settling = scope.launch {
                animated.snapTo(width)
                cancelledWidth = null
                animated.animateTo(target, spring())
            }
        }
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val cancelCurrent by rememberUpdatedState({ cancelDrag() })
        androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) cancelCurrent()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        fun moveDrag(pixels: Float) {
            // Ignore late pointer events from a gesture cancelled by lifecycle or focus loss.
            val width = dragged ?: return
            val maximum = resolveListWidth(plan, maxWidth, maxWidth, detailsShown, minimumListContentWidth).value
            dragged = (width + pixels / density.density).coerceIn(0f, maximum)
        }
        fun finishDrag(velocity: Float) {
            val width = dragged ?: return
            val minimum = resolveListWidth(plan, maxWidth, 0.dp, detailsShown, minimumListContentWidth).value
            val target = if (openingDrag) {
                when {
                    velocity / density.density >= 800f -> listWidth.value
                    width >= minimum -> width.coerceAtMost(maximumWidth)
                    else -> 0f
                }
            } else settleListDrag(width, velocity / density.density, minimum, listWidth.value,
                resolveListWidth(plan, maxWidth, maxWidth, detailsShown, minimumListContentWidth).value)
            settling = scope.launch {
                animated.snapTo(width)
                dragged = null
                if (target > 0f && (!openingDrag || velocity / density.density < 800f)) changeWidth(target.dp)
                shownIntent = target > 0f
                changeShown(target > 0f)
                animated.animateTo(target, spring(), initialVelocity = velocity / density.density)
            }
        }
        if (openingDrag && dragged != null) {
            val ready = dragged!! >= resolveListWidth(plan, maxWidth, 0.dp, detailsShown, minimumListContentWidth).value
            androidx.compose.material3.Surface(
                modifier = Modifier.align(Alignment.TopCenter).zIndex(2f).testTag("list-open-hint"),
                tonalElevation = 3.dp,
            ) {
                androidx.compose.material3.Text(
                    if (ready) "Отпустите, чтобы открыть" else "Потяните дальше или быстро смахните вправо",
                )
            }
        }
        Row(Modifier.fillMaxSize()) {
            // Keep the gesture owner composed even at zero width until the finger releases.
            if (visible || dragged != null) {
                key("session-list-pane") { Box(
                    Modifier
                        .width(visibleWidth)
                        .fillMaxHeight()
                        // The list is the first pane, so it pads the start edge of the window and leaves the end.
                        .consumeWindowInsets(sides.only(WindowInsetsSides.End))
                        .listPanelDrag(plan.listCanCollapse, direction, ::startDrag, ::moveDrag, ::finishDrag,
                            { cancelDrag() }),
                ) { listSlot(true) } }
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
            key("chat-pane") { Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .consumeWindowInsets(chatInsets)
                    .listPanelDrag(plan.listCanCollapse && (showList || allowListReveal), direction, ::startDrag, ::moveDrag,
                        ::finishDrag, { cancelDrag() }),
            ) { chatSlot() } }
            if (showDetails) {
                Box(
                    Modifier
                        .width(plan.detailsWidth)
                        .fillMaxHeight()
                        .consumeWindowInsets(sides.only(WindowInsetsSides.Start)),
                ) { details() }
            }
        }
        if (showList && plan.listCanCollapse) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = visibleWidth - 12.dp)
                    .width(24.dp)
                    .fillMaxHeight()
                    .testTag("list-resize-handle")
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Increase session list width") {
                                changeWidth(resolveListWidth(plan, maxWidth, currentWidth + 32.dp, detailsShown, minimumListContentWidth)); true
                            },
                            CustomAccessibilityAction("Decrease session list width") {
                                changeWidth(resolveListWidth(plan, maxWidth, currentWidth - 32.dp, detailsShown, minimumListContentWidth)); true
                            },
                        )
                    }
                    .listPanelDrag(true, direction, ::startDrag, ::moveDrag, ::finishDrag,
                        { cancelDrag() }),
            )
        }
    }
}
