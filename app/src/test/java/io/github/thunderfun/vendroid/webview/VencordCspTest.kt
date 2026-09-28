package io.github.thunderfun.vendroid.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Regression tests for VencordCsp.build. A CSP host-source with no explicit
// port only matches the scheme default (443 for wss), so the voice RTC
// gateway on 2053/2096 was rejected as a connect-src violation even with a
// wildcard entry for .discord.media. Also covers the matching URL.host vs
// URL.hostname firewall check.

class VencordCspTest {

    private val csp = VencordCsp.build()

    private fun connectSrc(): String =
        csp.split("; ").first { it.startsWith("connect-src") }

    @Test fun connectSrc_allowsDiscordMedia() {
        val src = connectSrc()
        assertTrue("missing wss discord.media: $src", src.contains("wss://*.discord.media"))
    }

    @Test fun connectSrc_allowsNonDefaultVoicePorts() {
        val src = connectSrc()
        assertTrue("missing port wildcard: $src", src.contains("wss://*.discord.media:*"))
        for (port in listOf(443, 2053, 2096, 8443)) {
            assertTrue("missing wss://*.discord.media:$port: $src", src.contains("wss://*.discord.media:$port"))
        }
    }

    @Test fun connectSrc_stillPinsOtherDiscordHosts() {
        val src = connectSrc()
        assertTrue(src.contains("https://*.discord.com"))
        assertTrue(src.contains("wss://*.discord.gg"))
    }

    @Test fun connectSrc_allowsUpstreamBundleHosts() {
        // The in-page CSS re-fetch fallback hits the GitHub release hosts
        // (the 302 hop chain ends on release-assets.githubusercontent.com).
        val src = connectSrc()
        assertTrue("missing github.com: $src", src.contains("https://github.com"))
        assertTrue(
            "missing release-assets host: $src",
            src.contains("https://release-assets.githubusercontent.com")
        )
    }

    @Test fun connectSrc_dropsRetiredOperatorHosts() {
        val src = connectSrc()
        assertFalse("vde-builds.nin0.dev must be gone: $src", src.contains("vde-builds.nin0.dev"))
        assertFalse("vendroid.nin0.dev must be gone: $src", src.contains("vendroid.nin0.dev"))
    }

    @Test fun connectSrc_keepsVencordBadges() {
        assertTrue(connectSrc().contains("https://badges.vencord.dev"))
    }

    @Test fun scriptAndStyleSrc_needNoBundleHosts() {
        // Bundle and CSS are inline-injected, so the host swap lives
        // entirely in connect-src.
        val script = csp.split("; ").first { it.startsWith("script-src") }
        val style = csp.split("; ").first { it.startsWith("style-src") }
        assertFalse(script.contains("github"))
        assertFalse(style.contains("github.com"))
    }

    @Test fun objectSrcLockedDown() {
        assertTrue(csp.contains("object-src 'none'"))
        assertTrue(csp.contains("base-uri 'none'"))
    }
}
