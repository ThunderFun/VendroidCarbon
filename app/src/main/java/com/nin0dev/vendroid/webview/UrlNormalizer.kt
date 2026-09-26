package com.nin0dev.vendroid.webview

import android.net.Uri
import java.net.IDN

/**
 * Normalizes a [Uri] received from WebView into a [Normalized] record that is
 * safe to display, launch, copy, share, and log.
 *
 * 1. Non-ASCII readability: the display form decodes percent-encoding in the
 *    path/query/fragment so the user sees real characters, while the
 *    launch/clipboard/share forms stay fully encoded for browser compatibility.
 *
 * 2. IDN / homograph spoofing: non-ASCII hosts are rendered in Punycode in
 *    every output so a lookalike domain can never masquerade as the Latin
 *    domain it visually resembles. Allowlist checks use the same ASCII host.
 *
 * 3. Phishing / credential-leak prevention: userinfo is stripped from all
 *    outputs; invisible and bidi-override characters are removed; mixed-script
 *    segments are kept encoded in the display form to defeat path homographs.
 *
 * Pure-string helpers avoid the Android framework so they can be unit-tested
 * on the JVM. Only [normalize] touches [Uri].
 */object UrlNormalizer {

    /** Cap on input length to bound per-char processing cost. */
    private const val MAX_INPUT = 8192

    data class Normalized(
        /** Human-readable, decoded, spoof-resistant string for the dialog title. */
        val displayString: String,
        /** Fully encoded, userinfo-free [Uri] safe for ACTION_VIEW. */
        val launchUri: Uri,
        /** Encoded string for clipboard / share (functional over pretty). */
        val clipboardString: String,
        /** Punycode-normalized host for allowlist comparisons, or null. */
        val asciiHost: String?,
        /** Lowercased scheme, or null. */
        val scheme: String?,
        /** True if the input was too broken to normalize cleanly. */
        val malformed: Boolean
    )

    /**
     * Normalize an Android [Uri]. Never throws; on failure returns a
     * [Normalized] with [Normalized.malformed] set so the caller can surface
     * an error toast.
     */    fun normalize(uri: Uri): Normalized {
        return try {
            val raw = uri.toString()
            if (raw.isEmpty()) return malformed(uri)

            val scheme = uri.scheme?.lowercase()
            val rawHost = uri.host
            val asciiHost = toAsciiHost(rawHost)
            val port = uri.takeIf { it.port > 0 }?.port ?: -1
            // Opaque (hostless) URIs (about:, mailto:, data:) carry their
            // content in schemeSpecificPart; uri.path returns null for them.
            // Use Uri's own opacity check: host == null alone misclassifies
            // hierarchical URIs with an empty authority (e.g.
            // file:///path?x=1#f) as opaque, which drops their query and
            // fragment.
            val isOpaque = uri.isOpaque
            // percentEncode's %XX pass-through assumes already-encoded
            // text, and the decoded accessors are lossy (invalid UTF-8
            // escapes collapse to U+FFFD; %3F/%23 decode into chars that
            // get re-emitted raw). Build from the encoded ones instead;
            // decodeForDisplay does the single decode for display.
            val rawPath = if (isOpaque) (uri.encodedSchemeSpecificPart ?: uri.encodedPath.orEmpty()) else uri.encodedPath.orEmpty()
            val rawQuery = if (isOpaque) null else uri.encodedQuery
            val rawFragment = if (isOpaque) null else uri.encodedFragment

            val launchStr = buildEncodedUrl(
                scheme, asciiHost, rawHost, port,
                rawPath, rawQuery, rawFragment,
                cap = MAX_INPUT
            ) ?: return malformed(uri)
            val launchUri = Uri.parse(launchStr)

            val display = buildDisplayString(
                scheme, asciiHost, rawHost, port, rawPath, rawQuery, rawFragment, cap = MAX_INPUT
            )

            Normalized(
                displayString = display,
                launchUri = launchUri,
                clipboardString = launchStr,
                asciiHost = asciiHost,
                scheme = scheme,
                malformed = false
            )
        } catch (_: Throwable) {
            malformed(uri)
        }
    }

    /**
     * Redacted form for log lines: userinfo stripped, common token-like query
     * params masked.
     */    fun redactForLog(rawUrl: String): String {
        if (rawUrl.isEmpty()) return ""
        val noUserInfo = stripUserinfo(rawUrl)
        return maskTokenParams(noUserInfo)
    }

    // ------------------------------------------------------------------
    //  Pure helpers (JVM-only, unit-tested)
    // ------------------------------------------------------------------

    /** Converts an IDN host to ASCII (Punycode). Returns null for null/empty
     *  input; returns null if [IDN.toASCII] rejects the host, so callers treat
     *  an invalid IDN as malformed instead of failing open to the raw Unicode
     *  host (which would defeat the Punycode homograph defense). */    fun toAsciiHost(host: String?): String? {
        if (host.isNullOrEmpty()) return null
        if (host.all { it.code <= 0x7F }) return host
        return try {
            IDN.toASCII(host).ifEmpty { null }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Removes the `user:pass@` userinfo segment from a raw URL string.
     *  Operates on the raw form so it works before Uri parsing.
     */
    fun stripUserinfo(rawUrl: String): String {
        val schemeEnd = rawUrl.indexOf("://")
        if (schemeEnd < 0) return rawUrl
        val afterScheme = schemeEnd + 3
        // The userinfo sits between "://" and the next path/query/fragment
        // delimiter. Find the last '@' in that span (userinfo may itself
        // contain '@' when encoded).
        val hostStart = rawUrl.indexOfAny(charArrayOf('/', '?', '#'), afterScheme)
            .let { if (it < 0) rawUrl.length else it }
        val atIdx = rawUrl.lastIndexOf('@', hostStart)
        if (atIdx <= afterScheme) return rawUrl
        return rawUrl.substring(0, afterScheme) + rawUrl.substring(atIdx + 1)
    }

    /**
     * Percent-decodes `%XX` sequences into UTF-8 characters. Malformed
     * sequences are left as the literal `%` so the string never corrupts.
     * Incomplete UTF-8 multibyte sequences fall back to the original `%XX`
     * text rather than emitting a replacement character. `+` is not treated
     * as space.
     */    fun percentDecode(s: String): String {
        if (s.isEmpty()) return s
        val out = StringBuilder(s.length)
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                bytes.add((hexVal(s[i + 1]) shl 4 or hexVal(s[i + 2])).toByte())
                i += 3
            } else {
                flushBytes(out, bytes); bytes.clear()
                out.append(c)
                i++
            }
        }
        flushBytes(out, bytes)
        return out.toString()
    }

    /**
     * Encodes raw non-ASCII characters and unsafe ASCII into `%XX` UTF-8.
     * Already-encoded `%XX` sequences are passed through untouched to avoid
     * double-encoding. [safe] lists ASCII chars to keep verbatim.
     */    fun percentEncode(s: String, safe: String = DEFAULT_SAFE): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                sb.append('%').append(s[i + 1]).append(s[i + 2])
                i += 3
                continue
            }
            if (c.code <= 0x7F) {
                if (safe.indexOf(c) >= 0) sb.append(c) else {
                    sb.append('%')
                    sb.append(HEX[(c.code shr 4) and 0xF])
                    sb.append(HEX[c.code and 0xF])
                }
            } else {
                for (b in c.toString().toByteArray(Charsets.UTF_8)) {
                    val v = b.toInt() and 0xFF
                    sb.append('%')
                    sb.append(HEX[(v shr 4) and 0xF])
                    sb.append(HEX[v and 0xF])
                }
            }
            i++
        }
        return sb.toString()
    }

    /** Strips invisible, bidi-override, and control characters that let a URL
     *  render differently from its logical content. */    fun stripInvisible(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            val cp = c.code
            when {
                cp <= 0x1F -> {}                 // C0 controls
                cp == 0x7F -> {}                 // DEL
                cp in 0x202A..0x202E -> {}       // bidi override
                cp in 0x2066..0x2069 -> {}       // bidi isolate
                cp in 0x200B..0x200D -> {}       // zero-width
                cp == 0xFEFF -> {}               // BOM
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Returns true if [s] mixes Latin script with Cyrillic or Greek, the
     * primary sources of homograph attacks. Pure-Cyrillic, pure-Greek, and
     * Latin+CJK are not flagged.
     */
    fun containsMixedScript(s: String): Boolean {
        if (s.isEmpty()) return false
        var hasLatin = false
        var hasCyrillic = false
        var hasGreek = false
        for (c in s) {
            // ASCII letters are Latin script and must be counted so that a
            // Latin-base word with a single Cyrillic confusable is detected.
            if (c in 'A'..'Z' || c in 'a'..'z') { hasLatin = true; continue }
            if (c.code <= 0x7F) continue
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.LATIN -> hasLatin = true
                Character.UnicodeScript.CYRILLIC -> hasCyrillic = true
                Character.UnicodeScript.GREEK -> hasGreek = true
                else -> {}
            }
        }
        return (hasLatin && hasCyrillic) || (hasLatin && hasGreek)
    }

    // ------------------------------------------------------------------
    //  Internal builders
    // ------------------------------------------------------------------

    private const val DEFAULT_SAFE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:/@?#%"
    private val HEX = "0123456789ABCDEF".toCharArray()

    /** Truncates without splitting a %XX escape or a UTF-16 surrogate pair. */
    private fun safeTruncate(s: String, cap: Int): String {
        if (s.length <= cap) return s
        var end = cap
        // Don't cut through a percent-encoded triplet.
        if (end >= 2 && s[end - 2] == '%') end -= 2
        else if (end >= 1 && s[end - 1] == '%') end -= 1
        // Don't leave a dangling high surrogate.
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end -= 1
        return s.substring(0, end)
    }

    private fun buildEncodedUrl(
        scheme: String?,
        asciiHost: String?,
        rawHost: String?,
        port: Int,
        rawPath: String,
        rawQuery: String?,
        rawFragment: String?,
        cap: Int
    ): String? {
        if (scheme == null) return null
        val sb = StringBuilder(64 + rawPath.length)
        // Hostless schemes (about:, data:, mailto:) use scheme-specific
        // rather than authority syntax; emit them without "://".
        if (asciiHost == null && rawHost == null) {
            sb.append(scheme).append(':')
            if (rawPath.isNotEmpty()) sb.append(percentEncode(rawPath))
            if (rawQuery != null) sb.append('?').append(percentEncode(rawQuery))
            if (rawFragment != null) sb.append('#').append(percentEncode(rawFragment))
            return safeTruncate(sb.toString(), cap)
        }
        sb.append(scheme).append("://")
        // Prefer the ASCII host; fall back to encoded raw host so the
        // launched intent always receives a single resolvable string.
        val hostForLaunch = asciiHost ?: rawHost?.let { percentEncode(it, safe = "") } ?: return null
        sb.append(hostForLaunch)
        if (port > 0) sb.append(':').append(port)
        if (rawPath.isNotEmpty()) sb.append(percentEncode(rawPath))
        if (rawQuery != null) sb.append('?').append(percentEncode(rawQuery))
        if (rawFragment != null) sb.append('#').append(percentEncode(rawFragment))
        return safeTruncate(sb.toString(), cap)
    }

    private fun buildDisplayString(
        scheme: String?,
        asciiHost: String?,
        rawHost: String?,
        port: Int,
        rawPath: String,
        rawQuery: String?,
        rawFragment: String?,
        cap: Int
    ): String {
        if (scheme == null) return ""
        val sb = StringBuilder(64 + rawPath.length)
        // Hostless schemes use scheme-specific syntax, not authority, so
        // emit "scheme:" without "//" to avoid "about://blank". Only treat it
        // as hostless when there is genuinely no host; when IDNA rejects a host
        // (asciiHost == null but rawHost != null) we must still show it,
        // otherwise the dialog title silently drops the hostname while the
        // launch URL keeps it.
        if (asciiHost == null && rawHost == null) {
            sb.append(scheme).append(':')
            if (rawPath.isNotEmpty()) sb.append(decodeForDisplay(rawPath))
            if (rawQuery != null) sb.append('?').append(decodeForDisplay(rawQuery))
            if (rawFragment != null) sb.append('#').append(decodeForDisplay(rawFragment))
            val full = sb.toString()
            return safeTruncate(full, cap)
        }
        sb.append(scheme).append("://")
        // Prefer the ASCII host (homograph defense); fall back to the raw host
        // only when IDNA rejected it (no ASCII form exists), so display and
        // launch stay consistent.
        sb.append(asciiHost ?: rawHost)
        if (port > 0) sb.append(':').append(port)
        sb.append(decodeForDisplay(rawPath))
        if (rawQuery != null) { sb.append('?'); sb.append(decodeForDisplay(rawQuery)) }
        if (rawFragment != null) { sb.append('#'); sb.append(decodeForDisplay(rawFragment)) }
        val full = sb.toString()
        return safeTruncate(full, cap)
    }

    /** Decodes and strips invisible chars; re-encodes the segment if the
     *  result mixes Latin with Cyrillic or Greek to defeat path homographs.
     */
    private fun decodeForDisplay(raw: String): String {
        if (raw.isEmpty()) return raw
        val decoded = stripInvisible(percentDecode(raw))
        // Keep URL structural delimiters readable even when re-encoding.
        return if (containsMixedScript(decoded)) {
            percentEncode(decoded, safe = DEFAULT_SAFE)
        } else decoded
    }

    private fun malformed(uri: Uri): Normalized = Normalized(
        displayString = "",
        launchUri = uri,
        clipboardString = uri.toString(),
        asciiHost = null,
        scheme = uri.scheme?.lowercase(),
        malformed = true
    )

    // ------------------------------------------------------------------
    //  Log redaction helpers
    // ------------------------------------------------------------------

    private val TOKEN_PARAM_NAMES = hashSetOf(
        "token", "access_token", "refresh_token", "api_key", "apikey",
        "key", "password", "pass", "pwd", "secret", "authorization", "auth"
    )

    private fun maskTokenParams(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) {
            // Fragments can also carry tokens (OAuth implicit-flow: #access_token=...).
            val frag = url.indexOf('#')
            if (frag < 0) return url
            return url.substring(0, frag + 1) + maskPairs(url.substring(frag + 1))
        }
        val frag = url.indexOf('#', q)
        val queryEnd = if (frag < 0) url.length else frag
        val query = url.substring(q + 1, queryEnd)
        val rebuiltQuery = maskPairs(query)
        return if (frag < 0) {
            url.substring(0, q + 1) + rebuiltQuery
        } else {
            url.substring(0, q + 1) + rebuiltQuery + '#' + maskPairs(url.substring(frag + 1))
        }
    }

    /** Masks token-like params in a `&`-separated pair string (query or fragment). */
    private fun maskPairs(pairs: String): String =
        pairs.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) pair
            else {
                val name = pair.substring(0, eq)
                if (name.lowercase() in TOKEN_PARAM_NAMES) "$name=***" else pair
            }
        }

    // ------------------------------------------------------------------
    //  Percent-encoding primitives
    // ------------------------------------------------------------------

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> c - 'A' + 10
    }

    private fun flushBytes(sb: StringBuilder, bytes: List<Byte>) {
        if (bytes.isEmpty()) return
        val arr = ByteArray(bytes.size)
        for (i in bytes.indices) arr[i] = bytes[i]
        val decoded = String(arr, Charsets.UTF_8)
        if (decoded.contains('\uFFFD')) {
            // Incomplete or invalid UTF-8; emit the raw bytes as %XX so the
            // original encoded text round-trips instead of corrupting.
            for (b in arr) {
                val v = b.toInt() and 0xFF
                sb.append('%')
                sb.append(HEX[(v shr 4) and 0xF])
                sb.append(HEX[v and 0xF])
            }
        } else {
            sb.append(decoded)
        }
    }
}