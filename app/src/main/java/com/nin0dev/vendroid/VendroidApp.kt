package com.nin0dev.vendroid

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.View
import android.webkit.WebView
import com.nin0dev.vendroid.webview.HttpClient
import com.nin0dev.vendroid.webview.clearBundleIdentityKeys
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.FirewallConfig
import com.nin0dev.vendroid.utils.VDELog
import java.io.File

class VendroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val isWebProcess = getCurrentProcessName().endsWith(":web")

        // Initialize logging in every process so the file-backed sink is
        // available anywhere (e.g. RecoveryActivity reads vde_logs.txt). Only
        // the :web process writes/rotates the file; others read-only.
        VDELog.init(applicationContext, persistToFile = isWebProcess)
        VDELog.i("VDE", "App started (PID=${android.os.Process.myPid()})")

        // Self-heal a type-poisoned clientMod (a Boolean persisted by the
        // setBool bridge bug of older builds). The startup fetch path read it
        // with an unguarded getString (HttpClient.resolveBundleLocation),
        // which crash-looped the process on every cold start; no recovery
        // option cleared the key. Removing it restores the "vencord" default.
        // Runs synchronously in every process before any reader; that
        // ordering also neutralizes the stale-map resurrection race: a warm
        // process can flush the poison back to disk, so each boot re-heals
        // before its first read. Precedent: evictStaleCssCache and the CSS
        // prefetch repair poisoned entries in place rather than crashing.
        val bootPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        try {
            bootPrefs.getString("clientMod", null)
        } catch (e: ClassCastException) {
            val removed = try {
                bootPrefs.edit().remove("clientMod").commit()
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

        // Self-heal an unusable vencordLocation (see
        // healUnusableVencordLocation below). Same placement contract as the
        // clientMod heal above: synchronous, every process, before any
        // reader, so no preload or fetch observes the stale value.
        healUnusableVencordLocation(
            bootPrefs,
            File(filesDir, "vencord.js")
        )

        // Log app + WebView versions for incident reports.
        try {
            @Suppress("NewApi")
            val wvPkg = android.webkit.WebView.getCurrentWebViewPackage()
            VDELog.i(
                "VDE",
                "Session: app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                    "webview=${wvPkg?.versionName ?: "unknown"}"
            )
        } catch (_: Throwable) {
            VDELog.i("VDE", "Session: app=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) webview=unknown")
        }

        if (isWebProcess) {
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
            // runCatching: Application.onCreate runs before MainActivity, so
            // a poisoned value would crash-loop :web cold start.
            val riskAccepted = runCatching { bootPrefs.getBoolean("riskWarningAccepted", false) }
                .onFailure { VDELog.w("VDE", "riskWarningAccepted type-poisoned; skipping prewarm: $it") }
                .getOrDefault(false)
            if (riskAccepted) {
                try {
                    prewarmedWebView = WebView(this).apply {
                        setBackgroundColor(android.graphics.Color.parseColor("#121214"))
                    }
                } catch (e: Exception) {
                    VDELog.e("VDE", "Failed to create prewarmed WebView", e)
                }
            }

            // One-time migration: move CSS cache entries from the shared
            // "settings" prefs into a dedicated "css_cache" file so the settings
            // file stays small (faster cold-start parse).
            Thread {
                try {
                    val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                    if (settingsPrefs.getBoolean("css_cache_migrated", false)) return@Thread
                    val cssPrefs = getSharedPreferences("css_cache", Context.MODE_PRIVATE)
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
                    settingsEditor.putBoolean("css_cache_migrated", true)
                    // Apply the copy before the removals+flag. A death between
                    // the two flushes would otherwise persist the flag without
                    // the copied entries; in this order it leaves duplicates
                    // and the migration re-runs.
                    if (migrated) editor.apply()
                    settingsEditor.apply()
                } catch (ex: Exception) {
                    VDELog.e("VDE", "CSS cache migration failed", ex)
                }
            }.start()

            // Safe mode: raise the kill switch and never publish a runtime in
            // this process. Read synchronously; Application.onCreate always
            // precedes Activity.onCreate here, so the read cannot race the
            // one-shot pref reset in MainActivity.
            // runCatching: the first safeMode reader in the process, so a
            // String-typed key (restored or hand-edited XML) would crash-loop
            // :web cold start here, before MainActivity's guarded reads run.
            // TRUE is the fail-safe fallback and only applies to a wrong
            // type: the session runs without Vencord, and MainActivity's
            // one-shot reset overwrites the poison with a real Boolean.
            val safeMode = runCatching {
                getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getBoolean("safeMode", false)
            }.onFailure { VDELog.w("VDE", "safeMode type-poisoned; failing safe: $it") }
                .getOrDefault(true)
            if (safeMode) {
                HttpClient.vencordDisabled = true
                VDELog.w("VDE", "Safe mode: skipping Vencord runtime preload")
            }

            // Pre-load the Vencord runtimes on a background thread so they are
            // in memory by the time MainActivity.onCreate() runs.
            Thread {
                // Publish-site guard; see HttpClient.vencordDisabled.
                if (HttpClient.vencordDisabled) return@Thread
                try {
                    // 1. VencordMobile runtime (65 KB raw resource, memory-mapped)
                    if (HttpClient.VencordMobileRuntime == null) {
                        resources.openRawResource(R.raw.vencord_mobile).use { inputStream ->
                            HttpClient.setVencordMobileRuntimeIfNull(HttpClient.readAsText(inputStream))
                        }
                    }
                    VDELog.i("VDE", "VencordMobile runtime preloaded")
                    // 2. Vencord runtime (potentially ~1 MB from disk)
                    val vendroidFile = File(filesDir, "vencord.js")
                    val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                    // Skip the preload while a redownload is pending so the
                    // stale bundle is never published. The file is kept on disk
                    // as fetchVencord's offline fallback; deleting it here would
                    // brick Vencord on an offline launch.
                    val needsRedownload = HttpClient.needsBundleRedownload(sPrefs)
                    if (!needsRedownload && vendroidFile.exists() && HttpClient.VencordRuntime == null) {
                        try {
                            // The file was written with applyPatches already
                            // applied during a previous download. Skip the
                            // redundant ~1MB regex scan by trusting the
                            // persisted patched flag + patch-set key.
                            //
                            // stillValid re-checks the guards at publish time;
                            // the read can stall for seconds on slow storage
                            // while a clientMod switch deletes the file and
                            // forces a redownload.
                            val published = HttpClient.setVencordRuntimeIfNull(
                                HttpClient.readBundleFromDisk(sPrefs, vendroidFile)
                            ) {
                                !HttpClient.needsBundleRedownload(sPrefs) && vendroidFile.exists()
                            }
                            if (published) {
                                VDELog.i("VDE", "Vencord runtime preloaded (${vendroidFile.length()} bytes)")
                            } else {
                                VDELog.i("VDE", "Vencord runtime preload skipped (published or invalidated elsewhere)")
                            }
                        } catch (ex: Exception) {
                            VDELog.e("VDE", "Failed to apply Vencord patches: ${ex.message}", ex)
                        }
                    }
                } catch (ex: Exception) {
                    VDELog.e("VDE", "Vencord preload failed: ${ex.message}", ex)
                }
            }.start()

            // 3. Pre-fetch and cache the Vencord CSS files, injected by
            // vencord_mobile.js on every page load. Stashing them in the
            // dedicated css_cache prefs lets JS skip the network fetch without
            // churning the main settings XML. Gated on first-run consent so no
            // network activity phones home before the user accepts the risk
            // warning. (Other startup threads are local-only: runtime preload,
            // cookie-DB warmup, and disk-cache preload touch no network.)
            // Safe mode skips this too: only vencord_mobile.js applies the
            // CSS, and it never loads, so the prefetch buys nothing.
            if (riskAccepted && !safeMode) {
                Thread {
                    try {
                        val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                        val cssPrefs = getSharedPreferences("css_cache", Context.MODE_PRIVATE)
                        val isEquicord = runCatching {
                            sPrefs.getString("clientMod", "vencord") == "equicord"
                        }.onFailure {
                            VDELog.w("VDE", "clientMod type-poisoned; CSS prefetch assumes vencord")
                        }.getOrDefault(false)
                        val cssUrls = listOf(
                            if (isEquicord) Constants.EQUICORD_CSS_URL else Constants.VENCORD_CSS_URL,
                            MORE_FIXES_CSS_URL
                        )
                        val editor = cssPrefs.edit()
                        val now = System.currentTimeMillis()
                        for (url in cssUrls) {
                            val key = "css_cache_vde_" + url.hashCode()
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
                            if (!missingOrPoisoned && now - ts <= 12 * 60 * 60 * 1000L) {
                                continue
                            }
                            if (!url.startsWith("https://")) {
                                VDELog.w("VDE", "CSS prefetch rejected non-HTTPS URL: $url")
                                continue
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
                    editor.apply()
                } catch (ex: Exception) {
                    VDELog.e("VDE", "CSS prefetch thread failed", ex)
                }
                }.start()
            }

            // 3b. Sweep stale CSS cache entries. Keys are URL-hash addressed
            // (css_cache_vde_<url.hashCode>), so entries written for retired
            // hosts (vde-builds.nin0.dev) are never read again and would
            // linger forever. Remove everything the current URL set would
            // not produce; the prefetch above (or the in-page fallback)
            // repopulates. Runs unconditionally, not gated on the risk
            // warning or safe mode: local-only prefs work, no network.
            Thread {
                try {
                    val cssPrefs = getSharedPreferences("css_cache", Context.MODE_PRIVATE)
                    val isEquicord = runCatching {
                        bootPrefs.getString("clientMod", "vencord") == "equicord"
                    }.getOrDefault(false)
                    val expectedKeys = setOf(
                        "css_cache_vde_" + (if (isEquicord) Constants.EQUICORD_CSS_URL else Constants.VENCORD_CSS_URL).hashCode(),
                        "css_cache_vde_" + MORE_FIXES_CSS_URL.hashCode()
                    )
                    val editor = cssPrefs.edit()
                    var removed = 0
                    for (key in cssPrefs.all.keys) {
                        if (key.startsWith("css_cache_vde_") && key !in expectedKeys) {
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
            }.start()

            // 4. Warm up the Chromium cookie DB so MainActivity does not pay
            // the cost on its first CookieManager.getInstance() call.
            Thread {
                try {
                    android.webkit.CookieManager.getInstance()
                } catch (ex: Exception) {
                    VDELog.e("VDE", "CookieManager warmup failed", ex)
                }
            }.start()

            // 5. Preload the persisted main-frame shell off the UI thread so
            // the first shouldInterceptRequest doesn't read from disk.
            Thread {
                try {
                    com.nin0dev.vendroid.webview.MainFrameDiskCache.init(applicationContext)
                    com.nin0dev.vendroid.webview.VWebviewClient.preloadMainFrameCache()
                    VDELog.i("VDE", "Main-frame disk cache preloaded")
                } catch (ex: Exception) {
                    VDELog.e("VDE", "Main-frame disk cache preload failed", ex)
                }
            }.start()
        }
    }

    companion object {
        @Volatile
        var prewarmedWebView: WebView? = null
            internal set

        /** Shared moreFixes.css URL, injected by vencord_mobile.js on every
         *  page load; kept here so the prefetch and the stale-cache sweep
         *  address the same key the runtime derives. */
        internal const val MORE_FIXES_CSS_URL =
            "https://raw.githubusercontent.com/VendroidEnhanced/random-files/refs/heads/main/moreFixes.css"

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
                prefs.getString("vencordLocation", null)
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
            editor.remove("vencordLocation")
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
            val bytes = java.io.File("/proc/self/cmdline").readBytes()
            val end = bytes.indexOf(0.toByte())
            val name = String(bytes, 0, if (end > 0) end else bytes.size)
            if (name.isNotEmpty()) return name
        } catch (_: Exception) {
        }
        // A failed cmdline read would misclassify this process as non-web and
        // skip FirewallConfig.init, blocking every request. ActivityManager
        // reports only the caller's own processes since API 22.
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val name = am?.runningAppProcesses
            ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        if (name.isNullOrEmpty()) {
            VDELog.e("VDE", "Process name detection failed on API ${Build.VERSION.SDK_INT}")
        }
        return name ?: ""
    }
}
