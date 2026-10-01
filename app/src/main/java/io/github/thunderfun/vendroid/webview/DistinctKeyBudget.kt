package io.github.thunderfun.vendroid.webview

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the bridge-written preference keys this process has seen so a page
 * script cannot grow the settings XML without bound. Seed it from the keys
 * already on disk when the bridge is built; without the seed, each activity
 * recreation or process restart hands the script a fresh batch of keys.
 *
 * The caller validates the value before reserving, so a rejected write does
 * not consume a slot.
 */
internal class DistinctKeyBudget(
    private val maxKeys: Int = MAX_KEYS,
    private val maxKeyLength: Int = MAX_KEY_LENGTH
) {
    private val keys = ConcurrentHashMap.newKeySet<String>()

    val size: Int get() = keys.size

    /** Marks already-persisted keys as known so they count toward [maxKeys]. */
    fun seed(existing: Iterable<String>) {
        existing.forEach { keys.add(it) }
    }

    /**
     * True when [id] may be persisted. Known keys always pass, a new key must
     * stay under [maxKeys], and no key may exceed [maxKeyLength].
     */
    fun tryReserve(id: String): Boolean {
        if (id.length > maxKeyLength) return false
        if (keys.contains(id)) return true
        if (keys.size >= maxKeys) return false
        keys.add(id)
        return true
    }

    companion object {
        internal const val MAX_KEYS = 256
        internal const val MAX_KEY_LENGTH = 128
    }
}
