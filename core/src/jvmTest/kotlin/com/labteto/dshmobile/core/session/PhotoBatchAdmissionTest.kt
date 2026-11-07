package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.wire.dto.*
import org.junit.Assert.*
import org.junit.Test

class PhotoBatchAdmissionTest {
    private val limits = ImageLimitsView(maxImagesPerMessage = 3, maxImageBytes = 10, maxMessageImageBytes = 20)
    @Test fun mixedSelectionRetainsValidOrderAndCountsExistingImages() {
        val ledger = PhotoBatchAdmission(limits, listOf(5))
        val candidates = listOf(4 to true, 11 to true, 3 to false, 6 to true, 1 to true)
        val accepted = candidates.mapIndexedNotNull { index, (bytes, valid) ->
            index.takeIf { ledger.accept("image/png", "image/png", bytes, if (valid) 1 else 0, 1) == null }
        }
        assertEquals(listOf(0, 3), accepted)
        assertEquals(3, ledger.count)
        assertEquals(15L, ledger.bytes)
    }
    @Test fun aggregateByteFailureDoesNotConsumeTheNextSmallerPhoto() {
        val ledger = PhotoBatchAdmission(limits, listOf(10))
        assertNull(ledger.accept("image/png", "image/png", 9, 1, 1))
        assertEquals(ImageRejection.BATCH_TOO_LARGE, ledger.accept("image/png", "image/png", 2, 1, 1))
        assertNull(ledger.accept("image/png", "image/png", 1, 1, 1))
        assertEquals(20L, ledger.bytes)
    }
    @Test fun cancellationOrEmptySelectionChangesNothing() {
        val ledger = PhotoBatchAdmission(limits, emptyList())
        assertEquals(0, ledger.count)
        assertEquals(0L, ledger.bytes)
        assertFalse(ledger.full)
    }
}
