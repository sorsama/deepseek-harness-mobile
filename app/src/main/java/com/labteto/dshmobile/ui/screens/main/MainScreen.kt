package com.labteto.dshmobile.ui.screens.main

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import com.labteto.dshmobile.ui.components.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.adaptive.AdaptiveShell
import com.labteto.dshmobile.ui.adaptive.ListControl
import com.labteto.dshmobile.ui.adaptive.planShell
import com.labteto.dshmobile.ui.adaptive.rememberHinge
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.input.AppShortcut
import com.labteto.dshmobile.ui.input.LocalShortcutRouter
import com.labteto.dshmobile.ui.input.matchAppShortcut
import com.labteto.dshmobile.ui.theme.DsTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The main screen, laid out for the window it is in (see `ui/adaptive`).
 *
 * A phone, and every window narrower than 600dp, gets the Discord-style shell it always had:
 *  - swipe right from the LEFT edge (or anywhere on the content) opens the chat-list drawer
 *    (ModalNavigationDrawer's built-in gesture; swipe left on the drawer
 *    content closes it, scrim tap and Back also work)
 *  - swipe left from the RIGHT edge opens the session Details panel
 *  - swipe right anywhere on the open Details panel closes it
 *
 * Wider windows dock what the phone slides in: the chat list becomes a permanent pane beside the
 * chat (medium), and on an expanded window Details becomes a pane too. A fold or hinge that splits
 * the window moves the boundary between panes onto the hinge; a half-open horizontal hinge turns
 * the chat into a transcript above the fold and the controls below it. [planShell] decides all of
 * that from numbers, and [AdaptiveShell] draws it.
 *
 * The list and the chat are `movableContentOf` inside [AdaptiveShell]: when the plan changes (a fold
 * opening, a rotation, a split-screen resize) each moves to its new place with its state, so nothing
 * the reader was doing is lost. The chat list's search and folds, the transcript's scroll position
 * and the sheet that was open all stay where they were.
 *
 * The details overlay (compact and medium) follows the finger. It used to wait for the drag to pass a
 * threshold and then play a fixed slide, so the first 12% of every swipe moved nothing and the panel
 * never felt attached to the hand. Now the drag drives the panel's position directly, and letting
 * go settles it open or closed from where it is and how fast it was going. The scrim and the blur
 * behind it track the same fraction, and the system back gesture drags it closed the same way.
 *
 * The details gesture detector only claims the drags it owns (leftward from the right edge
 * band, or any drag on the details area while it is open) and leaves every other horizontal
 * drag unconsumed. ModalNavigationDrawer puts an anchoredDraggable(Horizontal) on the whole
 * surface, so an always-consuming detector here would starve the drawer's open gesture.
 * Horizontal edge drags are axis-orthogonal to the chat list's vertical scroll, so the two
 * never conflict.
 */
