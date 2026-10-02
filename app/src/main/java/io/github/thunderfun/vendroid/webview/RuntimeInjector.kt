package io.github.thunderfun.vendroid.webview

import android.webkit.WebView
import io.github.thunderfun.vendroid.utils.JsPatches

/**
 * Single owner of the order-sensitive runtime injection eval chain, shared by
 * VWebviewClient.onPageStarted's fallback and MainActivity.injectVencordAttempt.
 * The order is load-bearing and must never drift between callers: the
 * capability-token bootstrap is evaluated first so the token is in scope
 * (closure-captured, not a window global) before the runtimes call the bridge.
 * The bootstrap is idempotent if the document already ran it; the env shim
 * (VENCORD_PRELUDE_JS) precedes the main runtime, and the mobile runtime is
 * evaluated last.
 *
 * The evals run separately to avoid building a ~1 MB string on the UI thread.
 * The user-theme gate flag precedes the prelude in the same eval (see
 * [JsPatches.vencordPreludeJs]), so a recovery session's prelude installs the
 * VencordNative setter trap before the bundle publishes its bridge object.
 */
internal object RuntimeInjector {
    /**
     * Runs the three injection evals on [view] in the pinned order:
     * bootstrap → prelude + main runtime → mobile runtime. Returns false when
     * the WebView was destroyed between the caller's liveness check and these
     * calls (IllegalStateException), true otherwise. Boot-verify scheduling,
     * logging, and missedInjection bookkeeping stay with the callers.
     */
    fun injectViaBridge(view: WebView, runtime: String, mobileRuntime: String): Boolean {
        try {
            view.evaluateJavascript(VencordNative.bridgeBootstrapJs() + ";", null)
            // Gate flag + env shim must precede the bundle
            // (see JsPatches.vencordPreludeJs).
            view.evaluateJavascript(
                JsPatches.vencordPreludeJs(HttpClient.userCssDisabled, HttpClient.userPluginsDisabled) + ";" + runtime + ";",
                null
            )
            view.evaluateJavascript(mobileRuntime + ";", null)
        } catch (_: IllegalStateException) {
            // WebView was destroyed between the liveness check and these calls.
            return false
        }
        return true
    }
}
