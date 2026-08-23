package com.apkbuilder.core

import kotlin.test.Test
import kotlin.test.assertTrue

class HtmlSyntaxCheckTest {

    @Test
    fun cleanHtmlHasNoIssues() {
        val html = """
            <!DOCTYPE html><html><head><script>
              function f(a) { return [a, a*2]; }
              var s = "a string with } and ) inside";
              // a comment with { unbalanced
              console.log(f(3));
            </script></head><body>ok</body></html>
        """.trimIndent()
        assertTrue(HtmlSyntaxCheck.check(html).isEmpty(), "unexpected issues")
    }

    @Test
    fun detectsUnbalancedBrace() {
        val html = "<html><script>function f(){ if(true){ }</script></html>"
        val issues = HtmlSyntaxCheck.check(html)
        assertTrue(issues.any { it.message.contains("閉じ括弧") })
    }

    @Test
    fun detectsUnterminatedString() {
        val html = "<html><script>var s = 'oops;</script></html>"
        assertTrue(HtmlSyntaxCheck.check(html).any { it.message.contains("文字列") })
    }

    @Test
    fun detectsMismatchedScriptTags() {
        val html = "<html><script>var x=1;</html>"
        assertTrue(HtmlSyntaxCheck.check(html).any { it.message.contains("script") })
    }
}
