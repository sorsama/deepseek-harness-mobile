package com.labteto.dshmobile.ui.screens.main.preview

/**
 * What a previewed file is, decided in one place.
 *
 * The order is: the file name (extension, then a few well-known bare names like `Dockerfile`), then
 * the harness's mime type if it ever gives one, then the first bytes of the file. The name wins
 * because it is free and almost always right; the bytes are only read when the name says nothing.
 * Nothing here touches Android, so every rule can be unit tested.
 */
internal enum class PreviewKind {
    Image, Svg, Pdf, Video, Audio, Markdown, Html, Table, Json, Notebook, Diff, Code, Text, Archive, Font, Binary
}

/** Which transfer a kind needs. */
internal enum class PreviewSource {
    /** Read as text, a page of lines at a time. */
    Text,

    /** Read as one whole byte block (up to the byte cap). */
    Bytes,

    /** Only the first few KB: enough to show a hex dump of something the app cannot render. */
    Head,
}

/**
 * @property kind the renderer family.
 * @property extension the effective, lower-case extension with no dot. For a file found by its
 * bytes this is the extension the content implies (`png`), so a renderer can still key off it.
 * @property label a proper-noun name for the badge (`PNG`, `Kotlin`), or null when the kind alone
 * says it (plain text, unknown binary), in which case the UI uses a translated generic label.
 * @property mime the content type, when known.
 * @property sniffed true when the bytes decided it, not the name.
 */
internal data class FileType(
    val kind: PreviewKind,
    val extension: String,
    val label: String? = null,
    val mime: String? = null,
    val sniffed: Boolean = false,
) {
    val source: PreviewSource
        get() = when (kind) {
            PreviewKind.Image, PreviewKind.Pdf, PreviewKind.Video, PreviewKind.Audio,
            PreviewKind.Archive, PreviewKind.Font -> PreviewSource.Bytes
            PreviewKind.Binary -> PreviewSource.Head
            else -> PreviewSource.Text
        }

    /** Kinds whose text is only meaningful whole: a half-read JSON document does not parse. */
    val needsWholeText: Boolean
        get() = kind == PreviewKind.Json || kind == PreviewKind.Notebook

    /** True for the kinds whose loaded text can be copied or searched. */
    val isText: Boolean get() = source == PreviewSource.Text
}

private fun type(kind: PreviewKind, ext: String, label: String? = null, mime: String? = null) =
    FileType(kind, ext, label, mime)

