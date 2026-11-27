package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.labteto.dshmobile.core.wire.dto.ImageAttachmentRef

/**
 * Draws an image a tool returned (a screenshot in a call's output, an image reference inside raw
 * JSON), given its durable reference and what a screen reader says for it.
 *
 * The host provides it because loading the bytes is the host's: on Android it is `AttachmentImage`,
 * which reads the attachment through the session store and shares the transcript's decoded-image
 * cache, exactly as the cards called it before they moved. iOS has no attachment loading yet
 * (attachments are a later milestone of #141), so there the default, null, draws nothing.
 */
val LocalAttachmentImage = staticCompositionLocalOf<(@Composable (ref: ImageAttachmentRef, contentDescription: String?) -> Unit)?> { null }
