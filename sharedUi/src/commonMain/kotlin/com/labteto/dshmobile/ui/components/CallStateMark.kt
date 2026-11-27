package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * How a step ended, at the end of a compact tool row: a shape with a spoken name, never colour alone.
 *
 * Running is the app's own animated dot, done a check, failed a circled exclamation mark and stopped a
 * square, so the four read apart in greyscale. (They were the bare characters "…", "✓", "!" and "■",
 * which a screen reader announces as punctuation and which made done and stopped hard to tell.)
 * A step that is neither running nor stopped nor failed is done, which is what an idle row is.
 */
@Composable
fun CallStateMark(state: DisclosureState, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    val name = stringResource(
        when (state) {
            DisclosureState.Running -> R.string.chat_state_running
            DisclosureState.Error -> R.string.chat_state_failed
            DisclosureState.Stopped -> R.string.chat_state_stopped
            DisclosureState.Idle -> R.string.chat_state_done
        },
    )
    Box(
        modifier.size(16.dp).semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            DisclosureState.Running -> StateDot(StateDotState.Running, size = 10.dp)
            DisclosureState.Error -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.error, modifier = Modifier.size(14.dp))
            DisclosureState.Stopped -> Icon(Icons.Filled.Stop, contentDescription = null, tint = colors.warnLabel, modifier = Modifier.size(14.dp))
            DisclosureState.Idle -> Icon(Icons.Filled.Check, contentDescription = null, tint = colors.labelTertiary, modifier = Modifier.size(14.dp))
        }
    }
}
