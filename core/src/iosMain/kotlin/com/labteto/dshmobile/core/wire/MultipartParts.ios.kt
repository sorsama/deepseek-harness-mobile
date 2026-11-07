package com.labteto.dshmobile.core.wire

/** iOS actual: no OkHttp here, so the portable reader, which `PortableMultipartTest` holds to OkHttp's. */
internal actual fun readMultipartParts(contentType: String, body: ByteArray): List<MultipartPart> =
    PortableMultipart.read(contentType, body)
