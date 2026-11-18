package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.common_close
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Closes the enclosing [DsBottomSheet] the way a swipe does — the sheet slides away — and only then
 * runs the action it was given. Outside a sheet it runs the action straight away.
 *
 * A sheet whose owner drops it from composition in the same breath as a pick vanishes mid-frame,
 * and whatever the pick opens next (a confirmation dialog, a keyboard) opens over a window that is
 * still tearing down. Hiding first keeps the two apart, and a second tap during the slide is
 * ignored rather than acted on twice.
 */
val LocalDsSheetHide = compositionLocalOf<(then: () -> Unit) -> Unit> { { then -> then() } }

/**
 * Pushes the sheet's content down by exactly as much as the status bar still covers of it, and by
 * nothing when the sheet's top edge is already below the bar.
 *
 * The sheet used to pad its content by the status bar's height always, so a one-row sheet whose top
 * edge sat halfway down the screen drew that much empty surface above the grabber. Only a sheet that
 * reaches the top of the screen (a long catalog, or any sheet with the keyboard up, which leaves it
 * no room to start lower) needs the room, and then it needs just the part of the bar that overlaps.
 * The sheet's top edge is its drag offset, which the sheet state publishes; reading it while the
 * spacer is measured makes the spacer follow the sheet as it moves and as the keyboard comes and goes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusBarClearance(state: SheetState) {
    val density = LocalDensity.current
    val barPx = WindowInsets.systemBars.getTop(density)
    Spacer(
        Modifier.layout { _, _ ->
            // Before the sheet has anchors it has no offset yet: it is still being placed far below the bar.
            val top = try {
                state.requireOffset()
            } catch (_: IllegalStateException) {
                Float.POSITIVE_INFINITY
            }
            val covered = if (top.isFinite()) (barPx - top).roundToInt().coerceAtLeast(0) else 0
            layout(0, covered) {}
        },
    )
}

/** Test tag of the close button every titled [DsBottomSheet] draws at the end of its title row. */
const val DS_SHEET_CLOSE_TAG = "ds-sheet-close"

/**
 * The app's sheet surface — the design kit's `.sheetbox`: `bgLayer2`, 28dp top corners, a 40×4dp
 * grabber, 16dp gaps and a 20dp side padding. [trailing] holds an optional action beside the title;
 * a close button always follows it at the row's end, so a caller never adds its own. A sheet with no
 * [title] (the hold menu, the connect screen's pickers) has no header row and so no button: those are
 * short lists that never fill the screen, and the scrim and back close them.
 *
 * Sheets rather than dialogs for pickers: they arrive from the thumb's end of the screen, size
 * themselves to their content, and let a long list scroll without fighting a fixed-height plate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DsBottomSheet(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val haptics = rememberDsHaptics()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hide = remember(state, scope) {
        var hiding = false
        val request: (() -> Unit) -> Unit = { then ->
            if (!hiding) {
                hiding = true
                // Completion rather than success: if the sheet is torn down mid-slide the pick
                // still happened, and dropping it would be the freeze this exists to prevent.
                scope.launch { state.hide() }.invokeOnCompletion { then() }
            }
        }
        request
    }
    ModalBottomSheet(
        onDismissRequest = {
            haptics.tap()
            onDismiss()
        },
        sheetState = state,
        modifier = modifier,
        shape = DsShapes.sheet,
        containerColor = colors.bgLayer2,
        scrimColor = colors.overlayMask,
        dragHandle = null,
        // The sides and the bottom of the safe area, joined with the keyboard: the activity draws
        // edge to edge, so nothing resizes for the IME, and a sheet that ignored it left the sign-in
        // token field and every sheet search under the keyboard. The union takes the larger of the
        // bar and the keyboard on each side, and the sheet's own scroll regions shrink to what is
        // left. The top is left out on purpose: a sheet whose catalog outgrows the screen is
        // expanded to the top and must not put its title or the close affordance under the status
        // bar, but the bar's whole height used to be added even to a short sheet that starts far
        // below it, leaving a wide empty band above the grabber. [StatusBarClearance] adds only the
        // part of the bar that actually overlaps.
        contentWindowInsets = {
            WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                .union(WindowInsets.ime)
        },
    ) {
        StatusBarClearance(state)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Escape closes the sheet the way a swipe does, for a person at a keyboard.
                .dismissOnEscape { hide { onDismiss() } }
                .dismissOnBackKey { hide { onDismiss() } }
                .padding(horizontal = DsSpacing.large, vertical = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            // A short grabber stands in for the platform drag handle so the sheet still reads as
            // draggable without the default's heavy vertical padding.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Spacer(
                    Modifier
                        .fillMaxWidth(0.12f)
                        .height(4.dp)
                        .clip(DsShapes.pillFull)
                        .background(colors.borderL3),
                )
            }
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = DsSpacing.small, bottom = DsSpacing.tiny),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        // The whole width, not the title's measured one: Compose lays text out at
                        // its measured width, and with the style's negative letter spacing Arabic
                        // shapes a few pixels wider than it measures, so "السجل" broke before its
                        // last letter. fillMaxWidth makes the layout width the column's.
                        Text(title, style = DsType.large20, color = colors.labelPrimary, modifier = Modifier.fillMaxWidth())
                        if (subtitle != null) {
                            Text(subtitle, style = DsType.small13, color = colors.labelTertiary)
                        }
                    }
                    trailing?.invoke()
                    // A way out that needs no gesture. Swipe, scrim and back are all still there,
                    // but a tall sheet leaves no scrim to tap (and none at all with the keyboard
                    // up), and a swipe from the middle of a long scrolled list scrolls the list
                    // instead of the sheet. The title row is not part of any scrolling region, so
                    // this button and a drag on the row are reachable however far the content
                    // below has been scrolled. It goes through the same slide-away as Escape, and
                    // `hide` ignores a second tap during the slide, so the owner hears `onDismiss`
                    // once.
                    DsIconButton(
                        icon = DsIcons.Close,
                        contentDescription = stringResource(Res.string.common_close),
                        onClick = { hide { onDismiss() } },
                        modifier = Modifier.testTag(DS_SHEET_CLOSE_TAG),
                    )
                }
            }
            CompositionLocalProvider(LocalDsSheetHide provides hide) { content() }
            Spacer(Modifier.height(DsSpacing.medium))
        }
    }
}

