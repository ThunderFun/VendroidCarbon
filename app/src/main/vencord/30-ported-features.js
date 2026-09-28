    // ------------------------------------------------------------------
    // Ported vendroid plugin features
    //
    // Everything below used to live in the vendored vendroidEnhancements /
    // vendroidSheets plugins; with the bundle now coming from upstream
    // GitHub releases, the runtime owns these features. Every entry point
    // yields while the transition guard (legacyVendroidPluginPresent) says
    // a cached operator-built bundle still carries them.
    // ------------------------------------------------------------------

    // vendroidSheets/styles.css. Turns modals into bottom sheets; pairs
    // with the vde-sheet-* class prefixes injected by the mini-patcher.
    var VENDROID_SHEETS_CSS = `
.vde-sheet-layer {
    padding: 0;
    max-width: 95vw;
    justify-self: center;
    justify-content: flex-end;
}

.vde-sheet-outer {
    align-items: flex-end;
    padding: 0;
}

.vde-sheet-inner, .vde-sheet-layer > div > div {
    border-bottom-left-radius: 0 !important;
    border-bottom-right-radius: 0 !important;
}
`.trim();

    // vendroidEnhancements/style.css. Classes used by the injected native
    // settings rows (and the plugin's old settings UI).
    var VENDROID_ENHANCEMENTS_CSS = `
.vde-close-button {
    margin-left: auto;
}

.vde-dev-options {
    margin-top: 20px;
}

.vde-button-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px;
}

.vde-icon-preview {
    width: 130px;
    border-radius: 100px;
    margin-bottom: 20px;
}

.vde-component-setting-title,
.vde-select-setting-title,
.vde-string-setting-title {
    color: var(--header-primary);
    margin-bottom: 8px;
}

.vde-component-setting-description,
.vde-select-setting-description,
.vde-string-setting-description {
    margin-bottom: 10px;
}

.vde-divider-setting {
    width: 100%;
    height: 1px;
    border-top: thin solid var(--background-modifier-accent);
    margin-top: 20px;
    margin-bottom: 20px;
}

/* The settings tab renders one <section class="vde-rows-section">;
   rows and headers are styled inline in 10-settings-rows.js. */

.vde-updater-tab-app-updates,
.vde-updater-tab-vencord-updates {
    margin-bottom: 3px;
}
`.trim();

    // vendroidEnhancements/mstyle.css. Mobile-only layout tweaks, gated on
    // "not desktop mode AND the device is actually an Android web client";
    // see isAndroidWebDevicePreOverride for why the gate must not consult
    // the (overridden) PlatformUtils module.
    var VENDROID_MSTYLE_CSS = `
div[class^="sidebarList_"] {
    width: calc(var(--screen-width) - 69px) !important;
}

[class^="sidebar__"] {
    width: var(--screen-width);
}

/* stylelint-disable-next-line selector-id-pattern */
#popout_59 {
    position: absolute !important;
    left: 0;

    /* stylelint-disable-next-line rule-empty-line-before */
    div[class^="headerTitle"] {
        width: 200px;
    }
}

div[class^="prompt_"] {
    width: 95vw !important;

    div[class^="scrollerContent"] {
        padding: 20px !important;
    }
}
`.trim();

    var _vendroidBaseCssInjected = false;
    var _vendroidMstyleInjected = false;
    var _vendroidScreenWidthLoopStarted = false;

    // Result of PlatformUtils.isAndroidWeb() captured BEFORE the Slate fix
    // overrides it to false (setupSlateOverride stores it). The plugin's
    // mstyle gate ran at plugin start, before any override, so the ported
    // gate must consult this captured value, or a UA sniff when the
    // capture has not happened yet, never the overridden module.
    var _vendroidOrigIsAndroidWeb = null;

    function isAndroidWebDevicePreOverride() {
        if (_vendroidOrigIsAndroidWeb !== null) return _vendroidOrigIsAndroidWeb;
        try {
            // Desktop-mode WebView UAs are desktop-shaped (X11/Linux, no
            // "Android" token); mobile UAs always carry it.
            return /\bAndroid\b/.test(navigator.userAgent);
        } catch (e) {
            return false;
        }
    }

    function desktopModeEnabled() {
        try {
            return !!(window.VencordMobileNative && window.VencordMobileNative.getBool("desktopMode", false));
        } catch (e) {
            return false;
        }
    }

    // Injects the ported plugin stylesheets. Idempotent: safe to call from
    // both the DOM-ready path and doInit (the mstyle gate may only become
    // decidable after setupSlateOverride captures the platform result).
    function applyVendroidPluginCss() {
        if (legacyVendroidPluginPresent()) return;
        if (!_vendroidBaseCssInjected) {
            injectStyle("vendroid_sheets", VENDROID_SHEETS_CSS);
            injectStyle("vendroid_enhancements", VENDROID_ENHANCEMENTS_CSS);
            _vendroidBaseCssInjected = true;
            console.warn("[Vendroid] Ported plugin CSS injected (sheets + enhancements)");
        }
        if (_vendroidMstyleInjected) return;
        if (desktopModeEnabled()) return;
        if (!isAndroidWebDevicePreOverride()) return;
        injectStyle("vendroid_mstyle", VENDROID_MSTYLE_CSS);
        _vendroidMstyleInjected = true;
        console.warn("[Vendroid] mstyle CSS injected (mobile, desktop mode off)");
        startScreenWidthUpdater();
    }

    // Ported from vendroidEnhancements.start(): keeps --screen-width in
    // sync with the real available width for the mstyle sidebar layout.
    function startScreenWidthUpdater() {
        if (_vendroidScreenWidthLoopStarted) return;
        _vendroidScreenWidthLoopStarted = true;
        setInterval(function() {
            try {
                var style = document.querySelector("#vde-screen-width") || document.createElement("style");
                style.setAttribute("id", "vde-screen-width");
                style.textContent = ":root { --screen-width: " + (screen && screen.availWidth || 0) + "px }";
                (document.head || document.documentElement).appendChild(style);
            } catch (e) {}
        }, 1000);
    }

    // Ported gesture navigation (vendroidEnhancements.start()): swipe
    // between the sidebar, chat and the member list. The vendroid_gestures
    // pref (default on) is read live per swipe, so the settings toggle
    // applies without a restart; the listeners stay attached for the page
    // lifetime either way.
    //
    // State is re-derived on every swipe from the same source back press
    // uses: the MOBILE_WEB_SIDEBAR_* subscriptions in 31-do-init.js feed
    // isSidebarOpen. The members panel has no Flux event and is read from
    // its DOM at swipe time.
    var _vendroidGesturesSetupDone = false;
    function gesturesEnabled() {
        try {
            return !window.VencordMobileNative || window.VencordMobileNative.getBool("vendroid_gestures", true);
        } catch (e) {
            console.error("[Vendroid] getBool for gestures failed: " + e.message);
            return true;
        }
    }
    function setupGestures() {
        if (_vendroidGesturesSetupDone) return;
        _vendroidGesturesSetupDone = true;
        if (legacyVendroidPluginPresent()) return;

        // Single-finger tracking. A gesture counts only when exactly one
        // finger was down for its whole life: a second finger, a lift with
        // fingers still down, or a touchcancel drops it. Both ends are read
        // in clientX/clientY, one coordinate space.
        var tracking = false;
        var startX = 0;
        var startY = 0;
        var startT = 0;
        var startTarget = null;

        var sidebarDriftLogged = false;
        var layerDriftLogged = false;

        // The DOM read never overrides isSidebarOpen; back press reads the
        // same flag and a second truth would drift. A mismatch is logged
        // once so selector rot or a lost Flux event shows up in VDELog.
        function sidebarDriftCanary() {
            if (sidebarDriftLogged) return;
            try {
                var domOpen = !!document.querySelector("div[class^='sidebar_']");
                if (domOpen === isSidebarOpen) return;
                sidebarDriftLogged = true;
                console.error("[Vendroid] Swipe: sidebar state drift, flux=" + isSidebarOpen +
                    " dom=" + domOpen + " (selector rot or missing Flux event)");
            } catch (e) {}
        }

        // Layer gate. Reuses the back-press helper but exempts the
        // sidebar's own layer container while the sidebar is open, so
        // left-swipe can still close it. discordLayerOpen() fails open when
        // the layer markup is missing; for swipes that would silently kill
        // the feature, so that case logs once.
        function layerBlocksSwipe(sidebarOpen) {
            var open;
            try { open = discordLayerOpen(); } catch (e) { return true; }
            if (!open) return false;
            try {
                var containers = document.querySelectorAll('[class*="layerContainer"]');
                if (containers.length === 0) {
                    if (!layerDriftLogged) {
                        layerDriftLogged = true;
                        console.error("[Vendroid] Swipe: layer containers absent, drift?");
                    }
                    return true;
                }
                if (!sidebarOpen) return true;
                for (var i = 0; i < containers.length; i++) {
                    var c = containers[i];
                    if (c.childElementCount === 0) continue;
                    if (c.querySelector("div[class^='sidebar_']")) continue;
                    return true;
                }
                return false;
            } catch (e) {
                return true;
            }
        }

        // Typing and scrubbing targets are input, not navigation: text
        // fields, sliders (input[type=range] is covered by the input rule),
        // inline players, the search card. The :not() exempts an explicit
        // contenteditable="false". Evaluated on the touchstart target,
        // which can differ from where the touch ends.
        function swipeTargetExcluded(el) {
            try {
                if (!el || !el.closest) return false;
                if (el.closest("input, textarea, [contenteditable]:not([contenteditable='false'])")) return true;
                if (el.closest("video, audio")) return true;
                if (el.closest('[data-vde-search-overlay]')) return true;
            } catch (e) {}
            return false;
        }

        // Horizontally scrollable surfaces (attachment carousels, the Nitro
        // shop) own horizontal drags. Runs after the threshold checks; the
        // getComputedStyle walk is too expensive for every tap.
        function swipeTargetScrollable(el) {
            try {
                for (var n = el; n && n !== document.body; n = n.parentElement) {
                    if (n.scrollWidth - n.clientWidth > 8) {
                        var ox = getComputedStyle(n).overflowX;
                        if (ox === "auto" || ox === "scroll") return true;
                    }
                }
            } catch (e) {}
            return false;
        }

        // Drag-to-select. Best effort: some Android flows materialise the
        // selection around the lift, so this can miss.
        function selectionActive() {
            try {
                var sel = window.getSelection();
                return !!(sel && sel.rangeCount > 0 && !sel.isCollapsed);
            } catch (e) {
                return false;
            }
        }

        // The search card is not a modal (pointer-events pass through below
        // it), so nothing else hides its touches. Same visibility test as
        // onBackPress; a hidden session leaves the results dock interactive.
        function searchOverlayVisible() {
            try {
                var o = _vendroidSearchOverlay;
                return !!(o && o.el && o.el.style.display !== 'none');
            } catch (e) {
                return false;
            }
        }

        // Lazy module lookups; the anchors are the same ones the plugin
        // used via @webpack/common. Accessed on swipe, long after boot.
        function navRouter() {
            try {
                return (Vencord.Webpack.Common && Vencord.Webpack.Common.NavigationRouter) ||
                    Vencord.Webpack.findByProps("transitionTo", "transitionToGuild");
            } catch (e) { return null; }
        }
        function selectedGuildStore() {
            try {
                return (Vencord.Webpack.Common && Vencord.Webpack.Common.SelectedGuildStore) ||
                    Vencord.Webpack.findByProps("getGuildId", "getLastSelectedGuildId");
            } catch (e) { return null; }
        }
        function channelSidebarActions() {
            try { return Vencord.Webpack.findByProps("toggleMembersSection"); } catch (e) { return null; }
        }

        function currentGuildId() {
            try {
                var store = selectedGuildStore();
                if (store && typeof store.getGuildId === "function") return store.getGuildId();
            } catch (e) {}
            return null;
        }

        // The member list only exists in a guild channel. SelectedGuildStore
        // can still report the last guild while a DM is open, so @me routes
        // are excluded by path too.
        function guildContext() {
            try {
                if (window.location.pathname.indexOf("/channels/@me") === 0) return false;
            } catch (e) { return false; }
            return !!currentGuildId();
        }

        // Same dispatch back press opens with. The hamburger click (hashed
        // class) is only the no-dispatcher fallback; history.back() is not
        // used because there may be nothing to pop.
        function openSidebar() {
            var fd = findFluxDispatcher();
            if (fd) {
                try {
                    fd.dispatch({ type: "MOBILE_WEB_SIDEBAR_OPEN" });
                    return true;
                } catch (e) {
                    console.error("[Vendroid] Swipe: sidebar OPEN dispatch failed: " + e.message);
                }
            }
            try {
                var hamburger = document.querySelector("button[class^='btnHamburger__']");
                if (hamburger) { hamburger.click(); return true; }
                console.warn("[Vendroid] Swipe: no dispatcher and no hamburger, sidebar not opened");
            } catch (e) {}
            return false;
        }

        function dispatchSidebarClose() {
            var fd = findFluxDispatcher();
            if (!fd) return false;
            try {
                fd.dispatch({ type: "MOBILE_WEB_SIDEBAR_CLOSE" });
                return true;
            } catch (e) {
                console.error("[Vendroid] Swipe: sidebar CLOSE dispatch failed: " + e.message);
                return false;
            }
        }

        // Close shape varies by route. Chat route: CLOSE alone, since
        // transitionToGuild would jump to the guild's last-active channel.
        // Guild list route: navigate into the guild, there is no chat to
        // reveal. DM list: transitionToGuild(null) lands on Friends, so
        // CLOSE plus history.back() instead.
        function closeSidebar() {
            var path = "";
            try { path = window.location.pathname; } catch (e) {}
            var chatRoute = /^\/channels\/[^\/]+\/[^\/]+$/.test(path);
            var dmHome = path.indexOf("/channels/@me") === 0;

            if (chatRoute) {
                if (dispatchSidebarClose()) return true;
            } else if (!dmHome) {
                var gid = currentGuildId();
                if (gid) {
                    var router = navRouter();
                    if (router && typeof router.transitionToGuild === "function") {
                        try { router.transitionToGuild(gid); return true; } catch (e) {
                            console.error("[Vendroid] Swipe: transitionToGuild failed: " + e.message);
                        }
                    }
                }
                if (dispatchSidebarClose()) return true;
            } else {
                dispatchSidebarClose();
            }
            try {
                if (window.history.length > 1) { window.history.back(); return true; }
            } catch (e) {}
            return false;
        }

        function toggleMembers() {
            var actions = channelSidebarActions();
            if (actions && typeof actions.toggleMembersSection === "function") {
                try { actions.toggleMembersSection(); return true; } catch (e) {
                    console.error("[Vendroid] Swipe: toggleMembersSection failed: " + e.message);
                }
            }
            return false;
        }

        document.addEventListener("touchstart", function(event) {
            if (event.touches.length !== 1) { tracking = false; return; }
            tracking = true;
            startX = event.changedTouches[0].clientX;
            startY = event.changedTouches[0].clientY;
            startT = Date.now();
            startTarget = event.target;
        }, { passive: true });

        document.addEventListener("touchcancel", function() {
            tracking = false;
        }, { passive: true });

        document.addEventListener("touchend", function(event) {
            if (!tracking) return;
            tracking = false;                                // one-shot
            if (event.touches.length !== 0) return;          // fingers still down

            try {
                var endX = event.changedTouches[0].clientX;
                var endY = event.changedTouches[0].clientY;
                var dx = endX - startX;
                var dy = endY - startY;

                // Gates cheapest first: a rejected swipe must not cost a
                // DOM query.
                if (!isInApp()) return;
                if (!gesturesEnabled()) return;
                if (desktopModeEnabled() || !isAndroidWebDevicePreOverride()) return;
                if (swipeTargetExcluded(startTarget)) return;
                if (selectionActive()) return;

                // Dominant-horizontal only: the 60 px floor plus the 1.5x
                // margin rejects diagonals and scroll drift while accepting a
                // long horizontal drag with vertical drift.
                if (Math.abs(dx) < 60 || Math.abs(dx) < 1.5 * Math.abs(dy)) return;
                if (Date.now() - startT > 600) return;       // drag, not swipe
                if (swipeTargetScrollable(startTarget)) return;

                if (searchOverlayVisible()) return;

                // Fresh state per swipe; each branch performs at most one
                // action.
                var sidebarOpen = isSidebarOpen;
                sidebarDriftCanary();
                if (layerBlocksSwipe(sidebarOpen)) return;

                var membersOpen = false;
                try { membersOpen = !!document.querySelector("div[class^='members_']"); } catch (e) {}

                if (dx < 0) {
                    // Left swipe (right to left).
                    if (sidebarOpen) {
                        closeSidebar();
                    } else if (!membersOpen && guildContext()) {
                        toggleMembers();
                    }
                    return;
                }

                // Right swipe (left to right).
                if (membersOpen && !sidebarOpen) {
                    toggleMembers();
                    return;
                }
                if (!sidebarOpen) openSidebar();
            } catch (e) {
                console.error("[Vendroid] gesture handler error: " + e.message);
            }
        }, { passive: true });

        if (gesturesEnabled()) {
            console.warn("[Vendroid] Gesture navigation enabled");
        } else {
            console.warn("[Vendroid] Gesture handler attached (enabled=false)");
        }
    }

    // Ported support-server warnings (vendroidEnhancements). Alerts on
    // entering (and on sending in) the Vencord/Equicord support channels.
    // Gated by the native vendroid_support_warnings pref (default on), read
    // live in each handler so the settings toggle applies without a restart.
    var VENCORD_SUPPORT_ID = "1026515880080842772";
    var ABANDONWARE_SUPPORT_ID = "1345457031426871417";
    var EQUICORD_SUPPORT_ID = "1297590739911573585";

    function supportWarningsEnabled() {
        try {
            return !window.VencordMobileNative || window.VencordMobileNative.getBool("vendroid_support_warnings", true);
        } catch (e) {
            console.error("[Vendroid] getBool for support warnings failed: " + e.message);
            return true;
        }
    }

    var _vendroidSupportWarningsSetupDone = false;
    function setupSupportWarnings() {
        if (_vendroidSupportWarningsSetupDone) return;
        _vendroidSupportWarningsSetupDone = true;
        if (legacyVendroidPluginPresent()) return;

        function supportLabelFor(channelId) {
            if (channelId === VENCORD_SUPPORT_ID || channelId === ABANDONWARE_SUPPORT_ID) return "Vencord";
            if (channelId === EQUICORD_SUPPORT_ID) return "Equicord";
            return null;
        }

        // Pre-send hook. Vencord.Api.MessageEvents is the exact surface the
        // MessageEventsAPI core plugin exposes for plugins'
        // onBeforeMessageSend; subscribing here avoids re-patching the
        // sendMessage module a second time alongside Vencord's own wrapper.
        try {
            var msgEvents = typeof Vencord !== "undefined" && Vencord.Api && Vencord.Api.MessageEvents;
            if (msgEvents && typeof msgEvents.addMessagePreSendListener === "function") {
                msgEvents.addMessagePreSendListener(function(channelId, msg) {
                    if (!supportWarningsEnabled()) return;
                    var label = supportLabelFor(channelId);
                    if (!label) return;
                    showNoSupportModal(label);
                    // Faithful port: blank the message; the send pipeline
                    // continues with empty content.
                    msg.content = "";
                });
                console.warn("[Vendroid] Support warning pre-send listener registered");
            } else {
                console.error("[Vendroid] Vencord.Api.MessageEvents unavailable; pre-send support warning disabled");
            }
        } catch (e) {
            console.error("[Vendroid] pre-send support warning setup failed: " + e.message);
        }

        // Channel-entry hook via the FluxDispatcher the runtime already
        // holds. The plugin used its flux CHANNEL_SELECT handler.
        try {
            var fd = findFluxDispatcher();
            if (fd && typeof fd.subscribe === "function") {
                fd.subscribe("CHANNEL_SELECT", function(data) {
                    try {
                        if (!supportWarningsEnabled()) return;
                        var channelId = data && data.channelId;
                        var label = supportLabelFor(channelId);
                        if (!label) return;
                        // The plugin gated the Equicord channel entry alert on
                        // running an Equicord build.
                        if (channelId === EQUICORD_SUPPORT_ID && !(Vencord.Api && Vencord.Api.isEquicord)) return;
                        showNoSupportModal(label);
                    } catch (eInner) {
                        console.error("[Vendroid] CHANNEL_SELECT support warning failed: " + eInner.message);
                    }
                });
                console.warn("[Vendroid] Support warning CHANNEL_SELECT subscription registered");
            } else {
                console.error("[Vendroid] FluxDispatcher unavailable; channel-entry support warning disabled");
            }
        } catch (e) {
            console.error("[Vendroid] CHANNEL_SELECT support warning setup failed: " + e.message);
        }
    }

    function showNoSupportModal(name) {
        try {
            var React = Vencord.Webpack.Common && Vencord.Webpack.Common.React;
            var Alerts = Vencord.Webpack.Common && Vencord.Webpack.Common.Alerts;
            if (!React || !Alerts || typeof Alerts.show !== "function") return;
            var pStyle = { marginTop: "8px", color: "var(--text-muted)", fontSize: "14px", lineHeight: "1.4" };
            var p = function(children, style) {
                return React.createElement("p", { style: Object.assign({}, pStyle, style || {}) }, children);
            };
            var link = React.createElement(
                "a",
                { href: "https://discord.gg/qtmpcF56Yf", target: "_blank", style: { color: "var(--text-link)" } },
                "VendroidEnhanced support server"
            );
            var body = React.createElement(
                "div",
                { style: { maxWidth: "420px" } },
                React.createElement("img", {
                    alt: "no-support-image",
                    src: "https://github.com/user-attachments/assets/4a351bfb-a2a1-4693-be2d-d19f18d76684",
                    style: { maxWidth: "100%", borderRadius: "8px" }
                }),
                p("You are using VendroidEnhanced, which the " + name + " Server does not provide support for!"),
                p([name + " only provides support for official builds. Therefore, please ask for support in the ", link, "."]),
                p("You will be banned from receiving support if you ignore this rule.", { color: "var(--header-primary)", fontWeight: "700" }),
                p("You can disable this warning and regain message sending permissions in the Vendroid settings tab.", { fontSize: "12px" })
            );
            Alerts.show({ title: "Hold on!", body: body });
        } catch (e) {
            console.error("[Vendroid] support warning modal failed: " + e.message);
        }
    }

    // Renders one marked <section class="vde-rows-section"> for the
    // settings-row poller to append into. The old plugin DOM shape (an empty
    // .vde-settings-tab anchor div) is gone, blank gap included.
    var _vendroidSettingsTabRegistered = false;
    function registerVendroidSettingsTab() {
        if (_vendroidSettingsTabRegistered) return;
        _vendroidSettingsTabRegistered = true;
        if (legacyVendroidPluginPresent()) return;
        try {
            var React = Vencord.Webpack.Common && Vencord.Webpack.Common.React;
            var settingsPlugin = Vencord.Plugins && Vencord.Plugins.plugins && Vencord.Plugins.plugins.Settings;
            if (!React || !settingsPlugin || !Array.isArray(settingsPlugin.customEntries)) {
                console.error("[Vendroid] Settings plugin unavailable; native settings rows will not render");
                return;
            }
            var VendroidSettingsTab = function() {
                return React.createElement(
                    "section",
                    { className: "vde-rows-section" }
                );
            };
            var VendroidSettingsIcon = function(props) {
                return React.createElement(
                    "svg",
                    {
                        width: (props && props.width) || 20,
                        height: (props && props.height) || 20,
                        viewBox: "0 0 24 24",
                        fill: "currentColor"
                    },
                    React.createElement("path", {
                        d: "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"
                    })
                );
            };
            settingsPlugin.customEntries.push({
                key: "vc-vdenhanced-settings",
                title: "Vendroid Settings",
                Component: VendroidSettingsTab,
                Icon: VendroidSettingsIcon
            });
            console.warn("[Vendroid] Vendroid settings tab registered");
        } catch (e) {
            console.error("[Vendroid] settings tab registration failed: " + e.message);
        }
    }

    function logVdePatchStats(source) {
        try {
            var s = _vdePatchStats;
            var appliedNames = Object.keys(s.applied);
            var missedNames = Object.keys(s.missed);
            console.warn("[Vendroid] VDE patch stats (" + source + "): chunks=" + s.chunks +
                " scanned=" + s.scanned +
                " applied=" + JSON.stringify(s.applied) +
                " missed=" + JSON.stringify(s.missed) +
                " retired=" + JSON.stringify(s.retired));
            if (missedNames.length > 0) {
                console.error("[Vendroid] VDE patch anchors missed (upstream Discord change?): " + missedNames.join(", "));
            }
            if (_vdeActivePatches.length === VDE_MODULE_PATCHES.length && s.chunks > 0 && appliedNames.length === 0) {
                // Every patch still active after real chunks flowed by and
                // nothing ever matched: the vendroidSheets anchors may have
                // rotated out of Discord's bundle entirely.
                console.error("[Vendroid] VDE patch stats: no vendroidSheets anchor matched any pushed module (sheets disabled by anchor rot?)");
            }
        } catch (e) {}
    }

    // Ported from vendroidEnhancements.start(): routes Vencord's "Edit
    // QuickCSS" action to the app's native editor dialog. The vanilla
    // bundle's browser shim opens a monaco popup instead, whose CDN vendor
    // script the strict CSP blocks; without this bridge the editor is a
    // blank window.
    var _vendroidQuickCssBridgeDone = false;
    function setupQuickCssBridge() {
        if (_vendroidQuickCssBridgeDone) return;
        _vendroidQuickCssBridgeDone = true;
        if (legacyVendroidPluginPresent()) return;
        try {
            if (typeof VencordNative === "undefined" || !VencordNative.quickCss ||
                typeof VencordNative.quickCss.get !== "function") {
                console.error("[Vendroid] VencordNative.quickCss shim unavailable; native QuickCSS editor disabled");
                return;
            }
            VencordNative.quickCss.openEditor = async function() {
                window.VencordMobileNative.openQuickCss((await VencordNative.quickCss.get()));
            };
            console.warn("[Vendroid] QuickCSS native editor bridge installed");
        } catch (e) {
            console.error("[Vendroid] quickCss bridge failed: " + e.message);
        }
    }

