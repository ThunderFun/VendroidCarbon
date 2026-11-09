    // Diagnostic snapshot of source-patch and runtime state. Boot
    // verification reads window.__vendroidPatchStatus after this runs.
    function vendroidRunPatchProbe(source) {
        try {
            var status = window.__vendroidPatchStatus || {};
            status.build = vendroidDiscordBuild();
            status.stage = source;
            status.doInit = source === "init";

            var gif = status.gif || (status.gif = {});
            var platform = null;
            try { platform = vendroidFindByProps("Fr", "Ct", "KY", "v1"); } catch (e) {}
            gif.platform = !!platform;
            if (platform) {
                gif.fr = platform.Fr;
                try {
                    var fd = Object.getOwnPropertyDescriptor(platform, "Fr");
                    gif.frDescriptor = fd ? {
                        configurable: !!fd.configurable,
                        writable: !!fd.writable,
                        get: typeof fd.get,
                        value: typeof fd.value
                    } : null;
                } catch (e) {}
            }
            var gifStore = null;
            try { gifStore = vendroidFindByProps("r$", "ed", "v8", "RQ"); } catch (e) {}
            if (!gifStore) {
                try { gifStore = findModuleByCode(["expression-picker-last-active-view"]); } catch (e) {}
            }
            gif.store = !!(gifStore && gifStore.r$);
            var gifViews = null;
            try { gifViews = vendroidFindByProps("kx", "VQ", "wp"); } catch (e) {}
            if (!gifViews) {
                try { gifViews = findModuleByCode(["expression-picker-chat-input-button"]); } catch (e) {}
            }
            gif.views = !!(gifViews && gifViews.kx);
            gif.overlayModule = _vdePatchStats.modules && _vdePatchStats.modules.gifTab || null;

            var voice = status.voice || (status.voice = {});
            var caps = null;
            try { caps = vendroidFindByProps("g7", "fA", "zU"); } catch (e) {}
            if (!caps) {
                try { caps = findModuleByCode(['ua.indexOf("OculusBrowser")']); } catch (e) {}
            }
            voice.hz = caps ? caps.Hz : null;
            if (caps) {
                try {
                    var hd = Object.getOwnPropertyDescriptor(caps, "Hz");
                    voice.hzDescriptor = hd ? {
                        configurable: !!hd.configurable,
                        writable: !!hd.writable,
                        get: typeof hd.get,
                        value: typeof hd.value
                    } : null;
                } catch (e) {}
            }
            var webrtcClass = null;
            try { webrtcClass = vendroidResolveWebrtcEngine(); } catch (e) {}
            voice.engineClass = webrtcClass ? webrtcClass.name : null;
            var store = null;
            try {
                store = vendroidFindByProps("isSupported", "getMediaEngine", "getInputDevices");
            } catch (e) {}
            voice.store = !!store;
            if (store) {
                try { voice.supported = store.isSupported() === true; }
                catch (e) { voice.supported = false; }
                var engine = null;
                try { engine = store.getMediaEngine(); } catch (e) {}
                voice.engine = engine && engine.constructor ? engine.constructor.name : null;
                if (engine && typeof engine.getSupportedSecureFramesProtocolVersion === "function") {
                    try { voice.dave = engine.getSupportedSecureFramesProtocolVersion(); }
                    catch (e) { voice.daveError = e.message; }
                }
            }

            try {
                var wp = { cacheType: "none", cacheKeys: 0, wreqKeys: 0 };
                if (typeof Vencord !== "undefined" && Vencord.Webpack) {
                    wp.cacheType = typeof Vencord.Webpack.cache;
                    if (Vencord.Webpack.cache && typeof Vencord.Webpack.cache === "object") {
                        wp.cacheKeys = Object.keys(Vencord.Webpack.cache).length;
                    }
                    if (Vencord.Webpack.wreq && Vencord.Webpack.wreq.c) {
                        wp.wreqKeys = Object.keys(Vencord.Webpack.wreq.c).length;
                    }
                }
                status.webpack = wp;
            } catch (e) {}

            status.patchStats = {
                chunks: _vdePatchStats.chunks,
                scanned: _vdePatchStats.scanned,
                applied: _vdePatchStats.applied,
                missed: _vdePatchStats.missed,
                modules: _vdePatchStats.modules,
                retired: _vdePatchStats.retired,
                legacy: !!_vdePatchStats.legacy
            };
            window.__vendroidPatchStatus = status;
            console.warn("[Vendroid] Patch probe (" + source + "): " + JSON.stringify(status));
        } catch (e) {
            console.error("[Vendroid] Patch probe failed: " + e.message);
        }
    }

    function doInit() {
        if (initialized) return;
        initialized = true;
        console.warn("[Vendroid] Initializing (webpack ready)");
        // Catch factories registered before the push hook was installed.
        try { vdePatchRegisteredFactories("do-init"); } catch (e) {}

        var fd = findFluxDispatcher();
        if (fd) {
            try {
                fd.subscribe("MOBILE_WEB_SIDEBAR_OPEN", () => { isSidebarOpen = true; });
                fd.subscribe("MOBILE_WEB_SIDEBAR_CLOSE", () => { isSidebarOpen = false; });
                console.warn("[Vendroid] FluxDispatcher subscribed OK");
            } catch(e) {
                console.error("[Vendroid] FluxDispatcher subscribe FAILED: " + e.message);
            }
        } else {
            console.error("[Vendroid] FluxDispatcher not available!");
        }

        recoverPlugins();
        tryStartPluginsStage();
        setupSlateOverride();
        setupSlateAutocorrect();
        setupSlateInputFix();
        setupTextCommandDispatcher();
        setupGifPickerButton();
        setupNativeSearch();
        setupSettingsRows();
        setupVoiceSupport();
        setupOutputDeviceSupport();
        vendroidRunPatchProbe("init");
        // setupSlateOverride must run before applyVendroidPluginCss so the
        // mstyle gate sees the real platform result. Register the settings tab
        // before the row poller's first tick.
        registerVendroidSettingsTab();
        applyVendroidPluginCss();
        setupQuickCssBridge();
        setupGestures();
        setupSupportWarnings();
        logVdePatchStats("init ready");

        setTimeout(() => {
            try { VencordMobileNative.dismissLoadingScreen(); } catch(e) {}
        }, 800);
    }

    // Webpack-independent subset for sessions where Vencord never boots.
    // Called only from the init-timeout branch in 90-init.js, and does not
    // set `initialized`: that flag marks the full doInit path.
    var _vendroidStandaloneInitDone = false;
    function doInitStandalone(reason) {
        if (_vendroidStandaloneInitDone) return;
        _vendroidStandaloneInitDone = true;
        // extractWebpackRequire() is otherwise reachable only through
        // tryInitWebpack(), which needs Vencord.Webpack. Extract here so a
        // no-Vencord session still gets a captured wreq for raw lookups.
        if (!_vendroidCapturedWreq) {
            try {
                var captured = extractWebpackRequire();
                if (captured) _vendroidCapturedWreq = captured;
            } catch (e) {}
        }
        try { vdePatchRegisteredFactories("standalone"); } catch (e) {}
        console.warn("[Vendroid] Standalone init (" + reason + "), rawWreq=" + (_vendroidCapturedWreq ? "yes" : "no"));
        try { logVdePatchStats("standalone"); } catch (e) {}
        try { vendroidRunPatchProbe("standalone"); } catch (e) {}
        // Isolated: a CSS failure must not cost gestures, and vice versa.
        try { applyVendroidPluginCss(); } catch (e) {
            console.error("[Vendroid] Standalone CSS failed: " + (e && e.message ? e.message : e));
        }
        try { setupGestures(); } catch (e) {
            console.error("[Vendroid] Standalone gestures failed: " + (e && e.message ? e.message : e));
        }
        // Native splash timeout is 30s; the init timeout is 15s, so dismiss
        // here or the splash would linger for another 15s.
        setTimeout(() => {
            try { VencordMobileNative.dismissLoadingScreen(); } catch (e) {}
        }, 0);
    }

    // Override the Android Slate gate to restore the command browser.
    // setupSlateInputFix handles the input differences.
    var _vendroidSlateOverrideDone = false;
    var _vendroidSlateOverrideRetries = 0;
    var _vendroidSlateOverrideGaveUp = false;

