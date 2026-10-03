!(() => {
    var _vendroidCapturedWreq = null;
    var _vendroidJsonpCallback = null;
    var _vendroidChunkArr = null;
    var _vendroidCaptureAttempts = 0;
    // Bound the fake-chunk capture loop. Each attempt registers a new module
    // id in Discord's webpack registry; without a cap this runs for the page
    // lifetime if capture never succeeds. 40 x 500ms ~= 20s.
    var _vendroidCaptureMaxAttempts = 40;
    var _vendroidGiveUpLogged = false;
    // Store-only policy for the probe-captured wreq: a startup-entry capture
    // precedes Discord's remaining chunk pushes, so wreq.m is nearly empty;
    // initializing Vencord then made findByProps miss and its Lazy proxies
    // throw "Reflect.get called on non-object", breaking plugin starts and
    // text commands. tryInitWebpack() hands the wreq over no earlier than
    // VENDROID_WREQ_RESCUE_ATTEMPTS (200 ticks = 10s) and only once the
    // registry holds at least this many modules.
    var VENDROID_WREQ_MIN_MODULES = 100;
    var _vendroidWreqFloorLogged = false;
    // Hot-path input logging (beforeinput/composition, per keystroke) crosses
    // the renderer→native IPC and lands in a disk-backed log — keep it off
    // unless actively debugging input issues.
    var VENDROID_VERBOSE_INPUT = false;

    (function setupWebpackInterception() {
        var existing = window.webpackChunkdiscord_app;
        if (existing && Array.isArray(existing)) {
            _vendroidChunkArr = existing;
            hookPushProperty(existing);
        }
        try {
            Object.defineProperty(window, 'webpackChunkdiscord_app', {
                configurable: true,
                enumerable: true,
                get() { return _vendroidChunkArr; },
                set(arr) {
                    _vendroidChunkArr = arr;
                    if (Array.isArray(arr)) {
                        hookPushProperty(arr);
                    }
                }
            });
        } catch(e) {
            console.error("[Vendroid] webpack interception setup failed: " + e.message);
        }
    })();

    function hookPushProperty(arr) {
        try {
            // Wrap an already-installed push so factories arriving through it
            // are transformed before the underlying handler (Discord's, or a
            // Vencord one on the evaluateJavascript fallback) processes them.
            // Factories that already flowed through before this script ran
            // are missed; the eager-wrap case below only captures the
            // callback for the fake-chunk probe.
            var rawPush = typeof arr.push === "function" ? arr.push : null;
            var currentPush = rawPush ? vdeWrapPush(rawPush) : rawPush;
            var overrideCount = 0;
            Object.defineProperty(arr, 'push', {
                get() { return currentPush; },
                set(fn) {
                    overrideCount++;
                    if (typeof fn === 'function') {
                        if (!_vendroidJsonpCallback) {
                            _vendroidJsonpCallback = fn;
                            console.warn("[Vendroid] Captured push override #" + overrideCount + ": " + fn.toString().substring(0, 80));
                            setTimeout(vendroidTryCaptureWreq, 50);
                        }
                        currentPush = vdeWrapPush(fn);
                    } else {
                        currentPush = fn;
                    }
                },
                configurable: true,
                enumerable: false
            });
            // Eager-wrap case: the push predated this script (fallback
            // injection path). Capture it for the fake-chunk probe exactly
            // like the set-handler branch does.
            if (rawPush && !_vendroidJsonpCallback) {
                _vendroidJsonpCallback = rawPush;
                console.warn("[Vendroid] Captured pre-existing push: " + rawPush.toString().substring(0, 80));
                setTimeout(vendroidTryCaptureWreq, 50);
            }
        } catch(e) {
            console.error("[Vendroid] push hook failed: " + e.message);
        }
    }

    // ------------------------------------------------------------------
    // VDE mini-patcher
    //
    // Ported vendroidSheets patch specs (VendroidEnhanced/vendroidSheets,
    // GPL-3.0). Applied to module factories as chunks are pushed, before
    // the underlying push handler sees them. Anchors are disjoint from
    // Vencord core's patches, and each replacement carries a vde-sheet-*
    // marker so a re-apply can never double-prefix.
    //
    // The regexes are Vencord-canonicalized by hand: Vencord rewrites \i in
    // patch regexes to (?:[A-Za-z_$][\w$]*) (canonicalizeMatch,
    // src/utils/patches.ts); the originals are noted per spec.
    //
    // Semantics mirror Vencord's patcher: a string find-gate on the factory
    // source, first-occurrence regex replace (no `all` flag), and one-shot
    // retirement. A non-all patch stops matching after the first module it
    // matched is processed, even when a replacement had no effect. Missed
    // replacements are counted so anchor rot shows up in device reports
    // (window.__vdePatchStats, read by the native boot-verify probe)
    // instead of failing silently.
    // ------------------------------------------------------------------
    var VDE_MODULE_PATCHES = [
        {
            name: "vde-sheets-modal",
            find: '"data-mana-component":"modal"',
            replacements: [
                {
                    name: "sheetInner",
                    match: /(?:[A-Za-z_$][\w$]*)\(\)\((?:[A-Za-z_$][\w$]*)\.container/, // /\i\(\)\(\i.container/
                    replace: '"vde-sheet-inner "+$&',
                    marker: "vde-sheet-inner "
                },
                {
                    name: "sheetOuter",
                    match: /(?:[A-Za-z_$][\w$]*)\(\)\((?:[A-Za-z_$][\w$]*)\.outerContainer/, // /\i\(\)\(\i.outerContainer/
                    replace: '"vde-sheet-outer "+$&',
                    marker: "vde-sheet-outer "
                }
            ]
        },
        {
            name: "vde-sheets-layer",
            find: "this.props.closeModal(this.props.modalKey)",
            replacements: [
                {
                    name: "sheetLayer",
                    match: /"div",{className:/, // /"div",{className:/
                    replace: '$&"vde-sheet-layer "+',
                    marker: "vde-sheet-layer "
                }
            ]
        },
        {
            name: "vde-sheets-fsm-string",
            find: ',fullScreenOnMobile:"',
            replacements: [
                {
                    name: "fsmString",
                    match: /fullScreenOnMobile:"(?:[A-Za-z_$][\w$]*)"/, // /fullScreenOnMobile:"\i"/
                    replace: 'fullScreenOnMobile:""'
                    // No marker: idempotent because the neutralized
                    // fullScreenOnMobile:"" no longer matches.
                }
            ]
        },
        {
            name: "vde-sheets-fsm-class",
            find: "fullscreenOnMobile__",
            replacements: [
                {
                    name: "fsmClass",
                    match: /((?:[A-Za-z_$][\w$]*)):"fullscreenOnMobile__?[a-zA-Z0-9]+"/, // /(\i):"fullscreenOnMobile__?[a-zA-Z0-9]+"/
                    replace: '$1:""'
                    // No marker: the find string is fully consumed by the
                    // replacement, so a re-run never matches.
                }
            ]
        }
    ];

    var _vdeActivePatches = VDE_MODULE_PATCHES.slice();
    var _vdeSeenFactories = typeof WeakSet === "function" ? new WeakSet() : null;
    var _vdePatchStats = { chunks: 0, scanned: 0, applied: {}, missed: {}, retired: [] };

    // Transition guard: while a cached operator-built bundle still carries
    // the vendored plugins, those plugins apply their own patches, styles
    // and features, so every ported feature here must yield rather than
    // apply them twice. Drop this once operator-built bundles are gone.
    function legacyVendroidPluginPresent() {
        try {
            return typeof Vencord !== "undefined" && Vencord.Plugins &&
                Vencord.Plugins.plugins != null &&
                Vencord.Plugins.plugins.VendroidEnhancements != null;
        } catch (e) {
            return false;
        }
    }

    function vdeCount(map, key) {
        map[key] = (map[key] || 0) + 1;
    }

    // Recompiles patched factory source into a real function. Mirrors
    // Vencord's patcher: "0," expression-ifies the source, anonymous
    // functions get their (possibly async-stripped) keyword re-prefixed,
    // and a same-origin sourceURL keeps error attribution un-masked (the
    // file:/// pragmas are relabeled by HttpClient's bundle patches for
    // the same reason).
    function vdeRecompileFactory(moduleId, newCode) {
        try {
            var isArrow = newCode.charAt(0) === "(";
            var expr = "0," + (!isArrow ? "function" : "") + newCode.slice(newCode.indexOf("("));
            var src = "// Webpack Module " + moduleId + " - patched by Vendroid\n" + expr +
                "\n//# sourceURL=https://discord.com/vencord-module" + moduleId;
            return (0, eval)(src);
        } catch (e) {
            console.error("[Vendroid] VDE patch recompile failed for module " + moduleId + ": " + e.message);
            return null;
        }
    }

    // Applies every still-active patch to one factory. Returns a replacement
    // factory when any replacement changed the source, else null.
    function vdePatchFactory(moduleId, factory, code) {
        var newCode = code;
        for (var i = _vdeActivePatches.length - 1; i >= 0; i--) {
            var patch = _vdeActivePatches[i];
            if (code.indexOf(patch.find) === -1) continue;
            for (var r = 0; r < patch.replacements.length; r++) {
                var repl = patch.replacements[r];
                try {
                    if (repl.marker && newCode.indexOf(repl.marker) !== -1) continue;
                    var replaced = newCode.replace(repl.match, repl.replace);
                    if (replaced === newCode) {
                        vdeCount(_vdePatchStats.missed, repl.name);
                        console.error("[Vendroid] VDE patch " + repl.name + " matched its find but had no effect (anchor rot? module " + moduleId + ")");
                    } else {
                        newCode = replaced;
                        vdeCount(_vdePatchStats.applied, repl.name);
                        console.warn("[Vendroid] VDE patch applied: " + repl.name + " (module " + moduleId + ")");
                    }
                } catch (ePatch) {
                    vdeCount(_vdePatchStats.missed, repl.name);
                    console.error("[Vendroid] VDE patch " + repl.name + " threw: " + ePatch.message);
                }
            }
            // One-shot retirement, mirroring Vencord's non-all handling.
            _vdePatchStats.retired.push(patch.name);
            _vdeActivePatches.splice(i, 1);
        }
        if (newCode === code) return null;
        return vdeRecompileFactory(moduleId, newCode);
    }

    function vdeTransformChunk(chunk) {
        if (legacyVendroidPluginPresent()) return;
        if (!chunk || !Array.isArray(chunk) || chunk.length < 2) return;
        var modules = chunk[1];
        if (!modules || typeof modules !== "object") return;
        _vdePatchStats.chunks++;
        for (var id in modules) {
            if (!Object.prototype.hasOwnProperty.call(modules, id)) continue;
            var factory = modules[id];
            if (typeof factory !== "function") continue;
            if (_vdeSeenFactories && _vdeSeenFactories.has(factory)) continue;
            if (_vdeSeenFactories) _vdeSeenFactories.add(factory);
            _vdePatchStats.scanned++;
            var code;
            try {
                code = Function.prototype.toString.call(factory);
            } catch (eToStr) { continue; }
            if (_vdeActivePatches.length === 0) break;
            try {
                var patched = vdePatchFactory(id, factory, code);
                if (patched) modules[id] = patched;
            } catch (ePatch) {
                console.error("[Vendroid] VDE patch pass failed for module " + id + ": " + ePatch.message);
            }
        }
        // Read by the native boot-verify probe (BOOT_VERIFY_JS) so anchor-rot
        // counters land in device reports.
        window.__vdePatchStats = _vdePatchStats;
    }

    function vdeWrapPush(fn) {
        if (typeof fn !== "function" || fn.__vdePatchedPush) return fn;
        var wrapped = function(chunk) {
            try {
                vdeTransformChunk(chunk);
            } catch (e) {
                console.error("[Vendroid] VDE chunk transform failed: " + e.message);
            }
            return fn.apply(this, arguments);
        };
        wrapped.__vdePatchedPush = true;
        return wrapped;
    }

    // Throttle probe pushes: extractWebpackRequire() also calls this from
    // the 50ms init poll, and unthrottled probes burned the 40-attempt
    // budget in ~2s.
    var _vendroidLastProbeAt = 0;

    function vendroidTryCaptureWreq() {
        if (_vendroidCapturedWreq) return true;
        if (!_vendroidJsonpCallback) return false;
        if (_vendroidCaptureAttempts >= _vendroidCaptureMaxAttempts) {
            if (!_vendroidGiveUpLogged) {
                _vendroidGiveUpLogged = true;
                console.error("[Vendroid] Gave up capturing __webpack_require__ after " + _vendroidCaptureAttempts + " attempts; runtime patches will not apply");
            }
            return false;
        }

        // Startup-entry format. Webpack never executes factories arriving via
        // push(); only the third array element (runtime callback) runs, with
        // __webpack_require__ as argument. The old probe pushed chunk id 0,
        // which marked the real chunk 0 installed (installedChunks[0] = 0) and
        // could break its lazy require.e(0); this id is unique and non-numeric.
        var now = Date.now();
        if (now - _vendroidLastProbeAt < 500) return false;
        _vendroidLastProbeAt = now;
        _vendroidCaptureAttempts++;

        var captured = null;
        var fakeChunk = [
            ["_vendroid_probe_" + now + "_" + _vendroidCaptureAttempts],
            {},
            function(wreq) { captured = wreq; }
        ];

        try {
            _vendroidJsonpCallback(fakeChunk);
        } catch(e) {
            if (_vendroidCaptureAttempts <= 3) console.warn("[Vendroid] Fake chunk error: " + e.message);
        }

        // Vencord's _initWebpack does Reflect.defineProperty(e.c, ...), so a
        // captured wreq needs .c; a shape change then fails here instead of
        // half-initializing Vencord.
        if (captured && typeof captured === "function" && captured.c) {
            // Store only; tryInitWebpack() hands it over late (see VENDROID_WREQ_MIN_MODULES).
            _vendroidCapturedWreq = captured;
            console.warn("[Vendroid] Captured __webpack_require__ from startup entry!");
            return true;
        }
        if (_vendroidCaptureAttempts <= 3) {
            console.warn("[Vendroid] Startup-entry probe did not yield wreq (type=" + typeof captured + ", attempt " + _vendroidCaptureAttempts + ")");
        }
        setTimeout(vendroidTryCaptureWreq, 500);
        return false;
    }

    // No eager handover function here. tryInitWebpack() is the only path to
    // Vencord.Webpack._initWebpack; timing policy at VENDROID_WREQ_MIN_MODULES.

    function tryPushFakeChunkDirectly() {
        if (_vendroidCapturedWreq) return true;
        var chunkArr = window.webpackChunkdiscord_app || _vendroidChunkArr;
        if (!chunkArr) return false;
        var pushFn = chunkArr.push;
        if (typeof pushFn !== "function") return false;
        if (pushFn.toString().indexOf("[native code]") !== -1) return false;

        // Same startup-entry probe format as vendroidTryCaptureWreq().
        var captured = null;
        var now = Date.now();
        var fakeChunk = [
            ["_vendroid_probe_" + now],
            {},
            function(wreq) { captured = wreq; }
        ];
        try {
            pushFn.call(chunkArr, fakeChunk);
        } catch(e) {}
        if (captured && typeof captured === "function" && captured.c) {
            // Store only, same policy as vendroidTryCaptureWreq().
            _vendroidCapturedWreq = captured;
            console.warn("[Vendroid] Captured __webpack_require__ via direct push!");
            return true;
        }
        return false;
    }

    console.warn("[Vendroid] vencord_mobile.js loaded");
    console.warn("[Vendroid] Early state: chunkArr=" + (window.webpackChunkdiscord_app ? "exists len=" + window.webpackChunkdiscord_app.length + " push=" + window.webpackChunkdiscord_app.push.toString().substring(0, 80) : "MISSING"));

    function getModalEscapeHandler() {
        try {
            if (typeof Vencord !== "undefined" && Vencord.Webpack && typeof Vencord.Webpack.findLazy === "function") {
                return Vencord.Webpack.findLazy(m => m.binds?.length === 1 && m.binds[0] === "esc");
            }
        } catch(e) {
            console.error("[Vendroid] getModalEscapeHandler error: " + e.message);
        }
        return null;
    }

    let ModalEscapeHandler = null;
    try {
        ModalEscapeHandler = getModalEscapeHandler();
        if (ModalEscapeHandler) {
            console.warn("[Vendroid] ModalEscapeHandler found");
        } else {
            console.warn("[Vendroid] ModalEscapeHandler not ready yet");
        }
    } catch(e) {
        console.error("[Vendroid] ModalEscapeHandler FAILED: " + e.message);
    }

    // Webpack lookups for the stores that report open overlays: the flux
    // layer stack, the modal stack, the context menu. Late chunks need
    // retries; whatever resolves is kept.
    var _vdeOverlayStores = { layers: null, modals: null, ctx: null, tries: 0 };
    function vendroidOverlayStores() {
        var s = _vdeOverlayStores;
        if (s.tries >= 30) return s;
        s.tries++;
        try {
            if (typeof Vencord === "undefined" || !Vencord.Webpack) return s;
            s.layers = s.layers || Vencord.Webpack.findByProps("hasLayers", "getLayers") || null;
            s.modals = s.modals || Vencord.Webpack.findByProps("getOpenModalKeys", "closeModal") || null;
            s.ctx = s.ctx || Vencord.Webpack.findByProps("getContextMenu", "isOpen") || null;
        } catch(e) {}
        return s;
    }

    // Is a Discord overlay open? Gate for the back press and swipes. Reads
    // the stores Discord's own shortcuts use (mod+k checks
    // LayerStore.hasLayers()). The layer containers keep mounted-but-empty
    // children, so the old childElementCount probe reported "open" with
    // nothing on screen, rejecting swipes and consuming back presses. The
    // DOM probe remains only when webpack is unavailable, and counts a
    // child only if it renders.
    function discordLayerOpen() {
        var s = vendroidOverlayStores();
        if (s.layers || s.modals || s.ctx) {
            try {
                if (s.layers && s.layers.hasLayers && s.layers.hasLayers()) return true;
                if (s.ctx && s.ctx.isOpen && s.ctx.isOpen()) return true;
                if (s.modals && s.modals.getOpenModalKeys) {
                    var keys = s.modals.getOpenModalKeys();
                    if (keys && (keys.length > 0 || (typeof keys.size === "number" && keys.size > 0))) return true;
                }
            } catch(e) {
                return true;
            }
            return false;
        }
        try {
            var containers = document.querySelectorAll('[class*="layerContainer"]');
            // No containers at all (markup change): fail open to the previous
            // unconditional call instead of breaking modal closing.
            if (containers.length === 0) return true;
            for (var i = 0; i < containers.length; i++) {
                var kids = containers[i].children;
                for (var j = 0; j < kids.length; j++) {
                    var r = kids[j].getBoundingClientRect();
                    if (r.width > 0 && r.height > 0) return true;
                }
            }
        } catch(e) {
            return true;
        }
        return false;
    }

    let isSidebarOpen = false;
    try {
        var path = window.location.pathname;
        // Sidebar is typically showing (channel/DM list) when not inside a specific channel view.
        isSidebarOpen = !/^\/channels\/[^\/]+\/[^\/]+$/.test(path);
    } catch(e) {}

    // Sidebar state reconciliation.
    //
    // What the Discord bundle does:
    //   - MobileWebSidebarStore.getIsOpen() = !platform.isMobile || sI, with
    //     sI flipped only by MOBILE_WEB_SIDEBAR_OPEN/CLOSE.
    //   - Its route listener dispatches those actions on channel switches,
    //     and the sidebar container unmounts when closed.
    //   - btnHamburger__ is not used: the open button dispatches
    //     MOBILE_WEB_SIDEBAR_OPEN directly.
    //   - div[class^='sidebar_'] also matches unrelated components such as
    //     settings pages and thread sidebars, so a bare DOM probe can read a
    //     false "open".
    //
    // syncSidebarOpenFromDom() reconciles in this order: first the store when
    // it resolves, since that is what Discord's UI reads; then a rendered-shell
    // check, where sidebar_ absence means closed; last the legacy hamburger.

    var _vendroidSidebarStore = null;
    var _vendroidSidebarStoreScans = 0;
    function mobileWebSidebarStore() {
        if (_vendroidSidebarStore) return _vendroidSidebarStore;
        // Cap the Vencord finder so a missing store isn't rescanned forever.
        // The raw fallback below keeps looking after the cap.
        if (_vendroidSidebarStoreScans < 60) {
            try {
                if (typeof Vencord !== "undefined" && Vencord.Webpack) {
                    _vendroidSidebarStoreScans++;
                    _vendroidSidebarStore = Vencord.Webpack.find(function(m) {
                        return m && m.displayName === "MobileWebSidebarStore" && typeof m.getIsOpen === "function";
                    }) || null;
                }
            } catch(e) {}
        }
        // Raw fallback for standalone sessions. rawFindModule memoizes hits
        // only, so a late-loading store is still picked up.
        if (!_vendroidSidebarStore) {
            _vendroidSidebarStore = rawFindModule("mobileWebSidebarStore", function(m) {
                return m && m.displayName === "MobileWebSidebarStore" && typeof m.getIsOpen === "function";
            });
        }
        return _vendroidSidebarStore;
    }

    // Snapshot of the rendered sidebar signals. A null field means the
    // probe threw, not that the signal is absent.
    function sidebarSignals() {
        var s = { dom: null, burger: null, store: null };
        try { s.dom = document.querySelector("div[class^='sidebar_']") ? 1 : 0; } catch(e) {}
        try { s.burger = document.querySelector("button[class^='btnHamburger__']") ? 1 : 0; } catch(e) {}
        try {
            var store = mobileWebSidebarStore();
            if (store && typeof store.getIsOpen === "function") s.store = store.getIsOpen() ? 1 : 0;
        } catch(e) {}
        return s;
    }

    function syncSidebarOpenFromDom() {
        var s;
        try { s = sidebarSignals(); } catch(e) { return isSidebarOpen; }
        var resolved = null;
        if (s.store !== null) {
            resolved = s.store === 1;
        } else if (s.dom === 1) {
            // sidebar_ present. Can also match settings/thread sidebars, so
            // never force the flag open from this alone.
            resolved = null;
        } else {
            // No sidebar_ element anywhere. Closed, but only trust it once
            // the app shell exists (pre-render would clobber the boot
            // path-derived value).
            var shell = s.burger === 1;
            if (!shell) {
                try { shell = !!document.querySelector("[class^='base_'], [class^='chat_']"); } catch(e) {}
            }
            if (shell) resolved = false;
        }
        if (resolved === null || resolved === isSidebarOpen) return isSidebarOpen;
        isSidebarOpen = resolved;
        return isSidebarOpen;
    }

    let initialized = false;

    // Desktop-oriented plugins upstream ships with enabledByDefault:true
    // that are dead weight or dead code inside the Android WebView:
    //   WebPWA              - PWA manifest + navigator.setAppBadge; the app
    //                         itself is the PWA
    //   WebScreenShare      - replaces getDisplayMedia with a quality-picker
    //                         modal; the WebView has no usable screen-capture
    //                         path, so the override only shadows future
    //                         native screenshare plumbing
    //   WebScreenShareFixes - desktop Chromium SDP bitrate munging (2500kbps
    //                         cap removal); no effect path here today
    // Disabled at two layers. Download-time bundle patches flip
    // enabledByDefault:!0 to !1 (BundlePatcher.kt, the source of truth for
    // both the settings default and recoverPlugins' force-enable pass
    // below). This sweep is the second layer, migrating settings persisted
    // by older builds; their materialized enabled:true would otherwise
    // outlive the bundle change (Vencord persists its settings tree to
    // localStorage under "VencordSettings", so old defaults are already on
    // disk).
    //
    // The enabledByDefault neutralization runs every boot, before the flag
    // early-return, so recoverPlugins can never resurrect the plugins even
    // when the bundle patch missed (anchor rot). The sweep itself is
    // one-shot via a dual flag: native prefs (survive WebView storage loss
    // and the in-memory localStorage shim) plus a localStorage mirror
    // (covers a silently rejected native write; bridge writes can be
    // dropped by the per-key rate limiter or the 256 distinct-key cap, and
    // rejections are silent). Skip if either flag is set. Bump the flag
    // suffix when extending the plugin list.
    var VDE_DEFAULT_OFF_PLUGINS = ["WebPWA", "WebScreenShare", "WebScreenShareFixes"];
    var VDE_DEFAULT_OFF_FLAG = "vendroid_plugin_defaults_v1";

    function applyPluginDefaultOff() {
        try {
            VDE_DEFAULT_OFF_PLUGINS.forEach(function(n) {
                var p = Vencord.Plugins && Vencord.Plugins.plugins && Vencord.Plugins.plugins[n];
                if (p && p.enabledByDefault) p.enabledByDefault = false;
            });

            var sweptNative = false, sweptLs = false;
            try { sweptNative = VencordMobileNative.getBool(VDE_DEFAULT_OFF_FLAG, false); } catch(e) {}
            try {
                sweptLs = typeof window.localStorage === "object" && window.localStorage !== null &&
                    window.localStorage.getItem(VDE_DEFAULT_OFF_FLAG) === "1";
            } catch(e) {}
            if (sweptNative || sweptLs) return;

            VDE_DEFAULT_OFF_PLUGINS.forEach(function(n) {
                // The read materializes plugins[n] from the (patched) bundle
                // default; that materialized state is what we inspect and
                // correct. Don't optimize the access away.
                var s = Vencord.Settings && Vencord.Settings.plugins && Vencord.Settings.plugins[n];
                if (s && s.enabled) {
                    s.enabled = false;
                    var p = Vencord.Plugins.plugins[n];
                    if (p && p.started) {
                        // Expected on the first migration boot: the bundle
                        // starts WebpackReady-stage plugins as soon as
                        // _initWebpack resolves, which can beat this sweep.
                        // stopPlugin un-applies patches and restores
                        // replaced globals (getDisplayMedia).
                        try { Vencord.Plugins.stopPlugin(p); } catch(e) {}
                    }
                    console.warn("[Vendroid] Default-off sweep disabled plugin: " + n);
                }
            });

            try {
                VencordMobileNative.setBool(VDE_DEFAULT_OFF_FLAG, true);
                var persisted = false;
                try { persisted = VencordMobileNative.getBool(VDE_DEFAULT_OFF_FLAG, false); } catch(e) {}
                if (!persisted) console.warn("[Vendroid] Default-off flag write rejected; localStorage mirror carries the flag");
            } catch(e) {}
            try { window.localStorage.setItem(VDE_DEFAULT_OFF_FLAG, "1"); } catch(e) {}
        } catch(e) {
            console.error("[Vendroid] applyPluginDefaultOff error: " + e.message);
        }
    }

    function recoverPlugins() {
        applyPluginDefaultOff();
        stopGateSuppressedPlugins();
        try {
            const plugins = Vencord.Plugins.plugins;
            const total = Object.keys(plugins).length;
            const enabled = Object.values(plugins).filter(p => Vencord.Plugins.isPluginEnabled(p.name)).length;
            console.warn("[Vendroid] Plugin state: " + enabled + "/" + total + " enabled");

            // Gated session: force-enable only required plugins. An
            // enabledByDefault plugin the user disabled must stay off; this
            // pass starts directly (bypassing startAllPlugins) and writes
            // enabled:true, outside the gate's no-settings-writes contract.
            const gated = !!window.VENCORD_USER_PLUGINS_DISABLED;
            const disabledRequired = Object.values(plugins).filter(p =>
                (p.required || (p.enabledByDefault && !gated)) &&
                !Vencord.Plugins.isPluginEnabled(p.name)
            );
            console.warn("[Vendroid] " + disabledRequired.length + " required/default plugins are disabled");
            let successCount = 0;
            if (disabledRequired.length > 0) {
                for (const p of disabledRequired) {
                    try {
                        if (!Vencord.Settings.plugins[p.name]) {
                            Vencord.Settings.plugins[p.name] = { enabled: true };
                        } else {
                            Vencord.Settings.plugins[p.name].enabled = true;
                        }
                        if (!Vencord.Plugins.pluginRequiresRestart(p) && !p.started) {
                            try { Vencord.Plugins.startDependenciesRecursive(p); } catch(e) {}
                            Vencord.Plugins.startPlugin(p);
                        }
                        console.warn("[Vendroid] Enabled: " + p.name + " (now=" + Vencord.Plugins.isPluginEnabled(p.name) + ",started=" + p.started + ")");
                        successCount++;
                    } catch(e) {
                        console.error("[Vendroid] Failed to enable " + p.name + ": " + e.message);
                    }
                }
            }
            console.warn("[Vendroid] Recovery result: " + successCount + "/" + disabledRequired.length + " enabled");
            return disabledRequired.length - successCount;
        } catch(e) {
            console.error("[Vendroid] recoverPlugins error: " + e.message);
            return -1;
        }
    }

    // Belt for a missed bundle patch: the patch gates startAllPlugins at
    // download time; this stops anything it already started. It only covers
    // the WebpackReady-stage set, so a bundle-driven start after this pass
    // still slips through. The patch remains the primary gate.
    function stopGateSuppressedPlugins() {
        if (!window.VENCORD_USER_PLUGINS_DISABLED) return;
        try {
            var stopped = 0;
            Object.values(Vencord.Plugins.plugins).forEach(function(p) {
                if (p.started && !p.required && !p.isDependency) {
                    try {
                        Vencord.Plugins.stopPlugin(p);
                        stopped++;
                    } catch(e) {}
                }
            });
            if (stopped) {
                console.warn("[Vendroid] User plugins gate: stopped " + stopped + " running plugin(s)");
            }
        } catch(e) {
            console.error("[Vendroid] User plugins gate failed: " + e.message);
        }
    }

    function tryStartPluginsStage() {
        if (window.VENCORD_USER_PLUGINS_DISABLED) {
            console.warn("[Vendroid] startAllPlugins skipped: user plugins disabled for this session");
            return;
        }
        try {
            var stages = {};
            Object.values(Vencord.Plugins.plugins).forEach(function(p) {
                if (p.startAt !== undefined) {
                    stages[p.startAt] = (stages[p.startAt] || 0) + 1;
                }
            });
            console.warn("[Vendroid] Plugin startAt distribution: " + JSON.stringify(stages));

            var keys = Object.keys(stages);
            for (var i = 0; i < keys.length; i++) {
                try {
                    Vencord.Plugins.startAllPlugins(keys[i]);
                    console.warn("[Vendroid] Called startAllPlugins(" + JSON.stringify(keys[i]) + ")");
                } catch(e) {
                    console.error("[Vendroid] startAllPlugins(" + JSON.stringify(keys[i]) + ") failed: " + e.message);
                }
            }
        } catch(e) {
            console.error("[Vendroid] tryStartPluginsStage error: " + e.message);
        }
    }

    function extractWebpackRequire() {
        if (_vendroidCapturedWreq) return _vendroidCapturedWreq;

        if (_vendroidJsonpCallback) {
            vendroidTryCaptureWreq();
            if (_vendroidCapturedWreq) return _vendroidCapturedWreq;
        }

        if (tryPushFakeChunkDirectly()) {
            return _vendroidCapturedWreq;
        }

        try {
            var searchTargets = [window, self];
            for (var t = 0; t < searchTargets.length; t++) {
                var obj = searchTargets[t];
                var keys;
                try { keys = Object.getOwnPropertyNames(obj); } catch(e) { continue; }
                for (var k = 0; k < keys.length && k < 200; k++) {
                    try {
                        var val = obj[keys[k]];
                        if (typeof val === "function" && val.c && typeof val.c === "object" &&
                            (val.m !== undefined || val.d !== undefined)) {
                            console.warn("[Vendroid] Found __webpack_require__ candidate at " + keys[k]);
                            return val;
                        }
                    } catch(e) {}
                }
            }
        } catch(e) {}

        try {
            var chunkArr = window.webpackChunkdiscord_app || _vendroidChunkArr;
            if (chunkArr && chunkArr.length > 0) {
                for (var i = 0; i < chunkArr.length && i < 5; i++) {
                    var entry = chunkArr[i];
                    if (!Array.isArray(entry) || entry.length < 2) continue;
                    var modules = entry[1];
                    if (!modules || typeof modules !== "object") continue;
                    var mkeys = Object.keys(modules);
                    for (var j = 0; j < mkeys.length && j < 5; j++) {
                        var factory = modules[mkeys[j]];
                        if (typeof factory !== "function") continue;
                        try {
                            var captured = null;
                            var fakeModule = { exports: {}, id: mkeys[j], loaded: false };
                            factory(fakeModule, fakeModule.exports, function(modId) {
                                if (!captured && typeof modId === "function" && modId.c) {
                                    captured = modId;
                                }
                                return {};
                            });
                            if (captured && typeof captured === "function" && captured.c) {
                                console.warn("[Vendroid] Extracted __webpack_require__ from chunk " + i + " module " + mkeys[j]);
                                return captured;
                            }
                        } catch(e) {}
                    }
                }
            }
        } catch(e) {
            console.error("[Vendroid] extractWebpackRequire chunk scan error: " + e.message);
        }
        return null;
    }

    function tryInitWebpack() {
        if (Vencord.Webpack.wreq) {
            console.warn("[Vendroid] wreq already set, skipping manual init");
            return true;
        }

        if (_vendroidCapturedWreq) {
            // Registry floor: see VENDROID_WREQ_MIN_MODULES. Falling through
            // would not help, since extractWebpackRequire() returns the same
            // captured wreq; the poll just retries until MAX_INIT_ATTEMPTS.
            var mLen = 0;
            try { mLen = _vendroidCapturedWreq.m ? Object.keys(_vendroidCapturedWreq.m).length : 0; } catch(e) {}
            if (mLen < VENDROID_WREQ_MIN_MODULES) {
                if (!_vendroidWreqFloorLogged) {
                    _vendroidWreqFloorLogged = true;
                    console.warn("[Vendroid] Captured wreq held back: module registry has " + mLen + " entries (waiting for " + VENDROID_WREQ_MIN_MODULES + ")");
                }
                return false;
            }
            try {
                if (typeof Vencord.Webpack._initWebpack === "function") {
                    Vencord.Webpack._initWebpack(_vendroidCapturedWreq);
                    console.warn("[Vendroid] _initWebpack called from captured wreq, wreq=" + typeof Vencord.Webpack.wreq + " modules=" + mLen);
                    if (Vencord.Webpack.wreq) return true;
                }
            } catch(e) {
                console.error("[Vendroid] _initWebpack from captured wreq failed: " + e.message);
            }
        }

        var wreq = extractWebpackRequire();
        if (wreq && typeof Vencord.Webpack._initWebpack === "function") {
            try {
                Vencord.Webpack._initWebpack(wreq);
                console.warn("[Vendroid] _initWebpack called, wreq=" + typeof Vencord.Webpack.wreq + " cache=" + typeof Vencord.Webpack.cache);
                if (Vencord.Webpack.wreq) return true;
            } catch(e) {
                console.error("[Vendroid] _initWebpack failed: " + e.message);
            }
        }
        return false;
    }

    // Last-resort webpack lookup for sessions where Vencord's helpers are
    // unusable (bundle eval failed, _initWebpack never ran). Reads only the
    // captured require's module cache (wreq.c); never executes factories.
    // Hits are memoized, misses are not, since a module may load late.
    var _vendroidRawFindCache = Object.create(null);

    function vendroidRawWreq() {
        if (_vendroidCapturedWreq) return _vendroidCapturedWreq;
        try {
            if (typeof Vencord !== "undefined" && Vencord.Webpack && Vencord.Webpack.wreq) {
                return Vencord.Webpack.wreq;
            }
        } catch (e) {}
        return null;
    }

    function rawFindModule(cacheKey, predicate) {
        var hit = _vendroidRawFindCache[cacheKey];
        if (hit) return hit;
        var wreq = vendroidRawWreq();
        if (!wreq || !wreq.c) return null;
        var ids;
        try { ids = Object.keys(wreq.c); } catch (e) { return null; }
        for (var i = 0; i < ids.length; i++) {
            var exp;
            try {
                var mod = wreq.c[ids[i]];
                exp = mod ? mod.exports : null;
            } catch (e) { continue; }
            if (exp === null || exp === undefined) continue;
            if (typeof exp !== "object" && typeof exp !== "function") continue;
            var ok = false;
            try { ok = !!predicate(exp); } catch (e) { ok = false; }
            if (!ok) continue;
            _vendroidRawFindCache[cacheKey] = exp;
            return exp;
        }
        return null;
    }

    let cachedFluxDispatcher = null;

    function findFluxDispatcher() {
        if (cachedFluxDispatcher) return cachedFluxDispatcher;
        try {
            var fd = Vencord.Webpack.Common?.FluxDispatcher;
            if (fd && typeof fd === "object" && fd.dispatch && fd.subscribe) { cachedFluxDispatcher = fd; return fd; }
        } catch(e) {}
        try {
            var fd2 = Vencord.Webpack.findByProps("dispatch", "subscribe");
            if (fd2 && typeof fd2 === "object" && fd2.subscribe) { cachedFluxDispatcher = fd2; return fd2; }
        } catch(e) {}
        try {
            var fd3 = Vencord.Webpack.find(function(m) {
                return typeof m === "object" && m !== null && m.dispatch && m.subscribe;
            });
            if (fd3 && typeof fd3 === "object" && fd3.subscribe) { cachedFluxDispatcher = fd3; return fd3; }
        } catch(e) {}
        // Vencord webpack helpers unavailable; use the app's own capture.
        var fd4 = rawFindModule("fluxDispatcher", function(m) {
            return typeof m.dispatch === "function" && typeof m.subscribe === "function";
        });
        if (fd4) { cachedFluxDispatcher = fd4; return fd4; }
        return null;
    }

    // One shared poll for every settings-tab row. The previous four
    // independent 750ms intervals each ran a full-document querySelectorAll
    // (~5 scans/sec for the page lifetime, even with Settings never opened).
    // One scan per tick feeds all injectors; ticks are skipped while
    // backgrounded.
    var _vendroidSettingsPoll = null;

    // The desktopMode value the running WebView was built with, captured
    // once per session. The UA is fixed at WebView install, so a persisted
    // value that differs from this one means a restart is pending.
    // Re-reading at injection time would pick up a post-toggle value after
    // a settings-tab remount and hide that pending restart. undefined means
    // not captured yet; false is a valid captured value.
    var _vdeDesktopModeAtBoot;
    function captureDesktopModeAtBootIfNeeded() {
        if (typeof _vdeDesktopModeAtBoot !== 'undefined') return;
        // The bridge and its token wrapper are installed before this script
        // runs. If either is missing, retry on the next poller tick.
        if (typeof VencordMobileNative === 'undefined') return;
        try {
            _vdeDesktopModeAtBoot = !!VencordMobileNative.getBool('desktopMode', false);
        } catch(e) {
            console.error('[Vendroid] desktopMode boot capture failed: ' + e.message);
        }
    }

    // The discordBranch value the running session opened with, captured once
    // per session. The branch is fixed at WebView install, so a persisted
    // value that differs means a restart is pending. Same capture contract
    // as _vdeDesktopModeAtBoot: undefined means not captured yet, retry per
    // poller tick.
    var _vdeDiscordBranchAtBoot;
    // Anything but the two non-default tokens reads as stable, matching the
    // native DiscordBranch.fromPrefValue fallback.
    function normalizeDiscordBranch(value) {
        return (value === 'ptb' || value === 'canary') ? value : 'stable';
    }
    function captureDiscordBranchAtBootIfNeeded() {
        if (typeof _vdeDiscordBranchAtBoot !== 'undefined') return;
        // The bridge and its token wrapper are installed before this script
        // runs. If either is missing, retry on the next poller tick.
        if (typeof VencordMobileNative === 'undefined') return;
        try {
            _vdeDiscordBranchAtBoot = normalizeDiscordBranch(
                VencordMobileNative.getString('discordBranch', 'stable'));
        } catch(e) {
            console.error('[Vendroid] discordBranch boot capture failed: ' + e.message);
        }
    }

    function setupSettingsRows() {
        if (_vendroidSettingsPoll) return;
        try {
            captureDesktopModeAtBootIfNeeded();
            captureDiscordBranchAtBootIfNeeded();
            _vendroidSettingsPoll = setInterval(injectSettingsRowsIfMissing, 750);
            injectSettingsRowsIfMissing(); // immediate try in case tab is open
        } catch(e) {
            console.error('[Vendroid] setupSettingsRows error: ' + e.message);
        }
    }

