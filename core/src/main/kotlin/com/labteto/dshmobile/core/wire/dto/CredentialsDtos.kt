package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Credentials-domain DTOs, ported from `packages/host/apiproxy/src/api/credentials.schema.ts` and
 * `packages/host/apiproxy/src/api/credentials.ts` (v0.1.0-rc.8).
 */

/** CredentialView entry of `credentials.describe`. */
@Serializable
data class CredentialView(
    @SerialName("configured") val configured: Boolean,
    /** Provenance label (e.g. the shadowing layer that supplies the value). */
    @SerialName("source") val source: String? = null,
    @SerialName("writable") val writable: Boolean,
)

/** Request payload of `credentials.describe`. */
@Serializable
data class CredentialsDescribeRequest(
    /** POSIX-portable environment-variable reference names. */
    @SerialName("refs") val refs: List<String> = emptyList(),
)

/** Value of `credentials.describe`. */
@Serializable
data class CredentialsDescribeValue(
    @SerialName("credentials") val credentials: Map<String, CredentialView> = emptyMap(),
)

/** Request payload of `credentials.set`: the one direction a value crosses this wire. */
@Serializable
data class CredentialsSetRequest(
    @SerialName("ref") val ref: String,
    @SerialName("value") val value: String,
)

/** Value of `credentials.set` (empty object). */
@Serializable
class CredentialsSetValue

/** Request payload of `credentials.unset`. */
@Serializable
data class CredentialsUnsetRequest(
    @SerialName("ref") val ref: String,
)

/** Value of `credentials.unset` (empty object). */
@Serializable
class CredentialsUnsetValue
