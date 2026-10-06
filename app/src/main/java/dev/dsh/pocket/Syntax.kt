package dev.dsh.pocket

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/** Languages the viewer can colour. PLAIN means "show it, do not scan it". */
enum class SyntaxLang {
    PLAIN, C, CPP, PYTHON, KOTLIN, JAVA, JAVASCRIPT, TYPESCRIPT, JSON, MARKDOWN,
    SHELL, YAML, TOML, XML, CSS, SQL, RUST, GO, RUBY, PHP, SWIFT, CSHARP, DART, LUA
}

// A light palette that matches the rest of the app; the viewer is white paper.
object SyntaxColors {
    val keyword = Color(0xFFC2185B)
    val type = Color(0xFF7B1FA2)
    val string = Color(0xFF2E7D32)
    val comment = Color(0xFF8A94A6)
    val number = Color(0xFFB26A00)
    val annotation = Color(0xFF00796B)
    val heading = Color(0xFF1D4ED8)
    val plain = Color(0xFF1F2937)
}

private val KEYWORD_STYLE = SpanStyle(SyntaxColors.keyword)
private val TYPE_STYLE = SpanStyle(SyntaxColors.type)
private val STRING_STYLE = SpanStyle(SyntaxColors.string)
private val COMMENT_STYLE = SpanStyle(SyntaxColors.comment)
private val NUMBER_STYLE = SpanStyle(SyntaxColors.number)
private val ANNOTATION_STYLE = SpanStyle(SyntaxColors.annotation)
private val HEADING_STYLE = SpanStyle(SyntaxColors.heading)

// Highlighting is a single linear scan; past this size the scan would start to
// show up while typing, so the editor degrades to plain monospace instead.
private const val HIGHLIGHT_LIMIT = 400_000
private val TICK = 0x60.toChar()

private val BY_LANGUAGE = mapOf(
    "c" to SyntaxLang.C, "cpp" to SyntaxLang.CPP, "python" to SyntaxLang.PYTHON,
    "kotlin" to SyntaxLang.KOTLIN, "java" to SyntaxLang.JAVA, "javascript" to SyntaxLang.JAVASCRIPT,
    "typescript" to SyntaxLang.TYPESCRIPT, "json" to SyntaxLang.JSON, "markdown" to SyntaxLang.MARKDOWN,
    "shell" to SyntaxLang.SHELL, "yaml" to SyntaxLang.YAML, "toml" to SyntaxLang.TOML, "xml" to SyntaxLang.XML,
    "css" to SyntaxLang.CSS, "sql" to SyntaxLang.SQL, "rust" to SyntaxLang.RUST, "go" to SyntaxLang.GO,
    "ruby" to SyntaxLang.RUBY, "php" to SyntaxLang.PHP, "swift" to SyntaxLang.SWIFT, "csharp" to SyntaxLang.CSHARP,
    "dart" to SyntaxLang.DART, "lua" to SyntaxLang.LUA, "plain" to SyntaxLang.PLAIN,
)

private val BY_EXTENSION = mapOf(
    "c" to SyntaxLang.C, "h" to SyntaxLang.C, "cpp" to SyntaxLang.CPP, "cc" to SyntaxLang.CPP,
    "cxx" to SyntaxLang.CPP, "hpp" to SyntaxLang.CPP, "py" to SyntaxLang.PYTHON, "pyw" to SyntaxLang.PYTHON,
    "kt" to SyntaxLang.KOTLIN, "kts" to SyntaxLang.KOTLIN, "gradle" to SyntaxLang.KOTLIN, "java" to SyntaxLang.JAVA,
    "js" to SyntaxLang.JAVASCRIPT, "mjs" to SyntaxLang.JAVASCRIPT, "cjs" to SyntaxLang.JAVASCRIPT,
    "jsx" to SyntaxLang.JAVASCRIPT, "ts" to SyntaxLang.TYPESCRIPT, "tsx" to SyntaxLang.TYPESCRIPT,
    "json" to SyntaxLang.JSON, "md" to SyntaxLang.MARKDOWN, "markdown" to SyntaxLang.MARKDOWN,
    "sh" to SyntaxLang.SHELL, "bash" to SyntaxLang.SHELL, "zsh" to SyntaxLang.SHELL, "env" to SyntaxLang.SHELL,
    "yml" to SyntaxLang.YAML, "yaml" to SyntaxLang.YAML, "toml" to SyntaxLang.TOML, "xml" to SyntaxLang.XML,
    "html" to SyntaxLang.XML, "htm" to SyntaxLang.XML, "svg" to SyntaxLang.XML, "vue" to SyntaxLang.XML,
    "css" to SyntaxLang.CSS, "scss" to SyntaxLang.CSS, "sql" to SyntaxLang.SQL, "rs" to SyntaxLang.RUST,
    "go" to SyntaxLang.GO, "rb" to SyntaxLang.RUBY, "php" to SyntaxLang.PHP, "swift" to SyntaxLang.SWIFT,
    "cs" to SyntaxLang.CSHARP, "dart" to SyntaxLang.DART, "lua" to SyntaxLang.LUA,
)

