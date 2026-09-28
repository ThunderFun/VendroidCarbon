package io.github.thunderfun.vendroid.webview

import io.github.thunderfun.vendroid.utils.Constants
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * Pins the bundle-host allowlist.
 *
 * The bundle is now fetched from GitHub release URLs, whose 302 chain hops
 * github.com → github.com/releases/download/<tag>/... →
 * release-assets.githubusercontent.com. Both hosts must be allowlisted, and
 * every redirect hop must clear the same gate (the shared client never
 * auto-follows redirects, so a single missed hop fails the whole fetch).
 *
 * Also pins the retired operator hosts staying rejected: a persisted
 * vencordLocation pointing at vde-builds.nin0.dev must keep failing the
 * gate so the boot-time heal removes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VencordHostAllowlistTest {

    // --- Constants.isAllowedVencordHost ---

    @Test
    fun githubApex_allowed() {
        assertTrue(Constants.isAllowedVencordHost("github.com"))
    }

    @Test
    fun releaseAssetsCdn_allowed() {
        assertTrue(Constants.isAllowedVencordHost("release-assets.githubusercontent.com"))
    }

    @Test
    fun retiredOperatorHosts_rejected() {
        assertFalse(Constants.isAllowedVencordHost("vde-builds.nin0.dev"))
        assertFalse(Constants.isAllowedVencordHost("git.nin0.dev"))
        assertFalse(Constants.isAllowedVencordHost("vendroid.nin0.dev"))
    }

    @Test
    fun lookalikeHosts_rejected() {
        // endsWith-style bypasses must fail: the matcher compares whole hosts.
        assertFalse(Constants.isAllowedVencordHost("evil-github.com"))
        assertFalse(Constants.isAllowedVencordHost("github.com.evil.io"))
        assertFalse(Constants.isAllowedVencordHost("release-assets.githubusercontent.com.evil.io"))
        assertFalse(Constants.isAllowedVencordHost("notgithub.com"))
        assertFalse(Constants.isAllowedVencordHost("raw.githubusercontent.com"))
    }

    // --- official URLs clear the fetch gate ---

    @Test
    fun officialBundleUrls_passTheFetchGate() {
        assertNull(HttpClient.bundleLocationFetchProblem(Constants.JS_BUNDLE_URL))
        assertNull(HttpClient.bundleLocationFetchProblem(Constants.EQUICORD_BUNDLE_URL))
    }

    @Test
    fun retiredHostUrls_failTheFetchGate() {
        // A legacy persisted vencordLocation must keep failing so
        // VendroidApp.healUnusableVencordLocation removes it on upgrade.
        val problem = HttpClient.bundleLocationFetchProblem(
            "https://vde-builds.nin0.dev/vencord/browser.js"
        )
        assertNotNull(problem)
        assertTrue(problem!!.contains("not in the allowed list"))
    }

    // --- redirect hop re-validation (executeVencordGetResolvingRedirect) ---

    @Test
    fun redirectHops_githubChain_isAccepted() {
        // Recorded hop chain for /releases/latest/download/browser.js:
        // github.com → github.com/releases/download/<tag>/... →
        // release-assets.githubusercontent.com. Relative Locations must
        // resolve against the current hop.
        var url = Constants.JS_BUNDLE_URL
        val hops = listOf(
            "/Vendicated/Vencord/releases/download/devbuild/browser.js",
            "https://release-assets.githubusercontent.com/github-production-release-asset/browser.js"
        )
        for (location in hops) {
            val target = HttpClient.resolveRedirectTarget(url, location)
            assertTrue(target.isHttps)
            assertTrue(
                "Hop host ${target.host} must be allowlisted",
                Constants.isAllowedVencordHost(target.host)
            )
            url = target.toString()
        }
        assertEquals("release-assets.githubusercontent.com", url.toHttpUrlOrNull()?.host)
    }

    @Test
    fun redirectHops_disallowedHost_rejected() {
        val ex = assertThrowsIo {
            HttpClient.resolveRedirectTarget(
                Constants.JS_BUNDLE_URL,
                "https://evil.com/browser.js"
            )
        }
        assertTrue(ex.message!!.contains("disallowed host"))
    }

    @Test
    fun redirectHops_nonHttps_rejected() {
        val ex = assertThrowsIo {
            HttpClient.resolveRedirectTarget(
                Constants.JS_BUNDLE_URL,
                "http://release-assets.githubusercontent.com/browser.js"
            )
        }
        assertTrue(ex.message!!.contains("non-HTTPS"))
    }

    @Test
    fun redirectHops_relativeLocation_resolvesAgainstCurrentHop() {
        // Relative references per RFC 3986: resolved against the hop URL,
        // not treated as a fresh absolute URL.
        val target = HttpClient.resolveRedirectTarget(
            "https://github.com/Vendicated/Vencord/releases/latest/download/browser.js",
            "/Vendicated/Vencord/releases/download/devbuild/browser.js"
        )
        assertEquals(
            "https://github.com/Vendicated/Vencord/releases/download/devbuild/browser.js",
            target.toString()
        )
    }

    @Test
    fun redirectHops_blankLocation_rejected() {
        val ex = assertThrowsIo {
            HttpClient.resolveRedirectTarget(Constants.JS_BUNDLE_URL, "  ")
        }
        assertTrue(ex.message!!.contains("blank Location"))
    }

    private fun assertThrowsIo(block: () -> Unit): java.io.IOException {
        try {
            block()
        } catch (e: java.io.IOException) {
            return e
        }
        throw AssertionError("Expected IOException")
    }
}