@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Saveable: a recreation the manifest does not absorb (a dark-mode switch, a font size change)
    // keeps these screens open, as the configChanges it does absorb keep everything else.
    var automationsOpen by rememberSaveable { mutableStateOf(false) }
    var automationSession by rememberSaveable { mutableStateOf<String?>(null) }
    // A subagent the Details panel asked to show. The transcript is the chat screen's sheet, so the
    // request travels from the panel to the chat through here and is cleared once the chat has it.
    var subagentRequest by remember { mutableStateOf<String?>(null) }
    val store = com.labteto.dshmobile.ui.rememberSessionStore()
    val automationAvailable by store.automationAvailable.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { store.refreshSessionsIfStale() } }

    val toast = rememberDsToast()
    val unsupportedAutomation = stringResource(R.string.ux_b_automation_unavailable)
    LaunchedEffect(automationAvailable, automationsOpen) {
        if (automationAvailable == false && automationsOpen) {
            automationsOpen = false
            automationSession = null
            toast.second(unsupportedAutomation)
        }
    }

    // `detailsOpen` is the intent for the overlay; `reveal` is where the panel physically is, 0
    // closed to 1 open. A drag moves `reveal` directly and decides `detailsOpen` when it ends. Every
    // open and close goes through `settleDetails`, so a fling's velocity carries into the settle.
    // Where Details is a docked pane there is no overlay, and `detailsShown` is the pane's own switch.
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var detailsShown by rememberSaveable { mutableStateOf(true) }
    // The docked chat list's switch. Only the reader sets it; the plan decides whether it applies.
    var listShown by rememberSaveable { mutableStateOf(true) }
    val reveal = remember { Animatable(if (detailsOpen) 1f else 0f) }
    val haptics = rememberDsHaptics()
    fun settleDetails(open: Boolean, velocity: Float = 0f) {
        // Only the intent speaks: a drag that settles keeps its silence, an open/close from a tap
        // or the back gesture gets one tick.
        if (open != detailsOpen) haptics.selection()
        detailsOpen = open
        scope.launch { reveal.animateTo(if (open) 1f else 0f, PanelSpring, initialVelocity = velocity) }
    }

    val router = LocalShortcutRouter.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Keyboard: Ctrl chords go to whoever owns the command (the list, the chat, this
            // screen), and Escape puts away the drawer or the Details overlay, the transient
            // surfaces that belong to this screen rather than to a window of their own.
            .onPreviewKeyEvent { event ->
                val shortcut = matchAppShortcut(
                    key = event.key,
                    ctrl = event.isCtrlPressed,
                    shift = event.isShiftPressed,
                    alt = event.isAltPressed,
                    meta = event.isMetaPressed,
                )
                when {
                    shortcut != null -> {
                        if (event.type == KeyEventType.KeyDown) router.post(shortcut)
                        true
                    }
                    event.key == Key.Escape && event.type == KeyEventType.KeyDown &&
                        (drawerState.targetValue == DrawerValue.Open || detailsOpen) -> {
                        if (detailsOpen) settleDetails(false) else scope.launch { drawerState.close() }
                        true
                    }
                    else -> false
                }
            },
    ) {
        val hinge = com.labteto.dshmobile.ui.adaptive.rememberHinge()
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val plan = remember(maxWidth, maxHeight, hinge, rtl) { planShell(maxWidth, maxHeight, hinge, rtl) }
        // Where the chat list is, it is on screen, and it has to be kept fresh. Sessions started,
        // renamed or archived on the computer reach this list by push, and a push is never replayed
        // after a gap, so the list also re-reads when it appears and when the app comes back to the
        // front, and now and then while it stays visible. The store spaces these reads out and runs
        // them quietly; see SessionStore.refreshSessionsIfStale.
        val listVisible = if (plan.listDocked) {
            listShown || !plan.listCanCollapse
        } else {
            drawerState.targetValue == DrawerValue.Open
        }
        LaunchedEffect(listVisible, lifecycleOwner) {
            if (!listVisible) return@LaunchedEffect
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    store.refreshSessionsIfStale()
                    delay(DrawerSyncPeriodMs)
                }
            }
        }
        // A window that grew past the phone layout no longer has a drawer to leave open, and one that
        // grew into three panes no longer has an overlay: put each away rather than leave it stranded.
        LaunchedEffect(plan.listDocked) { if (plan.listDocked) drawerState.snapTo(DrawerValue.Closed) }
        LaunchedEffect(plan.detailsDockable) {
            if (plan.detailsDockable) {
                detailsOpen = false
                reveal.snapTo(0f)
            }
        }

        val currentOpenSettings = androidx.compose.runtime.rememberUpdatedState(onOpenSettings)
        // The commands that belong to the shell: revealing the session list for the ones that act
        // on it (the list itself opens the dialog or the search), showing or hiding it, settings.
        val currentPlan = androidx.compose.runtime.rememberUpdatedState(plan)
        LaunchedEffect(router) {
            router.events.collect { shortcut ->
                val now = currentPlan.value
                when (shortcut) {
                    AppShortcut.NewSession, AppShortcut.SearchSessions -> {
                        // Show the list; the list itself opens the dialog or the search.
                        if (now.listDocked) listShown = true else scope.launch { drawerState.open() }
                    }
                    AppShortcut.ToggleSessionList -> {
                        if (!now.listDocked) {
                            scope.launch { if (drawerState.isOpen) drawerState.close() else drawerState.open() }
                        } else if (now.listCanCollapse) {
                            listShown = !listShown
                        }
                    }
                    AppShortcut.FocusComposer -> if (!now.listDocked) scope.launch { drawerState.close() }
                    AppShortcut.OpenSettings -> currentOpenSettings.value()
                }
            }
        }
        // Details is one panel, built here whether it is a pane or an overlay: the same actions either way.
        val detailsContent: @Composable (Modifier, Boolean) -> Unit = { modifier, docked ->
            fun close() = if (docked) detailsShown = false else settleDetails(false)
            DetailsPanel(
                onClose = { close() },
                docked = docked,
                onOpenAutomations = {
                    automationSession = store.currentSessionId.value
                    automationsOpen = true
                    close()
                },
                onOpenSubagent = { childId ->
                    subagentRequest = childId
                    close()
                },
                // Launched here rather than in the panel: the panel is on its way out, and a
                // coroutine tied to it would be cancelled before the session finished opening.
                onOpenSession = { sessionId ->
                    scope.launch { store.openSession(sessionId) }
                    close()
                },
                modifier = modifier,
            )
        }

        val listControl = when {
            !plan.listDocked -> ListControl.OpenDrawer
            !plan.listCanCollapse -> ListControl.None
            listShown -> ListControl.Collapse
            else -> ListControl.Expand
        }
        val detailsDocked = plan.detailsDockable

        AdaptiveShell(
            plan = plan,
            drawerState = drawerState,
            listShown = listShown,
            detailsShown = detailsShown,
            list = { docked ->
                // Docked, the list stays put when a session is picked; the drawer closes itself.
                val closeList: () -> Unit = if (docked) {
                    {}
                } else {
                    { scope.launch { drawerState.close() } }
                }
                ChatListDrawer(
                    onClose = closeList,
                    onOpenSettings = { currentOpenSettings.value() },
                    onOpenAutomations = { automationSession = null; automationsOpen = true; closeList() },
                )
            },
            details = { detailsContent(Modifier.fillMaxHeight(), true) },
            chat = {
                ChatWithDetailsOverlay(
                    overlayEnabled = !detailsDocked,
                    detailsOpen = detailsOpen,
                    reveal = reveal,
                    settleDetails = ::settleDetails,
                    toast = toast,
                    chat = {
                        ChatScreen(
                            onOpenDetails = {
                                if (detailsDocked) {
                                    haptics.selection()
                                    detailsShown = true
                                } else {
                                    settleDetails(true)
                                }
                            },
                            onOpenDrawer = {
                                haptics.tap()
                                if (plan.listDocked) listShown = !listShown else scope.launch { drawerState.open() }
                            },
                            detailsOpen = if (detailsDocked) detailsShown else detailsOpen,
                            subagentRequest = subagentRequest,
                            onSubagentRequestHandled = { subagentRequest = null },
                            listControl = listControl,
                            readingWidth = plan.readingWidth,
                            tabletop = plan.tabletop,
                        )
                    },
                    details = { modifier -> detailsContent(modifier, false) },
                )
            },
        )
    }
    if (automationsOpen && automationAvailable != false) AutomationScreen(automationSession, onClose = { automationsOpen = false })
}

