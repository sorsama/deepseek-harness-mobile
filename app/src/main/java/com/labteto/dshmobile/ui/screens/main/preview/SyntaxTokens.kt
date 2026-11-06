package com.labteto.dshmobile.ui.screens.main.preview

/**
 * A cheap, line-at-a-time syntax tokenizer.
 *
 * It does not parse: it finds comments, strings, numbers, keywords and call names on one line, and
 * a few shapes that matter for config files (`key:` in YAML, `[section]` in INI, Dockerfile
 * instructions, Makefile targets, markup tags). A block comment or a multi-line string that spans
 * lines is only coloured on the line that opens it. That is the trade for being able to colour any
 * line of a 4 MB file the moment it scrolls into view.
 */
internal enum class TokenKind { Keyword, String, Number, Comment, Function, Key, Attribute }

internal data class Token(val start: Int, val end: Int, val kind: TokenKind)

private enum class Family { C, Hash, Dash, Sql, Markup, Json, Ini, Yaml, Plain }

private val C_EXT = setOf(
    "js", "jsx", "ts", "tsx", "mjs", "cjs", "kt", "kts", "java", "c", "h", "cc", "cpp", "cxx", "hpp", "cs",
    "go", "rs", "swift", "dart", "scala", "sbt", "css", "scss", "less", "gradle", "groovy", "php", "proto",
    "sol", "zig", "jsonc", "json5", "vue", "svelte", "m", "mm", "d", "v", "pro", "smali", "gd", "coffee",
    "fs", "fsx", "ejs", "graphql", "gql",
)
private val HASH_EXT = setOf(
    "py", "pyi", "sh", "bash", "zsh", "fish", "rb", "pl", "r", "env", "dockerfile", "makefile", "mk", "cmake",
    "tf", "hcl", "nix", "ps1", "psm1", "gitignore", "gitattributes", "dockerignore", "npmrc", "bashrc", "zshrc",
    "profile", "ex", "exs", "jl", "lock", "bat-hash",
)
private val DASH_EXT = setOf("lua", "hs", "elm")
private val INI_EXT = setOf("ini", "toml", "properties", "cfg", "conf", "editorconfig", "service", "desktop", "toc")
private val MARKUP_EXT = setOf("xml", "plist", "html", "htm", "xhtml", "svg")
private val YAML_EXT = setOf("yaml", "yml")

private fun familyOf(language: String): Family = when (language) {
    "json", "geojson", "jsonl", "ndjson", "topojson", "har", "webmanifest" -> Family.Json
    in C_EXT -> Family.C
    in HASH_EXT -> Family.Hash
    in DASH_EXT -> Family.Dash
    "sql" -> Family.Sql
    in INI_EXT -> Family.Ini
    in MARKUP_EXT -> Family.Markup
    in YAML_EXT -> Family.Yaml
    else -> Family.Plain
}

/** True when the language gets any colouring at all. */
internal fun isHighlighted(language: String): Boolean = familyOf(language) != Family.Plain

private val KEYWORDS = setOf(
    "fun", "val", "var", "const", "let", "function", "export", "default", "import", "from",
    "return", "if", "else", "for", "while", "do", "when", "switch", "case", "break", "continue",
    "class", "interface", "object", "struct", "enum", "package", "public", "private", "protected",
    "internal", "static", "final", "void", "new", "try", "catch", "finally", "throw", "throws",
    "async", "await", "yield", "def", "lambda", "pass", "raise", "with", "as", "in", "is", "of",
    "null", "true", "false", "undefined", "None", "True", "False", "this", "super", "self", "type",
    "extends", "implements", "override", "suspend", "init", "companion", "data", "sealed", "abstract",
    "match", "elif", "print", "echo", "local", "require", "module", "fn", "impl", "pub", "use", "mut",
    "then", "fi", "done", "esac", "func", "defer", "go", "chan", "select", "range", "trait", "where",
)
private val SQL_KEYWORDS = setOf(
    "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table",
    "alter", "drop", "index", "join", "left", "right", "inner", "outer", "on", "group", "by", "order",
    "having", "limit", "and", "or", "not", "null", "as", "distinct", "union", "primary", "key", "foreign",
    "references", "default", "begin", "commit", "rollback", "case", "when", "then", "else", "end", "in",
    "like", "between", "is", "exists", "view", "trigger", "if", "with",
)
private val DOCKER_INSTRUCTIONS = setOf(
    "FROM", "RUN", "CMD", "LABEL", "EXPOSE", "ENV", "ADD", "COPY", "ENTRYPOINT", "VOLUME", "USER",
    "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK", "SHELL",
)

