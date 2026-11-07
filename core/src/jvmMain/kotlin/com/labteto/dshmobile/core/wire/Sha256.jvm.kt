package com.labteto.dshmobile.core.wire

import java.security.MessageDigest

/** JVM actual: the JDK's SHA-256, the one `RelayTls.spkiPin` uses. */
internal actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
