package com.labteto.dshmobile.ui.adaptive

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.WindowWidthSizeClass

/*
 * The adaptive shell's layout decision, as plain functions of numbers.
 *
 * Nothing here touches a View, an Activity or Compose state: the window's size, the fold that
 * crosses it (if one does) and the layout direction go in, a [ShellPlan] comes out. That is the
 * point. The rules about *which panes exist* and *where the hinge falls* are the part of an
 * adaptive layout that is easy to get subtly wrong and impossible to eyeball on every device, so
 * they live where a unit test can feed them a Pixel Fold, a Flip propped open like a laptop and a
 * Galaxy Z Fold in split screen, and the composables only draw what the plan says.
 *
 * The size classes are the Material 3 ones (compact under 600dp, medium under 840dp, expanded from
 * there; height compact under 480dp, medium under 900dp), taken from Jetpack WindowManager's
 * `WindowSizeClass` so the thresholds are the platform's, not ours. The fold geometry is
 * `FoldingFeature`'s, copied into [Hinge] so these functions do not need an Android class.
 */

/** Material's three window widths, as the shell sees them. See [ShellPlan.kind] for what each shows. */
enum class ShellKind {
    /**
     * One pane. The chat list is a drawer and Details an overlay: exactly the phone layout, and
     * what a Flip's inner screen, its cover screen and a tabletop-posture window get.
     */
    Compact,

    /** The chat list docked beside the chat. Details stays an overlay on the chat. */
    ListDocked,

    /** Chat list, chat and Details, side by side. Details is a pane the reader can hide. */
    ThreePane,
}

/** How the device is held, as far as the layout is concerned. */
enum class Posture {
    /** No fold in the way: a phone, a tablet, or a foldable lying flat with a seamless crease. */
    Flat,

    /** A vertical fold that cuts the window in two side by side (a book, or a dual-screen hinge). */
    Book,

    /** A horizontal fold that cuts the window in two, one above the other (a laptop-like Flip). */
    Tabletop,
}

/**
 * A fold or hinge that reaches the window, in the window's own coordinates, in dp.
 *
 * The same facts `FoldingFeature` reports. [separating] means the fold splits the display into two
 * logical areas (a half-open fold always does); [occluding] means there is no picture under it at
 * all, as on a dual-screen device. A seamless flat crease is neither, and is not a reason to lay
 * anything out differently.
 */
data class Hinge(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** True when the fold runs top to bottom, which cuts the window into a left and a right part. */
    val vertical: Boolean,
    val halfOpened: Boolean,
    val separating: Boolean,
    val occluding: Boolean,
) {
    /** True when the layout has to keep to one side of this hinge. */
    val splitsWindow: Boolean get() = separating || occluding
}

/** The band a horizontal hinge covers, in the window's coordinates. */
data class TabletopSplit(val top: Dp, val bottom: Dp)

/**
 * What the shell should be, for a window of a given size and fold.
 *
 * Widths are along the layout direction: [listWidth] is the start pane whichever physical side
 * that is, so the composables mirror for right-to-left with no case of their own.
 */
data class ShellPlan(
    val kind: ShellKind,
    val posture: Posture,
    val widthClass: WindowWidthSizeClass,
    val heightClass: WindowHeightSizeClass,
    /** The docked chat list's width, or zero where the list is a drawer. */
    val listWidth: Dp,
    /** Empty space after the list pane: the width a hinge occupies between it and the chat. */
    val listGap: Dp,
    /** The docked Details pane's width, or zero where Details is an overlay. */
    val detailsWidth: Dp,
    /** False when the list pane is one half of a hinge split: hiding it would put the chat on the crease. */
    val listCanCollapse: Boolean,
    /** The widest the transcript and composer grow before they are centred and stop. */
    val readingWidth: Dp,
    /** Set in [Posture.Tabletop]: where the chat column divides into transcript above and controls below. */
    val tabletop: TabletopSplit?,
) {
    /** True when the chat list is a pane rather than a drawer. */
    val listDocked: Boolean get() = kind != ShellKind.Compact

    /** True when Details is a pane rather than an overlay. */
    val detailsDockable: Boolean get() = kind == ShellKind.ThreePane
}

