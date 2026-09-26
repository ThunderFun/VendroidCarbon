package com.nin0dev.vendroid.webview

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.core.content.edit
import com.nin0dev.vendroid.BuildConfig
import com.nin0dev.vendroid.R
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.VDELog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.HttpURLConnection

object HttpClient {
    // Generous ceiling for any text body read into memory (bundle, CSS).
    const val MAX_READ_BYTES = 16 * 1024 * 1024 // 16 MB

    /**
     * Maximum redirect hops followed manually. Each hop can consume the full
     * connect+read timeouts (30s), and fetchVencord runs the loop inline on
     * the startup path, so the cap bounds worst-case latency. Further hops
     * throw IOException and hit the cached-bundle fallback.
     */
    private const val MAX_REDIRECT_HOPS = 5

    /**
     * Cap on the Location header text embedded in redirect failure messages;
     * the header is server-controlled and unbounded.
     */
    private const val MAX_LOGGED_LOCATION_CHARS = 300
    /**
     * Shared app-wide OkHttp client. Reuses pooled TCP/TLS connections across
     * requests to the same host, avoiding a fresh connect + TLS handshake.
     *
     * Security: redirects are disabled at the client (OkHttp defaults both to
     * true) and re-validated manually against the host allowlist by callers;
     * auto-following would be an allowlist bypass. No cookie jar (parity with
     * HttpURLConnection; Chromium owns session cookies) and no OkHttp cache
     * (the app has its own response caches).
     */
    val sharedClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // Bounds the whole exchange, including the body read: readTimeout
        // resets per socket read, so a host dribbling bytes can hold an
        // executor thread indefinitely (the 16MB cap limits bytes, not
        // time). Per call, so each redirect hop gets its own window.
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)       // keep false; see security note
        .followSslRedirects(false)    // keep false; OkHttp default is true
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    @Volatile
    var VencordRuntime: String? = null
        private set
    @Volatile
    var VencordMobileRuntime: String? = null
        private set

    /**
     * Session kill switch for safe mode. Raised at process start and
     * idempotently by MainActivity's safe-mode branch; never cleared for the
     * life of the process. Deliberately not re-read from the "safeMode" pref,
     * which MainActivity resets one-shot at startup. Readiness checks must
     * consult this flag so a safe-mode session survives activity recreation,
     * when the pref is false again.
     */
    @Volatile
    var vencordDisabled: Boolean = false

    /**
     * True once a bundle fetch or revalidation has completed in this process
     * (via [fetchVencord]). The warm-navigation
     * fast path keys on this rather than `VencordRuntime != null`, which the
     * disk preloads also set and which says nothing about freshness. Across
     * process death the same idea carries via [PREF_LAST_BUNDLE_CHECK] and
     * [BUNDLE_CHECK_INTERVAL_MS].
     */
    @Volatile
    private var bundleCheckedThisSession = false

    /**
     * Serializes bundle file and prefs writes between the startup fetch and
     * download paths, so the file, ETag, and patch flags always describe the
     * same download. Also orders the startup path's runtime publish with the
     * write; see [vencordRuntimeLock] for the lock order.
     */
    private val bundleWriteLock = Any()

    /**
     * Serializes runtime publishes and the pair-read behind [runtimeSnapshot].
     * The fields are @Volatile, but the disk-loading publishers are
     * check-then-read-then-publish, so a publish whose read stalled on slow
     * storage can land after a fresher publish or an invalidation and
     * resurrect stale content.
     *
     * Lock order: [downloadStoreAndSync] acquires this lock while holding
     * [bundleWriteLock], never the reverse. The `stillValid` callbacks run
     * under this lock and must not acquire [bundleWriteLock] or call
     * [downloadStoreAndSync].
     */
    private val vencordRuntimeLock = Any()

    /**
     * Unconditional write. Clears go through this, including MainActivity's
     * safe-mode branch, so it must not check [vencordDisabled]; enforcement
     * belongs at the publish sites ([setVencordRuntimeIfNull],
     * [setVencordRuntimeIfEnabled]).
     */
    @JvmStatic
    fun setVencordRuntime(value: String?) {
        synchronized(vencordRuntimeLock) { VencordRuntime = value }
    }

    @JvmStatic
    fun setVencordMobileRuntime(value: String?) {
        synchronized(vencordRuntimeLock) { VencordMobileRuntime = value }
    }

    /**
     * Compare-and-set publish for the mobile runtime: installs [value] only
     * while the mobile runtime is still unset and safe mode is not raised.
     * Returns true when this call performed the publish.
     */
    @JvmStatic
    fun setVencordMobileRuntimeIfNull(value: String?): Boolean =
        synchronized(vencordRuntimeLock) {
            if (VencordMobileRuntime == null && !vencordDisabled) {
                VencordMobileRuntime = value
                true
            } else false
        }

    /**
     * Compare-and-set publish for the main runtime: installs [value] only
     * while the runtime is still unset and safe mode is not raised, re-running
     * the caller's pre-read guards via [stillValid] under the lock. Returns
     * true when this call performed the publish.
     *
     * [stillValid] covers what the null-check cannot: the clientMod switch
     * nulls an already-null runtime, so only a re-check of the guards catches
     * a read that stalled across the switch. It runs under [vencordRuntimeLock];
     * the acquisition rule lives on that lock.
     *
     * The fresh-download publish in [downloadStoreAndSync] is deliberately not
     * a compare-and-set; it is authoritative and publishes through
     * [setVencordRuntime], so a stalled CAS cannot overwrite it.
     */
    @JvmStatic
    fun setVencordRuntimeIfNull(value: String?, stillValid: (() -> Boolean)? = null): Boolean =
        synchronized(vencordRuntimeLock) {
            if (VencordRuntime == null && !vencordDisabled && (stillValid == null || stillValid())) {
                VencordRuntime = value
                true
            } else false
        }

    /**
     * Publish for the authoritative download path: installs [value] only
     * while the safe-mode kill switch is down. No null-check, unlike the CAS
     * publishers; a fresh download must not be blocked by a stale in-memory
     * runtime (see [setVencordRuntimeIfNull]).
     *
     * The check runs under [vencordRuntimeLock], so safe mode cannot be
     * entered between the check and the write: the publish either precedes
     * the raise, and the safe-mode clear nulls it, or it observes the raised
     * flag and skips. Returns true when this call performed the publish.
     */
    @JvmStatic
    fun setVencordRuntimeIfEnabled(value: String?): Boolean =
        synchronized(vencordRuntimeLock) {
            if (!vencordDisabled) {
                VencordRuntime = value
                true
            } else false
        }

    /**
     * Consistent pair-read of both runtimes for the injection decision. A
     * publish that has installed the mobile runtime but not yet the main one
     * must never be observed torn.
     */
    @JvmStatic
    fun runtimeSnapshot(): Pair<String?, String?> =
        synchronized(vencordRuntimeLock) { VencordRuntime to VencordMobileRuntime }


    /** SharedPreferences key recording that the on-disk bundle is already patched. */
    const val PREF_BUNDLE_PATCHED = "vencordBundlePatched"

    /** SharedPreferences key recording which patch set the on-disk bundle carries. */
    const val PREF_BUNDLE_PATCH_SET = "vencordBundlePatchSet"

    /** SharedPreferences key of the stored bundle ETag. */
    const val PREF_ETAG = "vencordEtag"

    /** SharedPreferences key of the bundle URL the stored ETag belongs to. */
    const val PREF_ETAG_LOCATION = "vencordEtagLocation"

    /** SharedPreferences key of the URL whose response issued [PREF_ETAG]
     *  (the last hop of the fetch; can differ from [PREF_ETAG_LOCATION]
     *  after a redirect). */
    const val PREF_ETAG_REQUEST_URL = "vencordEtagRequestUrl"

    /** SharedPreferences key of the epoch-ms of the last definitive freshness
     *  answer (304 or fresh download); boots inside [BUNDLE_CHECK_INTERVAL_MS]
     *  skip the conditional GET entirely. Always read through [runCatching].
     *  Hand-edited or restored XML can carry a non-Long value, which must
     *  degrade to "never checked" (window due), never to "fresh". */
    const val PREF_LAST_BUNDLE_CHECK = "lastBundleCheckMs"

    /** How long a definitive freshness answer (304 / fresh download) lets
     *  later boots skip the bundle's conditional GET. Deliberately shorter
     *  than the CSS cache's 12h: this gates arbitrary JS in the Discord
     *  origin, so the window bounds security-update latency at ~6h + one
     *  boot. The session flag already grants long-lived processes unbounded
     *  staleness; this carries the same idea across process death. */
    internal const val BUNDLE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** SharedPreferences key of the bundle build tag (e.g. "Vencord@ada5cfe"). */
    const val PREF_BUNDLE_BUILD = "vencordBundleBuild"

    /** SharedPreferences key of the bundle SHA-256 (first 12 hex chars). */
    const val PREF_BUNDLE_HASH = "vencordBundleHash"

    /** SharedPreferences key of the app version that last fetched the bundle. */
    const val PREF_LAST_BUNDLE_UPDATE = "lastMajorUpdateThatUserHasUpdatedVencord"


    /**
     * String read that treats a wrong-typed value as absent. The bridge
     * allowlist rejects the bundle bookkeeping keys, so poison only comes
     * from hand-edited or restored XML; throwing would skip fetchVencord's
     * cached-bundle fallback.
     */
    private fun stringPrefOrNull(sPrefs: SharedPreferences, key: String): String? =
        try {
            sPrefs.getString(key, null)
        } catch (_: ClassCastException) {
            VDELog.w("HTTP", "$key pref wrong-typed; ignoring stored value")
            null
        }

    /**
     * Bundle version stamp, 0 when absent or wrong-typed. Both readers run
     * on the startup path, so a poison value must force a redownload, not
     * throw.
     */
    private fun bundleVersionStamp(sPrefs: SharedPreferences): Int =
        try {
            sPrefs.getInt(PREF_LAST_BUNDLE_UPDATE, 0)
        } catch (_: ClassCastException) {
            VDELog.w("HTTP", "Bundle version stamp wrong-typed; forcing redownload")
            0
        }

    /**
     * True when the persisted patched flag covers the current patch set.
     * Wrong-typed fields count as not current, so the file is re-checked and
     * re-patched rather than the read throwing.
     */
    private fun isPersistedPatchCurrent(sPrefs: SharedPreferences): Boolean {
        val patched = try {
            sPrefs.getBoolean(PREF_BUNDLE_PATCHED, false)
        } catch (_: ClassCastException) {
            VDELog.w("HTTP", "Bundle patch flag wrong-typed; re-patching")
            false
        }
        return patched && stringPrefOrNull(sPrefs, PREF_BUNDLE_PATCH_SET) == BundlePatcher.bundlePatchSetKey
    }

    /** Resolves the effective bundle URL from prefs, honoring clientMod. */
    fun resolveBundleLocation(sPrefs: SharedPreferences): String {
        // Choke point: a wrong-typed clientMod must never escape onto the
        // startup fetch path in fetchVencord; a crash there killed the
        // process on every cold start. The setBool STRING_SETTING_KEYS
        // guard stops new poison and VendroidApp's boot-time heal removes
        // old; this catch contains any future regression to a logged
        // default instead of an uncaught ClassCastException.
        val clientMod = try {
            sPrefs.getString("clientMod", "vencord")
        } catch (e: ClassCastException) {
            VDELog.w("HTTP", "clientMod pref wrong-typed (${e.javaClass.simpleName}); using default")
            "vencord"
        }
        val defaultUrl = if (clientMod == "equicord") {
            Constants.EQUICORD_BUNDLE_URL
        } else {
            Constants.JS_BUNDLE_URL
        }
        // Same guard as the clientMod read above.
        val customLocation = try {
            sPrefs.getString("vencordLocation", null)
        } catch (e: ClassCastException) {
            VDELog.w("HTTP", "vencordLocation pref wrong-typed (${e.javaClass.simpleName}); using default")
            null
        }
        // Normalize so trivial variants of a known URL (whitespace, trailing
        // slash) do not count as a custom location.
        return customLocation
            ?.trim()?.removeSuffix("/")
            ?.takeIf { it.isNotEmpty() }
            ?: defaultUrl
    }

    private fun isCustomBundleLocation(location: String): Boolean =
        !location.equals(Constants.JS_BUNDLE_URL, ignoreCase = true) &&
            !location.equals(Constants.EQUICORD_BUNDLE_URL, ignoreCase = true)

    /**
     * Single definition of "the bundle fetch path can accept this location":
     * returns null when fetchVencord's validation gates would pass, else a
     * short URL-free reason (safe to log or show; a custom value can carry
     * credentials in its query string).
     *
     * Both parsers must accept the URL. Android's Uri historically gated the
     * fetch, but OkHttp's HttpUrl builds the actual request: a value Uri
     * tolerates and HttpUrl rejects used to escape validation as an
     * IllegalArgumentException (not IOException) from Request.Builder().url(),
     * and values the parsers read differently could fetch a host the
     * allowlist never saw. Requiring agreement fails closed in both
     * directions.
     *
     * Custom-location detection (isCustomBundleLocation) deliberately stays
     * on the raw string form; this function never canonicalizes, so a value
     * differing from an official URL only by OkHttp-normalizable syntax keeps
     * forcing revalidation as before.
     *
     * Shared with the boot-time heal (VendroidApp.healUnusableVencordLocation)
     * so the fetch gate and the heal cannot drift apart.
     */
    internal fun bundleLocationFetchProblem(location: String): String? {
        val uriHost = Uri.parse(location).host
        if (uriHost == null || !Constants.isAllowedVencordHost(uriHost)) {
            return "host '${uriHost ?: "<none>"}' is not in the allowed list"
        }
        val url = location.toHttpUrlOrNull()
            ?: return "not a fetchable URL"
        if (!Constants.isAllowedVencordHost(url.host)) {
            return "host '${url.host}' is not in the allowed list"
        }
        if (!location.startsWith("https://")) {
            return "must use HTTPS"
        }
        return null
    }

    /**
     * Single definition of "the cached bundle must not be reused as-is": app
     * version bump, custom bundle URL, or debug build. Fetch, preload, and
     * activity code all consult this so the paths cannot drift apart.
     */
    fun needsBundleRedownload(sPrefs: SharedPreferences): Boolean =
        bundleVersionStamp(sPrefs) < BuildConfig.VERSION_CODE ||
            isCustomBundleLocation(resolveBundleLocation(sPrefs)) ||
            BuildConfig.DEBUG

    /**
     * Single definition of "this boot may skip the bundle's conditional GET":
     * a runtime is in memory, no forced redownload is pending, and either a
     * check completed this session or the last definitive answer is inside
     * the freshness window. Pure so unit tests can pin the verdict, above all
     * the polarity: an absent stamp (lastCheckMs <= 0) must read as DUE, never
     * fresh. Inverting that compiles clean, passes every other test, and
     * silently disables revalidation forever.
     */
    internal fun bundleCheckSkippable(
        runtimeInMemory: Boolean,
        needsRedownload: Boolean,
        checkedThisSession: Boolean,
        lastCheckMs: Long,
        nowMs: Long
    ): Boolean =
        runtimeInMemory && !needsRedownload &&
            (checkedThisSession || !isBundleCheckDue(lastCheckMs, nowMs))

    /**
     * True when the persisted stamp no longer lets a boot skip the conditional
     * GET: never checked, an untrustworthy stamp, or an elapsed window. Pure
     * so unit tests can pin the window math without prefs or network.
     *
     * A backwards wall clock (manual change, dead RTC booting to epoch) makes
     * the delta negative; treating that as due keeps a security update from
     * being postponed until wall time catches up.
     */
    internal fun isBundleCheckDue(lastCheckMs: Long, nowMs: Long): Boolean {
        if (lastCheckMs <= 0L) return true
        val delta = nowMs - lastCheckMs
        if (delta < 0L) return true
        return delta >= BUNDLE_CHECK_INTERVAL_MS
    }

    /** Invalidates the bundle's freshness bookkeeping while keeping the file
     *  on disk as the offline fallback. Also clears the freshness-window
     *  stamp, so a discarded corrupt file cannot ride a window earned by a
     *  previous, different bundle. */
    private fun invalidateBundleCache(sPrefs: SharedPreferences) {
        sPrefs.edit { clearBundleIdentityKeys() }
    }

    /**
     * Extract the build tag from the bundle's leading comment header
     * (e.g. "// Vencord a1b2c3d" -> "Vencord@a1b2c3d").
     */
    private val buildTagRegex = Regex("^//\\s*(Vencord|Equicord)\\s+([A-Za-z0-9._-]+)")
    private fun extractBuildTag(content: String): String? {
        for (line in content.lineSequence().take(4)) {
            val m = buildTagRegex.find(line)
            if (m != null) return m.groupValues[1] + "@" + m.groupValues[2]
        }
        return null
    }

    /** First 12 hex chars of the content's SHA-256; "unknown" on failure. */
    private fun shortSha256(content: String): String =
        try {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                .take(12)
        } catch (_: Exception) {
            "unknown"
        }

    /** Logs the on-disk bundle's identity once per process. */
    @Volatile
    private var bundleIdentityLogged = false
    private fun logBundleIdentity(logLabel: String, content: String) {
        if (bundleIdentityLogged) return
        bundleIdentityLogged = true
        val tag = extractBuildTag(content) ?: "unknown"
        VDELog.i("HTTP", "$logLabel build=$tag sha256=${shortSha256(content)} size=${content.length}")
    }

    /**
     * Loads the on-disk bundle, applying patches only when the persisted
     * patched flag does not cover the current patch set. When the file turns
     * out to carry every marker already (stale flag, e.g. a forced redownload
     * that never completed), the flag is re-persisted so later cold starts
     * skip the ~1MB regex scan.
     */
    @JvmStatic
    fun readBundleFromDisk(
        sPrefs: SharedPreferences,
        vendroidFile: File
    ): String {
        // downloadStoreAndSync bounds its writes by MAX_READ_BYTES, but an
        // interrupted write or a full disk can leave an arbitrary file behind,
        // and readText() has no cap: oversized content would OOM the calling
        // thread. Same bound as readAsText; fail closed.
        val fileSize = vendroidFile.length()
        if (fileSize > MAX_READ_BYTES) {
            throw IOException("Cached bundle exceeds $MAX_READ_BYTES byte limit ($fileSize bytes)")
        }
        val raw = vendroidFile.readText()
        logBundleIdentity("Cached bundle", raw)
        if (isPersistedPatchCurrent(sPrefs)) return raw
        val patched = applyPatches(raw)
        // Content that actually got patched here is patched in memory only;
        // only a full marker set proves the on-disk file is current.
        if (BundlePatcher.vencordRuntimePatches.all { raw.contains(it.marker) }) {
            sPrefs.edit()
                .putBoolean(PREF_BUNDLE_PATCHED, true)
                .putString(PREF_BUNDLE_PATCH_SET, BundlePatcher.bundlePatchSetKey)
                .apply()
        }
        return patched
    }

    @JvmStatic
    @Throws(IOException::class)
    fun fetchVencord(activity: Activity) {
        // Self-gate on the kill switch so no caller can trigger a bundle
        // download in a safe-mode session. The publish in
        // [downloadStoreAndSync] re-checks under vencordRuntimeLock, which
        // also covers safe mode entered mid-fetch.
        if (vencordDisabled) {
            VDELog.i("HTTP", "fetchVencord skipped: safe mode kill switch raised")
            return
        }
        val sPrefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val vencordLocation = resolveBundleLocation(sPrefs)
        val vencordHost = Uri.parse(vencordLocation).host
        // Log only the host, not the full URL (a custom URL could carry a token
        // in a query string, which would leak into the shareable log).
        VDELog.i("HTTP", "Fetching bundle from host: $vencordHost")
        // Gate before any network work: the bundle is arbitrary JS executed
        // in the Discord origin. bundleLocationFetchProblem owns the contract.
        bundleLocationFetchProblem(vencordLocation)?.let { problem ->
            throw IOException(
                "Vencord location rejected: $problem (${UrlNormalizer.redactForLog(vencordLocation)})"
            )
        }
        val vendroidFile = File(activity.filesDir, "vencord.js")
        // Discard a zero-length file (interrupted write) or an oversized one
        // (botched write; readBundleFromDisk refuses it). Deleting makes the
        // failure self-healing: the fetch below installs a fresh bundle rather
        // than every cold start failing on the same corrupt file.
        val cachedLength = if (vendroidFile.exists()) vendroidFile.length() else -1L
        if (cachedLength == 0L || cachedLength > MAX_READ_BYTES) {
            VDELog.w("HTTP", "Cached vencord.js is unusable ($cachedLength bytes), discarding")
            vendroidFile.delete()
            invalidateBundleCache(sPrefs)
        }

        // App version bumps invalidate the cache (patch definitions may have
        // changed). Custom URLs and debug builds keep the ETag so an unchanged
        // bundle costs a 304, not ~1MB. The cached file stays on disk as the
        // offline fallback; downloadStoreAndSync overwrites it atomically.
        val versionBump = bundleVersionStamp(sPrefs) < BuildConfig.VERSION_CODE
        val customUrl = isCustomBundleLocation(vencordLocation)
        val needsRedownload = versionBump || customUrl || BuildConfig.DEBUG

        if (needsRedownload) {
            if (customUrl || BuildConfig.DEBUG) {
                val msg = if (customUrl) {
                    "Debugging app or Vencord, bundle will be revalidated. Avoid using on limited networks"
                } else {
                    "Debugging app, bundle will be revalidated. Avoid using on limited networks"
                }
                activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_LONG).show() }
            }
            if (versionBump) invalidateBundleCache(sPrefs)
        }

        // Warm-navigation fast path: skip the round trip when a check already
        // completed this session, or the last definitive answer (304 / fresh
        // download) is inside the freshness window. A runtime preloaded from
        // disk is no freshness proof, so VencordRuntime != null alone never
        // suffices; bundleCheckSkippable owns the verdict.
        val checkedThisSession = bundleCheckedThisSession
        // runCatching per the PREF_LAST_BUNDLE_CHECK contract: a non-Long
        // here must read as never-checked, not fresh.
        val lastCheck = runCatching { sPrefs.getLong(PREF_LAST_BUNDLE_CHECK, 0L) }.getOrDefault(0L)
        if (bundleCheckSkippable(
                runtimeInMemory = VencordRuntime != null,
                needsRedownload = needsRedownload,
                checkedThisSession = checkedThisSession,
                lastCheckMs = lastCheck,
                nowMs = System.currentTimeMillis()
            )
        ) {
            if (checkedThisSession) {
                VDELog.d("HTTP", "Bundle already verified this session, skipping fetch")
            } else {
                VDELog.d(
                    "HTTP",
                    "Bundle checked ${System.currentTimeMillis() - lastCheck}ms ago, " +
                        "inside freshness window; skipping fetch"
                )
            }
            bundleCheckedThisSession = true
            // Must survive the early return: on a cold boot this can fire
            // before any inject pass, after the preload won runSafetyNetLoad's
            // CAS and the safety net returned without injecting, leaving
            // missedInjection set. This call is then the only recovery trigger
            // until the next navigation; it is idempotent.
            activity.runOnUiThread {
                (activity as? com.nin0dev.vendroid.MainActivity)?.injectVencordIfReady()
            }
            return
        }

        // A validator is only ever sent to the URL whose response issued it.
        val storedEtag = storedEtagFor(sPrefs, vencordLocation, vencordLocation)
        var resp: Response? = null
        // Set while a cache read inside the try is in flight, so the catch
        // can tell a cache-read failure from a network failure.
        var readingCache = false
        try {
            // ETag-conditional GET: 304 keeps the cache (cheap), 200 swaps in
            // a newer build. Detects new Vencord builds without wiping app
            // data.
            resp = executeVencordGetResolvingRedirect(vencordLocation, storedEtag) { hopUrl ->
                storedEtagFor(sPrefs, vencordLocation, hopUrl)
            }
            var responseCode = resp.code
            val responseEtag = resp.header("ETag")
            VDELog.i(
                "HTTP",
                "Bundle check: branch=preflight code=$responseCode " +
                    "etagSent=${storedEtag != null} etagRecv=${responseEtag != null}"
            )

            when {
                responseCode == HttpURLConnection.HTTP_NOT_MODIFIED && vendroidFile.exists() -> {
                    VDELog.i("HTTP", "Bundle branch: 304 (cache hit, fresh)")
                    if (VencordRuntime == null) {
                        readingCache = true
                        val cached = readBundleFromDisk(sPrefs, vendroidFile)
                        readingCache = false
                        setVencordRuntimeIfNull(cached) {
                            vendroidFile.exists()
                        }
                    }
                    bundleCheckedThisSession = true
                    // Definitive freshness answer: stamp the window so later
                    // boots skip the round trip entirely. The fallback
                    // branches below deliberately set neither this stamp nor
                    // bundleCheckedThisSession. A failed check must not
                    // masquerade as a fresh one in this process or the next.
                    sPrefs.edit()
                        .putLong(PREF_LAST_BUNDLE_CHECK, System.currentTimeMillis())
                        .apply()
                }

                responseCode == HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    // 304 with no local file; re-request unconditionally,
                    // following validated redirects like the primary path.
                    resp?.close()
                    resp = executeVencordGetResolvingRedirect(vencordLocation, null)
                    responseCode = resp.code
                    if (responseCode !in 200..299) {
                        throw bundleHttpFailure(responseCode, vencordLocation)
                    }
                    downloadStoreAndSync(resp, vendroidFile, sPrefs, bundleLocation = vencordLocation)
                }

                responseCode in 200..299 -> {
                    downloadStoreAndSync(resp, vendroidFile, sPrefs, bundleLocation = vencordLocation)
                }

                else -> {
                    // Fall back to the cached bundle on transient server errors
                    // so startup doesn't break; otherwise surface the failure.
                    if (vendroidFile.exists() && VencordRuntime == null) {
                        VDELog.e("HTTP", "Bundle branch: fallback-cache (HTTP $responseCode)")
                        readingCache = true
                        val cached = readBundleFromDisk(sPrefs, vendroidFile)
                        readingCache = false
                        setVencordRuntimeIfNull(cached) {
                            vendroidFile.exists()
                        }
                    } else {
                        throw bundleHttpFailure(responseCode, vencordLocation)
                    }
                }
            }
        } catch (io: IOException) {
            // The failed operation was the cache read itself (304 hit or
            // HTTP-error fallback). These failures are deterministic, so
            // retrying would only throw again and escape as a fresh
            // exception masking the original; rethrow io as-is. This also
            // skips the mislabeled "network error" log line below.
            if (readingCache) throw io
            // Fall back to the cache only when there is something to load;
            // rethrowing otherwise is fine: a missing file means nothing to
            // load, and an in-memory runtime makes this a refresh, which
            // MainActivity logs.
            if (vendroidFile.exists() && VencordRuntime == null) {
                VDELog.e("HTTP", "Bundle branch: fallback-cache (network error: ${io.message})")
                // Unlike the guarded reads above, a failure here propagates:
                // the cache is unreadable and there is nothing left to fall
                // back to. Fail closed.
                setVencordRuntimeIfNull(readBundleFromDisk(sPrefs, vendroidFile)) {
                    vendroidFile.exists()
                }
            } else {
                throw io
            }
        } finally {
            // Close to return the pooled connection (not disconnect()).
            resp?.close()
        }
        activity.runOnUiThread {
            (activity as? com.nin0dev.vendroid.MainActivity)?.injectVencordIfReady()
        }
    }

    /**
     * Non-2xx failure for the bundle fetch path. The location is redacted for
     * the shareable log (best-effort: userinfo and token-like query/fragment
     * params; a secret in the path still shows). Fresh instance per call so
     * stack traces point at the real throw site.
     */
    private fun bundleHttpFailure(code: Int, location: String): IOException =
        IOException("HTTP $code fetching Vencord bundle from ${UrlNormalizer.redactForLog(location)}")

    /**
     * Executes a conditional GET for the Vencord bundle over the shared pooled
     * client. Redirects are never auto-followed (client config).
     */
    private fun executeVencordGet(url: String, etag: String?): Response {
        val rb = Request.Builder().url(url)
        if (etag != null) rb.header("If-None-Match", etag)
        return sharedClient.newCall(rb.build()).execute()
    }

    /**
     * The stored ETag, but only when the stored state still belongs to
     * [originUrl] (a location or clientMod switch must not reuse validators
     * across resources) and it was issued by [requestUrl]'s own response.
     * An ETag compared against a resource that did not issue it can produce
     * a false 304 that pins a stale bundle as fresh.
     *
     * The stored request URL is OkHttp's canonical form (resp.request.url),
     * but hop 1 passes the raw bundle location, which can differ from it
     * (host-only URL, uppercase host, default port, dot segments). The
     * comparison also accepts [requestUrl]'s parsed form; two spellings
     * match only when they denote the same resource. Redirect hop URLs are
     * already canonical, so parsing is a no-op there.
     */
    private fun storedEtagFor(
        sPrefs: SharedPreferences,
        originUrl: String,
        requestUrl: String
    ): String? {
        val storedRequestUrl = stringPrefOrNull(sPrefs, PREF_ETAG_REQUEST_URL)
        val canonicalRequestUrl = requestUrl.toHttpUrlOrNull()?.toString()
        return stringPrefOrNull(sPrefs, PREF_ETAG)
            ?.takeIf { stringPrefOrNull(sPrefs, PREF_ETAG_LOCATION) == originUrl }
            ?.takeIf {
                storedRequestUrl == requestUrl ||
                    (canonicalRequestUrl != null && storedRequestUrl == canonicalRequestUrl)
            }
    }

    /**
     * Resolves a redirect Location against the current hop's URL (relative
     * references per RFC 3986) and gates the target on HTTPS + the host
     * allowlist.
     *
     * internal + pure so VencordHostAllowlistTest can pin the per-hop
     * re-validation contract (every hop of the GitHub 302 chain must clear
     * this gate) without network.
     */
    internal fun resolveRedirectTarget(currentUrl: String, location: String): HttpUrl {
        // Hop 1 is the caller's raw bundle location, so failure messages
        // redact it (see bundleHttpFailure).
        if (location.isBlank()) {
            throw IOException("Redirect with blank Location header from ${UrlNormalizer.redactForLog(currentUrl)}")
        }
        val base = currentUrl.toHttpUrlOrNull()
            ?: throw IOException("Unparseable current URL: ${UrlNormalizer.redactForLog(currentUrl)}")
        val target = base.resolve(location)
            ?: throw IOException(
                "Unresolvable redirect Location from ${UrlNormalizer.redactForLog(currentUrl)}: " +
                    UrlNormalizer.redactForLog(location.take(MAX_LOGGED_LOCATION_CHARS))
            )
        if (!target.isHttps) {
            throw IOException("Redirect to non-HTTPS scheme: ${target.scheme}")
        }
        if (!Constants.isAllowedVencordHost(target.host)) {
            throw IOException("Redirect to disallowed host: ${target.host}")
        }
        return target
    }

    /**
     * Executes a GET, following up to [MAX_REDIRECT_HOPS] redirects manually
     * (the client never auto-follows). Every target is validated against
     * HTTPS + the host allowlist before it is requested; Locations resolve
     * against the current hop's URL, so relative redirects stay correct
     * mid-chain.
     *
     * [etag] conditions the first request. [redirectEtag] may condition each
     * followed hop and must only return a validator for a URL whose own
     * response issued it (see [storedEtagFor]); the default never conditions
     * a followed hop.
     *
     * Precondition: the caller validated the initial [url]; only redirect
     * targets are validated here.
     */
    @Throws(IOException::class)
    fun executeVencordGetResolvingRedirect(
        url: String,
        etag: String?,
        redirectEtag: (hopUrl: String) -> String? = { null }
    ): Response {
        var currentUrl = url
        var currentEtag = etag
        var hops = 0
        while (true) {
            val resp = executeVencordGet(currentUrl, currentEtag)
            // 304 is a cache hit with no Location header; it must
            // short-circuit before redirect handling or a stored ETag
            // throws here and pins the cached bundle forever.
            if (resp.code == HttpURLConnection.HTTP_NOT_MODIFIED) return resp
            if (resp.code !in 300..399) return resp
            val location = resp.header("Location")
            // Release each 3xx's connection; the caller closes only the
            // final response.
            resp.close()
            if (location == null) {
                throw IOException("Redirect with no Location header from ${UrlNormalizer.redactForLog(currentUrl)}")
            }
            if (hops >= MAX_REDIRECT_HOPS) {
                throw IOException(
                    "Too many redirects (>$MAX_REDIRECT_HOPS) fetching Vencord bundle from ${UrlNormalizer.redactForLog(url)}"
                )
            }
            val target = resolveRedirectTarget(currentUrl, location)
            hops++
            VDELog.d(
                "HTTP",
                "Following redirect hop $hops to host=${target.host} " +
                    "etagSent=${currentEtag != null}"
            )
            currentUrl = target.toString()
            currentEtag = redirectEtag(currentUrl)
        }
    }

    /**
     * Single writer for the Vencord bundle: sanity-checks and patches the
     * response body, atomically installs it, and syncs the ETag / patch flag /
     * patch-set / last-update bookkeeping under the bundle write lock.
     *
     * Caller must have validated HTTPS + host allowlist and owns closing
     * [resp].
     *
     * The in-memory runtime is replaced immediately via
     * [setVencordRuntimeIfEnabled], which respects the safe-mode kill switch
     * (a raised switch saves to disk only).
     */
    @Throws(IOException::class)
    fun downloadStoreAndSync(
        resp: Response,
        vendroidFile: File,
        sPrefs: SharedPreferences,
        bundleLocation: String
    ) {
        if (resp.code !in 200..299) {
            throw IOException("HTTP ${resp.code} while storing Vencord bundle")
        }
        // Clamp the declared Content-Length before toInt(): a malicious host
        // could send a huge Long that wraps to a large positive Int, causing an
        // eager oversized pre-allocation in ByteArrayOutputStream before any
        // byte is read. Clamping to the read cap bounds that pre-allocation.
        val initialSize = resp.body.contentLength().coerceIn(8192L, MAX_READ_BYTES.toLong()).toInt()
        val content = readAsText(resp.body.byteStream(), initialSize)
        if (!looksLikeBundle(content)) {
            // Refuse to install: the cached bundle stays intact and the
            // caller's fallback logic handles the failure.
            throw IOException("Refusing to store bundle failing sanity check (${content.length} chars)")
        }
        val buildTag = extractBuildTag(content)
        val downloadHash = shortSha256(content)
        VDELog.i("HTTP", "Bundle downloaded (${content.length} chars) build=${buildTag ?: "unknown"} sha256=$downloadHash, applying patches...")
        val patched = applyPatches(content)
        // Hash the patched body: that is what gets installed on disk and what
        // the "Cached bundle ... sha256=" preload line hashes on the next start.
        val hash = shortSha256(patched)
        synchronized(bundleWriteLock) {
            // Unique temp name so concurrent download attempts cannot clobber
            // each other's file; a shared "vencord.js.tmp" once let one
            // writer's rename install another writer's truncated file.
            val tmpFile = File(vendroidFile.parent, "${vendroidFile.name}.${System.nanoTime()}.tmp")
            try {
                tmpFile.writeText(patched)
                if (!tmpFile.renameTo(vendroidFile)) {
                    throw IOException("Failed to rename ${tmpFile.name} to ${vendroidFile.name}")
                }
            } finally {
                // No-op after a successful rename; removes a partial temp file
                // on failure (unique names would otherwise leak files).
                tmpFile.delete()
            }
            val e = sPrefs.edit()
            val responseEtag = resp.header("ETag")
            if (responseEtag != null) {
                // One batch: the url+etag keys must flip atomically so a
                // concurrent reader never pairs a validator with a URL that
                // did not issue it.
                e.putString(PREF_ETAG, responseEtag)
                e.putString(PREF_ETAG_LOCATION, bundleLocation)
                e.putString(PREF_ETAG_REQUEST_URL, resp.request.url.toString())
            } else {
                // A server that stops sending ETags must not leave a stale one
                // behind.
                e.remove(PREF_ETAG)
                e.remove(PREF_ETAG_LOCATION)
                e.remove(PREF_ETAG_REQUEST_URL)
            }
            e.putInt(PREF_LAST_BUNDLE_UPDATE, BuildConfig.VERSION_CODE)
            // A fresh download is a definitive freshness answer for every
            // caller of this writer, so the skip-window stamp lives here
            // rather than per-branch in fetchVencord, under bundleWriteLock:
            // it can never describe a download that didn't happen.
            e.putLong(PREF_LAST_BUNDLE_CHECK, System.currentTimeMillis())
            if (buildTag != null) e.putString(PREF_BUNDLE_BUILD, buildTag) else e.remove(PREF_BUNDLE_BUILD)
            e.putString(PREF_BUNDLE_HASH, hash)
            // Persist the patch state so a later cold start skips the ~1MB
            // regex scan instead of re-running applyPatches.
            e.putBoolean(PREF_BUNDLE_PATCHED, true)
            e.putString(PREF_BUNDLE_PATCH_SET, BundlePatcher.bundlePatchSetKey)
            e.apply()
            // Flag-guarded setter: a stalled disk-load CAS must not overwrite
            // this fresher download, and a raised kill switch must block the
            // publish.
            if (!setVencordRuntimeIfEnabled(patched)) {
                VDELog.w("HTTP", "Safe mode raised; bundle saved to disk only, runtime not published")
            }
            bundleCheckedThisSession = true
            VDELog.i("HTTP", "Bundle patched and saved to disk (build=${buildTag ?: "unknown"} sha256=$hash)")
        }
    }

    /**
     * Cheap shape check to avoid installing a captive-portal page or an HTML
     * error page over a known-good cached bundle. Real bundles are ~1MB of
     * JavaScript; HTML starts with '<'.
     */
    private fun looksLikeBundle(content: String): Boolean =
        content.length >= 64 * 1024 && !content.trimStart().startsWith("<")

    // Delegates to BundlePatcher; kept so HttpClientBundlePatchTest keeps
    // pinning HttpClient.applyPatches and @JvmStatic callers stay binary-safe.
    @JvmStatic
    fun applyPatches(content: String): String = BundlePatcher.applyPatches(content)

    @Throws(IOException::class)
    fun readAsText(inputStream: InputStream, initialSize: Int = 8192, maxBytes: Int = MAX_READ_BYTES): String {
        // Use a pre-sized ByteArrayOutputStream to avoid ~17 StringBuilder
        // resizes when reading a ~1MB response.
        val bos = ByteArrayOutputStream(initialSize.coerceAtLeast(8192))
        // Bound the read so a compromised/streaming host cannot balloon memory
        // or disk. The cap is generous (well above the ~1MB bundle / small CSS)
        // but finite; exceeding it fails closed rather than OOMing.
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = inputStream.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("Response exceeds $maxBytes byte limit")
            bos.write(buf, 0, n)
        }
        return bos.toString("UTF-8")
    }

    /**
     * Reads the stream into a byte array, capping at [maxBytes] (default
     * [MAX_READ_BYTES]) and throwing [IOException] on overflow so callers fail
     * closed instead of ballooning memory. Mirrors the cap in [readAsText] for
     * the paths that need the raw bytes (WebView serve / disk cache).
     */
    @Throws(IOException::class)
    fun readAsBytes(
        inputStream: InputStream,
        maxBytes: Int = MAX_READ_BYTES,
        initialSize: Int = 8192
    ): ByteArray {
        // Seed the buffer from the expected content length (clamped) instead
        // of the full maxBytes ceiling, so a large max doesn't force a big
        // up-front allocation on every small fetch. coerceIn(8192, maxBytes)
        // throws IllegalArgumentException when maxBytes < 8192, so apply the
        // floor and cap separately.
        val bos = ByteArrayOutputStream(initialSize.coerceAtLeast(8192).coerceAtMost(maxBytes))
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = inputStream.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("Response exceeds $maxBytes byte limit")
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }
}

/**
 * Clears the bundle identity keys: where the cached bundle came from, what
 * build and hash it carries, whether it is patched, and when it was last
 * verified. Both invalidation call sites clear exactly this set, so the
 * lists cannot drift.
 *
 * [HttpClient.PREF_LAST_BUNDLE_UPDATE] is deliberately excluded; zeroing it
 * forces a full redownload and only the clientMod switch wants that. The
 * on-disk file and the in-memory runtime are caller decisions as well.
 */
internal fun SharedPreferences.Editor.clearBundleIdentityKeys() {
    remove(HttpClient.PREF_ETAG)
    remove(HttpClient.PREF_ETAG_LOCATION)
    remove(HttpClient.PREF_ETAG_REQUEST_URL)
    remove(HttpClient.PREF_BUNDLE_BUILD)
    remove(HttpClient.PREF_BUNDLE_HASH)
    remove(HttpClient.PREF_BUNDLE_PATCHED)
    remove(HttpClient.PREF_BUNDLE_PATCH_SET)
    remove(HttpClient.PREF_LAST_BUNDLE_CHECK)
}
