package com.labteto.dshmobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * DSH Mobile's shape and size system, from the "A · Ink" design kit.
 *
 * Every surface in the app falls into exactly one of four roles. Pick the role, then the token; if a
 * new surface seems to need a radius or height that none of these give, it is almost certainly in
 * the wrong role.
 *
 * ## 1. Containers: they hold other things
 * | Surface | Token | Radius |
 * |---|---|---|
 * | Cards, list cards, needs-you card, tool groups | [DsShapes.block] | 18dp |
 * | Approval / question / plan cards | [DsShapes.approvalCard] | 18dp |
 * | Chat composer card | [DsShapes.composer] | 20dp |
 * | Bottom sheets and the details panel edge | [DsShapes.sheet] | 28dp, top only |
 * | Dialogs | [DsShapes.dialog] | 24dp |
 * | Menus and toasts | [DsShapes.menu] / [DsShapes.toast] | 14dp |
 * | Code blocks and command strips | [DsShapes.code] | 12dp |
 *
 * ## 2. Interactive controls: one corner family, 12dp
 * Buttons, text and search inputs, segmented controls, the model chip, bordered icon buttons, and
 * the picker/option rows all use [DsShapes.control]. Only the full-width call-to-action
 * ([DsShapes.buttonLarge], 52dp tall) steps up to 14dp. There is no 10dp or 8dp control any more:
 * `buttonSmall` is kept as an alias so old call sites keep compiling, and it is also 12dp.
 *
 * Controls that sit side by side share one height from [DsSizes]: a search field, the button next
 * to it and a segmented rail are all [DsSizes.field] (48dp); an ordinary button is
 * [DsSizes.button] (44dp); an inline button inside a row is [DsSizes.buttonCompact] (36dp).
 * A bordered icon button that sits next to an input is the input's twin: same 48dp height, same
 * 12dp radius, same `bgLayer2` fill, same 1dp `borderL2` line.
 *
 * ## 3. Pills and tags: they label or filter
 * Status tags, counts and filter chips are fully round ([DsShapes.pillFull]); a non-interactive
 * `.tag` badge is the small 6dp [DsShapes.chip]. A chip's *visual* height is [DsSizes.chip] (36dp)
 * or [DsSizes.tag] (24dp), and its touch target is always at least [DsSizes.touchTarget] (48dp):
 * grow the hit area with padding or `minimumInteractiveComponentSize`, never the drawn pill.
 *
 * ## 4. Bare icon buttons: no chrome
 * An icon button on its own has no border and no fill. Its pressed and hovered states are a circle
 * (a 48dp touch target, a circular ripple), because a square ripple on a glyph reads as a rectangle
 * button that forgot its edge. This is what `DsIconButton` draws, and what `DsIconMenu` draws for
 * an icon that opens a menu, so a row of them (search, sort, more) always looks like one family.
 *
 * ## Rhythm
 * Screen gutter 16dp (20dp on the Sessions hero), card padding 16dp, row gap 8dp, section gap 14dp,
 * all in [DsSpacing]. Icons are 18dp inside rows and chips, 20dp in icon buttons and 24dp for the
 * hero glyphs.
 */
object DsShapes {
    /** The one corner of an interactive control: buttons, inputs, segmented rails, chips-as-controls. 12dp. */
    val control = RoundedCornerShape(12.dp)

    /** Design `.btn`. Same as [control]; the older name is kept so existing call sites read the same. */
    val buttonCapsule = control

    /** Design `.btn.lg`: the 52dp call-to-action, 14dp. */
    val buttonLarge = RoundedCornerShape(14.dp)

    /** Legacy alias of [control]. There is no separate small-control radius. */
    val buttonSmall = control

    /** The corner of one segment inside a segmented rail: the rail's 12dp minus its 3dp padding. */
    val SegmentRadius = 9.dp
    val segment = RoundedCornerShape(SegmentRadius)

    /** Chat bubbles: 18dp with a slightly tighter corner on the sender side. */
    val bubble = RoundedCornerShape(18.dp)
    val bubbleUser = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp)
    val bubbleAgent = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)

    /** Composer card: 20dp. */
    val composer = RoundedCornerShape(20.dp)

    /** Approval / question / plan cards: 18dp, the same as any other card. */
    val approvalCard = RoundedCornerShape(18.dp)

    /** Sheets: 28dp top corners only, like the design's `.sheetbox`. */
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 0.dp, bottomEnd = 0.dp)

    val dialog = RoundedCornerShape(24.dp)
    val menu = RoundedCornerShape(14.dp)
    val toast = RoundedCornerShape(14.dp)
    val tooltip = RoundedCornerShape(8.dp)

    /** Cards and code blocks. */
    val block = RoundedCornerShape(18.dp)
    val code = RoundedCornerShape(12.dp)

    /** Non-interactive tags: design `.tag`, 6dp. */
    val chip = RoundedCornerShape(6.dp)

    /** Pill-shaped labels and filters: counts, status, filter chips. */
    val pill = RoundedCornerShape(999.dp)
    val pillFull = RoundedCornerShape(999.dp)

    /** A list row's hover, press and selection fill. */
    val row = RoundedCornerShape(12.dp)
    val cube = RoundedCornerShape(16.dp)
}

/**
 * Control heights and hit targets. See the role table on [DsShapes].
 *
 * These are the *drawn* heights. The touch target is separate and never smaller than
 * [touchTarget]; a control drawn shorter than that is wrapped, not enlarged.
 */
object DsSizes {
    /** Minimum touch target for anything tappable. */
    val touchTarget = 48.dp

    /** Text and search inputs, a segmented rail's outer height, and a bordered icon button beside them. */
    val field = 48.dp

    /** Ordinary button. */
    val button = 44.dp

    /** The full-width call-to-action. */
    val buttonLarge = 52.dp

    /** Inline button inside a row or card. */
    val buttonCompact = 36.dp

    /** Filter chip and tab button: drawn at 36dp, hit area 48dp. */
    val chip = 36.dp

    /** A tappable chip in a dense row (the chat title row's preset and subagent triggers): drawn 28dp, hit area 48dp. */
    val chipCompact = 28.dp

    /** Status tag or count badge. */
    val tag = 24.dp

    /** The top bar row. */
    val topBar = 52.dp
}

val DsMaterialShapes = Shapes(
    extraSmall = DsShapes.chip,
    small = DsShapes.menu,
    medium = DsShapes.code,
    large = DsShapes.composer,
    extraLarge = DsShapes.dialog,
)
