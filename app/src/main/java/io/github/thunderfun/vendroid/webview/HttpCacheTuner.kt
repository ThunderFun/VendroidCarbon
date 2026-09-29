package io.github.thunderfun.vendroid.webview

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.thunderfun.vendroid.utils.VDELog

/**
 * Pins the WebView profile's HTTP cache quota to at least [MIN_QUOTA_BYTES].
 *
 * On a cold start Discord's web client downloads ~16 MB of content-hashed
 * JavaScript (one ~12 MB `web.<hash>.js` plus ~300 async chunks), then fills
 * the cache with avatars, emojis, and fonts. Discord serves all of it with
 * `Cache-Control: max-age=2592000` (30 days), but WebView sizes the cache
 * itself and the default "may not be a stable size across sessions"
 * ([androidx.webkit.HttpCache.getDefaultQuotaBytes]), so the cache can
 * shrink between sessions and evict the bundle, forcing a full re-download
 * on the next start.
 *
 * A quota set here persists across restarts and never expires
 * (androidx.webkit.HttpCache), so re-downloads only follow real content
 * changes such as a new `web.<hash>.js` after a Discord deploy. The quota is
 * only raised, never lowered.
 *
 * Call from the UI thread once the WebView exists and before its first load
 * (see `MainActivity.installWebView`). No-op unless the WebView supports
 * [WebViewFeature.MULTI_PROFILE] and [WebViewFeature.HTTP_CACHE_MANAGER].
 */
internal object HttpCacheTuner {

    /** 512 MiB, enough for Discord's hashed bundles plus media between sessions. */
    internal const val MIN_QUOTA_BYTES = 512L * 1024 * 1024

    private const val MIB = 1024L * 1024L

    /** Only a below-floor quota is raised; shrinking would trigger evictions. */
    internal fun needsRaise(currentQuotaBytes: Long): Boolean =
        currentQuotaBytes < MIN_QUOTA_BYTES

    /** Applies the quota floor to [webView]'s profile. UI thread only. */
    fun apply(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.HTTP_CACHE_MANAGER)
        ) {
            VDELog.i("Cache", "HTTP cache quota API unsupported; leaving WebView default")
            return
        }
        try {
            val cache = WebViewCompat.getProfile(webView).httpCache
            val defaultQuota = cache.defaultQuotaBytes
            if (!needsRaise(cache.quotaBytes)) {
                VDELog.i(
                    "Cache",
                    "HTTP cache quota already ${cache.quotaBytes / MIB} MiB " +
                        "(WebView default ${defaultQuota / MIB} MiB)"
                )
                return
            }
            cache.setQuotaBytes(MIN_QUOTA_BYTES)
            VDELog.i(
                "Cache",
                "HTTP cache quota raised to ${cache.quotaBytes / MIB} MiB " +
                    "(WebView default ${defaultQuota / MIB} MiB)"
            )
        } catch (e: Exception) {
            // A quota is an optimization; never let it break WebView setup.
            VDELog.w("Cache", "HTTP cache quota not applied: ${e.javaClass.simpleName} ${e.message}")
        }
    }
}
