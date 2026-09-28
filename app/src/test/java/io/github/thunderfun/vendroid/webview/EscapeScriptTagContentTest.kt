package io.github.thunderfun.vendroid.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract tests for escapeScriptTagContent; that KDoc explains why both
 * tokens must be escaped (script-data double-escape rules).
 */
class EscapeScriptTagContentTest {

    @Test
    fun `closing tag sequence is neutralized case-insensitively`() {
        assertEquals("<script>x<\\/script>y", escapeScriptTagContent("<script>x</script>y"))
        assertEquals("<script>x<\\/script>y", escapeScriptTagContent("<script>x</SCRIPT>y"))
        assertEquals("<script>x<\\/script>y", escapeScriptTagContent("<script>x</Script>y"))
        // Escaped form must not itself reintroduce the token on a second pass.
        val once = escapeScriptTagContent("a</script>b")
        assertEquals(once, escapeScriptTagContent(once))
    }

    @Test
    fun `comment opener is neutralized`() {
        assertEquals("var s = \"<\\!-- html\";", escapeScriptTagContent("var s = \"<!-- html\";"))
        assertEquals("/*<\\!--*/1", escapeScriptTagContent("/*<!--*/1"))
    }

    @Test
    fun `comment opener and closing tag are independent of order`() {
        assertEquals("<\\!--<\\/script", escapeScriptTagContent("<!--</script"))
        assertEquals("<\\/script<\\!--", escapeScriptTagContent("</script<!--"))
        assertEquals("a<\\!--b<\\/scriptc", escapeScriptTagContent("a<!--b</scriptc"))
    }

    @Test
    fun `escaped output contains neither raw hazard token`() {
        // Latent-trip shape: unbalanced <!-- then <script, plus both tokens
        // at the start, end, and adjacent to each other.
        val adversarial = "<!-- unbalanced\n<script>var a = \"</script\";\n" +
            "</script><!-- x --><!--</script</script><!--\n<script>"
        val escaped = escapeScriptTagContent(adversarial)
        assertFalse("raw </script survived escaping", escaped.contains("</script", ignoreCase = true))
        assertFalse("raw <!-- survived escaping", escaped.contains("<!--"))
    }

    @Test
    fun `inert sequences pass through untouched`() {
        // <script and --> are inert once <!-- and </script cannot occur;
        // rewriting them would only corrupt regex literals like /<script/.
        val inert = "var t = \"<script>\"; var a = 2 --> 1; var r = /<script/;"
        assertSame("clean payload must skip the copy", inert, escapeScriptTagContent(inert))
        assertSame("", escapeScriptTagContent(""))
    }

    @Test
    fun `tokens at payload boundaries are handled`() {
        assertEquals("<\\/script", escapeScriptTagContent("</script"))
        assertEquals("<\\!--", escapeScriptTagContent("<!--"))
        assertEquals("x<\\/script", escapeScriptTagContent("x</script"))
        assertEquals("x<\\!--", escapeScriptTagContent("x<!--"))
        assertEquals("x<\\/script<\\!--", escapeScriptTagContent("x</script<!--"))
    }

    @Test
    fun `vendored eqjs snapshot embeds with no raw hazard tokens`() {
        val snapshot = File("../eq.js")
        if (!snapshot.exists()) return // snapshot not vendored in this checkout
        val escaped = escapeScriptTagContent(snapshot.readText())
        assertFalse("raw </script survived escaping", escaped.contains("</script", ignoreCase = true))
        assertFalse("raw <!-- survived escaping", escaped.contains("<!--"))
        // Upstream's own pre-escaped QuickCSS closers and the neutralized SVG
        // comment openers must survive intact.
        assertTrue("upstream <\\/script closers were corrupted", escaped.contains("<\\/script"))
        assertTrue("comment openers were not neutralized", escaped.contains("<\\!--"))
    }
}
