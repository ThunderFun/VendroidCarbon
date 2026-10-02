package io.github.thunderfun.vendroid.webview

import io.github.thunderfun.vendroid.utils.SettingKeys

internal object BridgeSettings {
    // Settings keys the rest of the app reads as Booleans (via
    // SharedPreferences.getBoolean). Writing a String to any of these (e.g.
    // via setString) makes getBoolean throw ClassCastException. That was
    // a cold-start crash loop before the fallbacks went into MainActivity;
    // now it silently defeats the toggle. setString must never write to
    // them. String-read mirror: STRING_SETTING_KEYS below.
    internal val BOOLEAN_SETTING_KEYS = setOf(
        SettingKeys.KEY_VENDROID_CONFIRM_EXTERNAL_LINKS,
        SettingKeys.KEY_VENDROID_BLOCK_TYPING_INDICATOR,
        SettingKeys.KEY_VENDROID_REMEMBER_LAST_CHANNEL,
        // Ported plugin feature toggles, read by vencord_mobile.js and
        // surfaced as injected settings rows.
        SettingKeys.KEY_VENDROID_GESTURES,
        SettingKeys.KEY_VENDROID_SUPPORT_WARNINGS,
        // Migrated by MainActivity.migrateSettings() and read by the plugin.
        // Guarded here so setString can't type-poison them into Strings.
        SettingKeys.KEY_CHECK_VDE_UPDATES,
        SettingKeys.KEY_CHECK_ANNOUNCEMENTS,
        // Desktop UA switch. installWebView reads this as a Boolean at
        // startup; that read falls back to false on a wrong type, but a
        // String would still disable the toggle.
        SettingKeys.KEY_DESKTOP_MODE
    )

    // Settings keys the app reads as Strings. Writing a Boolean here
    // makes getString throw ClassCastException; clientMod did exactly
    // that: its readers sat on the startup fetch path with no matching
    // catch, so the process crash-looped on every cold start, and no
    // recovery option cleared the key. setBool must never write
    // to these, the mirror of BOOLEAN_SETTING_KEYS above. vencordLocation
    // is already rejected by isKeyAllowed for reads and writes; listed
    // here only to pin the contract if that gate changes.
    internal val STRING_SETTING_KEYS = setOf(
        SettingKeys.KEY_CLIENT_MOD,
        // Discord branch token, read natively at cold start to build the app
        // shell URL. Value-validated in VencordNative.setString.
        SettingKeys.KEY_DISCORD_BRANCH,
        SettingKeys.KEY_VENCORD_LOCATION,
        // Custom bar tint, written by the setBarColor bridge method. Pinned
        // here because setBool is prefix-admitted: without this entry page JS
        // could type-poison the key for the startup reader (see the
        // BOOLEAN_SETTING_KEYS note). setString on the key stays allowed but
        // is value-validated in VencordNative.setString.
        SettingKeys.KEY_VENDROID_BAR_COLOR,
        // Custom splash glow color, written by the setOrbColor bridge
        // method. Pinned for the same setBool type-poison reason; setString
        // on the key stays allowed but is value-validated in
        // VencordNative.setString.
        SettingKeys.KEY_VENDROID_ORB_COLOR,
        // Custom splash background, written by the setSplashBgColor bridge
        // method. Pinned for the same setBool type-poison reason; setString
        // stays allowed but is value-validated in VencordNative.setString.
        SettingKeys.KEY_VENDROID_SPLASH_BG_COLOR
    )

    // Boolean bridge keys surfaced as rapid-tap rows in the vencord_mobile.js
    // settings tree. VencordNative.minWriteIntervalNanos gives these keys a
    // 50 ms write rate-limit window instead of the default 500 ms. Listed by
    // explicit members, not filtered from BOOLEAN_SETTING_KEYS; that set pins
    // the write-type contract, this one pins tap cadence, so the two must
    // not be coupled by derivation.
    internal val RAPID_TOGGLE_KEYS = setOf(
        SettingKeys.KEY_VENDROID_CONFIRM_EXTERNAL_LINKS,
        SettingKeys.KEY_VENDROID_BLOCK_TYPING_INDICATOR,
        SettingKeys.KEY_VENDROID_GESTURES,
        SettingKeys.KEY_VENDROID_SUPPORT_WARNINGS,
        SettingKeys.KEY_DESKTOP_MODE
    )

    // Single type-contract gate for both bridge write paths: returns
    // false when the app reads [key] with the other type. Both setters
    // call this before guardedPrefs, so a rejected call consumes no
    // rate-limit slot or distinct-key budget. internal + pure so
    // BridgeSettingTypeContractTest can pin it without Android.
    internal fun isTypeSafeBridgeWrite(op: String, key: String): Boolean =
        !(op == "setString" && key in BOOLEAN_SETTING_KEYS) &&
            !(op == "setBool" && key in STRING_SETTING_KEYS)

    // Settings keys outside the Vencord-/vendroid_ prefixes that the plugin
    // may legitimately read/write via the bridge.
    private val EXTRA_ALLOWED_KEYS = setOf(
        SettingKeys.KEY_CHECK_VDE_UPDATES,
        SettingKeys.KEY_CHECK_ANNOUNCEMENTS,
        // Discord branch switcher from the Vendroid settings tree, consumed
        // by the native startup path. Unprefixed; without this entry both
        // its reads and writes would be dropped.
        SettingKeys.KEY_DISCORD_BRANCH,
        // Desktop mode toggle from the eq.js settings tree, consumed by
        // installWebView at startup. Unprefixed; without this entry both
        // its reads and writes were dropped, so the toggle never applied.
        SettingKeys.KEY_DESKTOP_MODE
    )

    // Which settings keys page JS may read or write via the bridge. Split
    // from [isKeyAllowed] and kept pure so BridgeKeyAllowlistTest can pin
    // it without Android. vencordLocation is rejected for reads as well
    // as writes: it selects the code the app downloads and executes, and
    // a custom value can carry credentials in its query string.
    //
    // Recovery-only flags (SettingKeys.KEY_SAFE_MODE, KEY_DISABLE_THEMES,
    // KEY_DISABLE_PLUGINS) stay absent: the recovery screen owns them, and
    // page JS must not be able to read or flip them.
    internal fun isBridgeKeyAllowed(id: String): Boolean =
        id != SettingKeys.KEY_VENCORD_LOCATION &&
            (id == SettingKeys.KEY_CLIENT_MOD ||
                id in EXTRA_ALLOWED_KEYS ||
                id.startsWith("Vencord-") ||
                id.startsWith(SettingKeys.VENDROID_PREFIX) ||
                id.startsWith("Vencord_") ||
                id.startsWith("css_cache_"))
}
