package com.nin0dev.vendroid.webview

/**
 * Builds the enforced CSP that replaces Discord's main-frame
 * `Content-Security-Policy` header.
 *
 * Kept in a plain Kotlin object instead of [VWebviewClient]'s companion so
 * [VencordCspTest] can run on the JVM. Loading `VWebviewClient` would run its
 * companion initializer, which constructs `android.util.LruCache` and throws
 * under unit tests.
 */
object VencordCsp {

    /**
     * Strict CSP for Discord main-frame responses. `connect-src` is tightened
     * to block token and message exfiltration to non-allowlisted hosts; the
     * forge hosts in `style-src` keep user themes working.
     *
     * Notes:
     *  - `script-src` needs 'unsafe-inline' + 'unsafe-eval' (webpack and the
     *    injected firewall <script>), so this guards exfiltration, not XSS.
     *  - `style-src` needs 'unsafe-inline' + data: for injectStyle().
     */
    fun build(): String =
        "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
            "style-src 'self' 'unsafe-inline' data: " +
            "https://*.githack.com https://cbcdn.githack.com " +
            "https://raw.githubusercontent.com https://cdn.jsdelivr.net " +
            "https://*.github.io https://*.codeberg.page; " +
            "connect-src 'self' https://*.discord.com https://*.discordapp.com " +
            "https://*.discord.media https://*.discord.media:* " +
            "https://*.discordapp.net " +
            // Wildcards, not bare hosts: Discord uses regional gateways
            // (wss://gateway-us-east1-b.discord.gg) and voice gateways on
            // *.discord.media, which a bare wss://gateway.discord.gg misses.
            // The `:*` port wildcard matters: a host-source with no port only
            // matches the scheme default (443 for wss), but voice RTC runs on
            // 2053/2096/etc, so the join handshake would fail as a connect-src
            // violation. The explicit ports are a fallback for parsers that
            // ignore `*`.
            "wss://*.discord.gg wss://*.discord.media wss://*.discord.media:* " +
            "wss://*.discord.media:443 wss://*.discord.media:2053 " +
            "wss://*.discord.media:2096 wss://*.discord.media:8443 " +
            // Attachment uploads PUT directly to signed URLs on these
            // Discord-owned buckets. Pinned rather than *.storage.googleapis.com
            // so attacker-created buckets stay blocked.
            "https://discord-attachments-uploads-prd.storage.googleapis.com " +
            "https://discord-attachments-upstream-prd.storage.googleapis.com " +
            // Upstream bundle/CSS hosts (GitHub releases). The bundle and the
            // prefetched CSS are inline-injected, so script-src/style-src need
            // no entries; these only carry the in-page CSS re-fetch fallback,
            // which follows the same 302 hop chain the native fetch
            // re-validates (github.com → release-assets.githubusercontent.com).
            "https://github.com https://release-assets.githubusercontent.com " +
            "https://badges.vencord.dev; " +
            "img-src * data: blob:; " +
            "media-src * blob:; " +
            "font-src * data:; " +
            "worker-src 'self' blob:; " +
            "child-src * blob:; " +
            "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
}
