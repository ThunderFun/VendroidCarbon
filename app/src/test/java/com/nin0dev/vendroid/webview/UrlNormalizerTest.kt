package com.nin0dev.vendroid.webview

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [UrlNormalizer]. Pure-string helpers are JVM-only; the
 * [normalize] entry point touches [Uri], so the whole class runs under
 * Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UrlNormalizerTest {

    // ------------------------------------------------------------------
    //  percentDecode
    // ------------------------------------------------------------------

    @Test fun percentDecode_cjkThreeBytes() {
        assertEquals("中文", UrlNormalizer.percentDecode("%E4%B8%AD%E6%96%87"))
    }

    @Test fun percentDecode_lowerAndUpperHex() {
        assertEquals("中文", UrlNormalizer.percentDecode("%e4%b8%ad%E6%96%87"))
    }

    @Test fun percentDecode_passthroughAscii() {
        assertEquals("/path/to/file.html", UrlNormalizer.percentDecode("/path/to/file.html"))
    }

    @Test fun percentDecode_malformedTruncatedLeftAsLiteral() {
        // Truncated UTF-8 sequence at end; the %E4 is kept literal.
        assertEquals("a%E4", UrlNormalizer.percentDecode("a%E4"))
    }

    @Test fun percentDecode_nonHexLeftAsLiteral() {
        assertEquals("a%ZZ", UrlNormalizer.percentDecode("a%ZZ"))
    }

    @Test fun percentDecode_plusIsNotSpace() {
        assertEquals("a+b", UrlNormalizer.percentDecode("a+b"))
    }

    // ------------------------------------------------------------------
    //  percentEncode
    // ------------------------------------------------------------------

    @Test fun percentEncode_cjkToUtf8Bytes() {
        assertEquals("%E4%B8%AD%E6%96%87", UrlNormalizer.percentEncode("中文", safe = ""))
    }

    @Test fun percentEncode_doesNotDoubleEncode() {
        assertEquals("%E4%B8%AD", UrlNormalizer.percentEncode("%E4%B8%AD", safe = ""))
    }

    @Test fun percentEncode_safeCharsPassThrough() {
        // Default safe set includes letters and '/', so a plain path survives.
        assertEquals("/path", UrlNormalizer.percentEncode("/path"))
    }

    @Test fun percentEncode_spaceBecomesPercent20() {
        // Letters are in the default safe set; only the space is encoded.
        assertEquals("a%20b", UrlNormalizer.percentEncode("a b"))
    }

    // ------------------------------------------------------------------
    //  stripUserinfo
    // ------------------------------------------------------------------

    @Test fun stripUserinfo_basic() {
        assertEquals("https://host.com/path", UrlNormalizer.stripUserinfo("https://user:pass@host.com/path"))
    }

    @Test fun stripUserinfo_userOnly() {
        assertEquals("https://host.com/", UrlNormalizer.stripUserinfo("https://user@host.com/"))
    }

    @Test fun stripUserinfo_noUserinfo() {
        assertEquals("https://host.com/path", UrlNormalizer.stripUserinfo("https://host.com/path"))
    }

    @Test fun stripUserinfo_noScheme() {
        assertEquals("foo", UrlNormalizer.stripUserinfo("foo"))
    }

    @Test fun stripUserinfo_atInPathNotTouched() {
        assertEquals("https://host.com/@path", UrlNormalizer.stripUserinfo("https://host.com/@path"))
    }

    // ------------------------------------------------------------------
    //  stripInvisible
    // ------------------------------------------------------------------

    @Test fun stripInvisible_removesBidiOverride() {
        val input = "abc\u202Edef"
        assertEquals("abcdef", UrlNormalizer.stripInvisible(input))
    }

    @Test fun stripInvisible_removesZeroWidthSpace() {
        // Zero-width space leaves no visible gap.
        assertEquals("discord", UrlNormalizer.stripInvisible("dis\u200Bcord"))
    }

    @Test fun stripInvisible_removesC0Controls() {
        assertEquals("ab", UrlNormalizer.stripInvisible("a\u0000b"))
        assertEquals("abb", UrlNormalizer.stripInvisible("a\rb\nb"))
    }

    @Test fun stripInvisible_keepsNormalText() {
        assertEquals("中文 path", UrlNormalizer.stripInvisible("中文 path"))
    }

    @Test fun stripInvisible_removesBom() {
        assertEquals("abc", UrlNormalizer.stripInvisible("\uFEFFabc"))
    }

    // ------------------------------------------------------------------
    //  containsMixedScript
    // ------------------------------------------------------------------

    @Test fun mixedScript_latinAndCyrillic() {
        // 'p' + Cyrillic 'а' + 'y', a homograph-style mix.
        assertTrue(UrlNormalizer.containsMixedScript("pаypal"))
    }

    @Test fun mixedScript_latinAndGreek() {
        assertTrue(UrlNormalizer.containsMixedScript("paypаl"))
    }

    @Test fun mixedScript_pureCyrillicNotFlagged() {
        assertFalse(UrlNormalizer.containsMixedScript("пример"))
    }

    @Test fun mixedScript_pureCjkNotFlagged() {
        assertFalse(UrlNormalizer.containsMixedScript("中文"))
    }

    @Test fun mixedScript_latinAndCjkNotFlagged() {
        // Latin + CJK is fine. CJK has no Latin confusables.
        assertFalse(UrlNormalizer.containsMixedScript("hello中文"))
    }

    @Test fun mixedScript_pureAsciiNotFlagged() {
        assertFalse(UrlNormalizer.containsMixedScript("paypal.com"))
    }

    @Test fun mixedScript_emptyNotFlagged() {
        assertFalse(UrlNormalizer.containsMixedScript(""))
    }

    // ------------------------------------------------------------------
    //  toAsciiHost
    // ------------------------------------------------------------------

    @Test fun toAsciiHost_pureAsciiUnchanged() {
        assertEquals("example.com", UrlNormalizer.toAsciiHost("example.com"))
    }

    @Test fun toAsciiHost_cjkIdn() {
        // 例子.com -> xn--fsq.com (well-known IDN test domain)
        val result = UrlNormalizer.toAsciiHost("例子.com")
        assertNotNull(result)
        assertTrue(result!!.startsWith("xn--"))
    }

    @Test fun toAsciiHost_nullInNullOut() {
        assertNull(UrlNormalizer.toAsciiHost(null))
    }

    @Test fun toAsciiHost_emptyInNullOut() {
        assertNull(UrlNormalizer.toAsciiHost(""))
    }

    @Test fun toAsciiHost_invalidIdnFailsClosedToNull() {
        // An IDNA-invalid non-ASCII host (e.g. one containing an emoji, which
        // IDN.toASCII rejects) must fail CLOSED to null rather than returning
        // the raw Unicode host, which would defeat the Punycode homograph
        // display defense.
        assertNull(UrlNormalizer.toAsciiHost("\uD83D\uDE00.com")) // 😀.com
    }

    // ------------------------------------------------------------------
    //  redactForLog
    // ------------------------------------------------------------------

    @Test fun redactForLog_stripsUserinfo() {
        val r = UrlNormalizer.redactForLog("https://user:pass@host.com/path")
        assertFalse(r.contains("user:pass"))
        assertTrue(r.contains("host.com/path"))
    }

    @Test fun redactForLog_masksTokenParam() {
        val r = UrlNormalizer.redactForLog("https://host.com/path?token=secret&keep=ok")
        assertTrue(r.contains("token=***"))
        assertTrue(r.contains("keep=ok"))
    }

    @Test fun redactForLog_noQueryUnchanged() {
        assertEquals("https://host.com/path", UrlNormalizer.redactForLog("https://host.com/path"))
    }

    @Test fun redactForLog_emptyIn() {
        assertEquals("", UrlNormalizer.redactForLog(""))
    }

    // ------------------------------------------------------------------
    //  normalize (end-to-end via Uri)
    // ------------------------------------------------------------------

    @Test fun normalize_encodedCjkPath_displayShowsChinese_launchStaysEncoded() {
        val uri = Uri.parse("https://example.com/search?q=%E4%B8%AD%E6%96%87")
        val n = UrlNormalizer.normalize(uri)
        assertFalse(n.malformed)
        // Display decodes the query so the user can read it.
        assertTrue(n.displayString.contains("中文"))
        // Launch form must stay percent-encoded for ACTION_VIEW.
        assertTrue(n.clipboardString.contains("%E4%B8%AD"))
    }

    @Test fun normalize_rawCjkPath_launchEncodesIt() {
        // Some WebView paths deliver raw (unencoded) CJK.
        val uri = Uri.parse("https://example.com/搜索")
        val n = UrlNormalizer.normalize(uri)
        assertFalse(n.malformed)
        assertTrue(n.clipboardString.contains("%E6%90%9C%E7%B4%A2"))
    }

    @Test fun normalize_cjkIdnHost_asciiHostIsPunycode_displayHostIsAscii() {
        val uri = Uri.parse("https://例子.com/path")
        val n = UrlNormalizer.normalize(uri)
        assertFalse(n.malformed)
        assertNotNull(n.asciiHost)
        assertTrue(n.asciiHost!!.startsWith("xn--"))
        // Display host is ASCII, never the Unicode form.
        assertFalse(n.displayString.contains("例子"))
        assertTrue(n.displayString.contains("xn--"))
    }

    @Test fun normalize_userinfoStrippedFromAllOutputs() {
        val uri = Uri.parse("https://user:pass@host.com/path")
        val n = UrlNormalizer.normalize(uri)
        assertFalse(n.malformed)
        assertFalse(n.displayString.contains("user:pass"))
        assertFalse(n.clipboardString.contains("user:pass"))
    }

    @Test fun normalize_pureAsciiUrl_idempotent() {
        val uri = Uri.parse("https://example.com/path?x=1#frag")
        val n = UrlNormalizer.normalize(uri)
        assertFalse(n.malformed)
        assertEquals("https://example.com/path?x=1#frag", n.clipboardString)
        assertEquals("https://example.com/path?x=1#frag", n.displayString)
    }

    @Test fun normalize_mixedScriptPathKeepsEncodedInDisplay() {
        // Cyrillic 'а' (U+0430) hiding among Latin letters in the path.
        // Raw (unencoded) form: the mixed-script detector must force the
        // encoded form into the display.
        val uri = Uri.parse("https://host.com/p%D0%B0yment")
        val n = UrlNormalizer.normalize(uri)
        assertFalse(n.malformed)
        // Display should NOT show the decoded Cyrillic 'а' (which would look
        // like a Latin 'a' and fool the user).
        assertFalse(n.displayString.contains("а"))
    }

    @Test fun normalize_invisibleCharsStrippedFromDisplay() {
        // U+202E bidi override percent-encoded: %E2%80%AE
        val uri = Uri.parse("https://host.com/%E2%80%AEevil")
        val n = UrlNormalizer.normalize(uri)
        assertFalse(n.malformed)
        assertFalse(n.displayString.contains("\u202E"))
    }

    @Test fun normalize_nullSchemeNotMalformedButRejectedByCaller() {
        // A Uri with no scheme: normalize should still produce something,
        // and the caller (LinkHandler) rejects non-http(s) schemes.
        val uri = Uri.parse("javascript:alert(1)")
        val n = UrlNormalizer.normalize(uri)
        // javascript: parses with scheme "javascript" → not malformed, but
        // the caller rejects it. We just verify scheme is lowercased.
        assertEquals("javascript", n.scheme)
    }

    @Test fun normalize_emptyUriIsMalformed() {
        val n = UrlNormalizer.normalize(Uri.parse(""))
        assertTrue(n.malformed)
    }

    @Test fun normalize_displayNotTruncated() {
        val longPath = "a".repeat(500)
        val uri = Uri.parse("https://host.com/$longPath")
        val n = UrlNormalizer.normalize(uri)
        assertFalse(n.malformed)
        assertTrue(n.displayString.length > 500)
        assertFalse(n.displayString.endsWith("…"))
    }

    @Test fun normalize_aboutSchemeNotMalformed() {
        // about:blank is passed through shouldOverrideUrlLoading before
        // normalize; but if it reaches here it should not be flagged malformed.
        val n = UrlNormalizer.normalize(Uri.parse("about:blank"))
        assertFalse(n.malformed)
        assertEquals("about", n.scheme)
    }

    // Regression: hostless schemes must not gain "//" or drop content.
    @Test fun normalize_aboutBlankDisplayIsNotMalformed() {
        val n = UrlNormalizer.normalize(Uri.parse("about:blank"))
        assertFalse(n.malformed)
        assertEquals("about:blank", n.displayString)
    }

    @Test fun normalize_mailtoDisplayPreservesSchemeSpecificPart() {
        val n = UrlNormalizer.normalize(Uri.parse("mailto:foo@bar.com"))
        assertFalse(n.malformed)
        assertEquals("mailto:foo@bar.com", n.displayString)
    }

    // Regression: token params in the fragment must be redacted.
    @Test fun redactForLog_masksTokenInFragment() {
        val r = UrlNormalizer.redactForLog("https://host.com/page#token=secret&keep=ok")
        assertFalse("token leaked into fragment: $r", r.contains("secret"))
        assertTrue(r.contains("keep=ok"))
    }

    @Test fun redactForLog_masksTokenInBothQueryAndFragment() {
        val r = UrlNormalizer.redactForLog("https://host.com/p?token=secret#access_token=abc")
        assertFalse("query token leaked: $r", r.contains("secret"))
        assertFalse("fragment token leaked: $r", r.contains("abc"))
    }

    // ------------------------------------------------------------------
    //  Round-trip fidelity
    //
    //  The launch/clipboard form must denote the same resource as the
    //  input href: escapes survive byte-for-byte, raw unsafe chars get
    //  encoded, and display decodes exactly once.
    // ------------------------------------------------------------------

    @Test fun normalize_doubleEncodedPath_launchFaithful_displaySingleDecoded() {
        val n = UrlNormalizer.normalize(Uri.parse("https://x/dir/file%2520name"))
        assertFalse(n.malformed)
        assertEquals("https://x/dir/file%2520name", n.clipboardString)
        // The resource is literally named "file%20name"; showing
        // "file name" would mean the display decoded twice.
        assertTrue(n.displayString.contains("file%20name"))
        assertFalse(n.displayString.contains("file name"))
    }

    @Test fun normalize_doubleEncodedQuery_roundTrips() {
        val n = UrlNormalizer.normalize(Uri.parse("https://x/p?q=a%2520b"))
        assertEquals("https://x/p?q=a%2520b", n.clipboardString)
    }

    @Test fun normalize_doubleEncodedFragment_roundTrips() {
        val n = UrlNormalizer.normalize(Uri.parse("https://x/p#a%2520b"))
        assertEquals("https://x/p#a%2520b", n.clipboardString)
    }

    @Test fun normalize_structuralEscapes_notReemittedRaw() {
        // %3F and %23 decode into '?'/'#'. Re-emitting them raw would turn
        // the rest of the path into a query, or truncate it into a fragment.
        assertEquals(
            "https://x/a%3Fb",
            UrlNormalizer.normalize(Uri.parse("https://x/a%3Fb")).clipboardString
        )
        assertEquals(
            "https://x/a%23b",
            UrlNormalizer.normalize(Uri.parse("https://x/a%23b")).clipboardString
        )
        assertEquals(
            "https://x/p?x=1%23b",
            UrlNormalizer.normalize(Uri.parse("https://x/p?x=1%23b")).clipboardString
        )
    }

    @Test fun normalize_semanticEscapes_preservedByteFaithfully() {
        // Some servers distinguish these from their decoded forms
        // (/a%2Fb vs /a/b path matching, '+' vs space in form queries).
        assertEquals(
            "https://x/a%2Fb",
            UrlNormalizer.normalize(Uri.parse("https://x/a%2Fb")).clipboardString
        )
        assertEquals(
            "https://x/s?q=a%2Bb",
            UrlNormalizer.normalize(Uri.parse("https://x/s?q=a%2Bb")).clipboardString
        )
    }

    @Test fun normalize_escapeCasePreserved() {
        val n = UrlNormalizer.normalize(Uri.parse("https://x/%e4%b8%ad"))
        assertEquals("https://x/%e4%b8%ad", n.clipboardString)
    }

    @Test fun normalize_invalidUtf8Escapes_preservedNotReplaced() {
        // The framework decodes invalid UTF-8 bytes to U+FFFD, which
        // re-encodes to different bytes. The launch form must keep the
        // original bytes.
        assertEquals("https://x/%FF", UrlNormalizer.normalize(Uri.parse("https://x/%FF")).clipboardString)
        assertEquals("https://x/%C0%80", UrlNormalizer.normalize(Uri.parse("https://x/%C0%80")).clipboardString)
        assertEquals(
            "https://x/%E4%B8%AD%FF",
            UrlNormalizer.normalize(Uri.parse("https://x/%E4%B8%AD%FF")).clipboardString
        )
    }

    @Test fun normalize_strayAndTruncatedPercent_leftAlone() {
        // A '%' outside a valid escape is copied verbatim. A trailing %25
        // must keep its hex digits: pass-through needs two chars after '%'.
        assertEquals("https://x/50%off", UrlNormalizer.normalize(Uri.parse("https://x/50%off")).clipboardString)
        assertEquals("https://x/s?50%off", UrlNormalizer.normalize(Uri.parse("https://x/s?50%off")).clipboardString)
        assertEquals("https://x/p#50%off", UrlNormalizer.normalize(Uri.parse("https://x/p#50%off")).clipboardString)
        assertEquals("https://x/%2", UrlNormalizer.normalize(Uri.parse("https://x/%2")).clipboardString)
        assertEquals("https://x/%25", UrlNormalizer.normalize(Uri.parse("https://x/%25")).clipboardString)
    }

    @Test fun normalize_encodedAstralEmoji_roundTrips() {
        val n = UrlNormalizer.normalize(Uri.parse("https://x/%F0%9F%98%80"))
        assertEquals("https://x/%F0%9F%98%80", n.clipboardString)
    }

    @Test fun normalize_nulEscape_launchPreserved_displayStripped() {
        val n = UrlNormalizer.normalize(Uri.parse("https://x/a%00b"))
        assertEquals("https://x/a%00b", n.clipboardString)
        assertFalse(n.displayString.contains("\u0000"))
    }

    @Test fun normalize_structureOddities_preserved() {
        // Empty-but-present components and repeated delimiters must survive.
        assertEquals("https://x", UrlNormalizer.normalize(Uri.parse("https://x")).clipboardString)
        assertEquals("https://x?", UrlNormalizer.normalize(Uri.parse("https://x?")).clipboardString)
        assertEquals("https://x/#", UrlNormalizer.normalize(Uri.parse("https://x/#")).clipboardString)
        assertEquals(
            "https://x/p?a?b#c#d",
            UrlNormalizer.normalize(Uri.parse("https://x/p?a?b#c#d")).clipboardString
        )
    }

    @Test fun normalize_rawUnsafeChars_stillEncodedForLaunch() {
        // Reading encoded input must not stop the pipeline from encoding
        // chars the href carried raw.
        assertEquals("https://x/a%20b", UrlNormalizer.normalize(Uri.parse("https://x/a b")).clipboardString)
        assertEquals("https://x/%5Ba%5D/b%7Cc", UrlNormalizer.normalize(Uri.parse("https://x/[a]/b|c")).clipboardString)
        val q = UrlNormalizer.normalize(Uri.parse("https://x/s?q=搜索"))
        assertTrue(q.clipboardString.contains("%E6%90%9C%E7%B4%A2"))
        assertTrue(q.displayString.contains("搜索"))
    }

    @Test fun normalize_opaqueEscapesPreserved_displayDecoded() {
        val d = UrlNormalizer.normalize(Uri.parse("data:text/html,%3Cb%3E"))
        assertEquals("data:text/html,%3Cb%3E", d.clipboardString)
        assertTrue(d.displayString.contains("<b>"))

        val m = UrlNormalizer.normalize(Uri.parse("mailto:foo%40bar.com"))
        assertEquals("mailto:foo%40bar.com", m.clipboardString)
        assertEquals("mailto:foo@bar.com", m.displayString)
    }

    @Test fun normalize_idempotent_overItsOwnOutput() {
        val inputs = listOf(
            "https://x/dir/file%2520name", "https://x/a%3Fb", "https://x/a%23b",
            "https://x/a%2Fb", "https://x/s?q=a%2Bb", "https://x/%FF",
            "https://x/%e4%b8%ad", "https://x/50%off", "https://x/a b",
            "https://example.com/搜索", "https://x/s?50%off", "https://x/p#a%2520b",
            "data:text/html,%3Cb%3E", "mailto:foo%40bar.com", "https://x/%C0%80",
            "https://x/%E4%B8%AD%FF", "https://x/%F0%9F%98%80"
        )
        for (u in inputs) {
            val once = UrlNormalizer.normalize(Uri.parse(u)).clipboardString
            val twice = UrlNormalizer.normalize(Uri.parse(once)).clipboardString
            assertEquals("not idempotent for $u", once, twice)
        }
    }
}