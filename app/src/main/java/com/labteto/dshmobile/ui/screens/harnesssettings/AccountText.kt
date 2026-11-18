package com.labteto.dshmobile.ui.screens.harnesssettings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import com.labteto.dshmobile.core.wire.dto.AccountBonus
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The bonus notices still worth showing at [now]. The server's expiry is checked again when the notice
 * is drawn, as the harness's own client does: a notice whose award has run out is not shown, and one
 * whose timestamp cannot be read is not taken for expired. An empty expiry never expires.
 */
internal fun liveBonuses(bonuses: List<AccountBonus>, now: Instant): List<AccountBonus> =
    bonuses.filter { bonus ->
        val expires = parseInstant(bonus.expiresAt)
        bonus.expiresAt.isEmpty() || expires == null || expires.isAfter(now)
    }

private fun parseInstant(text: String): Instant? {
    if (text.isEmpty()) return null
    return runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
}

/**
 * Put [text] on the clipboard marked as sensitive, so Android 13 and newer keep it out of the
 * clipboard preview and the keyboard's suggestions. The sign-in link carries a one-time code, which is
 * why it goes through here and not through the plain text copy the rest of the app uses.
 */
internal fun copySensitive(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(IS_SENSITIVE, true) }
    runCatching { clipboard.setPrimaryClip(clip) }
}

/** `ClipDescription.EXTRA_IS_SENSITIVE`, spelled out so it also compiles for the older platforms that ignore it. */
private const val IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
