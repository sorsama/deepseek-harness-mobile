package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.shared.resources.Res
import com.labteto.dshmobile.shared.resources.common_close
import com.labteto.dshmobile.shared.resources.common_copied
import com.labteto.dshmobile.shared.resources.common_error
import com.labteto.dshmobile.shared.resources.hold_copy_all
import com.labteto.dshmobile.shared.resources.hold_select_text
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import org.jetbrains.compose.resources.stringResource

// The hold gesture itself (holdable, clickableWithHold, HoldMenu) is in Hold.kt. This is the copy to the
// clipboard and the dialog built on it; the platform's own confirmation is behind rememberCopyNotice.

/**
 * Copies text to the clipboard and confirms it.
 *
 * From Android 13 the system shows its own clipboard confirmation, so the toast is only for older
 * phones: saying "Copied" twice is noise, and saying it never left older phones guessing. It is the
 * platform toast rather than the app's, because a copy is often made from a sheet or a dialog, and
 * the app's toast is drawn in the screen's window underneath them.
 *
 * A clip too large for the platform to carry is refused by the system, not truncated; that is
 * reported rather than thrown, so a huge tool output cannot crash the app on its way out.
 */
@Composable
fun rememberCopier(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val haptics = rememberDsHaptics()
    val notice = rememberCopyNotice()
    val copied = stringResource(Res.string.common_copied)
    val failed = stringResource(Res.string.common_error)
    return remember(clipboard, haptics, notice, copied, failed) {
        { text: String ->
            val ok = runCatching { clipboard.setText(AnnotatedString(text)) }.isSuccess
            if (ok) haptics.confirm() else haptics.reject()
            notice(ok, if (ok) copied else failed)
        }
    }
}

/**
 * Says how a copy went, where the platform does not say it itself. Android shows a platform toast for a
 * failure, and for a success before Android 13 (from 13 the system confirms a copy). iOS says nothing: the
 * haptic is the confirmation there, as it is in the system's own apps.
 */
@Composable
internal expect fun rememberCopyNotice(): (ok: Boolean, message: String) -> Unit

/**
 * The whole of a text, laid out for picking part of it.
 *
 * A bubble's text is not selectable in place: a hold there opens the hold menu, and both cannot own
 * the same gesture. This is where a reader goes when they want a sentence rather than the message.
 */
@Composable
fun SelectTextDialog(text: String, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    val copy = rememberCopier()
    DsDialog(title = stringResource(Res.string.hold_select_text), onDismiss = onDismiss) {
        SelectionContainer(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text, style = DsType.bubbleText, color = colors.labelPrimary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DsButton(
                text = stringResource(Res.string.common_close),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
            )
            Spacer(Modifier.width(DsSpacing.small))
            DsButton(
                text = stringResource(Res.string.hold_copy_all),
                onClick = {
                    copy(text)
                    onDismiss()
                },
                variant = DsButtonVariant.Info,
            )
        }
    }
}

