package com.nin0dev.vendroid.webview

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.ConsoleMessage.MessageLevel
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nin0dev.vendroid.BuildConfig
import com.nin0dev.vendroid.MainActivity
import com.nin0dev.vendroid.R
import com.nin0dev.vendroid.utils.VDELog
import java.lang.ref.WeakReference

class VChromeClient(activity: MainActivity) : WebChromeClient() {
    private val activityRef: WeakReference<MainActivity> = WeakReference(activity)

    companion object {
        // Per-page cap for unprefixed ERROR console capture from Discord
        // app origins. Reset by VWebviewClient.onPageStarted.
        private val consoleErrorQuota = java.util.concurrent.atomic.AtomicInteger(30)
        private val seenConsoleErrors: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()

        @JvmStatic
        fun resetPageErrorQuota() {
            consoleErrorQuota.set(30)
            seenConsoleErrors.clear()
        }
    }
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var webview: WebView
    private var cachedActivityRef: MainActivity? = null
    private var insetsController: WindowInsetsControllerCompat? = null
    val isFullscreen: Boolean get() = customView != null

    private fun ensureViewsInitialized(activity: MainActivity) {
        if (cachedActivityRef !== activity) {
            fullscreenContainer = activity.findViewById(R.id.fullscreen_container)
            webview = activity.findViewById(R.id.webview)
            cachedActivityRef = activity
        }
    }

    private fun getInsetsController(activity: MainActivity): WindowInsetsControllerCompat {
        return insetsController ?: WindowInsetsControllerCompat(activity.window, activity.window.decorView).also {
            insetsController = it
        }
    }

    private val transparentPoster: Bitmap by lazy {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { it.eraseColor(0) }
    }

    override fun getDefaultVideoPoster(): Bitmap? = transparentPoster

    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
        val message = msg.message()

        // Route [Vendroid] messages to VDELog in all builds (not just DEBUG)
        // to capture vencord_mobile.js diagnostics (webpack capture, plugin
        // init, Slate fixes, GIF picker) for the in-app log viewer. Only accept
        // them when the committed main frame is a Discord app-origin host, so a
        // third-party Activity/iframe page cannot forge trusted diagnostics
        // into the shareable log.
        if (message.startsWith("[Vendroid]") || message.startsWith("[VDE]")) {
            val host = activityRef.get()?.currentHostForBridge
            if (host == null || !com.nin0dev.vendroid.utils.Constants.isDiscordAppOrigin(host)) {
                // Drop without persisting: emitted by a non-app-origin page.
                return true
            }
            val level = when (msg.messageLevel()) {
                MessageLevel.ERROR -> VDELog.Level.ERROR
                MessageLevel.WARNING -> VDELog.Level.WARN
                else -> VDELog.Level.INFO
            }
            VDELog.log(level, "JS", message)
            // Do not also call Logger. That would double-log to VDELog.
            return true
        }

