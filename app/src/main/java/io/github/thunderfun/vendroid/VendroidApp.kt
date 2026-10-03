package io.github.thunderfun.vendroid

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.core.content.ContextCompat
import io.github.thunderfun.vendroid.R
import io.github.thunderfun.vendroid.webview.HttpClient
import io.github.thunderfun.vendroid.webview.MainFrameDiskCache
import io.github.thunderfun.vendroid.webview.VWebviewClient
import io.github.thunderfun.vendroid.webview.VencordRuntimeLoader
import io.github.thunderfun.vendroid.webview.clearBundleIdentityKeys
import io.github.thunderfun.vendroid.utils.Constants
import io.github.thunderfun.vendroid.utils.FirewallConfig
import io.github.thunderfun.vendroid.utils.SettingKeys
import io.github.thunderfun.vendroid.utils.VDELog
import io.github.thunderfun.vendroid.utils.getBooleanSafe
import io.github.thunderfun.vendroid.utils.getStringSafe
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class VendroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val isWebProcess = getCurrentProcessName().endsWith(":web")

        // Initialize logging in every process so the file-backed sink is
        // available anywhere (e.g. RecoveryActivity reads vde_logs.txt). Only
        // the :web process writes/rotates the file; others read-only.
        VDELog.init(applicationContext, persistToFile = isWebProcess)
        VDELog.i("VDE", "App started (PID=${Process.myPid()})")

        // Startup runs as an ordered list of small steps; each step's full
        // contract is on the step itself. The heals must run synchronously
        // in every process before any reader, and the :web steps start
        // background work in the order shown.
        val bootPrefs = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)

        // 1. Self-heal a type-poisoned clientMod (see healTypePoisonedClientMod).
        healTypePoisonedClientMod(bootPrefs)

        // 2. Self-heal an unusable vencordLocation (see
        // healUnusableVencordLocation below). Same placement contract as the
        // clientMod heal above: synchronous, every process, before any
        // reader, so no preload or fetch observes the stale value.
        healUnusableVencordLocation(
            bootPrefs,
            HttpClient.vendroidFile(filesDir)
        )

        // 3. Log app + WebView versions for incident reports.
        logSessionInfo()

        if (isWebProcess) {
            // 4. Firewall config, WebView data-dir suffix, renderer prewarm.
            // Returns the first-run risk-warning consent, which also gates
            // the CSS prefetch in step 9, so the pref is read exactly once.
            val riskAccepted = initWebProcess(bootPrefs)

            // 5. One-time migration of CSS cache entries into the dedicated
            // css_cache prefs file.
            startCssCacheMigration()

            // 6. Read safe mode and raise the kill switch when it is set,
            // so this process never publishes a runtime. The flag also
            // gates the CSS prefetch.
            val safeMode = applySafeModeGate()

            // 7. Read the one-shot disableThemes flag and raise the user
            // theme session gate; Vencord keeps loading
            // (see HttpClient.userCssDisabled).
            applyUserCssGate()

            // 8. Read the one-shot disablePlugins flag and raise the plugin
            // session gate; Vencord keeps loading
            // (see HttpClient.userPluginsDisabled).
            applyUserPluginsGate()

            // 9. Pre-load the Vencord runtimes on a background thread.
            preloadVencordRuntimes()

            // 10. Pre-fetch the Vencord CSS files, the only startup work that
            // touches the network; gated on first-run consent (step 4) and
            // safe mode (step 6).
            if (riskAccepted && !safeMode) prefetchVencordCss()

            // 11. Sweep stale CSS cache entries; local prefs only, no network.
            sweepStaleCssCacheEntries(bootPrefs)

            // 12. Warm up the Chromium cookie DB.
            warmUpCookieManager()

            // 13. Preload the persisted main-frame shell off the UI thread.
            preloadMainFrameDiskCache()
        }
    }

    /**
     * App-scope executor for the :web cold-start steps in onCreate. The cap
     * of 4 bounds parallel cold-start IO while still letting the queued
     * cookie-DB and disk-cache warmups start during boot. Idle core threads
     * exit after 30s, so the pool holds no threads outside startup. The
     * :main process never submits, so it never spawns pool threads.
     *
     * core must equal max here. With core=0 and an unbounded queue the pool
     * never grows past one worker, serializing every task.
     */
    private val startupExecutor = ThreadPoolExecutor(
        4, 4, 30L, TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { r -> Thread(r, "vde-startup") }
    ).apply { allowCoreThreadTimeOut(true) }

    /**
     * Runs [body] on [startupExecutor] and renames the worker for the task's
     * duration, so thread dumps and ANR traces name the task actually
     * running. The finally reset accounts for pool-thread reuse. Tasks keep
     * their own try/catch; this wrapper swallows nothing.
     */
    private fun launchStartupTask(task: String, body: () -> Unit) {
        startupExecutor.execute {
            Thread.currentThread().name = "vde-startup-$task"
            try {
                body()
            } finally {
                Thread.currentThread().name = "vde-startup"
            }
        }
    }

    /**
     * Self-heal a type-poisoned clientMod (a Boolean persisted by the
     * setBool bridge bug of older builds). The startup fetch path read it
     * with an unguarded getString (HttpClient.resolveBundleLocation),
     * which crash-looped the process on every cold start; no recovery
     * option cleared the key. Removing it restores the "vencord" default.
     * Runs synchronously in every process before any reader; that
     * ordering also neutralizes the stale-map resurrection race: a warm
     * process can flush the poison back to disk, so each boot re-heals
     * before its first read. Precedent: evictStaleCssCache and the CSS
     * prefetch repair poisoned entries in place rather than crashing.
     */
    private fun healTypePoisonedClientMod(prefs: SharedPreferences) {
        try {
            prefs.getString(SettingKeys.KEY_CLIENT_MOD, null)
        } catch (e: ClassCastException) {
            val removed = try {
                prefs.edit().remove(SettingKeys.KEY_CLIENT_MOD).commit()
            } catch (t: Throwable) {
                VDELog.e("VDE", "Could not remove poisoned clientMod key", t)
                false
            }
            if (removed) {
                VDELog.w("VDE", "Removed type-poisoned clientMod key (legacy setBool bridge bug)")
            } else {
                VDELog.w("VDE", "clientMod heal did not persist; retrying next boot")
            }
        }
    }

    /** Logs app + WebView versions for incident reports. */
    private fun logSessionInfo() {
        try {
            @Suppress("NewApi")
            val wvPkg = WebView.getCurrentWebViewPackage()
            VDELog.i(
                "VDE",
                "Session: app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                    "webview=${wvPkg?.versionName ?: "unknown"}"
            )
        } catch (_: Throwable) {
            VDELog.i("VDE", "Session: app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) webview=unknown")
        }
    }

    /**
     * :web process startup, in order: initialize the firewall config before
     * any WebView request can fire, set the per-process WebView data-dir
     * suffix, then prewarm the renderer.
     *
     * @return whether the user accepted the first-run risk warning. onCreate
     *   reuses it to gate the CSS prefetch, so the pref is read exactly once
     *   per boot.
     */
    private fun initWebProcess(bootPrefs: SharedPreferences): Boolean {
        // Initialize the firewall config before any WebView request can
        // fire; shouldInterceptRequest() reads it via
        // Constants.isAllowedDomain().
        FirewallConfig.init(applicationContext)
        VDELog.i("VDE", "Firewall config initialized")

        // On Android P+, each non-default process needs a unique WebView
        // data-directory suffix or WebView creation crashes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("web")
        }

        // Pre-warm the Chromium renderer by creating a WebView and keeping
        // it alive; MainActivity reuses it, making WebView init ~50-100ms
        // faster (vs ~200-500ms cold). Only do this once the user has
        // accepted the first-run risk warning; otherwise MainActivity
        // creates its own WebView.
        // getBooleanSafe: Application.onCreate runs before MainActivity, so
        // a poisoned value would crash-loop :web cold start.
        val riskAccepted = bootPrefs.getBooleanSafe(SettingKeys.KEY_RISK_WARNING_ACCEPTED, false) {
            VDELog.w("VDE", "riskWarningAccepted type-poisoned; skipping prewarm: $it")
        }
        if (riskAccepted) {
            try {
                prewarmedWebView = WebView(this).apply {
                    // Resource read, not a literal, so a rebrand edits
                    // resources, not code. The color is transient:
                    // MainActivity re-tints on install, before the WebView
                    // first draws.
                    setBackgroundColor(
                        ContextCompat.getColor(this@VendroidApp, R.color.status_bar_color)
                    )
                }
            } catch (e: Exception) {
                VDELog.e("VDE", "Failed to create prewarmed WebView", e)
            }
        }
        return riskAccepted
    }

    /**
     * One-time migration: move CSS cache entries from the shared
     * "settings" prefs into a dedicated "css_cache" file so the settings
     * file stays small (faster cold-start parse).
     */
    private fun startCssCacheMigration() {
        launchStartupTask("css-migration") {
            try {
                val settingsPrefs = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)
                if (settingsPrefs.getBoolean(SettingKeys.KEY_CSS_CACHE_MIGRATED, false)) return@launchStartupTask
                val cssPrefs = getSharedPreferences(SettingKeys.CSS_CACHE_PREFS_NAME, Context.MODE_PRIVATE)
                val editor = cssPrefs.edit()
                val settingsEditor = settingsPrefs.edit()
                var migrated = false
                for ((key, value) in settingsPrefs.all) {
                    if (!key.startsWith("css_cache_")) continue
                    when (value) {
                        is String -> { editor.putString(key, value); migrated = true }
                        is Long -> { editor.putLong(key, value); migrated = true }
                    }
                    settingsEditor.remove(key)
                }
                settingsEditor.putBoolean(SettingKeys.KEY_CSS_CACHE_MIGRATED, true)
                // Apply the copy before the removals+flag. A death between
                // the two flushes would otherwise persist the flag without
                // the copied entries; in this order it leaves duplicates
                // and the migration re-runs.
                if (migrated) editor.apply()
                settingsEditor.apply()
            } catch (ex: Exception) {
                VDELog.e("VDE", "CSS cache migration failed", ex)
            }
        }
    }

    /**
     * Safe mode: raise the kill switch and never publish a runtime in
     * this process. Read synchronously; Application.onCreate always
     * precedes Activity.onCreate here, so the read cannot race the
     * one-shot pref reset in MainActivity.
     *
     * getBooleanSafe: the first safeMode reader in the process, so a
     * String-typed key (restored or hand-edited XML) would crash-loop
     * :web cold start here, before MainActivity's guarded reads run. The
     * prefs-file lookup stays unguarded. onCreate already fetched the
     * same file into bootPrefs, so this call gets the cached instance.
     * TRUE is the fail-safe fallback and only applies to a wrong
     * type: the session runs without Vencord, and MainActivity's
     * one-shot reset overwrites the poison with a real Boolean.
     *
     * @return the safe-mode flag, for callers that gate further work on it.
     */
    private fun applySafeModeGate(): Boolean {
        val safeMode = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .getBooleanSafe(SettingKeys.KEY_SAFE_MODE, false, poisonDefault = true) {
                VDELog.w("VDE", "safeMode type-poisoned; failing safe: $it")
            }
        if (safeMode) {
            HttpClient.vencordDisabled = true
            VDELog.w("VDE", "Safe mode: skipping Vencord runtime preload")
        }
        return safeMode
    }

    /**
     * Recovery "Disable themes": raise the session switch that suppresses
     * user theme CSS while Vencord keeps loading. Read synchronously and
     * before any WebView request (same ordering rationale as
     * [applySafeModeGate]).
     *
     * The pref is NOT reset here; MainActivity owns the one-shot reset
     * (mirroring safe mode), so the toast and the type-poison heal happen
     * once per launch, on the UI path.
     *
     * poisonDefault = true: a String-typed key (restored or hand-edited XML)
     * reads as "suppressed" instead of crash-looping :web cold start.
     * Suppression is the benign direction, and MainActivity's reset
     * overwrites the poison with a real Boolean.
     */
    private fun applyUserCssGate() {
        val disabled = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .getBooleanSafe(SettingKeys.KEY_DISABLE_THEMES, false, poisonDefault = true) {
                VDELog.w("VDE", "disableThemes type-poisoned; failing safe: $it")
            }
        if (disabled) {
            HttpClient.userCssDisabled = true
            VDELog.w("VDE", "User themes disabled for this session (recovery one-shot)")
        }
    }

    /**
     * Recovery "Disable plugins": raise the session switch that makes
     * Vencord's plugin manager skip non-required plugins. Read
     * synchronously before any WebView request, same ordering rationale as
     * [applyUserCssGate].
     *
     * MainActivity owns the one-shot reset, so the toast and the type-poison
     * heal happen once per launch on the UI path. poisonDefault = true: a
     * String-typed key reads as "suppressed" instead of crash-looping :web.
     * A wrong read only skips plugins for one session; MainActivity's reset
     * rewrites the key as a Boolean.
     */
    private fun applyUserPluginsGate() {
        val disabled = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .getBooleanSafe(SettingKeys.KEY_DISABLE_PLUGINS, false, poisonDefault = true) {
                VDELog.w("VDE", "disablePlugins type-poisoned; failing safe: $it")
            }
        if (disabled) {
            HttpClient.userPluginsDisabled = true
            VDELog.w("VDE", "User plugins disabled for this session (recovery one-shot)")
        }
    }

    /**
     * Pre-load the Vencord runtimes on a background thread so they are
     * in memory by the time MainActivity.onCreate() runs. The load-and-publish
     * sequence lives in [VencordRuntimeLoader]; this wrapper supplies
     * threading, logging, and Application-scope resources/filesDir, which
     * stay valid for the process lifetime.
     */
    private fun preloadVencordRuntimes() {
        launchStartupTask("runtime-preload") {
            val sPrefs = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)
            try {
                val outcome = VencordRuntimeLoader.loadIfMissing(sPrefs, resources, filesDir)
                if (outcome.mobilePublished) {
                    VDELog.i("VDE", "VencordMobile runtime preloaded")
                }
                if (outcome.bundlePublished) {
                    VDELog.i(
                        "VDE",
                        "Vencord runtime preloaded (${HttpClient.vendroidFile(filesDir).length()} bytes)"
                    )
                }
                if (!outcome.publishedSomething) {
                    VDELog.i(
                        "VDE",
                        "Vencord runtime preload published nothing (safe mode or runtimes already present)"
                    )
                }
            } catch (ex: Exception) {
                VDELog.e("VDE", "Vencord preload failed: ${ex.message}", ex)
            }
        }
    }

    /**
     * Pre-fetch and cache the Vencord CSS files, injected by
     * vencord_mobile.js on every page load. Stashing them in the
     * dedicated css_cache prefs lets JS skip the network fetch without
     * churning the main settings XML. Gated on first-run consent so no
     * network activity phones home before the user accepts the risk
     * warning. (Other startup tasks are local-only: runtime preload,
     * cookie-DB warmup, and disk-cache preload touch no network.)
     * Safe mode skips this too: only vencord_mobile.js applies the
     * CSS, and it never loads, so the prefetch buys nothing.
     */
    private fun prefetchVencordCss() {
        launchStartupTask("css-prefetch") {
            try {
                val sPrefs = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)
                val cssPrefs = getSharedPreferences(SettingKeys.CSS_CACHE_PREFS_NAME, Context.MODE_PRIVATE)
                val isEquicord = sPrefs.getStringSafe(SettingKeys.KEY_CLIENT_MOD, "vencord") {
                    VDELog.w("VDE", "clientMod type-poisoned; CSS prefetch assumes vencord")
                } == "equicord"
                val cssUrls = listOf(
                    if (isEquicord) Constants.EQUICORD_CSS_URL else Constants.VENCORD_CSS_URL
                )
                val editor = cssPrefs.edit()
                val now = System.currentTimeMillis()
                for (url in cssUrls) {
                    prefetchOne(url, cssPrefs, editor, now)
                }
                editor.apply()
            } catch (ex: Exception) {
                VDELog.e("VDE", "CSS prefetch task failed", ex)
            }
        }
    }

    /**
     * Staleness-check and fetch one CSS URL into [editor]; the caller owns
     * the editor and its single apply(). [now] supplies both the freshness
     * comparison and the written `_ts` stamp, so every URL in one pass shares
     * one clock reading. Failures are logged and swallowed so one bad URL
     * cannot abort the remaining ones.
     */
    private fun prefetchOne(
        url: String,
        cssPrefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        now: Long
    ) {
        val key = CssCacheKeys.keyFor(url)
        VDELog.d("VDE", "Prefetching CSS: $url")
        try {
            // Staleness check and fetch share one try so a
            // type-poisoned entry (e.g. an attacker wrote a
            // Boolean under this key) cannot abort the
            // prefetch loop. A poisoned read is treated as
            // stale, refetched, and repaired by putString.
            val ts = try {
                cssPrefs.getLong("${key}_ts", 0)
            } catch (e: Exception) {
                0L
            }
            val missingOrPoisoned = try {
                cssPrefs.getString(key, null) == null
            } catch (e: Exception) {
                VDELog.w("VDE", "CSS cache key $key type-poisoned, refetching")
                true
            }
            if (!missingOrPoisoned && now - ts <= CssCacheKeys.PREFETCH_FRESHNESS_MS) {
                return
            }
            if (!url.startsWith("https://")) {
                VDELog.w("VDE", "CSS prefetch rejected non-HTTPS URL: $url")
                return
            }
            // GitHub release URLs 302-hop (github.com →
            // release-assets.githubusercontent.com) and the
            // shared client never auto-follows redirects, so
            // the prefetch resolves them itself and
            // re-validates each hop against the bundle host
            // allowlist. A bare 3xx check would silently
            // disable the CSS cache and push every page load
            // onto the slower in-page fetch.
            HttpClient.executeVencordGetResolvingRedirect(url, null).use { resp ->
                val code = resp.code
                if (code in 200..299) {
                    val css = HttpClient.readAsText(resp.body.byteStream())
                    editor.putString(key, css)
                    editor.putLong("${key}_ts", now)
                } else {
                    VDELog.w("VDE", "CSS prefetch HTTP $code from $url")
                }
            }
        } catch (ex: Exception) {
            // Stack traces only reach the log file.
            // getRecentLogs shows the message alone, so the
            // exception text is included here.
            VDELog.e("VDE", "CSS fetch failed for $url: ${ex.message ?: ex.javaClass.simpleName}", ex)
        }
    }

    /**
     * Sweep stale CSS cache entries. Keys are URL-hash addressed
     * (css_cache_vde_<url.hashCode>; see CssCacheKeys), so entries written
     * for retired hosts (vde-builds.nin0.dev) are never read again and would
     * linger forever. Remove everything the current URL set would
     * not produce; the prefetch (or the in-page fallback) repopulates.
     * Runs unconditionally, not gated on the risk warning or safe mode:
     * local-only prefs work, no network.
     */
    private fun sweepStaleCssCacheEntries(bootPrefs: SharedPreferences) {
        launchStartupTask("css-sweep") {
            try {
                val cssPrefs = getSharedPreferences(SettingKeys.CSS_CACHE_PREFS_NAME, Context.MODE_PRIVATE)
                val isEquicord = bootPrefs.getStringSafe(SettingKeys.KEY_CLIENT_MOD, "vencord") == "equicord"
                val expectedKeys = setOf(
                    CssCacheKeys.keyFor(if (isEquicord) Constants.EQUICORD_CSS_URL else Constants.VENCORD_CSS_URL)
                )
                val editor = cssPrefs.edit()
                var removed = 0
                for (key in cssPrefs.all.keys) {
                    if (key.startsWith(CssCacheKeys.VDE_PREFIX) && key !in expectedKeys) {
                        editor.remove(key)
                        removed++
                    }
                }
                if (removed > 0) {
                    editor.apply()
                    VDELog.i("VDE", "CSS cache sweep removed $removed stale entr${if (removed == 1) "y" else "ies"}")
                }
            } catch (ex: Exception) {
                VDELog.e("VDE", "CSS cache sweep failed", ex)
            }
        }
    }

    /**
     * Warm up the Chromium cookie DB so MainActivity does not pay
     * the cost on its first CookieManager.getInstance() call.
     */
    private fun warmUpCookieManager() {
        launchStartupTask("cookie-warmup") {
            try {
                CookieManager.getInstance()
            } catch (ex: Exception) {
                VDELog.e("VDE", "CookieManager warmup failed", ex)
            }
        }
    }

    /**
     * Preload the persisted main-frame shell off the UI thread so
     * the first shouldInterceptRequest doesn't read from disk.
     */
    private fun preloadMainFrameDiskCache() {
        launchStartupTask("disk-cache-preload") {
            try {
                MainFrameDiskCache.init(applicationContext)
                VWebviewClient.preloadMainFrameCache()
                VDELog.i("VDE", "Main-frame disk cache preloaded")
            } catch (ex: Exception) {
                VDELog.e("VDE", "Main-frame disk cache preload failed", ex)
            }
        }
    }

    companion object {
        @Volatile
        var prewarmedWebView: WebView? = null
            internal set

        /**
         * One-shot notice flag for MainActivity, set when
         * healUnusableVencordLocation removes an unusable vencordLocation.
         * Native-only: the bridge key allowlist (VencordNative.isBridgeKeyAllowed)
         * rejects this name, so page JS can never read or flip it.
         */
        internal const val PREF_VENCORD_LOCATION_HEALED = "vencordLocationHealed"

        /**
         * Destroys the pre-warmed WebView if MainActivity never consumed it.
         * Call from MainActivity.onDestroy() when prewarmUsed == false to
         * avoid leaking the renderer process.
         */
        fun destroyPrewarmedWebViewIfUnused() {
            prewarmedWebView?.destroy()
            prewarmedWebView = null
        }

        /**
         * Removes a persisted vencordLocation the bundle fetch path can
         * never accept, so a value carried over from an older build cannot
         * brick Vencord forever.
         *
         * A rejected location is otherwise unrecoverable: HttpClient.fetchVencord
         * throws before its offline-fallback try/catch, both runtime preloads
         * skip the cached file while the location counts as custom, and the
         * bridge rejects every read and write of the key. An older build's
         * custom URL therefore means an unmodded Discord on every boot, with
         * no in-app way to clear the key.
         *
         * Contract (mirrors the clientMod heal in onCreate): commit()
         * makes the removal durable before anything reads, and the check
         * is condition-based rather than flag-guarded. A key resurrected
         * by a warm process flushing its stale in-memory map, or by a
         * restored backup, is healed again on the next boot; the healed
         * state is the fixed point.
         *
         * Cleared alongside the key:
         *  - PREF_LAST_BUNDLE_UPDATE zeroed: the first boot after a heal
         *    must revalidate unconditionally even when no app version bump
         *    would force it (a hand-edited or restored pref can carry a
         *    current stamp).
         *  - bundle identity keys (ETag trio, patch flags, freshness
         *    stamp): validators describe the old location.
         *  - the cached vencord.js: it was fetched from a source the
         *    operator no longer permits; nothing from that source runs
         *    again. Cost: an offline first boot after the heal loads no
         *    bundle until the network returns; the official location is
         *    fetchable by then, so the fetch's cached-file fallback works
         *    normally from that point on.
         *
         * Internal + explicit-file so the repair logic can be pinned by
         * Robolectric tests without booting the Application.
         */
        internal fun healUnusableVencordLocation(prefs: SharedPreferences, vendroidFile: File) {
            var wrongTyped = false
            val stored = try {
                prefs.getString(SettingKeys.KEY_VENCORD_LOCATION, null)
            } catch (e: ClassCastException) {
                wrongTyped = true
                null
            }
            // Same normalization as resolveBundleLocation: an empty value
            // already resolves to the default and needs no heal.
            val location = stored?.trim()?.removeSuffix("/")?.takeIf { it.isNotEmpty() }
            val problem = when {
                wrongTyped -> "wrong-typed value"
                location != null -> HttpClient.bundleLocationFetchProblem(location)
                else -> null
            } ?: return

            val editor = prefs.edit()
            editor.remove(SettingKeys.KEY_VENCORD_LOCATION)
            editor.putInt(HttpClient.PREF_LAST_BUNDLE_UPDATE, 0)
            editor.clearBundleIdentityKeys()
            editor.putBoolean(PREF_VENCORD_LOCATION_HEALED, true)
            val committed = try {
                editor.commit()
            } catch (t: Throwable) {
                VDELog.e("VDE", "vencordLocation heal did not persist", t)
                false
            }
            if (!committed) {
                VDELog.w("VDE", "vencordLocation heal did not persist; retrying next boot")
                return
            }
            VDELog.w("VDE", "Removed unusable vencordLocation: $problem")
            if (!vendroidFile.delete() && vendroidFile.exists()) {
                VDELog.w("VDE", "Could not delete cached bundle after vencordLocation heal; the next download overwrites it")
            }
            // Belt: nothing from the removed source survives this boot. The
            // preload thread has not started yet, so this is normally a no-op.
            HttpClient.setVencordRuntime(null)
        }
    }

    private fun getCurrentProcessName(): String {
        // Application.getProcessName() is a static method available from API 28.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        // Fallback for API 26-27: read the process name from /proc/self/cmdline.
        try {
            val bytes = File("/proc/self/cmdline").readBytes()
            val end = bytes.indexOf(0.toByte())
            val name = String(bytes, 0, if (end > 0) end else bytes.size)
            if (name.isNotEmpty()) return name
        } catch (_: Exception) {
            // Fall through to the ActivityManager fallback below.
        }
        // A failed cmdline read would misclassify this process as non-web and
        // skip FirewallConfig.init, blocking every request. ActivityManager
        // reports only the caller's own processes since API 22.
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val name = am?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }?.processName
        if (name.isNullOrEmpty()) {
            VDELog.e("VDE", "Process name detection failed on API ${Build.VERSION.SDK_INT}")
        }
        return name ?: ""
    }
}

