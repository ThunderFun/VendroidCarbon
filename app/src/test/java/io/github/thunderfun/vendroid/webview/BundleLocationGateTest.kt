package io.github.thunderfun.vendroid.webview

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.thunderfun.vendroid.utils.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Pins [HttpClient.bundleLocationFetchProblem], the single gate contract
 * shared by fetchVencord, VencordNative.updateVencord, and the boot-time
 * vencordLocation heal (VendroidApp.healUnusableVencordLocation):
 *
 *  - Official URLs and trivial variants pass; custom paths on the allowed
 *    host keep working (the developer workflow must survive the heal).
 *  - Disallowed hosts, missing hosts, non-HTTPS, and URLs OkHttp cannot
 *    parse are rejected.
 *  - Host-spoof attempts fail under both parsers.
 *  - resolveBundleLocation ignores a wrong-typed vencordLocation instead of
 *    throwing onto the startup fetch path (same contract as clientMod).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundleLocationGateTest {

    private lateinit var sPrefs: SharedPreferences

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        sPrefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sPrefs.edit().clear().commit()
    }

    // --- bundleLocationFetchProblem: accepted locations ---

    @Test
    fun officialUrls_pass() {
        assertNull(HttpClient.bundleLocationFetchProblem(Constants.JS_BUNDLE_URL))
        assertNull(HttpClient.bundleLocationFetchProblem(Constants.EQUICORD_BUNDLE_URL))
    }

    @Test
    fun trailingSlashVariant_passes() {
        assertNull(HttpClient.bundleLocationFetchProblem(Constants.JS_BUNDLE_URL + "/"))
    }

    @Test
    fun customPathOnAllowedHost_passes() {
        // The developer workflow: a different build on an allowed host.
        assertNull(
            HttpClient.bundleLocationFetchProblem(
                "https://github.com/Vendicated/Vencord/releases/download/devbuild/browser.js?tag=dev"
            )
        )
    }

    // --- bundleLocationFetchProblem: rejected locations ---

    @Test
    fun disallowedHost_rejected() {
        val problem = HttpClient.bundleLocationFetchProblem(
            "https://raw.githubusercontent.com/VendroidEnhanced/x/browser.js"
        )
        assertTrue(problem!!.contains("not in the allowed list"))
    }

    @Test
    fun missingHost_rejected() {
        // Uri.parse sees no authority at all.
        assertTrue(HttpClient.bundleLocationFetchProblem("hello")!!.contains("not in the allowed list"))
    }

    @Test
    fun plainHttpOnAllowedHost_rejected() {
        assertEquals(
            "must use HTTPS",
            HttpClient.bundleLocationFetchProblem("http://github.com/Vendicated/Vencord/releases/latest/download/browser.js")
        )
    }

    @Test
    fun uppercaseScheme_rejected() {
        // The old gate's startsWith("https://") semantics are preserved:
        // the heal removes values that could never fetch.
        assertEquals(
            "must use HTTPS",
            HttpClient.bundleLocationFetchProblem("HTTPS://github.com/Vendicated/Vencord/releases/latest/download/browser.js")
        )
    }

    @Test
    fun uriToleratedButOkHttpUnparseable_rejected() {
        // Port out of range: Android's Uri accepts it, OkHttp's HttpUrl
        // refuses to parse. This input used to slip past the gate and blow
        // up later as an IllegalArgumentException from Request.Builder().
        assertEquals(
            "not a fetchable URL",
            HttpClient.bundleLocationFetchProblem("https://github.com:99999/browser.js")
        )
    }

    @Test
    fun userinfoSpoof_rejected() {
        // The userinfo delimits before the host for both parsers: the
        // fetch would target evil.com.
        val problem = HttpClient.bundleLocationFetchProblem(
            "https://github.com@evil.com/browser.js"
        )
        assertTrue(problem!!.contains("not in the allowed list"))
        assertTrue(problem.contains("evil.com"))
    }

    @Test
    fun parserDisagreement_neverAdmitsADisallowedHost() {
        // Adversarial corpus: for every input, either the gate rejects the
        // value, or both parsers read the host as allowlisted and OkHttp
        // would fetch an allowed host.
        val adversarial = listOf(
            "https://github.com\\@evil.com/browser.js",
            "https://github.com\n.evil.com/browser.js",
            "https://github.com\t.evil.com/browser.js",
            "https://github.com.evil.com/browser.js",
            "https://github.com:65536/browser.js",
            "https://github.com:/browser.js"
        )
        for (location in adversarial) {
            val problem = HttpClient.bundleLocationFetchProblem(location)
            if (problem != null) continue
            val url = location.toHttpUrlOrNull()
            assertTrue(
                "Gate admitted '$location' but HttpUrl host '${url?.host}' is not allowlisted",
                url != null && Constants.isAllowedVencordHost(url.host)
            )
        }
    }

    // --- resolveBundleLocation: type-poison and normalization ---

    @Test
    fun wrongTypedVencordLocation_fallsBackToDefault() {
        sPrefs.edit().putBoolean("vencordLocation", true).commit()
        assertEquals(Constants.JS_BUNDLE_URL, HttpClient.resolveBundleLocation(sPrefs))
    }

    @Test
    fun blankVencordLocation_fallsBackToDefault() {
        // Empty and whitespace resolve through the same takeIf; the message
        // names the failing case.
        for (blank in listOf("", "   ")) {
            sPrefs.edit().putString("vencordLocation", blank).commit()
            assertEquals(
                "blank value '$blank' must fall back to the official default",
                Constants.JS_BUNDLE_URL,
                HttpClient.resolveBundleLocation(sPrefs)
            )
        }
    }

    @Test
    fun customLocation_isNormalized() {
        sPrefs.edit().putString("vencordLocation", "  https://github.com/Vendicated/Vencord/releases/download/devbuild/browser.js/  ").commit()
        assertEquals(
            "https://github.com/Vendicated/Vencord/releases/download/devbuild/browser.js",
            HttpClient.resolveBundleLocation(sPrefs)
        )
    }

    @Test
    fun defaultUrl_honorsClientMod() {
        sPrefs.edit().putString("clientMod", "equicord").commit()
        assertEquals(Constants.EQUICORD_BUNDLE_URL, HttpClient.resolveBundleLocation(sPrefs))
    }
}
