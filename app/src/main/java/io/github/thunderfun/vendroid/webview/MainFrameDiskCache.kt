package io.github.thunderfun.vendroid.webview

import android.content.Context
import android.net.Uri
import io.github.thunderfun.vendroid.utils.Constants
import io.github.thunderfun.vendroid.utils.VDELog
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persistent disk cache for the Discord main-frame HTML shell.
 *
 * Serves the same purpose as the in-memory [VWebviewClient] main-frame cache
 * but survives process death, so a cold start doesn't block on a network fetch
 * of the (mostly static) Discord app shell.
 *
 * Security model:
 *  - Only **raw** (un-patched) HTML is persisted. The JS network firewall is
 *    injected at serve time using the *current* firewall config, so a user who
 *    tightens their firewall between sessions is never served a stale, wider
 *    allowlist. (The embedded firewall's `__vendroidFw` guard would otherwise
 *    block the new, tighter firewall from being applied.)
 *  - Only app-shell Discord routes are cacheable. Login / authorize / track and
 *    other routes that may carry OAuth `state`/`nonce`/CSRF parameters are
 *    **never** cached, so a stale copy cannot be re-served into a sensitive flow.
 *  - Stale entries are served only within [MAX_AGE_MS]; beyond that the cache is
 *    treated as a miss so ancient markup is never shown.
 *  - Each entry stores both the credential partition token and the full
 *    request key of the fetch (method + variant headers + credential
 *    partition). The serve path rejects a shell whose key or token differs
 *    from the request's, so URL-keyed storage cannot swap bodies across
 *    credential contexts or request variants (e.g. a different Accept).
 *    Entries written before the key existed always miss.
 *  - Disk is self-reclaiming: each committed write sweeps entries older than
 *    [MAX_AGE_MS], so evicted or orphaned entries cannot accumulate on disk.
 *  - Writes are atomic (`.tmp` + `renameTo`) and the file is size-capped.
 *  - Reads are defensive (any failure is a cache miss → network fetch).
 */object MainFrameDiskCache {

    /** Max staleness for a stale-while-revalidate serve. */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000 // 24 hours

    /** Upper bound on a persisted body so a malformed huge response can't fill disk. */
    internal const val MAX_BODY_BYTES = 2 * 1024 * 1024

    /** Cap on the number of URLs recorded in the preload index. */
    private const val MAX_INDEXED_URLS = 64

    /** Entry files are named "$base.<suffix>"; base is this many lowercase hex chars. */
    private const val ENTRY_BASE_LEN = 32

    /** Reserved meta key carrying the credential partition; "@" is not valid
     *  in an HTTP field name, so it can never collide with a stored header. */
    private const val CREDENTIAL_META_KEY = "@cred"

    /** Reserved meta key carrying the full request key; see
     *  [CachedMainFrame.requestKey]. */
    private const val REQUEST_KEY_META_KEY = "@rkey"

    /** Serializes the index.txt read-modify-write and the post-write stale sweep. */
    private val indexAddLock = ReentrantLock()

    /**
     * Single source of truth for "app-shell route": the resume gate
     * ([isResumableRoute], called from MainActivity) and the disk-cache gate
     * ([isCacheableRoute]) both call this predicate, so they cannot drift.
     *
     * Exact-or-slash-prefix only: `path == X || path.startsWith("$X/")`. A
     * bare `startsWith` would admit /storexyz or /libraryanything, which
     * Discord 404s; resuming one lands on a page with empty back history
     * (hardlock). /app is exact only, no /app/... subpages exist. Excluded:
     * login/authorize/track (OAuth state/nonce/CSRF params) and /blog
     * (NavigationPolicy sends it to the external popup, no in-app back path).
     */
    fun isAppShellPath(path: String): Boolean =
        path == "/app" || // exact only, no /app/... subpages exist
            path == "/channels" || path.startsWith("/channels/") ||
            path == "/library" || path.startsWith("/library/") ||
            path == "/store" || path.startsWith("/store/") ||
            path == "/friends" || path.startsWith("/friends/")

    private var cacheDir: File? = null
    private val initLock = Any()

    fun init(context: Context) {
        synchronized(initLock) {
            if (cacheDir != null) return
            cacheDir = File(context.applicationContext.cacheDir, "main_frame_cache").apply {
                // Cache dir is a throwaway; recreate if it was cleared.
                if (!exists()) mkdirs()
            }
        }
    }

    /**
     * True if the URL may be persisted and re-served as a stale HTML shell.
     * Requires HTTPS, the default HTTPS port, a Discord **app origin**
     * (discord.com / ptb. / canary. / discordapp.com apex, never CDN/media
     * subdomains, which serve attacker-uploaded content) and an app-shell
     * path ([isAppShellPath]). A non-443 port is a different origin, so it
     * never qualifies.
     */
    fun isCacheableRoute(url: Uri): Boolean {
        if (url.scheme != "https") return false
        // Uri.port is -1 when the URL omits the default port.
        val port = url.port
        if (port != -1 && port != 443) return false
        val host = url.host ?: return false
        if (!Constants.isDiscordAppOrigin(host)) return false
        val path = url.path ?: return false
        return isAppShellPath(path)
    }

    /**
     * The resume twin of [isCacheableRoute] and the only policy applied to a
     * restored lastUrl: MainActivity restores it via `loadUrl()`, which
     * bypasses shouldOverrideUrlLoading and NavigationPolicy.decide (the
     * deep-link branch runs decide; this branch does not).
     */
    fun isResumableRoute(urlString: String): Boolean =
        isCacheableRoute(Uri.parse(urlString))

    /**
     * Persists a raw HTML main-frame response. No-op unless [isCacheableRoute]
     * passes and the body is within the size cap. [headers] must be the
     * fetch-path's sanitized headers so a stale serve preserves security headers.
     * [credentialPartition] is the request's credential partition token ("" when
     * none was visible); [requestKey] is its full request key (see
     * [CachedMainFrame.requestKey]). The serve path checks both; a null
     * [requestKey] marks a legacy entry that always misses. [reasonPhrase] is
     * stored and replayed on a stale serve.
     */
    fun writeMainFrame(
        urlString: String,
        rawBody: ByteArray,
        headers: Map<String, String>,
        reasonPhrase: String = "OK",
        credentialPartition: String = "",
        nowMs: Long = System.currentTimeMillis(),
        requestKey: String? = null
    ): Boolean {
        val dir = cacheDir ?: return false
        if (rawBody.isEmpty() || rawBody.size > MAX_BODY_BYTES) return false
        val uri = try { Uri.parse(urlString) } catch (_: Exception) { return false }
        if (!isCacheableRoute(uri)) return false

        return try {
            val base = entryBaseName(urlString)
            // Matched-pair scheme: the body is named by the same timestamp
            // stored in the meta ("$base.<ts>.html"). The meta (renamed last)
            // is the commit point, so a reader always resolves the body from
            // the timestamp it just read from meta. A torn {new body, old
            // meta} combination is structurally impossible. With a fixed
            // "base.html" name, renaming the body into place before the meta
            // could expose a new body paired with an old meta.
            val bodyTmp = File(dir, "$base.tmp")
            val bodyFile = File(dir, "$base.$nowMs.html")
            val metaTmp = File(dir, "$base.meta.tmp")
            val meta = File(dir, "$base.meta")

            bodyTmp.writeBytes(rawBody)
            if (!bodyTmp.renameTo(bodyFile)) {
                bodyTmp.delete()
                return false
            }

            // Meta lines:
            //   0: fetch timestamp (== the body filename timestamp)
            //   1: base64 reason phrase
            //   2..: "key\tbase64value" header pairs (bounded, no newlines in values)
            //   "@rkey\tbase64value" request key; absent in older entries
            //         (reads as null, so the serve path always misses)
            //   last: "@cred\tbase64value" credential partition; absent in
            //         older entries (reads as "")
            val sb = StringBuilder()
            sb.append(nowMs).append('\n')
            sb.append(Base64.getEncoder().encodeToString(reasonPhrase.toByteArray(Charsets.UTF_8))).append('\n')
            val enc = Base64.getEncoder()
            for ((k, v) in headers) {
                // Keep the headers the stale serve can replay: content-type
                // and the security policies in STALE_PRESERVED_HEADERS.
                val lk = k.lowercase()
                if (lk == "content-length" || lk == "content-encoding" || lk == "transfer-encoding") continue
                if (lk == "content-type" || lk in STALE_PRESERVED_HEADERS) {
                    val kv = "$k\t${enc.encodeToString(v.toByteArray(Charsets.UTF_8))}"
                    sb.append(kv).append('\n')
                }
            }
            if (requestKey != null) {
                sb.append(REQUEST_KEY_META_KEY).append('\t')
                    .append(enc.encodeToString(requestKey.toByteArray(Charsets.UTF_8))).append('\n')
            }
            sb.append(CREDENTIAL_META_KEY).append('\t')
                .append(enc.encodeToString(credentialPartition.toByteArray(Charsets.UTF_8))).append('\n')
            // Write meta atomically LAST. It is the commit point.
            metaTmp.writeText(sb.toString())
            if (!metaTmp.renameTo(meta)) {
                metaTmp.delete()
                // Clean up the now-orphaned body file; no meta references it.
                bodyFile.delete()
                return false
            }

            // Best-effort: drop every other file for this URL now that the new
            // meta is committed, including superseded bodies and .tmp debris
            // from an interrupted write. Safe even if a concurrent reader holds
            // an old file open (deleting an open file just orphans the fd; a
            // read that races the delete lands in the catch below and becomes
            // a cache miss).
            dir.listFiles { _, n ->
                n.startsWith("$base.") && n != "$base.$nowMs.html" && n != "$base.meta"
            }?.forEach { it.delete() }

            // Record the URL in the index so cold-start preload can enumerate it.
            indexAddLock.withLock {
                val idx = File(dir, "index.txt")
                val lines = try {
                    idx.readLines().toMutableList()
                } catch (_: Exception) { mutableListOf() }
                // Keep the list bounded; drop the oldest entries beyond the cap.
                if (!lines.contains(urlString)) {
                    lines.add(urlString)
                    if (lines.size > MAX_INDEXED_URLS) {
                        // Untrack the oldest URL. Its files may still be inside
                        // the MAX_AGE_MS serve window, so leave deletion to the
                        // sweep below, which reclaims them once they expire.
                        // The dead index line is a harmless preload miss until
                        // the FIFO cycles it out.
                        lines.removeAt(0)
                    }
                    // Index write is best-effort; losing it only skips a cold-start preload hint.
                    try { idx.writeText(lines.joinToString("\n") + "\n") } catch (_: Exception) {}
                }
                // Reclaim aged-out entries (evicted URLs, crash debris).
                sweepExpiredEntries(dir, nowMs)
            }
            true
        } catch (e: Exception) {
            VDELog.d("MainFrameDiskCache", "write failed: ${e.message}")
            false
        }
    }

    /** A valid stale HTML shell read from disk, or null. */
    data class CachedMainFrame(
        val body: ByteArray,
        val reasonPhrase: String,
        val headers: Map<String, String>,
        val fetchedAt: Long,
        /** Credential context the body was fetched under ("" when none was
         *  visible); the serve path rejects a shell whose token differs. */
        val credentialPartition: String = "",
        /** Full request key (method + variant headers + credential partition)
         *  of the fetch, or null for legacy entries; the serve path rejects a
         *  null or mismatched key. */
        val requestKey: String? = null
    )

    /**
     * Reads a stale main-frame from disk if present and within [MAX_AGE_MS].
     * Any failure (missing, corrupt, too old, wrong route) is a cache miss.
     */
    fun readMainFrame(urlString: String, nowMs: Long = System.currentTimeMillis()): CachedMainFrame? {
        val dir = cacheDir ?: return null
        val uri = try { Uri.parse(urlString) } catch (_: Exception) { return null }
        if (!isCacheableRoute(uri)) return null

        return try {
            val base = entryBaseName(urlString)
            val metaFile = File(dir, "$base.meta")
            // The body is named by the timestamp stored in the meta, so a
            // committed meta always references a body that was already renamed
            // into place. Re-resolve the meta's timestamp after reading the
            // body so a commit that lands mid-read becomes a miss, never a
            // torn pair.
            val fetchedAt = readFetchedAt(metaFile) ?: return null
            if (nowMs - fetchedAt > MAX_AGE_MS) {
                // Expired. Drop so a later read doesn't re-serve a dead entry.
                metaFile.delete()
                File(dir, "$base.$fetchedAt.html").delete()
                return null
            }
            val bodyFile = File(dir, "$base.$fetchedAt.html")
            if (!bodyFile.exists()) return null
            val body = bodyFile.readBytes()
            if (body.isEmpty() || body.size > MAX_BODY_BYTES) return null
            // Commit landed mid-read → treat as a miss rather than a torn pair.
            if (readFetchedAt(metaFile) != fetchedAt) return null
            val metaLines = metaFile.readText().split('\n')
            if (metaLines.size < 2) return null
            val reason = try {
                String(Base64.getDecoder().decode(metaLines[1]), Charsets.UTF_8)
            } catch (_: Exception) {
                "OK"
            }
            // Rebuild headers from any "key\tvalue" pairs in lines 2+. The
            // reserved "@cred" line carries the credential partition; every
            // other line is a header.
            val headers = HashMap<String, String>()
            var credentialPartition = ""
            var requestKey: String? = null
            val dec = Base64.getDecoder()
            for (line in metaLines.drop(2)) {
                if (line.isEmpty()) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val k = line.substring(0, tab)
                val vEnc = line.substring(tab + 1)
                // Undecodable line: skip it; the content-type default below covers an empty map.
                try {
                    val value = String(dec.decode(vEnc), Charsets.UTF_8)
                    when (k) {
                        CREDENTIAL_META_KEY -> credentialPartition = value
                        REQUEST_KEY_META_KEY -> requestKey = value
                        else -> headers[k] = value
                    }
                } catch (_: Exception) {}
            }
            if (headers.isEmpty()) headers["content-type"] = "text/html"
            CachedMainFrame(body, reason, headers, fetchedAt, credentialPartition, requestKey)
        } catch (e: Exception) {
            VDELog.d("MainFrameDiskCache", "read failed: ${e.message}")
            null
        }
    }

    /** Reads just the fetchedAt (first line) of a meta file, or null on failure. */
    private fun readFetchedAt(metaFile: File): Long? =
        try { metaFile.readText().substringBefore('\n').toLongOrNull() } catch (_: Exception) { null }

    /**
     * Deletes every cache entry older than [MAX_AGE_MS]. Best-effort; runs
     * after every committed write, inside [indexAddLock].
     *
     * Entries can outlive their index line through index eviction, a failed
     * index write, or crash debris, and [readMainFrame] only reclaims an entry
     * when that exact URL is read again. An expired entry can never be served
     * again, so deleting it costs at most a cache miss. Entries younger than
     * MAX_AGE_MS, including files just committed by a write that has not
     * reached the index yet, are left alone. The sweep never touches
     * index.txt; dead lines there are harmless preload misses.
     */
    private fun sweepExpiredEntries(dir: File, nowMs: Long) {
        try {
            val filesByBase = HashMap<String, MutableList<File>>()
            for (f in dir.listFiles() ?: return) {
                val base = f.name.substringBefore('.')
                // Entry files are "$base.<suffix>" with a hex base of exactly
                // ENTRY_BASE_LEN chars; anything else is not ours to touch.
                if (base.length != ENTRY_BASE_LEN ||
                    !base.all { it in '0'..'9' || it in 'a'..'f' }) continue
                filesByBase.getOrPut(base) { mutableListOf() }.add(f)
            }
            for ((base, files) in filesByBase) {
                // Age comes from the meta's fetch timestamp; fall back to the
                // newest file mtime so debris without a readable meta still
                // ages out.
                val fetchedAt = readFetchedAt(File(dir, "$base.meta"))
                    ?: files.maxOfOrNull { it.lastModified() }?.takeIf { it > 0L }
                    ?: continue // undatable, leave alone
                if (nowMs - fetchedAt <= MAX_AGE_MS) continue
                files.forEach { it.delete() }
            }
        } catch (_: Exception) {
            // Sweep is best-effort; anything left behind is retried on the next sweep.
        }
    }

    /** The cacheable URLs recorded during writes, for cold-start preload. */
    fun preloadableUrls(): List<String> {
        val dir = cacheDir ?: return emptyList()
        return try {
            File(dir, "index.txt").readLines().filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Best-effort cleanup for tests / cache management. Not called on the hot
     * path. Also drops the in-memory preloaded shells, because the stale-serve
     * path in [VWebviewClient] reads only memory and would otherwise keep
     * serving entries whose disk files no longer exist.
     */
    fun clear() {
        VWebviewClient.clearPreloadedShells()
        val dir = cacheDir ?: return
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
            // Leftover files are inert; the sweep reclaims them once they age out.
        }
    }

    private fun entryBaseName(urlString: String): String {
        // SHA-256 (truncated) instead of hashCode(): hashCode() is not
        // collision-free, so two URLs could silently share a cache entry and
        // one page would be served the other's stale HTML. Truncating to 32
        // hex chars keeps collisions astronomically unlikely while staying
        // compact.
        val md = MessageDigest.getInstance("SHA-256")
        val sb = StringBuilder(ENTRY_BASE_LEN)
        for (b in md.digest(urlString.toByteArray(Charsets.UTF_8))) {
            sb.append(((b.toInt() and 0xFF) + 0x100).toString(16).substring(1))
        }
        return sb.toString().take(ENTRY_BASE_LEN)
    }
}