/** The bridge sends a language id; the file name is the fallback. */
fun syntaxFor(language: String, name: String): SyntaxLang {
    BY_LANGUAGE[language.lowercase()]?.let { return it }
    val base = name.substringAfterLast('/').lowercase()
    val ext = base.substringAfterLast('.', "")
    return BY_EXTENSION[ext] ?: SyntaxLang.PLAIN
}

private val SHARED = setOf(
    "as", "break", "case", "catch", "class", "const", "continue", "default", "do", "else", "enum",
    "extends", "false", "finally", "for", "if", "import", "in", "interface", "new", "null", "of",
    "package", "return", "static", "super", "switch", "this", "throw", "true", "try", "var", "while",
)

private val KEYWORDS: Map<SyntaxLang, Set<String>> = mapOf(
    SyntaxLang.C to setOf("auto", "char", "double", "extern", "float", "goto", "inline", "int", "long",
        "register", "short", "signed", "sizeof", "struct", "typedef", "union", "unsigned", "void", "volatile"),
    SyntaxLang.CPP to setOf("auto", "bool", "constexpr", "delete", "explicit", "friend", "namespace",
        "nullptr", "operator", "private", "protected", "public", "reinterpret_cast", "sizeof", "template",
        "typename", "using", "virtual", "void", "override", "noexcept"),
    SyntaxLang.PYTHON to setOf("and", "assert", "async", "await", "def", "del", "elif", "except", "False",
        "from", "global", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "self",
        "True", "with", "yield", "print"),
    SyntaxLang.KOTLIN to setOf("abstract", "annotation", "by", "companion", "constructor", "crossinline",
        "data", "delegate", "dynamic", "final", "fun", "get", "infix", "init", "inline", "internal",
        "is", "it", "lateinit", "noinline", "object", "open", "operator", "out", "override", "private",
        "protected", "public", "reified", "sealed", "set", "suspend", "typealias", "val", "when", "where"),
    SyntaxLang.JAVA to setOf("abstract", "assert", "boolean", "byte", "char", "double", "final", "float",
        "implements", "instanceof", "int", "long", "native", "private", "protected", "public", "record",
        "sealed", "short", "synchronized", "throws", "transient", "var", "void", "volatile", "yield"),
    SyntaxLang.JAVASCRIPT to setOf("async", "await", "debugger", "delete", "export", "function", "instanceof",
        "let", "typeof", "undefined", "void", "with", "yield"),
    SyntaxLang.TYPESCRIPT to setOf("abstract", "any", "async", "await", "boolean", "declare", "export",
        "function", "implements", "instanceof", "keyof", "let", "namespace", "never", "number", "readonly",
        "type", "typeof", "undefined", "unknown", "void"),
    SyntaxLang.JSON to setOf("true", "false", "null"),
    SyntaxLang.SHELL to setOf("alias", "cd", "done", "echo", "elif", "esac", "exit", "export", "fi",
        "function", "local", "readonly", "shift", "source", "then", "trap", "unset", "until", "which"),
    SyntaxLang.YAML to setOf("true", "false", "null", "yes", "no", "on", "off"),
    SyntaxLang.TOML to setOf("true", "false"),
    SyntaxLang.SQL to setOf("add", "all", "alter", "and", "any", "as", "asc", "between", "by", "column",
        "constraint", "create", "database", "delete", "desc", "distinct", "drop", "exists", "foreign",
        "from", "full", "group", "having", "index", "inner", "insert", "into", "is", "join", "key",
        "left", "like", "limit", "not", "null", "on", "or", "order", "outer", "primary", "right",
        "select", "set", "table", "top", "union", "unique", "update", "values", "view", "where"),
    SyntaxLang.RUST to setOf("async", "await", "crate", "dyn", "fn", "impl", "let", "loop", "match",
        "mod", "move", "mut", "pub", "ref", "self", "Self", "trait", "type", "unsafe", "use", "where"),
    SyntaxLang.GO to setOf("chan", "defer", "fallthrough", "func", "go", "goto", "map", "range",
        "select", "struct", "type", "nil", "make", "len", "cap", "append", "interface"),
    SyntaxLang.RUBY to setOf("alias", "and", "begin", "def", "defined?", "elsif", "end", "ensure",
        "module", "next", "nil", "not", "or", "redo", "rescue", "retry", "self", "then", "undef",
        "unless", "until", "when", "yield"),
    SyntaxLang.PHP to setOf("abstract", "array", "callable", "clone", "declare", "echo", "elseif",
        "empty", "enddeclare", "endfor", "endforeach", "endif", "endswitch", "endwhile", "enum", "fn",
        "foreach", "function", "global", "goto", "implements", "include", "instanceof", "isset", "list",
        "match", "namespace", "or", "print", "private", "protected", "public", "readonly", "require",
        "trait", "unset", "use", "xor"),
    SyntaxLang.SWIFT to setOf("associatedtype", "deinit", "extension", "fileprivate", "func", "guard",
        "inout", "internal", "let", "nil", "open", "protocol", "repeat", "rethrows", "self", "struct",
        "subscript", "typealias", "var", "where"),
    SyntaxLang.CSHARP to setOf("abstract", "bool", "byte", "decimal", "delegate", "double", "event",
        "explicit", "extern", "fixed", "float", "foreach", "implicit", "int", "internal", "is", "lock",
        "long", "namespace", "object", "operator", "out", "override", "params", "private", "protected",
        "public", "readonly", "ref", "sbyte", "sealed", "short", "sizeof", "stackalloc", "string",
        "struct", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using", "virtual", "void",
        "volatile", "async", "await", "record"),
    SyntaxLang.DART to setOf("abstract", "assert", "async", "await", "covariant", "deferred", "dynamic",
        "export", "extension", "external", "factory", "final", "get", "hide", "implements", "late",
        "library", "mixin", "on", "operator", "part", "required", "rethrow", "set", "show", "typedef",
        "void", "with", "yield"),
    SyntaxLang.LUA to setOf("and", "end", "function", "local", "nil", "not", "or", "repeat", "then", "until"),
)

