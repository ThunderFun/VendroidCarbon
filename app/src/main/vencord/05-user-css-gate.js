    // ------------------------------------------------------------------
    // User-theme gate belt (recovery session only)
    // ------------------------------------------------------------------
    // Two native layers already cover themes: the prelude's VencordNative
    // setter trap (see JsPatches.vencordPreludeJs) neuters uploaded themes,
    // and the network gate (VWebviewClient.userCssGateResponse) blocks
    // remote theme links. This belt blanks a vencord-themes style the
    // loader wrote anyway, in case its write landed before the trap
    // engaged, and re-blanks after the loader's DOMContentLoaded pass.
    function setupUserThemeCssGateBelt() {
        try {
            if (!window.VENCORD_USER_THEMES_DISABLED) return;
            if (window.__vendroidThemeGateBelt) return;
            window.__vendroidThemeGateBelt = 1;
            function blankUserThemeStyle() {
                try {
                    var el = document.getElementById("vencord-themes");
                    if (el && el.textContent) {
                        el.textContent = "";
                        console.warn("[Vendroid] User theme CSS suppressed for this session");
                    }
                } catch (e) { /* best effort */ }
            }
            blankUserThemeStyle();
            // The loader writes vencord-themes after an IndexedDB await, so
            // this blank usually sees an empty style and the interval below
            // does the real work. This listener covers a loader that wrote
            // synchronously. On the late bridge-injection path readyState is
            // already past "loading" and the immediate call above is what
            // matters.
            if (document.readyState === "loading") {
                document.addEventListener("DOMContentLoaded", blankUserThemeStyle, { once: true });
            }
            // Bounded follow-up passes for a slow first apply; stops after 5s.
            var beltTries = 0;
            var beltTimer = setInterval(function() {
                blankUserThemeStyle();
                if (++beltTries >= 10) clearInterval(beltTimer);
            }, 500);
        } catch (e) {
            console.warn("[Vendroid] User theme CSS gate belt failed:", e);
        }
    }
    setupUserThemeCssGateBelt();
