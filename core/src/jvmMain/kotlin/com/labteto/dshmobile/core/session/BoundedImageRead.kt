package com.labteto.dshmobile.core.session

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads at most limit + one sentinel byte; a lying content provider cannot grow the buffer. */
fun readImageBounded(input: InputStream, limit: Long): ByteArray? {
    require(limit in 0 until Int.MAX_VALUE.toLong())
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var remaining = limit + 1
    while (remaining > 0) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) break
        if (count == 0) {
            val single = input.read()
            if (single < 0) break
            output.write(single)
            remaining--
        } else {
            output.write(buffer, 0, count)
            remaining -= count
        }
    }
    return if (output.size().toLong() > limit) null else output.toByteArray()
}
