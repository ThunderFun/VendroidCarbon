package com.nin0dev.vendroid.webview

import java.util.concurrent.ConcurrentHashMap

/**
 * Claim bookkeeping for JS already embedded into a main-frame URL's HTML.
 *
 * ResponseHtmlInjector can embed the JS network firewall (and the Vencord
 * runtimes) directly into a served HTML body; VWebviewClient.onPageStarted
 * must then skip its own evaluateJavascript fallback for that URL. A claim is
 * recorded only when the script actually embedded; a claim stranded by an
 * aborted load is removed so the next visit cannot consume a claim for a body
 * that lacks the scripts; on overflow the set is cleared so new navigations
 * stay tracked. Drift here breaks onPageStarted's skip logic (fail-open: the
 * page runs without the JS firewall).
 *
 * Both sets, both caps, and the overflow-clear + add/remove pattern were
 * previously copy-pasted at four sites across VWebviewClient; the pattern is
 * implemented exactly once here.
 */
internal object EmbeddedUrlClaims {
    // Track main-frame URLs that already have the network firewall JS
    // embedded in the HTML (from shouldInterceptRequest / fetchAndProcessResponse).
    // When onPageStarted fires for one of these, the combined
    // firewall+animation evaluateJavascript can be skipped entirely,
    // saving one IPC round-trip.  Capped at 16 entries to bound memory.
    private val firewallEmbeddedUrls = ConcurrentHashMap.newKeySet<String>()
    private const val MAX_FIREWALL_TRACKED = 16

    // Track main-frame URLs that already have the Vencord runtimes embedded
    // in the HTML. Mirrors firewallEmbeddedUrls so onPageStarted can skip
    // the (up to ~1 MB) evaluateJavascript round-trip for those URLs.
    private val runtimeEmbeddedUrls = ConcurrentHashMap.newKeySet<String>()
    private const val MAX_RUNTIME_TRACKED = 16

    /**
     * Check if the firewall JS was already embedded in the HTML for [url]
     * by a prior shouldInterceptRequest pass.  Consumes the flag (removes it)
     * so it's only used once per URL.
     */
    fun consumeFirewall(url: String): Boolean = firewallEmbeddedUrls.remove(url)

    /**
     * Check if the Vencord runtimes were already embedded into the HTML for
     * [url] by a prior shouldInterceptRequest pass. Consumes the flag so it's
     * only used once per URL. Returns false if they were not embedded (e.g. not
     * in memory at serve time), letting [VWebviewClient.onPageStarted] fall
     * through to bridge injection.
     */
    fun consumeRuntime(url: String): Boolean = runtimeEmbeddedUrls.remove(url)

    /** Record that the firewall JS is embedded in [url]'s HTML. On overflow, clears the set so a new navigation is still tracked. */
    fun recordFirewall(url: String) {
        if (firewallEmbeddedUrls.size >= MAX_FIREWALL_TRACKED) firewallEmbeddedUrls.clear()
        firewallEmbeddedUrls.add(url)
    }

    /** Drop a firewall claim stranded by an aborted or unpatched serve of [url]. */
    fun clearFirewall(url: String) {
        firewallEmbeddedUrls.remove(url)
    }

    /** Record that the Vencord runtimes are embedded in [url]'s HTML. On overflow, clears the set so a new navigation is still tracked. */
    fun recordRuntime(url: String) {
        if (runtimeEmbeddedUrls.size >= MAX_RUNTIME_TRACKED) runtimeEmbeddedUrls.clear()
        runtimeEmbeddedUrls.add(url)
    }

    /** Drop a runtime claim stranded by an aborted or unpatched serve of [url]. */
    fun clearRuntime(url: String) {
        runtimeEmbeddedUrls.remove(url)
    }

    /** Drop both claims (firewall and runtime) for [url]. */
    fun clearAll(url: String) {
        firewallEmbeddedUrls.remove(url)
        runtimeEmbeddedUrls.remove(url)
    }
}
