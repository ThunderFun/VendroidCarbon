package io.github.thunderfun.vendroid.webview

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Full-origin trust checks: the bridge token and runtimes must never reach an
 * `http://` or non-default-port app host, and a main-frame redirect must not
 * import another origin's body under the original URL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InterceptPolicyTest {

    private fun url(value: String) = value.toHttpUrl()

    // --- isDiscordAppOriginUrl(String / HttpUrl) ---

    @Test fun appOriginUrl_apexAccepted() {
        assertTrue(isDiscordAppOriginUrl("https://discord.com/app"))
        assertTrue(isDiscordAppOriginUrl("https://discord.com:443/app"))
        assertTrue(isDiscordAppOriginUrl(url("https://discord.com/app")))
    }

    @Test fun appOriginUrl_appHostsAccepted() {
        assertTrue(isDiscordAppOriginUrl("https://ptb.discord.com/app"))
        assertTrue(isDiscordAppOriginUrl("https://canary.discord.com/app"))
        assertTrue(isDiscordAppOriginUrl("https://discordapp.com/app"))
    }

    @Test fun appOriginUrl_httpRejected() {
        assertFalse(isDiscordAppOriginUrl("http://discord.com/app"))
    }

    @Test fun appOriginUrl_nonDefaultPortRejected() {
        assertFalse(isDiscordAppOriginUrl("https://discord.com:8443/app"))
    }

    @Test fun appOriginUrl_nonAppHostsRejected() {
        assertFalse(isDiscordAppOriginUrl("https://discord.gg/invite"))
        assertFalse(isDiscordAppOriginUrl("https://discordsays.com/game"))
        assertFalse(isDiscordAppOriginUrl("https://cdn.discordapp.com/img.png"))
        assertFalse(isDiscordAppOriginUrl("https://discord.com.evil.com/app"))
        assertFalse(isDiscordAppOriginUrl("https://evil.com/app"))
    }

    @Test fun appOriginUrl_malformedRejected() {
        assertFalse(isDiscordAppOriginUrl("not a url"))
        assertFalse(isDiscordAppOriginUrl(""))
    }

    // --- mainFrameRedirectAllowed ---

    @Test fun redirect_sameOriginAllowed() {
        assertTrue(
            mainFrameRedirectAllowed(
                "https://discord.com/app",
                url("https://discord.com/channels/@me")
            )
        )
    }

    @Test fun redirect_crossAppHostRejected() {
        assertFalse(
            mainFrameRedirectAllowed(
                "https://discord.com/app",
                url("https://ptb.discord.com/app")
            )
        )
        assertFalse(
            mainFrameRedirectAllowed(
                "https://discordapp.com/app",
                url("https://discord.com/app")
            )
        )
    }

    @Test fun redirect_portChangeRejected() {
        assertFalse(
            mainFrameRedirectAllowed(
                "https://discord.com/app",
                url("https://discord.com:8443/app")
            )
        )
    }

    @Test fun redirect_schemeChangeRejected() {
        assertFalse(
            mainFrameRedirectAllowed(
                "https://discord.com/app",
                url("http://discord.com/app")
            )
        )
    }

    @Test fun redirect_nonAppDestinationRejected() {
        assertFalse(
            mainFrameRedirectAllowed(
                "https://discord.com/app",
                url("https://discord.gg/invite")
            )
        )
    }

    @Test fun redirect_malformedOriginalFailsClosed() {
        assertFalse(
            mainFrameRedirectAllowed(
                "nonsense",
                url("https://discord.com/app")
            )
        )
    }

    // --- stripVencordIncompatibleCsp policy lists ---

    @Test fun stripCsp_singlePolicy_dropsIncompatibleDirectivesKeepsRest() {
        val stripped = stripVencordIncompatibleCsp(
            "default-src 'self'; frame-ancestors 'none'; script-src 'unsafe-inline'"
        )
        assertEquals("frame-ancestors 'none'", stripped)
    }

    @Test fun stripCsp_multiplePolicies_eachFilteredAndPreserved() {
        // Every policy must survive: a later put would drop the first one's
        // frame-ancestors restriction.
        val stripped = stripVencordIncompatibleCsp(
            "default-src 'self'; frame-ancestors 'none', connect-src api.example; form-action 'self'"
        )
        assertEquals("frame-ancestors 'none', form-action 'self'", stripped)
    }

    @Test fun stripCsp_policyWithNoSurvivingDirective_getsFloor() {
        val stripped = stripVencordIncompatibleCsp("default-src 'none'")
        assertEquals("frame-ancestors 'none'; base-uri 'none'; object-src 'none'", stripped)
    }

    @Test fun stripCsp_restripJoinedList_isIdempotent() {
        val once = stripVencordIncompatibleCsp("frame-ancestors 'none', form-action 'self'")
        assertEquals(once, stripVencordIncompatibleCsp(once))
    }

    // --- effectivePort ---

    @Test fun effectivePort_defaultsForKnownSchemes() {
        assertEquals(443, effectivePort("https", -1))
        assertEquals(80, effectivePort("http", -1))
        assertEquals(443, effectivePort("HTTPS", -1))
        assertEquals(8443, effectivePort("https", 8443))
        assertEquals(443, effectivePort("https", 443))
    }

    @Test fun effectivePort_unknownSchemeWithoutPort_returnsMinusOne() {
        assertEquals(-1, effectivePort("ftp", -1))
        assertEquals(-1, effectivePort(null, -1))
    }

    // --- redirect hop cookie eligibility ---

    @Test fun cookieHeaderPairs_parsesNameValueIdentities() {
        assertEquals(setOf("a" to "1", "b" to "2"), cookieHeaderPairs("a=1; b=2"))
        assertEquals(
            setOf("__dcfduid" to "abc", "session" to "xyz"),
            cookieHeaderPairs("__dcfduid=abc; session=xyz")
        )
        // Values may contain '=': only the first one splits name from value.
        assertEquals(setOf("token" to "a=b"), cookieHeaderPairs("token=a=b"))
        // Surrounding quotes normalize away on both sides.
        assertEquals(setOf("q" to "v"), cookieHeaderPairs("q=\"v\""))
        assertTrue(cookieHeaderPairs(null).isEmpty())
        assertTrue(cookieHeaderPairs("").isEmpty())
        assertTrue(cookieHeaderPairs("novalue").isEmpty())
        assertTrue(cookieHeaderPairs("=value").isEmpty())
    }

    @Test fun filterCookieHeader_keepsOnlyEligibleIdentities() {
        val allowed = setOf("a" to "1", "c" to "3")
        assertEquals("a=1; c=3", filterCookieHeader("a=1; b=2; c=3", allowed))
        assertEquals("b=2", filterCookieHeader("a=1; b=2", setOf("b" to "2")))
    }

    @Test fun filterCookieHeader_sameNameDifferentValue_isDropped() {
        // The Strict cookie at another path shares the Lax cookie's name but
        // not its value; name-only matching would resend it.
        assertNull(filterCookieHeader("session=strict-value; other=1", setOf("session" to "lax-value")))
    }

    @Test fun filterCookieHeader_emptyResultReturnsNull() {
        assertNull(filterCookieHeader("a=1", emptySet()))
        assertNull(filterCookieHeader("a=1", setOf("b" to "2")))
        assertNull(filterCookieHeader(null, setOf("a" to "1")))
    }

    @Test fun setCookiePair_parsesIdentityAndRejectsMalformed() {
        assertEquals("sid" to "abc", setCookiePair("sid=abc; Path=/; SameSite=Lax"))
        assertEquals("sid" to "abc", setCookiePair("sid=abc"))
        assertEquals("sid" to "v", setCookiePair("sid=\"v\"; HttpOnly"))
        assertNull(setCookiePair("=abc"))
        assertNull(setCookiePair("noequals"))
        assertNull(setCookiePair(""))
    }

    @Test fun setCookieSameSiteStrict_onlyExplicitStrict() {
        assertTrue(setCookieSameSiteStrict("sid=abc; SameSite=Strict"))
        assertTrue(setCookieSameSiteStrict("sid=abc; samesite=strict; Path=/"))
        assertTrue(setCookieSameSiteStrict("sid=abc; SameSite=\"Strict\""))
        assertFalse(setCookieSameSiteStrict("sid=abc; SameSite=Lax"))
        assertFalse(setCookieSameSiteStrict("sid=abc; SameSite=None"))
        assertFalse(setCookieSameSiteStrict("sid=abc"))
        assertFalse(setCookieSameSiteStrict("sid=abc; Path=/strict"))
    }

    // --- MainFrameDiskCache.isCacheableRoute full-origin gate ---

    @Test fun cacheableRoute_defaultPortAppShellAccepted() {
        assertTrue(MainFrameDiskCache.isCacheableRoute(Uri.parse("https://discord.com/app")))
        assertTrue(MainFrameDiskCache.isCacheableRoute(Uri.parse("https://discord.com:443/channels/@me")))
    }

    @Test fun cacheableRoute_nonDefaultPortRejected() {
        assertFalse(MainFrameDiskCache.isCacheableRoute(Uri.parse("https://discord.com:8443/app")))
    }

    @Test fun cacheableRoute_httpAndNonAppHostsRejected() {
        assertFalse(MainFrameDiskCache.isCacheableRoute(Uri.parse("http://discord.com/app")))
        assertFalse(MainFrameDiskCache.isCacheableRoute(Uri.parse("https://cdn.discordapp.com/app")))
    }

    // --- credential partition + stale header preservation ---

    @Test fun credentialHeadersPartition_hashesCookieAndAuthorization() {
        val a = credentialHeadersPartition(mapOf("Cookie" to "session=a", "authorization" to "Bearer t"))
        val b = credentialHeadersPartition(mapOf("cookie" to "session=a", "Authorization" to "Bearer t"))
        assertEquals(a, b)
        assertTrue(a.startsWith("|ck="))
        assertTrue(a.contains("|ah="))
        assertFalse(a.contains("session=a"))
        assertFalse(a.contains("Bearer"))
        assertEquals("", credentialHeadersPartition(emptyMap()))
        assertNotEquals(
            credentialHeadersPartition(mapOf("cookie" to "session=a")),
            credentialHeadersPartition(mapOf("cookie" to "session=b"))
        )
    }

    @Test fun copyStalePreservedHeaders_copiesCaseInsensitivelyAndSkipsOthers() {
        val stored = mapOf(
            "Referrer-Policy" to "no-referrer",
            "permissions-policy" to "geolocation=()",
            "x-content-type-options" to "nosniff",
            "cache-control" to "max-age=60",
            "set-cookie" to "a=1"
        )
        val into = HashMap<String, String>()
        copyStalePreservedHeaders(stored, into)
        assertEquals("no-referrer", into["referrer-policy"])
        assertEquals("geolocation=()", into["permissions-policy"])
        assertEquals("nosniff", into["x-content-type-options"])
        assertFalse(into.containsKey("cache-control"))
        assertFalse(into.containsKey("set-cookie"))
    }

    @Test fun diskCache_roundTripsCredentialPartitionAndPreservedHeaders() {
        MainFrameDiskCache.init(ApplicationProvider.getApplicationContext())
        val url = "https://discord.com/app"
        val headers = mapOf(
            "content-type" to "text/html",
            "referrer-policy" to "no-referrer",
            "cache-control" to "max-age=60"
        )
        assertTrue(
            MainFrameDiskCache.writeMainFrame(
                url, "<html></html>".toByteArray(Charsets.UTF_8), headers,
                "OK", credentialPartition = "|ck=abc"
            )
        )
        val read = MainFrameDiskCache.readMainFrame(url)
        assertEquals("|ck=abc", read?.credentialPartition)
        assertEquals("no-referrer", read?.headers?.get("referrer-policy"))
        assertFalse(read?.headers?.containsKey("cache-control") ?: true)
    }
}
