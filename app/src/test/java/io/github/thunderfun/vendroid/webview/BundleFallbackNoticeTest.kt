package io.github.thunderfun.vendroid.webview

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the one-shot contract of the cached-bundle fallback notice
 * ([HttpClient.claimBundleFallbackNotice] / [HttpClient.clearBundleFallbackNotice]):
 *
 *  - The first fallback of a failure episode claims the notice; a repeat
 *    fallback in the same process does not, and neither does a boot where
 *    the persisted flag is already set.
 *  - A definitive fetch success clears both halves, so the next outage warns
 *    again.
 *  - A wrong-typed persisted flag reads as "not notified" and is healed by
 *    the claim's write instead of throwing on the startup path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundleFallbackNoticeTest {

    private lateinit var sPrefs: SharedPreferences

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        sPrefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sPrefs.edit().clear().commit()
        // Resets the process-wide in-memory latch between tests.
        HttpClient.clearBundleFallbackNotice(sPrefs)
    }

    @Test
    fun firstFallback_claimsNotice_secondFallbackDoesNot() {
        assertTrue(HttpClient.claimBundleFallbackNotice(sPrefs))
        assertFalse(HttpClient.claimBundleFallbackNotice(sPrefs))
        assertTrue(sPrefs.getBoolean(HttpClient.PREF_BUNDLE_FALLBACK_NOTIFIED, false))
    }

    @Test
    fun persistedFlagFromPreviousBoot_suppressesNotice() {
        sPrefs.edit().putBoolean(HttpClient.PREF_BUNDLE_FALLBACK_NOTIFIED, true).commit()

        assertFalse(HttpClient.claimBundleFallbackNotice(sPrefs))
    }

    @Test
    fun successfulFetch_clearsBothHalves_soNextOutageWarnsAgain() {
        assertTrue(HttpClient.claimBundleFallbackNotice(sPrefs))

        HttpClient.clearBundleFallbackNotice(sPrefs)

        assertFalse(sPrefs.contains(HttpClient.PREF_BUNDLE_FALLBACK_NOTIFIED))
        assertTrue(HttpClient.claimBundleFallbackNotice(sPrefs))
    }

    @Test
    fun wrongTypedFlag_isTreatedAsNotNotified_andHealed() {
        sPrefs.edit().putString(HttpClient.PREF_BUNDLE_FALLBACK_NOTIFIED, "poison").commit()

        assertTrue(HttpClient.claimBundleFallbackNotice(sPrefs))

        assertTrue(sPrefs.getBoolean(HttpClient.PREF_BUNDLE_FALLBACK_NOTIFIED, false))
    }
}
