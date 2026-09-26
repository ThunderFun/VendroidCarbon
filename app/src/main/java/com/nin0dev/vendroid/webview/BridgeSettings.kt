package com.nin0dev.vendroid.webview

internal object BridgeSettings {
    // Settings keys the rest of the app reads as Booleans (via
    // SharedPreferences.getBoolean). Writing a String to any of these (e.g.
    // via setString) makes getBoolean throw ClassCastException. That was
    // a cold-start crash loop before the fallbacks went into MainActivity;
    // now it silently defeats the toggle. setString must never write to
    // them. String-read mirror: STRING_SETTING_KEYS below.
    internal val BOOLEAN_SETTING_KEYS = setOf(
        "vendroid_confirmExternalLinks",
        "vendroid_blockTypingIndicator",
        "vendroid_rememberLastChannel",
        // Ported plugin feature toggles, read by vencord_mobile.js and
        // surfaced as injected settings rows.
        "vendroid_gestures",
        "vendroid_support_warnings",
        // Migrated by MainActivity.migrateSettings() and read by the plugin.
        // Guarded here so setString can't type-poison them into Strings.
        "checkVDEUpdates",
        "checkAnnouncements",
        // Desktop UA switch. installWebView reads this as a Boolean at
        // startup; that read falls back to false on a wrong type, but a
        // String would still disable the toggle.
        "desktopMode"
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
        "clientMod",
        "vencordLocation"
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
        "checkVDEUpdates",
        "checkAnnouncements",
        // Desktop mode toggle from the eq.js settings tree, consumed by
        // installWebView at startup. Unprefixed; without this entry both
        // its reads and writes were dropped, so the toggle never applied.
        "desktopMode"
    )

    // Which settings keys page JS may read or write via the bridge. Split
    // from [isKeyAllowed] and kept pure so BridgeKeyAllowlistTest can pin
    // it without Android. vencordLocation is rejected for reads as well
    // as writes: it selects the code the app downloads and executes, and
    // a custom value can carry credentials in its query string.
    internal fun isBridgeKeyAllowed(id: String): Boolean =
        id != "vencordLocation" &&
            (id == "clientMod" ||
                id in EXTRA_ALLOWED_KEYS ||
                id.startsWith("Vencord-") ||
                id.startsWith("vendroid_") ||
                id.startsWith("Vencord_") ||
                id.startsWith("css_cache_"))
}
