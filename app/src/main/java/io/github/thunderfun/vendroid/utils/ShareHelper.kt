package io.github.thunderfun.vendroid.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Helpers for launching share intents from an Activity. Centralizes ACTION_SEND
 * construction and the no-handler fallback so callers need not duplicate the
 * intent-building boilerplate.
 */
object ShareHelper {

    /**
     * Opens the system share sheet with [text] as the body and a fixed
     * "VendroidCarbon Logs" subject. Falls back to a toast when no app can
     * handle the share intent.
     */
    fun shareLogs(context: Context, text: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "VendroidCarbon Logs")
            }
            context.startActivity(Intent.createChooser(intent, "Share logs"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app available to share logs", Toast.LENGTH_SHORT).show()
        }
    }
}