private val BUILTIN_TYPES = setOf(
    "String", "Int", "Long", "Double", "Float", "Boolean", "Char", "Byte", "Short", "List", "Map",
    "Set", "Array", "Any", "Unit", "Nothing", "Object", "Integer", "Void", "Bool", "File", "Path",
    "Exception", "Override", "Deprecated", "JvmStatic", "JvmOverloads", "Composable", "Test",
)

private fun lineCommentOf(lang: SyntaxLang): String? = when (lang) {
    SyntaxLang.PYTHON, SyntaxLang.SHELL, SyntaxLang.YAML, SyntaxLang.TOML, SyntaxLang.RUBY -> "#"
    SyntaxLang.SQL -> "--"
    SyntaxLang.PLAIN, SyntaxLang.MARKDOWN -> null
    else -> "//"
}

private fun blockCommentOf(lang: SyntaxLang): Pair<String, String>? = when (lang) {
    SyntaxLang.XML -> "<!--" to "-->"
    SyntaxLang.PYTHON, SyntaxLang.SHELL, SyntaxLang.YAML, SyntaxLang.TOML, SyntaxLang.MARKDOWN,
    SyntaxLang.PLAIN, SyntaxLang.SQL -> null
    else -> "/*" to "*/"
}

private fun allowsTick(lang: SyntaxLang) =
    lang == SyntaxLang.JAVASCRIPT || lang == SyntaxLang.TYPESCRIPT || lang == SyntaxLang.KOTLIN

private fun allowsAnnotation(lang: SyntaxLang) =
    lang == SyntaxLang.KOTLIN || lang == SyntaxLang.JAVA || lang == SyntaxLang.SWIFT || lang == SyntaxLang.CSHARP

/** Paint the search hits over an already-highlighted string. */
private fun withMatches(base: AnnotatedString, matches: List<IntRange>, current: Int): AnnotatedString {
    if (matches.isEmpty()) return base
    val builder = AnnotatedString.Builder(base.text)
    base.spanStyles.forEach { builder.addStyle(it.item, it.start, it.end) }
    matches.forEachIndexed { index, range ->
        val end = (range.last + 1).coerceAtMost(base.length)
        if (range.first >= end) return@forEachIndexed
        builder.addStyle(SpanStyle(background = if (index == current) Color(0xFFFFB300) else Color(0xFFFFF1A8)),
            range.first, end)
    }
    return builder.toAnnotatedString()
}

