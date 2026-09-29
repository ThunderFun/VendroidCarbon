package io.github.thunderfun.vendroid.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the bridge settings-key allowlist ([VencordNative.isBridgeKeyAllowed])
 * against the keys the eq.js settings tree renders. Unprefixed tree keys used
 * to fail this gate: reads returned the default, writes were dropped, and
 * toggles flipped in the UI without ever persisting.
 *
 * Dispositions:
 *  - desktopMode: allowed and consumed natively at startup; its Boolean type
 *    mirror is pinned in BridgeSettingTypeContractTest.
 *  - checkVDEUpdates, clientMod: allowed. checkVDEUpdates is read by the
 *    updater tab; clientMod is value-validated in setString.
 *  - discordBranch: allowed. Consumed natively at startup (DiscordBranch)
 *    and value-validated in setString.
 *  - checkAnnouncements: allowed but currently unread. Kept for upstream,
 *    and type-guarded meanwhile.
 *  - vencordLocation: rejected. It selects the code the app downloads and
 *    executes, so page JS must never write it.
 *  - splashScreen, allowRemoteDebugging: blocked. Nothing native consumes
 *    them, so HttpClient prunes them from the tree at download time. The
 *    gate keeps blocking them so a re-addition cannot become a dead toggle.
 *  - orbColor, splashBgColor: allowed. Splash-only colors written by their
 *    dedicated bridge methods (setOrbColor/setSplashBgColor) and
 *    value-validated on the setString route; read only at startup.
 *
 * When the eq.js tree gains a key, decide and record its disposition here:
 * allowed, or deliberately blocked.
 */
class BridgeKeyAllowlistTest {

    @Test
    fun `natively consumed app settings are allowed`() {
        for (key in setOf("desktopMode", "checkVDEUpdates", "checkAnnouncements", "clientMod", "discordBranch"))
            assertTrue("bridge must allow $key", VencordNative.isBridgeKeyAllowed(key))
    }

    @Test
    fun `vencordLocation stays rejected as a code-injection vector`() {
        assertFalse(VencordNative.isBridgeKeyAllowed("vencordLocation"))
    }

    @Test
    fun `recovery-only flags stay unreachable from page JS`() {
        // The recovery screen is the only writer; page JS must be able to
        // neither read nor flip safe mode or the themes recovery flag.
        assertFalse(VencordNative.isBridgeKeyAllowed("safeMode"))
        assertFalse(VencordNative.isBridgeKeyAllowed("disableThemes"))
    }

    @Test
    fun `pruned dead keys stay blocked`() {
        for (key in setOf("splashScreen", "allowRemoteDebugging"))
            assertFalse("bridge must keep blocking $key", VencordNative.isBridgeKeyAllowed(key))
    }

    @Test
    fun `prefixed plugin and cache keys stay allowed`() {
        for (key in setOf(
            "Vencord-TestPlugin",
            "Vencord_TestPlugin",
            "vendroid_blockTypingIndicator",
            "css_cache_example"
        ))
            assertTrue("bridge must allow prefixed key $key", VencordNative.isBridgeKeyAllowed(key))
    }
}
