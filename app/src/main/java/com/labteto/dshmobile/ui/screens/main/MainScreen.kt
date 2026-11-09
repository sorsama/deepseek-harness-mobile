package com.labteto.dshmobile.ui.screens.main

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
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
import com.labteto.dshmobile.ui.components.DsToastHost
import com.labteto.dshmobile.ui.components.rememberDsHaptics
import com.labteto.dshmobile.ui.components.rememberDsToast
import com.labteto.dshmobile.ui.theme.DsTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Discord-style shell:
 *  - swipe right from the LEFT edge (or anywhere on the content) opens the chat-list drawer
 *    (ModalNavigationDrawer's built-in gesture; swipe left on the drawer
 *    content closes it, scrim tap and Back also work)
 *  - swipe left from the RIGHT edge opens the session Details panel
 *  - swipe right anywhere on the open Details panel closes it
 *
 * The details panel follows the finger. It used to wait for the drag to pass a threshold and then
 * play a fixed slide, so the first 12% of every swipe moved nothing and the panel never felt
 * attached to the hand. Now the drag drives the panel's position directly, and letting go settles
 * it open or closed from where it is and how fast it was going. The scrim and the blur behind it
 * track the same fraction, and the system back gesture drags it closed the same way.
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
    var automationsOpen by remember { mutableStateOf(false) }
    var automationSession by remember { mutableStateOf<String?>(null) }
    // A subagent the Details panel asked to show. The transcript is the chat screen's sheet, so the
    // request travels from the panel to the chat through here and is cleared once the chat has it.
    var subagentRequest by remember { mutableStateOf<String?>(null) }
    val store = com.labteto.dshmobile.ui.rememberSessionStore()
    val automationAvailable by store.automationAvailable.collectAsStateWithLifecycle()
    // Sessions started, renamed or archived on the computer reach this list by push, and a push is
    // never replayed after a gap, so the drawer also re-reads the list when it opens and when the
    // app comes back to the front, and now and then while it stays open. The store spaces these
    // reads out and runs them quietly; see SessionStore.refreshSessionsIfStale.
    val lifecycleOwner = LocalLifecycleOwner.current
    val drawerWanted = drawerState.targetValue == DrawerValue.Open
    LaunchedEffect(drawerWanted, lifecycleOwner) {
        if (!drawerWanted) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                store.refreshSessionsIfStale()
                delay(DrawerSyncPeriodMs)
            }
        }
    }
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

    // `detailsOpen` is the intent; `reveal` is where the panel physically is, 0 closed to 1 open.
    // A drag moves `reveal` directly and decides `detailsOpen` when it ends. Every open and close
    // goes through `settleDetails`, so a fling's velocity carries into the settle.
    var detailsOpen by remember { mutableStateOf(false) }
    val reveal = remember { Animatable(0f) }
    val haptics = rememberDsHaptics()
    fun settleDetails(open: Boolean, velocity: Float = 0f) {
        // Only the intent speaks: a drag that settles keeps its silence, an open/close from a tap
        // or the back gesture gets one tick.
        if (open != detailsOpen) haptics.selection()
        detailsOpen = open
        scope.launch { reveal.animateTo(if (open) 1f else 0f, PanelSpring, initialVelocity = velocity) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
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
            ) {
                ChatListDrawer(
                    onClose = { scope.launch { drawerState.close() } },
                    onOpenSettings = onOpenSettings,
                    onOpenAutomations = { automationSession = null; automationsOpen = true; scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            // Most of a phone's width, but never the whole of it: the strip of chat left showing is
            // what you tap to go back, and on a narrow phone a fixed 300dp left a 60dp sliver.
            val detailsWidth = min(maxWidth * 0.88f, 380.dp)
            val panelPx = with(density) { detailsWidth.toPx() }

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
                Box(Modifier.fillMaxSize().blur((6 * progress).dp)) {
                    ChatScreen(
                        onOpenDetails = { settleDetails(true) },
                        onOpenDrawer = {
                            haptics.tap()
                            scope.launch { drawerState.open() }
                        },
                        detailsOpen = detailsOpen,
                        subagentRequest = subagentRequest,
                        onSubagentRequestHandled = { subagentRequest = null },
                    )
                }

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
                            ) { settleDetails(false) },
                    )

                    DetailsPanel(
                        onClose = { settleDetails(false) },
                        onOpenAutomations = {
                            automationSession = store.currentSessionId.value
                            automationsOpen = true
                            settleDetails(false)
                        },
                        onOpenSubagent = { childId ->
                            subagentRequest = childId
                            settleDetails(false)
                        },
                        // Launched here rather than in the panel: the panel is on its way out, and a
                        // coroutine tied to it would be cancelled before the session finished opening.
                        onOpenSession = { sessionId ->
                            scope.launch { store.openSession(sessionId) }
                            settleDetails(false)
                        },
                        modifier = Modifier
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
                        settleDetails(false)
                    } catch (cancelled: CancellationException) {
                        settleDetails(true)
                        throw cancelled
                    }
                }
                DsToastHost(toast, Modifier.align(Alignment.TopCenter))
            }
        }
    }
    if (automationsOpen && automationAvailable != false) AutomationScreen(automationSession, onClose = { automationsOpen = false })
}

/** The settle after a drag or a tap: quick, and without the overshoot a sliding panel reads as a bounce. */
private val PanelSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 600f)

/** Panel widths per second past which a release counts as a fling, whatever the position. */
private const val FlingFraction = 0.8f

/** Share of the panel's width a drag must travel before letting go commits to its direction. */
private const val CommitFraction = 0.2f

/** How far the back gesture can pull the panel before it commits: the rest is the closing animation. */
private const val BackPeek = 0.4f

/** The chat list keeps a sliver of the conversation visible, so the scrim has somewhere to be tapped. */
private const val DrawerWidthFraction = 0.9f

/** How often an open sessions drawer re-reads the list as a fallback for pushes it missed. */
private const val DrawerSyncPeriodMs = 15_000L
