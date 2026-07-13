package com.springcat.ide.core.lang

import org.json.JSONArray
import org.json.JSONObject

/** A single problem found in source, 1-based line for display. */
data class Problem(val line: Int, val column: Int, val message: String)

/**
 * Lightweight, dependency-free static checks that run on-device as you type.
 *
 * This is deliberately conservative — it only reports problems it is confident
 * about, so it never cries wolf:
 *  - unbalanced / mismatched brackets and unterminated strings (C-like langs)
 *  - malformed JSON (via the platform JSON parser)
 *
 * Deeper, language-accurate checking for JS/JSX happens at run time through the
 * bundled Babel compiler in the preview screen.
 */
object SyntaxChecker {

    fun check(source: String, language: Language): List<Problem> = when (language) {
        Language.JSON -> checkJson(source)
        Language.KOTLIN, Language.JAVA, Language.JAVASCRIPT, Language.TYPESCRIPT,
        Language.C, Language.CPP, Language.CSHARP, Language.GO, Language.RUST,
        Language.SWIFT, Language.DART, Language.PHP, Language.CSS,
        -> checkBrackets(source, language)
        else -> emptyList()
    }

    private fun checkJson(source: String): List<Problem> {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            when (trimmed.first()) {
                '[' -> JSONArray(trimmed)
                else -> JSONObject(trimmed)
            }
            emptyList()
        } catch (e: Exception) {
            listOf(Problem(1, 1, "Invalid JSON: ${e.message ?: "parse error"}"))
        }
    }

    private fun checkBrackets(source: String, language: Language): List<Problem> {
        val problems = ArrayList<Problem>()
        val stack = ArrayDeque<Triple<Char, Int, Int>>() // bracket, line, col
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        val openers = setOf('(', '[', '{')

        var line = 1
        var col = 0
        var i = 0
        val n = source.length
        val lineComment = language.lineComment
        val block = language.blockComment

        while (i < n) {
            val c = source[i]
            if (c == '\n') { line++; col = 0; i++; continue }
            col++

            // Skip line comments
            if (lineComment != null && source.startsWith(lineComment, i)) {
                val nl = source.indexOf('\n', i)
                if (nl < 0) break
                i = nl; continue
            }
            // Skip block comments
            if (block != null && source.startsWith(block.first, i)) {
                val end = source.indexOf(block.second, i + block.first.length)
                if (end < 0) {
                    problems += Problem(line, col, "Unterminated block comment")
                    break
                }
                // advance line counter across the comment
                for (k in i until end + block.second.length) if (source[k] == '\n') line++
                i = end + block.second.length; continue
            }
            // Skip strings
            if (c == '"' || c == '\'' || c == '`') {
                val startLine = line
                val startCol = col
                var j = i + 1
                var closed = false
                while (j < n) {
                    when (source[j]) {
                        '\\' -> j += 2
                        '\n' -> if (c != '`') break else { line++; j++ }
                        c -> { closed = true; j++; break }
                        else -> j++
                    }
                }
                if (!closed) problems += Problem(startLine, startCol, "Unterminated string literal")
                i = j; continue
            }

            when (c) {
                in openers -> stack.addLast(Triple(c, line, col))
                ')', ']', '}' -> {
                    val top = stack.removeLastOrNull()
                    if (top == null) {
                        problems += Problem(line, col, "Unexpected closing '$c'")
                    } else if (top.first != pairs[c]) {
                        problems += Problem(line, col, "Mismatched '$c' — expected to close '${top.first}' from line ${top.second}")
                    }
                }
            }
            i++
        }
        for ((br, l, cc) in stack) {
            problems += Problem(l, cc, "Unclosed '$br'")
        }
        return problems.sortedWith(compareBy({ it.line }, { it.column })).take(50)
    }
}
