package com.labteto.dshmobile.translation

import com.labteto.dshmobile.core.wire.TranslationLimits
import java.security.MessageDigest

/**
 * Everything that makes one translation of one reasoning block different from another.
 *
 * Manual and automatic translation build the same key for the same block, which is what lets them
 * share one cache: a tap on a block that was translated on its own is instant, and the other way
 * round. A different provider or target language is a different key, so changing either on the host
 * asks again instead of showing text made for another choice, and edited text is a different key
 * through [textFingerprint], so a changed block is never shown a stale translation.
 *
 * @property host the host the session lives on, as the transcript names it.
 * @property session the session id on that host.
 * @property block the block's own identity inside the session: the assistant message's stable key
 *   and the block's index in it. History replay, reconnects and the streaming-to-durable swap all
 *   keep it, which is why it is used rather than a list position.
 * @property targetLanguage the resolved language tag, lower-cased.
 * @property provider the host's translation provider id, as `cotTranslation/limits` names it.
 * @property fingerprint [textFingerprint] of the text that was sent.
 */
data class TranslationKey(
    val host: String,
    val session: String,
    val block: String,
    val targetLanguage: String,
    val provider: String,
    val fingerprint: String,
) {
    /**
     * One opaque id for the key: a SHA-256 of its parts. The cache and its file use it instead of the
     * parts, so the file holds no host address or session id, and the parts cannot run together
     * (`a|b` and `ab|` differ), because each is length-prefixed before it is hashed.
     */
    val id: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in listOf(host, session, block, targetLanguage, provider, fingerprint)) {
            digest.update("${part.length}:".toByteArray(Charsets.UTF_8))
            digest.update(part.toByteArray(Charsets.UTF_8))
        }
        digest.digest().toHex()
    }

    companion object {
        /** Builds the key for [text] translated into [targetLanguage] by [provider]. */
        fun of(
            host: String,
            session: String,
            block: String,
            targetLanguage: String,
            provider: String,
            text: String,
        ): TranslationKey = TranslationKey(
            host = host,
            session = session,
            block = block,
            targetLanguage = targetLanguage.lowercase(),
            provider = provider,
            fingerprint = textFingerprint(text),
        )
    }
}

/**
 * A short stand-in for [text] that changes when the text does: the first 16 hex digits of its
 * SHA-256, then its length in UTF-16 units. The length alone would not notice an edit that keeps it.
 */
fun textFingerprint(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.toHex().take(16) + "." + text.length
}

private fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0xf])
    }
    return out.toString()
}

/**
 * The language a translation goes to: the host's own preference, or [appLanguageTag] when that
 * preference is `auto`. This is the one place the rule lives, so the manual button and automatic
 * translation cannot pick different targets.
 */
fun resolveTargetLanguage(preferred: String, appLanguageTag: String): String =
    preferred.takeUnless { it == "auto" } ?: appLanguageTag

/**
 * Whether the host can translate at all with what it reported. The manual button has always hidden
 * itself unless this is true, and Settings uses the same check to say so when it is not.
 */
fun TranslationLimits.canTranslate(): Boolean =
    maxTextChars >= 2 && preferences.provider in availableProviders
