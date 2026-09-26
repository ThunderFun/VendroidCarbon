package com.nin0dev.vendroid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.webview.HttpClient
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
    fun plainHttpOnAllowedHost_heals() {
        seedCustomLegacyState("http://github.com/Vendicated/Vencord/releases/latest/download/browser.js")

        VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

        assertNull(prefs.getString("vencordLocation", null))
        assertFalse(vendroidFile.exists())
        assertTrue(prefs.getBoolean(VendroidApp.PREF_VENCORD_LOCATION_HEALED, false))
    }

    @Test
    fun uriToleratedButOkHttpUnparseable_heals() {
        seedCustomLegacyState("https://github.com:99999/browser.js")

        VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

        assertNull(prefs.getString("vencordLocation", null))
        assertFalse(vendroidFile.exists())
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
    fun officialUrl_notTouched() {
        seedCustomLegacyState(Constants.JS_BUNDLE_URL)
        val fileBefore = vendroidFile.readText()

        VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

        assertEquals(Constants.JS_BUNDLE_URL, prefs.getString("vencordLocation", null))
        assertTrue(vendroidFile.exists())
        assertEquals(fileBefore, vendroidFile.readText())
        assertFalse(prefs.getBoolean(VendroidApp.PREF_VENCORD_LOCATION_HEALED, false))
    }

    @Test
    fun equicordOfficialUrl_notTouched() {
        seedCustomLegacyState(Constants.EQUICORD_BUNDLE_URL)

        VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

        assertEquals(Constants.EQUICORD_BUNDLE_URL, prefs.getString("vencordLocation", null))
        assertTrue(vendroidFile.exists())
        assertFalse(prefs.getBoolean(VendroidApp.PREF_VENCORD_LOCATION_HEALED, false))
    }

    @Test
    fun customPathOnAllowedHost_notTouched() {
        // The developer workflow must survive the heal.
        seedCustomLegacyState("https://github.com/Vendicated/Vencord/releases/download/devbuild/browser.js")

        VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

        assertEquals(
            "https://github.com/Vendicated/Vencord/releases/download/devbuild/browser.js",
            prefs.getString("vencordLocation", null)
        )
        assertTrue(vendroidFile.exists())
        assertFalse(prefs.getBoolean(VendroidApp.PREF_VENCORD_LOCATION_HEALED, false))
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
    fun absentKey_noOp() {
        VendroidApp.healUnusableVencordLocation(prefs, vendroidFile)

        assertFalse(prefs.getBoolean(VendroidApp.PREF_VENCORD_LOCATION_HEALED, false))
        assertNull(prefs.getString(HttpClient.PREF_ETAG, null))
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
