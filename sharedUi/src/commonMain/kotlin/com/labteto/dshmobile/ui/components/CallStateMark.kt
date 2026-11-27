package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsTheme
import org.jetbrains.compose.resources.stringResource
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.chat_state_done
import com.labteto.dshmobile.shared.resources.chat_state_failed
import com.labteto.dshmobile.shared.resources.chat_state_running
import com.labteto.dshmobile.shared.resources.chat_state_stopped

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
            DisclosureState.Running -> Res.string.chat_state_running
            DisclosureState.Error -> Res.string.chat_state_failed
            DisclosureState.Stopped -> Res.string.chat_state_stopped
            DisclosureState.Idle -> Res.string.chat_state_done
        },
    )
    Box(
        modifier.size(16.dp).semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            DisclosureState.Running -> StateDot(StateDotState.Running, size = 10.dp)
            DisclosureState.Error -> Icon(DsIcons.ErrorOutline, contentDescription = null, tint = colors.error, modifier = Modifier.size(14.dp))
            DisclosureState.Stopped -> Icon(DsIcons.Stop, contentDescription = null, tint = colors.warnLabel, modifier = Modifier.size(14.dp))
            DisclosureState.Idle -> Icon(DsIcons.Check, contentDescription = null, tint = colors.labelTertiary, modifier = Modifier.size(14.dp))
        }
    }
}
