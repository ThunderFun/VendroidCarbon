package com.nin0dev.vendroid

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.nin0dev.vendroid.webview.HttpClient
import com.nin0dev.vendroid.webview.LinkHandler
import com.nin0dev.vendroid.webview.SecureWebViewDialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Pins the dialog registry's teardown contract:
 *
 *  - onDestroy dismisses tracked dialogs. The framework's post-onDestroy
 *    window cleanup removes views without running dismiss listeners, so
 *    without this the editor WebView (destroyed only in
 *    SecureWebViewDialog's dismiss listener) leaks with its renderer.
 *  - The forced teardown dismissal of the first-run warning must not
 *    re-enter finish() from its dismiss listener.
 *
 * ShadowDialog.dismiss() delegates to the real Dialog.dismiss(), so listener
 * dispatch is deferred to the looper exactly as in production. Every
 * post-teardown assertion idles the main looper first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [
        MainActivityWebViewLifecycleTest.DestroyAwareWebViewShadow::class,
        MainActivityWebViewLifecycleTest.ShadowWebViewFeature::class,
    ],
)
class DialogLifecycleTest {

    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        // Skip the first-run risk warning so onCreate installs the WebView.
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("riskWarningAccepted", true)
            .putBoolean("vendroid_rememberLastChannel", false)
            .commit()

        // Same convention as MainActivityVencordRuntimeLoadTest. Both runtimes
        // must be published before buildActivity so loadVencordRuntimes never
        // enqueues a real fetchVencord; a runtime-null boot takes the
        // immediate bundle-check path (bundleCheckDelayMs returns 0) and would
        // do real network I/O in a unit test.
        HttpClient.vencordDisabled = false
        HttpClient.setVencordRuntime("1;")
        HttpClient.setVencordMobileRuntime("2;")

        controller = Robolectric.buildActivity(MainActivity::class.java)
        activity = controller.setup().get()
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun editorShadow(wv: WebView): MainActivityWebViewLifecycleTest.DestroyAwareWebViewShadow =
        shadowOf(wv) as MainActivityWebViewLifecycleTest.DestroyAwareWebViewShadow

    @Test
    fun editorDialogOpen_activityDestroyed_dialogDismissedAndWebViewDestroyed() {
        val editorWv = WebView(activity)
        val dialog = SecureWebViewDialog.create(activity, editorWv) {}
        dialog.show()

        controller.destroy()
        idleMainLooper()

        assertFalse(dialog.isShowing)
        assertTrue(editorShadow(editorWv).destroyed)
    }

    @Test
    fun editorDialog_userDismiss_stillDestroysWebView() {
        val editorWv = WebView(activity)
        val dialog = SecureWebViewDialog.create(activity, editorWv) {}
        dialog.show()

        dialog.dismiss()
        idleMainLooper()

        assertTrue(editorShadow(editorWv).destroyed)

        // Teardown afterwards must not re-run anything destructive.
        controller.destroy()
        idleMainLooper()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun linkPopup_activityDestroyed_dialogDismissed() {
        LinkHandler(activity).showLinkPopup(Uri.parse("https://example.com/page"))
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog!!.isShowing)

        controller.destroy()
        idleMainLooper()

        assertFalse(dialog.isShowing)
    }

    @Test
    fun riskWarning_activityDestroyed_dialogDismissedWithoutFinishRecursion() {
        // setUp() accepted the warning for this test's application; flip it
        // back so this activity's onCreate takes the warning path.
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("riskWarningAccepted", false)
            .commit()

        val warnController = Robolectric.buildActivity(MainActivity::class.java)
        val warnActivity = warnController.setup().get()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog!!.isShowing)

        warnController.destroy()
        idleMainLooper()

        assertFalse(dialog.isShowing)
        assertTrue(warnActivity.isDestroyed)
    }
}
