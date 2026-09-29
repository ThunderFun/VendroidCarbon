package io.github.thunderfun.vendroid.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the download-time patch set against the vanilla upstream bundles.
 *
 * The patch list is the three sourceURL relabels plus the three vde-off
 * default flips (WebPWA, WebScreenShare, WebScreenShareFixes). The
 * VENDROID_DISABLED and vde-prune-* patches were removed with the
 * vendored plugin bundle and must stay retired. The regression this suite catches is a pattern
 * drifting out of upstream text; applyPatches would log "Patch matched
 * nothing". For the relabels, uncaught errors would collapse back to
 * "Script error." with lineno 0. For the default flips, the three desktop
 * plugins would come back enabled by default (the runtime sweep still
 * disables them, so this is defense in depth, not the only guard).
 *
 * The fixtures are the vanilla release bundles vendored in the repo root
 * (vencord_snapshot.js / equicord_snapshot.js).
 * Each test first asserts its precondition on the raw snapshot so an
 * upstream re-bundle that drops an anchor fails with a precise message
 * instead of a confusing post-patch assertion.
 */
class HttpClientBundlePatchTest {

    private fun snapshot(name: String): File {
        // Callers check exists() and skip when a snapshot is not vendored in
        // this checkout. The path resolves from the app module's test working
        // directory.
        return File("../$name")
    }

    // --- mechanics ---

    @Test
    fun `relabels rewrite all three sourceURL pragmas`() {
        val content = "// Vencord deadbeef\n" +
            "//# sourceURL=file:///VencordWeb\n" +
            "//# sourceURL=file:///ExtractedWebpackModule\n" +
            "//# sourceURL=file:///WebpackModule\n"
        val patched = HttpClient.applyPatches(content)
        assertTrue(patched.contains("//# sourceURL=https://discord.com/vencord-web.js"))
        assertTrue(patched.contains("//# sourceURL=https://discord.com/vencord-ext-module"))
        assertTrue(patched.contains("//# sourceURL=https://discord.com/vencord-module"))
        assertFalse(patched.contains("file:///"))
    }

    @Test
    fun `patching is idempotent`() {
        val content = "//# sourceURL=file:///VencordWeb" +
            "//# sourceURL=file:///ExtractedWebpackModule" +
            "//# sourceURL=file:///WebpackModule"
        val once = HttpClient.applyPatches(content)
        assertEquals(once, HttpClient.applyPatches(once))
    }

    @Test
    fun `the retired patches stay retired`() {
        // Guard against reintroduction: the chat-input disable and the three
        // settings prunes must not come back. The vanilla bundle does not
        // carry the vendroidEnhancements plugin whose behavior they shimmed,
        // and the runtime now owns those features.
        val content = "\"chat input type must be set\"" +
            "splashScreen:{label:\"Splash screen\"}" +
            "discordBranch:{type:\"select\"}" +
            "allowRemoteDebugging:{label:\"Allow remote debugging\"}"
        val patched = HttpClient.applyPatches(content)
        assertEquals(content, patched)
        assertFalse(patched.contains("__VENDROID_DISABLED"))
        assertFalse(patched.contains("vde-prune-"))
    }

    // --- vanilla snapshot fixtures ---

    @Test
    fun `vencord snapshot - relabels match and apply`() {
        val snapshot = snapshot("vencord_snapshot.js")
        if (!snapshot.exists()) return
        val raw = snapshot.readText()
        // Precondition: all three anchors exist exactly once upstream.
        assertEquals(1, countOccurrences(raw, "//# sourceURL=file:///VencordWeb"))
        assertEquals(1, countOccurrences(raw, "//# sourceURL=file:///ExtractedWebpackModule"))
        assertEquals(1, countOccurrences(raw, "//# sourceURL=file:///WebpackModule"))
        // Precondition: the removed patches' anchors are absent upstream, so
        // the reduced patch set loses nothing on vanilla bundles.
        assertFalse(raw.contains("\"chat input type must be set\""))
        assertFalse(raw.contains("splashScreen:{label:"))

        val patched = HttpClient.applyPatches(raw)
        assertFalse(patched.contains("file:///VencordWeb"))
        assertFalse(patched.contains("file:///ExtractedWebpackModule"))
        assertFalse(patched.contains("file:///WebpackModule"))
        assertTrue(patched.contains("https://discord.com/vencord-web.js"))
        assertTrue(patched.contains("https://discord.com/vencord-ext-module"))
        assertTrue(patched.contains("https://discord.com/vencord-module"))
        // Relabels are comment-only, so their only length change is the
        // URL delta. Each vde-off flip swaps !0 for !1 (length-neutral)
        // and adds its marker; any other length change means the patch
        // mangled code.
        val relabelDelta =
            ("https://discord.com/vencord-web.js".length - "file:///VencordWeb".length) +
            ("https://discord.com/vencord-ext-module".length - "file:///ExtractedWebpackModule".length) +
            ("https://discord.com/vencord-module".length - "file:///WebpackModule".length)
        assertEquals(
            raw.length + relabelDelta +
                "/*vde-off-pwa*/".length + "/*vde-off-wss*/".length + "/*vde-off-wssf*/".length,
            patched.length
        )
    }

    @Test
    fun `equicord snapshot - relabels match and apply`() {
        val snapshot = snapshot("equicord_snapshot.js")
        if (!snapshot.exists()) return
        val raw = snapshot.readText()
        assertTrue("Equicord snapshot must carry its build tag header", raw.startsWith("// Equicord "))
        assertEquals(1, countOccurrences(raw, "//# sourceURL=file:///VencordWeb"))
        assertEquals(1, countOccurrences(raw, "//# sourceURL=file:///ExtractedWebpackModule"))
        assertEquals(1, countOccurrences(raw, "//# sourceURL=file:///WebpackModule"))

        val patched = HttpClient.applyPatches(raw)
        assertFalse(patched.contains("file:///VencordWeb"))
        assertFalse(patched.contains("file:///ExtractedWebpackModule"))
        assertFalse(patched.contains("file:///WebpackModule"))
        assertTrue(patched.contains("https://discord.com/vencord-web.js"))
    }

    @Test
    fun `vanilla snapshots carry no vendroid mobile glue`() {
        // The ported features live in vencord_mobile.js; if upstream ever
        // grows VencordMobile* glue (or a snapshot is accidentally replaced
        // by an operator-built bundle), this flags the drift.
        for (name in listOf("vencord_snapshot.js", "equicord_snapshot.js")) {
            val snapshot = snapshot(name)
            if (!snapshot.exists()) continue
            assertFalse("$name must not contain VencordMobile glue", snapshot.readText().contains("VencordMobile"))
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }
}
