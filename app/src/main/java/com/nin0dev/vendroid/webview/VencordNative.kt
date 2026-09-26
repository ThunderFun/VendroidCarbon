package com.nin0dev.vendroid.webview

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.edit
import com.nin0dev.vendroid.MainActivity
import com.nin0dev.vendroid.R
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.FirewallConfig
import com.nin0dev.vendroid.utils.ShareHelper
import com.nin0dev.vendroid.utils.VDELog
import java.io.File
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class VencordNative(private val activity: WeakReference<MainActivity>, wv: WebView) {
    private val wvRef: WeakReference<WebView> = WeakReference(wv)

    companion object {
        private const val CSS_CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        // Maximum length of a single String value a page script may persist via
        // setString; bounds the settings XML size from any single write.
        private const val MAX_STRING_VALUE_LENGTH = 64 * 1024 // 64 KB
        // Delegates: the implementations live in BridgeSettings.kt; the
        // contract tests reach these as VencordNative.<member>.
        internal val BOOLEAN_SETTING_KEYS get() = BridgeSettings.BOOLEAN_SETTING_KEYS
        internal val STRING_SETTING_KEYS get() = BridgeSettings.STRING_SETTING_KEYS
        internal fun isTypeSafeBridgeWrite(op: String, key: String) = BridgeSettings.isTypeSafeBridgeWrite(op, key)
        internal fun isBridgeKeyAllowed(id: String) = BridgeSettings.isBridgeKeyAllowed(id)

        private val gson = com.google.gson.Gson()

        // Capability token, injected only into the top-level Discord document
        // (see bridgeBootstrapJs). addJavascriptInterface exposes the bridge to
        // every frame, so each bridge method requires this token. The token is
        // captured in the bootstrap's closure scope, never exposed as a window
        // global, so subframes can't read it and native checks reject their
        // calls.
        //
        // NOTE: the token is generated once per PROCESS (never rotated on
        // navigation / MainActivity recreation), so it is process-lifetime,
        // not per-document. It excludes cross-origin contexts and (by not
        // being a window global) prevents subframes reading it; it is NOT a
        // boundary against same-origin code already running in the top Discord
        // document, which receives the token automatically via the wrapper.
        private val tokenLock = Any()
        @Volatile private var bridgeToken: String? = null
        private val tokenChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray()
        private val secureRandom = SecureRandom()

        // QuickCSS plumbing between the editor dialog and the main WebView.
        // evaluateJavascript does not await Promises (a pending one serializes
        // to "{}"), and the Vencord web shim (main WebView) and window.qcssSet
        // (editor WebView) live in separate JS contexts, so both flows park
        // their settled result in a page global and let Kotlin poll it.
        private const val QUICKCSS_POLL_INTERVAL_MS = 250L
        private const val QUICKCSS_POLL_ATTEMPTS = 8

        // Kicks off quickCss.get() and parks the settled value in
        // window.__vdeQcss.
        private const val QUICKCSS_GET_START_JS =
            "(function(){try{" +
            "if(typeof VencordNative==='undefined'||!VencordNative.quickCss||typeof VencordNative.quickCss.get!=='function'){" +
            "window.__vdeQcss={settled:true,value:null};return}" +
            "VencordNative.quickCss.get().then(function(v){" +
            "window.__vdeQcss={settled:true,value:(v==null?'':String(v))}}," +
            "function(){window.__vdeQcss={settled:true,value:null}})" +
            "}catch(e){window.__vdeQcss={settled:true,value:null}}})()"

        // Returns 0 while pending, null when there is nothing to load, else
        // the raw CSS text. WebView JSON-encodes each result unambiguously.
        private const val QUICKCSS_GET_POLL_JS =
            "(function(){try{var s=window.__vdeQcss;" +
            "if(!s||s.settled!==true)return 0;" +
            "return s.value==null?null:String(s.value)}catch(e){return null}})()"

        // Save wrapper for QuickCssBridge.quickCssSet. Returns true only when
        // the Vencord web shim exists and quickCss.set() invoked without
        // throwing; false previously looked identical to success, so the
        // editor toasted "Saved" and closed, silently dropping the edits.
        private fun quickCssSaveJs(cssJson: String): String =
            "(function(){try{" +
            "if(typeof VencordNative==='undefined'||!VencordNative.quickCss||typeof VencordNative.quickCss.set!=='function')return false;" +
            "var p=VencordNative.quickCss.set($cssJson);" +
            "if(p&&typeof p.then==='function')p.catch(function(e){console.error('VDE QuickCSS save failed:',e)});" +
            "return true" +
            "}catch(e){console.error('VDE QuickCSS save failed:',e);return false}})()"

        private fun ensureToken(): String {
            bridgeToken?.let { return it }
            synchronized(tokenLock) {
                bridgeToken?.let { return it }
                val len = 32
                val sb = StringBuilder(len)
                for (i in 0 until len) {
                    sb.append(tokenChars[secureRandom.nextInt(tokenChars.size)])
                }
                bridgeToken = sb.toString()
                return bridgeToken!!
            }
        }

        /** True iff [token] matches the current session capability token. */
        private fun isBridgeAuthorized(token: String?): Boolean {
            val expected = bridgeToken ?: return false
            if (token == null) return false
            // Constant-time comparison to avoid timing side channels.
            val a = expected.toCharArray()
            val b = token.toCharArray()
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }

        /**
         * JavaScript that runs in the TOP-LEVEL Discord document before any
         * bridge call. It wraps window.VencordMobileNative so every method call
         * is prepended with the per-session capability token. The token is held
         * ONLY in this closure, never published as a window global, so even a
         * same-origin subframe/window that can access this document's globals
         * cannot read it, nor pass the native check. Subframes hold the raw
         * injected object but no usable token.
         *
         * Idempotent: a re-entrant call (e.g. the bootstrap was embedded by
         * shouldInterceptRequest and also injected via onPageStarted) returns
         * early via the window.__vendroidBootstrapped marker. That marker is a
         * boolean, NOT the token, so it leaks nothing. Re-wrapping the bridge
         * would unshift the token twice and shift every argument position.
         */
        @Volatile
        private var cachedBootstrapJs: String? = null

        fun bridgeBootstrapJs(): String {
            cachedBootstrapJs?.let { return it }
            val t = ensureToken()
            // JSON-encode so the token is safely embedded in a JS string literal.
            val tokenLiteral = gson.toJson(t)
            return ("(function(){" +
                "'use strict';" +
                // Capture uncaught errors so bundle boot crashes are visible
                // in the logs (otherwise "Vencord undefined" is the only
                // symptom). Idempotent; throttled to avoid spam.
                "if(!window.__vdeUncaughtHook){" +
                "window.__vdeUncaughtHook=true;" +
                "if(!Array.isArray(window.__vdeUncaught))window.__vdeUncaught=[];" +
                "var vdeEmit=function(m,s,l){if(window.__vdeUncaught.length<10)window.__vdeUncaught.push(m+'@'+s+':'+l);" +
                "if(m!==window.__vdeLastUncaught||Date.now()-(window.__vdeLastUncaughtAt||0)>2000){" +
                "console.error('[Vendroid][Uncaught] '+m+' @ '+s+':'+l);" +
                "window.__vdeLastUncaught=m;window.__vdeLastUncaughtAt=Date.now();}};" +
                "window.addEventListener('error',function(ev){" +
                // Resource load failures (img/script/link) surface as message-less
                // ErrorEvents; record only genuine script exceptions, which carry
                // a message or error object.
                "if(!((ev&&ev.message)||(ev&&ev.error)))return;" +
                "var m=String(ev.message);" +
                "var s=(ev&&ev.filename)?ev.filename:'?';" +
                "var ln=(ev&&ev.lineno)?ev.lineno:0;" +
                "vdeEmit(m,s,ln);},true);" +
                "window.addEventListener('unhandledrejection',function(ev){" +
                "var r=(ev&&ev.reason)?ev.reason:ev;" +
                "var m=(r&&r.message)?String(r.message):String(r);" +
                // Benign WebView audio race (sound effects); preventDefault also hides Chromium's "Uncaught (in promise)" line.
                "if(r&&r.name==='AbortError'&&m.indexOf('play() request was interrupted')!==-1){ev.preventDefault();return;}" +
                "vdeEmit('unhandledrejection: '+m,'?',0);});}" +
                "if(window.__vendroidBootstrapped)return;" +
                "window.__vendroidBootstrapped=true;" +
                "var vendroidToken=$tokenLiteral;" +
                "var raw=window.VencordMobileNative;" +
                "if(raw){var wrap={};" +
                "for(var k in raw){if(typeof raw[k]==='function')(function(name){wrap[name]=function(){var a=Array.prototype.slice.call(arguments);a.unshift(vendroidToken);return raw[name].apply(raw,a);};})(k);}" +
                "window.VencordMobileNative=wrap;}" +
                "})();").also { cachedBootstrapJs = it }
        }
    }

    init {
        IconAliasManager.initCurrentIcon(activity.get())
    }

    @Volatile
    private var logsDialogActive = false

    @Volatile
    private var firewallDialogActive = false

    @Volatile
    private var quickCssDialogActive = false

    // Eagerly initialize SharedPreferences in the constructor (which runs on
    // the main thread during WebView setup) instead of lazily on the JS bridge
    // thread. The first getSharedPreferences() reads+parses the XML file from
    // disk, taking 50-100ms on eMMC; doing it on the bridge thread would stall
    // all @JavascriptInterface methods.
    private val settingsPrefs: SharedPreferences? = activity.get()
        ?.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Dedicated SharedPreferences for CSS cache entries. Isolating CSS from
    // the main "settings" prefs avoids rewriting the entire settings XML on
    // every CSS write and keeps the settings file small (faster cold-start
    // parse, no contention between CSS churn and settings).
    private val cssCachePrefs: SharedPreferences? = activity.get()
        ?.getSharedPreferences("css_cache", Context.MODE_PRIVATE)

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var isShutdown = false

    // Bridge-thread serialization is an implementation detail, not a contract;
    // a lost increment here would silently delay eviction.
    private val cssCacheEvictionCounter = AtomicInteger(0)

    private fun evictStaleCssCache() {
        val cssPrefs = cssCachePrefs ?: return
        val now = System.currentTimeMillis()
        val editor = cssPrefs.edit()
        var evicted = false
        for (key in cssPrefs.all.keys) {
            if (!key.startsWith("css_cache_") || key.endsWith("_ts")) continue
            // VendroidApp's prefetch guards this same getLong. One
            // type-poisoned timestamp would otherwise abort every eviction
            // run at the same key, since getAll() iteration order is stable.
            // 0L reads as stale, so the removals below also clear the poison.
            val ts = try {
                cssPrefs.getLong("${key}_ts", 0)
            } catch (e: Exception) {
                VDELog.w("VN", "CSS cache ${key}_ts type-poisoned, evicting")
                0L
            }
            if (now - ts > CSS_CACHE_TTL_MS) {
                editor.remove(key)
                editor.remove("${key}_ts")
                evicted = true
            }
        }
        if (evicted) editor.apply()
    }

    // Per-key rate limiter: prevents rapid re-writes to the same key while
    // allowing independent keys to be written in parallel. Uses
    // System.nanoTime() (monotonic) instead of currentTimeMillis() (wall clock,
    // which can jump on clock adjustments and break the limiter). Bounded:
    // rateLimitWrite is called with attacker-controlled ids before
    // isKeyAllowed, so a page script could grow this unbounded; cap and evict
    // the oldest entries on overflow.
    private val lastWriteTime = ConcurrentHashMap<String, Long>()
    private val LAST_WRITE_CAP = 2048

    // Total number of distinct keys a page script may persist. The per-key
    // rate limiter only bounds the in-memory timing map (it evicts the oldest
    // entry on overflow); without this, a script could write an unbounded
    // number of distinct Vencord-* keys, growing the settings XML without
    // limit. This set never evicts, so once the cap is reached no new distinct
    // key is accepted.
    private val persistedDistinctKeys = ConcurrentHashMap.newKeySet<String>()
    private val MAX_DISTINCT_WRITE_KEYS = 256

    private fun canWriteNewDistinctKey(id: String): Boolean {
        if (persistedDistinctKeys.contains(id)) return true
        if (persistedDistinctKeys.size >= MAX_DISTINCT_WRITE_KEYS) return false
        persistedDistinctKeys.add(id)
        return true
    }

    // Settings-row toggles get tapped in quick succession. The default 500 ms
    // window would swallow the second tap and the row's read-back would snap
    // the switch back, which looks like a broken toggle. 50 ms still caps
    // scripted writes at 20/s per key.
    private val TOGGLE_SETTING_KEYS = setOf(
        "vendroid_confirmExternalLinks",
        "vendroid_blockTypingIndicator",
        "vendroid_gestures",
        "vendroid_support_warnings"
    )

    private fun minWriteIntervalNanos(id: String): Long =
        if (id in TOGGLE_SETTING_KEYS) 50_000_000L else 500_000_000L

    private fun rateLimitWrite(id: String): Boolean = rateLimitWrite(id, minWriteIntervalNanos(id))

    private fun rateLimitWrite(id: String, minIntervalNanos: Long): Boolean {
        val now = System.nanoTime()
        // Use presence, not a 0L sentinel: System.nanoTime() is ~0 shortly
        // after boot, so `?: 0L` silently rejected the first write within the
        // interval.
        val last = lastWriteTime[id]
        if (last != null && now - last < minIntervalNanos) return false
        if (lastWriteTime.size >= LAST_WRITE_CAP && !lastWriteTime.containsKey(id)) {
            // Cap reached and this is a new key: evict an old entry so the map
            // stays bounded. Pick the oldest single entry (cheap, no full
            // scan on the hot path).
            var oldestId: String? = null
            var oldestTs = Long.MAX_VALUE
            for ((k, v) in lastWriteTime) {
                if (v < oldestTs) { oldestTs = v; oldestId = k }
            }
            if (oldestId != null) lastWriteTime.remove(oldestId)
        }
        lastWriteTime[id] = now
        return true
    }

    private fun isKeyAllowed(id: String, forWrite: Boolean): Boolean {
        if (isBridgeKeyAllowed(id)) return true
        val op = if (forWrite) "write" else "read"
        VDELog.w("VN", "Blocked $op for disallowed key: $id")
        return false
    }

    /**
     * Shared guarded path for the JS bridge get/set prefs methods. Centralizes
     * the domain gate, per-key write rate limit, allowed-key allowlist check,
     * ClassCastException corruption recovery, and the outer defensive
     * Throwable catch across getString/getBool/setString/setBool and getCssCache.
     */
    private fun <T> guardedPrefs(
        op: String,
        id: String?,
        default: T,
        forWrite: Boolean,
        strictDomain: Boolean,
        block: (SharedPreferences) -> T
    ): T {
        val safeId = id ?: return default
        return try {
            val onDomain = if (strictDomain) isOnDiscordDomainStrict() else isOnDiscordDomain()
            if (!onDomain) return default
            if (forWrite && !rateLimitWrite(safeId)) return default
            if (!isKeyAllowed(safeId, forWrite)) return default
            // Bound the number of distinct persisted keys a script can
            // introduce (the per-key rate limiter only bounds the timing map).
            // css_cache_* keys are write-rejected in the block, so they are
            // never counted.
            if (forWrite && !safeId.startsWith("css_cache_") && !canWriteNewDistinctKey(safeId)) {
                VDELog.w("VN", "Rejected write to new distinct key past cap: $safeId")
                return default
            }
            val prefs: SharedPreferences = if (safeId.startsWith("css_cache_")) {
                cssCachePrefs ?: return default
            } else {
                settingsPrefs ?: return default
            }
            try {
                block(prefs)
            } catch (e: ClassCastException) {
                // Only purge a corrupted key on a WRITE path. On a READ, the
                // caller may be probing a key type it doesn't know (e.g. a
                // page script calling getBool on an Int-typed Vencord setting);
                // a destructive remove there would let page JS permanently
                // delete settings it couldn't otherwise touch.
                if (forWrite) {
                    VDELog.e("VN", "$op($safeId) ClassCastException, removing corrupted key", e)
                    prefs.edit().remove(safeId).apply()
                } else {
                    VDELog.w("VN", "$op($safeId) ClassCastException on read, returning default")
                }
                default
            }
        } catch (t: Throwable) {
            VDELog.e("VN", "$op($safeId) failed", t)
            default
        }
    }

    private fun isOnDiscordDomain(): Boolean {
        return Constants.isDiscordAppOrigin(activity.get()?.currentHostForBridge ?: return false)
    }

    /**
     * Defense-in-depth domain check for sensitive bridge methods. Re-reads the
     * WebView's current URL on the UI thread in addition to the cached
     * [currentHostForBridge], closing the TOCTOU window between a navigation
     * and the cached host being refreshed. Returns false if the live URL is
     * not a Discord domain.
     *
     * The UI-thread read is bounded to 50 ms so a busy UI thread cannot stall
     * the shared JS bridge thread. If the live read times out, the method
     * fails CLOSED (returns false) rather than trusting the cached host, so a
     * slow UI thread cannot widen the TOCTOU window for sensitive methods.
     *
     * A passing live read also re-publishes the URL and clears
     * [MainActivity.navigationInProgress]; loads that never finish (download
     * handoff, abandoned navigation) would otherwise pin every strict call
     * to this slow path.
     */
    private fun isOnDiscordDomainStrict(): Boolean {
        if (!isOnDiscordDomain()) return false
        val wvActivity = activity.get() ?: return false
        // Fast path: with no navigation in flight, the cached host was written
        // after the current document committed, so it cannot be stale across a
        // navigation. Skip the UI-thread round trip. This makes steady-state
        // bridge writes (setString/setBool) latch-free instead of blocking the
        // shared JS bridge thread on a possibly-busy UI thread for up to 50ms.
        if (!wvActivity.navigationInProgress) return true
        val wv = wvRef.get() ?: return false
        // getUrl() must run on the UI thread; bridge methods run on a Chromium
        // thread, so bound the read to avoid stalling the shared bridge thread.
        var verified = false
        val latch = java.util.concurrent.CountDownLatch(1)
        wvActivity.runOnUiThread {
            try {
                val url = wv.url
                val host = url?.let { Uri.parse(it).host }
                if (host != null && Constants.isDiscordAppOrigin(host)) {
                    // UI-thread writes cannot interleave with onPageStarted,
                    // which re-sets the flag if a load is genuinely still
                    // in flight.
                    wvActivity.currentUrlForBridge = url
                    wvActivity.currentHostForBridge = host
                    wvActivity.navigationInProgress = false
                    verified = true
                }
            } catch (_: Exception) {}
            latch.countDown()
        }
        try {
            // Fail closed on timeout rather than trusting a possibly-stale
            // cached host across a navigation.
            if (!latch.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return false
            }
        } catch (_: InterruptedException) {
            return false
        }
        return verified
    }

    fun shutdown() {
        isShutdown = true
        executor.shutdown()
        // Drops the WebView reference so every wvRef consumer sees null and
        // bails. Runs on the UI thread, in the same handler message as
        // wv.destroy(); the only call site is MainActivity.onDestroy. A
        // bridge-posted runnable therefore executes either before that
        // message, with the WebView alive, or after it, where goBack's
        // checks bail. If onDestroy reorders these calls, goBack's
        // catch(Throwable) is the only guard left.
        wvRef.clear()
    }

    /**
     * Bridges run tasks on the executor; they can be invoked by the JS bridge
     * during teardown (between wv.destroy() and executor.shutdown()), so we
     * drop the task instead of throwing RejectedExecutionException on the
     * Chromium bridge thread.
     */
    private fun safeExecute(block: () -> Unit) {
        if (isShutdown) return
        try {
            executor.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Shut down between the check and the submit; nothing useful to do.
        }
    }

    @JavascriptInterface
    fun setOverlayActive(token: String?, active: Boolean) {
        if (!isBridgeAuthorized(token)) return
        if (!isOnDiscordDomain()) return
        // Bar colors are owned by MainActivity.barColors. Forward the overlay
        // state and let it re-derive status/nav colors. The manager is
        // order-independent, so interleaving with fullscreen video is safe.
        val act = activity.get() ?: return
        act.barColors.publishOverlayActive(active)
    }

    @JavascriptInterface
    fun goBack(token: String?) {
        if (!isBridgeAuthorized(token)) return
        if (!isOnDiscordDomain()) return
        // Early skip only; shutdown() can still run between here and the
        // runnable below.
        if (isShutdown) return
        val act = activity.get() ?: return
        act.runOnUiThread {
            // Surviving these checks proves wv.destroy() has not run; see
            // shutdown().
            if (isShutdown) return@runOnUiThread
            val wv = wvRef.get() ?: return@runOnUiThread
            try {
                if (wv.canGoBack()) wv.goBack() else act.finish()
            } catch (t: Throwable) {
                // Catch Throwable rather than IllegalStateException.
                // Destroyed-WebView calls throw NPE inside Chromium on some
                // builds; see the threading-model note on MainActivity.wv.
                VDELog.e("VN", "goBack failed", t)
            }
        }
    }

    // Native-gated actions for the app-owned UI buttons rendered in
    // vencord_mobile.js ("View logs" / "Open firewall editor"). openQuickCss,
    // the other privileged method, is called directly by the QuickCSS editor
    // bridge and must stay JS-callable, so it is not collapsed into this
    // channel. updateVencord/updateVendroid were retired with the
    // upstream-bundle flip: their only callers lived in the vendored
    // vendroidEnhancements plugin, which the vanilla bundle does not carry.
    private val ALLOWED_NATIVE_ACTIONS = setOf("openLogs", "openFirewallEditor")

    @JavascriptInterface
    fun requestNative(token: String?, action: String?) {
        // Token is required as defense-in-depth, but the real boundary is that
        // page JS cannot reach these two app-owned actions except through this
        // single gated channel.
        if (!isBridgeAuthorized(token)) return
        val safeAction = action ?: return
        if (safeAction !in ALLOWED_NATIVE_ACTIONS) return
        when (safeAction) {
            "openLogs" -> openLogs()
            "openFirewallEditor" -> openFirewallEditor()
        }
    }

    @JavascriptInterface
    fun getString(token: String?, id: String?, defaultValue: String?): String {
        if (!isBridgeAuthorized(token)) return defaultValue ?: ""
        val safeId = id ?: return defaultValue ?: ""
        val safeDefault = defaultValue ?: ""
        // Single access route for CSS cache values: delegate to getCssCache
        // rather than reading the dedicated cache prefs a second way here.
        if (safeId.startsWith("css_cache_")) return getCssCache(token, safeId) ?: safeDefault
        return guardedPrefs("getString", safeId, safeDefault, false, false) { prefs ->
            prefs.getString(safeId, safeDefault) ?: safeDefault
        }
    }

    @JavascriptInterface
    fun getBool(token: String?, id: String?, defaultValue: Boolean): Boolean {
        if (!isBridgeAuthorized(token)) return defaultValue
        val safeId = id ?: return defaultValue
        return guardedPrefs("getBool", safeId, defaultValue, false, false) { prefs ->
            prefs.getBoolean(safeId, defaultValue)
        }
    }

    @JavascriptInterface
    fun setString(token: String?, id: String?, value: String?) {
        if (!isBridgeAuthorized(token)) return
        val safeId = id ?: return
        val safeValue = value ?: return
        // Type-safety: never write a String to a key the app reads as a
        // Boolean (see BOOLEAN_SETTING_KEYS). A String there silently
        // defeats the toggle.
        if (!isTypeSafeBridgeWrite("setString", safeId)) {
            VDELog.w("VN", "Rejected setString on Boolean-key: $safeId")
            return
        }
        // Bound the persisted value length so a script cannot grow the settings
        // XML from a single write (distinct keys are separately capped).
        if (safeValue.length > MAX_STRING_VALUE_LENGTH) {
            VDELog.w("VN", "Rejected setString with oversized value (${safeValue.length} chars): $safeId")
            return
        }
        guardedPrefs("setString", safeId, Unit, true, true) { prefs ->
            // The CSS cache is READ-ONLY from page JS. It is populated only by
            // the native prefetch in VendroidApp from the two
            // operator-controlled CSS URLs. Letting JS write css_cache_* keys
            // would let any Discord-origin script poison the cache with
            // attacker CSS re-injected on every page load, or fill ~128 MB of
            // storage via the predictable key scheme. Reject all writes.
            if (safeId.startsWith("css_cache_")) {
                VDELog.w("VN", "Rejected JS write to read-only CSS cache key: $safeId")
                return@guardedPrefs
            }
            if (safeId == "clientMod") {
                // Only the two known mods are valid; reject anything else.
                if (safeValue != "vencord" && safeValue != "equicord") {
                    VDELog.w("VN", "Rejected invalid clientMod value: $safeValue")
                    return@guardedPrefs
                }
                // Invalidate the stale bundle so the next launch downloads
                // the new mod cleanly, rather than injecting both the old
                // (from preload) and new (from fetchVencord) runtimes.
                prefs.edit {
                    // Zero the version stamp so the next launch redownloads
                    // unconditionally.
                    putInt(HttpClient.PREF_LAST_BUNDLE_UPDATE, 0)
                    clearBundleIdentityKeys()
                    activity.get()?.filesDir?.let { File(it, "vencord.js").delete() }
                    HttpClient.setVencordRuntime(null)
                }
            }
            prefs.edit {
                putString(safeId, safeValue)
            }
            Unit
        }
    }

    @JavascriptInterface
    fun setBool(token: String?, id: String?, value: Boolean) {
        if (!isBridgeAuthorized(token)) return
        val safeId = id ?: return
        // Type-safety: never write a Boolean to a key the app reads as a
        // String. guardedPrefs' write-path ClassCastException recovery cannot
        // cover this: putBoolean never reads the old value, so the write
        // silently replaces the String. Checked before guardedPrefs so a
        // rejected call consumes no rate-limit slot or distinct-key budget.
        if (!isTypeSafeBridgeWrite("setBool", safeId)) {
            VDELog.w("VN", "Rejected setBool on String-key: $safeId")
            return
        }
        guardedPrefs("setBool", safeId, Unit, true, true) { prefs ->
            // The CSS cache is READ-ONLY from page JS (populated only by the
            // native prefetch in VendroidApp). Mirror the setString guard: a
            // Boolean write to a css_cache_* key would type-poison that key so
            // the prefetch's getString() throws ClassCastException, aborting
            // the prefetch loop on every launch.
            if (safeId.startsWith("css_cache_")) {
                VDELog.w("VN", "Rejected JS write to read-only CSS cache key: $safeId")
                return@guardedPrefs
            }
            prefs.edit {
                putBoolean(safeId, value)
            }
            // Live-update the link-confirm flag so the toggle takes effect
            // without an app restart. Inside the guarded block so rejected or
            // rate-limited writes can't desync the flag from persisted state.
            if (safeId == "vendroid_confirmExternalLinks") {
                com.nin0dev.vendroid.webview.LinkHandler.updateConfirmExternalLinks(value)
            }
            // Live-update the typing indicator filter so the toggle takes
            // effect without an app restart.
            if (safeId == "vendroid_blockTypingIndicator") {
                com.nin0dev.vendroid.webview.VWebviewClient.updateTypingBlock(value)
            }
            // Opt-out wipes any persisted position.
            if (safeId == "vendroid_rememberLastChannel" && !value) {
                prefs.edit { remove("lastUrl") }
            }
            Unit
        }
    }

    @JavascriptInterface
    fun changeAppIcon(token: String?, id: String?) {
        // Single auth check; nothing between here and the guarded work can
        // change the verdict.
        if (!isBridgeAuthorized(token)) return
        val rawId = id?.trim()
        val safeId = rawId?.let { r -> IconAliasManager.ICON_NAMES.find { it.equals(r, ignoreCase = true) } }
        if (rawId == null || safeId == null) {
            val a = activity.get()
            // rawId is page-controlled and unbounded, and lands in a Toast
            // (which gets parcellized), so bound it.
            val why = if (rawId == null) "null id" else "unknown id '${rawId.take(64)}'"
            a?.runOnUiThread { Toast.makeText(a, "Icon change: $why", Toast.LENGTH_SHORT).show() }
            return
        }
        if (!isOnDiscordDomainStrict()) {
            val a = activity.get()
            a?.runOnUiThread { Toast.makeText(a, "Icon change: not on Discord domain", Toast.LENGTH_SHORT).show() }
            return
        }
        val act = activity.get() ?: return
        try {
            synchronized(IconAliasManager.iconLock) {
                // Verify the cache against PM before trusting it, so the guard
                // and oldIcon below are truthful. Runs even on the
                // already-active path so re-selecting the same icon cleans up
                // leftover aliases.
                try {
                    IconAliasManager.reconcileIconState(act)
                } catch (t: Throwable) {
                    // Reads inside are exception-safe; this is only a safety
                    // net so a bridge method can never crash the process.
                    VDELog.e("VN", "changeAppIcon: icon state reconcile failed", t)
                }
                if (safeId == IconAliasManager.currentIcon) {
                    act.runOnUiThread {
                        Toast.makeText(act, "Icon '$safeId' is already active", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                val oldIcon = IconAliasManager.currentIcon
                if (oldIcon == null) {
                    // Reconcile failed to establish a baseline (it logs the
                    // reason). Guessing "Main" could disable the alias about
                    // to be enabled and leave the launcher with no entry. No
                    // PM writes have happened yet, so abort; the next attempt
                    // reconciles again.
                    VDELog.e("VN", "changeAppIcon: no resolved icon baseline; aborting")
                    act.runOnUiThread {
                        Toast.makeText(act, "Icon change failed: icon state unavailable", Toast.LENGTH_LONG).show()
                    }
                    return
                }
                val pm = act.packageManager
                val pkg = act.applicationContext
                // Enable first: while both aliases are briefly enabled the
                // launcher still has an entry; disabling first could leave none.
                pm.setComponentEnabledSetting(
                    IconAliasManager.iconComponent(pkg, safeId),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                // The new alias is live in the launcher from here on, so the
                // cache must claim it now, even if the cleanup below fails.
                IconAliasManager.currentIcon = safeId
                fun disableOld(): Boolean = try {
                    pm.setComponentEnabledSetting(
                        IconAliasManager.iconComponent(pkg, oldIcon),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    true
                } catch (t: Throwable) {
                    VDELog.e("VN", "changeAppIcon: disabling old icon '$oldIcon' failed", t)
                    false
                }
                // One retry covers transient binder failures. If it still
                // fails, reconcileIconState heals the leftover alias on the
                // next icon change or app start.
                val cleanupFailed = !disableOld() && !disableOld()
                act.runOnUiThread {
                    Toast.makeText(
                        act,
                        if (cleanupFailed)
                            "Icon switched to $safeId, but the old icon could not be removed. Opening the icon switcher again (even on the same icon) repairs it."
                        else
                            "Icon changed to $safeId. Restart launcher if it doesn't update.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } catch (t: Throwable) {
            // Safety net: an exception escaping a @JavascriptInterface method
            // kills the process. On exit the cache is either unchanged (the
            // enable threw before any commit) or already claims the live
            // alias.
            VDELog.e("VN", "changeAppIcon failed for id=$safeId", t)
            val a = activity.get()
            a?.runOnUiThread {
                Toast.makeText(a, "Icon change failed: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Read-only counterpart to [changeAppIcon] for the settings-tab icon
     * picker; reports the resolved launcher alias so the picker can
     * preselect the active icon. The cache is reconciled at bridge
     * construction (initCurrentIcon), so a null means reconcile failed:
     * report the manifest default rather than guess a stale alias.
     */
    @JavascriptInterface
    fun getCurrentAppIcon(token: String?): String {
        if (!isBridgeAuthorized(token)) return "Main"
        return synchronized(IconAliasManager.iconLock) { IconAliasManager.currentIcon } ?: "Main"
    }

    @JavascriptInterface
    fun openQuickCss(token: String?, quickCss: String?) {
        // Strict check: this opens a WebView with a JS interface, so a
        // non-Discord whitelisted page must not invoke it. Called directly by
        // the required vendroidEnhancements plugin, which passes the current
        // QuickCSS from IndexedDB.
        if (!isBridgeAuthorized(token)) return
        if (!isOnDiscordDomainStrict()) return
        openQuickCssInternal(quickCss)
    }

    private fun openQuickCssInternal(quickCss: String?) {
        val act = activity.get() ?: return
        if (quickCssDialogActive) return
        val safeQuickCss = quickCss ?: ""
        openAssetEditor(
            act = act,
            assetUrl = "file:///android_asset/quickcss_editor.html",
            createBridge = { dialog -> QuickCssBridge(act, dialog) },
            onShown = { quickCssDialogActive = true },
            onDialogDismiss = { quickCssDialogActive = false },
            onPageFinished = { view ->
                if (safeQuickCss.isNotEmpty()) {
                    view.evaluateJavascript(
                        "window.qcssSet?.(${gson.toJson(safeQuickCss)})", null
                    )
                } else {
                    // Fallback when the bundle didn't supply the CSS. Vencord
                    // stores QuickCSS in IndexedDB, so it must be read via the
                    // loaded bundle; if Vencord isn't ready the editor opens
                    // empty. In practice the bundle always passes the CSS, so
                    // this branch is rarely hit. (Mechanism: QUICKCSS_GET_*_JS.)
                    val mainWv = wvRef.get() ?: return@openAssetEditor
                    val currentAct = activity.get()
                    if (currentAct == null || currentAct.isFinishing || currentAct.isDestroyed) return@openAssetEditor
                    try {
                        mainWv.evaluateJavascript(QUICKCSS_GET_START_JS, null)
                    } catch (_: IllegalStateException) {
                        // Main WebView destroyed before the read could start.
                        return@openAssetEditor
                    }
                    val handler = Handler(Looper.getMainLooper())
                    var pending = true
                    fun deliver(json: String?) {
                        pending = false
                        // Async callback: the editor may have been dismissed
                        // or the activity finished by now. Guard first.
                        if (!quickCssDialogActive) return
                        if (currentAct.isFinishing || currentAct.isDestroyed) return
                        if (json == null) return // nothing to load; editor stays empty
                        try {
                            view.evaluateJavascript(
                                "window.qcssSet?.($json)", null
                            )
                        } catch (_: IllegalStateException) {
                            // Editor WebView was destroyed before delivery.
                        }
                    }
                    fun poll(attempt: Int) {
                        if (!pending || !quickCssDialogActive) return
                        if (currentAct.isFinishing || currentAct.isDestroyed) return
                        try {
                            mainWv.evaluateJavascript(QUICKCSS_GET_POLL_JS) { result ->
                                val value = result?.trim()
                                when (value) {
                                    // Script failed, Vencord missing, or
                                    // get() rejected: nothing to load.
                                    null, "null" -> deliver(null)
                                    // 0 = still pending; retry, bounded.
                                    "0" ->
                                        if (attempt + 1 >= QUICKCSS_POLL_ATTEMPTS) {
                                            VDELog.w("VN", "quickCss fallback: get() never settled; editor opened empty")
                                            deliver(null)
                                        } else {
                                            handler.postDelayed({ poll(attempt + 1) }, QUICKCSS_POLL_INTERVAL_MS)
                                        }
                                    else -> deliver(value) // WebView's own JSON encoding
                                }
                            }
                        } catch (_: IllegalStateException) {
                            // Main WebView destroyed mid-poll: give up quietly.
                            pending = false
                        }
                    }
                    handler.postDelayed({ poll(0) }, QUICKCSS_POLL_INTERVAL_MS)
                }
            }
        )
    }

    /** Interface implemented by the per-editor bridges so the shared
     *  [openAssetEditor] helper can mark the page origin as committed. */
    private interface EditorOrigin {
        var originCommitted: Boolean
    }

    /**
     * Shared wiring for the QuickCSS / log-viewer / firewall editor dialogs:
     * the runOnUiThread + isFinishing/isDestroyed guard, the WebView,
     * SecureWebViewDialog configure/create (with [onDialogDismiss]), the shared
     * webViewClient that sets the bridge's originCommitted flag then calls
     * [onPageFinished], show(), loadUrl([assetUrl]), and a single inner/outer
     * try-catch with a VDELog failure.
     */    private fun openAssetEditor(
        act: MainActivity,
        assetUrl: String,
        createBridge: (android.app.Dialog) -> EditorOrigin,
        onShown: () -> Unit,
        onDialogDismiss: () -> Unit,
        onPageFinished: (WebView) -> Unit
    ) {
        act.runOnUiThread {
            // Nullable so the catch path can clean up a failure at any point.
            var wv: WebView? = null
            var dialog: android.app.Dialog? = null
            try {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                val editor = WebView(act)
                wv = editor
                SecureWebViewDialog.configure(editor)

                dialog = SecureWebViewDialog.create(act, editor) { onDialogDismiss() }

                val bridge = createBridge(dialog)
                editor.addJavascriptInterface(bridge, "VencordMobileNative")

                editor.webViewClient = object : android.webkit.WebViewClient() {
                    // Fail closed: only ever load the expected bundled asset.
                    // Any navigation away from it (a link tap, meta refresh, or
                    // a future asset change) is blocked so the
                    // un-token-gated editor bridge is never exposed to a
                    // remote/attacker document.
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest
                    ): Boolean {
                        return request.url.toString() != assetUrl
                    }

                    // Block every subresource except the bundled asset itself,
                    // so a static editor page cannot pull remote
                    // scripts/CSS/frames into the privileged (un-token-gated)
                    // editor WebView.
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest
                    ): android.webkit.WebResourceResponse? {
                        return if (request.url.toString() == assetUrl) null
                        else android.webkit.WebResourceResponse(
                            "text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Bind the origin flag to the exact asset URL, and reset it if
                        // we ever finish on something else, so the bridge stays dead
                        // until the correct local document is committed.
                        bridge.originCommitted = url == assetUrl
                        if (bridge.originCommitted) {
                            view?.let { onPageFinished(it) }
                        }
                    }
                }

                onShown()
                dialog.show()
                editor.loadUrl(assetUrl)
            } catch (e: Throwable) {
                val d = dialog
                if (d != null && d.isShowing) {
                    // Dismissal runs the listener chain, which destroys wv,
                    // resets the active flag, and unregisters the dialog.
                    d.dismiss()
                } else {
                    // A never-shown dialog never fires its dismiss listener.
                    wv?.destroy()
                    if (d != null) act.unregisterDialog(d)
                    onDialogDismiss()
                }
                VDELog.e("VN", "openAssetEditor($assetUrl) failed", e)
            }
        }
    }

    private class QuickCssBridge(
        private val activity: MainActivity,
        private val dialog: android.app.Dialog
    ) : EditorOrigin {
        // Set from onPageFinished (UI thread). WebView.getUrl() must be called
        // on the UI thread, but @JavascriptInterface methods run on a Chromium
        // internal thread, so checking getUrl() directly is unreliable.
        @Volatile override var originCommitted = false

        private fun isExpectedOrigin(): Boolean = originCommitted

        @android.webkit.JavascriptInterface
        fun quickCssSet(css: String?) {
            if (!isExpectedOrigin()) return
            val safe = css ?: ""
            activity.runOnUiThread {
                val mainWv = activity.findViewById<WebView>(R.id.webview)
                if (mainWv == null) {
                    // No main WebView: the CSS cannot be saved. Keep the
                    // editor open so the user's edits survive.
                    VDELog.e("VN", "quickCssSet: main WebView unavailable, QuickCSS not saved")
                    Toast.makeText(
                        activity,
                        "Couldn't save QuickCSS: main app window unavailable. Your edits are kept.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                try {
                    mainWv.evaluateJavascript(quickCssSaveJs(gson.toJson(safe))) { result ->
                        // Async callback: the editor may have been dismissed
                        // by now; the isShowing guards below cover that.
                        if (activity.isFinishing || activity.isDestroyed) return@evaluateJavascript
                        if (result?.trim() == "true") {
                            Toast.makeText(activity, "Saved QuickCSS", Toast.LENGTH_SHORT).show()
                            if (dialog.isShowing) dialog.dismiss()
                        } else {
                            // Save not confirmed (see quickCssSaveJs): keep the
                            // editor open instead of toasting a false "Saved".
                            VDELog.e("VN", "quickCssSet: save not confirmed (evaluateJavascript result: $result)")
                            Toast.makeText(
                                activity,
                                "Couldn't save QuickCSS: Vencord isn't ready in the main window. Your edits are kept.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (t: Throwable) {
                    // The WebView can be destroyed between the null check and
                    // the call; treat that as a failed save too.
                    VDELog.e("VN", "quickCssSet: evaluateJavascript failed", t)
                    Toast.makeText(
                        activity,
                        "Couldn't save QuickCSS: Vencord isn't ready in the main window. Your edits are kept.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun quickCssClose() {
            if (!isExpectedOrigin()) return
            activity.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }

    @JavascriptInterface
    fun getCssCache(token: String?, cacheKey: String?): String? {
        if (!isBridgeAuthorized(token)) return null
        val safeKey = cacheKey ?: return null
        if (!safeKey.startsWith("css_cache_")) return null
        if (cssCacheEvictionCounter.incrementAndGet() % 50 == 0) {
            safeExecute { evictStaleCssCache() }
        }
        return guardedPrefs("getCssCache", safeKey, null, false, false) { prefs ->
            prefs.getString(safeKey, null)
        }
    }

    @JavascriptInterface
    fun dismissLoadingScreen(token: String?) {
        if (!isBridgeAuthorized(token)) return
        if (!isOnDiscordDomain()) return
        val act = activity.get() ?: return
        act.runOnUiThread {
            act.loadingScreen.dismiss()
        }
    }

    @JavascriptInterface
    fun isDebugBuild(token: String?): Boolean {
        if (!isBridgeAuthorized(token)) return false
        if (!isOnDiscordDomain()) return false
        return com.nin0dev.vendroid.BuildConfig.DEBUG
    }

    private fun openLogs() {
        // Gate on Discord domain. A failed Discord load still leaves
        // currentHostForBridge on a Discord host, so troubleshooting
        // remains possible; non-Discord whitelisted pages cannot open
        // the viewer or read app diagnostics.
        if (!isOnDiscordDomain()) return
        val act = activity.get() ?: return
        if (logsDialogActive) return
        openAssetEditor(
            act = act,
            assetUrl = "file:///android_asset/log_viewer.html",
            createBridge = { dialog -> LogViewerBridge(act, dialog) },
            onShown = { logsDialogActive = true },
            onDialogDismiss = { logsDialogActive = false },
            onPageFinished = { view ->
                val logs = VDELog.getRecentLogs(500)
                view.evaluateJavascript(
                    "window.vdeSetLogs?.(${gson.toJson(logs)})", null
                )
            }
        )
    }

    private class LogViewerBridge(
        private val activity: MainActivity,
        private val dialog: android.app.Dialog
    ) : EditorOrigin {
        @Volatile override var originCommitted = false

        private fun isExpectedOrigin(): Boolean = originCommitted

        @android.webkit.JavascriptInterface
        fun close() {
            if (!isExpectedOrigin()) return
            if (dialog.isShowing) dialog.dismiss()
        }

        @android.webkit.JavascriptInterface
        fun clearLogs() {
            if (!isExpectedOrigin()) return
            VDELog.clearLogs()
        }

        @android.webkit.JavascriptInterface
        fun refreshLogs(): String {
            if (!isExpectedOrigin()) return ""
            return VDELog.getRecentLogs(500)
        }

        @android.webkit.JavascriptInterface
        fun shareLogs() {
            if (!isExpectedOrigin()) return
            val text = VDELog.getLogFileContents()
            activity.runOnUiThread {
                ShareHelper.shareLogs(activity, text)
            }
        }
    }

    private fun openFirewallEditor() {
        // Strict check: this mutates the firewall config, so a non-Discord
        // page must not invoke it even if currentHostForBridge is stale.
        if (!isOnDiscordDomainStrict()) return
        val act = activity.get() ?: return
        if (firewallDialogActive) return
        openAssetEditor(
            act = act,
            assetUrl = "file:///android_asset/firewall_editor.html",
            createBridge = { dialog -> FirewallEditorBridge(act, dialog) },
            onShown = { firewallDialogActive = true },
            onDialogDismiss = { firewallDialogActive = false },
            onPageFinished = { view ->
                val json = gson.toJson(FirewallConfig.toJson())
                view.evaluateJavascript(
                    "window.vdeFirewallInit?.($json)", null
                )
            }
        )
    }

    private class FirewallEditorBridge(
        private val activity: MainActivity,
        private val dialog: android.app.Dialog
    ) : EditorOrigin {
        @Volatile override var originCommitted = false
        @Volatile private var lastError: String? = null

        private fun isExpectedOrigin(): Boolean = originCommitted

        @android.webkit.JavascriptInterface
        fun close() {
            try {
                if (!isExpectedOrigin()) return
                if (dialog.isShowing) dialog.dismiss()
            } catch (t: Throwable) {
                lastError = "close: ${t.message}"
            }
        }

        @android.webkit.JavascriptInterface
        fun getFirewallConfig(): String {
            try {
                if (!isExpectedOrigin()) return "{\"error\":\"origin\"}"
                return FirewallConfig.toJson()
            } catch (t: Throwable) {
                lastError = "getFirewallConfig: ${t.javaClass.name}: ${t.message}"
                return "{\"categories\":[],\"customDomains\":[],\"error\":\"${sanitize(lastError)}\"}"
            }
        }

        // Bound the error string and keep it JSON-safe for config.error.
        private fun sanitize(s: String?): String {
            val raw = s ?: ""
            return raw.take(200)
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ")
        }

        @android.webkit.JavascriptInterface
        fun saveFirewallConfig(json: String): Boolean {
            try {
                if (!isExpectedOrigin()) return false
                val ok = FirewallConfig.fromJsonAndSave(json)
                if (ok) {
                    activity.runOnUiThread {
                        android.widget.Toast.makeText(
                            activity, "Firewall saved", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return ok
            } catch (t: Throwable) {
                lastError = "saveFirewallConfig: ${t.message}"
                return false
            }
        }

        @android.webkit.JavascriptInterface
        fun resetFirewallConfig() {
            try {
                if (!isExpectedOrigin()) return
                FirewallConfig.resetToDefaults()
                activity.runOnUiThread {
                    android.widget.Toast.makeText(
                        activity, "Firewall reset to defaults", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (t: Throwable) {
                lastError = "resetFirewallConfig: ${t.message}"
            }
        }
    }
}
