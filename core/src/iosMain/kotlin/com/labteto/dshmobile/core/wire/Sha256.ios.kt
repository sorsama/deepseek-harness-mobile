package com.labteto.dshmobile.core.wire

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/** iOS actual: CommonCrypto's SHA-256, the system's own implementation. */
@OptIn(ExperimentalUnsignedTypes::class, ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    // `addressOf(0)` needs an element to point at, so an empty input is hashed from a one-byte
    // array with a length of zero.
    val input = if (bytes.isEmpty()) ByteArray(1) else bytes
    input.usePinned { inputPinned ->
        digest.usePinned { digestPinned ->
            CC_SHA256(inputPinned.addressOf(0), bytes.size.convert(), digestPinned.addressOf(0))
        }
    }
    return digest.toByteArray()
}
