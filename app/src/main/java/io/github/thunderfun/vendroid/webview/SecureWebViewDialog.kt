package io.github.thunderfun.vendroid.webview

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.webkit.WebSettings
import android.webkit.WebView
import io.github.thunderfun.vendroid.MainActivity

/**
 * Helper for building a full-screen, black-themed dialog that hosts a
 * WebView configured with the app's secure defaults. The dialog is
 * cancelable and the WebView is destroyed when it is dismissed.
 *
 * Used by the QuickCSS, log-viewer, and firewall editors to avoid
 * duplicating the WebView/dialog wiring in each open path.
 */object SecureWebViewDialog {

    /** Configures [wv] with the app's standard secure WebView settings. */
    fun configure(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        wv.setBackgroundColor(Color.parseColor("#0f0f10"))
    }

    /**
     * Creates a full-screen black dialog hosting [wv]. The dialog is
     * cancelable and destroys [wv] on dismissal; [onDismiss] is invoked after
     * the WebView is destroyed (e.g. to reset an "active" flag). When
     * [activity] is a [MainActivity] the dialog is registered there, so a
     * destroy while showing dismisses it and runs this listener. Callers must
     * call [Dialog.show] and load the asset HTML themselves.
     */
    fun create(activity: Activity, wv: WebView, onDismiss: (() -> Unit)? = null): Dialog {
        val mainActivity = activity as? MainActivity
        return Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(wv)
            setCancelable(true)
            setOnDismissListener {
                try {
                    wv.destroy()
                } finally {
                    onDismiss?.invoke()
                    mainActivity?.unregisterDialog(this)
                }
            }
            mainActivity?.registerDialog(this)
        }
    }
}