/** Extension → type, for everything that is not a plain "code" extension. */
private val EXTENSION_TYPES: Map<String, FileType> = buildMap {
    fun put(kind: PreviewKind, label: String?, mime: String?, vararg ext: String) =
        ext.forEach { put(it, type(kind, it, label, mime)) }
    // Images the platform can decode (heic, heif and avif depend on the Android version).
    put(PreviewKind.Image, "PNG", "image/png", "png")
    put(PreviewKind.Image, "JPEG", "image/jpeg", "jpg", "jpeg", "jpe", "jfif")
    put(PreviewKind.Image, "WebP", "image/webp", "webp")
    put(PreviewKind.Image, "GIF", "image/gif", "gif")
    put(PreviewKind.Image, "BMP", "image/bmp", "bmp")
    put(PreviewKind.Image, "HEIC", "image/heic", "heic")
    put(PreviewKind.Image, "HEIF", "image/heif", "heif")
    put(PreviewKind.Image, "AVIF", "image/avif", "avif")
    put(PreviewKind.Image, "ICO", "image/x-icon", "ico")
    put(PreviewKind.Svg, "SVG", "image/svg+xml", "svg")
    put(PreviewKind.Pdf, "PDF", "application/pdf", "pdf")
    // Video and audio the platform player can usually open.
    put(PreviewKind.Video, "MP4", "video/mp4", "mp4", "m4v")
    put(PreviewKind.Video, "WebM", "video/webm", "webm")
    put(PreviewKind.Video, "MOV", "video/quicktime", "mov")
    put(PreviewKind.Video, "MKV", "video/x-matroska", "mkv")
    put(PreviewKind.Video, "3GP", "video/3gpp", "3gp", "3g2")
    put(PreviewKind.Audio, "MP3", "audio/mpeg", "mp3")
    put(PreviewKind.Audio, "M4A", "audio/mp4", "m4a")
    put(PreviewKind.Audio, "AAC", "audio/aac", "aac")
    put(PreviewKind.Audio, "WAV", "audio/wav", "wav")
    put(PreviewKind.Audio, "OGG", "audio/ogg", "ogg", "oga")
    put(PreviewKind.Audio, "Opus", "audio/opus", "opus")
    put(PreviewKind.Audio, "FLAC", "audio/flac", "flac")
    // Documents.
    put(PreviewKind.Markdown, "Markdown", "text/markdown", "md", "markdown", "mdown")
    put(PreviewKind.Html, "HTML", "text/html", "html", "htm", "xhtml")
    put(PreviewKind.Table, "CSV", "text/csv", "csv")
    put(PreviewKind.Table, "TSV", "text/tab-separated-values", "tsv", "tab")
    put(PreviewKind.Json, "JSON", "application/json", "json", "har", "webmanifest")
    put(PreviewKind.Json, "GeoJSON", "application/geo+json", "geojson", "topojson")
    put(PreviewKind.Json, "JSON Lines", "application/x-ndjson", "jsonl", "ndjson")
    put(PreviewKind.Notebook, "Notebook", "application/x-ipynb+json", "ipynb")
    put(PreviewKind.Diff, "Diff", "text/x-diff", "diff", "patch")
    // Archives the app can list (they are all zip files underneath).
    put(
        PreviewKind.Archive, "ZIP", "application/zip",
        "zip", "jar", "apk", "aar", "war", "ear", "whl", "vsix", "xpi", "epub", "apkg",
        "docx", "xlsx", "pptx", "odt", "ods", "odp",
    )
    put(PreviewKind.Font, "TrueType", "font/ttf", "ttf")
    put(PreviewKind.Font, "OpenType", "font/otf", "otf")
    // Binary the app cannot render. A label tells the person what they are holding.
    put(PreviewKind.Binary, "GZIP", "application/gzip", "gz", "tgz")
    put(PreviewKind.Binary, "TAR", "application/x-tar", "tar")
    put(PreviewKind.Binary, "7-Zip", "application/x-7z-compressed", "7z")
    put(PreviewKind.Binary, "RAR", "application/vnd.rar", "rar")
    put(PreviewKind.Binary, "WOFF", "font/woff", "woff")
    put(PreviewKind.Binary, "WOFF2", "font/woff2", "woff2")
    put(PreviewKind.Binary, "SQLite", "application/vnd.sqlite3", "sqlite", "sqlite3")
    put(PreviewKind.Binary, "Executable", null, "exe", "dll")
    put(PreviewKind.Binary, "Library", null, "so", "dylib")
    put(PreviewKind.Binary, "Java class", null, "class")
    put(PreviewKind.Binary, "Dex", null, "dex")
    put(PreviewKind.Binary, "WebAssembly", "application/wasm", "wasm")
    put(PreviewKind.Binary, null, null, "bin", "dat", "o", "a")
    put(PreviewKind.Binary, "Disk image", null, "iso", "img", "dmg")
    put(PreviewKind.Binary, "Photoshop", null, "psd")
}

/** Source extensions and the name the badge shows for each. A missing name falls back to the upper-cased extension. */
private val CODE_LANGUAGES: Map<String, String> = mapOf(
    "js" to "JavaScript", "jsx" to "JSX", "mjs" to "JavaScript", "cjs" to "JavaScript",
    "ts" to "TypeScript", "tsx" to "TSX", "kt" to "Kotlin", "kts" to "Kotlin", "java" to "Java",
    "c" to "C", "h" to "C header", "cc" to "C++", "cpp" to "C++", "cxx" to "C++", "hpp" to "C++ header",
    "cs" to "C#", "go" to "Go", "rs" to "Rust", "swift" to "Swift", "dart" to "Dart", "scala" to "Scala",
    "css" to "CSS", "scss" to "SCSS", "less" to "Less", "py" to "Python", "pyi" to "Python",
    "sh" to "Shell", "bash" to "Shell", "zsh" to "Shell", "fish" to "Shell", "rb" to "Ruby", "pl" to "Perl",
    "r" to "R", "php" to "PHP", "lua" to "Lua", "sql" to "SQL", "vue" to "Vue", "svelte" to "Svelte",
    "gradle" to "Gradle", "groovy" to "Groovy", "yaml" to "YAML", "yml" to "YAML", "toml" to "TOML",
    "ini" to "INI", "cfg" to "Config", "conf" to "Config", "xml" to "XML", "plist" to "XML",
    "properties" to "Properties", "env" to "Env", "dockerfile" to "Dockerfile", "makefile" to "Makefile",
    "mk" to "Makefile", "cmake" to "CMake", "tf" to "Terraform", "proto" to "Protobuf",
    "graphql" to "GraphQL", "gql" to "GraphQL", "bat" to "Batch", "cmd" to "Batch", "ps1" to "PowerShell",
    "psm1" to "PowerShell", "vim" to "Vim", "el" to "Lisp", "clj" to "Clojure", "ex" to "Elixir",
    "exs" to "Elixir", "erl" to "Erlang", "hs" to "Haskell", "ml" to "OCaml", "nim" to "Nim",
    "zig" to "Zig", "jl" to "Julia", "m" to "Objective-C", "mm" to "Objective-C++", "asm" to "Assembly",
    "s" to "Assembly", "tex" to "LaTeX", "jsonc" to "JSON", "json5" to "JSON5", "gitignore" to "Git",
    "gitattributes" to "Git", "editorconfig" to "EditorConfig", "dockerignore" to "Docker", "npmrc" to "npm",
    "sbt" to "Scala", "pro" to "ProGuard", "smali" to "Smali", "lock" to "Lockfile", "bashrc" to "Shell",
    "zshrc" to "Shell", "profile" to "Shell", "toc" to "Config", "service" to "Config", "desktop" to "Config",
    "nix" to "Nix", "hcl" to "HCL", "ejs" to "EJS", "hbs" to "Handlebars", "pug" to "Pug",
    "coffee" to "CoffeeScript", "elm" to "Elm", "fs" to "F#", "fsx" to "F#", "vb" to "Visual Basic",
    "pas" to "Pascal", "d" to "D", "v" to "V", "sol" to "Solidity", "kv" to "Kv", "gd" to "GDScript",
)