/** The list pane's share of the window, before it is clamped to [ListPaneMin]..[ListPaneMax]. */
internal const val ListPaneFraction = 0.36f

/**
 * The narrowest and widest the docked chat list is, when no hinge decides its width. 300dp is where
 * the list's own header ("Sessions" and its three actions) stops truncating in English; 360dp is
 * Material's navigation drawer width and about what the phone drawer already gives (90% of 412dp),
 * which is where longer scripts such as Arabic keep their title. Wider is only whitespace.
 */
val ListPaneMin = 300.dp
val ListPaneMax = 360.dp

/** The docked Details pane. Narrower than the overlay's 380dp cap: it shares the screen. */
val DetailsPaneWidth = 340.dp

/** The least width the chat keeps beside a docked list, anywhere a hinge splits the window. */
val ChatPaneMin = 320.dp

/**
 * The least width the chat keeps when Details is docked beside it. Below this the pane would turn
 * the conversation into a column of three-word lines, so Details stays an overlay instead.
 */
val ChatPaneMinWithDetails = 480.dp

/**
 * The widest a transcript and composer stay before they centre. Around 75 characters of body text
 * at the app's type size, which is the comfortable end of the 45 to 90 a line should hold; past it
 * the eye loses the next line on a wide screen.
 */
val ReadingWidthMax = 780.dp

/** The smallest the list may be when a hinge sets its width. */
val ListPaneHingeMin = 240.dp

/** The room a tabletop split keeps for the transcript above the hinge, and for the controls below it. */
val TabletopMinTop = 120.dp
val TabletopMinBottom = 150.dp

/**
 * Chooses the shell for a window.
 *
 * Rules, in order:
 *  1. A horizontal hinge that splits the window (a Flip propped like a laptop) is a tabletop: one
 *     pane, the conversation above the hinge and the controls below it. Docked panes are dropped
 *     there, because a full-height list or Details pane would run straight across the crease.
 *  2. Otherwise the width class decides: compact is the phone layout; medium docks the list;
 *     expanded docks the list and, if the chat keeps [ChatPaneMinWithDetails], Details as well.
 *  3. A window under 480dp tall (a landscape phone, a Flip's cover screen) never gets more than the
 *     list docked: three panes across 900dp that are 360dp high is a worse reading column than the
 *     phone layout.
 *  4. A vertical hinge that splits the window moves the pane boundary onto the hinge, so the chat
 *     list is the whole of one half and the chat (and Details, when it fits) the other, and nothing
 *     sits on the crease. Where either half is too narrow to hold its pane the hinge is ignored and
 *     the unsplit sizes apply.
 */
