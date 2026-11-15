package com.labteto.dshmobile.runtime.workspace

/**
 * MIME types for project files, by name.
 *
 * Android's own table (`MimeTypeMap`) knows media and office files well and source code hardly at
 * all: it has no entry for `.kt`, `.ts`, `.rs`, `.go` or `.toml`, so a Files app would offer to
 * open a Kotlin file with nothing. The table here is checked first and covers what a project holds;
 * [platform] (the device's `MimeTypeMap` in the provider, nothing in tests) is asked next.
 *
 * Source code maps to `text/plain` unless a more specific type is in common use (`text/markdown`,
 * `application/json`): every text editor on Android accepts `text/plain`, and few register for
 * `text/x-kotlin`.
 */
class MimeTypes(private val platform: (extension: String) -> String? = { null }) {

    fun forName(name: String): String {
        val lower = name.lowercase()
        WHOLE_NAMES[lower]?.let { return it }
        val ext = lower.substringAfterLast('.', "")
        if (ext.isEmpty() || ext == lower.removePrefix(".")) {
            // No extension at all, or a dotfile such as `.gitignore` whose whole name is the "extension".
            return if (lower.startsWith('.')) TEXT else OCTET
        }
        return BY_EXTENSION[ext] ?: platform(ext) ?: OCTET
    }

    /** The extension a new file of [mime] should get, or null when none is known or it already has one. */
    fun extensionFor(mime: String, platformExtension: (String) -> String? = { null }): String? {
        if (mime == OCTET || mime == TEXT) return if (mime == TEXT) "txt" else null
        return PREFERRED_EXTENSION[mime] ?: platformExtension(mime)
    }

    companion object {
        const val OCTET = "application/octet-stream"
        const val TEXT = "text/plain"
        const val DIRECTORY = "vnd.android.document/directory"

        private val WHOLE_NAMES = mapOf(
            "makefile" to TEXT,
            "dockerfile" to TEXT,
            "license" to TEXT,
            "readme" to TEXT,
            "gradlew" to TEXT,
            "procfile" to TEXT,
        )

        private val TEXT_EXTENSIONS = listOf(
            "txt", "log", "kt", "kts", "java", "gradle", "groovy", "scala", "swift", "m", "mm", "h", "hpp", "c", "cc", "cpp", "cxx",
            "rs", "go", "py", "pyi", "rb", "php", "pl", "lua", "r", "dart", "ex", "exs", "erl", "hs", "clj", "fs", "cs", "vb",
            "ts", "tsx", "jsx", "mjs", "cjs", "vue", "svelte", "astro", "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
            "toml", "ini", "cfg", "conf", "env", "properties", "lock", "sql", "graphql", "gql", "proto", "tf", "nix",
            "gitignore", "gitattributes", "editorconfig", "dockerignore", "npmrc", "nvmrc", "patch", "diff", "tex", "rst", "adoc",
        )

        private val BY_EXTENSION: Map<String, String> = buildMap {
            TEXT_EXTENSIONS.forEach { put(it, TEXT) }
            put("md", "text/markdown")
            put("markdown", "text/markdown")
            put("json", "application/json")
            put("jsonl", "application/json")
            put("yaml", "application/yaml")
            put("yml", "application/yaml")
            put("xml", "text/xml")
            put("html", "text/html")
            put("htm", "text/html")
            put("css", "text/css")
            put("js", "text/javascript")
            put("csv", "text/csv")
            put("tsv", "text/tab-separated-values")
            put("svg", "image/svg+xml")
            put("png", "image/png")
            put("jpg", "image/jpeg")
            put("jpeg", "image/jpeg")
            put("gif", "image/gif")
            put("webp", "image/webp")
            put("bmp", "image/bmp")
            put("ico", "image/x-icon")
            put("heic", "image/heic")
            put("pdf", "application/pdf")
            put("zip", "application/zip")
            put("gz", "application/gzip")
            put("tgz", "application/gzip")
            put("tar", "application/x-tar")
            put("xz", "application/x-xz")
            put("zst", "application/zstd")
            put("7z", "application/x-7z-compressed")
            put("jar", "application/java-archive")
            put("apk", "application/vnd.android.package-archive")
            put("wasm", "application/wasm")
            put("mp3", "audio/mpeg")
            put("wav", "audio/wav")
            put("ogg", "audio/ogg")
            put("mp4", "video/mp4")
            put("webm", "video/webm")
            put("mov", "video/quicktime")
            put("ttf", "font/ttf")
            put("otf", "font/otf")
            put("woff", "font/woff")
            put("woff2", "font/woff2")
            put("ipynb", "application/json")
        }

        private val PREFERRED_EXTENSION = mapOf(
            "text/markdown" to "md",
            "application/json" to "json",
            "application/yaml" to "yaml",
            "text/xml" to "xml",
            "text/html" to "html",
            "text/css" to "css",
            "text/javascript" to "js",
            "text/csv" to "csv",
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/gif" to "gif",
            "image/webp" to "webp",
            "image/svg+xml" to "svg",
            "application/pdf" to "pdf",
            "application/zip" to "zip",
        )
    }
}
