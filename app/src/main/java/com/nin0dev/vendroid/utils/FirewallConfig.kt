package com.nin0dev.vendroid.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object FirewallConfig {

    enum class Category(
        val id: String,
        val label: String,
        val domains: List<String>,
        /** If true, this category is always allowed and cannot be disabled. */
        val locked: Boolean = false
    ) {
        DISCORD(
            "discord", "Discord core",
            listOf(
                "discord.com", ".discord.com",
                "discordapp.com", ".discordapp.com",
                "discord.gg", ".discord.gg",
                "discord.media", ".discord.media",
                "discordapp.net", ".discordapp.net",
                "discordsays.com", ".discordsays.com",
                "watchanimeattheoffice.com", ".watchanimeattheoffice.com"
            ),
            locked = true
        ),
        GITHUB(
            "github", "GitHub / Vencord sources",
            listOf(
                "github.com", ".github.com",
                "githubusercontent.com", ".githubusercontent.com",
                "github.io", ".github.io",
                "codeberg.org", ".codeberg.org",
                "codeberg.page", ".codeberg.page",
                "githack.com", ".githack.com",
                "vencord.dev", ".vencord.dev"
            )
            // The retired operator hosts (git.nin0.dev, vde-builds.nin0.dev,
            // vendroid.nin0.dev) stay removed: github.com and
            // githubusercontent.com now serve the bundle and CSS.
        ),
        HCAPTCHA(
            "hcaptcha", "hCaptcha (login captcha)",
            listOf("hcaptcha.com", ".hcaptcha.com")
        ),
        GOOGLE_STORAGE(
            "google_storage", "Google Storage (Discord attachments CDN)",
            listOf("storage.googleapis.com", ".storage.googleapis.com")
        ),
        CDN(
            "cdn", "jsDelivr CDN (Vencord themes/plugins)",
            listOf("cdn.jsdelivr.net", ".cdn.jsdelivr.net", "jsdelivr.net", ".jsdelivr.net")
        ),

        // Third-party embed providers. Disabled by default.

        YOUTUBE(
            "youtube", "YouTube (video embeds)",
            listOf(
                "youtube.com", ".youtube.com",
                "googlevideo.com", ".googlevideo.com",
                "i.ytimg.com", "youtu.be"
            )
        ),
        TWITCH(
            "twitch", "Twitch (video embeds)",
            listOf(
                "twitch.tv", ".twitch.tv",
                "twitchcdn.net", ".twitchcdn.net",
                "ttvnw.net", ".ttvnw.net",
                "jtvnw.net", ".jtvnw.net",
                "gql.twitch.tv", "spade.twitch.tv"
            )
        ),
        TWITTER(
            "twitter", "Twitter / X (video embeds)",
            listOf(
                "twitter.com", ".twitter.com",
                "twimg.com", ".twimg.com",
                "api.twitter.com", "video.twimg.com"
            )
        ),
        SPOTIFY(
            "spotify", "Spotify (audio embeds)",
            listOf(
                "spotify.com", ".spotify.com",
                "spotifycdn.com", ".spotifycdn.com",
                "scdn.co", ".scdn.co",
                "wg.spotify.com"
            )
        ),
        SOUNDCLOUD(
            "soundcloud", "SoundCloud (audio embeds)",
            listOf(
                "soundcloud.com", ".soundcloud.com",
                "sndcdn.com", ".sndcdn.com"
            )
        ),
        VIMEO(
            "vimeo", "Vimeo (video embeds)",
            listOf(
                "vimeo.com", ".vimeo.com",
                "vimeocdn.com", ".vimeocdn.com",
                "akamaized.net", ".akamaized.net"
            )
        ),
        REDDIT(
            "reddit", "Reddit (video embeds)",
            listOf(
                "redditmedia.com", "redditstatic.com",
                "redd.it", ".redd.it"
            )
        ),
        STREAMABLE(
            "streamable", "Streamable (video embeds)",
            listOf("streamable.com", ".streamable.com")
        ),
        PAYPAL(
            "paypal", "PayPal (checkout embeds)",
            listOf(
                "paypalobjects.com", ".paypalobjects.com",
                "paypal.com", ".paypal.com"
            )
        ),
        AUDIUS(
            "audius", "Audius (audio embeds)",
            listOf("audius.co", ".audius.co")
        ),
        ALGOLIA(
            "algolia", "Algolia (Discord help-center search)",
            listOf("algolianet.com", ".algolianet.com", "algolia.net", ".algolia.net")
        ),
        GIF_KLIPY(
            "gif_klipy", "Klipy GIF picker",
            listOf("klipy.com", ".klipy.com")
        );

        companion object {
            fun fromId(id: String): Category? = entries.firstOrNull { it.id == id }
        }
    }

    private const val PREFS_NAME = "firewall"
    private const val KEY_DISABLED_CATEGORIES = "disabled_categories"

    // Nullable holder rather than `lateinit var` so any method called before
    // init() fails safely (returns a default/no-op) instead of throwing
    // UninitializedPropertyAccessException. The singleton's init requirement
    // must not couple to an uncaught runtime exception.
    private var prefs: SharedPreferences? = null
    private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var cachedSnapshot: Set<String> = emptySet()
    @Volatile private var cachedJsSnapshot: Set<String> = emptySet()

    // Categories disabled on first launch. Core categories stay enabled.
    private val DEFAULT_DISABLED_CATEGORIES: Set<String> = setOf(
        Category.YOUTUBE.id,
        Category.TWITCH.id,
        Category.TWITTER.id,
        Category.SPOTIFY.id,
        Category.SOUNDCLOUD.id,
        Category.VIMEO.id,
        Category.REDDIT.id,
        Category.STREAMABLE.id,
        Category.PAYPAL.id,
        Category.AUDIUS.id,
        Category.ALGOLIA.id,
        Category.GIF_KLIPY.id
    )

    // Categories exposed to the JS firewall. All categories are exposed to JS;
    // CSS-only forge hosts are excluded per-domain via [jsExcludedDomains]
    // since the native layer handles those. Deriving from [Category.entries]
    // (instead of a hand-maintained id list) keeps the native and JS
    // allowlists in sync.
    private val jsLayerCategories: Set<String> =
        Category.entries.map { it.id }.toSet()

    // Single exclusion set: forge hosts fetched only at the native layer for
    // CSS. Excluded from the JS firewall array because the native interceptor
    // (VWebviewClient.isForgeHost) already governs them; keeping them out
    // avoids a second, divergent allowlist. Any forge host added to
    // isForgeHost() must also be reflected here to keep the native/JS split
    // consistent.
    private val jsExcludedDomains = setOf(
        "github.io", ".github.io",
        "codeberg.page", ".codeberg.page",
        "githack.com", ".githack.com"
    )

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Seed defaults on first launch.
        if (!prefs!!.contains(KEY_DISABLED_CATEGORIES)) {
            prefs!!.edit().putStringSet(KEY_DISABLED_CATEGORIES, DEFAULT_DISABLED_CATEGORIES).apply()
        }
        rebuildSnapshots()
    }

    /** Allowed hosts for the native interceptor. Cached after [init]/[save]. */
    fun allowedHosts(): Set<String> = cachedSnapshot

    /** Allowed hosts for the JS firewall. Cached after [init]/[save]. */
    fun jsAllowedHosts(): Set<String> = cachedJsSnapshot

    /** True once [init] has been called, letting callers assert the boot-order
     *  contract explicitly instead of silently reading an empty snapshot. */
    fun isInitialized(): Boolean = initialized.get()

    /**
     * Normalizes a raw category domain entry to leading-dot form
     * (".example.com"). The native matcher relies on this: a bare
     * "example.com" entry would match "evildomainexample.com" via endsWith.
     */
    private fun toLeadingDot(d: String): String =
        if (d.startsWith(".")) d else ".$d"

    private fun rebuildSnapshots() {
        val disabled = disabledCategories()
        val native = LinkedHashSet<String>()
        val js = LinkedHashSet<String>()
        for (cat in Category.entries) {
            // Locked categories are always included, never disabled.
            if (!cat.locked && cat.id in disabled) continue
            for (d in cat.domains) {
                native.add(toLeadingDot(d))
                if (cat.id in jsLayerCategories && d !in jsExcludedDomains) {
                    js.add(toLeadingDot(d))
                }
            }
        }
        cachedSnapshot = native
        cachedJsSnapshot = js
    }

    fun disabledCategories(): Set<String> {
        val p = prefs ?: return emptySet()
        val raw = p.getStringSet(KEY_DISABLED_CATEGORIES, emptySet()) ?: emptySet()
        // Filter out locked IDs in case a stale/tampered prefs file contains them.
        return raw.filter { Category.fromId(it)?.locked == false }.toSet()
    }

    /** Persist a complete config update. Returns false if input was malformed. */
    fun save(disabledCats: Set<String>): Boolean {
        val p = prefs ?: return false
        // Locked categories can never be disabled, so drop any attempt to do so.
        val validCats = disabledCats.filter { Category.fromId(it)?.locked == false }.toSet()
        p.edit()
            .putStringSet(KEY_DISABLED_CATEGORIES, validCats)
            .apply()
        rebuildSnapshots()
        Constants.invalidateFirewallCaches()
        return true
    }

    fun resetToDefaults() {
        val p = prefs ?: return
        p.edit()
            .putStringSet(KEY_DISABLED_CATEGORIES, DEFAULT_DISABLED_CATEGORIES)
            .apply()
        rebuildSnapshots()
        Constants.invalidateFirewallCaches()
    }

    /** JSON for the bridge. Returns a minimal valid JSON object on failure so
     *  the editor shows an empty state rather than crashing the JS bridge. */
    fun toJson(): String {
        return try {
            buildJson()
        } catch (t: Throwable) {
            lastError = sanitizeError(t.message ?: t.javaClass.simpleName)
            "{\"categories\":[],\"error\":\"$lastError\"}"
        }
    }

    // Bound the error string and keep it JSON-safe for the editor.
    private fun sanitizeError(raw: String): String {
        val trimmed = raw.take(200).replace("\\", "\\\\").replace("\"", "\\\"")
        return trimmed.replace("\n", " ").replace("\r", " ").replace("\t", " ")
    }

    @Volatile var lastError: String? = null

    private fun buildJson(): String {
        val disabled = disabledCategories()
        val arr = JSONArray()
        for (cat in Category.entries) {
            val o = JSONObject()
            o.put("id", cat.id)
            o.put("label", cat.label)
            val domainsArr = JSONArray()
            for (d in cat.domains) domainsArr.put(d)
            o.put("domains", domainsArr)
            o.put("locked", cat.locked)
            o.put("enabled", cat.locked || cat.id !in disabled)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("categories", arr)
        return root.toString()
    }

    /** Parse JSON from the bridge and save. Returns false on parse failure. */
    fun fromJsonAndSave(json: String): Boolean {
        return try {
            val root = JSONObject(json)
            val catsArr = root.getJSONArray("categories")
            if (catsArr.length() > Category.entries.size) return false
            val disabled = mutableSetOf<String>()
            for (i in 0 until catsArr.length()) {
                val o = catsArr.getJSONObject(i)
                val id = o.getString("id")
                val cat = Category.fromId(id)
                // Skip locked categories here; save() filters again as a guard.
                if (cat != null && !cat.locked && !o.optBoolean("enabled", true)) {
                    disabled.add(id)
                }
            }
            save(disabled)
        } catch (_: Exception) {
            false
        }
    }
}