private fun markersFor(family: Family): List<String> = when (family) {
    Family.C -> listOf("//", "/*")
    Family.Hash, Family.Yaml -> listOf("#")
    Family.Dash -> listOf("--")
    Family.Sql -> listOf("--", "/*")
    Family.Ini -> listOf("#", ";")
    else -> emptyList()
}

/**
 * Where a comment starts on [line], ignoring markers inside quotes, or -1. A `#` after text in a
 * YAML or INI value counts only when it follows whitespace, so `color: #fff` and `a#b` are not cut.
 */
internal fun commentStart(line: String, markers: List<String>, requireSpaceBefore: Boolean = false): Int {
    var quote = 0.toChar()
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (quote != 0.toChar()) {
            if (c == '\\') i++
            else if (c == quote) quote = 0.toChar()
        } else if (c == '"' || c == '\'' || c == '`') {
            quote = c
        } else {
            for (marker in markers) {
                if (line.startsWith(marker, i)) {
                    if (!requireSpaceBefore || i == 0 || line[i - 1].isWhitespace()) return i
                }
            }
        }
        i++
    }
    return -1
}

/** The tokens of one line of a file whose language key is [language] (usually its extension). */
internal fun tokenizeLine(line: String, language: String): List<Token> {
    if (line.isBlank()) return emptyList()
    return when (val family = familyOf(language)) {
        Family.Plain -> emptyList()
        Family.Json -> tokenizeJsonLine(line)
        Family.Markup -> tokenizeMarkupLine(line)
        else -> tokenizeCodeLine(line, language, family)
    }
}

private fun tokenizeCodeLine(line: String, language: String, family: Family): List<Token> {
    val tokens = ArrayList<Token>()
    val trimmed = line.trimStart()
    val indent = line.length - trimmed.length
    // A line that is wholly inside a C-style block comment (` * text`, ` */`).
    if (family == Family.C && (trimmed.startsWith("* ") || trimmed == "*" || trimmed.startsWith("*/"))) {
        return listOf(Token(indent, line.length, TokenKind.Comment))
    }
    val spaced = family == Family.Yaml || family == Family.Ini
    val comment = commentStart(line, markersFor(family), requireSpaceBefore = spaced)
    val limit = if (comment >= 0) comment else line.length
    var commentEnd = line.length
    if (comment >= 0 && line.startsWith("/*", comment)) {
        val close = line.indexOf("*/", comment + 2)
        if (close >= 0) commentEnd = close + 2
    }
    var cursor = 0
    // Shapes that own the start of a line.
    when {
        family == Family.Ini && trimmed.startsWith("[") && trimmed.contains(']') -> {
            val end = line.indexOf(']') + 1
            tokens.add(Token(indent, end, TokenKind.Function))
            cursor = end
        }
        family == Family.Ini || family == Family.Yaml -> {
            var start = indent
            if (family == Family.Yaml) {
                while (line.startsWith("- ", start)) start += 2
                while (start < line.length && line[start] == ' ') start++
            }
            var end = start
            while (end < limit && (line[end].isLetterOrDigit() || line[end] in "_-.$/ \"'")) end++
            val key = line.substring(start, end).trimEnd()
            val after = start + key.length
            var at = after
            while (at < line.length && line[at] == ' ') at++
            val separator = line.getOrNull(at)
            if (key.isNotEmpty() && separator != null && (separator == '=' || separator == ':' && (at + 1 >= line.length || line[at + 1] == ' '))) {
                tokens.add(Token(start, after, TokenKind.Key))
                cursor = at + 1
            }
        }
        language == "dockerfile" -> {
            val word = trimmed.takeWhile { it.isLetter() }
            if (word in DOCKER_INSTRUCTIONS) {
                tokens.add(Token(indent, indent + word.length, TokenKind.Keyword))
                cursor = indent + word.length
            }
        }
        language == "makefile" || language == "mk" -> {
            val colon = trimmed.indexOf(':')
            if (colon > 0 && !trimmed.startsWith("\t") && !line.startsWith("\t") && trimmed.substring(0, colon).none { it == ' ' || it == '=' || it == '$' }) {
                tokens.add(Token(indent, indent + colon, TokenKind.Function))
                cursor = indent + colon + 1
            }
        }
    }
    val keywords = if (family == Family.Sql) SQL_KEYWORDS else KEYWORDS
    while (cursor < limit) {
        val c = line[cursor]
        when {
            c == '"' || c == '\'' || c == '`' -> {
                var end = cursor + 1
                while (end < limit) {
                    if (line[end] == '\\') end += 2
                    else if (line[end] == c) { end++; break }
                    else end++
                }
                end = end.coerceAtMost(limit)
                tokens.add(Token(cursor, end, TokenKind.String))
                cursor = end
            }
            c.isDigit() -> {
                var end = cursor
                while (end < limit && (line[end].isLetterOrDigit() || line[end] == '.' || line[end] == '_')) end++
                tokens.add(Token(cursor, end, TokenKind.Number))
                cursor = end
            }
            c.isLetter() || c == '_' || c == '$' && family == Family.Hash -> {
                var end = cursor + 1
                while (end < limit && (line[end].isLetterOrDigit() || line[end] == '_')) end++
                val word = line.substring(cursor, end)
                val kind = when {
                    (if (family == Family.Sql) word.lowercase() else word) in keywords -> TokenKind.Keyword
                    end < limit && line[end] == '(' -> TokenKind.Function
                    else -> null
                }
                kind?.let { tokens.add(Token(cursor, end, it)) }
                cursor = end
            }
            else -> cursor++
        }
    }
    if (comment >= 0) {
        tokens.add(Token(comment, commentEnd, TokenKind.Comment))
        // Text after a closed inline block comment is code again.
        if (commentEnd < line.length) {
            tokens.addAll(tokenizeCodeLine(line.substring(commentEnd), language, family).map {
                Token(it.start + commentEnd, it.end + commentEnd, it.kind)
            })
        }
    }
    return tokens
}

