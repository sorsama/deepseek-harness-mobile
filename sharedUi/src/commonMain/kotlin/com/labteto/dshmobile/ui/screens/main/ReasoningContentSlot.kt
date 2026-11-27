package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/**
 * The body of an open reasoning block in the transcript: `(text, complete, blockId)`, where
 * `complete` says the thought is finished and `blockId` is the block's stable identity in its
 * session.
 *
 * The host provides it because what sits under the text is the host's. On Android it is
 * `ReasoningContent` (`ReasoningTranslation.kt`), which adds the translate control and automatic
 * translation through the session store and the harness's translation RPCs; `AppRoot` provides it,
 * so every transcript (the chat, the subagent sheet, Trajectory) gets the same body it always had.
 * iOS has no reasoning translation yet (a later milestone of #141), so the default draws what
 * Android draws when the harness offers no translation: the selectable text alone.
 */
val LocalReasoningContent = staticCompositionLocalOf<@Composable (text: String, complete: Boolean, blockId: String) -> Unit> {
    PlainReasoningContent
}

private val PlainReasoningContent: @Composable (String, Boolean, String) -> Unit = { text, _, _ ->
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        SelectionContainer {
            Text(text.trimEnd(), style = DsType.small13, color = DsTheme.colors.labelTertiary, softWrap = true)
        }
    }
}
