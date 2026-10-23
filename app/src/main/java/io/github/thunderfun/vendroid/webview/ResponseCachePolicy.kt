package io.github.thunderfun.vendroid.webview

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Response metadata kept for freshness checks and conditional requests. */
internal val CACHE_METADATA_HEADERS = setOf(
    "cache-control", "age", "date", "expires", "etag", "last-modified", "vary"
)

private fun cacheDirectives(headers: Map<String, String>): Map<String, String?> =
    ResponseHeaderMerge.valueFor(headers, "cache-control").orEmpty().split(',').associate {
        val parts = it.trim().split('=', limit = 2)
        parts[0].trim().lowercase(Locale.ROOT) to parts.getOrNull(1)?.trim()?.trim('"')
    }

private fun secondsToMs(value: String?): Long =
    value?.toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(Long.MAX_VALUE / 1000)?.times(1000) ?: 0L

private fun httpDate(value: String?): Long? {
    if (value == null) return null
    return try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }.parse(value)?.time
    } catch (_: Exception) { null }
}

private fun responseAge(headers: Map<String, String>, fetchedAt: Long, nowMs: Long): Long {
    if (nowMs < fetchedAt || fetchedAt < 0) return Long.MAX_VALUE
    val apparentAge = httpDate(ResponseHeaderMerge.valueFor(headers, "date"))
        ?.let { (fetchedAt - it).coerceAtLeast(0) } ?: 0L
    val age = maxOf(apparentAge, secondsToMs(ResponseHeaderMerge.valueFor(headers, "age")))
    val resident = nowMs - fetchedAt
    return if (age > Long.MAX_VALUE - resident) Long.MAX_VALUE else age + resident
}

private fun freshnessLifetime(headers: Map<String, String>, fetchedAt: Long, fallbackMs: Long): Long {
    val directives = cacheDirectives(headers)
    if ("max-age" in directives) return secondsToMs(directives["max-age"])
    val expires = ResponseHeaderMerge.valueFor(headers, "expires")
    if (expires != null) {
        val expiry = httpDate(expires) ?: return 0
        val date = httpDate(ResponseHeaderMerge.valueFor(headers, "date")) ?: fetchedAt
        return (expiry - date).coerceAtLeast(0)
    }
    return fallbackMs
}

/** Fresh per the origin's directives, capped by the caller's fallback TTL. */
internal fun responseCacheIsFresh(
    headers: Map<String, String>, fetchedAt: Long, fallbackMs: Long,
    nowMs: Long = System.currentTimeMillis()
): Boolean = !cacheExcludedByDirectives(headers.mapKeys { it.key.lowercase(Locale.ROOT) }) &&
    responseAge(headers, fetchedAt, nowMs) < minOf(fallbackMs, freshnessLifetime(headers, fetchedAt, fallbackMs))

/**
 * Whether a cached shell may be served at all.
 *
 * Freshness ([responseCacheIsFresh]) decides when a background revalidation is
 * due, not whether a stale body may be shown: turning an elapsed `max-age`
 * into a blocking fetch would remove the stale-while-revalidate serve. Only
 * directives that forbid reuse are hard blocks: no-store / no-cache / private
 * (via [cacheExcludedByDirectives]) and `must-revalidate`.
 */
internal fun responseCacheMayServeShell(
    headers: Map<String, String>, fetchedAt: Long, freshMs: Long, maxAgeMs: Long,
    nowMs: Long = System.currentTimeMillis()
): Boolean {
    if (cacheExcludedByDirectives(headers.mapKeys { it.key.lowercase(Locale.ROOT) }) ||
        nowMs < fetchedAt || nowMs - fetchedAt >= maxAgeMs) return false
    if (responseCacheIsFresh(headers, fetchedAt, freshMs, nowMs)) return true
    return "must-revalidate" !in cacheDirectives(headers)
}

/** Bounded, credential/request-keyed retry state. Times are monotonic, not wall clock. */
internal class ShellRefreshSchedule(private val maxEntries: Int = 64) {
    private data class State(val failures: Int, val nextAttempt: Long)
    private val states = LinkedHashMap<String, State>(16, 0.75f, true)

    @Synchronized fun isDue(key: String, nowMs: Long): Boolean =
        states[key]?.let { nowMs >= it.nextAttempt } ?: true

    @Synchronized fun completed(key: String, success: Boolean, nowMs: Long) {
        val failures = if (success) 0 else minOf((states[key]?.failures ?: 0) + 1, 6)
        val delay = if (success) 300_000L else minOf(30_000L shl (failures - 1), 900_000L)
        states[key] = State(failures, nowMs + delay)
        while (states.size > maxEntries) states.remove(states.keys.first())
    }
}