private val TEXT_EXTENSIONS = setOf("txt", "text", "log", "rst", "adoc", "asciidoc", "org", "rtf", "nfo", "srt", "vtt", "bib")

/** Bare file names (no usable extension) that are source, with the language key they highlight as. */
private val WELL_KNOWN_NAMES: Map<String, String> = mapOf(
    "dockerfile" to "dockerfile", "containerfile" to "dockerfile",
    "makefile" to "makefile", "gnumakefile" to "makefile",
    "rakefile" to "rb", "gemfile" to "rb", "podfile" to "rb", "vagrantfile" to "rb", "fastfile" to "rb",
    "procfile" to "env", "justfile" to "makefile", "jenkinsfile" to "groovy", "brewfile" to "rb",
    "cmakelists.txt" to "cmake", "build.gradle" to "gradle", "settings.gradle" to "gradle",
)
private val TEXT_NAMES = setOf(
    "license", "licence", "readme", "changelog", "authors", "contributors", "copying", "notice",
    "todo", "codeowners", "version",
)

/** The last path segment, with either separator. */
internal fun fileNameOf(path: String): String = path.replace('\\', '/').substringAfterLast('/')

/**
 * The type the file's name gives, or null when the name says nothing (no extension, or one the app
 * does not know), which is the cue to look at the bytes.
 */
internal fun typeFromName(path: String): FileType? {
    val name = fileNameOf(path)
    val lower = name.lowercase()
    WELL_KNOWN_NAMES[lower]?.let { key ->
        return type(PreviewKind.Code, key, CODE_LANGUAGES[key] ?: key.uppercase())
    }
    if (lower == ".env" || lower.startsWith(".env.")) return type(PreviewKind.Code, "env", "Env")
    if (lower in TEXT_NAMES) return type(PreviewKind.Text, "txt")
    // A dotfile (`.bashrc`) is all extension.
    val extension = if (lower.startsWith(".") && lower.indexOf('.', 1) < 0) lower.removePrefix(".")
    else lower.substringAfterLast('.', "")
    if (extension.isEmpty()) return null
    EXTENSION_TYPES[extension]?.let { return it }
    CODE_LANGUAGES[extension]?.let { return type(PreviewKind.Code, extension, it) }
    if (extension in TEXT_EXTENSIONS) return type(PreviewKind.Text, extension)
    return null
}

