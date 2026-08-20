package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Skills-domain DTOs, ported from `packages/host/apiproxy/src/api/skills.schema.ts` and
 * `packages/host/apiproxy/src/api/skills.ts` (v0.1.0-rc.8). Listing is the domain's only RPC:
 * invocation itself is a plain `session.prompt` whose leading `/name` token the host recognizes.
 */

/** Skill catalog row (wire projection of the host SkillSummary). */
@Serializable
data class SkillEntry(
    /** Kebab-case identifier the user references as `/name` in the composer. */
    @SerialName("name") val name: String,
    /** Short routing description. */
    @SerialName("description") val description: String,
    /** Optional extra routing guidance. */
    @SerialName("whenToUse") val whenToUse: String? = null,
    /** False marks a user-only skill (`disable-model-invocation`). */
    @SerialName("modelInvocable") val modelInvocable: Boolean,
)

/** Request payload of `skill.list` (the session's header cwd resolves the project root). */
@Serializable
data class SkillListRequest(
    @SerialName("sessionId") val sessionId: String,
)

/** Value of `skill.list`. */
@Serializable
data class SkillListValue(
    @SerialName("skills") val skills: List<SkillEntry> = emptyList(),
)
