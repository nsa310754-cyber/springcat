package com.springcat.ide.core.lang

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Colour palette used by the highlighter — tuned for a dark editor surface. */
data class HighlightTheme(
    val keyword: Color = Color(0xFF7CF3B0),
    val string: Color = Color(0xFFF7C948),
    val number: Color = Color(0xFFE39FF6),
    val comment: Color = Color(0xFF6B7C74),
    val plain: Color = Color(0xFFE6EDEA),
)

/**
 * A small, allocation-light tokenizer that highlights source code for display.
 *
 * It is intentionally lexical (not a full parser): it recognises line/block
 * comments, string literals, numbers and language keywords. That is enough to
 * make code readable across every [Language] without per-language grammars.
 */
class SyntaxHighlighter(private val theme: HighlightTheme = HighlightTheme()) {

    fun highlight(source: String, language: Language): AnnotatedString = buildAnnotatedString {
        val tokens = tokenize(source, language)
        var cursor = 0
        for (token in tokens) {
            if (token.start > cursor) {
                withStyle(SpanStyle(color = theme.plain)) {
                    append(source.substring(cursor, token.start))
                }
            }
            withStyle(token.style) { append(source.substring(token.start, token.end)) }
            cursor = token.end
        }
        if (cursor < source.length) {
            withStyle(SpanStyle(color = theme.plain)) { append(source.substring(cursor)) }
        }
    }

    private data class Token(val start: Int, val end: Int, val style: SpanStyle)

    private fun tokenize(src: String, lang: Language): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        val n = src.length
        val blockOpen = lang.blockComment?.first
        val blockClose = lang.blockComment?.second

        while (i < n) {
            val c = src[i]

            // Block comment
            if (blockOpen != null && blockClose != null && src.startsWith(blockOpen, i)) {
                val end = src.indexOf(blockClose, i + blockOpen.length)
                    .let { if (it < 0) n else it + blockClose.length }
                tokens += Token(i, end, SpanStyle(color = theme.comment))
                i = end
                continue
            }

            // Line comment
            if (lang.lineComment != null && src.startsWith(lang.lineComment, i)) {
                val end = src.indexOf('\n', i).let { if (it < 0) n else it }
                tokens += Token(i, end, SpanStyle(color = theme.comment))
                i = end
                continue
            }

            // String literals (single, double, backtick)
            if (c == '"' || c == '\'' || c == '`') {
                val end = consumeString(src, i, c)
                tokens += Token(i, end, SpanStyle(color = theme.string))
                i = end
                continue
            }

            // Numbers
            if (c.isDigit()) {
                var j = i + 1
                while (j < n && (src[j].isLetterOrDigit() || src[j] == '.' || src[j] == '_')) j++
                tokens += Token(i, j, SpanStyle(color = theme.number))
                i = j
                continue
            }

            // Identifiers / keywords
            if (c.isLetter() || c == '_') {
                var j = i + 1
                while (j < n && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                val word = src.substring(i, j)
                if (word in lang.keywords) {
                    tokens += Token(i, j, SpanStyle(color = theme.keyword, fontWeight = FontWeight.SemiBold))
                }
                i = j
                continue
            }

            i++
        }
        return tokens
    }

    private fun consumeString(src: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < src.length) {
            when (src[i]) {
                '\\' -> i += 2 // skip escaped char
                quote -> return i + 1
                else -> i++
            }
        }
        return src.length
    }
}
