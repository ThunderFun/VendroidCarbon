package io.github.thunderfun.vendroid.webview

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.webkit.WebResourceResponseCompat
import androidx.webkit.WebViewFeature
import io.github.thunderfun.vendroid.BuildConfig
import io.github.thunderfun.vendroid.MainActivity
import io.github.thunderfun.vendroid.VendroidApp
import io.github.thunderfun.vendroid.utils.Constants
import io.github.thunderfun.vendroid.utils.DiscordBranch
import io.github.thunderfun.vendroid.utils.FirewallConfig
import io.github.thunderfun.vendroid.utils.JsPatches
import io.github.thunderfun.vendroid.utils.VDELog
import java.io.ByteArrayInputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.lang.ref.WeakReference
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

class VWebviewClient(
    context: Context
) : WebViewClient() {
    private val appContext: Context = context.applicationContext
    private val activityRef: WeakReference<Activity> = if (context is Activity) WeakReference(context) else WeakReference(null)

    // Single accessor for the host-activity cast: one WeakReference read plus
    // safe cast per use, so call sites never re-implement the dance.
    private val mainActivity: MainActivity? get() = activityRef.get() as? MainActivity

    // Monotonic main-frame navigation generation. onPageStarted bumps it;
    // async evaluateJavascript callbacks capture it at schedule time and bail
    // if a newer navigation started, so injection cannot run against a
    // different document than the one that scheduled it.
    private val mainFrameGeneration = AtomicLong(0)

    private val linkHandler: LinkHandler = LinkHandler(context)

    private class CachedResponse(
        val statusCode: Int,
        val reasonPhrase: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val fetchedAt: Long = System.currentTimeMillis()
    )

    private enum class CacheTarget { THEME_CSS, MAIN_FRAME }

    // Static caches survive across MainActivity recreations (rotation, memory
    // pressure, etc.) so previously-fetched CSS / HTML is still warm.
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        // Subframe navigations to javascript:, data:, file:, intent: and
        // custom schemes are not network requests and never pass through
        // shouldInterceptRequest. Allow only browser/media subframe schemes so
        // a javascript: URL cannot execute in a subframe context. (Cross-origin
        // subframes can't read the top-frame capability token anyway, but fail
        // closed rather than rely on that.)
        if (!request.isForMainFrame && url.scheme != "https" && url.scheme != "http" &&
            url.scheme != "blob" && url.scheme != "data"
        ) {
            return true
        }
        // A data: SUBFRAME DOCUMENT NAVIGATION is stricter than a data:
        // SUBRESOURCE (<img>): as a document, data:image/svg+xml executes
        // inline onload script and data:text/html is full HTML. The subresource
        // path treats data:image/ as inert (Discord relies on it for
        // avatars/icons), but a subframe navigating there must not run script.
        if (!request.isForMainFrame && url.scheme == "data" && !isInertDataPayload(url.toString())) {
            return true
        }
        // Non-allowlisted links go to the link popup (Copy / Open / Share / Cancel).
        when (NavigationPolicy.decide(url, request.isForMainFrame)) {
            NavigationPolicy.Action.LOAD_IN_WEBVIEW -> return false
            // Non-browser schemes: cancel, no popup.
            NavigationPolicy.Action.IGNORE -> return true
            NavigationPolicy.Action.SHOW_POPUP -> {
                VDELog.d("WV", "External link: ${UrlNormalizer.redactForLog(url.toString())}")
                linkHandler.showLinkPopup(url)
                return true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val generation = mainFrameGeneration.incrementAndGet()
        mainActivity?.let {
            it.currentUrlForBridge = url
            it.currentHostForBridge = Uri.parse(url).host
            it.navigationInProgress = true
            it.documentGeneration = generation
        }
        VDELog.i("WV", "Page started: ${UrlNormalizer.redactForLog(url)}")
        VChromeClient.resetPageErrorQuota()

        // If shouldInterceptRequest already embedded the firewall (and possibly
        // the runtimes) into this URL's HTML, skip the evaluateJavascript
        // calls. The embedded scripts run at parse time. Otherwise inject the
        // combined firewall + animation patches in a single IPC call.
        if (!EmbeddedUrlClaims.consumeFirewall(url)) {
            view.evaluateJavascript(JsPatches.STARTUP_PATCHES_JS, null)
        }

        // If the runtimes weren't embedded (cold start before they were in
        // memory, or a route served without them), inject them now via the bridge.
        if (EmbeddedUrlClaims.consumeRuntime(url)) {
            // Already parsed as part of the document.
            return
        }
        view.evaluateJavascript("typeof Vencord!=='undefined'&&typeof VencordMobile!=='undefined'") { result ->
            if (result?.trim() == "true") return@evaluateJavascript
            // Bail if the hosting activity is gone. The WebView may have been
            // destroyed (onDestroy), and calling evaluateJavascript on a
            // destroyed WebView throws IllegalStateException. This callback runs
            // asynchronously, so it can fire after onDestroy despite being
            // scheduled here.
            val activity = mainActivity
            if (activity == null || activity.isFinishing || activity.isDestroyed) return@evaluateJavascript
            // The captured [url] describes the page that scheduled this
            // callback, not the document now in [view]; a navigation may have
            // started in between. The injection chain delivers the bridge
            // capability token (bridgeBootstrapJs runs first in
            // RuntimeInjector), so require both the same navigation generation
            // and an app-origin current URL. The new page schedules its own
            // callback, so skipping here loses nothing.
            if (mainFrameGeneration.get() != generation) return@evaluateJavascript
            // getUrl() is safe on the UI thread (result callbacks run there);
            // the try covers a WebView destroyed mid-race, which the activity
            // checks above do not.
            val currentUrl = try {
                view.url
            } catch (_: Exception) {
                null
            }
            // Full origin: never evaluate the capability token in an http app
            // host or on a non-default port.
            if (currentUrl == null || !isDiscordAppOriginUrl(currentUrl)) return@evaluateJavascript
            val runtime = HttpClient.VencordRuntime
            val mobileRuntime = HttpClient.VencordMobileRuntime
            if (!HttpClient.vencordDisabled && runtime != null && mobileRuntime != null) {
                // Result deliberately ignored: the log below fires whether or
                // not the WebView died mid-eval, matching the old inline chain.
                RuntimeInjector.injectViaBridge(view, runtime, mobileRuntime)
                VDELog.i("WV", "Runtime injected via bridge for ${UrlNormalizer.redactForLog(url)}")
                activity.scheduleBootVerify("page-start-eval")
            } else if (!HttpClient.vencordDisabled) {
                // Only meaningful while a runtime could still arrive this
                // session; safe mode never publishes one.
                activity.missedInjection = true
            }
        }
    }

    /**
     * Refresh the bridge's cached origin at commit time rather than waiting
     * for onPageFinished. A redirect that changes hosts between onPageStarted
     * and commit would otherwise leave the cached origin on the pre-redirect
     * host for the whole load. Only the main frame fires this callback.
     */
    override fun onPageCommitVisible(view: WebView, url: String) {
        super.onPageCommitVisible(view, url)
        mainActivity?.let {
            it.currentUrlForBridge = url
            it.currentHostForBridge = Uri.parse(url).host
            it.navigationInProgress = false
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        val activity = mainActivity
        activity?.let {
            it.currentUrlForBridge = url
            it.currentHostForBridge = Uri.parse(url).host
            it.navigationInProgress = false
        }
        VDELog.d("WV", "Page finished: ${UrlNormalizer.redactForLog(url)}")

        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            activity.loadingScreen?.scheduleDismiss(500)
        }
        activity?.scheduleBootVerify("page-finished")
    }

    /**
     * Records or clears this URL's firewall/runtime injection claims to match
     * what the served body actually contains. Only claim "embedded" when it
     * actually was. Otherwise onPageStarted would skip its
     * evaluateJavascript fallback and the page would run with no JS firewall
     * (fail-open). On overflow, clear so a new navigation is still tracked.
     * The removes drop claims stranded by a load aborted between
     * shouldInterceptRequest and onPageStarted: the next visit to this URL
     * must not consume a claim for a body that lacks the scripts.
     *
     * Shared by the stale-shell serve, the LRU main-frame serve, and the
     * fetch tail (injectAndRecordClaims).
     */
    private fun updateInjectionClaims(urlString: String, firewallEmbedded: Boolean, runtimeEmbedded: Boolean) {
        if (firewallEmbedded) EmbeddedUrlClaims.recordFirewall(urlString)
        else EmbeddedUrlClaims.clearFirewall(urlString)
        if (runtimeEmbedded) EmbeddedUrlClaims.recordRuntime(urlString)
        else EmbeddedUrlClaims.clearRuntime(urlString)
    }

    /**
     * Serves a stale-while-revalidate main-frame from the disk cache.
     * On a hit: injects the current firewall + CSS into the cached raw HTML and
     * returns it (no network wait), then refreshes the caches in the background
     * so the next navigation is fresh. The refresh resolves redirects via
     * [resolveRedirects]; a blocked chain just skips the refresh. Returns
     * null to fall through to a blocking fetch when there is no valid disk
     * entry.
     */
    private fun serveStaleMainFrame(
        req: WebResourceRequest,
        urlString: String,
        responseCacheKey: String
    ): WebResourceResponse? {
        // The shell store (URL-keyed disk cache + preloaded shells) only ever
        // holds GET-fetched HTML, so a HEAD/POST main frame must neither serve
        // a shell nor spawn the revalidate below.
        if (req.method != "GET") return null
        // Enforce the "app-shell Discord routes only" premise at the consumer:
        // preloadedShells is process-lifetime state and the injection below
        // hardcodes isDiscordMainFrame = true. Mirrors the disk read gate.
        if (!MainFrameDiskCache.isCacheableRoute(req.url)) return null
        // Prefer the memory-preloaded shell (filled at cold start from the
        // disk cache). Other routes fall through to the per-URL disk entry via
        // the preload, so a /channels request never receives the /app body. We
        // never read from disk here: shouldInterceptRequest runs on the
        // Chromium network thread, and a blocking read would stall the shared
        // worker.
        val cached: MainFrameDiskCache.CachedMainFrame? = StaleMainFrame.inMemoryShell(urlString)
        val shell = cached ?: return null
        // Bind the URL-keyed shell to the credential context it was fetched
        // under. A mismatch is a miss; the foreground fetch repopulates the
        // entry. The write-time directive gate cannot prove account
        // independence, so this check does not rely on that assumption.
        if (shell.credentialPartition != requestCredentialPartition(req)) return null
        // Pre-fix builds could persist a non-HTML body under a parameterized
        // type; never rewrite such an entry to text/html. A miss just refetches.
        val storedCt = ResponseHeaderMerge.valueFor(shell.headers, "content-type")
        if (!ResponseHeaderMerge.isHtmlMediaType(storedCt)) return null
        val text = try { String(shell.body, Charsets.UTF_8) } catch (_: Exception) { return null }
        val result = ResponseHtmlInjector.injectFirewallAndCss(text, urlString, isDiscordMainFrame = true)
        val patched = result.html
        // No `</head>` (patched == null): serve the unpatched body and let
        // onPageStarted apply the scripts via the bridge.
        val bodyBytes = (patched ?: text).toByteArray(Charsets.UTF_8)

        updateInjectionClaims(urlString, firewallEmbedded = patched != null, runtimeEmbedded = result.runtimeEmbedded)
        if (result.runtimeEmbedded) {
            VDELog.i("WV", "Embedded Vencord runtime into stale main frame: ${UrlNormalizer.redactForLog(urlString)}")
        }
        // Background revalidate; skip if one is already in flight for this URL.
        // The slot cap bounds queued plus running refreshes globally, so
        // distinct shell hits cannot pile unbounded work behind one slow
        // request. A skipped refresh is harmless; the next navigation retries.
        val claimed = revalidatingUrls.add(urlString)
        if (claimed && revalidationSlots.tryAcquire()) {
            // Build the refresh request ON the intercept thread and snapshot
            // the main-frame flag: `req` is a Chromium-owned
            // WebResourceRequest that is not guaranteed safe to dereference
            // off-thread or after the callback returns.
            val refreshBuilder = try { okHttpRequestBuilder(req, urlString) } catch (_: Exception) { null }
            val refreshIsMainFrame = req.isForMainFrame
            val refreshCookiePairs = initialCookiePairs(req)
            val refreshCredentialPartition = requestCredentialPartition(req)
            try {
                revalidateExecutor.execute {
                    var refreshResponse: Response? = null
                    try {
                        if (refreshBuilder != null) {
                            refreshResponse = HttpClient.sharedClient.newCall(refreshBuilder.build()).execute()
                            // Redirects are never auto-followed (client config); resolve
                            // 3xx via the same allowlist gate as the foreground fetch,
                            // so the refresh lands on the real final response. Null
                            // (blocked chain) skips the refresh and leaves the caches
                            // untouched; the shell MAX_AGE bounds that staleness, after
                            // which the blocking foreground fetch self-heals.
                            val resolved = resolveRedirects(refreshIsMainFrame, refreshResponse, urlString, 0, hashSetOf(urlString), refreshCookiePairs)
                            if (resolved != null) {
                                if (resolved !== refreshResponse) {
                                    // Close the consumed 3xx (resolveRedirects never closes its input).
                                    refreshResponse.close()
                                    refreshResponse = resolved
                                }
                                // fetchAndProcessResponse's return value is discarded
                                // here, so the Chromium M138+ multi-cookie channel
                                // (Set-Cookie attached to that object) would drop the
                                // final response's cookies; replay them into the store
                                // like resolveRedirects does for hops. Pre-M138,
                                // fetchAndProcessResponse replays Set-Cookie into the
                                // store itself, making this an idempotent overwrite.
                                // Main frame only: subresource chains never touch the
                                // cookie store (see resolveRedirects).
                                if (refreshIsMainFrame) harvestRedirectCookies(resolved)
                                fetchAndProcessResponse(
                                    refreshIsMainFrame, resolved, false, CacheTarget.MAIN_FRAME,
                                    urlString, responseCacheKey, credentialPartition = refreshCredentialPartition,
                                    publishInjectionClaims = false
                                )
                            }
                        }
                    } catch (_: Exception) {
                        // Best-effort refresh; failure just leaves the cache stale.
                    } finally {
                        // Close to return the pooled connection (not disconnect()).
                        refreshResponse?.close()
                        revalidatingUrls.remove(urlString)
                        revalidationSlots.release()
                    }
                }
            } catch (_: RejectedExecutionException) {
                // The queue is sized for the slot cap, so this should not
                // happen; release rather than leak the slot.
                revalidatingUrls.remove(urlString)
                revalidationSlots.release()
            }
        } else if (claimed) {
            // Global cap reached; the next navigation retries.
            revalidatingUrls.remove(urlString)
        }

        // A stale-served main frame is always HTML (the raw shell). Serve a
        // clean "text/html" MIME; the charset goes in the "encoding" arg, not
        // the MIME. Header reads are case-insensitive because entries
        // persisted by older builds keep the server's wire casing (lowercase
        // on HTTP/2, Title-Case on HTTP/1.1); an exact-case miss here silently
        // dropped CSP/HSTS from stale serves of HTTP/2-fetched shells.
        val ct = "text/html"
        val headers = buildStaleHeaders(bodyBytes.size, ct, shell.headers)
        // isCacheableRoute above guarantees the app origin.
        rebuildCachedAppCsp(headers, isAppOrigin = true)
        return WebResourceResponse(
            ct, "utf-8", 200, shell.reasonPhrase,
            headers,
            ByteArrayInputStream(bodyBytes)
        )
    }

    /**
     * Rebuilds the response headers for a stale-served shell. Replays the
     * stored security headers picked by [copyStalePreservedHeaders] and sets
     * an accurate Content-Length for the re-injected body. The stored CSP
     * lines only seed [rebuildCachedAppCsp], which the caller runs right after
     * this: a build upgrade must not keep serving the fetch-time policy.
     *
     * The LRU serve path instead overrides the cached header map in place
     * (see serveCachedMainFrame). The two derivations are not equivalent; do
     * not merge them.
     */
    private fun buildStaleHeaders(
        bodySize: Int,
        contentType: String,
        stored: Map<String, String>
    ): HashMap<String, String> {
        val headers = HashMap<String, String>()
        headers["content-type"] = contentType
        headers["content-length"] = bodySize.toString()
        // See STALE_PRESERVED_HEADERS for what this replays and why.
        copyStalePreservedHeaders(stored, headers)
        return headers
    }

    /**
     * Adds [headers] to this builder, skipping in order:
     *  1. OkHttp-reserved (hop-by-hop/session) headers, see
     *     [OKHTTP_RESTRICTED_HEADERS];
     *  2. accept-encoding, so OkHttp's transparent gzip handling is used (the
     *     app relies on this when rewriting Content-Length/Content-Encoding);
     *  3. conditional headers ([STRIPPED_CONDITIONAL_HEADERS]), folded into
     *     the cache key (see cacheKey);
     *  4. credential headers ([CREDENTIAL_HEADERS]) when [crossOrigin]:
     *     never forwarded across an origin change;
     *  5. content-type when [dropContentType]: the converted request carries
     *     no body; forwarding the old body's Content-Type would advertise one.
     *  6. cookie when [dropCookie]: redirect hops re-derive cookies from the
     *     store per destination instead of copying the previous hop's header
     *     (see resolveRedirects). Initial requests keep the default and
     *     forward Chromium's original Cookie header.
     *
     * One copy shared by [okHttpRequestBuilder] and [resolveRedirects]; a
     * drift between the sites silently forwards hop-by-hop headers or
     * conditional validators.
     */
    private fun Request.Builder.addFilteredHeaders(
        headers: Iterable<Pair<String, String>>,
        crossOrigin: Boolean = false,
        dropContentType: Boolean = false,
        dropCookie: Boolean = false
    ) {
        for ((key, value) in headers) {
            val lowerKey = key.lowercase()
            if (lowerKey in OKHTTP_RESTRICTED_HEADERS) continue
            if (lowerKey == "accept-encoding") continue
            if (lowerKey in STRIPPED_CONDITIONAL_HEADERS) continue
            if (crossOrigin && lowerKey in CREDENTIAL_HEADERS) continue
            if (dropCookie && lowerKey == "cookie") continue
            if (dropContentType && lowerKey == "content-type") continue
            addHeader(key, value)
        }
    }

    /**
     * Builds an OkHttp [Request.Builder] from a [WebResourceRequest]. Adds
     * the WebView UA when the request carries none (Chromium never surfaces
     * User-Agent in requestHeaders; without this, OkHttp sends its
     * okhttp/x.y.z default and a desktopMode override is lost).
     *
     * Redirects are not handled here; the caller resolves 3xx manually through
     * the allowlist gate (see [resolveRedirects]).
     */
    private fun okHttpRequestBuilder(req: WebResourceRequest, urlString: String): Request.Builder {
        val rb = Request.Builder().url(urlString)
        applyMethodAndBody(rb, req.method)
        if (req.url.path?.endsWith(".css") == true && isVencordCssUrl(req.url)) {
            rb.header("Cache-Control", "no-cache")
            rb.header("Pragma", "no-cache")
        }
        rb.addFilteredHeaders(req.requestHeaders.map { it.toPair() })
        val ua = webViewUserAgent
        if (ua != null && req.requestHeaders.keys.none { it.equals("user-agent", ignoreCase = true) }) {
            rb.header("User-Agent", ua)
        }
        return rb
    }

    /**
     * Cookie (name, value) identities Chromium selected for an intercepted
     * request's initial Cookie header (the real SameSite / 3PC / partitioning
     * view when COOKIE_INTERCEPT is supported). Empty when the header is
     * absent, which fails closed to hop-set cookies only. Intercept thread
     * only: [req] is Chromium-owned.
     */
    private fun initialCookiePairs(req: WebResourceRequest): MutableSet<Pair<String, String>> =
        cookieHeaderPairs(
            req.requestHeaders.entries
                .firstOrNull { it.key.equals("cookie", ignoreCase = true) }?.value
        ).toMutableSet()

    /**
     * Credential context of [req], bound to persisted shells at write time
     * and checked at serve time so a body fetched under one credential set is
     * not replayed under another. Empty when no credential header is visible
     * (pre-M138 WebViews without COOKIE_INTERCEPT), which keeps the old serve
     * behavior there. Intercept thread only: [req] is Chromium-owned.
     */
    private fun requestCredentialPartition(req: WebResourceRequest): String =
        credentialHeadersPartition(req.requestHeaders)

    /**
     * Manually follows an HTTP redirect from the pooled client (which never
     * auto-follows). Each hop is re-validated before a new connection opens:
     *  - main-frame navigations against the Discord-only navigation allowlist
     *    and the app-origin trust boundary (mirroring
     *    [NavigationPolicy.decide]) so a redirect cannot smuggle a non-Discord
     *    page into the top frame; the final body renders under the original
     *    URL's origin;
     *  - subresource fetches against the broader subresource allowlist
     *    ([shouldBlockUri]) and the recovery user-theme gate
     *    ([userCssGateResponse]).
     * All hops must be https and pass the privacy filter. Credential-bearing
     * headers are never forwarded to a different origin. Main-frame hops
     * re-derive cookies from the store but only for the (name, value)
     * identities Chromium selected for the original request or a hop set
     * without SameSite=Strict: getCookie() has no initiating-site context, so
     * the unfiltered store view would re-send Strict cookies Chromium
     * withheld, and name-only matching would re-send a same-named cookie with
     * a different value (different path/domain/SameSite). Subresource hops
     * never touch the cookie store because their credentials mode is not
     * visible from WebResourceRequest.
     *
     * Returns the final (non-redirect) [Response], or null when the chain
     * cannot proceed: a hop rejected by the allowlist, a redirect loop, a
     * missing/unresolvable Location, a non-standard 3xx (only
     * 301/302/303/307/308 are followed), or the depth limit (a 4th redirect
     * response). The foreground caller turns null into a blocking response
     * (the SWR refresh just skips, leaving caches untouched), so a 3xx is
     * never served as the resource. Chromium treats intercepted responses as
     * final and does not follow their Location; serving one would render the
     * redirect body (typically empty) as the page and pass its unvalidated
     * Location header to Chromium.
     *
     * Method handling is per [redirectMethod] and [applyMethodAndBody]; see
     * those for the RFC 9110 matrix, the wire-method derivation, and the
     * body pairing.
     *
     * [response] is owned by the caller; this method never closes it. It
     * closes any intermediate responses it allocates while following.
     *
     * [isMainFrame] is a snapshot of WebResourceRequest.isForMainFrame taken
     * on the intercept thread, not the request object: the SWR refresh calls
     * this off-thread, where the Chromium-owned request must not be
     * dereferenced.
     */
    private fun resolveRedirects(
        isMainFrame: Boolean,
        response: Response,
        urlString: String,
        depth: Int,
        seen: MutableSet<String>,
        eligibleCookiePairs: MutableSet<Pair<String, String>>
    ): Response? {
        if (response.code !in 300..399) return response

        // Chromium would have applied this hop's Set-Cookie natively. Login
        // flows commonly set session cookies on a 302 before redirecting, so
        // replay each into the cookie store, scoped to the hop's own URL (the
        // store enforces domain/path against it). Main frames only: navigations
        // send credentials, while a subresource hop's credentials mode is not
        // observable from WebResourceRequest, so a replay could write cookies
        // for a fetch that omitted them. A blocked 3xx never reaches
        // fetchAndProcessResponse, so for main frames this is its only delivery
        // path. resolveRedirects validates each hop URL before fetching it, so
        // replays never target an unvalidated host.
        if (isMainFrame) harvestRedirectCookies(response, eligibleCookiePairs)
        // Fail closed on non-standard 3xx: 304 has no Location (its
        // conditional headers are stripped here anyway), and 300-with-Location
        // and 305/306 carry semantics this path does not implement. Runs
        // after the cookie harvest, so even a blocked 3xx delivers Set-Cookie.
        if (response.code !in FOLLOWABLE_REDIRECT_CODES) return null
        // Depth limit: a 4th redirect is never served (see KDoc).
        if (depth >= 3) return null
        val location = response.header("Location") ?: return null
        val resolved = response.request.url.resolve(location) ?: return null
        val target = resolved.toString()
        if (!seen.add(target)) return null  // redirect loop → block

        if (resolved.scheme != "https") return null
        val resolvedHost = resolved.host ?: return null  // fail closed on unresolvable host

        // Main-frame redirects are re-validated against the Discord-only
        // navigation allowlist (like a directly-tapped link). Using the broad
        // subresource allowlist here would let a Discord-origin page 3xx to a
        // non-Discord allowlisted host and load it as the top frame in-app,
        // bypassing the popup that forces non-Discord hosts to the browser.
        // The path-consuming gates below compare the decoded path, matching
        // the direct-request callers and NavigationPolicy (see decodedPath).
        val path = decodedPath(resolved)
        if (isMainFrame) {
            if (!Constants.isNavigationAllowedDomain(resolvedHost)) return null
            // The final body renders under the ORIGINAL request URL's origin
            // because Chromium never navigates to the redirect target, so the
            // destination must share that origin. The app-origin check above
            // stays as the explicit NavigationPolicy mirror.
            if (!Constants.isDiscordAppOrigin(resolvedHost)) return null
            if (!mainFrameRedirectAllowed(urlString, resolved)) return null
            // Mirror NavigationPolicy: Discord /blog pages route to the popup.
            if (path == "/blog" || path.startsWith("/blog/")) return null
        } else {
            if (shouldBlockUri(resolved.scheme, resolvedHost, path)) return null
        }
        if (shouldBlockForPrivacy(resolvedHost, path) != null) return null

        // The recovery "Disable themes" gate applies to redirect destinations
        // too: while the flag is raised, an exempt operator stylesheet (e.g.
        // github.com/.../browser.css) must not be able to 3xx into a
        // user-theme .css on another forge host, which would pass
        // shouldBlockUri (forge + .css) and be fetched and applied despite
        // the recovery setting. Same placement contract as the direct path
        // (shouldBlockForRequest): inside the shared gate, before any fetch.
        // Only fires for forge-host .css while the flag is raised, so it is a
        // no-op for every normal chain: main-frame hops cannot be forge hosts
        // anyway, and the direct path already gated the initial URL. Blocking
        // returns null, the fail-closed blocked response for the original
        // request, like every other hop rejection.
        userCssGateResponse(Uri.parse(target))?.let { return null }

        // Never forward credential-bearing headers to a different origin: the
        // original request may carry them, and a cross-origin redirect would
        // leak them to the target (for subresources, potentially an
        // attacker-controllable allowlisted host such as github.io). An
        // origin is scheme + host + effective port; the previous host-only
        // comparison forwarded credentials across a port change
        // (https://host to https://host:8443), which browsers strip per the
        // Fetch spec.
        val crossOrigin = !isSameOrigin(response.request.url, resolved)
        // Map the next hop's method from the wire method actually sent for
        // the current one, not the original request's: an earlier hop may
        // already have converted POST to GET (see redirectMethod).
        val currentMethod = response.request.method
        val nextMethod = redirectMethod(response.code, currentMethod)
        val methodChanged = nextMethod != currentMethod
        val rb = Request.Builder().url(target)
        applyMethodAndBody(rb, nextMethod)
        rb.addFilteredHeaders(
            response.request.headers,
            crossOrigin = crossOrigin,
            dropContentType = methodChanged,
            // Never copy the previous hop's Cookie header: a path-scoped
            // cookie would over-send. The store selects cookies for this
            // destination below; crossOrigin stripping still covers
            // authorization-family headers.
            dropCookie = true
        )
        // Re-derive store cookies only under (name, value) identities
        // Chromium selected for the original request or a hop set without
        // SameSite=Strict; the full getCookie() view would re-send Strict
        // cookies Chromium withholds. harvestRedirectCookies has already
        // replayed this hop's Set-Cookie into the store.
        if (isMainFrame && isDiscordAppOriginUrl(resolved)) {
            filterCookieHeader(CookieManager.getInstance().getCookie(target), eligibleCookiePairs)
                ?.let { rb.header("Cookie", it) }
        }
        val follow = HttpClient.sharedClient.newCall(rb.build()).execute()
        return try {
            val next = resolveRedirects(isMainFrame, follow, urlString, depth + 1, seen, eligibleCookiePairs)
            if (next !== follow) {
                // Close the consumed intermediate response; keep the deeper one.
                follow.close()
            }
            next
        } catch (e: Exception) {
            follow.close()
            throw e
        }
    }

    /**
     * Serves a theme-CSS hit from the in-memory LRU, or null to fall through
     * to the fetch tail (which refills the entry).
     */
    private fun serveCachedThemeCss(responseCacheKey: String): WebResourceResponse? {
        themeCssCache.get(responseCacheKey)?.let { cached ->
            // An empty body must never be served; drop and refetch.
            if (cached.body.isEmpty()) {
                themeCssCache.remove(responseCacheKey)
                return null
            }
            if (System.currentTimeMillis() - cached.fetchedAt < THEME_CSS_TTL_MS) {
                return WebResourceResponse("text/css", "utf-8", cached.statusCode, cached.reasonPhrase, cached.headers, ByteArrayInputStream(cached.body))
            }
            themeCssCache.remove(responseCacheKey)
        }
        return null
    }

    /**
     * Serves a main-frame hit from the in-memory LRU, or null to fall through
     * to the stale-shell delegate and then the fetch tail.
     *
     * The cache stores the RAW body, so inject the firewall / runtimes at
     * serve time with the current config (same model as the disk cache). This
     * way a tightened firewall applies on the very next serve instead of
     * serving a stale embed.
     *
     * This path overrides the cached header map in place; the stale path
     * instead re-reads its stored headers case-insensitively and rebuilds
     * (see buildStaleHeaders). The two derivations are not equivalent; do not
     * merge them.
     */
    private fun serveCachedMainFrame(
        cached: CachedResponse,
        responseCacheKey: String,
        urlString: String
    ): WebResourceResponse? {
        // An empty body must never be served; drop and refetch.
        if (cached.body.isEmpty()) {
            mainFrameCache.remove(responseCacheKey)
            return null
        }
        if (System.currentTimeMillis() - cached.fetchedAt < MAIN_FRAME_TTL_MS) {
            val text = String(cached.body, Charsets.UTF_8)
            // Derived from the request URL, not hardcoded, so a cache writer
            // that misses its gate cannot inject the shell into a non-HTTPS
            // or non-443 URL.
            val isAppOrigin = isDiscordAppOriginUrl(urlString)
            val result = ResponseHtmlInjector.injectFirewallAndCss(
                text, urlString,
                isDiscordMainFrame = isAppOrigin
            )
            val patched = result.html
            val serveBytes = (patched ?: text).toByteArray(Charsets.UTF_8)
            // Snapshot verdict and stranded-claim removes; see
            // InjectionResult and updateInjectionClaims.
            updateInjectionClaims(urlString, firewallEmbedded = patched != null, runtimeEmbedded = result.runtimeEmbedded)
            // Cached headers are already lowercase (fetch path
            // canonicalizes); the puts below replace in place rather
            // than adding a second, mixed-case line.
            val headers = HashMap(cached.headers)
            // Stored policy lines never serve verbatim: a build upgrade can
            // flip ENFORCE_STRICT_CSP, and the disk cache outlives upgrades.
            rebuildCachedAppCsp(headers, isAppOrigin)
            headers["content-type"] = "text/html"
            headers["content-length"] = serveBytes.size.toString()
            return WebResourceResponse(
                "text/html", "utf-8", cached.statusCode, cached.reasonPhrase,
                headers, ByteArrayInputStream(serveBytes)
            )
        }
        mainFrameCache.remove(responseCacheKey)
        return null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
        // Scheme + host + privacy gate shared with the Service Worker client.
        shouldBlockForRequest(req)?.let { return it }
        if (!shouldInterceptForCspStripping(req)) return null
        val urlString = req.url.toString()
        val isCss = req.url.path?.endsWith(".css") == true
        val isThemeCss = isCss && isVencordCssUrl(req.url)
        val isMainFrame = req.isForMainFrame

        var response: Response? = null
        try {
            val responseCacheKey = cacheKey(req, urlString)

            if (isThemeCss) {
                serveCachedThemeCss(responseCacheKey)?.let { return it }
            }

            // Only GET main frames participate in the main-frame caches. The LRU
            // key carries the request method, but a redirect chain can convert the
            // wire method (POST→GET on 301/302/303), so a cached body is a GET
            // artifact only if the request itself is GET. The preloaded shells and
            // the disk cache are URL-keyed with no method separation and are
            // GET-gated at their write sites instead; see fetchAndProcessResponse.
            if (isMainFrame && req.method == "GET") {
                mainFrameCache.get(responseCacheKey)?.let { cached ->
                    serveCachedMainFrame(cached, responseCacheKey, urlString)?.let { return it }
                }

                // Stale-while-revalidate disk cache: on a cold start (empty in-memory
                // cache) serve the persisted raw HTML shell immediately, injecting the
                // current firewall, and refresh the cache in the background so the next
                // navigation is fresh. Only app-shell Discord routes are eligible.
                serveStaleMainFrame(req, urlString, responseCacheKey)?.let { return it }
            }

            val rb = okHttpRequestBuilder(req, urlString)
            response = HttpClient.sharedClient.newCall(rb.build()).execute()
            // Redirects are not auto-followed; resolve 3xx manually through the
            // allowlist gate. Null means the chain never reached a final
            // response. Seed the loop guard with the original URL so a
            // self-3xx host doesn't add an extra hop before the loop is
            // detected.
            val credentialPartition = requestCredentialPartition(req)
            val resolved = resolveRedirects(
                isMainFrame, response, urlString, 0, hashSetOf(urlString),
                initialCookiePairs(req)
            )
            if (resolved == null) {
                return blockedResponse()
            }
            if (resolved !== response) { response.close(); response = resolved }
            val cacheTarget = if (isThemeCss) CacheTarget.THEME_CSS else if (isMainFrame) CacheTarget.MAIN_FRAME else null
            val result = fetchAndProcessResponse(
                req.isForMainFrame, response, isCss, cacheTarget,
                urlString, responseCacheKey, credentialPartition = credentialPartition
            )
            return result
        } catch (e: Exception) {
            VDELog.w("WV", "Fetch failed for ${UrlNormalizer.redactForLog(urlString)}: ${e.javaClass.simpleName}")
            // Fail closed: null would hand the request to Chromium's native
            // fetch, bypassing redirect validation, credential stripping, CSP
            // processing, and parse-time injection. The cache-serve paths
            // above share this catch; a throw there degrades to this error
            // response instead of escaping the network thread.
            return fetchErrorResponse()
        } finally {
            // Close to return the pooled connection (not disconnect()).
            response?.close()
        }
    }

    /**
     * Redirect hops' Set-Cookie headers never reach Chromium on the app-
     * followed chain (only the final response is served). Replay each into
     * the store; full Set-Cookie strings (HttpOnly/SameSite/Expires) are
     * parsed by the store's own cookie parser. Values that could not occur
     * on the wire (NUL, newline) are dropped so a malformed header cannot
     * poison the store. HSTS from hops has no public setter and is accepted
     * as a gap; the final response's HSTS still applies normally.
     *
     * When [eligiblePairs] is supplied (main-frame chains), each applied
     * cookie's (name, value) identity is recorded so a later hop may
     * re-derive it; explicit SameSite=Strict values are skipped because the
     * hop context can be Lax even when the chain is same-origin.
     *
     * Also covers 3xx responses that resolveRedirects blocks (depth limit,
     * loop, unusable Location).
     */
    private fun harvestRedirectCookies(response: Response, eligiblePairs: MutableSet<Pair<String, String>>? = null) {
        val hopUrl = response.request.url.toString()
        var applied = 0
        for ((name, value) in response.headers) {
            if (!name.equals("set-cookie", ignoreCase = true)) continue
            if (value.none { it == '\u0000' || it == '\n' }) {
                CookieManager.getInstance().setCookie(hopUrl, value)
                applied++
                if (eligiblePairs != null && !setCookieSameSiteStrict(value)) {
                    setCookiePair(value)?.let { eligiblePairs.add(it) }
                }
            }
        }
        if (applied > 0) {
            VDELog.d("WV", "Applied $applied redirect-hop cookie(s) for ${UrlNormalizer.redactForLog(hopUrl)}")
        }
    }

    private fun isVencordCssUrl(uri: Uri): Boolean {
        val host = uri.host ?: return false
        if (!isForgeHost(host)) return false
        val urlLower = uri.toString().lowercase()
        return urlLower.contains("vencord") || urlLower.contains("equicord") || urlLower.contains("vendroid")
    }

    /**
     * Interception scope: forge-host CSS (CSP stripping) and GET/HEAD
     * app-origin main frames (CSP + injection pipeline).
     *
     * Redirect hops of natively fetched resources never reach this method.
     * AwProxyingURLLoaderFactory's ShouldNotInterceptRequest() returns true
     * once Chromium redirects such a request, so the hop finishes inside
     * Chromium. The redirect target of an allowlisted image, script, XHR,
     * media, non-GET/HEAD main frame, or service-worker fetch skips this
     * gate, resolveRedirects, the privacy filter, and the user-CSS gate.
     * Chromium's own CORS and cookie rules still apply.
     *
     * Accepted gap: intercepting every subresource and re-issuing it through
     * OkHttp would break CORS semantics, video Range requests, and cookie
     * handling.
     */
    private fun shouldInterceptForCspStripping(req: WebResourceRequest): Boolean {
        val scheme = req.url.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false

        if (req.url.path?.endsWith(".css") == true) {
            val host = req.url.host ?: return false
            if (isForgeHost(host)) return true
        }

        if (req.isForMainFrame) {
            // Full app origin only (https + app host + default port).
            // Non-GET/HEAD main frames go to Chromium; an OkHttp re-issue
            // would drop the body.
            if (isDiscordAppOriginUrl(req.url.toString())) {
                if (req.method == "GET" || req.method == "HEAD") return true
            }
        }

        return false
    }

    /** Cache key = URL + method + variant/conditional headers + credential
     *  session digest, so a cached body is only served to an identical request
     *  from the same session.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun cacheKey(req: WebResourceRequest, urlString: String): String {
        val sb = StringBuilder(urlString)
        sb.append("|m=").append(req.method)
        for (h in VARIANT_HEADERS) {
            // Header names are case-insensitive; the map casing isn't guaranteed.
            val v = req.requestHeaders.entries
                .firstOrNull { it.key.equals(h, ignoreCase = true) }?.value
            if (v != null) sb.append('|').append(h).append('=').append(v)
        }
        // Credential session partition; the digest never leaves memory. The
        // same token is stamped on persisted shells and checked at serve time.
        sb.append(credentialHeadersPartition(req.requestHeaders))
        return sb.toString()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    /**
     * Mutable carrier threaded through the fetchAndProcessResponse stage
     * helpers below. Fields are reassigned as the pipeline advances
     * (header fold, MIME pin, bounded read, raw persist, injection, cache
     * write, cookie delivery); the orchestrator owns the instance and no
     * stage's intermediate state escapes this class.
     */
    private class ProcessedResponse(
        val statusCode: Int,
        val headers: HashMap<String, String>
    ) {
        val setCookies = ArrayList<String>(2)
        // Lowercase server-declared media type, captured by the header fold
        // before MIME normalization; read by the persist and cache stages.
        var declaredCt: String? = null
        var reasonPhrase: String = ""
        var contentType: String = "application/octet-stream"
        var body: ByteArray = ByteArray(0)
        // Pre-injection snapshot of [body]: what the MAIN_FRAME caches store.
        var rawBody: ByteArray = ByteArray(0)
    }

    private fun fetchAndProcessResponse(
        isForMainFrame: Boolean,
        response: Response,
        isCss: Boolean,
        cacheTarget: CacheTarget? = null,
        urlString: String = "",
        cacheKey: String = urlString,
        credentialPartition: String = "",
        publishInjectionClaims: Boolean = true
    ): WebResourceResponse {
        // The injection/CSP/MIME gates must be keyed on the FINAL response host
        // (the target after the app-followed redirect chain in
        // resolveRedirects), NOT the original request host. Otherwise a
        // Discord-origin main-frame request that the server redirects to a
        // non-Discord (but allowlisted) host would be treated as a Discord main
        // frame: CSP stripped/replaced and the capability-token bootstrap +
        // runtimes embedded into attacker-controlled content rendered in the
        // Discord origin.
        val responseUrl = response.request.url
        val host = responseUrl.host ?: ""
        // Capability-token gate: full origin (https + app host + effective
        // port), not just the host, so https://discord.com:8443 never counts
        // as the app origin. Every injection and cache-write decision below
        // keys on it.
        val isAppOrigin = isDiscordAppOriginUrl(responseUrl)

        val statusCode = response.code
        val state = ProcessedResponse(
            statusCode,
            headers = HashMap(response.headers.size.coerceAtLeast(16))
        )

        foldHeadersAndCsp(response, isAppOrigin, isForMainFrame, isCss, state)

        // HTTP/2 has no reason phrase (OkHttp returns ""), so the old "OK"
        // fallback reported a 404 as "404 OK". Use the standard IANA phrase
        // for known codes and leave the rest empty, as h2 does.
        state.reasonPhrase = response.message.takeIf { it.isNotEmpty() }
            ?: STANDARD_REASON_PHRASES[statusCode].orEmpty()

        pinMainFrameMime(isAppOrigin, isForMainFrame, state)
        readBodyBounded(response, state)
        persistRawMainFrame(response, urlString, isAppOrigin, isForMainFrame, credentialPartition, state)
        // A cache-only refresh (see serveStaleMainFrame) discards the returned
        // response, so it must not publish claims for a body Chromium never
        // receives.
        if (publishInjectionClaims) {
            injectAndRecordClaims(urlString, host, isAppOrigin, isForMainFrame, state)
        }
        cacheResult(response, urlString, cacheKey, credentialPartition, cacheTarget, isAppOrigin, state)
        return deliverCookiesAndBuild(response, state, urlString, isForMainFrame)
    }

    /** Header fold + CSP policy switch stage of [fetchAndProcessResponse]. */
    private fun foldHeadersAndCsp(
        response: Response,
        isAppOrigin: Boolean,
        isMainFrame: Boolean,
        isCss: Boolean,
        state: ProcessedResponse
    ) {
        // Fold duplicate header names (see ResponseHeaderMerge); Set-Cookie
        // values are diverted to setCookies and re-attached below.
        for ((key, value) in response.headers) {
            val lowerKey = key.lowercase()
            if (isAppOrigin && lowerKey == "content-security-policy") {
                if (BuildConfig.ENFORCE_STRICT_CSP) {
                    // Enforce the strict policy; the browser itself blocks
                    // exfiltration (connect-src) to non-allowlisted hosts.
                    state.headers["content-security-policy"] = VencordCsp.build()
                } else {
                    // Report-only: keep the enforced policies loose and attach
                    // the strict policy as Report-Only for violation triage.
                    // Append every filtered server policy into one
                    // comma-delimited list (CSP3 §2.2); replacing the value
                    // would drop restrictions browsers enforce conjunctively.
                    val stripped = stripVencordIncompatibleCsp(value)
                    val existing = state.headers["content-security-policy"]
                    state.headers["content-security-policy"] =
                        if (existing == null) stripped else "$existing, $stripped"
                    if (isMainFrame) {
                        state.headers["content-security-policy-report-only"] = VencordCsp.build()
                    }
                }
                continue
            }
            if (isAppOrigin && lowerKey == "content-security-policy-report-only") continue
            ResponseHeaderMerge.merge(state.headers, state.setCookies, lowerKey, value)
        }
        // Server-declared media type, captured before the MIME normalization
        // below: the 2xx app-origin pin forces content-type to text/html, so
        // a later read of the headers can no longer tell a shell from a
        // JSON endpoint body.
        state.declaredCt = state.headers["content-type"]?.lowercase()
        if (isCss) state.headers["content-type"] = "text/css"

        // Install the application CSP even when the server sent none: the
        // in-loop branch above runs only on a server-supplied header, so a
        // CSP-less app-origin response would otherwise receive no app policy
        // in strict mode, and a report-only server header would be dropped
        // without replacement in loose mode.
        if (isAppOrigin) {
            if (BuildConfig.ENFORCE_STRICT_CSP) {
                if (!state.headers.containsKey("content-security-policy")) {
                    state.headers["content-security-policy"] = VencordCsp.build()
                }
            } else if (isMainFrame && !state.headers.containsKey("content-security-policy-report-only")) {
                state.headers["content-security-policy-report-only"] = VencordCsp.build()
            }
        }
    }

    /** App-origin main-frame MIME pinning stage of [fetchAndProcessResponse]. */
    private fun pinMainFrameMime(isAppOrigin: Boolean, isMainFrame: Boolean, state: ProcessedResponse) {
        // WebResourceResponse's mimeType must be a bare media type. The
        // charset goes in the separate "encoding" arg; a MIME carrying it
        // ("text/html; charset=utf-8") makes WebView render the document as
        // plain text. OkHttp's transparent gzip decode can also drop
        // Content-Type and strips Content-Encoding/Content-Length, and the
        // body has already been fully read, so those framing headers are
        // stale at any status code.
        if (isAppOrigin && isMainFrame) {
            if (state.statusCode in 200..299) {
                state.headers["content-type"] = pinnedMainFrameContentType(state.declaredCt)
            } else {
                // Error pages keep the server's media type, bare; the API
                // answers errors in JSON, and forcing text/html would
                // mislabel them. A dropped or empty type still falls back to
                // HTML, since the octet-stream default below would turn the
                // page into a download.
                state.headers["content-type"] =
                    ResponseHeaderMerge.bareMediaType(state.headers["content-type"]) ?: "text/html"
            }
            state.headers.remove("content-encoding")
            state.headers.remove("content-length")
        }
        // Re-read so the served MIME matches what the block above wrote.
        state.contentType = state.headers.getOrDefault("content-type", "application/octet-stream")
    }

    /** Bounded body read stage of [fetchAndProcessResponse]. */
    private fun readBodyBounded(response: Response, state: ProcessedResponse) {
        // OkHttp has no errorStream; byteStream() yields the error page for 4xx/5xx
        // and an empty stream for no-body responses (204/304/HEAD). Read once for
        // all status codes.
        state.body = try {
            // Read the body through a bounded reader so a compromised/oversized
            // host cannot balloon heap here (mirrors HttpClient.readAsText's
            // cap). Seed the buffer from the declared content length (clamped)
            // so we don't pre-allocate a full 16 MB on every intercepted fetch.
            HttpClient.readAsBytes(
                response.body.byteStream(),
                initialSize = response.body.contentLength()
                    .coerceIn(8192L, HttpClient.MAX_READ_BYTES.toLong()).toInt()
            )
        } catch (_: Exception) {
            // Serving fewer bytes than Content-Length promises makes Chromium
            // wait for bytes that never arrive, so drop the framing headers.
            state.headers.remove("content-length")
            state.headers.remove("content-encoding")
            ByteArray(0)
        }
        // The RAW body (pre-injection) is what gets cached / persisted, so a
        // tightened firewall applies at serve time (see MAIN_FRAME cache serve).
        state.rawBody = state.body
    }

    /** RAW main-frame disk persist stage of [fetchAndProcessResponse]. */
    private fun persistRawMainFrame(
        response: Response,
        urlString: String,
        isAppOrigin: Boolean,
        isMainFrame: Boolean,
        credentialPartition: String,
        state: ProcessedResponse
    ) {
        // Persist the RAW main-frame HTML (before injection) for stale-while-
        // revalidate on a later cold start. Injection is applied at serve time
        // with the current firewall config, so only app-shell routes qualify.
        // GET-only: the disk cache is URL-keyed with no method separation and
        // is stale-served to GET navigations. A real HEAD body is empty anyway
        // (isNotEmpty skips it); the gate exists for the 307-preserved POST
        // whose HTML would otherwise seed the store.
        // Each entry is bound to the credential context of its fetch (see
        // credentialPartition), so the directive gate need not prove account
        // independence on its own.
        if (isAppOrigin && isMainFrame && state.statusCode in 200..299 && state.body.isNotEmpty() &&
            response.request.method == "GET" &&
            !cacheExcludedByDirectives(state.headers)
        ) {
            // declaredCt, not the header map: the serving type is pinned to
            // text/html above, so that read can never fail here. Exact media
            // type, not a substring: text/plain; note="text/html" is not HTML
            // and must not enter a store whose serve path rewrites the MIME.
            if (ResponseHeaderMerge.isHtmlMediaType(state.declaredCt)) {
                // body is still raw here; injection happens below. Persist
                // off the network thread with the sanitized headers so a stale
                // serve matches the in-memory cache (incl. security headers).
                enqueueMainFrameWrite(urlString, state.body, HashMap(state.headers), state.reasonPhrase, credentialPartition)
            }
        }
    }

    /** Firewall injection + injection-claim recording stage of [fetchAndProcessResponse]. */
    private fun injectAndRecordClaims(
        urlString: String,
        host: String,
        isAppOrigin: Boolean,
        isMainFrame: Boolean,
        state: ProcessedResponse
    ) {
        // Inject the JS network firewall into every Discord HTML response. This
        // runs before any page scripts and wraps fetch/XHR/WebSocket for hosts
        // that slip past shouldInterceptRequest. The runtimes are embedded
        // alongside when both are in memory, so the renderer parses them at
        // document time instead of blocking the UI thread on a ~1 MB
        // evaluateJavascript round-trip. The HTML check is part of the gate so
        // non-HTML bodies clear claims below.
        val injectionEligible = isAppOrigin && isMainFrame && state.statusCode in 200..299 &&
            state.body.isNotEmpty() &&
            ResponseHeaderMerge.isHtmlMediaType(state.headers["content-type"])
        if (injectionEligible) {
            try {
                val text = state.body.toString(Charsets.UTF_8)
                val result = ResponseHtmlInjector.injectFirewallAndCss(text, urlString, isDiscordMainFrame = isAppOrigin)
                val patched = result.html
                if (patched != null && patched !== text) {
                    state.body = patched.toByteArray(Charsets.UTF_8)
                    state.headers["content-length"] = state.body.size.toString()
                    state.headers.remove("content-encoding")
                    // Record the embedded URL so onPageStarted can skip
                    // re-injection. On overflow, clear so a new navigation
                    // is still tracked (a dropped entry only costs one
                    // idempotent re-injection).
                    updateInjectionClaims(urlString, firewallEmbedded = true, runtimeEmbedded = result.runtimeEmbedded)
                    VDELog.i("WV", "Embedded firewall JS: $host (${FirewallConfig.jsAllowedHosts().size} hosts)")
                    if (result.runtimeEmbedded) {
                        VDELog.i("WV", "Embedded Vencord runtime into main frame")
                    }
                } else {
                    // No `</head>`: raw body. Drop any stranded claim
                    // (see serveStaleMainFrame).
                    EmbeddedUrlClaims.clearAll(urlString)
                }
            } catch (_: Exception) {
                // A decode/inject failure serves the raw body: a stale claim
                // would make onPageStarted skip its fallback.
                EmbeddedUrlClaims.clearAll(urlString)
            }
        } else if (isMainFrame) {
            // The gate above skipped the embed. Drop any claim stranded by an
            // earlier Discord-body serve of this URL, so onPageStarted falls
            // back to evaluateJavascript instead of skipping on a stale
            // claim. Main frames only: claims are never held for
            // subresource URLs.
            EmbeddedUrlClaims.clearAll(urlString)
        }
    }

    /** LRU cache-write stage of [fetchAndProcessResponse]. */
    private fun cacheResult(
        response: Response,
        urlString: String,
        cacheKey: String,
        credentialPartition: String,
        cacheTarget: CacheTarget?,
        isAppOrigin: Boolean,
        state: ProcessedResponse
    ) {
        // MAIN_FRAME caches the RAW (pre-injection) body; the firewall and
        // runtimes are injected at serve time so a config change applies on
        // the next serve. THEME_CSS has no injected content, so it caches
        // the final bytes.
        val bytesToCache = if (cacheTarget == CacheTarget.MAIN_FRAME) state.rawBody else state.body
        // An empty body is never a valid entry. A failed or over-cap read
        // would otherwise be cached as a blank 200 and served for the full
        // TTL, since fresh LRU hits never revalidate.
        if (state.statusCode in 200..299 && cacheTarget != null && bytesToCache.isNotEmpty() &&
            // The response's own directives outrank this cache's TTLs.
            !cacheExcludedByDirectives(state.headers)
        ) {
            // Set-Cookie never enters the header map (diverted to setCookies),
            // so cached headers are cookie-free by construction. A cached
            // replay cannot resurrect an old token (zombie session) or cross
            // accounts on a shared device.
            val headersToCache = HashMap(state.headers).apply {
                remove("content-length")
                remove("content-encoding")
            }
            val entry = CachedResponse(state.statusCode, state.reasonPhrase, headersToCache, bytesToCache)
            when (cacheTarget) {
                CacheTarget.THEME_CSS -> themeCssCache.put(cacheKey, entry)
                CacheTarget.MAIN_FRAME -> if (response.request.method == "GET" &&
                    // Same final-host gate as the injection block above:
                    // cacheTarget is keyed on the original request, so a
                    // redirected third-party body must not land under a
                    // Discord URL (the serve paths inject with trust derived
                    // from that URL). MainFrameDiskCache re-gates the disk
                    // write.
                    isAppOrigin &&
                    // HTML only, judged on the declared type (see declaredCt).
                    ResponseHeaderMerge.isHtmlMediaType(state.declaredCt)
                ) {
                    // GET-only: the LRU key carries the ORIGINAL request's
                    // method, but resolveRedirects may have converted the wire
                    // method, and a non-GET body under that key would serve a
                    // later HEAD/POST navigation. The URL-keyed shell refresh
                    // below is gated here too.
                    mainFrameCache.put(cacheKey, entry)
                    // Refresh the in-memory preloaded shell so the preferred serve
                    // path doesn't fall back to a stale startup-time copy.
                    // isAppOrigin restated: this writes process-lifetime
                    // state and must not rely on the enclosing gate.
                    // The active branch's shell, same URL the cold start
                    // anchors on. A hardcoded stable URL would leave PTB and
                    // canary sessions refreshing the wrong preloaded entry.
                    val activeShellUrl = mainActivity?.appShell?.appShellUrl
                        ?: DiscordBranch.DEFAULT.appShellUrl
                    if (urlString == activeShellUrl && isAppOrigin) {
                        val refreshed = MainFrameDiskCache.CachedMainFrame(
                            state.rawBody, state.reasonPhrase, headersToCache,
                            System.currentTimeMillis(), credentialPartition
                        )
                        val shells = HashMap(StaleMainFrame.preloadedShells)
                        shells[urlString] = refreshed
                        StaleMainFrame.preloadedShells = shells
                    }
                }
            }
        }
    }

    /**
     * Set-Cookie delivery + final response construction stage of
     * [fetchAndProcessResponse]. The multivalue channel returns early on
     * success; any glue failure falls through to the CookieManager replay.
     *
     * Cookies are stored only for main-frame responses. Navigations send
     * credentials, so a Set-Cookie is unambiguous there. A subresource chain's
     * initiating frame and credentials mode are not visible from
     * WebResourceRequest, and the only intercepted subresources are forge
     * stylesheets, so a cookie they set would guess at browser policy.
     *
     * [originalUrlString] is the URL the interception started from, not the
     * final response URL. The multivalue channel is used only when both are
     * same-origin and share a path: Chromium stores attached cookies against
     * the original request URL, so a cross-host chain lands them under the
     * wrong host, and a same-origin path change derives a no-Path cookie's
     * default path from the wrong URL (see [multiCookieChannelAllowed]).
     */
    private fun deliverCookiesAndBuild(
        response: Response,
        state: ProcessedResponse,
        originalUrlString: String,
        isForMainFrame: Boolean
    ): WebResourceResponse {
        if (isForMainFrame && state.setCookies.isNotEmpty()) {
            val responseUrl = response.request.url.toString()
            if (isMultiCookieChannelSupported() &&
                multiCookieChannelAllowed(originalUrlString, response.request.url)
            ) {
                // Chromium M138+ splits the androidx wrapper's NUL-joined value
                // into real Set-Cookie headers, so every cookie survives with
                // full attribute fidelity (HttpOnly, SameSite, Expires).
                try {
                    val compat = WebResourceResponseCompat(
                        state.contentType, "utf-8", state.statusCode, state.reasonPhrase,
                        state.headers, ByteArrayInputStream(state.body)
                    )
                    compat.setCookies(state.setCookies)
                    return compat.toWebResourceResponse()
                } catch (_: Exception) {
                    // Glue failure; fall through to the CookieManager path.
                }
            }
            // Without the multivalue channel, or on a cross-origin chain
            // where attached cookies would be stored under the original
            // request's host, the flat map can carry only one Set-Cookie,
            // and comma-joining corrupts Expires dates. Replay each into
            // the cookie store, scoped to the final response URL.
            // setCookie is void, so a store that refuses a cookie logs nothing
            // here; the delivery log below still shows what was attempted.
            for (cookie in state.setCookies) {
                CookieManager.getInstance().setCookie(responseUrl, cookie)
            }
            VDELog.d("WV", "Delivered ${state.setCookies.size} Set-Cookie header(s) for ${UrlNormalizer.redactForLog(responseUrl)}")
        }

        return WebResourceResponse(state.contentType, "utf-8", state.statusCode, state.reasonPhrase, state.headers, ByteArrayInputStream(state.body))
    }

    /**
     * The multivalue Set-Cookie channel needs Chromium M138+ glue. The
     * provider's COOKIE_INTERCEPT feature report is ground truth; OEM
     * WebViews vary and version strings are unreliable. Support is fixed for
     * the process lifetime once WebView is loaded, so the check is cached.
     */
    @Volatile
    private var multiCookieChannel: Boolean? = null

    private fun isMultiCookieChannelSupported(): Boolean {
        multiCookieChannel?.let { return it }
        val supported = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.COOKIE_INTERCEPT)
        } catch (_: Exception) {
            false
        }
        multiCookieChannel = supported
        return supported
    }

    companion object {
        // Theme CSS is public, changes rarely, and is fetched from forge hosts.
        // A longer TTL avoids a blocking network-thread refetch on every
        // navigation while still picking up theme edits within a session.
        private const val THEME_CSS_TTL_MS = 300_000L
        private const val MAIN_FRAME_TTL_MS = 300_000L
        private val STRIPPED_CONDITIONAL_HEADERS = setOf(
            "if-none-match", "if-modified-since", "if-unmodified-since", "if-match"
        )
        // OkHttp throws IllegalArgumentException on these reserved (hop-by-hop /
        // session) headers. They are managed by the HTTP client itself and
        // normally not surfaced in getRequestHeaders(), but we drop them
        // defensively.
        private val OKHTTP_RESTRICTED_HEADERS = setOf(
            "host", "content-length", "transfer-encoding", "connection", "keep-alive",
            "proxy-authorization", "te", "trailer", "upgrade"
        )

        // Credential-bearing request headers that must never be forwarded across
        // an origin change, so a cross-origin redirect cannot leak the origin's
        // session/authorization to the target. (proxy-authorization is already in
        // OKHTTP_RESTRICTED_HEADERS.)
        private val CREDENTIAL_HEADERS = setOf(
            "cookie", "authorization", "proxy-authorization", "authentication-info"
        )
        // IANA reason phrases (RFC 9110) for codes that surface through the
        // interceptor; consulted only when the wire carried none (HTTP/2).
        private val STANDARD_REASON_PHRASES = mapOf(
            200 to "OK", 201 to "Created", 202 to "Accepted", 204 to "No Content",
            206 to "Partial Content",
            301 to "Moved Permanently", 302 to "Found", 303 to "See Other",
            304 to "Not Modified", 307 to "Temporary Redirect", 308 to "Permanent Redirect",
            400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed", 408 to "Request Timeout",
            409 to "Conflict", 410 to "Gone", 413 to "Content Too Large",
            414 to "URI Too Long", 415 to "Unsupported Media Type",
            416 to "Range Not Satisfiable", 429 to "Too Many Requests",
            431 to "Request Header Fields Too Large", 451 to "Unavailable For Legal Reasons",
            500 to "Internal Server Error", 501 to "Not Implemented", 502 to "Bad Gateway",
            503 to "Service Unavailable", 504 to "Gateway Timeout",
            505 to "HTTP Version Not Supported"
        )
        // Forge hosts are only trusted to serve user-theme CSS. This predicate
        // (the single copy) serves both the instance CSP-stripping interception
        // (isVencordCssUrl / shouldInterceptForCspStripping) and the shared
        // network gate here in the companion, which enforces the CSS-only rule.
        private val FORGE_HOSTS_EXACT = hashSetOf(
            "github.com", "raw.githubusercontent.com", "codeberg.org",
            "githack.com", "raw.githack.com", "cdn.githack.com", "cbcdn.githack.com"
        )
        // No cache: this is a hash-set lookup plus a few suffix checks, and an
        // unbounded map let page content grow process memory with distinct
        // hostnames.
        private fun isForgeHost(host: String): Boolean =
            host in FORGE_HOSTS_EXACT ||
                host.endsWith(".github.io") ||
                host.endsWith(".codeberg.page") ||
                host.endsWith(".githack.com")

        // Thread-safe LRU cache wrapping LruCache because
        // shouldInterceptRequest runs on Chromium network threads and can be
        // invoked concurrently.
        private class ThreadSafeLruCache(maxSize: Int) {
            private val cache = object : LruCache<String, CachedResponse>(maxSize) {
                override fun sizeOf(key: String, value: CachedResponse): Int = value.body.size
            }
            @Synchronized fun get(key: String): CachedResponse? = cache.get(key)
            @Synchronized fun put(key: String, value: CachedResponse): CachedResponse? = cache.put(key, value)
            @Synchronized fun remove(key: String): CachedResponse? = cache.remove(key)
        }

        private val themeCssCache = ThreadSafeLruCache(2 * 1024 * 1024)
        private val mainFrameCache = ThreadSafeLruCache(2 * 1024 * 1024)

        // Privacy filter: blocks Discord telemetry, Sentry, fingerprinting,
        // and (optionally) typing indicators at the path level. Shared by
        // VWebviewClient.shouldInterceptRequest and the Service Worker client
        // in MainActivity so SW-fetched requests can't bypass it.

        @Volatile
        private var blockTypingIndicator = false

        private val SENTRY_PATTERN = Regex("^/assets/sentry\\.[^/]+\\.js$")

        /** Update the typing indicator block. Called at startup and on toggle. */
        fun updateTypingBlock(block: Boolean) {
            blockTypingIndicator = block
        }

        // Cached WebView UA for intercepted fetches. Read on Chromium network
        // threads, which must not query WebView settings.
        @Volatile
        private var webViewUserAgent: String? = null

        /** Refreshes the cache. Called at WebView setup and on recreation. */
        fun updateWebViewUserAgent(userAgent: String?) {
            webViewUserAgent = userAgent
        }

        /**
         * Decoded full path of an OkHttp URL, mirroring Android Uri.getPath():
         * percent-decoded segments joined with "/". HttpUrl has no decoded
         * full-path accessor and encodedPath preserves escapes like %62, so
         * the shared gates must not compare encoded paths ("/%62log" would
         * slip past a check for "/blog" or "/science"). The result always
         * carries a leading "/"; an empty path yields "/" where Uri yields
         * "", and no rule matches either.
         */
        internal fun decodedPath(url: HttpUrl): String =
            "/" + url.pathSegments.joinToString("/")

        /**
         * Content type for a 2xx app-origin main frame. Pins text/html only
         * when the server declared none (OkHttp's transparent gzip decode can
         * drop Content-Type) or declared HTML; any other declared type is kept
         * bare, so a JSON or text/plain body is never converted into
         * executable HTML in the trusted origin. [declaredCt] is already
         * lowercase (captured in the header fold).
         */
        internal fun pinnedMainFrameContentType(declaredCt: String?): String {
            val bare = ResponseHeaderMerge.bareMediaType(declaredCt)
            return if (bare == null || bare == "text/html") "text/html" else bare
        }

        /**
         * Returns a blocking [WebResourceResponse] if the request matches a
         * privacy filter, or null to allow. Always blocks telemetry (/science,
         * /track), Sentry SDK, and fingerprinting (/api.js, /cdn-cgi/). Blocks
         * typing indicators only if [blockTypingIndicator] is true.
         *
         * A fresh [WebResourceResponse] is allocated per call to match the
         * existing host-block pattern and avoid relying on Chromium
         * stream-reuse semantics.
         */
        fun shouldBlockForPrivacy(host: String?, path: String?, lowerHost: String? = host?.lowercase()): WebResourceResponse? {
            if (host == null || path.isNullOrEmpty()) return null
            if (!Constants.isDiscordDomainLower(lowerHost ?: return null)) return null
            return when {
                path.endsWith("/science") || path.endsWith("/track") -> {
                    VDELog.d("WV", "Blocked telemetry: $path")
                    blockedResponse()
                }
                path.endsWith("/api.js") || path.startsWith("/cdn-cgi/") -> {
                    VDELog.d("WV", "Blocked fingerprinting: $path")
                    blockedResponse()
                }
                SENTRY_PATTERN.matches(path) -> {
                    VDELog.d("WV", "Blocked Sentry SDK: $path")
                    blockedResponse()
                }
                blockTypingIndicator && path.endsWith("/typing") -> {
                    VDELog.d("WV", "Blocked typing indicator: $path")
                    blockedResponse()
                }
                else -> null
            }
        }
        // 301/302/303 are the only codes that (may) change the request
        // method; preserving it is the entire purpose of 307/308.
        private val POST_TO_GET_REDIRECT_CODES = setOf(301, 302, 303)

        // The only 3xx statuses followed by resolveRedirects. Derived from the
        // conversion set so the invariant "every method-converting status is
        // followable" cannot drift. 300-with-Location (RFC 9110 §15.4.1), 304
        // and 305/306 have no semantics this path implements and fail closed;
        // see the gate in resolveRedirects.
        private val FOLLOWABLE_REDIRECT_CODES = POST_TO_GET_REDIRECT_CODES + setOf(307, 308)

        /**
         * The method a follow-up redirect request must use, given the redirect
         * status and the method actually sent on the wire for this hop.
         *
         * resolveRedirects derives the input from the current hop's
         * [Response.request], never from the original [WebResourceRequest], so
         * a POST→GET conversion on an early hop survives later 307/308 hops.
         * The result is applied through [applyMethodAndBody].
         *
         *  - 307/308: preserve the method unconditionally (RFC 9110
         *    §15.4.7/§15.4.8).
         *  - 301/302/303: convert POST to GET, the historical browser behavior
         *    and the only method change RFC 9110 sanctions. GET and HEAD keep
         *    their method on every code, so a redirected HEAD never downloads
         *    the target body.
         *  - Anything else preserves the method; only [FOLLOWABLE_REDIRECT_CODES]
         *    statuses reach here.
         */
        private fun redirectMethod(statusCode: Int, currentMethod: String): String {
            if (currentMethod == "POST" && statusCode in POST_TO_GET_REDIRECT_CODES) return "GET"
            return currentMethod
        }

        /**
         * Applies [method] to [rb] with a body pairing OkHttp accepts.
         * Request.Builder.method() throws IllegalArgumentException unless
         * GET/HEAD are bodiless and every other method has one, and a builder
         * defaults to GET, so the method must be set explicitly. WebResource
         * requests expose no body, so non-GET/HEAD methods carry
         * [RequestBody.EMPTY] (Content-Length: 0 on the wire).
         */
        private fun applyMethodAndBody(rb: Request.Builder, method: String) {
            val body = if (method == "GET" || method == "HEAD") null else RequestBody.EMPTY
            rb.method(method, body)
        }

        /** Blocking response: 200 + empty text/plain body. A 204 can let
         *  Chromium serve a cached copy; a 200 with mismatched MIME makes the
         *  resource a no-op (no script execution, no CSS application). */
        private fun blockedResponse(): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        /**
         * Blocking response for an intercepted request whose app-side fetch
         * threw. Unlike [blockedResponse] this is a 502 with no-store: the
         * failure must not be cached. Chromium renders a returned response
         * verbatim (no neterror page), so the main frame stays blank until
         * the next navigation, the same UX as a rejected redirect chain.
         */
        private fun fetchErrorResponse(): WebResourceResponse =
            WebResourceResponse(
                "text/plain", "utf-8", 502, "Bad Gateway",
                mapOf("cache-control" to "no-store"),
                ByteArrayInputStream(ByteArray(0))
            )

        /**
         * The operator-controlled stylesheets vencord_mobile.js fetches on
         * every page load (browser.css / moreFixes.css). They live on forge
         * hosts and end in .css, so the recovery user-theme gate must exempt
         * them or it would break the mod's own fix CSS.
         *
         * Compared on scheme + host + effective port + path; the query is
         * ignored (cache-busting variants). Port is part of the origin: the
         * same host on a different port must not inherit the exemption.
         */
        private data class OperatorCssUrl(
            val scheme: String,
            val host: String,
            val port: Int,
            val path: String
        )

        private val OPERATOR_CSS_URLS: List<OperatorCssUrl> = listOf(
            Constants.VENCORD_CSS_URL,
            Constants.EQUICORD_CSS_URL,
            VendroidApp.MORE_FIXES_CSS_URL
        ).map { url ->
            val uri = Uri.parse(url)
            OperatorCssUrl(
                scheme = uri.scheme.orEmpty(),
                host = uri.host.orEmpty(),
                port = effectivePort(uri.scheme, uri.port),
                path = uri.path.orEmpty()
            )
        }

        /**
         * True when [uri] is one of [OPERATOR_CSS_URLS] (scheme + host +
         * effective port + path, query ignored).
         */
        internal fun isOperatorCssUrl(uri: Uri): Boolean {
            val scheme = uri.scheme ?: return false
            val host = uri.host ?: return false
            val path = uri.path ?: return false
            val port = effectivePort(scheme, uri.port)
            return OPERATOR_CSS_URLS.any { op ->
                scheme.equals(op.scheme, ignoreCase = true) &&
                    host.equals(op.host, ignoreCase = true) &&
                    port == op.port &&
                    path == op.path
            }
        }

        /**
         * Layer 1 of the recovery session's user-theme gate: while
         * [HttpClient.userCssDisabled] is raised, blocks forge-host
         * stylesheets except the operator CSS. Returns a blocking response,
         * or null to allow the request.
         *
         * Broader than [isVencordCssUrl]: that predicate is a
         * cache-classification heuristic whose vencord|equicord|vendroid
         * substring check would miss user themes such as
         * `raw.githubusercontent.com/SomeUser/theme/main/theme.css`. Fails
         * closed: all forge-host CSS is blocked except the exempt operator
         * URLs, even a page's non-theme CSS.
         *
         * Uploaded themes are not visible here: they are imported as blob:
         * URLs from IndexedDB, which never reach shouldInterceptRequest, and
         * are covered by the runtime prelude's VencordNative setter trap
         * (Layer 2).
         */
        internal fun userCssGateResponse(uri: Uri): WebResourceResponse? {
            if (!HttpClient.userCssDisabled) return null
            if (uri.path?.endsWith(".css") != true) return null
            val host = uri.host?.lowercase() ?: return null
            if (!isForgeHost(host)) return null
            if (isOperatorCssUrl(uri)) return null
            logUserCssBlock(host)
            return themeBlockedResponse()
        }

        /**
         * Blocking response for the user-theme gate: 403 with an empty body
         * and no-cache, so Chromium neither applies nor stores a suppressed
         * theme. Unlike [blockedResponse] (200) a 403 cannot be mistaken for
         * a successful empty stylesheet by cache heuristics.
         */
        private fun themeBlockedResponse(): WebResourceResponse =
            WebResourceResponse(
                "text/css", "utf-8", 403, "Forbidden",
                mapOf("Cache-Control" to "no-cache"),
                ByteArrayInputStream(ByteArray(0))
            )

        /**
         * Minimum gap between "theme CSS blocked" log lines. A failed @import
         * can be retried on every navigation, which would flood the shareable
         * log; the first block always logs (the initial stamp is 0).
         */
        private const val THEME_BLOCK_LOG_INTERVAL_MS = 10_000L

        @Volatile
        private var lastThemeBlockLogAtMs = 0L

        private fun logUserCssBlock(host: String) {
            val now = System.currentTimeMillis()
            // Non-atomic check-and-set is fine: a lost update just logs the
            // same host twice, which the rate limit absorbs.
            if (now - lastThemeBlockLogAtMs < THEME_BLOCK_LOG_INTERVAL_MS) return
            lastThemeBlockLogAtMs = now
            VDELog.w("WV", "User themes disabled; blocked theme CSS: $host")
        }

        /**
         * True if a `data:` URL payload is inert (cannot execute script) in
         * both contexts that consult this predicate:
         *
         * - NAVIGATION (subframe document loads, via
         *   VWebviewClient.shouldOverrideUrlLoading): this list alone applies.
         *   `data:image/svg+xml` is NOT inert there (an SVG document runs
         *   inline `onload` script) and `data:text/html` is raw HTML.
         * - SUBRESOURCE (shouldBlockForRequest): adds `data:image/` (kept
         *   broad, not restricted to png/jpeg/etc.): as a SUBRESOURCE (<img>),
         *   data:image/svg+xml does NOT execute scripts, and Discord relies on
         *   it for avatars/icons.
         *
         * Uses ignoreCase startsWith instead of lowercasing the whole URL:
         * data: payloads can be hundreds of KB, and a full-string lowercase
         * copies them.
         */
        private fun isInertDataPayload(url: String): Boolean =
            url.startsWith("data:font/", ignoreCase = true) ||
                url.startsWith("data:application/font", ignoreCase = true) ||
                url.startsWith("data:text/css", ignoreCase = true) ||
                url.startsWith("data:application/octet-stream", ignoreCase = true) // some fonts use this

        /**
         * Shared scheme + host + privacy interception gate used by both the
         * WebView client's [shouldInterceptRequest] and the Service Worker
         * client (in MainActivity), so the two enforcement paths cannot drift.
         *
         * Restricts to browser/inline schemes (data:, blob: allowed for
         * embedded media/images), blocks `http` (MITM risk), blocks any host
         * outside the domain allowlist, and applies the privacy path filter.
         * Returns a blocking response if the request must be denied, or null
         * to allow it through to the fetch/CSP path.
         */
        @RequiresApi(Build.VERSION_CODES.N)
        fun shouldBlockForRequest(request: WebResourceRequest): WebResourceResponse? {
            val scheme = request.url.scheme
            // data:/blob: have a null host and would bypass the domain
            // allowlist. Allow only inert media/font/CSS data: payloads; block
            // executable data: payloads that could run script in the page
            // origin. The bridge capability token neutralizes any data:
            // subframe that does load, so this is defense in depth.
            //
            // NOTE: data:image/ is kept broad (not restricted to png/jpeg/etc.)
            // because as a SUBRESOURCE (<img>), data:image/svg+xml does NOT
            // execute scripts. Discord relies on it for avatars/icons. The
            // SVG-script-execution risk applies only in navigation/subframe
            // contexts, handled by shouldOverrideUrlLoading.
            if (scheme == "data") {
                val url = request.url.toString()
                val isInert = url.startsWith("data:image/", ignoreCase = true) || isInertDataPayload(url)
                if (!isInert) {
                    return blockedResponse()
                }
                // Inert data: payloads are allowed; no host gate applies.
                return shouldBlockForPrivacy(null, null)
            }

            val host = request.url.host
            val lowerHost = host?.lowercase()
            if (shouldBlockUri(scheme, host, request.url.path, lowerHost)) {
                return blockedResponse()
            }
            // Recovery session ("Disable themes"): suppress user theme CSS
            // from forge hosts before it can be fetched or served from the
            // theme CSS cache. Runs before the theme-cache lookup and the
            // CSP-strip fetch in shouldInterceptRequest, and inside this
            // shared gate so the Service Worker path cannot drift from the
            // WebView path.
            userCssGateResponse(request.url)?.let { return it }
            // Privacy: block Discord telemetry, Sentry, and fingerprinting before
            // the CSP-stripping/fetch path so they never reach the network.
            return shouldBlockForPrivacy(host, request.url.path, lowerHost)
        }

        /**
         * The core scheme + host + forge-host-CSS-only gate, shared by both the
         * direct request path and the redirect re-validation path so they cannot
         * drift. Returns true when the URL must be blocked.
         *
         * Note: unlike [shouldBlockForRequest] this does NOT apply the privacy
         * path filter (callers with a host/path apply it separately), and does
         * NOT handle data: payloads (the direct path does; redirect Location
         * targets are never data:).
         */
        private fun shouldBlockUri(scheme: String?, host: String?, path: String?, lowerHost: String? = host?.lowercase()): Boolean {
            // data:, file:, intent:, and custom schemes have a null host and
            // would otherwise bypass the domain allowlist below. blob: is
            // needed for Discord media.
            if (scheme != "https" && scheme != "http" && scheme != "blob") {
                return true
            }

            if (host != null && (lowerHost == null || !Constants.isAllowedDomainLower(lowerHost))) {
                // Block non-whitelisted subresources (scripts, images, XHR, media, etc.)
                return true
            }
            if (scheme == "http") {
                return true
            }
            // Forge hosts are only trusted for user-theme CSS. Block all other
            // subresources so attacker content on those hosts can't exfiltrate.
            //
            // Note: the CSS itself isn't inert. Attribute-selector + url()
            // exfiltration to a .css endpoint on an allowlisted forge host is
            // possible. Accepted as a trust trade-off of remote-theme support.
            if (lowerHost != null && isForgeHost(lowerHost)) {
                val isCss = path?.endsWith(".css") == true
                if (!isCss) return true
            }
            return false
        }

        // Bounded executor for SWR background refetches. Per-URL dedup avoids
        // redundant refreshes during rapid navigation, and [revalidationSlots]
        // caps total queued plus running refreshes globally. The bounded queue
        // is a backstop: the slot gate means the executor should never see
        // more than MAX_OUTSTANDING_REVALIDATIONS tasks, and AbortPolicy turns
        // any impossible overflow into a caught rejection rather than unbounded
        // queue growth.
        private const val MAX_OUTSTANDING_REVALIDATIONS = 8
        private val revalidateExecutor: Executor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(MAX_OUTSTANDING_REVALIDATIONS),
            { r -> Thread(r, "vd-swr-revalidate") },
            ThreadPoolExecutor.AbortPolicy()
        )
        private val revalidatingUrls = ConcurrentHashMap.newKeySet<String>()
        private val revalidationSlots = Semaphore(MAX_OUTSTANDING_REVALIDATIONS)

        // Best-effort raw main-frame persistence. Writes are coalesced per URL
        // and the bytes waiting behind the single writer are capped, so
        // repeated navigations cannot retain unbounded bodies off-thread. The
        // executor queue holds at most the one drain task started below.
        private const val MAX_PENDING_DISK_BYTES = 8L * 1024 * 1024
        private val diskCacheExecutor: Executor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(1),
            { r -> Thread(r, "vd-mainframe-disk") },
            ThreadPoolExecutor.AbortPolicy()
        )
        private class PendingMainFrameWrite(
            val url: String,
            val body: ByteArray,
            val headers: Map<String, String>,
            val reasonPhrase: String,
            val credentialPartition: String
        )
        private val pendingDiskWrites = HashMap<String, PendingMainFrameWrite>()
        private var pendingDiskBytes = 0L
        private var diskDrainScheduled = false
        private val pendingDiskLock = Any()

        /**
         * Queues [body] for disk persistence, replacing any pending write for
         * the same URL. Drops the newest write when the pending byte budget is
         * exhausted (the disk cache is best-effort). Bodies the disk cache
         * would reject by size are dropped here instead of being retained.
         */
        private fun enqueueMainFrameWrite(
            urlString: String,
            body: ByteArray,
            headers: Map<String, String>,
            reasonPhrase: String,
            credentialPartition: String
        ) {
            if (body.isEmpty() || body.size > MainFrameDiskCache.MAX_BODY_BYTES) return
            val entry = PendingMainFrameWrite(urlString, body, headers, reasonPhrase, credentialPartition)
            val schedule: Boolean
            synchronized(pendingDiskLock) {
                val previous = pendingDiskWrites.put(urlString, entry)
                pendingDiskBytes += body.size - (previous?.body?.size ?: 0)
                if (pendingDiskBytes > MAX_PENDING_DISK_BYTES) {
                    // Over budget: drop this capture. Restore the accounting
                    // only if no other thread has replaced the entry meanwhile.
                    if (pendingDiskWrites.remove(urlString, entry)) {
                        pendingDiskBytes -= body.size
                    }
                    return
                }
                schedule = !diskDrainScheduled
                diskDrainScheduled = true
            }
            if (schedule) {
                try {
                    diskCacheExecutor.execute { drainPendingDiskWrites() }
                } catch (_: RejectedExecutionException) {
                    // The schedule gate makes this unreachable, but a rejection
                    // must not wedge the flag or strand the pending writes.
                    synchronized(pendingDiskLock) { diskDrainScheduled = false }
                }
            }
        }

        /** Drains the coalesced disk queue; runs on [diskCacheExecutor]. */
        private fun drainPendingDiskWrites() {
            while (true) {
                val next = synchronized(pendingDiskLock) {
                    val oldest = pendingDiskWrites.entries.firstOrNull()
                    if (oldest == null) {
                        diskDrainScheduled = false
                        null
                    } else {
                        pendingDiskWrites.remove(oldest.key)
                        pendingDiskBytes -= oldest.value.body.size
                        oldest.value
                    }
                } ?: return
                try {
                    MainFrameDiskCache.writeMainFrame(
                        next.url, next.body, next.headers, next.reasonPhrase,
                        credentialPartition = next.credentialPartition
                    )
                } catch (_: Exception) {
                    // Best-effort; keep draining.
                }
            }
        }

        // Delegates: the preloaded-shell store moved to StaleMainFrame;
        // VendroidApp and MainFrameDiskCache call through VWebviewClient.
        fun preloadMainFrameCache() = StaleMainFrame.preloadMainFrameCache()
        fun clearPreloadedShells() = StaleMainFrame.clearPreloadedShells()
    }
}


// Delegates to ResponseHtmlInjector; kept top-level so
// EscapeScriptTagContentTest can call it unqualified.
internal fun escapeScriptTagContent(s: String): String =
    ResponseHtmlInjector.escapeScriptTagContent(s)
