package com.labteto.dshmobile.ui.input

import androidx.compose.runtime.Composable

/**
 * Whether a hardware keyboard is attached, which changes what Enter means in the composer (see [decideComposerKey]).
 *
 * Android reads the configuration (`Configuration.keyboard`), which reports one while a keyboard is attached (a
 * Chromebook always has one) and none on a bare phone. iOS answers false for now, so Enter adds a line there as
 * the on-screen return key does and the send button sends; an iPad with a keyboard is a later step (#141).
 */
@Composable
expect fun hardwareKeyboardAttached(): Boolean
