package com.labteto.dshmobile.translation

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One finished translation, by [TranslationKey.id]. */
@Serializable
data class CachedTranslation(val id: String, val text: String)

/**
 * Finished translations, newest use last, bounded two ways: at most [maxEntries] of them and at most
 * [maxChars] characters of translated text in all. Past either, the entry used longest ago goes
 * first. A single translation bigger than [maxChars] is still kept until the next one arrives, so
 * the block that was just translated never loses its text under its own reader.
 *
 * Reading an entry counts as using it, so a block the reader keeps coming back to stays.
 */
class TranslationCache(val maxEntries: Int = DEFAULT_MAX_ENTRIES, val maxChars: Int = DEFAULT_MAX_CHARS) {
    private val entries = LinkedHashMap<String, String>(16, 0.75f, true)
    private var chars = 0

    @Synchronized fun get(id: String): String? = entries[id]

    @Synchronized fun put(id: String, text: String) {
        entries.put(id, text)?.let { chars -= it.length }
        chars += text.length
        while ((entries.size > maxEntries || chars > maxChars) && entries.size > 1) {
            val oldest = entries.entries.iterator()
            val eldest = oldest.next()
            chars -= eldest.value.length
            oldest.remove()
        }
    }

    /** Puts [saved] (oldest first) in behind what is already here, without replacing any of it. */
    @Synchronized fun restore(saved: List<CachedTranslation>) {
        val live = LinkedHashMap(entries)
        entries.clear()
        chars = 0
        saved.forEach { if (it.id !in live) put(it.id, it.text) }
        live.forEach { (id, text) -> put(id, text) }
    }

    /** Every entry, oldest first; what [TranslationStorage] writes. */
    @Synchronized fun snapshot(): List<CachedTranslation> = entries.map { CachedTranslation(it.key, it.value) }

    @Synchronized fun size(): Int = entries.size

    @Synchronized fun totalChars(): Int = chars

    companion object {
        const val DEFAULT_MAX_ENTRIES = 300
        const val DEFAULT_MAX_CHARS = 1_500_000
    }
}

/** Where the cache lives between launches. Blocking: callers run it off the main thread. */
interface TranslationStorage {
    fun read(): List<CachedTranslation>
    fun write(entries: List<CachedTranslation>)
}

/**
 * [TranslationStorage] in one JSON file, in the app's cache directory.
 *
 * The cache directory is the right place: translations are a convenience that can be made again, the
 * system may clear it when storage runs low, it is not backed up, and it is private to the app. A
 * file that is missing, from another version or damaged reads as empty rather than failing.
 */
class FileTranslationStorage(private val file: File) : TranslationStorage {
    @Serializable
    private data class Saved(val version: Int, val entries: List<CachedTranslation>)

    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): List<CachedTranslation> = try {
        if (!file.isFile) emptyList()
        else json.decodeFromString(Saved.serializer(), file.readText()).takeIf { it.version == VERSION }?.entries.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    override fun write(entries: List<CachedTranslation>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(Saved.serializer(), Saved(VERSION, entries)))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // A cache that cannot be written is still a cache for this launch.
        }
    }

    private companion object {
        const val VERSION = 1
    }
}