        // Non-Vendroid messages: logcat only in debug builds to avoid
        // flooding VDELog with Discord's internal JS noise. Unprefixed
        // ERROR messages from Discord app origins are persisted (capped,
        // deduped) since they signal bundle boot failures.
        if (msg.messageLevel() == MessageLevel.ERROR) {
            val host = activityRef.get()?.currentHostForBridge
            if (host != null && com.nin0dev.vendroid.utils.Constants.isDiscordAppOrigin(host)) {
                val source = msg.sourceId() ?: ""
                val titled = "$message @ ${UrlNormalizer.redactForLog(source)}:${msg.lineNumber()}"
                val dedupeKey = titled.take(80)
                if (seenConsoleErrors.add(dedupeKey) && consoleErrorQuota.getAndDecrement() > 0) {
                    VDELog.log(VDELog.Level.ERROR, "JS", titled.take(500))
                }
            }
        }
        if (BuildConfig.DEBUG) {
            when (msg.messageLevel()) {
                MessageLevel.ERROR -> Log.e("Vendroid", "[JS] $message @ ${msg.lineNumber()}")
                MessageLevel.WARNING -> Log.w("Vendroid", "[JS] $message @ ${msg.lineNumber()}")
                else -> {}
            }
        }
        return true
    }

    /**
     * Routes a page capture request (getUserMedia) through the Android
     * runtime permission flow. Only audio is granted; camera, MIDI and
     * protected media are denied. The grant itself happens in
     * [MainActivity.requestVoicePermissions] once RECORD_AUDIO is held, since
     * the WebView rejects getUserMedia if the app grants capture without it.
     */
    override fun onPermissionRequest(request: PermissionRequest) {
        val activity = activityRef.get()
        if (activity == null) {
            VDELog.w("Voice", "Capture request denied: activity gone")
            request.deny()
            return
        }
        if (!request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            VDELog.w(
                "Voice",
                "Capture request denied: resources=${request.resources.joinToString(",")}"
            )
            request.deny()
            return
        }
        // request.origin is a Uri; redact its query/fragment before logging.
        VDELog.i(
            "Voice",
            "Audio capture requested by ${UrlNormalizer.redactForLog(request.origin?.toString() ?: "?")}"
        )
        activity.requestVoicePermissions(request, request.resources)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        val activity = activityRef.get() ?: return false

        val oldCallback = activity.filePathCallback
        activity.filePathCallback = filePathCallback
        oldCallback?.onReceiveValue(null)

        return try {
            val intent = fileChooserParams.createIntent()
            activity.fileChooserLauncher.launch(intent)
            true
        } catch (e: Exception) {
            // Deliver a cancel via the callback or return false, never both.
            // False cancels the request through Chromium's internal
            // uploadFileCallback, and that callback's duplicate-delivery
            // guard throws "Duplicate showFileChooser result" inline on the
            // UI thread.
            activity.filePathCallback = null
            filePathCallback.onReceiveValue(null)
            VDELog.log(VDELog.Level.WARN, "FileChooser", "Failed to launch file chooser intent", e)
            true
        }
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (customView != null) {
            // Already fullscreen: reject the NEW request only. Telling the old
            // callback it was hidden while its view is still on screen would
            // desync the WebView's fullscreen state.
            callback.onCustomViewHidden()
            return
        }
        val activity = activityRef.get() ?: run {
            // Reject the request so the WebView doesn't leave it pending.
            callback.onCustomViewHidden()
            return
        }
        ensureViewsInitialized(activity)
        customView = view
        customViewCallback = callback
        // Hardware layer on the fullscreen view lets the compositor overlay
        // the video surface directly without extra composition passes.
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        fullscreenContainer.addView(view)
        fullscreenContainer.visibility = View.VISIBLE
        webview.visibility = View.GONE
        val controller = getInsetsController(activity)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        // Bar colors are owned by MainActivity.barColors. Publish the
        // state change and let it derive the colors.
        activity.barColors.publishVideoFullscreen(true)
    }

    override fun onHideCustomView() {
        if (customView == null) return
        val activity = activityRef.get()
        val localCustomView = customView
        val localCallback = customViewCallback

        customView = null
        customViewCallback = null

        if (activity == null) {
            localCallback?.onCustomViewHidden()
            return
        }

        // Publish the fullscreen exit before view teardown. Every remaining
        // exit path must converge the bar-color state, and the reapply only
        // touches window attributes, not the view hierarchy.
        activity.barColors.publishVideoFullscreen(false)

        if (!::fullscreenContainer.isInitialized || !::webview.isInitialized) {
            localCallback?.onCustomViewHidden()
            return
        }

        fullscreenContainer.visibility = View.GONE
        // Restore to default layer type: the view is being removed, so
        // the GPU texture it held can be released.
        localCustomView?.setLayerType(View.LAYER_TYPE_NONE, null)
        fullscreenContainer.removeView(localCustomView)
        webview.visibility = View.VISIBLE
        val controller = getInsetsController(activity)
        controller.show(WindowInsetsCompat.Type.navigationBars())
        localCallback?.onCustomViewHidden()
    }

    fun hideCustomView() {
        if (customView != null) {
            onHideCustomView()
        }
    }
}
