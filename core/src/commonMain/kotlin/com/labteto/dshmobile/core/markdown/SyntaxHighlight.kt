package com.labteto.dshmobile.core.markdown

/**
 * Lightweight syntax highlighting, pure JVM (no Android or Compose imports) so the tokenizer's
 * behaviour is unit-testable in `:core` and survives a merge that rewrites the renderer.
 *
 * This is a lexer, not a parser: it walks each line once and labels spans. That is deliberate — a
 * real grammar per language would be thousands of lines to keep correct, and a phone screen shows
 * a dozen lines of code at a time, where comments/strings/keywords/numbers carry essentially all
 * of the readability. An unknown language degrades to a single Plain span rather than throwing.
 *
 * The color mapping lives in the UI layer, not here: this file names *what* a span is, and the
 * theme decides what that looks like.
 */

/** What a highlighted span is. The renderer maps each to a color. */
enum class SyntaxToken {
    Comment,
    String,
    Number,
    Keyword,
    Function,
    Type,
    Plain,
}

/** One labelled slice of a line. Concatenating [text] over all spans reproduces the input. */
data class SyntaxSpan(val text: String, val token: SyntaxToken)

/** Language ids that mean the same lexer, mapped to the canonical one. */
private val LANG_ALIAS = mapOf(
    "js" to "typescript", "javascript" to "typescript", "ts" to "typescript", "tsx" to "typescript",
    "sh" to "bash", "shell" to "bash", "zsh" to "bash", "console" to "bash",
    "py" to "python", "yml" to "yaml", "kt" to "kotlin", "kts" to "kotlin",
    "html" to "xml", "htm" to "xml", "vue" to "xml", "svg" to "xml",
)

private val COMMENT_BY_LANG = mapOf(
    "java" to Regex("//[^\\n]*"),
    "kotlin" to Regex("//[^\\n]*"),
    "typescript" to Regex("//[^\\n]*"),
    "c" to Regex("//[^\\n]*"),
    "json" to Regex("//[^\\n]*"),
    "python" to Regex("#[^\\n]*"),
    "yaml" to Regex("#[^\\n]*"),
    "bash" to Regex("#[^\\n]*"),
    "sql" to Regex("(--[^\\n]*|#[^\\n]*)"),
    "xml" to Regex("<!--[\\s\\S]*?-->"),
)

