package com.labteto.dshmobile.ui.adaptive

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Resolves the visible width without mutating the user's preferred width on window resize. */
fun resolveListWidth(
    plan: ShellPlan,
    windowWidth: Dp,
    preferredWidth: Dp?,
    detailsShown: Boolean,
    minimumContentWidth: Dp = 240.dp,
): Dp {
    if (!plan.listDocked || !plan.listCanCollapse) return plan.listWidth
    val detailsVisible = plan.detailsDockable && detailsShown
    val reserved = if (detailsVisible) plan.detailsWidth + ChatPaneMinWithDetails + 2.dp
        else ChatPaneMin + 1.dp
    val maximum = (windowWidth - reserved).coerceAtLeast(0.dp)
    val minimum = minimumContentWidth.coerceIn(0.dp, maximum)
    val preferred = preferredWidth?.takeIf { it.value.isFinite() } ?: plan.listWidth
    return preferred.coerceIn(minimum, maximum)
}
