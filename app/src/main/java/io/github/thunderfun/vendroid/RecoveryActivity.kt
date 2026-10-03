package io.github.thunderfun.vendroid

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import io.github.thunderfun.vendroid.utils.SettingKeys
import io.github.thunderfun.vendroid.utils.ShareHelper
import io.github.thunderfun.vendroid.utils.VDELog
import io.github.thunderfun.vendroid.webview.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecoveryActivity : AppCompatActivity() {

    companion object {
        /**
         * When true, onCreate runs the restart sequence (kill :web, start
         * MainActivity) without building the recovery UI. Used by
         * VencordNative.restartApp: the WebView bridge runs in :web, which
         * cannot kill itself without racing its own singleTask relaunch, so
         * the switcher's "Restart now" hands off to this activity, whose
         * process survives the kill.
         */
        const val EXTRA_RELAUNCH_MAIN = "io.github.thunderfun.vendroid.RELAUNCH_MAIN"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Trampoline: restart immediately, no UI.
        if (intent?.getBooleanExtra(EXTRA_RELAUNCH_MAIN, false) == true) {
            restartWith { }
            return
        }

        setContentView(R.layout.activity_recovery)

        // Show last-boot state from the boot-verify probe, if available.
        findViewById<TextView>(R.id.last_boot_state).apply {
            val state = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(MainActivity.PREF_LAST_BOOT_STATE, null)
            if (state.isNullOrEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = "Last boot: $state"
            }
        }

        findViewById<MaterialCardView>(R.id.start_normally).setOnClickListener {
            it.isClickable = false
            // Clear all three one-shot recovery flags so "Start normally"
            // boots clean and re-enables themes and plugins.
            restartWith { e ->
                e.putBoolean(SettingKeys.KEY_SAFE_MODE, false)
                e.putBoolean(SettingKeys.KEY_DISABLE_THEMES, false)
                e.putBoolean(SettingKeys.KEY_DISABLE_PLUGINS, false)
            }
        }
        findViewById<MaterialCardView>(R.id.safe_mode).setOnClickListener {
            it.isClickable = false
            restartWith { e -> e.putBoolean(SettingKeys.KEY_SAFE_MODE, true) }
        }
        // One-shot: the next boot suppresses user themes (Vencord still
        // loads) so the user can remove the broken theme in Vencord's Themes
        // panel; themes return on the next normal start.
        findViewById<MaterialCardView>(R.id.disable_themes).setOnClickListener {
            it.isClickable = false
            restartWith { e -> e.putBoolean(SettingKeys.KEY_DISABLE_THEMES, true) }
        }
        // One-shot: the next boot starts only required plugins (Vencord still
        // loads) so the user can disable the broken plugin in Vencord's
        // Plugins panel; plugins return on the next normal start.
        findViewById<MaterialCardView>(R.id.disable_plugins).setOnClickListener {
            it.isClickable = false
            restartWith { e -> e.putBoolean(SettingKeys.KEY_DISABLE_PLUGINS, true) }
        }
        findViewById<MaterialCardView>(R.id.force_update).setOnClickListener {
            it.isClickable = false
            restartWith { e -> e.putInt(HttpClient.PREF_LAST_BUNDLE_UPDATE, 0) }
        }
        findViewById<MaterialCardView>(R.id.view_logs).setOnClickListener {
            it.isClickable = false
            val logCard = it
            val logFile = File(filesDir, "vde_logs.txt")
            val prevLogFile = File(filesDir, "vde_logs.prev.txt")
            // Read the logs off the main thread to avoid jank/ANR on large
            // files. Defensive read: a corrupt/unreadable log file must not
            // crash the recovery screen. Prefer VDELog's own sink so the same
            // content source is used as the in-app viewer, falling back to a
            // message. Show the previous (crashed) session first, then the
            // current one, since VDELog rotates (not truncates) the file on
            // startup now.
            lifecycleScope.launch(Dispatchers.IO) {
                val text = try {
                    buildString {
                        if (prevLogFile.exists()) {
                            append("=== Previous session ===\n")
                            append(prevLogFile.readText())
                            append('\n')
                        }
                        if (logFile.exists()) {
                            append("=== Current session ===\n")
                            append(VDELog.getLogFileContents())
                        }
                    }.ifEmpty { "No logs available." }
                } catch (_: Exception) {
                    "Failed to read logs."
                }
                withContext(Dispatchers.Main) {
                    showLogDialog(text).setOnDismissListener { logCard.isClickable = true }
                }
            }
        }

    }

    private fun showLogDialog(text: String): AlertDialog {
        val scrollView = ScrollView(this).apply {
            setPadding(48, 32, 48, 32)
        }
        val textView = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        scrollView.addView(textView)
        return AlertDialog.Builder(this)
            .setTitle("VendroidCarbon Logs")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("VDE Logs", text))
            }
            .setNegativeButton("Share") { _, _ ->
                ShareHelper.shareLogs(this, text)
            }
            .show()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /**
     * Shared restart sequence for the recovery buttons. Kills the :web
     * process, applies [edit] to the settings prefs, then restarts
     * MainActivity.
     *
     * Kill before write, commit() rather than apply(). Both deliberate.
     * Killing first means a warm :web process can't flush a stale apply()
     * over the new value. commit() is synchronous, so the change is on disk
     * before MainActivity restarts. Full rationale in [killWebProcess].
     */
    private fun restartWith(edit: (SharedPreferences.Editor) -> Unit) {
        killWebProcess()
        val sPrefs = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = sPrefs.edit()
        edit(editor)
        editor.commit()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * Terminates the `:web` process so MainActivity cold-starts and re-reads
     * the settings prefs from disk. Without this, a warm :web process keeps
     * its stale in-memory SharedPreferences and never honors recovery changes
     * (the singleTask activity also gets onNewIntent, not onCreate).
     *
     * Callers must invoke this BEFORE committing any pref write. A queued
     * apply() flush from :web rewrites the full XML from its stale in-memory
     * map and would clobber a preceding commit; killing first closes that
     * race, and commit() being synchronous makes the write durable before
     * MainActivity restarts.
     */
    private fun killWebProcess() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // On API 22+ runningAppProcesses() returns only this app's own
            // processes, which is exactly what we need here.
            am.runningAppProcesses?.forEach { proc ->
                if (proc.processName == "$packageName:web") {
                    Process.killProcess(proc.pid)
                }
            }
        } catch (t: Throwable) {
            VDELog.e("Recovery", "killWebProcess failed", t)
        }
    }
}
