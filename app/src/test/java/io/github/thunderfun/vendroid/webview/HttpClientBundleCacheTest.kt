package io.github.thunderfun.vendroid.webview

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Pins the disk-cache contract of [HttpClient.readBundleFromDisk]:
 *
 *  - An oversized (corrupt) file fails closed; readText() has no cap and an
 *    uncaught OOM on a background thread still kills the process.
 *  - A well-formed file round-trips unchanged when the persisted patch flag
 *    is absent and no patch markers are missing.
 *
 * Also pins [clearBundleIdentityKeys], the key list both bundle invalidation
 * call sites must keep identical. The lists were once hand-synced and the
 * switch's copy missed the build/hash pair, so the recovery screen named the
 * old mod's bundle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpClientBundleCacheTest {

    private lateinit var sPrefs: SharedPreferences
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        sPrefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sPrefs.edit().clear().commit()
        filesDir = appContext.filesDir
    }

    @Test
    fun oversizedCacheFile_failsClosed() {
        val file = File(filesDir, "vencord.js")
        file.writeText("a".repeat(HttpClient.MAX_READ_BYTES + 1))
        try {
            try {
                HttpClient.readBundleFromDisk(sPrefs, file)
                fail("Expected IOException for a cache file over MAX_READ_BYTES")
            } catch (expected: IOException) {
                // The message names the limit.
                assert(expected.message!!.contains("byte limit"))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun wellFormedCacheFile_roundTripsUnchanged() {
        val file = File(filesDir, "vencord.js")
        val content = "// Vencord testbuild\n" + "x".repeat(4096)
        file.writeText(content)
        try {
            assertEquals(content, HttpClient.readBundleFromDisk(sPrefs, file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun clearBundleIdentityKeys_removesExactlyTheEightIdentityKeys() {
        val identityKeys = listOf(
            HttpClient.PREF_ETAG,
            HttpClient.PREF_ETAG_LOCATION,
            HttpClient.PREF_ETAG_REQUEST_URL,
            HttpClient.PREF_BUNDLE_BUILD,
            HttpClient.PREF_BUNDLE_HASH,
            HttpClient.PREF_BUNDLE_PATCHED,
            HttpClient.PREF_BUNDLE_PATCH_SET,
            HttpClient.PREF_LAST_BUNDLE_CHECK
        )
        sPrefs.edit {
            identityKeys.forEach { putString(it, "x") }
            // These two must survive the wipe. Only the mod switch zeroes
            // the version stamp, and unrelated settings are out of scope.
            putInt(HttpClient.PREF_LAST_BUNDLE_UPDATE, 7)
            putString("unrelatedSetting", "keep")
        }
        sPrefs.edit { clearBundleIdentityKeys() }
        identityKeys.forEach { assertFalse("$it should be cleared", sPrefs.contains(it)) }
        assertEquals(7, sPrefs.getInt(HttpClient.PREF_LAST_BUNDLE_UPDATE, -1))
        assertEquals("keep", sPrefs.getString("unrelatedSetting", null))
    }
}
