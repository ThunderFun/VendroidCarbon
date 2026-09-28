package io.github.thunderfun.vendroid.webview

import android.net.Uri
import io.github.thunderfun.vendroid.utils.Constants

/**
 * Pure navigation policy for WebView URL loads. Callers must normalize the URL
 * via [UrlNormalizer] first.
 *
 * Subframes always load in-WebView, with shouldInterceptRequest governing
 * their subresources. Main-frame navigations to Discord-owned domains load
 * in-WebView, except for two cases that go to the link popup:
 *  - /blog paths, standalone marketing pages the app cannot navigate back out
 *    of, so they open in the system browser instead.
 *  - every other https host.
 *
 * Main-frame navigations to schemes the popup cannot open (blob:, data:,
 * mailto:, custom deep links) return [Action.IGNORE]. LinkHandler launches
 * only http(s), so a popup would just show its blocked-scheme toast.
 */
object NavigationPolicy {

    enum class Action { LOAD_IN_WEBVIEW, SHOW_POPUP, IGNORE }

    /**
     * Single source of truth for navigation routing. Takes the full URL so
     * path-based rules can apply (Discord-owned /blog pages route to the popup).
     */
    fun decide(url: Uri, isForMainFrame: Boolean): Action {
        if (!isForMainFrame) return Action.LOAD_IN_WEBVIEW
        if (url.scheme == "about") return Action.LOAD_IN_WEBVIEW
        // Non-browser schemes are cancelled silently.
        if (url.scheme != "https" && url.scheme != "http") return Action.IGNORE
        // Main frames must load over HTTPS. A cleartext http:// frame would
        // expose the session/token to a network attacker; do not rely solely
        // on shouldInterceptRequest to stop it (redirects/service workers
        // could bypass that callback). Route to the popup instead.
        if (url.scheme != "https") return Action.SHOW_POPUP
        val host = url.host
        if (host != null && Constants.isNavigationAllowedDomain(host)) {
            val path = url.path ?: ""
            if (path == "/blog" || path.startsWith("/blog/")) return Action.SHOW_POPUP
            return Action.LOAD_IN_WEBVIEW
        }
        return Action.SHOW_POPUP
    }
}