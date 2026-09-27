package com.nin0dev.vendroid.webview

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nin0dev.vendroid.MainActivity
import com.nin0dev.vendroid.R
import java.lang.ref.WeakReference

/**
 * Shows a popup when the user taps an external link, offering to copy, open,
 * or share the URL. Gated by the `vendroid_confirmExternalLinks` toggle,
 * which mirrors the typing-indicator toggle pattern in [VWebviewClient]:
 * a `@Volatile` static field, read once at startup and live-updated from
 * `VencordNative.setBool`. Default is `true` (dialog shown).
 */
class LinkHandler(context: Context) {
    private val activityRef: WeakReference<Activity> =
        if (context is Activity) WeakReference(context) else WeakReference(null)

    /**
     * Shows the link popup, or opens directly when the toggle is off. Called
     * from [VWebviewClient.shouldOverrideUrlLoading] on the UI thread.
     */
    fun showLinkPopup(url: Uri) {
        val activity = activityRef.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) return

        val n = UrlNormalizer.normalize(url)

        // Restrict to browser schemes. WebView can invoke shouldOverrideUrlLoading
        // for arbitrary schemes; forwarding those to ACTION_VIEW could launch
        // unintended deep-link targets or leak content URIs.
        val scheme = n.scheme
        if (scheme != "http" && scheme != "https") {
            Toast.makeText(activity, R.string.link_blocked_scheme, Toast.LENGTH_SHORT).show()
            return
        }

        if (n.malformed) {
            Toast.makeText(activity, R.string.link_malformed, Toast.LENGTH_SHORT).show()
            return
        }

        if (!confirmExternalLinks) {
            openExternal(activity, n.launchUri)
            return
        }

        val labels = arrayOf(
            activity.getString(R.string.link_dialog_copy),
            activity.getString(R.string.link_dialog_open),
            activity.getString(R.string.link_dialog_share),
            activity.getString(R.string.link_dialog_cancel)
        )
        // AlertDialog renders only one of setMessage/setItems/setView; the URL
        // goes in the title so it stays visible alongside the item list.
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(n.displayString.ifEmpty { activity.getString(R.string.link_dialog_title) })
            .setCancelable(true)
            .setItems(labels) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> copyToClipboard(activity, n.clipboardString)
                    1 -> openExternal(activity, n.launchUri)
                    2 -> shareLink(activity, n.clipboardString)
                    // 3 = Cancel
                }
            }
            .create()
        // Tracked so activity teardown dismisses it instead of leaking the window.
        val main = activity as? MainActivity
        if (main != null) {
            main.registerDialog(dialog)
            dialog.setOnDismissListener { main.unregisterDialog(dialog) }
        }
        dialog.show()
    }

    private fun openExternal(activity: Activity, url: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, url)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Always show a chooser. Counting resolvers is unreliable under
        // Android 11+ visibility rules and risks auto-launching a spoofing
        // app that registered for this host.
        val chooser = Intent.createChooser(intent, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            // No browser installed; shouldOverrideUrlLoading already blocked in-WebView nav.
        }
    }

    private fun copyToClipboard(activity: Activity, url: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("link", url))
        Toast.makeText(activity, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLink(activity: Activity, url: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No share targets available.
        }
    }

    companion object {
        // Mirrors blockTypingIndicator in VWebviewClient: @Volatile field read
        // on the UI thread, set at startup and live-updated from VencordNative.
        @Volatile
        private var confirmExternalLinks = true

        /** Updates the confirm-external-links toggle. */
        fun updateConfirmExternalLinks(value: Boolean) {
            confirmExternalLinks = value
        }
    }
}