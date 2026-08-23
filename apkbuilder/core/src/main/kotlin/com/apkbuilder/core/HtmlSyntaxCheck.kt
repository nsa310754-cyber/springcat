package com.apkbuilder.core

/**
 * A lightweight, dependency-free static check for the game HTML — enough to
 * catch the most common "won't run" mistakes before building (unbalanced
 * brackets in a <script>, an unterminated string, an unclosed <script> tag).
 * It is deliberately conservative: real runtime/syntax validation happens when
 * the preview WebView actually loads the page (window.onerror). Kept in core so
 * it is unit-testable on a plain JVM.
 */
data class SyntaxIssue(val message: String)

object HtmlSyntaxCheck {

    fun check(html: String): List<SyntaxIssue> {
        val issues = mutableListOf<SyntaxIssue>()

        // <script> ... </script> extraction (non-src blocks only).
        val scriptRegex = Regex("<script(?![^>]*\\bsrc\\b)[^>]*>(.*?)</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val openScripts = Regex("<script\\b", RegexOption.IGNORE_CASE).findAll(html).count()
        val closeScripts = Regex("</script>", RegexOption.IGNORE_CASE).findAll(html).count()
        if (openScripts != closeScripts) {
            issues.add(SyntaxIssue("<script> と </script> の数が一致しません ($openScripts 対 $closeScripts)"))
        }

        var index = 0
        for (match in scriptRegex.findAll(html)) {
            index++
            checkJs(match.groupValues[1], index, issues)
        }
        return issues
    }

    private fun checkJs(js: String, scriptIndex: Int, issues: MutableList<SyntaxIssue>) {
        var i = 0
        val stack = ArrayDeque<Char>()
        var line = 1
        val n = js.length
        while (i < n) {
            val c = js[i]
            when {
                c == '\n' -> line++
                c == '/' && i + 1 < n && js[i + 1] == '/' -> {
                    while (i < n && js[i] != '\n') i++
                    continue
                }
                c == '/' && i + 1 < n && js[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(js[i] == '*' && js[i + 1] == '/')) {
                        if (js[i] == '\n') line++
                        i++
                    }
                    i += 2
                    continue
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val quote = c
                    i++
                    while (i < n && js[i] != quote) {
                        if (js[i] == '\\') i++
                        if (i < n && js[i] == '\n') line++
                        i++
                    }
                    if (i >= n) {
                        issues.add(SyntaxIssue("script #$scriptIndex: 閉じられていない文字列があります (行 $line 付近)"))
                        return
                    }
                }
                c == '{' || c == '(' || c == '[' -> stack.addLast(c)
                c == '}' || c == ')' || c == ']' -> {
                    val expected = when (c) {
                        '}' -> '{'; ')' -> '('; else -> '['
                    }
                    if (stack.isEmpty() || stack.removeLast() != expected) {
                        issues.add(SyntaxIssue("script #$scriptIndex: 対応しない '$c' があります (行 $line 付近)"))
                        return
                    }
                }
            }
            i++
        }
        if (stack.isNotEmpty()) {
            issues.add(SyntaxIssue("script #$scriptIndex: 閉じ括弧が不足しています ('${stack.last()}' が未クローズ)"))
        }
    }
}
