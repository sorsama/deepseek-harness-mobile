package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.labteto.dshmobile.shared.resources.Res
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.posix.memcpy

/**
 * iOS reads the Geist fonts as Compose resources (`composeResources/font`), which the build copies
 * into the app bundle, and gives the bytes to the Skia text engine.
 *
 * This is not `Font(Res.font.x)`, which is a `@Composable`: [DsType] holds plain `TextStyle` values
 * that are read all over the app outside composition, so the families have to exist without one.
 * A file that cannot be read leaves that weight on the system font rather than crashing the app on
 * its first frame.
 */
internal actual fun createUiFontFamily(): FontFamily = family(
    "geist_regular" to FontWeight.Normal,
    "geist_medium" to FontWeight.Medium,
    "geist_semibold" to FontWeight.SemiBold,
    "geist_bold" to FontWeight.Bold,
)

internal actual fun createCodeFontFamily(): FontFamily = family(
    "geist_mono_regular" to FontWeight.Normal,
    "geist_mono_medium" to FontWeight.Medium,
    "geist_mono_semibold" to FontWeight.SemiBold,
)

/** No `includeFontPadding` outside Android. */
internal actual fun createPlatformTextStyle(): PlatformTextStyle? = null

private fun family(vararg faces: Pair<String, FontWeight>): FontFamily {
    val fonts = faces.mapNotNull { (name, weight) ->
        val bytes = readBundledFont(name) ?: return@mapNotNull null
        Font(identity = name, data = bytes, weight = weight)
    }
    return if (fonts.isEmpty()) FontFamily.Default else FontFamily(fonts)
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalResourceApi::class)
private fun readBundledFont(name: String): ByteArray? = runCatching {
    val url = NSURL.URLWithString(Res.getUri("font/$name.ttf")) ?: return null
    val data = NSData.dataWithContentsOfURL(url) ?: return null
    ByteArray(data.length.toInt()).also { out ->
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    }
}.getOrNull()
