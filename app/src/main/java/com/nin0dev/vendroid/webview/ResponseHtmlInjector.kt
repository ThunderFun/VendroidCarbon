package com.nin0dev.vendroid.webview

import com.nin0dev.vendroid.utils.JsPatches
import java.util.concurrent.atomic.AtomicReference

internal object ResponseHtmlInjector {
    private val disableHighlightCss = "html{-webkit-tap-highlight-color:transparent}a,button,[role=\"button\"],input,textarea,select,[tabindex]:not([tabindex=\"-1\"]){outline:none}"

    private val disableHighlightTag = "<style id=\"vendroid-disable-highlight\">$disableHighlightCss</style>"

    /**
     * Result of [injectFirewallAndCss].
     *
     * [runtimeEmbedded] reflects the same snapshot that performed the embed,
     * so it always matches the served HTML. Callers must claim
     * EmbeddedUrlClaims.recordRuntime from this flag, never from a fresh re-read of the
     * HttpClient statics: a preload publishing between the embed snapshot and
     * a re-read would claim runtimes the HTML lacks, and onPageStarted would
     * then skip its typeof probe and missedInjection recovery.
     */
    internal class InjectionResult(
        val html: String?,
        val runtimeEmbedded: Boolean
    )

    /**
     * Injects the JS network firewall, CSP violation reporter, disable-highlight
     * CSS, and, when both runtimes are in memory, the Vencord runtimes into a
     * Discord main-frame shell at `</head>`.
     *
     * The runtimes are only embedded on Discord main-frames (matching the
     * trust gate in [onPageStarted]); they must never run on whitelisted
     * non-Discord pages, which would expose the bridge object. All blocks are
     * inserted at a single `headIdx` from the ORIGINAL text, with the style
     * placed first: the downloaded runtime may itself contain a literal
     * `</head>`, so re-scanning the patched string could relocate the style
     * into script text where it is never applied. Runtime content is escaped
     * via [escapeScriptTagContent] so neither a `</script>` nor a `<!--` can
     * stop the inline tag from terminating.
     *
     * @return an [InjectionResult] whose [InjectionResult.html] is the patched
     *   HTML, or null when no `</head>` was found (callers serve the unpatched
     *   body). A non-null html may still lack the runtime scripts;
     *   [InjectionResult.runtimeEmbedded] is the embed verdict for that body.
     */    internal fun injectFirewallAndCss(
        text: String,
        urlString: String,
        isDiscordMainFrame: Boolean
    ): InjectionResult {
        val headIdx = findHeadCloseIndex(text)
        if (headIdx < 0) return InjectionResult(null, false)
        // Gate on the local snapshot, not a fresh static re-read: a re-read of
        // the statics here could race a mid-session null (clientMod switch)
        // against the !! uses below. Callers propagate runtimeReady as the
        // embed verdict (see InjectionResult).
        val runtime = HttpClient.VencordRuntime
        val mobileRuntime = HttpClient.VencordMobileRuntime
        val runtimeReady = isDiscordMainFrame && !HttpClient.vencordDisabled &&
            runtime != null && mobileRuntime != null
        val firewallJs = JsPatches.NETWORK_FIREWALL_JS
        val animJs = JsPatches.ANIMATION_PATCH_JS
        val cspJs = JsPatches.CSP_VIOLATION_REPORTER_JS
        val sb = StringBuilder(
            text.length + firewallJs.length + animJs.length + cspJs.length +
                (runtime?.length ?: 0) + (mobileRuntime?.length ?: 0) + 256
        )
        sb.append(text, 0, headIdx)
        sb.append(disableHighlightTag)
        // Embedded form of STARTUP_PATCHES_JS, so the patches run at parse
        // time. The payload sets must match: once the embed is recorded,
        // onPageStarted skips the evaluateJavascript fallback, so a patch
        // missing from this tag never runs on the common path. The animation
        // patch was once omitted here, and the background visibility spoof
        // silently no-opped on every load. These strings go in raw; none may
        // contain "</script" or "<!--".
        sb.append("<script>$firewallJs;$animJs;$cspJs</script>")
        if (runtimeReady) {
            sb.append("<script>")
                .append(escapeScriptTagContent(VencordNative.bridgeBootstrapJs()))
                .append("</script>")
                // Gate flag then env shim must precede the bundle
                // (see JsPatches.vencordPreludeJs).
                .append("<script>").append(JsPatches.vencordPreludeJs(HttpClient.userCssDisabled)).append(';')
                .append(escapedOf(escapedRuntimeRef, runtime!!)).append(';')
                .append(escapedOf(escapedMobileRuntimeRef, mobileRuntime!!)).append(";</script>")
        }
        sb.append(text, headIdx, text.length)
        return InjectionResult(sb.toString(), runtimeReady)
    }