/** The type a content type gives, or null for one the app does not render. Parameters (`; charset=`) are ignored. */
internal fun typeFromMime(mime: String?): FileType? {
    val base = mime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    fun byExt(ext: String): FileType? = EXTENSION_TYPES[ext]?.copy(sniffed = true)
    fun generic(kind: PreviewKind): FileType {
        val sub = base.substringAfter('/').removePrefix("x-")
        return byExt(sub) ?: type(kind, sub, sub.uppercase(), base).copy(sniffed = true)
    }
    return when {
        base == "application/pdf" -> byExt("pdf")
        base == "image/svg+xml" -> byExt("svg")
        base == "image/jpeg" || base == "image/jpg" -> byExt("jpg")
        base == "image/x-icon" || base == "image/vnd.microsoft.icon" -> byExt("ico")
        base.startsWith("image/") -> generic(PreviewKind.Image)
        base == "video/quicktime" -> byExt("mov")
        base == "video/x-matroska" -> byExt("mkv")
        base == "video/3gpp" || base == "video/3gpp2" -> byExt("3gp")
        base.startsWith("video/") -> generic(PreviewKind.Video)
        base == "audio/mpeg" -> byExt("mp3")
        base == "audio/x-wav" || base == "audio/wave" -> byExt("wav")
        base.startsWith("audio/") -> generic(PreviewKind.Audio)
        base == "text/markdown" -> byExt("md")
        base == "text/html" || base == "application/xhtml+xml" -> byExt("html")
        base == "text/csv" -> byExt("csv")
        base == "text/tab-separated-values" -> byExt("tsv")
        base == "application/geo+json" -> byExt("geojson")
        base == "application/x-ndjson" || base == "application/jsonl" -> byExt("jsonl")
        base == "application/x-ipynb+json" -> byExt("ipynb")
        base == "application/json" || base.endsWith("+json") -> byExt("json")
        base == "text/x-diff" || base == "text/x-patch" -> byExt("diff")
        base == "application/zip" || base == "application/java-archive" -> byExt("zip")
        base == "text/xml" || base == "application/xml" -> type(PreviewKind.Code, "xml", "XML", base).copy(sniffed = true)
        base.startsWith("text/") -> type(PreviewKind.Text, "txt", null, base).copy(sniffed = true)
        else -> null
    }
}

private fun ByteArray.u(i: Int): Int = if (i in indices) this[i].toInt() and 0xFF else -1

private fun ByteArray.startsWithBytes(vararg prefix: Int, at: Int = 0): Boolean =
    prefix.indices.all { u(at + it) == prefix[it] }

private fun ByteArray.startsWithAscii(text: String, at: Int = 0): Boolean =
    text.indices.all { u(at + it) == text[it].code }

private fun ByteArray.containsAscii(text: String, within: Int): Boolean {
    val end = minOf(size, within) - text.length
    for (start in 0..end) if (startsWithAscii(text, start)) return true
    return false
}

private val BMP_DIB_SIZES = setOf(12, 40, 52, 56, 64, 108, 124)

private fun looksLikeMp3Frame(head: ByteArray): Boolean {
    if (head.u(0) != 0xFF || head.u(1) and 0xE0 != 0xE0) return false
    val version = (head.u(1) shr 3) and 0x03
    val layer = (head.u(1) shr 1) and 0x03
    val bitrate = (head.u(2) shr 4) and 0x0F
    val rate = (head.u(2) shr 2) and 0x03
    return version != 1 && layer != 0 && bitrate != 0x0F && bitrate != 0 && rate != 3
}

/**
 * The type the first bytes of a file give, or null when they match nothing the app knows and the
 * data is not text. [head] is whatever was read from the start of the file (16 KB is plenty).
 *
 * Plain text that matches no format is [PreviewKind.Text]; data with NULs or a pile of control
 * bytes is [PreviewKind.Binary].
 */
