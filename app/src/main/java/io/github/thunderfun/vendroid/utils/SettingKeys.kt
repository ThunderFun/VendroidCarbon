package io.github.thunderfun.vendroid.utils

import android.content.SharedPreferences

/**
 * Canonical names of the shared "settings"/"css_cache" prefs files and every
 * setting key stored in them. All readers and writers take keys from here, so
 * drift between a reader and a writer is a compile error instead of a
 * silently ignored setting.
 *
 * Keys persist in on-device XML. Renaming one orphans existing users' data
 * and resurrects defaults for security-relevant flags (safeMode,
 * riskWarningAccepted), so this file only ever gains keys.
 *
 * Deliberately not exhaustive: the bundle bookkeeping keys (HttpClient.PREF_*)
 * and the boot-state and heal-marker flags (MainActivity.PREF_LAST_BOOT_STATE,
 * VendroidApp.PREF_VENCORD_LOCATION_HEALED) stay with their owners; unit
 * tests reference them there.
 */
internal object SettingKeys {
    /** The main settings file, shared with the JS bridge. */
    const val PREFS_NAME = "settings"

    /**
     * Dedicated file for CSS cache entries. Isolating CSS from [PREFS_NAME]
     * keeps the settings XML small and avoids rewriting it on every CSS write.
     */
    const val CSS_CACHE_PREFS_NAME = "css_cache"

    // --- Core app toggles ---

    /** Kill switch: load no Vencord runtime this session (RecoveryActivity / MainActivity). */
    const val KEY_SAFE_MODE = "safeMode"

    /**
     * One-shot flag for the recovery "Disable themes" action: the next :web
     * boot suppresses user theme CSS (remote links and uploaded themes) while
     * Vencord keeps loading. Reset by MainActivity's first boot read, like
     * [KEY_SAFE_MODE]. Kept out of the bridge allowlist so page JS can
     * neither read nor write it.
     */
    const val KEY_DISABLE_THEMES = "disableThemes"

    /**
     * One-shot flag for the recovery "Disable plugins" action: the next :web
     * boot starts only required plugins and their dependencies, while Vencord
     * keeps loading. Reset by MainActivity's first boot read, like
     * [KEY_DISABLE_THEMES]. Kept out of the bridge allowlist so page JS can
     * neither read nor write it.
     */
    const val KEY_DISABLE_PLUGINS = "disablePlugins"

    /** First-run security warning acceptance; gates WebView prewarm and startup. */
    const val KEY_RISK_WARNING_ACCEPTED = "riskWarningAccepted"

    /** Which client mod runtime to load ("vencord" / "equicord"); also bridged as a string. */
    const val KEY_CLIENT_MOD = "clientMod"

    /**
     * Which Discord web-app origin the client opens ("stable" / "ptb" /
     * "canary"). Bridged as a string, validated against [DiscordBranch], and
     * applied only at the next cold start. The key name matches the old plugin
     * tree's setting, so a value written before this feature existed keeps
     * working.
     */
    const val KEY_DISCORD_BRANCH = "discordBranch"

    /** Desktop UA switch, read natively at startup and writable via the
     *  bridge. The UA's Chrome version mirrors the installed WebView engine
     *  (MainActivity.desktopUserAgentFrom). */
    const val KEY_DESKTOP_MODE = "desktopMode"

    /** Saved channel URL for the remember-last-channel feature. */
    const val KEY_LAST_URL = "lastUrl"

    /** One-shot flag for MainActivity's legacy-settings migration. */
    const val KEY_MIGRATED_SETTINGS = "migratedSettings"

    // --- Legacy keys (removed by the settings migration) ---

    /** Legacy single update toggle, split into [KEY_CHECK_VDE_UPDATES] + [KEY_CHECK_ANNOUNCEMENTS]. */
    const val KEY_CHECK_VENDROID_UPDATES = "checkVendroidUpdates"

    /** Legacy equicord preference, superseded by [KEY_CLIENT_MOD]. */
    const val KEY_EQUICORD = "equicord"

    // --- Plugin-facing toggles (read by vencord_mobile.js via the bridge) ---

    /** Prefix every plugin-defined setting key starts with; the bridge allowlist keys off it. */
    const val VENDROID_PREFIX = "vendroid_"

