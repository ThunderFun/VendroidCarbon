package io.github.thunderfun.vendroid.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the skip decision for the bundle's conditional GET:
 * [HttpClient.bundleCheckSkippable] (the fetchVencord fast-path verdict) and
 * [HttpClient.isBundleCheckDue] (the persisted-stamp window math). Pure
 * functions: no prefs, no network, no Robolectric.
 *
 * The polarity assertions (absent stamp -> due; first boot -> network) exist
 * because inverting the fast-path condition compiles clean, passes every
 * other test, and silently disables revalidation forever: the fast path
 * returns before the stamp is ever written.
 */
class HttpClientBundleFreshnessTest {

    private val interval = HttpClient.BUNDLE_CHECK_INTERVAL_MS

    // --- bundleCheckSkippable: the fetchVencord fast-path verdict ---

    @Test
    fun firstBootNeverChecked_mustNotSkip() {
        // An absent stamp must read as DUE. If this ever returns true, the
        // skip fires before any stamp is written and the bundle is never
        // revalidated.
        assertFalse(
            HttpClient.bundleCheckSkippable(
                runtimeInMemory = true,
                needsRedownload = false,
                checkedThisSession = false,
                lastCheckMs = 0L,
                nowMs = 1_000_000L
            )
        )
    }

    @Test
    fun verifiedThisSession_skips() {
        assertTrue(
            HttpClient.bundleCheckSkippable(
                runtimeInMemory = true,
                needsRedownload = false,
                checkedThisSession = true,
                lastCheckMs = 0L,
                nowMs = 1_000_000L
            )
        )
    }

    @Test
    fun withinWindow_skips() {
        val now = 1_000_000_000L
        assertTrue(
            HttpClient.bundleCheckSkippable(
                runtimeInMemory = true,
                needsRedownload = false,
                checkedThisSession = false,
                lastCheckMs = now - interval + 60_000L,
                nowMs = now
            )
        )
    }

    @Test
    fun windowExpired_doesNotSkip() {
        val now = 1_000_000_000L
        assertFalse(
            HttpClient.bundleCheckSkippable(
                runtimeInMemory = true,
                needsRedownload = false,
                checkedThisSession = false,
                lastCheckMs = now - interval - 1L,
                nowMs = now
            )
        )
    }

    @Test
    fun noRuntimeInMemory_neverSkips() {
        // The network path is the missedInjection recovery trigger: a boot
        // whose runtime is not in memory must always reach it, even with a
        // session flag or a fresh window.
        assertFalse(
            HttpClient.bundleCheckSkippable(
                runtimeInMemory = false,
                needsRedownload = false,
                checkedThisSession = true,
                lastCheckMs = 1_000_000_000L,
                nowMs = 1_000_000_000L
            )
        )
    }

    @Test
    fun pendingRedownload_neverSkips() {
        // Version bump, custom URL, or debug build: force the round trip.
        assertFalse(
            HttpClient.bundleCheckSkippable(
                runtimeInMemory = true,
                needsRedownload = true,
                checkedThisSession = true,
                lastCheckMs = 1_000_000_000L,
                nowMs = 1_000_000_000L
            )
        )
    }

    // --- isBundleCheckDue: the window math ---

    @Test
    fun absentStamp_isDue() {
        assertTrue(HttpClient.isBundleCheckDue(0L, 5_000_000L))
    }

    @Test
    fun negativeStamp_isDue() {
        assertTrue(HttpClient.isBundleCheckDue(-42L, 5_000_000L))
    }

    @Test
    fun withinWindow_isNotDue() {
        val last = 1_000_000L
        assertFalse(HttpClient.isBundleCheckDue(last, last + interval - 1))
    }

    @Test
    fun exactlyAtBoundary_isDue() {
        val last = 1_000_000L
        assertTrue(HttpClient.isBundleCheckDue(last, last + interval))
    }

    @Test
    fun pastWindow_isDue() {
        val last = 1_000_000L
        assertTrue(HttpClient.isBundleCheckDue(last, last + interval + 60_000L))
    }

    @Test
    fun clockSetBackwards_isDue() {
        // Manual clock change / dead-RTC epoch boot: the delta goes negative.
        // Treating that as not-due would postpone the security check until
        // wall time catches up.
        val last = 10_000_000L
        assertTrue(HttpClient.isBundleCheckDue(last, last - 60_000L))
    }
}