internal fun sniffFileType(head: ByteArray): FileType? {
    if (head.isEmpty()) return null
    fun known(ext: String) = EXTENSION_TYPES.getValue(ext).copy(sniffed = true)
    when {
        head.startsWithBytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> return known("png")
        head.startsWithBytes(0xFF, 0xD8, 0xFF) -> return known("jpg")
        head.startsWithAscii("GIF87a") || head.startsWithAscii("GIF89a") -> return known("gif")
        head.startsWithAscii("RIFF") && head.startsWithAscii("WEBP", 8) -> return known("webp")
        head.startsWithAscii("RIFF") && head.startsWithAscii("WAVE", 8) -> return known("wav")
        head.startsWithAscii("BM") && head.size >= 26 && head.u(6) == 0 && head.u(7) == 0 &&
            head.u(8) == 0 && head.u(9) == 0 && head.u(14) in BMP_DIB_SIZES -> return known("bmp")
        head.startsWithBytes(0x00, 0x00, 0x01, 0x00) && head.size >= 22 && (head.u(4) or (head.u(5) shl 8)) in 1..256 ->
            return known("ico")
        head.startsWithAscii("%PDF-") -> return known("pdf")
        head.startsWithAscii("PK") && (head.startsWithBytes(0x50, 0x4B, 0x03, 0x04) || head.startsWithBytes(0x50, 0x4B, 0x05, 0x06)) ->
            return known("zip")
        head.startsWithAscii("ftyp", 4) -> {
            val brand = String(head, 8, minOf(4, head.size - 8).coerceAtLeast(0), Charsets.ISO_8859_1)
            return when {
                brand in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1") -> known("heic")
                brand == "avif" || brand == "avis" -> known("avif")
                brand == "qt  " -> known("mov")
                brand == "M4A " || brand == "M4B " -> known("m4a")
                brand.startsWith("3g") -> known("3gp")
                else -> known("mp4")
            }
        }
        head.startsWithBytes(0x1A, 0x45, 0xDF, 0xA3) -> return if (head.containsAscii("webm", 64)) known("webm") else known("mkv")
        head.startsWithAscii("OggS") -> return known("ogg")
        head.startsWithAscii("fLaC") -> return known("flac")
        head.startsWithAscii("ID3") && head.u(3) in 2..4 -> return known("mp3")
        head.u(0) == 0xFF && (head.u(1) == 0xF1 || head.u(1) == 0xF9) -> return known("aac")
        looksLikeMp3Frame(head) -> return known("mp3")
        head.startsWithBytes(0x00, 0x01, 0x00, 0x00, 0x00) -> return known("ttf")
        head.startsWithAscii("OTTO") -> return known("otf")
        head.startsWithAscii("wOFF") -> return known("woff")
        head.startsWithAscii("wOF2") -> return known("woff2")
        head.startsWithBytes(0x1F, 0x8B) -> return known("gz")
        head.startsWithBytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> return known("7z")
        head.startsWithAscii("Rar!") -> return known("rar")
        head.startsWithAscii("SQLite format 3") -> return known("sqlite")
        head.startsWithBytes(0x7F, 0x45, 0x4C, 0x46) || head.startsWithBytes(0x00, 0x61, 0x73, 0x6D) ||
            head.startsWithBytes(0xCA, 0xFE, 0xBA, 0xBE) -> return known("bin")
        head.startsWithAscii("ustar", 257) -> return known("tar")
    }
    if (!looksLikeText(head)) return type(PreviewKind.Binary, "bin").copy(sniffed = true)
    val start = head.decodeToString(0, minOf(head.size, 1024)).removePrefix(BYTE_ORDER_MARK).trimStart()
    return when {
        start.startsWith("<svg") || start.startsWith("<?xml") && start.contains("<svg") -> known("svg")
        start.startsWith("<!doctype html", ignoreCase = true) || start.startsWith("<html", ignoreCase = true) -> known("html")
        start.startsWith("diff --git") || start.startsWith("--- a/") || start.startsWith("Index: ") -> known("diff")
        start.startsWith("<?xml") -> type(PreviewKind.Code, "xml", "XML").copy(sniffed = true)
        start.startsWith("#!") -> {
            val line = start.lineSequence().first()
            val key = when {
                "python" in line -> "py"
                "node" in line || "deno" in line || "bun" in line -> "js"
                "ruby" in line -> "rb"
                "perl" in line -> "pl"
                else -> "sh"
            }
            type(PreviewKind.Code, key, CODE_LANGUAGES[key]).copy(sniffed = true)
        }
        else -> type(PreviewKind.Text, "txt").copy(sniffed = true)
    }
}

/**
 * Whether [bytes] read as text: no NULs, few stray control bytes, and valid UTF-8 (a cut-off
 * character at the very end is tolerated, since the window ends wherever it ends).
 */
internal fun looksLikeText(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return true
    var control = 0
    var invalid = 0
    var i = 0
    val limit = bytes.size
    while (i < limit) {
        val b = bytes[i].toInt() and 0xFF
        when {
            b == 0 -> return false
            b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C && b != 0x1B -> { control++; i++ }
            b < 0x80 -> i++
            else -> {
                val need = when {
                    b and 0xE0 == 0xC0 -> 1
                    b and 0xF0 == 0xE0 -> 2
                    b and 0xF8 == 0xF0 -> 3
                    else -> -1
                }
                if (need < 0) { invalid++; i++ }
                else if (i + need >= limit) break // cut off by the window
                else {
                    val ok = (1..need).all { bytes[i + it].toInt() and 0xC0 == 0x80 }
                    if (ok) i += need + 1 else { invalid++; i++ }
                }
            }
        }
    }
    return control * 100 <= limit * 2 && invalid * 100 <= limit * 2
}

/**
 * The type of [path]: the name first, then [mime], then [head]. Null only when the name and mime
 * say nothing and no [head] has been read yet, which is the caller's cue to read it.
 */
internal fun detectFileType(path: String, mime: String? = null, head: ByteArray? = null): FileType? {
    typeFromName(path)?.let { return it }
    typeFromMime(mime)?.let { return it }
    return head?.let { sniffFileType(it) } ?: head?.let { type(PreviewKind.Text, "txt").copy(sniffed = true) }
}
