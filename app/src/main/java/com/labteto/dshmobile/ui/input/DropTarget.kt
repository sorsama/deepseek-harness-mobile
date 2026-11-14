package com.labteto.dshmobile.ui.input

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat

/**
 * Makes this area accept files and text dropped from another window: the ChromeOS Files app, a
 * browser, another app in split screen or a freeform window.
 *
 * Files arrive as `content://` addresses and are readable only while the drop's permission lives, so
 * the permission is requested here, before anything reads them (an unrequested one throws as soon
 * as the upload opens the stream). It is kept until the activity ends rather than released at once:
 * an upload streams the file well after the drop returns. When nothing is taken the permission is
 * released straight away.
 *
 * [onDrop] gets the dropped files and the dropped text (blank when there is none) and answers
 * whether it took them. [onActiveChange] is true while a drag is over the area, for the highlight.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.attachmentDropTarget(
    enabled: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onDrop: (files: List<Uri>, text: String) -> Boolean,
): Modifier = composed {
    val context = LocalContext.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnActive by rememberUpdatedState(onActiveChange)
    val target = remember(context) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = currentOnActive(true)

            override fun onExited(event: DragAndDropEvent) = currentOnActive(false)

            override fun onEnded(event: DragAndDropEvent) = currentOnActive(false)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                currentOnActive(false)
                val drag = event.toAndroidDragEvent()
                val clip = drag.clipData ?: return false
                val split = splitClip(clip.parts(context))
                if (split.files.isEmpty() && split.text.isBlank()) return false
                // Before the files are touched: the grant is what makes the addresses readable.
                val grant = if (split.files.isNotEmpty()) {
                    context.findActivity()?.let { ActivityCompat.requestDragAndDropPermissions(it, drag) }
                } else {
                    null
                }
                val taken = currentOnDrop(split.files.map(Uri::parse), split.text)
                if (!taken) grant?.release()
                return taken
            }
        }
    }
    // Everything is offered a chance: what is useful (files, images, text) is decided when it lands.
    Modifier.dragAndDropTarget(shouldStartDragAndDrop = { currentEnabled }, target = target)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
