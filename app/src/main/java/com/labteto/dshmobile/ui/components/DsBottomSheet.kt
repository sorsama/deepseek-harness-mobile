package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.coroutines.launch

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
 * The app's sheet surface — the design kit's `.sheetbox`: `bgLayer2`, 28dp top corners, a 40×4dp
 * grabber, 16dp gaps and a 20dp side padding. [trailing] holds an optional action beside the title.
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
        // The whole safe area, not just the nav bar: a sheet whose catalog outgrows the screen is
        // expanded to the top, and its content must not slide under the status bar (the title and
        // the close affordance sat clipped behind the clock before this). The keyboard is part of
        // it: the activity draws edge to edge, so nothing resizes for the IME, and a sheet that
        // ignored it left the sign-in token field and every sheet search under the keyboard. The
        // union takes the larger of the bar and the keyboard on each side, and the sheet's own
        // scroll regions shrink to what is left.
        contentWindowInsets = { WindowInsets.systemBars.union(WindowInsets.ime) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                        Text(title, style = DsType.large20, color = colors.labelPrimary)
                        if (subtitle != null) {
                            Text(subtitle, style = DsType.small13, color = colors.labelTertiary)
                        }
                    }
                    trailing?.invoke()
                }
            }
            CompositionLocalProvider(LocalDsSheetHide provides hide) { content() }
            Spacer(Modifier.height(DsSpacing.medium))
        }
    }
}

