package com.labteto.dshmobile.ui.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps dictation from competing with the speech service for audio focus.
 *
 * On an API 37 emulator the app took transient focus for a take, 36 ms later the speech service asked
 * for focus for its own capture, the app was told it had lost focus, took that for "another app is
 * using the audio" and cancelled its own dictation within a second, on every tap. The recognition
 * service owns the capture and the focus of a take; what ends a take is the person, the recogniser's
 * own callbacks and the lifecycle (see `DictationController`). Nothing under `ui/voice` may request
 * focus or listen to it, and this test reads the sources to make sure nobody adds it back.
 *
 * Code only: comments and string literals that explain the history are not scanned.
 */
class DictationAudioFocusRuleTest {
    private val forbidden = listOf(
        "requestAudioFocus",
        "abandonAudioFocus",
        "AudioFocusRequest",
        "OnAudioFocusChangeListener",
        "onAudioFocusChange",
        "AUDIOFOCUS_",
    )

    @Test
    fun noDictationCodeRequestsOrListensToAudioFocus() {
        val dir = voiceSources()
        val offences = mutableListOf<String>()
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val code = withoutCommentsAndStrings(file.readText())
            for (token in forbidden) {
                var at = code.indexOf(token)
                while (at >= 0) {
                    val line = code.substring(0, at).count { it == '\n' } + 1
                    offences += "${file.name}:$line uses $token"
                    at = code.indexOf(token, at + token.length)
                }
            }
        }
        assertTrue(
            "Dictation must not request or react to audio focus: the speech service takes focus for its own " +
                "take, and treating that as 'another app took the audio' cancels the person's own dictation " +
                "(seen on the API 37 emulator). End a take on the recogniser's callbacks and the lifecycle " +
                "instead.\n" + offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    private fun voiceSources(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val voice = File(dir, "app/src/main/java/com/labteto/dshmobile/ui/voice")
            if (voice.isDirectory) return voice
            dir = dir.parentFile
        }
        error("ui/voice was not found from ${System.getProperty("user.dir")}")
    }

    /** Blanks `//` and block comments and the insides of string literals, keeping newlines. */
    private fun withoutCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> {
                    while (i < source.length && source[i] != '\n') { out.append(' '); i++ }
                }
                c == '/' && next == '*' -> {
                    val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                    while (i < end) { out.append(if (source[i] == '\n') '\n' else ' '); i++ }
                }
                c == '"' -> {
                    out.append(' ')
                    i++
                    while (i < source.length && source[i] != '"' && source[i] != '\n') {
                        if (source[i] == '\\') { out.append(' '); i++ }
                        if (i < source.length) { out.append(' '); i++ }
                    }
                    if (i < source.length && source[i] == '"') { out.append(' '); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
