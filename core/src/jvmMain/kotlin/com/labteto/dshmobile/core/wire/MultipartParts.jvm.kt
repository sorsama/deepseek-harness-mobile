package com.labteto.dshmobile.core.wire

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartReader
import okio.Buffer

/**
 * JVM actual: OkHttp's own `MultipartReader`, which is what the unary client has always parsed with.
 * This is the body of `RpcMultipart.decode` from before it moved to common code, unchanged.
 */
internal actual fun readMultipartParts(contentType: String, body: ByteArray): List<MultipartPart> {
    val boundary = contentType.toMediaTypeOrNull()?.parameter("boundary")
        ?: throw IllegalArgumentException("multipart response carries no boundary")
    val parts = ArrayList<MultipartPart>()
    MultipartReader(Buffer().write(body), boundary).use { reader ->
        while (true) {
            val part = reader.nextPart() ?: break
            parts += MultipartPart(part.headers["Content-Disposition"], part.body.readByteArray())
        }
    }
    return parts
}
