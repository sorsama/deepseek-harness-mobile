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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

// The hold gesture itself (holdable, clickableWithHold, HoldMenu) is in :sharedUi. What stays here is
// the part that talks to Android's clipboard and toast, and the dialog built on it.

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
    val context = androidx.compose.ui.platform.LocalContext.current
    val copied = stringResource(R.string.common_copied)
    val failed = stringResource(R.string.common_error)
    return remember(clipboard, haptics, context, copied, failed) {
        { text: String ->
            val ok = runCatching { clipboard.setText(AnnotatedString(text)) }.isSuccess
            if (ok) haptics.confirm() else haptics.reject()
            if (!ok || android.os.Build.VERSION.SDK_INT < 33) {
                android.widget.Toast.makeText(context, if (ok) copied else failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

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
    DsDialog(title = stringResource(R.string.hold_select_text), onDismiss = onDismiss) {
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
                text = stringResource(R.string.common_close),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
            )
            Spacer(Modifier.width(DsSpacing.small))
            DsButton(
                text = stringResource(R.string.hold_copy_all),
                onClick = {
                    copy(text)
                    onDismiss()
                },
                variant = DsButtonVariant.Info,
            )
        }
    }
}

