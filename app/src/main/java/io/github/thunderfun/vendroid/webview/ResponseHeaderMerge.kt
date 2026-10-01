package io.github.thunderfun.vendroid.webview

/**
 * Response-header folding for the shouldInterceptRequest proxy path.
 *
 * `WebResourceResponse` exposes headers as a `Map<String, String>`, one value
 * per name. A naive put() silently drops every duplicate. Duplicates are legal
 * per RFC 9110 §5.2 and real in practice: Discord main frames ship two
 * Set-Cookie headers (`__dcfduid` + `__sdcfduid`), and duplicate Vary/Link
 * headers are common behind CDNs, so these helpers fold them instead.
 *
 * Keys are canonicalized to lowercase. HTTP/2 and HTTP/3 require lowercase
 * field names on the wire, and Chromium matches header names
 * case-insensitively, so mixed-case map keys previously produced duplicate
 * header lines (e.g. a pinned "Content-Type" landing next to the server's
 * "content-type", with Chromium honoring the first one).
 */
internal object ResponseHeaderMerge {

    /**
     * Headers whose values are not comma-separated lists. A duplicate of one
     * of these keeps the last value; joining would corrupt it ("report-to" /
     * "nel" are JSON objects that stop parsing when comma-joined,
     * "content-length" is a single framing integer). CSP is excluded: its
     * value is a comma-delimited policy list (CSP3 §2.2), so joining
     * duplicates preserves policies a last-value-wins put would drop.
     */
    private val SINGLE_INSTANCE_HEADERS = setOf(
        "content-type", "content-length", "content-encoding", "transfer-encoding",
        "location", "date", "etag", "age", "expires", "last-modified",
        "content-disposition", "strict-transport-security", "report-to", "nel"
    )

    /**
     * Folds one header pair into [headers]. Duplicate list headers are
     * comma-joined per RFC 9110 §5.2, the same value the Fetch API shows to a
     * non-intercepted page. Duplicated singletons keep the last value.
     *
     * Set-Cookie values are diverted to [setCookies] in wire order. They must
     * never be comma-joined (cookie `Expires` values contain raw commas) and
     * the flat map can only carry one of them; callers re-attach them via the
     * WebView multivalue-cookie channel or CookieManager.
     *
     * A Set-Cookie value containing NUL or newline is dropped. Neither can
     * occur in a real header value on the wire, and either would corrupt the
     * NUL-separated multivalue encoding or the cookie store.
     */
    fun merge(
        headers: MutableMap<String, String>,
        setCookies: MutableList<String>,
        name: String,
        value: String
    ) {
        val lower = name.lowercase()
        if (lower == "set-cookie") {
            if (value.none { it == '\u0000' || it == '\n' }) setCookies.add(value)
            return
        }
        val existing = headers[lower]
        headers[lower] = when {
            existing == null || lower in SINGLE_INSTANCE_HEADERS -> value
            else -> "$existing, $value"
        }
    }

    /**
     * Case-insensitive read for header maps that may predate lowercase
     * canonicalization (older persisted disk-cache entries keep their
     * original wire casing). Fast path is the exact lowercase key.
     */
    fun valueFor(headers: Map<String, String>, lowerName: String): String? {
        val exact = headers[lowerName]
        if (exact != null) return exact
        return headers.entries.firstOrNull { it.key.lowercase() == lowerName }?.value
    }

    /**
     * Strips MIME parameters from a Content-Type value
     * ("text/html; charset=utf-8" -> "text/html"); returns null when
     * [contentType] is null or has no media type before the parameters.
     *
     * For use as a WebResourceResponse mimeType only: Blink exact-matches
     * that string, so a parameter-bearing value renders the document as
     * plain text. The header map keeps the full value.
     */
    fun bareMediaType(contentType: String?): String? {
        val bare = contentType?.substringBefore(';')?.trim()
        return bare?.takeIf { it.isNotEmpty() }
    }

    /**
     * True when [contentType]'s media type is exactly `text/html`, ignoring
     * parameters and case. Use this at HTML gates instead of a substring
     * match: `text/plain; note="text/html"` is not HTML, and a store that
     * admits it rewrites the MIME to text/html at serve time.
     */
    fun isHtmlMediaType(contentType: String?): Boolean =
        bareMediaType(contentType)?.equals("text/html", ignoreCase = true) == true
}
