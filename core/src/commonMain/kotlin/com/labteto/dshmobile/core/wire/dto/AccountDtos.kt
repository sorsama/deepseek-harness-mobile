package com.labteto.dshmobile.core.wire.dto

import kotlinx.serialization.Serializable

/**
 * The shapes of the harness's account calls (`account/getState` and the rest), read from `packages/credentials/deepseek-account`
 * (`types.ts`) and `packages/api/account-controller` and checked against answers captured from a
 * 0.2.1-rc.2 harness. Every field the app does not read is left out; the wire layer ignores unknown keys.
 *
 * Nothing here is a credential. The harness keeps the token, and the one secret-like value that does
 * cross, [SignInAttempt.authorizeUrl], is treated as one (see `AccountText.redactUrls`).
 */

/** Where Platform's own pages are; the harness derives them from its configured origin. Never carries a token. */
@Serializable data class AccountLinks(val usageUrl: String = "", val topUpUrl: String = "")

/**
 * The latest sign-in attempt, kept by the harness until the next attempt: a finished one stays after
 * it ends, so [phase] is not "a sign-in is running" by itself.
 *
 * [phase] is one of `initializing`, `waiting-browser`, `exchanging`, `committing`, `succeeded`,
 * `cancelled`, `expired`, `failed`. [authorizeUrl] is present only while `waiting-browser`; it is a
 * one-time link, so it is never logged or shown. [expiresAt] is epoch milliseconds. [errorCode] is
 * one of `no-response`, `network`, `protocol`, `expired`, `storage` on a failed or expired attempt.
 */
@Serializable data class SignInAttempt(
    val id: String,
    val phase: String,
    val authorizeUrl: String? = null,
    val expiresAt: Long? = null,
    val errorCode: String? = null,
) {
    /** Without the link, so a log line or a crash report that prints an attempt cannot carry its one-time code. */
    override fun toString(): String = "SignInAttempt(id=$id, phase=$phase, errorCode=$errorCode, hasLink=${authorizeUrl != null})"
}

/** `account/getState`: whether a credential is stored, and the latest attempt. [status] is `signed-out` or `credential-stored`. */
@Serializable data class AccountView(
    val status: String,
    val links: AccountLinks = AccountLinks(),
    val attempt: SignInAttempt? = null,
)

/** The profile fields Platform supplies, with the contact exactly as Platform masks it. */
@Serializable data class AccountProfile(
    val id: String? = null,
    val name: String? = null,
    val contact: String? = null,
    val avatarUrl: String? = null,
)

/** `account/getProfile`: [status] is `ready` (with [value]) or `failed`. A signed-out harness answers null instead. */
@Serializable data class AccountProfileOutcome(val status: String, val value: AccountProfile? = null)

/** One granted bonus the Platform has not yet recorded as shown. [title] and [message] are written by the server in the caller's language. */
@Serializable data class AccountBonus(
    val orderId: String,
    val title: String? = null,
    val message: String = "",
    /** ISO timestamp, or empty when the bonus never expires. */
    val expiresAt: String = "",
)

/** `account/getUnnotifiedBonuses`: the bonuses with the account they belong to, or null while signed out. */
@Serializable data class AccountBonusBatch(val accountId: String, val bonuses: List<AccountBonus> = emptyList())
