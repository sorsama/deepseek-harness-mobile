package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The header stays at the original project while a session can enter a worktree. */
fun effectiveWorkingDirectory(projections: Map<String, JsonElement>?, original: String?): String? =
    (projections?.get("workingDirectory") as? JsonPrimitive)
        ?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: original
