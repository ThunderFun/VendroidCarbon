package io.github.thunderfun.vendroid

import android.content.Context
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.WebViewFeature
import io.github.thunderfun.vendroid.webview.HttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWebView

/**
 * Pins the WebView lifecycle contract of MainActivity:
 *
 *  1. onDestroy nulls `wv` immediately after destroy(), and both statements
 *     are adjacent on the UI thread, so every entry point that re-reads `wv`
 *     after onDestroy observes null and no-ops.
 *  2. A destroyed WebView may still deliver pending evaluateJavascript
 *     results (Chromium flushes queued callbacks, often with null). A callback
 *     holding the captured instance must re-check the `wv` field before
 *     touching it, or a post-destroy delivery calls reload() on the dead
 *     instance and crashes (reload throws NPE inside WebViewChromium, not
 *     IllegalStateException, so try/catch ISE does not contain it).
 *
 * The destroy-aware shadow reproduces the destroyed-WebView behavior: reload()
 * throws once destroyed. Without the liveness guard in injectVencordAttempt,
 * probeCallbackAfterDestroy_staleResult fails with that exception.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [
        MainActivityWebViewLifecycleTest.DestroyAwareWebViewShadow::class,
        MainActivityWebViewLifecycleTest.ShadowWebViewFeature::class,
    ],
)
class MainActivityWebViewLifecycleTest {

    /**
     * Robolectric cannot satisfy androidx.webkit's provider lookup, so every
     * WebViewFeature check reports false. Both call sites in the app (safe
     * browsing, service worker) are feature-gated and skip cleanly.
     */
    @Implements(WebViewFeature::class)
    class ShadowWebViewFeature {
        companion object {
            @JvmStatic
            @Implementation
            fun isFeatureSupported(feature: String): Boolean = false
        }
    }

    @Implements(WebView::class)
    class DestroyAwareWebViewShadow : ShadowWebView() {
        var destroyed = false
            private set
        var reloadCount = 0
            private set
        val evalCalls = mutableListOf<Pair<String, ValueCallback<String>?>>()

        @Implementation
        override fun evaluateJavascript(script: String?, callback: ValueCallback<String>?) {
            // Delivery after destroy is simulated by the test invoking a
            // captured callback later.
            evalCalls += (script ?: "") to callback
        }

        @Implementation
        override fun reload() {
            // Matches WebViewChromium: reload() on a destroyed WebView
            // throws. Most builds throw NPE, some ISE; either fails the test.
            check(!destroyed) { "The WebView has been destroyed" }
            reloadCount++
        }

        @Implementation
        override fun destroy() {
            destroyed = true
        }
    }

    private lateinit var controller: org.robolectric.android.controller.ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var shadow: DestroyAwareWebViewShadow

    /** The evaluateJavascript callback for the injectVencordAttempt state probe. */
    private val probeCallback: ValueCallback<String>?
        get() = shadow.evalCalls.lastOrNull { (script, _) ->
            script.startsWith("(document.readyState")
        }?.second

    @Before
    fun setUp() {
        // Skip the first-run risk warning so onCreate installs the WebView.
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("riskWarningAccepted", true)
            .putBoolean("vendroid_rememberLastChannel", false)
            .commit()

        // Publish a tiny runtime so injectVencordIfReady has something to inject.
        HttpClient.vencordDisabled = false
        HttpClient.setVencordRuntime("1;")
        HttpClient.setVencordMobileRuntime("2;")

        controller = Robolectric.buildActivity(MainActivity::class.java)
        activity = controller.setup().get()
        val wv = activity.findViewById<WebView>(R.id.webview)
        shadow = org.robolectric.Shadows.shadowOf(wv) as DestroyAwareWebViewShadow

        // injectVencordIfReady only injects on Discord app origins.
        activity.currentUrlForBridge = "https://discord.com/app"
        activity.currentHostForBridge = "discord.com"
    }

    @Test
    fun probeCallbackWhileAlive_missedInjection_triggersReload() {
        // Positive control: the same delivery reloads while the activity
        // is alive.
        activity.injectVencordIfReady()
        activity.missedInjection = true
        probeCallback!!.onReceiveValue("\"N\"")

        assertEquals(1, shadow.reloadCount)
        assertFalse(activity.missedInjection)
    }

    @Test
    fun probeCallbackAfterDestroy_staleResult_doesNotReloadOrCrash() {
        // Page started before the bundle was published (missedInjection =
        // true), user exits, and the pending probe result is flushed by the
        // destroyed WebView. Without the liveness guard this calls reload()
        // on the dead instance and crashes.
        activity.injectVencordIfReady()
        activity.missedInjection = true
        val callback = probeCallback
        controller.destroy()

        assertTrue(shadow.destroyed)
        callback!!.onReceiveValue("\"N\"")

        assertEquals("reload() must not run on the destroyed WebView", 0, shadow.reloadCount)
    }

    @Test
    fun probeCallbackAfterDestroy_nullResult_doesNotReloadOrCrash() {
        activity.injectVencordIfReady()
        val callback = probeCallback
        controller.destroy()

        // Chromium commonly delivers null when the renderer is gone.
        callback!!.onReceiveValue(null)

        assertEquals(0, shadow.reloadCount)
    }

    @Test
    fun injectionEntryPointsAfterDestroy_areNoops() {
        controller.destroy()

        // Both entry points re-read wv, observe null, and must not reach the
        // destroyed WebView.
        activity.injectVencordIfReady()
        activity.scheduleBootVerify("test")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val probeCalls = shadow.evalCalls.count { (script, _) -> script.startsWith("(document.readyState") }
        val bootCalls = shadow.evalCalls.count { (script, _) -> script.startsWith("(function(){try{") }
        assertEquals(0, probeCalls)
        assertEquals(0, bootCalls)
    }
}
