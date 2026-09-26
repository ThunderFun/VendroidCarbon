    // Wraps the store's supports() rather than the engine's. The voice patch
    // copies WebRTC methods over the engine object, which would overwrite an
    // engine-level wrapper. The settings UI calls store.supports directly, so
    // the wrapper covers the function the warning reads and survives the
    // engine swap.
    function setupOutputDeviceSupport() {
        if (_vendroidOutputSetupDone) return;
        try {
            if (typeof Vencord === "undefined" || !Vencord.Webpack || !Vencord.Webpack.findByProps) {
                vendroidOutputRetry("Vencord not ready");
                return;
            }
            var store = null;
            try { store = Vencord.Webpack.findByProps("isSupported", "getMediaEngine", "getInputDevices"); } catch (e) {}
            if (!store || typeof store.supports !== "function") {
                try { store = Vencord.Webpack.findByProps("isSupported", "getMediaEngine"); } catch (e) {}
            }
            if (!store || typeof store.supports !== "function") {
                vendroidOutputRetry("media engine store not found");
                return;
            }
            var supported = false;
            try { supported = store.supports("AUDIO_OUTPUT_DEVICE") === true; } catch (e) {}
            if (supported) {
                console.warn("[Vendroid] Output: engine already reports AUDIO_OUTPUT_DEVICE (shim=" +
                    vendroidOutputShimActive() + "); prelude won");
                _vendroidOutputSetupDone = true;
                return;
            }
            if (!vendroidOutputShimActive()) {
                // The prelude runs before the runtime on every injection path.
                // Wait for it rather than faking support.
                vendroidOutputRetry("setSinkId shim not installed");
                return;
            }
            if (!store.__vendroidOutputSupportsPatched) {
                var origSupports = store.supports;
                var patched = function(kind) {
                    if (kind === "AUDIO_OUTPUT_DEVICE" && vendroidOutputShimActive()) return true;
                    return origSupports.apply(this, arguments);
                };
                try {
                    Object.defineProperty(store, "supports", {
                        configurable: true, writable: true, value: patched
                    });
                } catch (e) {
                    try { store.supports = patched; } catch (e2) {}
                }
                if (store.supports !== patched) {
                    vendroidOutputRetry("supports wrapper did not take");
                    return;
                }
                try {
                    Object.defineProperty(store, "__vendroidOutputSupportsPatched",
                        { value: true, configurable: true });
                } catch (e) {}
                console.warn("[Vendroid] Output: wrapped store.supports for AUDIO_OUTPUT_DEVICE");
            }
            // Re-render the settings UI without a reload. The store already
            // holds Default (the engine synthesizes it when the gate is off),
            // so one synthetic devicechange is enough.
            try {
                if (navigator.mediaDevices && typeof navigator.mediaDevices.dispatchEvent === "function") {
                    navigator.mediaDevices.dispatchEvent(new Event("devicechange"));
                }
            } catch (e) {}
            var nowSupported = false;
            try { nowSupported = store.supports("AUDIO_OUTPUT_DEVICE") === true; } catch (e) {}
            if (nowSupported) {
                console.warn("[Vendroid] Output: warning gate cleared (shim=" +
                    vendroidOutputShimActive() + ")");
            } else {
                console.error("[Vendroid] Output: drift, supports wrapper did not clear the gate");
            }
            _vendroidOutputSetupDone = true;
        } catch (e) {
            console.error("[Vendroid] setupOutputDeviceSupport error: " + e.message);
            vendroidOutputRetry("exception: " + e.message);
        }
    }

