package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Settings-domain DTOs, ported from `packages/host/apiproxy/src/api/settings.schema.ts` and
 * `packages/host/apiproxy/src/api/settings.ts` (v0.1.0-rc.8).
 */

/** One redacted secret slot. */
@Serializable
data class SettingsSecretView(
    @SerialName("path") val path: List<String> = emptyList(),
    @SerialName("set") val set: Boolean,
)

/** SettingsNamespaceView row of settings.describe and the write responses. */
@Serializable
data class SettingsNamespaceView(
    @SerialName("ns") val ns: String,
    /** Namespace JSON schema (opaque). */
    @SerialName("schema") val schema: JsonElement,
    /** Effective value (opaque). */
    @SerialName("value") val value: JsonElement,
    /** Base (deployment) value, when layered. */
    @SerialName("base") val base: JsonElement? = null,
    /** User override value, when layered. */
    @SerialName("user") val user: JsonElement? = null,
    /** 'live' or 'restart' — when the effective value applies. */
    @SerialName("applies") val applies: String,
    @SerialName("secrets") val secrets: List<SettingsSecretView> = emptyList(),
    @SerialName("revision") val revision: Long,
)

/** Value of `settings.describe`. */
@Serializable
data class SettingsDescribeValue(
    @SerialName("writable") val writable: Boolean,
    @SerialName("hasDocument") val hasDocument: Boolean,
    @SerialName("namespaces") val namespaces: List<SettingsNamespaceView> = emptyList(),
)

/** Value of `settings.openDocument`. */
@Serializable
data class SettingsOpenDocumentValue(
    @SerialName("opened") val opened: Boolean = true,
)

/** One path-addressed edit of settings.mutate. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("op")
sealed class SettingsPathOp {
    @Serializable
    @SerialName("set")
    data class Set(
        @SerialName("path") val path: List<String>,
        @SerialName("value") val value: JsonElement,
    ) : SettingsPathOp()

    @Serializable
    @SerialName("unset")
    data class Unset(
        @SerialName("path") val path: List<String>,
    ) : SettingsPathOp()
}

// ---- settings.* request payloads ----

/** Request payload of `settings.update`. */
@Serializable
data class SettingsUpdateRequest(
    @SerialName("ns") val ns: String,
    @SerialName("patch") val patch: Map<String, JsonElement> = emptyMap(),
    @SerialName("expectedRevision") val expectedRevision: Long? = null,
)

/** Request payload of `settings.replace`. */
@Serializable
data class SettingsReplaceRequest(
    @SerialName("ns") val ns: String,
    @SerialName("section") val section: Map<String, JsonElement> = emptyMap(),
    @SerialName("expectedRevision") val expectedRevision: Long? = null,
)

/** Request payload of `settings.mutate`. */
@Serializable
data class SettingsMutateRequest(
    @SerialName("ns") val ns: String,
    @SerialName("ops") val ops: List<SettingsPathOp> = emptyList(),
    @SerialName("expectedRevision") val expectedRevision: Long? = null,
)

// ---- settings.* response values ----
// The schema declares every write response as the namespace's new redacted view
// (settingsNamespaceViewSchema), so update/replace/mutate share the namespace view shape.

/** Value of `settings.update` (the namespace's new redacted view). */
typealias SettingsUpdateValue = SettingsNamespaceView

/** Value of `settings.replace` (the namespace's new redacted view). */
typealias SettingsReplaceValue = SettingsNamespaceView

/** Value of `settings.mutate` (the namespace's new redacted view). */
typealias SettingsMutateValue = SettingsNamespaceView

