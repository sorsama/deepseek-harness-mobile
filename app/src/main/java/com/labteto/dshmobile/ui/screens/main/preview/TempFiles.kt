package com.labteto.dshmobile.ui.screens.main.preview

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scratch files for the viewers that need a real file (a PDF, a video, a font, a zip).
 *
 * They all live in one cache folder. The first use in a process clears it, because anything left
 * from an earlier run belongs to a viewer that no longer exists; each viewer deletes its own file
 * when it leaves the screen.
 */
internal object PreviewTempFiles {
    private val swept = AtomicBoolean(false)

    private fun directory(context: Context): File {
        val dir = File(context.cacheDir, "preview-tmp")
        if (swept.compareAndSet(false, true)) dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        return dir
    }

    /** Writes [bytes] to a new temp file ending in `.[extension]`. Runs on the IO dispatcher. */
    suspend fun write(context: Context, bytes: ByteArray, extension: String): File = withContext(Dispatchers.IO) {
        val safe = extension.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        val file = File.createTempFile("pv-", ".$safe", directory(context))
        try {
            file.writeBytes(bytes)
            // Cancelled while writing (the viewer left the screen): do not leave the file behind.
            currentCoroutineContext().ensureActive()
            file
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }
}
