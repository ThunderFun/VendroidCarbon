package io.github.thunderfun.vendroid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.thunderfun.vendroid.utils.Constants
import io.github.thunderfun.vendroid.webview.HttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins [VendroidApp.healUnusableVencordLocation], the boot-time repair for a
 * persisted vencordLocation the fetch path can never accept (disallowed
 * host, non-HTTPS, unparseable, or wrong-typed).
 *
 * The heal must be a no-op for values that work (official URLs, custom
 * paths on the allowed host), remove the key plus its bundle bookkeeping
 * for values that cannot work, set the one-shot notice flag, and stay
 * idempotent: the healed state is the fixed point a resurrected key
 * returns to on the next boot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VencordLocationHealTest {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var vendroidFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        vendroidFile = File(context.filesDir, "vencord.js")
        vendroidFile.delete()
        // Tests publish runtimes directly; reset so cases are independent.
        HttpClient.setVencordRuntime(null)
    }

    private fun seedCustomLegacyState(location: String) {
        // Mimic a device upgrading off an older build: custom location, a
        // cached bundle downloaded from it, a current version stamp, and
        // identity keys bound to the old location.
        prefs.edit()
            .putString("vencordLocation", location)
            .putInt(HttpClient.PREF_LAST_BUNDLE_UPDATE, BuildConfig.VERSION_CODE)
            .putString(HttpClient.PREF_ETAG, "etag-1")
            .putString(HttpClient.PREF_ETAG_LOCATION, location)
            .putString(HttpClient.PREF_ETAG_REQUEST_URL, location)
            .putString(HttpClient.PREF_BUNDLE_BUILD, "Vencord@deadbeef")
            .putBoolean(HttpClient.PREF_BUNDLE_PATCHED, true)
            .commit()
        vendroidFile.writeText("// Vencord deadbeef\n" + "x".repeat(1024))
    }

    @Test
    fun disallowedHost_healsEverything() {
        seedCustomLegacyState("https://evil.com/browser.js")
        HttpClient.setVencordRuntime("stale-bundle")

        VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

        assertNull(prefs.getString("vencordLocation", null))
        // Stamp zeroed even though it carried the current version code: the
        // first post-heal boot must revalidate unconditionally.
        assertEquals(0, prefs.getInt(HttpClient.PREF_LAST_BUNDLE_UPDATE, -1))
        assertNull(prefs.getString(HttpClient.PREF_ETAG, null))
        assertNull(prefs.getString(HttpClient.PREF_ETAG_LOCATION, null))
        assertNull(prefs.getString(HttpClient.PREF_ETAG_REQUEST_URL, null))
        assertNull(prefs.getString(HttpClient.PREF_BUNDLE_BUILD, null))
        // Default false: an absent key must read as absent, not patched.
        assertFalse(prefs.getBoolean(HttpClient.PREF_BUNDLE_PATCHED, false))
        assertFalse(vendroidFile.exists())
        assertNull(HttpClient.VencordRuntime)
        assertTrue(prefs.getBoolean(VendroidApp.PREF_VENCORD_LOCATION_HEALED, false))
    }

    @Test
    fun gateRejectedLocation_heals() {
        // Non-HTTPS and OkHttp-unparseable values are both gate rejections
        // and must take the same heal path; the reasons are pinned in
        // BundleLocationGateTest.
        val locations = listOf(
            "http://github.com/Vendicated/Vencord/releases/latest/download/browser.js",
            "https://github.com:99999/browser.js"
        )
        for (location in locations) {
            prefs.edit().clear().commit()
            seedCustomLegacyState(location)

            VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

            assertNull("$location must be removed", prefs.getString("vencordLocation", null))
            assertFalse("$location must delete the cached bundle", vendroidFile.exists())
            assertTrue(
                "$location must set the heal notice",
                prefs.getBoolean(VendroidApp.PREF_VENCORD_LOCATION_HEALED, false)
            )
        }
    }

    @Test
    fun wrongTypedValue_heals() {
        // Legacy poison from a hand-edited or restored settings file.
        prefs.edit().putBoolean("vencordLocation", true).commit()
        vendroidFile.writeText("// Vencord deadbeef\nx")

        VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

        assertNull(prefs.getString("vencordLocation", null))
        assertFalse(vendroidFile.exists())
        assertTrue(prefs.getBoolean(VendroidApp.PREF_VENCORD_LOCATION_HEALED, false))
    }

    @Test
    fun heal_isIdempotent() {
        seedCustomLegacyState("https://evil.com/browser.js")

        VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)
        VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

        assertNull(prefs.getString("vencordLocation", null))
        assertTrue(prefs.getBoolean(VendroidApp.PREF_VENCORD_LOCATION_HEALED, false))
    }

    @Test
    fun allowedLocations_notTouched() {
        // Official URLs and custom paths on the allowed host pass the gate;
        // the heal must leave the key, the cached bundle, and the notice
        // flag alone.
        val locations = listOf(
            Constants.JS_BUNDLE_URL,
            Constants.EQUICORD_BUNDLE_URL,
            "https://github.com/Vendicated/Vencord/releases/download/devbuild/browser.js"
        )
        for (location in locations) {
            prefs.edit().clear().commit()
            seedCustomLegacyState(location)
            val fileBefore = vendroidFile.readText()

            VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

            assertEquals("$location must survive the heal", location, prefs.getString("vencordLocation", null))
            assertTrue("$location must keep the cached bundle", vendroidFile.exists())
            assertEquals("$location must not touch the cached bundle", fileBefore, vendroidFile.readText())
            assertFalse(
                "$location must not set the heal notice",
                prefs.getBoolean(VendroidApp.PREF_VENCORD_LOCATION_HEALED, false)
            )
        }
    }

    @Test
    fun retiredOperatorHostUrl_heals() {
        // A vencordLocation persisted by an older build pointing at the
        // retired operator host can never fetch again after the upstream
        // flip; the heal must remove it so the boot falls back to the
        // official GitHub URL.
        seedCustomLegacyState("https://vde-builds.nin0.dev/vencord/browser.js")

        VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

        assertNull(prefs.getString("vencordLocation", null))
        assertFalse(vendroidFile.exists())
        assertTrue(prefs.getBoolean(VendroidApp.PREF_VENCORD_LOCATION_HEALED, false))
    }

    @Test
    fun emptyValue_notTouched() {
        // Empty already resolves to the default; the heal leaves the
        // harmless key.
        seedCustomLegacyState("")
        val fileBefore = vendroidFile.readText()

        VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

        assertEquals("", prefs.getString("vencordLocation", null))
        assertTrue(vendroidFile.exists())
        assertEquals(fileBefore, vendroidFile.readText())
        assertFalse(prefs.getBoolean(VendroidApp.PREF_VENCORD_LOCATION_HEALED, false))
    }

    @Test
    fun healedDefaults_resolveToOfficialUrl() {
        // End-to-end property for the affected user: after the heal, the
        // startup fetch path resolves a location the gate accepts.
        seedCustomLegacyState("https://evil.com/browser.js")
        VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

        val resolved = HttpClient.resolveBundleLocation(prefs)
        assertNotNull(resolved)
        assertNull(HttpClient.bundleLocationFetchProblem(resolved!!))
    }
}
