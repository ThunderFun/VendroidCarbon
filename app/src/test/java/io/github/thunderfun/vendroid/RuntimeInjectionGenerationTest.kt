package io.github.thunderfun.vendroid

import android.content.Context
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import io.github.thunderfun.vendroid.webview.HttpClient
import io.github.thunderfun.vendroid.webview.VencordNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Pins the document-binding contract of MainActivity.injectVencordAttempt's
 * runtime probe:
 *
 *  1. A probe result delivered after a main-frame navigation must not reach
 *     RuntimeInjector.injectViaBridge, whose first eval carries the
 *     process-lifetime capability token. The callback is bound to the
 *     document that scheduled it via `documentGeneration` and re-reads
 *     `wv.url` as a second, commit-level guard.
 *  2. Only explicit probe outcomes are acted on. A null or unexpected
 *     renderer value fails closed: no injection, no reload.
 *  3. The bootstrap's renderer-side origin guard and RuntimeInjector's live
 *     origin check are the defense-in-depth layers behind the generation
 *     guard.
 *
 * Like the other MainActivity suites, this relies on the sentinel runtimes
 * and the app-origin shell URL set up in [setUp]. The positive control
 * (currentDocumentProbe_injectsRuntime) keeps the guards from degenerating
 * into always-bail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [
        MainActivityWebViewLifecycleTest.DestroyAwareWebViewShadow::class,
        MainActivityWebViewLifecycleTest.ShadowWebViewFeature::class,
    ],
)
class RuntimeInjectionGenerationTest {

    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var wv: WebView
    private lateinit var shadow: MainActivityWebViewLifecycleTest.DestroyAwareWebViewShadow

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

        // Publish sentinel runtimes so injection is observable.
        HttpClient.vencordDisabled = false
        HttpClient.setVencordRuntime("1;")
        HttpClient.setVencordMobileRuntime("2;")

        controller = Robolectric.buildActivity(MainActivity::class.java)
        activity = controller.setup().get()
        wv = activity.findViewById(R.id.webview)
        @Suppress("UNCHECKED_CAST")
        shadow = org.robolectric.Shadows.shadowOf(wv)
            as MainActivityWebViewLifecycleTest.DestroyAwareWebViewShadow

        // Both fixes read wv.url, so this suite depends on ShadowWebView
        // implementing getUrl() as "the last loadUrl()". The prefs above force
        // resolveInitialUrl through loadAppShell(), so the shell load must be
        // this exact URL. Fail loudly here if the Robolectric version stops
        // tracking it; the destroy-aware shadow then needs a getUrl override.
        assertEquals("https://discord.com/app", wv.url)

        // injectVencordIfReady only injects on Discord app origins.
        activity.currentUrlForBridge = "https://discord.com/app"
        activity.currentHostForBridge = "discord.com"
    }

    /** Schedules the runtime probe and returns its callback. */
    private fun scheduleProbe(): ValueCallback<String> {
        activity.injectVencordIfReady()
        val callback = probeCallback
        assertNotNull("injectVencordIfReady must schedule the probe", callback)
        return callback!!
    }

    /** Models onPageStarted for a new main-frame navigation. */
    private fun simulateNavigation(url: String) {
        wv.loadUrl(url) // ShadowWebView.getUrl() now reports the new URL
        activity.currentUrlForBridge = url
        activity.currentHostForBridge = Uri.parse(url).host
        activity.documentGeneration += 1
    }

    /** Models a renderer-side commit whose onPageStarted has not been delivered yet. */
    private fun simulateCommitWithoutOnPageStarted(url: String) {
        wv.loadUrl(url) // ShadowWebView.getUrl() now reports the new URL
        activity.currentUrlForBridge = url
        activity.currentHostForBridge = Uri.parse(url).host
        // Generation unchanged on purpose: only the live read can reject this.
    }

    @Test
    fun staleProbeAfterNavigation_doesNotInject() {
        // Regression test for the finding: the probe was scheduled on an app
        // document, then a navigation replaced it before the result arrived.
        // The old "N" result must not run the token-bearing injection chain.
        val callback = scheduleProbe()
        simulateNavigation("https://activity.discordsays.com/")
        val evalCount = shadow.evalCalls.size

        callback.onReceiveValue("\"N\"")

        assertEquals("stale probe must not schedule more evals", evalCount, shadow.evalCalls.size)
        assertEquals(0, shadow.reloadCount)
    }

    @Test
    fun staleProbeMissedInjection_doesNotReload() {
        val callback = scheduleProbe()
        activity.missedInjection = true
        simulateNavigation("https://activity.discordsays.com/")

        callback.onReceiveValue("\"N\"")

        // The guard runs before the reload branch; the replacement page's own
        // startup flow owns the flag now.
        assertEquals(0, shadow.reloadCount)
        assertTrue("a stale callback must not consume the flag", activity.missedInjection)
    }

    @Test
    fun committedDocumentWithoutOnPageStarted_doesNotInject() {
        // The generation is unchanged, so only the live-URL read can reject
        // this. Without the test, deleting the live read leaves the suite
        // green.
        val callback = scheduleProbe()
        simulateCommitWithoutOnPageStarted("https://activity.discordsays.com/")
        val evalCount = shadow.evalCalls.size

        callback.onReceiveValue("\"N\"")

        assertEquals(evalCount, shadow.evalCalls.size)
        assertEquals(0, shadow.reloadCount)
    }

    @Test
    fun unexpectedProbeValue_doesNotInject() {
        val callback = scheduleProbe()
        val evalCount = shadow.evalCalls.size

        callback.onReceiveValue("\"wat\"")

        // Fail closed: an off-protocol renderer value is not "N".
        assertEquals(evalCount, shadow.evalCalls.size)
        assertEquals(0, shadow.reloadCount)
    }

    @Test
    fun currentDocumentProbe_injectsRuntime() {
        // Positive control: a current-document "N" still runs the full
        // bootstrap -> prelude + runtime -> mobile-runtime chain.
        val callback = scheduleProbe()
        val evalCount = shadow.evalCalls.size

        callback.onReceiveValue("\"N\"")

        val injected = shadow.evalCalls.drop(evalCount).map { it.first }
        assertTrue(
            "the token-bearing bootstrap must run",
            injected.any { it.contains("window.__vendroidBootstrapped") }
        )
        assertTrue("the main runtime eval must run", injected.any { it.contains("1;") })
        assertTrue("the mobile runtime eval must run", injected.any { it.contains("2;") })
    }

    @Test
    fun bootstrapJs_containsOriginGuard() {
        val js = VencordNative.bridgeBootstrapJs()

        assertTrue(js.contains("location.protocol!=='https:'"))
        assertTrue(js.contains("location.port!==''&&location.port!=='443'"))
        assertTrue("the host list must be embedded", js.contains("\"discord.com\""))
        assertTrue(
            "the host walk must compare with ===; page scripts can patch prototypes",
            js.contains("vdeHosts[vdeI]===location.hostname")
        )
        assertFalse("the guard must not call patchable Array.prototype.indexOf", js.contains("vdeHosts.indexOf"))
    }
}
