package com.nin0dev.vendroid.webview

import com.nin0dev.vendroid.utils.VDELog

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
        val marker: String
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
     */
    private const val SAME_ORIGIN_SOURCE_URL = "https://discord.com/vencord-web.js"
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
        )
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
            // interpreted as a group reference.
            result = patch.pattern.replace(result) {
                matchCount++
                patch.replacement
            }
            if (matchCount == 0) {
                VDELog.w("HTTP", "Patch matched nothing; upstream bundle may have changed: ${patch.marker}")
            }
        }
        return result
    }
}