/**
 * The chat, with the Details overlay that slides over it.
 *
 * The overlay is sized from this pane, not the window: beside a docked chat list it covers the chat
 * only, and across a hinge it never reaches the other half. Where Details is a docked pane instead
 * ([overlayEnabled] false) none of the gesture machinery exists and this is just the chat.
 */
@Composable
private fun ChatWithDetailsOverlay(
    overlayEnabled: Boolean,
    detailsOpen: Boolean,
    reveal: Animatable<Float, AnimationVector1D>,
    settleDetails: (open: Boolean, velocity: Float) -> Unit,
    toast: Pair<State<String?>, (String) -> Unit>,
    chat: @Composable () -> Unit,
    details: @Composable (Modifier) -> Unit,
) {
    if (!overlayEnabled) {
        Box(Modifier.fillMaxSize()) {
            chat()
            DsToastHost(toast, Modifier.align(Alignment.TopCenter))
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        // Most of the pane's width, but never the whole of it: the strip of chat left showing is
        // what you tap to go back, and on a narrow phone a fixed 300dp left a 60dp sliver.
        val detailsWidth = min(maxWidth * 0.88f, 380.dp)
        val panelPx = with(density) { detailsWidth.toPx() }
        val scope = rememberCoroutineScope()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(detailsOpen, panelPx) {
                    val width = size.width.toFloat()
                    val edgeBandPx = 28.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Measured from the panel's own edge, so the same code serves RTL, where
                        // the panel and its edge band sit on the left.
                        val fromEdge = if (rtl) down.position.x else width - down.position.x
                        // Claim only gestures this screen handles: drags toward the middle that
                        // start in the edge band open details; drags starting on the open details
                        // panel close it. Everything else must stay unconsumed for the drawer.
                        val owned = if (!detailsOpen) fromEdge <= edgeBandPx else fromEdge <= panelPx
                        if (!owned) return@awaitEachGesture

                        var claimed = false
                        awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                            val inward = if (rtl) overSlop > 0f else overSlop < 0f
                            // While closed, only a drag toward the middle belongs to this screen; a
                            // drag the other way from the edge is the drawer's to open with.
                            claimed = detailsOpen || inward
                            if (claimed) change.consume()
                        } ?: return@awaitEachGesture
                        if (!claimed) return@awaitEachGesture

                        val tracker = VelocityTracker()
                        val startReveal = reveal.value
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                tracker.addPosition(change.uptimeMillis, change.position)
                                if (change.changedToUpIgnoreConsumed()) break
                                // A deeper scrollable claimed the drag; let it keep it.
                                if (change.isConsumed) break
                                val dx = change.positionChange().x
                                val inwardDx = if (rtl) dx else -dx
                                change.consume()
                                val next = (reveal.value + inwardDx / panelPx).coerceIn(0f, 1f)
                                scope.launch { reveal.snapTo(next) }
                            }
                        } finally {
                            val vx = tracker.calculateVelocity().x
                            val inwardVelocity = (if (rtl) vx else -vx) / panelPx
                            // Direction decides, not the halfway mark: a fifth of the panel's width
                            // toward closed closes it, so a short flick does what it means.
                            val moved = reveal.value - startReveal
                            val open = when {
                                abs(inwardVelocity) > FlingFraction -> inwardVelocity > 0f
                                abs(moved) > CommitFraction -> moved > 0f
                                else -> startReveal > 0.5f
                            }
                            settleDetails(open, inwardVelocity)
                        }
                    }
                },
        ) {
            // The chat softens under the open panel: a blur where the platform renders one (API 31+;
            // below that `blur` is a no-op and the scrim alone does the work).
            val progress = reveal.value
            Box(Modifier.fillMaxSize().blur((6 * progress).dp)) { chat() }

            if (progress > 0f) {
                // Tap anywhere outside the panel to close it — the same contract as the drawer's
                // scrim. Its opacity follows the panel, so a half-dragged panel dims halfway.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = progress }
                        .background(DsTheme.colors.overlayMask)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = stringResource(R.string.common_close),
                        ) { settleDetails(false, 0f) },
                )

                details(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(detailsWidth)
                        .fillMaxHeight()
                        .offset {
                            val hidden = ((1f - progress) * panelPx).roundToInt()
                            // `offset` is not mirrored, so push toward the panel's own edge.
                            IntOffset(if (rtl) -hidden else hidden, 0)
                        },
                )
            }

            // Back drags the panel closed under the thumb and lets go of it if the gesture is
            // cancelled, rather than snapping shut the moment back is pressed.
            PredictiveBackHandler(enabled = detailsOpen) { events ->
                try {
                    events.collect { event -> reveal.snapTo(1f - event.progress * BackPeek) }
                    settleDetails(false, 0f)
                } catch (cancelled: CancellationException) {
                    settleDetails(true, 0f)
                    throw cancelled
                }
            }
            DsToastHost(toast, Modifier.align(Alignment.TopCenter))
        }
    }
}

/** The settle after a drag or a tap: quick, and without the overshoot a sliding panel reads as a bounce. */
private val PanelSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 600f)

/** Panel widths per second past which a release counts as a fling, whatever the position. */
private const val FlingFraction = 0.8f

/** Share of the panel's width a drag must travel before letting go commits to its direction. */
private const val CommitFraction = 0.2f

/** How far the back gesture can pull the panel before it commits: the rest is the closing animation. */
private const val BackPeek = 0.4f

/** How often a visible sessions list re-reads itself as a fallback for pushes it missed. */
private const val DrawerSyncPeriodMs = 15_000L
