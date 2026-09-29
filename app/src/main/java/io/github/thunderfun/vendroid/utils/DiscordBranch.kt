package io.github.thunderfun.vendroid.utils

/**
 * The three Discord web-app origins the client can open, selected by the
 * `discordBranch` pref. One owner for the token -> host -> URL mapping so no
 * call site re-derives it.
 *
 * Values are enum tokens, never URLs. Page JS may write this pref through the
 * bridge, so an arbitrary URL here would become the origin that receives the
 * capability token and the injected runtimes. [fromPrefValue] maps unknown
 * values to [STABLE] so a hand-edited or type-poisoned pref fails safe; the
 * bridge write path rejects unknown tokens outright.
 *
 * Every branch loads `/app`. The bare apex domains serve the marketing page
 * when logged out, and the app's navigation and cache policies are built
 * around the `/app` route.
 */
internal enum class DiscordBranch(
    val prefValue: String,
    val host: String,
    val label: String
) {
    STABLE("stable", "discord.com", "Stable"),
    PTB("ptb", "ptb.discord.com", "PTB"),
    CANARY("canary", "canary.discord.com", "Canary");

    /** The cold-start app shell for this branch, e.g. `https://ptb.discord.com/app`. */
    val appShellUrl: String get() = "https://$host/app"

    companion object {
        val DEFAULT = STABLE

        /**
         * Startup read of the persisted pref. A null or unrecognized value
         * maps to [DEFAULT]; startup must never throw.
         */
        fun fromPrefValue(value: String?): DiscordBranch = ofPrefValue(value) ?: DEFAULT

        /**
         * Exact token -> branch, or null when the value is not a known token.
         * The bridge write path rejects null rather than normalizing, so a
         * typo cannot silently persist the default.
         */
        fun ofPrefValue(value: String?): DiscordBranch? =
            entries.firstOrNull { it.prefValue == value }

        /**
         * Host -> branch for the resume gates, null for non-branch hosts.
         * Case-insensitive because Uri host casing is not guaranteed.
         */
        fun ofHost(host: String?): DiscordBranch? {
            val h = host?.lowercase() ?: return null
            return entries.firstOrNull { it.host == h }
        }
    }
}
