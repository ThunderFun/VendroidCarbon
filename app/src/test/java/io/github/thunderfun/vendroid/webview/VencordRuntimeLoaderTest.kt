package io.github.thunderfun.vendroid.webview

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.thunderfun.vendroid.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the contract of [VencordRuntimeLoader.loadIfMissing], the single owner
 * of the runtime preload sequence shared by VendroidApp's preload thread and
 * MainActivity's safety-net task:
 *
 *  1. Publishes a missing runtime; never clobbers one already present.
 *  2. Does nothing once the safe-mode kill switch is raised.
 *  3. The bundle branch stays closed while needsBundleRedownload() is true.
 *
 * The bundle branch is inert under testDebugUnitTest:
 * needsBundleRedownload() returns true for every debug build
 * (BuildConfig.DEBUG), so the on-disk file is never read. That also puts
 * stillValid invalidation (file deleted mid-read) out of unit-test reach;
 * the CAS helpers themselves are pinned by HttpClient's tests. The callers'
 * end-to-end contracts are pinned by MainActivityVencordRuntimeLoadTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VencordRuntimeLoaderTest {

    private lateinit var context: Context
    private lateinit var sPrefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        resetStatics()
    }

    @After
    fun tearDown() {
        // The runtime fields and kill switch are process-wide statics that
        // every other test class in this JVM reads. Reset even after failures
        // so test-class ordering cannot leak state.
        resetStatics()
    }

    private fun resetStatics() {
        HttpClient.vencordDisabled = false
        HttpClient.setVencordRuntime(null)
        HttpClient.setVencordMobileRuntime(null)
    }

    @Test
    fun missingMobileRuntime_isPublishedWithResourceContent() {
        val expected = context.resources.openRawResource(R.raw.vencord_mobile).use {
            HttpClient.readAsText(it)
        }

        val outcome = VencordRuntimeLoader.loadIfMissing(sPrefs, context.resources, context.filesDir)

        assertTrue(outcome.mobilePublished)
        assertTrue(outcome.publishedSomething)
        assertEquals(expected, HttpClient.VencordMobileRuntime)
    }

    @Test
    fun existingMobileRuntime_isNeverClobbered() {
        // Preload thread won the race / a test stub is installed: the loader
        // must leave an already-published runtime untouched.
        HttpClient.setVencordMobileRuntime("2;")

        val outcome = VencordRuntimeLoader.loadIfMissing(sPrefs, context.resources, context.filesDir)

        assertFalse(outcome.mobilePublished)
        assertEquals("2;", HttpClient.VencordMobileRuntime)
    }

    @Test
    fun safeModeRaised_nothingIsReadOrPublished() {
        HttpClient.vencordDisabled = true

        val outcome = VencordRuntimeLoader.loadIfMissing(sPrefs, context.resources, context.filesDir)

        assertFalse(outcome.mobilePublished)
        assertFalse(outcome.bundlePublished)
        assertFalse(outcome.publishedSomething)
        assertNull(HttpClient.VencordMobileRuntime)
        assertNull(HttpClient.VencordRuntime)
    }

    @Test
    fun bundleBranch_staysClosedWhileRedownloadIsPending() {
        // needsBundleRedownload() is always true under testDebugUnitTest
        // (BuildConfig.DEBUG), so the cached file must not be read or
        // published even though it exists.
        HttpClient.vendroidFile(context.filesDir).writeText("cached;")

        val outcome = VencordRuntimeLoader.loadIfMissing(sPrefs, context.resources, context.filesDir)

        assertTrue(outcome.mobilePublished)
        assertFalse(outcome.bundlePublished)
        assertNull(HttpClient.VencordRuntime)
    }
}
