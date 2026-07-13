package com.springcat.ide.core.lang

/**
 * A programming (or markup) language the editor understands well enough to
 * offer file-type detection and syntax highlighting for.
 *
 * The list is deliberately broad — SpringCat is a general-purpose scratchpad
 * for "沢山のプログラミング言語" (many programming languages).
 */
enum class Language(
    val displayName: String,
    val extensions: List<String>,
    val lineComment: String?,
    val blockComment: Pair<String, String>?,
    val keywords: Set<String>,
) {
    KOTLIN(
        "Kotlin", listOf("kt", "kts"), "//", "/*" to "*/",
        setOf(
            "fun", "val", "var", "class", "object", "interface", "data", "sealed",
            "when", "if", "else", "for", "while", "return", "import", "package",
            "private", "public", "internal", "protected", "override", "suspend",
            "companion", "init", "in", "is", "as", "null", "true", "false", "this",
        ),
    ),
    JAVA(
        "Java", listOf("java"), "//", "/*" to "*/",
        setOf(
            "public", "private", "protected", "class", "interface", "enum", "void",
            "static", "final", "abstract", "new", "return", "if", "else", "for",
            "while", "switch", "case", "break", "continue", "import", "package",
            "extends", "implements", "this", "super", "null", "true", "false",
            "int", "long", "double", "float", "boolean", "char", "byte", "throws",
        ),
    ),
    PYTHON(
        "Python", listOf("py", "pyw"), "#", null,
        setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "import",
            "from", "as", "with", "try", "except", "finally", "raise", "lambda",
            "yield", "async", "await", "None", "True", "False", "and", "or", "not",
            "in", "is", "pass", "break", "continue", "global", "nonlocal", "self",
        ),
    ),
    JAVASCRIPT(
        "JavaScript", listOf("js", "mjs", "cjs"), "//", "/*" to "*/",
        setOf(
            "function", "const", "let", "var", "return", "if", "else", "for",
            "while", "switch", "case", "break", "continue", "import", "export",
            "from", "default", "class", "extends", "new", "this", "async", "await",
            "null", "undefined", "true", "false", "typeof", "instanceof",
        ),
    ),
    TYPESCRIPT(
        "TypeScript", listOf("ts", "tsx"), "//", "/*" to "*/",
        setOf(
            "function", "const", "let", "var", "return", "if", "else", "for",
            "interface", "type", "enum", "class", "extends", "implements", "public",
            "private", "protected", "readonly", "import", "export", "from", "async",
            "await", "null", "undefined", "true", "false", "as", "namespace",
        ),
    ),
    C(
        "C", listOf("c", "h"), "//", "/*" to "*/",
        setOf(
            "int", "char", "float", "double", "void", "long", "short", "unsigned",
            "signed", "struct", "union", "enum", "typedef", "const", "static",
            "return", "if", "else", "for", "while", "switch", "case", "break",
            "continue", "sizeof", "include", "define", "NULL",
        ),
    ),
    CPP(
        "C++", listOf("cpp", "cc", "cxx", "hpp", "hh"), "//", "/*" to "*/",
        setOf(
            "int", "char", "float", "double", "void", "bool", "class", "struct",
            "namespace", "template", "typename", "public", "private", "protected",
            "virtual", "override", "const", "static", "return", "if", "else", "for",
            "while", "switch", "case", "new", "delete", "nullptr", "true", "false",
            "using", "auto", "constexpr",
        ),
    ),
    CSHARP(
        "C#", listOf("cs"), "//", "/*" to "*/",
        setOf(
            "using", "namespace", "class", "struct", "interface", "enum", "public",
            "private", "protected", "internal", "static", "readonly", "void", "int",
            "string", "bool", "var", "new", "return", "if", "else", "for", "foreach",
            "while", "switch", "case", "this", "null", "true", "false", "async", "await",
        ),
    ),
    GO(
        "Go", listOf("go"), "//", "/*" to "*/",
        setOf(
            "func", "package", "import", "var", "const", "type", "struct", "interface",
            "map", "chan", "go", "defer", "return", "if", "else", "for", "range",
            "switch", "case", "select", "break", "continue", "nil", "true", "false",
        ),
    ),
    RUST(
        "Rust", listOf("rs"), "//", "/*" to "*/",
        setOf(
            "fn", "let", "mut", "const", "struct", "enum", "trait", "impl", "pub",
            "use", "mod", "match", "if", "else", "for", "while", "loop", "return",
            "self", "Self", "None", "Some", "Ok", "Err", "true", "false", "async", "await",
        ),
    ),
    SWIFT(
        "Swift", listOf("swift"), "//", "/*" to "*/",
        setOf(
            "func", "let", "var", "class", "struct", "enum", "protocol", "extension",
            "import", "return", "if", "else", "for", "while", "switch", "case",
            "guard", "self", "nil", "true", "false", "public", "private", "static",
        ),
    ),
    RUBY(
        "Ruby", listOf("rb"), "#", "=begin" to "=end",
        setOf(
            "def", "end", "class", "module", "if", "elsif", "else", "unless", "while",
            "until", "for", "do", "return", "yield", "require", "attr_accessor",
            "self", "nil", "true", "false", "and", "or", "not", "then", "begin", "rescue",
        ),
    ),
    PHP(
        "PHP", listOf("php"), "//", "/*" to "*/",
        setOf(
            "function", "class", "public", "private", "protected", "static", "return",
            "if", "else", "elseif", "foreach", "for", "while", "switch", "case",
            "echo", "new", "use", "namespace", "null", "true", "false", "this",
        ),
    ),
    KOTLIN_SCRIPT_GRADLE(
        "Gradle (Kotlin)", listOf("gradle.kts"), "//", "/*" to "*/",
        setOf("plugins", "dependencies", "implementation", "android", "buildTypes"),
    ),
    HTML(
        "HTML", listOf("html", "htm"), null, "<!--" to "-->",
        setOf("html", "head", "body", "div", "span", "script", "style", "link", "meta"),
    ),
    CSS(
        "CSS", listOf("css", "scss", "less"), null, "/*" to "*/",
        setOf("color", "background", "margin", "padding", "display", "flex", "grid"),
    ),
    XML(
        "XML", listOf("xml", "xsd", "svg"), null, "<!--" to "-->",
        emptySet(),
    ),
    JSON(
        "JSON", listOf("json"), null, null,
        setOf("true", "false", "null"),
    ),
    YAML(
        "YAML", listOf("yml", "yaml"), "#", null,
        setOf("true", "false", "null"),
    ),
    MARKDOWN(
        "Markdown", listOf("md", "markdown"), null, null,
        emptySet(),
    ),
    SHELL(
        "Shell", listOf("sh", "bash", "zsh"), "#", null,
        setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case",
            "esac", "function", "return", "echo", "export", "local", "in",
        ),
    ),
    SQL(
        "SQL", listOf("sql"), "--", "/*" to "*/",
        setOf(
            "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE",
            "ALTER", "DROP", "JOIN", "INNER", "LEFT", "RIGHT", "ON", "GROUP", "ORDER",
            "BY", "HAVING", "LIMIT", "VALUES", "INTO", "AND", "OR", "NOT", "NULL",
        ),
    ),
    DART(
        "Dart", listOf("dart"), "//", "/*" to "*/",
        setOf(
            "void", "var", "final", "const", "class", "extends", "implements", "with",
            "import", "return", "if", "else", "for", "while", "switch", "case",
            "async", "await", "this", "null", "true", "false", "new",
        ),
    ),
    PLAIN_TEXT(
        "Plain Text", listOf("txt", "log", "text"), null, null, emptySet(),
    ),
    ;

    companion object {
        /** Resolve a language from a file name, falling back to plain text. */
        fun fromFileName(name: String): Language {
            val lower = name.lowercase()
            // Prefer the longest matching extension (e.g. ".gradle.kts" over ".kts").
            return entries
                .flatMap { lang -> lang.extensions.map { ext -> ext to lang } }
                .filter { (ext, _) -> lower.endsWith(".$ext") }
                .maxByOrNull { (ext, _) -> ext.length }
                ?.second
                ?: PLAIN_TEXT
        }

        /** Count of distinct languages offered, for display in the UI. */
        val supportedCount: Int get() = entries.size - 1 // exclude PLAIN_TEXT
    }
}