/**
 * Single source for the CSS cache key derivation and the prefetch freshness
 * window, shared by VendroidApp's startup prefetch and stale-entry sweep.
 *
 * The key derivation is a cross-language contract. Entries live in the
 * dedicated "css_cache" prefs file (SettingKeys.CSS_CACHE_PREFS_NAME) under
 * `"css_cache_vde_" + url.hashCode()` keys, and
 * app/src/main/vencord/90-init.js derives the identical key in
 * cssCacheKey(url) to read them back through the bridge. JS never writes
 * css_cache_* keys. Keep the two derivations in sync.
 *
 * Two unrelated TTLs apply to this cache; do not merge them:
 * - [PREFETCH_FRESHNESS_MS] (12 h) decides when the startup prefetch
 *   refetches an entry it wrote.
 * - VencordNative.CSS_CACHE_TTL_MS (7 days) decides when the in-page
 *   fallback's eviction, VencordNative.evictStaleCssCache, drops an entry
 *   nobody refreshed.
 */
internal object CssCacheKeys {
    /** Shared key prefix; the sweep matches it to find strays. */
    const val VDE_PREFIX = "css_cache_vde_"

    /** How long a prefetched entry stays fresh; the prefetch refetches older ones. */
    const val PREFETCH_FRESHNESS_MS = 12 * 60 * 60 * 1000L

    /** Cache key for [url], matching the JS-side cssCacheKey derivation. */
    fun keyFor(url: String): String = VDE_PREFIX + url.hashCode()
}