    /**
     * Identity-keyed cache for escapeScriptTagContent(). The runtime strings
     * held by HttpClient are ~1 MB and immutable until replaced, so keying on
     * reference identity avoids re-scanning/re-copying them on every cached
     * main-frame serve. A replaced runtime (clientMod switch, update) gets a
     * new String instance, which misses the cache exactly once.
     */
    private class EscapedJs(val raw: String, val escaped: String)

    // One cache slot per runtime string, both served by [escapedOf].
    // AtomicReference.get/set have volatile semantics, which matters because
    // injectFirewallAndCss runs on concurrent WebView threads.
    private val escapedRuntimeRef = AtomicReference<EscapedJs?>()
    private val escapedMobileRuntimeRef = AtomicReference<EscapedJs?>()

    private fun escapedOf(holder: AtomicReference<EscapedJs?>, raw: String): String {
        val cached = holder.get()
        if (cached != null && cached.raw === raw) return cached.escaped
        val e = EscapedJs(raw, escapeScriptTagContent(raw))
        holder.set(e)
        return e.escaped
    }

    /**
     * Finds the real closing `</head>`, skipping matches inside raw-text
     * elements (script, style, textarea, title) or HTML comments, where the
     * literal text may legally appear. Returns -1 when none is found, in
     * which case callers degrade to bridge injection in onPageStarted.
     */
    private fun findHeadCloseIndex(text: String): Int {
        val rawTextOpeners = arrayOf("<script", "<style", "<textarea", "<title")
        val rawTextClosers = arrayOf("</script", "</style", "</textarea", "</title")
        var from = 0
        while (true) {
            val headIdx = text.indexOf("</head>", from, ignoreCase = true)
            if (headIdx < 0) return -1
            var hidden = false
            for (i in rawTextOpeners.indices) {
                val open = text.lastIndexOf(rawTextOpeners[i], headIdx, ignoreCase = true)
                if (open < 0) continue
                val close = text.lastIndexOf(rawTextClosers[i], headIdx, ignoreCase = true)
                if (close < open) { hidden = true; break }
            }
            val commentOpen = text.lastIndexOf("<!--", headIdx)
            val commentClose = text.lastIndexOf("-->", headIdx)
            if (commentOpen > commentClose) hidden = true
            if (!hidden) return headIdx
            from = headIdx + 1
        }
    }

    /**
     * Escapes [s] for safe inlining inside an HTML `<script>` block by replacing
     * `</script` (case-insensitive) with `<\/script` and `<!--` with `<\!--`. The
     * parser no longer recognizes either sequence, while `\/` and `\!` evaluate
     * to `/` and `!` at runtime, so JS string and template-literal contents are
     * unchanged. (`\!` is a SyntaxError inside `u`-flagged regexes; the vendored
     * snapshot only has `<!--` inside a template literal.)
     *
     * `<!--` cannot end the element, but it puts the tokenizer into the
     * script-data escaped state, where a later `<script` enters the
     * double-escaped state. There the element's own `</script>` closer no longer
     * ends the tag. The rest of the document is swallowed as script text and the
     * embedded payload never runs. Bundles legitimately carry both sequences, so
     * both are escaped here rather than assumed absent. With `<!--` neutralized
     * the tokenizer never leaves plain script-data state, so bare `<script` is
     * inert and needs no rewriting.
     *
     * Scans [s] itself with ignoreCase matching, never a lowercased copy:
     * lowercasing can change string length (İ U+0130 becomes i + U+0307), so
     * offsets taken from the copy slice [s] at wrong positions, corrupting
     * the tail or leaving `</script` unescaped. Returns [s] itself when neither
     * token occurs, so clean payloads skip the copy.
     *
     * Internal so the contract tests in EscapeScriptTagContentTest can pin both
     * escapes.
     */
    internal fun escapeScriptTagContent(s: String): String {
        var sb: StringBuilder? = null
        var i = 0
        while (true) {
            val nextScript = s.indexOf("</script", i, ignoreCase = true)
            val nextComment = s.indexOf("<!--", i)
            val next = when {
                nextScript < 0 -> nextComment
                nextComment < 0 -> nextScript
                else -> minOf(nextScript, nextComment)
            }
            if (next < 0) break
            if (sb == null) sb = StringBuilder(s.length + 16)
            if (next == nextScript) {
                sb.append(s, i, next).append("<\\/script")
                i = next + "</script".length
            } else {
                sb.append(s, i, next).append("<\\!--")
                i = next + "<!--".length
            }
        }
        return sb?.append(s, i, s.length)?.toString() ?: s
    }
}
