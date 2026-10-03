package io.github.thunderfun.vendroid.webview

import io.github.thunderfun.vendroid.BuildConfig
import io.github.thunderfun.vendroid.utils.Constants
import java.security.MessageDigest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Decision helpers for the interception pipeline: redirect origin checks,
 * Set-Cookie channel gating, cache-directive evaluation, and CSP rebuilding.
 * Top-level (not members of [VWebviewClient]) and free of Android framework
 * calls, so JVM unit tests can load them without running that class's
 * companion initializer, which constructs android.util.LruCache and throws
 * on the JVM. Same precedent as [VencordCsp].
 */

/**
 * Same-origin test (scheme + host + effective port) for redirect hops.
 * HttpUrl.port is the effective port (443 by default for https).
 */
internal fun isSameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
    a.scheme == b.scheme && a.host == b.host && a.port == b.port

/**
 * Full-origin app check for privileged surfaces: runtime and token injection,
 * bridge authorization, and shell caching. [Constants.isDiscordAppOrigin]
 * ignores scheme and port, so `http://discord.com` and
 * `https://discord.com:8443` would pass it. [HttpUrl.port] is the effective
 * port: absent means 443.
 */
internal fun isDiscordAppOriginUrl(url: HttpUrl): Boolean =
    url.scheme == "https" && url.port == 443 && Constants.isDiscordAppOrigin(url.host)

/** [isDiscordAppOriginUrl] for URL strings; unparsable input fails closed. */
internal fun isDiscordAppOriginUrl(url: String): Boolean =
    url.toHttpUrlOrNull()?.let(::isDiscordAppOriginUrl) ?: false

/**
 * Main-frame redirect destination policy. The app follows the 3xx itself and
 * returns the final body as the response to the ORIGINAL request URL, so
 * Chromium commits that body under the original URL's origin. The destination
 * must share the original's scheme, host, and effective port and still be an
 * app origin. Otherwise one origin's content lands in another origin's
 * security context.
 */
internal fun mainFrameRedirectAllowed(originalUrlString: String, destination: HttpUrl): Boolean {
    val original = originalUrlString.toHttpUrlOrNull() ?: return false
    return isSameOrigin(original, destination) && isDiscordAppOriginUrl(destination)
}

/**
 * True when Chromium may apply the final response's Set-Cookie values itself.
 * Chromium stores attached cookies against the intercepted request URL
 * (SetCookieHeader), so origin equality is not enough: on a same-origin path
 * change, a cookie without an explicit Path derives its default path from the
 * wrong URL. Query and fragment do not affect cookie scoping. Fails
 * conservative: an unparsable or path-changed URL uses the CookieManager
 * fallback, which scopes to the final URL explicitly.
 */
internal fun multiCookieChannelAllowed(originalUrlString: String, finalUrl: HttpUrl): Boolean {
    val original = originalUrlString.toHttpUrlOrNull() ?: return false
    return isSameOrigin(original, finalUrl) && original.encodedPath == finalUrl.encodedPath
}

/**
 * Headers that select a different response body, folded into the cache key
 * (see VWebviewClient.cacheKey). This set is also the Vary allowlist: a Vary
 * on any field outside it is rejected by [cacheExcludedByDirectives], because
 * the key would not distinguish the variants and bodies would be shared
 * across distinct requests.
 */
internal val VARIANT_HEADERS = setOf(
    "if-none-match", "if-modified-since", "accept-encoding", "accept"
)

/**
 * True when the response's own directives forbid reuse from this cache:
 * no-store, no-cache, or private in any shape (including the RFC 9111
 * field-qualified forms `no-cache="field"` / `private="field"`, which the app
 * cannot honor because it cannot strip the named fields), Vary: *, or Vary
 * naming a field outside [VARIANT_HEADERS]. Takes the folded header map (all
 * keys lowercase).
 *
 * Directive names are parsed separately from values and unquoted, so quoting
 * cannot smuggle one past the check. Accepted approximation: a quoted value
 * containing a comma (`private="a,b"`) mis-splits. The first token's name is
 * still the directive name, so exclusions hold; the bogus trailing token's
 * name never matches, so a non-exclusion directive degrades to no exclusion
 * rather than failing open.
 */
internal fun cacheExcludedByDirectives(headers: Map<String, String>): Boolean {
    val cacheControl = headers["cache-control"]?.lowercase()
    if (cacheControl != null) {
        for (directive in cacheControl.split(',')) {
            val name = directive.trim().substringBefore('=').trim().trim('"')
            when (name) {
                "no-store", "no-cache", "private" -> return true
            }
        }
    }
    val vary = headers["vary"]?.lowercase() ?: return false
    return vary.split(',').any {
        val name = it.trim().trim('"')
        name == "*" || name !in VARIANT_HEADERS
    }
}