/** Colour one file. Pure and allocation-light: one pass, one AnnotatedString. */
fun highlight(code: String, lang: SyntaxLang, matches: List<IntRange> = emptyList(), current: Int = -1): AnnotatedString {
    if (lang == SyntaxLang.PLAIN || code.length > HIGHLIGHT_LIMIT) return withMatches(AnnotatedString(code), matches, current)
    if (lang == SyntaxLang.MARKDOWN) return withMatches(highlightMarkdown(code), matches, current)
    val builder = AnnotatedString.Builder(code)
    val keywords = SHARED + KEYWORDS[lang].orEmpty()
    val lineComment = lineCommentOf(lang)
    val block = blockCommentOf(lang)
    val length = code.length
    var i = 0
    while (i < length) {
        val c = code[i]
        if (block != null && code.startsWith(block.first, i)) {
            val close = code.indexOf(block.second, i + block.first.length)
            val end = if (close < 0) length else close + block.second.length
            builder.addStyle(COMMENT_STYLE, i, end)
            i = end
            continue
        }
        if (lineComment != null && code.startsWith(lineComment, i)) {
            val close = code.indexOf('\n', i)
            val end = if (close < 0) length else close
            builder.addStyle(COMMENT_STYLE, i, end)
            i = end
            continue
        }
        if (lang == SyntaxLang.XML && c == '<') {
            val close = code.indexOf('>', i)
            val end = if (close < 0) length else close + 1
            builder.addStyle(TYPE_STYLE, i, end)
            i = end
            continue
        }
        if (c == '@' && allowsAnnotation(lang)) {
            var j = i + 1
            while (j < length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
            builder.addStyle(ANNOTATION_STYLE, i, j)
            i = j
            continue
        }
        if (c == '"' || c == '\'' || (c == TICK && allowsTick(lang))) {
            val triple = TICK.toString() + TICK + TICK
            if ((c == '"' || c == TICK) && code.startsWith(triple, i)) {
                val close = code.indexOf(triple, i + 3)
                val end = if (close < 0) length else close + 3
                builder.addStyle(STRING_STYLE, i, end)
                i = end
                continue
            }
            var j = i + 1
            while (j < length) {
                val d = code[j]
                if (d == '\\') { j += 2; continue }
                if (d == c) { j++; break }
                if (d == '\n' && c != TICK) break
                j++
            }
            val end = minOf(j, length)
            var style = STRING_STYLE
            if (lang == SyntaxLang.JSON) {
                var k = end
                while (k < length && code[k] == ' ') k++
                if (k < length && code[k] == ':') style = TYPE_STYLE
            }
            builder.addStyle(style, i, end)
            i = end
            continue
        }
        if (c.isDigit()) {
            var j = i
            while (j < length && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
            builder.addStyle(NUMBER_STYLE, i, j)
            i = j
            continue
        }
        if (c.isLetter() || c == '_' || c == '$') {
            var j = i
            while (j < length && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '$')) j++
            val word = code.substring(i, j)
            when {
                word in keywords -> builder.addStyle(KEYWORD_STYLE, i, j)
                word in BUILTIN_TYPES || (word.isNotEmpty() && word[0].isUpperCase()) -> builder.addStyle(TYPE_STYLE, i, j)
            }
            i = j
            continue
        }
        i++
    }
    return withMatches(builder.toAnnotatedString(), matches, current)
}

private fun highlightMarkdown(code: String): AnnotatedString {
    val builder = AnnotatedString.Builder(code)
    val fence = TICK.toString() + TICK + TICK
    var inside = false
    var i = 0
    while (i < code.length) {
        val close = code.indexOf('\n', i)
        val lineEnd = if (close < 0) code.length else close
        val line = code.substring(i, lineEnd)
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith(fence) -> { builder.addStyle(COMMENT_STYLE, i, lineEnd); inside = !inside }
            inside -> builder.addStyle(STRING_STYLE, i, lineEnd)
            trimmed.startsWith("#") -> builder.addStyle(HEADING_STYLE, i, lineEnd)
            else -> {
                var k = i
                while (k < lineEnd) {
                    val open = code.indexOf(TICK, k)
                    if (open < 0 || open >= lineEnd) break
                    val shut = code.indexOf(TICK, open + 1)
                    if (shut < 0 || shut > lineEnd) break
                    builder.addStyle(STRING_STYLE, open, shut + 1)
                    k = shut + 1
                }
                var b = i
                while (b < lineEnd) {
                    val open = code.indexOf("**", b)
                    if (open < 0 || open >= lineEnd) break
                    val shut = code.indexOf("**", open + 2)
                    if (shut < 0 || shut > lineEnd) break
                    builder.addStyle(TYPE_STYLE, open, shut + 2)
                    b = shut + 2
                }
            }
        }
        i = lineEnd + 1
    }
    return builder.toAnnotatedString()
}

/** All occurrences of a query, capped so a huge file cannot lock up the UI. */
fun findMatches(text: String, query: String, limit: Int = 2000): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val result = ArrayList<IntRange>()
    var index = text.indexOf(query, 0, ignoreCase = true)
    while (index >= 0 && result.size < limit) {
        result.add(index until index + query.length)
        index = text.indexOf(query, index + query.length, ignoreCase = true)
    }
    return result
}
