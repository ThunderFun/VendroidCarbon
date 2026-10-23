package io.github.thunderfun.vendroid.webview

import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Adds the stored response's validator unless the request already carries one. */
internal fun addShellValidators(builder: Request.Builder, shell: MainFrameDiskCache.CachedMainFrame): Boolean {
    val request = builder.build()
    if (request.header("If-None-Match") != null || request.header("If-Modified-Since") != null) return false
    val etag = ResponseHeaderMerge.valueFor(shell.headers, "etag")
    val modified = ResponseHeaderMerge.valueFor(shell.headers, "last-modified")
    when {
        etag != null -> builder.header("If-None-Match", etag)
        modified != null -> builder.header("If-Modified-Since", modified)
        else -> return false
    }
    return true
}

/** Replays a validated 304 as a 200 over the stored raw body, so every cache gate still applies. */
internal fun validatedShellResponse(
    response: Response, shell: MainFrameDiskCache.CachedMainFrame, originalUrl: String,
    addedValidator: Boolean
): Response? {
    if (response.code != 304 || !addedValidator || response.request.url.toString() != originalUrl) return null
    val etag = ResponseHeaderMerge.valueFor(shell.headers, "etag")
    val modified = ResponseHeaderMerge.valueFor(shell.headers, "last-modified")
    if (if (etag != null) response.request.header("If-None-Match") != etag
        else modified == null || response.request.header("If-Modified-Since") != modified) return null
    val merged = shell.headers.mapKeys { it.key.lowercase() }.toMutableMap()
    // Age describes the revalidation response, not the original download.
    merged.remove("age")
    merged.remove("date")
    for (name in response.headers.names()) {
        val lower = name.lowercase()
        if (lower == "content-type" || lower in CACHE_METADATA_HEADERS || lower in STALE_PRESERVED_HEADERS) {
            merged[lower] = response.headers.values(name).joinToString(", ")
        }
    }
    val builder = response.newBuilder().code(200).message("OK")
        .body(shell.body.toResponseBody())
    builder.headers(okhttp3.Headers.Builder().apply {
        for ((name, value) in merged) {
            if (name !in setOf("content-length", "content-encoding", "transfer-encoding", "set-cookie")) add(name, value)
        }
    }.build())
    return builder.build()
}

/** Validators from a redirect target describe a body stored under another URL. */
internal fun shellStorageHeaders(response: Response, url: String, headers: Map<String, String>): HashMap<String, String> =
    HashMap(headers).apply {
        remove("content-length")
        remove("content-encoding")
        remove("transfer-encoding")
        if (response.request.url.toString() != url) {
            remove("etag")
            remove("last-modified")
        }
    }
