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

    // Force Slate on Android to restore the command browser. Discord disables
    // Slate when isAndroidWeb() is true; we override the platform module so
    // Slate is used, and setupSlateInputFix handles the resulting input issues.
    var _vendroidSlateOverrideDone = false;
    var _vendroidSlateOverrideRetries = 0;

