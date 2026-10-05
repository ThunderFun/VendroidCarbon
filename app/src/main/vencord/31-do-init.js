    function doInit() {
        if (initialized) return;
        initialized = true;
        console.warn("[Vendroid] Initializing (webpack ready)");

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
        // Ported plugin features. Order matters for applyVendroidPluginCss:
        // setupSlateOverride above captures the pre-override isAndroidWeb
        // result when the platform module is found, so the mstyle gate can
        // decide here. registerVendroidSettingsTab must run before the
        // settings-row poller's first tick so the tab exists.
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
        console.warn("[Vendroid] Standalone init (" + reason + "), rawWreq=" + (_vendroidCapturedWreq ? "yes" : "no"));
        try { logVdePatchStats("standalone"); } catch (e) {}
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

    // Force Slate on Android to restore the command browser. Discord disables
    // Slate when isAndroidWeb() is true; we override the platform module so
    // Slate is used, and setupSlateInputFix handles the resulting input issues.
    var _vendroidSlateOverrideDone = false;
    var _vendroidSlateOverrideRetries = 0;
    var _vendroidSlateOverrideGaveUp = false;

