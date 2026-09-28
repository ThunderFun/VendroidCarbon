package io.github.thunderfun.vendroid.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ResponseHeaderMerge]. Pure Kotlin, no Android types, so it
 * runs on the plain JVM. Covers the duplicate-header folding that replaced
 * the previous last-value-wins put, which dropped e.g. one of Discord's two
 * Set-Cookie headers on every main frame.
 */
class ResponseHeaderMergeTest {

    private fun merge(vararg pairs: Pair<String, String>): Pair<Map<String, String>, List<String>> {
        val headers = HashMap<String, String>()
        val cookies = ArrayList<String>()
        for ((k, v) in pairs) ResponseHeaderMerge.merge(headers, cookies, k, v)
        return headers to cookies
    }

    @Test fun duplicateSetCookie_collectedInWireOrder_notInMap() {
        val (headers, cookies) = merge(
            "set-cookie" to "__dcfduid=aaa; Expires=Sat, 30 Aug 2031 08:46:56 GMT; HttpOnly",
            "set-cookie" to "__sdcfduid=bbb; Expires=Sat, 30 Aug 2031 08:46:56 GMT"
        )
        assertEquals(listOf("__dcfduid=aaa; Expires=Sat, 30 Aug 2031 08:46:56 GMT; HttpOnly", "__sdcfduid=bbb; Expires=Sat, 30 Aug 2031 08:46:56 GMT"), cookies)
        assertFalse(headers.containsKey("set-cookie"))
    }

    @Test fun setCookie_mixedCasing_allCollected() {
        val (_, cookies) = merge(
            "Set-Cookie" to "a=1",
            "set-cookie" to "b=2",
            "SET-COOKIE" to "c=3"
        )
        assertEquals(listOf("a=1", "b=2", "c=3"), cookies)
    }

    @Test fun setCookie_withCommaInExpires_neverJoined() {
        val (headers, cookies) = merge(
            "set-cookie" to "a=1; Expires=Sat, 30 Aug 2031 08:46:56 GMT",
            "set-cookie" to "b=2"
        )
        // A comma join would split mid-date; both cookies must stay intact.
        assertEquals(listOf("a=1; Expires=Sat, 30 Aug 2031 08:46:56 GMT", "b=2"), cookies)
        assertFalse(headers.containsKey("set-cookie"))
    }

    @Test fun setCookie_valueWithNulDropped() {
        val (_, cookies) = merge(
            "set-cookie" to "a=1\u0000b=2",
            "set-cookie" to "c=3"
        )
        assertEquals(listOf("c=3"), cookies)
    }

    @Test fun setCookie_valueWithNewlineDropped() {
        val (_, cookies) = merge("set-cookie" to "a=1\nHost: evil")
        assertTrue(cookies.isEmpty())
    }

    @Test fun duplicateListHeader_commaJoined() {
        val (headers, _) = merge(
            "vary" to "Accept-Encoding",
            "vary" to "Origin"
        )
        assertEquals("Accept-Encoding, Origin", headers["vary"])
    }

    @Test fun duplicateListHeader_keepsWireOrder() {
        val (headers, _) = merge(
            "link" to "<a>; rel=preload",
            "link" to "<b>; rel=preload"
        )
        assertEquals("<a>; rel=preload, <b>; rel=preload", headers["link"])
    }

    @Test fun duplicateSingleton_lastWins() {
        val (headers, _) = merge(
            "report-to" to """{"group":"a"}""",
            "report-to" to """{"group":"b"}"""
        )
        assertEquals("""{"group":"b"}""", headers["report-to"])
    }

    @Test fun duplicateContentSecurityPolicy_lastWins_notCommaJoined() {
        val (headers, _) = merge(
            "content-security-policy" to "default-src 'self'",
            "content-security-policy" to "object-src 'none'"
        )
        assertEquals("object-src 'none'", headers["content-security-policy"])
    }

    @Test fun mixedCaseNames_foldedIntoLowercaseSingleEntry() {
        val (headers, _) = merge(
            "Content-Type" to "text/html; charset=utf-8",
            "content-type" to "application/json"
        )
        // One entry, not two lines Chromium would have to disambiguate.
        assertEquals(1, headers.size)
        assertEquals("application/json", headers["content-type"])
    }

    @Test fun mixedCaseListHeader_joinedAcrossCasing() {
        val (headers, _) = merge(
            "Vary" to "Accept-Encoding",
            "vary" to "Origin"
        )
        assertEquals("Accept-Encoding, Origin", headers["vary"])
    }

    @Test fun singletonReplacedInPlace_keepsLowercaseKey() {
        val headers = HashMap<String, String>()
        ResponseHeaderMerge.merge(headers, ArrayList(), "content-type", "text/plain")
        ResponseHeaderMerge.merge(headers, ArrayList(), "Content-Type", "text/html")
        assertEquals("text/html", headers["content-type"])
        assertEquals(1, headers.size)
    }

    @Test fun uniqueHeaders_passThroughLowercased() {
        val (headers, _) = merge(
            "X-Build-Id" to "abc",
            "Server" to "cloudflare"
        )
        assertEquals("abc", headers["x-build-id"])
        assertEquals("cloudflare", headers["server"])
    }

    @Test fun valueFor_exactLowercaseHit() {
        val map = mapOf("content-type" to "text/html")
        assertEquals("text/html", ResponseHeaderMerge.valueFor(map, "content-type"))
    }

    @Test fun valueFor_caseInsensitiveFallback_forLegacyEntries() {
        val map = mapOf("Content-Security-Policy" to "default-src 'self'")
        assertEquals("default-src 'self'", ResponseHeaderMerge.valueFor(map, "content-security-policy"))
    }

    @Test fun valueFor_missReturnsNull() {
        assertNull(ResponseHeaderMerge.valueFor(mapOf("a" to "b"), "c"))
    }

    @Test fun bareMediaType_stripsParameters() {
        assertEquals("text/html", ResponseHeaderMerge.bareMediaType("text/html; charset=utf-8"))
        assertEquals("application/json", ResponseHeaderMerge.bareMediaType("application/json; charset=utf-8"))
    }

    @Test fun bareMediaType_bareValuePassesThrough() {
        assertEquals("text/html", ResponseHeaderMerge.bareMediaType("text/html"))
        assertEquals("application/json", ResponseHeaderMerge.bareMediaType("application/json"))
    }

    @Test fun bareMediaType_trimsWhitespace() {
        assertEquals("text/html", ResponseHeaderMerge.bareMediaType(" text/html "))
        assertEquals("text/html", ResponseHeaderMerge.bareMediaType("text/html ; charset=utf-8"))
    }

    @Test fun bareMediaType_nullOrEmptyReturnsNull() {
        assertNull(ResponseHeaderMerge.bareMediaType(null))
        assertNull(ResponseHeaderMerge.bareMediaType(""))
        assertNull(ResponseHeaderMerge.bareMediaType("   "))
    }

    @Test fun bareMediaType_parameterOnlyValueReturnsNull() {
        // Caller decides the fallback; the media type is genuinely absent.
        assertNull(ResponseHeaderMerge.bareMediaType("; charset=utf-8"))
    }
}