/** One line of JSON: keys, strings, numbers and the three literals. Works on compact or pretty text. */
internal fun tokenizeJsonLine(line: String): List<Token> {
    val tokens = ArrayList<Token>()
    var i = 0
    val n = line.length
    while (i < n) {
        val c = line[i]
        when {
            c == '"' -> {
                var end = i + 1
                while (end < n) {
                    if (line[end] == '\\') end += 2
                    else if (line[end] == '"') { end++; break }
                    else end++
                }
                end = end.coerceAtMost(n)
                var look = end
                while (look < n && line[look] == ' ') look++
                tokens.add(Token(i, end, if (look < n && line[look] == ':') TokenKind.Key else TokenKind.String))
                i = end
            }
            c == '-' || c.isDigit() -> {
                var end = i + 1
                while (end < n && (line[end].isDigit() || line[end] in ".eE+-")) end++
                tokens.add(Token(i, end, TokenKind.Number))
                i = end
            }
            c.isLetter() -> {
                var end = i
                while (end < n && line[end].isLetter()) end++
                if (line.substring(i, end) in setOf("true", "false", "null")) tokens.add(Token(i, end, TokenKind.Keyword))
                i = end
            }
            c == '/' && line.startsWith("//", i) -> {
                tokens.add(Token(i, n, TokenKind.Comment))
                i = n
            }
            else -> i++
        }
    }
    return tokens
}

/** One line of XML or HTML: comments, tag names, attribute names and quoted values. */
internal fun tokenizeMarkupLine(line: String): List<Token> {
    val tokens = ArrayList<Token>()
    var i = 0
    val n = line.length
    var inTag = false
    while (i < n) {
        val c = line[i]
        when {
            line.startsWith("<!--", i) -> {
                val close = line.indexOf("-->", i + 4)
                val end = if (close >= 0) close + 3 else n
                tokens.add(Token(i, end, TokenKind.Comment))
                i = end
            }
            c == '<' -> {
                var end = i + 1
                if (end < n && (line[end] == '/' || line[end] == '?' || line[end] == '!')) end++
                val nameStart = end
                while (end < n && (line[end].isLetterOrDigit() || line[end] in "-_:.")) end++
                tokens.add(Token(i, end.coerceAtLeast(nameStart), TokenKind.Keyword))
                inTag = true
                i = end.coerceAtLeast(i + 1)
            }
            inTag && c == '>' -> { inTag = false; i++ }
            inTag && (c == '"' || c == '\'') -> {
                val close = line.indexOf(c, i + 1)
                val end = if (close >= 0) close + 1 else n
                tokens.add(Token(i, end, TokenKind.String))
                i = end
            }
            inTag && (c.isLetter() || c == '_') -> {
                var end = i
                while (end < n && (line[end].isLetterOrDigit() || line[end] in "-_:.")) end++
                tokens.add(Token(i, end, TokenKind.Attribute))
                i = end
            }
            else -> i++
        }
    }
    return tokens
}