/**
 * Non-reversible partition token for a credential-bearing header value:
 * SHA-256, 8-byte hex prefix. Not a secret; a collision only merges two
 * sessions' cache entries. Never log the input.
 */
internal fun credentialPartitionToken(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }

/**
 * Session-partition token for a request's credential headers: the
 * [credentialPartitionToken] of each present `Cookie` / `Authorization`
 * header, tagged by kind, or "" when neither is present. Folded into the
 * in-memory cache key and stamped on persisted shells. Header lookup is
 * case-insensitive.
 */
internal fun credentialHeadersPartition(headers: Map<String, String>): String {
    val sb = StringBuilder()
    for ((header, tag) in listOf("cookie" to "ck", "authorization" to "ah")) {
        val value = headers.entries
            .firstOrNull { it.key.equals(header, ignoreCase = true) }?.value
            ?: continue
        sb.append('|').append(tag).append('=').append(credentialPartitionToken(value))
    }
    return sb.toString()
}

/**
 * True when a URL-keyed stale shell may serve a request. The shell's
 * [shellRequestKey] must equal [requestKey] (method + [VARIANT_HEADERS] +
 * credential partition; see VWebviewClient.cacheKey) and both partitions must
 * match. A null [shellRequestKey] (legacy entry) always misses. The partition
 * is checked separately even though it is part of the key, so a future
 * cacheKey change cannot drop account isolation.
 */
internal fun staleShellMatches(
    shellRequestKey: String?,
    shellCredentialPartition: String,
    requestKey: String,
    requestCredentialPartition: String
): Boolean =
    shellRequestKey != null &&
        shellRequestKey == requestKey &&
        shellCredentialPartition == requestCredentialPartition

private val VENCORD_INCOMPATIBLE_CSP_DIRECTIVES = hashSetOf(
    "default-src", "script-src", "script-src-elem", "script-src-attr",
    "style-src", "style-src-elem", "style-src-attr",
    "connect-src", "img-src", "font-src", "media-src",
    "worker-src", "manifest-src", "child-src"
)

/** Conservative floor for a policy that loses every directive; an empty
 *  policy list would mean "allow all". */
private const val CSP_POLICY_FLOOR = "frame-ancestors 'none'; base-uri 'none'; object-src 'none'"

/**
 * Drops the directives VencordMobile's runtime cannot operate under from
 * every policy in [cspValue]. A CSP header value is a comma-delimited policy
 * list (CSP3 §2.2), so each policy is filtered independently; filtering the
 * list as one policy would let an incompatible directive hide behind a comma.
 * A policy that loses every directive falls back to [CSP_POLICY_FLOOR].
 */
internal fun stripVencordIncompatibleCsp(cspValue: String): String {
    return cspValue.split(',')
        .map { policy ->
            policy.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { directive ->
                    val directiveName = directive.substringBefore(" ").lowercase()
                    directiveName in VENCORD_INCOMPATIBLE_CSP_DIRECTIVES
                }
                .joinToString("; ")
                .ifEmpty { CSP_POLICY_FLOOR }
        }
        .joinToString(", ")
}

/**
 * Effective port for URL comparison: [explicitPort] when set (Android's Uri
 * uses -1 for absent), otherwise the https/http default, or -1 for other
 * schemes.
 */
internal fun effectivePort(scheme: String?, explicitPort: Int): Int {
    if (explicitPort != -1) return explicitPort
    return when {
        scheme.equals("https", ignoreCase = true) -> 443
        scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }
}

/** Cookie `name=value` pairs in a `Cookie` header value (`a=1; b=2`);
 *  malformed pairs without `name=` are ignored. Values are trimmed and
 *  surrounding double quotes stripped so recorded and store-returned values
 *  compare equal. */
internal fun cookieHeaderPairs(header: String?): Set<Pair<String, String>> {
    if (header.isNullOrEmpty()) return emptySet()
    return header.split(';').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val name = pair.substring(0, eq).trim().ifEmpty { null } ?: return@mapNotNull null
        name to normalizeCookieValue(pair.substring(eq + 1).trim())
    }.toSet()
}

/** Keeps only `name=value` pairs whose (name, value) identity is in
 *  [allowedPairs]; returns null when nothing survives, so callers never emit
 *  an empty Cookie header. Matching the value, not just the name, drops a
 *  same-named cookie scoped to another path. */
