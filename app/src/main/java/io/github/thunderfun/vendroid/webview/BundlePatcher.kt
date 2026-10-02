package io.github.thunderfun.vendroid.webview

import io.github.thunderfun.vendroid.utils.VDELog

internal object BundlePatcher {
    // Vencord bundle patches applied at download time. The Slate/command-browser
    // fix and the ported plugin features (sheets patches, CSS, gestures,
    // support warnings) are applied at runtime in vencord_mobile.js. Each
    // patch carries a marker string present in its own replacement, so
    // applyPatches() can skip it when already patched; the pattern anchors
    // on upstream text, so re-applying cannot grow the replacement.
    internal data class BundlePatch(
        val pattern: Regex,
        val replacement: String,
        val marker: String,
        // When non-null, invoked per match; the output is inserted
        // literally, so '$' and '\' in it are never interpreted as group
        // references. Used by patches that keep the matched middle
        // (group 1) and rewrite its tail. `replacement` goes unused but
        // should carry the marker so bundlePatchSetKey reflects the patch
        // identity.
        val transform: ((MatchResult) -> String)? = null
    )

    /**
     * The bundle's `//# sourceURL=file:///VencordWeb` pragma makes Chromium
     * treat bundle code as cross-origin on the evaluateJavascript path, so
     * uncaught errors are masked to "Script error." with lineno 0. Relabeling
     * to a same-origin URL keeps attribution while disabling the masking.
     * Comment-only tokens: no semantic effect. The dynamic per-module pragmas
     * are relabeled for the same reason.
     *
     * The historical VENDROID_DISABLED and vde-prune-* patches were
     * removed. The chat-input/Slate fix is covered by the runtime's
     * isAndroidWeb override, and the vendored vendroidEnhancements plugin
     * that added the pruned settings no longer ships in the bundle. The
     * three sourceURL anchors are verified present exactly once in both
     * vanilla release bundles (vencord_snapshot.js / equicord_snapshot.js).
     *
     * The vde-off-* patches flip WebPWA, WebScreenShare and
     * WebScreenShareFixes off by default; all three are desktop-oriented
     * and are dead weight or dead code inside the Android WebView. They
     * pair with the runtime's one-time default-off sweep in
     * vencord_mobile.js (00-boot.js), which migrates settings persisted by
     * older builds and covers a missed anchor. The vde-user-plugins-gate
     * patch is the other behavior-changing one: it makes startAllPlugins
     * honor the recovery plugins gate.
     */
    private const val SAME_ORIGIN_SOURCE_URL = "https://discord.com/vencord-web.js"

    private const val USER_PLUGINS_GATE_MARKER = "/*vde-user-plugins-gate*/"

    /**
     * Makes startAllPlugins skip every plugin that is not required and not a
     * dependency while the recovery session flag is raised. The condition goes
     * into the enablement check, so staged starts (Init, WebpackReady,
     * DOMContentLoaded) keep their normal ordering for required plugins and
     * skip user plugins.
     *
     * Anchored on the "Starting plugins (stage ...)" log literal; all minified
     * identifiers (loop var, registry, isPluginEnabled, predicate argument)
     * are captured, so a rename in a future bundle keeps matching. A miss
     * logs "Patch matched nothing" and the 00-boot belt is the fallback.
     */
    private val userPluginsGatePatch = BundlePatch(
        Regex(
            "(Starting plugins \\(stage \\$\\{[A-Za-z_$][\\w$]*\\}\\)`\\);)" +
                "(for\\(let ([A-Za-z_$][\\w$]*) in ([A-Za-z_$][\\w$]*)\\)if\\()" +
                "([A-Za-z_$][\\w$]*)\\(\\3\\)(\\)\\{)"
        ),
        replacement = USER_PLUGINS_GATE_MARKER,
        marker = USER_PLUGINS_GATE_MARKER,
        transform = { m ->
            m.groupValues[1] + m.groupValues[2] +
                m.groupValues[5] + "(" + m.groupValues[3] + ")" +
                "&&(!window.VENCORD_USER_PLUGINS_DISABLED||" +
                m.groupValues[4] + "[" + m.groupValues[3] + "].required||" +
                m.groupValues[4] + "[" + m.groupValues[3] + "].isDependency)" +
                m.groupValues[6] + USER_PLUGINS_GATE_MARKER
        }
    )