fun planShell(width: Dp, height: Dp, hinge: Hinge?, rtl: Boolean): ShellPlan {
    val sizeClass = WindowSizeClass.compute(width.value, height.value)
    val widthClass = sizeClass.windowWidthSizeClass
    val heightClass = sizeClass.windowHeightSizeClass

    fun plan(
        kind: ShellKind,
        posture: Posture = Posture.Flat,
        listWidth: Dp = 0.dp,
        listGap: Dp = 0.dp,
        detailsWidth: Dp = 0.dp,
        listCanCollapse: Boolean = true,
        tabletop: TabletopSplit? = null,
    ) = ShellPlan(
        kind = kind,
        posture = posture,
        widthClass = widthClass,
        heightClass = heightClass,
        listWidth = listWidth,
        listGap = listGap,
        detailsWidth = detailsWidth,
        listCanCollapse = listCanCollapse,
        readingWidth = ReadingWidthMax,
        tabletop = tabletop,
    )

    // 1. Tabletop: the window is cut into a top and a bottom part.
    if (hinge != null && !hinge.vertical && hinge.splitsWindow) {
        val top = hinge.top.dp
        val bottom = hinge.bottom.dp
        if (top >= TabletopMinTop && height - bottom >= TabletopMinBottom) {
            return plan(ShellKind.Compact, Posture.Tabletop, tabletop = TabletopSplit(top, bottom))
        }
    }

    // 2 and 3. The pane count the window's size would give, with no fold in the way.
    val sized = when {
        widthClass == WindowWidthSizeClass.COMPACT -> ShellKind.Compact
        heightClass == WindowHeightSizeClass.COMPACT -> ShellKind.ListDocked
        widthClass == WindowWidthSizeClass.MEDIUM -> ShellKind.ListDocked
        else -> ShellKind.ThreePane
    }
    if (sized == ShellKind.Compact) return plan(ShellKind.Compact)

    // 4. A vertical hinge: the list is one half, everything else the other.
    if (hinge != null && hinge.vertical && hinge.splitsWindow) {
        val startEdge = if (rtl) width.value - hinge.right else hinge.left
        val endEdge = if (rtl) width.value - hinge.left else hinge.right
        val start = startEdge.dp
        val end = width - endEdge.dp
        val gap = (endEdge - startEdge).coerceAtLeast(0f).dp
        if (start >= ListPaneHingeMin && end >= ChatPaneMin) {
            val detailsFits = sized == ShellKind.ThreePane && end >= ChatPaneMinWithDetails + DetailsPaneWidth
            return plan(
                kind = if (detailsFits) ShellKind.ThreePane else ShellKind.ListDocked,
                posture = Posture.Book,
                listWidth = start,
                listGap = gap,
                detailsWidth = if (detailsFits) DetailsPaneWidth else 0.dp,
                listCanCollapse = false,
            )
        }
    }

    // No hinge decides it: size the panes by the window.
    val list = (width * ListPaneFraction).coerceIn(ListPaneMin, ListPaneMax)
    val chatBesideList = width - list
    if (chatBesideList < ChatPaneMin) return plan(ShellKind.Compact)
    if (sized == ShellKind.ThreePane && chatBesideList - DetailsPaneWidth >= ChatPaneMinWithDetails) {
        return plan(ShellKind.ThreePane, listWidth = list, detailsWidth = DetailsPaneWidth)
    }
    return plan(ShellKind.ListDocked, listWidth = list)
}

/**
 * The margin each side of a column that is held to [readingWidth] inside a pane [paneWidth] wide.
 * Zero where the pane is no wider than the column, and where there is no cap ([Dp.Unspecified]).
 */
fun readingGutter(paneWidth: Dp, readingWidth: Dp): Dp =
    if (readingWidth.isSpecified) ((paneWidth - readingWidth) / 2).coerceAtLeast(0.dp) else 0.dp

/** Where a tabletop chat column divides, in the column's own coordinates. */
data class TabletopRegions(
    /** The transcript's height: it ends at the hinge. */
    val topHeight: Dp,
    /** The space the hinge takes between the transcript and the controls; nothing is drawn in it. */
    val gap: Dp,
)

/**
 * Divides a chat column at the hinge.
 *
 * [columnTop] is where the column starts in the window, [columnHeight] how tall it is right now.
 * That height shrinks when the keyboard opens, so the hinge stays where the device is folded while
 * the bottom half gets smaller: the controls keep [TabletopMinBottom], and the transcript gives up
 * what is needed to leave it. When even that does not fit (a keyboard that fills the bottom half
 * and more) the result is null and the column is laid out as an ordinary one: a composer that
 * crosses the crease is better than one that is not there.
 */
fun tabletopRegions(
    columnTop: Dp,
    columnHeight: Dp,
    split: TabletopSplit,
    minTop: Dp = TabletopMinTop,
    minBottom: Dp = TabletopMinBottom,
): TabletopRegions? {
    val hingeTop = split.top - columnTop
    val gap = (split.bottom - split.top).coerceAtLeast(0.dp)
    if (hingeTop < minTop) return null
    // The tallest the transcript may be while the controls below the gap still have their minimum.
    val room = columnHeight - gap - minBottom
    if (room < minTop) return null
    return if (hingeTop <= room) {
        TabletopRegions(topHeight = hingeTop, gap = gap)
    } else {
        // The keyboard has eaten into the bottom half: the transcript yields, and the hinge no
        // longer divides anything, so there is no gap to leave.
        TabletopRegions(topHeight = columnHeight - minBottom, gap = 0.dp)
    }
}
