package io.github.thunderfun.vendroid.webview

internal object StaleMainFrame {
    internal const val MAX_MEMORY_BYTES = 8 * 1024 * 1024
    private val shells = LinkedHashMap<String, MainFrameDiskCache.CachedMainFrame>(16, 0.75f, true)
    private var bytes = 0
    private var generation = 0L

    /** Loads the newest URLs first, resume route ahead of them. A live network update is never replaced. */
    fun preloadMainFrameCache(preferredUrl: String? = null) {
        val startGeneration = synchronized(this) { generation }
        val urls = MainFrameDiskCache.preloadableUrls().asReversed().toMutableList()
        if (preferredUrl != null && urls.remove(preferredUrl)) urls.add(0, preferredUrl)
        var loadedBytes = 0
        for (url in urls) {
            if (loadedBytes >= MAX_MEMORY_BYTES) break
            val cached = MainFrameDiskCache.readMainFrame(url) ?: continue
            loadedBytes += cached.body.size
            synchronized(this) {
                if (generation != startGeneration) return
                // A preload must not evict a route fetched or used during startup.
                if (!shells.containsKey(url) && bytes + cached.body.size <= MAX_MEMORY_BYTES) {
                    shells[url] = cached
                    bytes += cached.body.size
                }
            }
        }
    }

    @Synchronized internal fun put(url: String, entry: MainFrameDiskCache.CachedMainFrame) {
        if (entry.body.isEmpty() || entry.body.size > MAX_MEMORY_BYTES) return
        shells.remove(url)?.let { bytes -= it.body.size }
        shells[url] = entry
        bytes += entry.body.size
        while (bytes > MAX_MEMORY_BYTES) {
            val oldest = shells.keys.first()
            bytes -= shells.remove(oldest)!!.body.size
        }
    }

    @Synchronized internal fun remove(url: String) {
        shells.remove(url)?.let { bytes -= it.body.size }
    }

    @Synchronized fun clearPreloadedShells() {
        generation++
        shells.clear()
        bytes = 0
    }

    @Synchronized internal fun inMemoryShell(
        urlString: String, nowMs: Long = System.currentTimeMillis()
    ): MainFrameDiskCache.CachedMainFrame? {
        val entry = shells[urlString] ?: return null
        if (entry.body.isEmpty() || nowMs < entry.fetchedAt ||
            nowMs - entry.fetchedAt >= MainFrameDiskCache.MAX_AGE_MS) {
            remove(urlString)
            return null
        }
        return entry
    }
}