/**
 * Block comment delimiters, tried before the line comment.
 *
 * Only the c-family and sql have one worth the state machine; the others (python docstrings, yaml)
 * have no block form at all. Left unhandled, a `/* … */` in a Kotlin snippet would go on coloring
 * the code after it as a comment.
 */
private val BLOCK_COMMENT_OPEN = mapOf(
    "java" to "/*", "kotlin" to "/*", "typescript" to "/*", "c" to "/*", "sql" to "/*",
)

private const val BLOCK_COMMENT_CLOSE = "*/"

/** Keywords per language. Not exhaustive by design — the high-value words only. */
private val KEYWORDS_BY_LANG = mapOf(
    "kotlin" to setOf(
        "fun", "val", "var", "if", "else", "return", "class", "object", "when", "for", "in",
        "import", "package", "private", "public", "internal", "suspend", "data", "override",
        "this", "null", "true", "false", "interface", "sealed", "enum", "companion", "by",
        "is", "as", "try", "catch", "finally", "throw", "init", "constructor", "lateinit",
    ),
    "java" to setOf(
        "public", "private", "protected", "static", "final", "class", "interface", "void", "int",
        "long", "double", "boolean", "return", "new", "if", "else", "for", "while", "import",
        "package", "null", "true", "false", "String", "extends", "implements", "throws", "try",
        "catch", "this", "super",
    ),
    "typescript" to setOf(
        "const", "let", "var", "function", "return", "export", "import", "from", "async", "await",
        "class", "interface", "type", "if", "else", "null", "true", "false", "this", "extends",
        "implements", "new", "typeof", "void", "public", "private", "readonly", "as", "of", "in",
    ),
    "python" to setOf(
        "def", "return", "import", "from", "class", "if", "else", "elif", "for", "in", "and", "or",
        "not", "None", "True", "False", "async", "await", "with", "try", "except", "finally",
        "lambda", "yield", "raise", "pass", "global", "is",
    ),
    "json" to setOf("true", "false", "null"),
    "yaml" to setOf("true", "false", "null", "yes", "no", "on", "off"),
    "bash" to setOf(
        "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "function", "return",
        "echo", "export", "local", "case", "esac", "while", "until", "read", "set", "source",
    ),
    "sql" to setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
        "CREATE", "TABLE", "INDEX", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "AND", "OR",
        "NOT", "NULL", "ORDER", "BY", "GROUP", "HAVING", "LIMIT", "OFFSET", "AS", "PRIMARY",
        "KEY", "FOREIGN", "REFERENCES", "INT", "INTEGER", "VARCHAR", "TEXT", "BOOLEAN", "TIMESTAMP",
    ),
    "xml" to setOf("true", "false", "null"),
)

/** Languages whose keywords are matched case-sensitively (SQL is conventionally upper-case). */
private val CASE_INSENSITIVE_KEYWORDS = setOf("sql")

private val STRING_RE = Regex("\"[^\"\\n]*\"|'[^'\\n]*'|`[^`\\n]*`")
private val WORD_RE = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val NUMBER_RE = Regex("\\d+\\.?\\d*")

/** The canonical lexer id for a fence language, or null when nothing recognizes it. */
fun normalizeLanguage(lang: String?): String? =
    lang?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { LANG_ALIAS[it] ?: it }

/** Whether [lang] has a lexer. Plain-text fences get no highlighting work at all. */
fun isHighlighted(lang: String?): Boolean = normalizeLanguage(lang)?.let { it in KEYWORDS_BY_LANG || it in COMMENT_BY_LANG } == true

/**
 * Split one code block into lines of labelled spans.
 *
 * Block-comment state carries across lines; everything else is decided per line. Concatenating
 * every span's text, and joining the lines with `\n`, reproduces [code] exactly — the renderer
 * relies on that to lay the result out as ordinary text.
 */
fun highlightCode(code: String, lang: String?): List<List<SyntaxSpan>> {
    val langKey = normalizeLanguage(lang)
    if (langKey == null || !isHighlighted(langKey)) {
        return code.split("\n").map { line -> listOf(SyntaxSpan(line, SyntaxToken.Plain)) }
    }
    val keywords = KEYWORDS_BY_LANG[langKey].orEmpty()
    val caseInsensitive = langKey in CASE_INSENSITIVE_KEYWORDS
    val lineComment = COMMENT_BY_LANG[langKey]
    val blockOpen = BLOCK_COMMENT_OPEN[langKey]

    var inBlockComment = false
    return code.split("\n").map { line ->
        // Minified/generated source should not allocate a styled span for every token.
        // Reset lexical state across the skipped line rather than guessing its delimiters.
        if (line.length > 10_000) {
            inBlockComment = false
            return@map listOf(SyntaxSpan(line, SyntaxToken.Plain))
        }
        // HighlightLine reports where it ended up, which is where the next line starts.
        val (spans, endedInBlock) = highlightLine(
            line, keywords, caseInsensitive, lineComment, blockOpen, inBlockComment,
        )
        inBlockComment = endedInBlock
        spans
    }
}

private fun highlightLine(
    text: String,
    keywords: Set<String>,
    caseInsensitive: Boolean,
    lineComment: Regex?,
    blockOpen: String?,
    startInBlock: Boolean,
): Pair<List<SyntaxSpan>, Boolean> {
    val out = mutableListOf<SyntaxSpan>()
    var i = 0
    var inBlock = startInBlock

    fun push(token: SyntaxToken, value: String) {
        if (value.isEmpty()) return
        // Merge with the previous span of the same token so the renderer builds fewer spans.
        val last = out.lastOrNull()
        if (last != null && last.token == token) out[out.lastIndex] = last.copy(text = last.text + value)
        else out += SyntaxSpan(value, token)
    }

    while (i < text.length) {
        if (inBlock) {
            val close = text.indexOf(BLOCK_COMMENT_CLOSE, i)
            if (close < 0) {
                // Still open at end of line: the next line resumes inside the comment.
                push(SyntaxToken.Comment, text.substring(i))
                return out to true
            }
            push(SyntaxToken.Comment, text.substring(i, close + BLOCK_COMMENT_CLOSE.length))
            i = close + BLOCK_COMMENT_CLOSE.length
            inBlock = false
            continue
        }
        if (blockOpen != null && text.startsWith(blockOpen, i)) {
            push(SyntaxToken.Comment, blockOpen)
            i += blockOpen.length
            inBlock = true
            continue
        }
        if (lineComment != null) {
            val cm = lineComment.find(text, i)
            if (cm != null && cm.range.first == i) {
                push(SyntaxToken.Comment, cm.value)
                return out to false
            }
        }
        val sm = STRING_RE.find(text, i)
        if (sm != null && sm.range.first == i) {
            push(SyntaxToken.String, sm.value)
            i += sm.value.length
            continue
        }
        val wordMatch = WORD_RE.find(text, i)
        if (wordMatch != null && wordMatch.range.first == i) {
            val word = wordMatch.value
            val isKeyword = if (caseInsensitive) {
                keywords.any { it.equals(word, ignoreCase = true) }
            } else {
                word in keywords
            }
            val token = when {
                isKeyword -> SyntaxToken.Keyword
                // A word immediately followed by `(` reads as a call, which is what a reader
                // scans for; a capitalized word is a type or constructor by convention.
                text.getOrNull(i + word.length) == '(' -> SyntaxToken.Function
                word.first().isUpperCase() -> SyntaxToken.Type
                else -> SyntaxToken.Plain
            }
            push(token, word)
            i += word.length
            continue
        }
        val numMatch = NUMBER_RE.find(text, i)
        if (numMatch != null && numMatch.range.first == i) {
            push(SyntaxToken.Number, numMatch.value)
            i += numMatch.value.length
            continue
        }
        push(SyntaxToken.Plain, text[i].toString())
        i++
    }
    return out to inBlock
}
