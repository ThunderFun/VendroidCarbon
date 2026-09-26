package com.nin0dev.vendroid.webview

internal object StaleMainFrame {
    // Preloaded raw shells for every URL persisted last session (keyed by URL).
    // Populated off-thread at cold start so shouldInterceptRequest never has
    // to touch disk on the Chromium network thread. Written off-thread, read
    // on the network thread → guarded by @Volatile (map replaced atomically).
    @Volatile
    internal var preloadedShells: Map<String, MainFrameDiskCache.CachedMainFrame> = emptyMap()

    /** Preloads all cached shells into memory. Called off-thread at cold start. */
    fun preloadMainFrameCache() {
        val shells = HashMap<String, MainFrameDiskCache.CachedMainFrame>()
        for (url in MainFrameDiskCache.preloadableUrls()) {
            val cached = MainFrameDiskCache.readMainFrame(url) ?: continue
            shells[url] = cached
        }
        preloadedShells = shells
    }

    /** Drops the in-memory preloaded shells. Called by [MainFrameDiskCache.clear]. */
    fun clearPreloadedShells() {
        preloadedShells = emptyMap()
    }

    /** Looks up a shell from the in-memory preload, or null. */
    internal fun inMemoryShell(urlString: String): MainFrameDiskCache.CachedMainFrame? {
        val shells = preloadedShells
        val entry = shells[urlString]
        if (entry == null || entry.body.isEmpty() ||
            System.currentTimeMillis() - entry.fetchedAt > MainFrameDiskCache.MAX_AGE_MS
        ) {
            return null
        }
        return entry
    }
}
