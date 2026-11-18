package com.labteto.dshmobile.ui.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsType

// Connection control: padding, dot, two gaps and chevron; host text can ellipsize.
internal val TreeConnectionChromeWidth = (24 + 7 + 12 + 14).dp

@Composable
internal fun treeContentMinimumWidth(): androidx.compose.ui.unit.Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val sampleWidth = with(density) {
        measurer.measure("MM…", style = DsType.rowTitle, maxLines = 1).size.width.toDp() +
            measurer.measure("99 min", style = DsType.caption11, maxLines = 1).size.width.toDp()
    }
    val header = TreeConnectionChromeWidth + DsSpacing.touchTarget * 2 + DsSpacing.xsmall * 2
    val row = 32.dp + DsSpacing.small * 2 + DsSpacing.touchTarget + 30.dp + sampleWidth
    return maxOf(header, row) + DsSpacing.comfortable * 2
}
