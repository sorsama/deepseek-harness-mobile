package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable

/** iOS confirms a copy with the haptic alone; see the expect. */
@Composable
internal actual fun rememberCopyNotice(): (ok: Boolean, message: String) -> Unit = NoCopyNotice

private val NoCopyNotice: (Boolean, String) -> Unit = { _, _ -> }