internal fun filterCookieHeader(header: String?, allowedPairs: Set<Pair<String, String>>): String? {
    if (header.isNullOrEmpty() || allowedPairs.isEmpty()) return null
    val kept = header.split(';').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val name = pair.substring(0, eq).trim()
        val value = normalizeCookieValue(pair.substring(eq + 1).trim())
        if ((name to value) in allowedPairs) pair.trim() else null
    }
    return kept.takeIf { it.isNotEmpty() }?.joinToString("; ")
}

/** `name=value` identity of a `Set-Cookie` value's first pair, normalized,
 *  or null when it is malformed. */
internal fun setCookiePair(value: String): Pair<String, String>? {
    val pair = value.substringBefore(';').trim()
    val eq = pair.indexOf('=')
    if (eq <= 0) return null
    val name = pair.substring(0, eq).trim().ifEmpty { return null }
    return name to normalizeCookieValue(pair.substring(eq + 1).trim())
}

/** Strips one pair of surrounding double quotes; CookieManager stores
 *  unquoted values, so normalized pairs from either side compare equal. */
private fun normalizeCookieValue(value: String): String =
    value.removeSurrounding("\"")

/** True when a `Set-Cookie` value carries an explicit `SameSite=Strict`;
 *  absent or any other value is not strict (unset SameSite is Lax-enforced
 *  by modern Chromium). */
internal fun setCookieSameSiteStrict(value: String): Boolean =
    value.split(';').drop(1).any { attribute ->
        val name = attribute.substringBefore('=').trim()
        name.equals("samesite", ignoreCase = true) &&
            attribute.substringAfter('=', "").trim().trim('"').equals("strict", ignoreCase = true)
    }

/**
 * Rebuilds the application CSP lines of a cached app-origin main-frame serve
 * from the CURRENT build policy. Stored lines are never authoritative: a
 * build upgrade flips ENFORCE_STRICT_CSP, and a stored report-only-era
 * enforced line must not outlive it.
 *
 * Loose mode: the stored enforced value is either a server CSP already
 * stripped by the fetch path (re-stripping is idempotent) or this app's own
 * strict policy left by an older strict build, which is kept verbatim
 * (re-stripping VencordCsp.build() would gut it into the fallback). The two
 * are told apart by exact comparison, valid because build() is a static
 * per-build string. A fresh report-only line is installed either way.
 *
 * Strict mode: the app policy replaces whatever is stored, and any
 * report-only line is dropped, mirroring VWebviewClient's foldHeadersAndCsp.
 */
internal fun rebuildCachedAppCsp(
    headers: MutableMap<String, String>,
    isAppOrigin: Boolean,
    strict: Boolean = BuildConfig.ENFORCE_STRICT_CSP
) {
    if (!isAppOrigin) return
    if (strict) {
        headers["content-security-policy"] = VencordCsp.build()
        headers.remove("content-security-policy-report-only")
    } else {
        val stored = headers["content-security-policy"]
        if (stored != null && stored != VencordCsp.build()) {
            headers["content-security-policy"] = stripVencordIncompatibleCsp(stored)
        }
        headers["content-security-policy-report-only"] = VencordCsp.build()
    }
}

/**
 * Response policies replayed on a stale shell serve. Content-Type and
 * Content-Length come from the re-injected body and the CSP lines from
 * [rebuildCachedAppCsp]; these are the remaining headers whose absence would
 * leave the stale page weaker than a fresh fetch. Names are lowercase;
 * lookups are case-insensitive because older persisted entries keep the
 * server's wire casing.
 */
internal val STALE_PRESERVED_HEADERS = setOf(
    "content-security-policy",
    "content-security-policy-report-only",
    "strict-transport-security",
    "referrer-policy",
    "permissions-policy",
    "cross-origin-opener-policy",
    "cross-origin-embedder-policy",
    "cross-origin-resource-policy",
    "x-content-type-options",
    "x-frame-options"
)

/**
 * Copies the [STALE_PRESERVED_HEADERS] present in [stored] into [into],
 * normalizing keys to lowercase. The stale serve path and the disk write's
 * header allowlist must stay in sync or persisted policies are dropped.
 */
internal fun copyStalePreservedHeaders(stored: Map<String, String>, into: MutableMap<String, String>) {
    for (name in STALE_PRESERVED_HEADERS) {
        val value = ResponseHeaderMerge.valueFor(stored, name) ?: continue
        into[name] = value
    }
}