    const val KEY_VENDROID_CONFIRM_EXTERNAL_LINKS = "vendroid_confirmExternalLinks"
    const val KEY_VENDROID_BLOCK_TYPING_INDICATOR = "vendroid_blockTypingIndicator"
    const val KEY_VENDROID_REMEMBER_LAST_CHANNEL = "vendroid_rememberLastChannel"
    const val KEY_VENDROID_GESTURES = "vendroid_gestures"
    const val KEY_VENDROID_SUPPORT_WARNINGS = "vendroid_support_warnings"

    /**
     * Custom status/nav bar tint, "#rrggbb". Absent or empty = theme default.
     * Written by the setBarColor bridge method and value-validated on the
     * setString route; read at MainActivity startup and live-applied via
     * BarColorManager.publishCustomColor.
     */
    const val KEY_VENDROID_BAR_COLOR = "vendroid_barColor"

    /**
     * Custom splash glow color, "#rrggbb". Absent or empty = auto (the glow
     * blobs follow vendroid_barColor). Written by the setOrbColor bridge
     * method and value-validated on the setString route; read once at
     * MainActivity startup and passed to LoadingScreenManager. Splash-only,
     * so unlike vendroid_barColor there is no live apply.
     */
    const val KEY_VENDROID_ORB_COLOR = "vendroid_orbColor"

    /**
     * Custom splash background (stage) color, "#rrggbb". Absent or empty =
     * auto (the stage follows vendroid_barColor through SplashPalette's
     * clamp). Written by the setSplashBgColor bridge method and
     * value-validated on the setString route; read once at MainActivity
     * startup and painted onto the loading screen. Splash-only, so no live
     * apply.
     */
    const val KEY_VENDROID_SPLASH_BG_COLOR = "vendroid_splashBgColor"

    // --- Update toggles (migrated + bridge-writable) ---

    const val KEY_CHECK_VDE_UPDATES = "checkVDEUpdates"
    const val KEY_CHECK_ANNOUNCEMENTS = "checkAnnouncements"

    /** One-shot flag for the CSS-cache file migration in VendroidApp. */
    const val KEY_CSS_CACHE_MIGRATED = "css_cache_migrated"

    /**
     * Location of the Vencord bundle (default GitHub release, or a custom
     * URL). It selects the code the app downloads and executes, so the
     * bridge allowlist gates every read and write.
     */
    const val KEY_VENCORD_LOCATION = "vencordLocation"
}

/**
 * Boolean read that survives a wrong-typed value (the type-poison guard).
 * SharedPreferences throws ClassCastException when a key was written as a
 * String (restored or hand-edited XML, or a legacy bridge bug); callers on
 * the startup path must not crash-loop on that.
 *
 * Two fallbacks, matching the raw
 * `runCatching { getBoolean(k, d) }.getOrDefault(f)` pattern this replaces:
 * - [default] is what an absent key reads (the getBoolean fallback).
 * - [poisonDefault] is what a present-but-wrong-typed key reads, and
 *   defaults to [default]. The safeMode readers pass `true` here while
 *   absent reads as `false`: poison must fail safe, but a fresh install
 *   with no key must still boot with Vencord enabled.
 *
 * [onPoison] keeps the call site's own failure log (the consequence differs
 * per site: skip prewarm, fail safe, show warning...). Pass it wherever the
 * poison is worth a VDELog line; omit it where silence is fine.
 */
internal fun SharedPreferences.getBooleanSafe(
    key: String,
    default: Boolean,
    poisonDefault: Boolean = default,
    onPoison: ((Throwable) -> Unit)? = null
): Boolean =
    runCatching { getBoolean(key, default) }
        .onFailure { onPoison?.invoke(it) }
        .getOrDefault(poisonDefault)

/**
 * String read that survives a wrong-typed value, the String counterpart to
 * [getBooleanSafe]. SharedPreferences throws ClassCastException when a key
 * was written with another type (restored or hand-edited XML, or a legacy
 * bridge bug); readers on the startup path must not crash-loop on that.
 *
 * A present-but-wrong-typed key reads as [default]; [onPoison] keeps the
 * call site's own failure log. Callers that need the wrong-typed signal
 * itself, the boot-time heals that remove or replace the poison, keep
 * their explicit try/catch instead of using this.
 */
internal fun SharedPreferences.getStringSafe(
    key: String,
    default: String,
    onPoison: ((Throwable) -> Unit)? = null
): String =
    // getString is @Nullable in the platform annotations; a null return
    // means "absent", which [default] already covers. The elvis keeps the
    // result typed String instead of String?.
    runCatching { getString(key, default) ?: default }
        .onFailure { onPoison?.invoke(it) }
        .getOrDefault(default)
