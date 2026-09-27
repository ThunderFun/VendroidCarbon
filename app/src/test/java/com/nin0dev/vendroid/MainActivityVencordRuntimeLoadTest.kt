package com.nin0dev.vendroid

import android.content.Context
import android.content.SharedPreferences
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.nin0dev.vendroid.webview.HttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference

/**
 * Pins the contract of MainActivity's Vencord runtime safety net
 * ([MainActivity.runSafetyNetLoad]):
 *
 *  1. Publishes a missing runtime; never clobbers one published by the
 *     preload thread or a test stub.
 *  2. Does nothing once the safe-mode kill switch is raised.
 *  3. Publishes after activity destroy (process-wide state) without touching
 *     the destroyed WebView.
 *  4. A late publish retries injection, healing a page that loaded before the
 *     runtimes were ready via the missedInjection reload.
 *
 * Tests call [MainActivity.runSafetyNetLoad] directly; it is companion-scoped
 * for exactly that. Activities are built with both runtimes pre-published so
 * loadVencordRuntimes skips the real enqueue. These tests must not race the
 * fetchExecutor thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [
        MainActivityWebViewLifecycleTest.DestroyAwareWebViewShadow::class,
        MainActivityWebViewLifecycleTest.ShadowWebViewFeature::class,
    ],
)
class MainActivityVencordRuntimeLoadTest {

    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var shadow: MainActivityWebViewLifecycleTest.DestroyAwareWebViewShadow
    private lateinit var sPrefs: SharedPreferences

    /** The evaluateJavascript callback for the injectVencordAttempt state probe. */
    private val probeCallback: ValueCallback<String>?
        get() = shadow.evalCalls.lastOrNull { (script, _) ->
            script.startsWith("(document.readyState")
        }?.second

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        sPrefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sPrefs.edit()
            .putBoolean("riskWarningAccepted", true)
            .putBoolean("vendroid_rememberLastChannel", false)
            .commit()

        // Pre-publish stub runtimes before building the activity so onCreate
        // skips the safety-net enqueue; each test then nulls the field under
        // test.
        HttpClient.vencordDisabled = false
        HttpClient.setVencordRuntime("1;")
        HttpClient.setVencordMobileRuntime("2;")
    }

    private fun buildActivity() {
        controller = Robolectric.buildActivity(MainActivity::class.java)
        activity = controller.setup().get()
        val wv = activity.findViewById<WebView>(R.id.webview)
        @Suppress("UNCHECKED_CAST")
        shadow = org.robolectric.Shadows.shadowOf(wv)
            as MainActivityWebViewLifecycleTest.DestroyAwareWebViewShadow
        // injectVencordIfReady only injects on Discord app origins.
        activity.currentUrlForBridge = "https://discord.com/app"
        activity.currentHostForBridge = "discord.com"
    }

    @Test
    fun missingMobileRuntime_isPublished() {
        buildActivity()
        HttpClient.setVencordMobileRuntime(null)
        val expected = ApplicationProvider.getApplicationContext<Context>()
            .resources.openRawResource(R.raw.vencord_mobile).use {
                HttpClient.readAsText(it)
            }

        MainActivity.runSafetyNetLoad(
            sPrefs, activity.resources, activity.filesDir, WeakReference(activity)
        )

        assertEquals(expected, HttpClient.VencordMobileRuntime)
        // The bundle branch is inert in tests: needsBundleRedownload() is
        // always true under testDebugUnitTest (BuildConfig.DEBUG), so the
        // on-disk file is never read and the stub survives.
        assertEquals("1;", HttpClient.VencordRuntime)
    }

    @Test
    fun existingMobileRuntime_isNeverClobbered() {
        buildActivity()
        // Preload thread won the race / a test stub is installed: the task
        // must leave an already-published runtime untouched.
        HttpClient.setVencordMobileRuntime("2;")

        MainActivity.runSafetyNetLoad(
            sPrefs, activity.resources, activity.filesDir, WeakReference(activity)
        )

        assertEquals("2;", HttpClient.VencordMobileRuntime)
    }

    @Test
    fun safeModeRaised_nothingIsReadOrPublished() {
        buildActivity()
        HttpClient.vencordDisabled = true
        HttpClient.setVencordRuntime(null)
        HttpClient.setVencordMobileRuntime(null)

        MainActivity.runSafetyNetLoad(
            sPrefs, activity.resources, activity.filesDir, WeakReference(activity)
        )

        assertNull(HttpClient.VencordMobileRuntime)
        assertNull(HttpClient.VencordRuntime)
    }

    @Test
    fun afterDestroy_publishesButNeverTouchesWebView() {
        buildActivity()
        controller.destroy()
        assertTrue(shadow.destroyed)
        HttpClient.setVencordMobileRuntime(null)

        MainActivity.runSafetyNetLoad(
            sPrefs, activity.resources, activity.filesDir, WeakReference(activity)
        )

        // The publish is process-wide state, but the destroyed activity's
        // WebView must not be probed. onResume's visibility eval lands in
        // evalCalls too, so count only probe scripts, as the lifecycle suite
        // does.
        assertNotNull(HttpClient.VencordMobileRuntime)
        assertEquals(
            0,
            shadow.evalCalls.count { (script, _) -> script.startsWith("(document.readyState") }
        )
    }

    @Test
    fun latePublish_retriesInjection_andHealsMissedPageViaReload() {
        buildActivity()
        // Page finished loading before the runtimes were ready: onPageStarted
        // found nothing to inject and flagged it.
        HttpClient.setVencordMobileRuntime(null)
        activity.missedInjection = true

        MainActivity.runSafetyNetLoad(
            sPrefs, activity.resources, activity.filesDir, WeakReference(activity)
        )

        // The publish must trigger injectVencordIfReady on the UI thread
        // (inline here, since the test thread is the UI thread in Robolectric).
        val probe = probeCallback
        assertNotNull("late publish must retry injection", probe)
        // Page has no Vencord -> the missedInjection branch schedules a reload.
        probe!!.onReceiveValue("\"N\"")
        assertEquals(1, shadow.reloadCount)
        assertFalse(activity.missedInjection)
    }
}