    internal val vencordRuntimePatches: List<BundlePatch> = listOf(
        BundlePatch(
            Regex.escape("//# sourceURL=file:///VencordWeb").toRegex(),
            "//# sourceURL=$SAME_ORIGIN_SOURCE_URL",
            SAME_ORIGIN_SOURCE_URL
        ),
        BundlePatch(
            Regex.escape("//# sourceURL=file:///ExtractedWebpackModule").toRegex(),
            "//# sourceURL=https://discord.com/vencord-ext-module",
            "https://discord.com/vencord-ext-module"
        ),
        BundlePatch(
            Regex.escape("//# sourceURL=file:///WebpackModule").toRegex(),
            "//# sourceURL=https://discord.com/vencord-module",
            "https://discord.com/vencord-module"
        ),
        defaultOffPatch("WebPWA", "/*vde-off-pwa*/"),
        defaultOffPatch("WebScreenShare", "/*vde-off-wss*/"),
        defaultOffPatch("WebScreenShareFixes", "/*vde-off-wssf*/"),
        userPluginsGatePatch
    )

    /**
     * Flips `enabledByDefault:!0` to `!1` on one upstream plugin definition.
     *
     * The default has to be fixed in the bundle, not just in runtime
     * state, because the settings store materializes `plugins.X.enabled`
     * from `required||enabledByDefault` on first access and the runtime's
     * recoverPlugins() force-enable pass keys off the same field.
     *
     * Anchor distances, measured in both vendored snapshots: name and
     * enabledByDefault sit 215-225 chars apart on one minified line. The
     * 400-char lazy window leaves drift headroom without reaching another
     * plugin's flag (nothing between a name and a foreign flag fits in
     * it), and the quote-terminated name anchor cannot match inside a
     * longer plugin name ("WebScreenShare" vs "WebScreenShareFixes"). A
     * miss logs "Patch matched nothing" and is pinned by the snapshot
     * fixture tests.
     */
    private fun defaultOffPatch(plugin: String, marker: String) = BundlePatch(
        Regex("""(name:"$plugin".{0,400}?enabledByDefault:)!0"""),
        marker,
        marker,
        { m -> m.groupValues[1] + "!1" + marker }
    )

    /**
     * Identity of the current patch list, persisted alongside
     * [PREF_BUNDLE_PATCHED]. Derived from the patch definitions so editing
     * [vencordRuntimePatches] without a versionCode bump still invalidates
     * the flag and re-patches the on-disk bundle.
     */
    internal val bundlePatchSetKey: String =
        vencordRuntimePatches.joinToString("|") { it.pattern.pattern + "->" + it.replacement }
            .hashCode().toString()

    internal fun applyPatches(content: String): String {
        if (vencordRuntimePatches.isEmpty()) return content
        VDELog.d("HTTP", "Applying ${vencordRuntimePatches.size} patches")
        // Few patches means sequential replace is simpler than mapping
        // combined-regex matches back to individual patches. Each replace
        // copies ~1MB, acceptable for 2-3 patches at download time.
        var result = content
        for (patch in vencordRuntimePatches) {
            VDELog.d("HTTP", "Patch: ${patch.pattern.pattern}")
            // Skip already-patched content so a re-apply (e.g. the persisted
            // flag was cleared) never double-suffixes the replacement.
            if (result.contains(patch.marker)) continue
            var matchCount = 0
            // Lambda replacement: the returned string is inserted literally,
            // so '$' or '\' in a replacement (common in minified JS) is never
            // interpreted as a group reference. BundlePatch.transform relies
            // on the same guarantee.
            result = patch.pattern.replace(result) { m ->
                matchCount++
                patch.transform?.invoke(m) ?: patch.replacement
            }
            if (matchCount == 0) {
                VDELog.w("HTTP", "Patch matched nothing; upstream bundle may have changed: ${patch.marker}")
            }
        }
        return result
    }
}
