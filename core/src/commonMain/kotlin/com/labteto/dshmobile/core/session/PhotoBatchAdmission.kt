package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.wire.dto.ImageLimitsView
import com.labteto.dshmobile.core.wire.dto.ImageRejection

/** Only accepted images consume the count/byte budget; file receipts never enter this ledger. */
class PhotoBatchAdmission(private val limits: ImageLimitsView, existingImageBytes: List<Int>) {
    var count = existingImageBytes.size
        private set
    var bytes = existingImageBytes.sumOf { it.toLong() }
        private set
    val full get() = count >= limits.maxImagesPerMessage

    fun accept(declaredType: String, detectedType: String?, size: Int, width: Int, height: Int): ImageRejection? {
        val reason = limits.admitBatch(count, bytes, size)
            ?: limits.admitImage(declaredType, detectedType, size, width, height)
        if (reason == null) { count++; bytes += size }
        return reason
    }
}
