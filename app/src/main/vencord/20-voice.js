    function vendroidVoicePrereqsOk() {
        return typeof window.AudioContext === "function" &&
            typeof window.RTCPeerConnection === "function" &&
            !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
    }

    // First export of a webpack namespace matching `pred`. Webpack re-minifies
    // export letters every build ("A", "WI", "hB", ...), so callers must key on
    // value shape, not names. Harmony namespaces expose bindings as accessors on
    // the namespace object or its prototype, so walk both.
    function vendroidFindExport(mod, pred) {
        if (!mod) return null;
        var keys = [];
        for (var o = mod; o && o !== Object.prototype; o = Object.getPrototypeOf(o)) {
            var ks;
            try { ks = Object.getOwnPropertyNames(o); } catch (e) { break; }
            for (var i = 0; i < ks.length; i++) {
                if (keys.indexOf(ks[i]) < 0) keys.push(ks[i]);
            }
        }
        for (var j = 0; j < keys.length; j++) {
            var k = keys[j];
            if (k === "__esModule") continue;
            var v;
            try { v = mod[k]; } catch (e) { continue; }
            if (pred(v, k)) return v;
        }
        return null;
    }

    // A media-engine class: the picker calls static supported() to choose it
    // and the store drives the instance methods. Only static supported() is
    // required, since the picker reads it; other methods may be renamed. The
    // DUMMY engine also matches this shape but lives in another module, so
    // discovery is scoped by findModuleByCode's source signature.
    function vendroidIsEngineClass(v) {
        if (typeof v !== "function" || typeof v.supported !== "function" || !v.prototype) return false;
        var proto = v.prototype;
        return typeof proto.connect === "function" ||
            typeof proto.setInputVolume === "function" ||
            typeof proto.getAudioInputDevices === "function" ||
            typeof proto.eachConnection === "function";
    }

    // Bounded retry for the resolvers below. A future build may load the store
    // lazily or move the engine module, so one miss must not disable voice
    // permanently.
    function vendroidVoiceRetry(reason) {
        if (_vendroidVoiceSetupScheduled) return;
        if (_vendroidVoiceSetupRetries++ >= VENDROID_VOICE_MAX_RETRIES) {
            console.error("[Vendroid] Voice: giving up after " + VENDROID_VOICE_MAX_RETRIES +
                " retries (" + reason + ")");
            _vendroidVoiceSetupDone = true;
            return;
        }
        _vendroidVoiceSetupScheduled = true;
        setTimeout(function() {
            _vendroidVoiceSetupScheduled = false;
            setupVoiceSupport();
        }, 250);
    }

    // Resolves the WebRTC engine class by source signature, then pulls the
    // class out of whatever export letter it was minified to. All three patterns
    // are source strings, never export letters: the class name plus two unique
    // call-site/log strings from the same module.
    function vendroidResolveWebrtcEngine() {
        var ns = null;
        try {
            ns = findModuleByCode([
                "MediaEngineWebRTC",
                "MediaEngineWebRTC.handleActiveSinksChange",
                "WebRTC is not supported on"
            ]);
        } catch (e) {
            console.error("[Vendroid] Voice: WebRTC module lookup failed: " + e.message);
            return null;
        }
        return vendroidFindExport(ns, vendroidIsEngineClass);
    }

    // Forces the WebRTC class's static supported() true when the real capture
    // prerequisites exist. Idempotent: a retry must not stack wrappers.
    function vendroidPatchEngineSupported(webrtcClass) {
        if (!webrtcClass || webrtcClass.__vendroidSupportedPatched) return true;
        var origSupported = webrtcClass.supported;
        var patched = function() {
            if (vendroidVoicePrereqsOk()) return true;
            return origSupported.apply(this, arguments);
        };
        try {
            Object.defineProperty(webrtcClass, "supported", {
                configurable: true, writable: true, value: patched
            });
        } catch (e) {
            try { webrtcClass.supported = patched; } catch (e2) {}
        }
        if (webrtcClass.supported !== patched) {
            console.error("[Vendroid] Voice: WebRTC supported() patch did not take");
            return false;
        }
        try {
            Object.defineProperty(webrtcClass, "__vendroidSupportedPatched",
                { value: true, configurable: true });
        } catch (e) {}
        console.warn("[Vendroid] Voice: WebRTC supported() patched");
        return true;
    }

    // Copies the live WebRTC engine onto the object the store already holds
    // (`to`), bound to that engine. EventEmitter methods stay on the original
    // object so the store's subscriptions survive, and emitted events are
    // forwarded so store state (devices, mute, speaking) keeps updating.
    function vendroidInstallEngineFallback(store, webrtcClass) {
        var to = store.getMediaEngine();
        if (!to || typeof to !== "object") return false;
        if (to.__vendroidWebrtcInstalled) {
            var already = false;
            try { already = to.supported() === true; } catch (e) {}
            return already;
        }
        var webrtc;
        try {
            // The picker itself does `new (engineFor(kind))` with no args, so
            // the constructor is arg-free. Building from the class drops the
            // dependency on the picker's per-build export letters.
            webrtc = new webrtcClass();
        } catch (e) {
            console.error("[Vendroid] Voice: new WebRTC engine failed: " + e.message);
            return false;
        }
        if (!webrtc || typeof webrtc !== "object") return false;

        var KEEP = {
            constructor: 1, on: 1, off: 1, addListener: 1, removeListener: 1, once: 1,
            removeAllListeners: 1, emit: 1, listeners: 1, listenerCount: 1,
            eventNames: 1, setMaxListeners: 1, getMaxListeners: 1,
            prependListener: 1, prependOnceListener: 1, rawListeners: 1
        };
        var proto = Object.getPrototypeOf(webrtc);
        var names = Object.getOwnPropertyNames(proto);
        var copied = 0;
        for (var i = 0; i < names.length; i++) {
            var key = names[i];
            if (KEEP[key]) continue;
            var desc = Object.getOwnPropertyDescriptor(proto, key);
            if (!desc) continue;
            try {
                if (desc.get || desc.set) {
                    (function(k, d) {
                        Object.defineProperty(to, k, {
                            configurable: true, enumerable: true,
                            get: d.get ? function() { return d.get.call(webrtc); } : undefined,
                            set: d.set ? function(v) { d.set.call(webrtc, v); } : undefined
                        });
                    })(key, desc);
                } else if (typeof desc.value === "function") {
                    (function(k, fn) {
                        Object.defineProperty(to, k, {
                            configurable: true, enumerable: true, writable: true,
                            value: function() { return fn.apply(webrtc, arguments); }
                        });
                    })(key, desc.value);
                }
                copied++;
            } catch (e) {}
        }

        try {
            var baseEmit = Object.getPrototypeOf(proto).emit;
            if (typeof baseEmit === "function") {
                webrtc.emit = function(name) {
                    var args = Array.prototype.slice.call(arguments, 1);
                    try { to.emit.apply(to, [name].concat(args)); } catch (e) {}
                    return baseEmit.apply(webrtc, arguments);
                };
            }
        } catch (e) {}

        // The engine starts device enumeration only once something listens for
        // DeviceChange (handleNewListener). The store's listener is on the old
        // engine object, so add a no-op here; the emit wrapper then forwards the
        // populated device lists to the store.
        try { webrtc.on("devicechange", function() {}); } catch (e) {}

        // The store's isSupported() calls `to.supported()`. The copy loop
        // already copies the prototype method, but pin an explicit instance
        // version so a build that defines supported() only on the constructor
        // still yields a supported store engine.
        try {
            Object.defineProperty(to, "supported", {
                configurable: true, enumerable: true, writable: true,
                value: function() {
                    if (vendroidVoicePrereqsOk()) return true;
                    try { return webrtcClass.supported.apply(webrtcClass, arguments); }
                    catch (e) { return false; }
                }
            });
        } catch (e) {}

        var ok = false;
        try { ok = to.supported() === true; } catch (e) {}
        if (ok) {
            try {
                Object.defineProperty(to, "__vendroidWebrtcInstalled",
                    { value: true, configurable: true });
            } catch (e) {}
            console.warn("[Vendroid] Voice: swapped store engine to WebRTC (methods copied=" + copied + ")");
        } else {
            console.error("[Vendroid] Voice: engine swap did not take (copied=" + copied + ")");
        }
        return ok;
    }

    function setupVoiceSupport() {
        if (_vendroidVoiceSetupDone) return;
        try {
            if (typeof Vencord === "undefined" || !Vencord.Webpack) {
                vendroidVoiceRetry("Vencord not ready");
                return;
            }

            // 1. Best-effort: flip the capability flag the picker reads. It
            //    may be a non-configurable webpack getter, in which case this
            //    logs and moves on; step 2 is the one that must succeed.
            //    Prefer the known minified prop set, then a source signature
            //    if the letters changed.
            try {
                var caps = null;
                try { caps = vendroidFindByProps("g7", "fA", "zU"); } catch (e0) {}
                if (!caps || caps.Hz === undefined) {
                    try { caps = findModuleByCode(['ua.indexOf("OculusBrowser")']); } catch (e0) {}
                }
                if (!caps) {
                    console.warn("[Vendroid] Voice: capability module not found");
                } else if (caps.Hz === true) {
                    console.warn("[Vendroid] Voice: Hz already true");
                } else {
                    var oldHz = caps.Hz;
                    try {
                        Object.defineProperty(caps, "Hz", {
                            configurable: true, enumerable: true,
                            get: function() { return true; }
                        });
                        console.warn("[Vendroid] Voice: Hz " + oldHz + " -> true");
                    } catch (e1) {
                        // Hz can be a non-configurable getter on this export.
                        // Try the single-prop lookup for another owner before
                        // giving up.
                        var patched = false;
                        try {
                            var proxy = vendroidFindByProps("Hz");
                            if (proxy && proxy !== caps && proxy.Hz !== true) {
                                Object.defineProperty(proxy, "Hz", {
                                    configurable: true, enumerable: true,
                                    get: function() { return true; }
                                });
                                patched = true;
                            }
                        } catch (e2) {}
                        if (patched) {
                            console.warn("[Vendroid] Voice: Hz " + oldHz + " -> true (proxy)");
                        } else {
                            console.warn("[Vendroid] Voice: Hz not redefinable (" + e1.message + ")");
                        }
                    }
                }
            } catch (e) {
                console.error("[Vendroid] Voice: Hz probe failed: " + e.message);
            }

            // 2. Resolve the WebRTC engine class by code signature and force
            //    it to report supported when the capture prerequisites exist.
            //    Done before the store is resolved so a lazily evaluated store
            //    binds WebRTC naturally instead of needing the fallback swap.
            //    This also fixes the picker, which consults supported().
            var webrtcClass = vendroidResolveWebrtcEngine();
            if (webrtcClass) {
                if (!vendroidPatchEngineSupported(webrtcClass)) {
                    vendroidVoiceRetry("WebRTC supported() patch did not take");
                    return;
                }
            } else {
                console.warn("[Vendroid] Voice: WebRTC engine class not found");
            }

            // 3. Resolve the store by stable method names (property names are
            //    never minified). If it already bound the DUMMY engine, put a
            //    live WebRTC engine behind it so join/connect work now.
            var store = null;
            try { store = vendroidFindByProps("isSupported", "getMediaEngine", "getInputDevices"); } catch (e) {}
            if (!store || typeof store.getMediaEngine !== "function") {
                try { store = vendroidFindByProps("isSupported", "getMediaEngine"); } catch (e) {}
            }
            if (!store || typeof store.getMediaEngine !== "function" || typeof store.isSupported !== "function") {
                console.warn("[Vendroid] Voice: media engine store not found");
                vendroidVoiceRetry("store not found");
                return;
            }
            var supported = false;
            try { supported = store.isSupported() === true; } catch (e) { supported = false; }
            console.warn("[Vendroid] Voice: store isSupported=" + supported);
            if (supported) {
                _vendroidVoiceSetupDone = true;
                return;
            }
            // Voice is genuinely unsupported. Only now does a missing engine
            // class matter: without it there is nothing to swap in.
            if (!webrtcClass) {
                vendroidVoiceRetry("WebRTC engine class not found");
                return;
            }
            if (vendroidInstallEngineFallback(store, webrtcClass)) {
                _vendroidVoiceSetupDone = true;
            } else {
                vendroidVoiceRetry("engine swap did not take");
            }
        } catch (e) {
            console.error("[Vendroid] setupVoiceSupport error: " + e.message);
            vendroidVoiceRetry("exception: " + e.message);
        }
    }

    // Fallback for the evaluate injection path. The prelude shim
    // (VENCORD_PRELUDE_JS) installs setSinkId before Discord's deferred
    // bundles evaluate, which clears the output-device warning. Here
    // injectVencordAttempt waits for readyState past "loading", so the engine
    // can capture its capability constant before the shim exists; wrapping
    // the store's supports() for AUDIO_OUTPUT_DEVICE clears the warning
    // anyway. Routing stays with the OS.
    var _vendroidOutputSetupDone = false;
    var _vendroidOutputSetupRetries = 0;
    var _vendroidOutputSetupScheduled = false;
    // 60 retries x 250ms = 15s, matching the voice patch's lazy-load budget.
    var VENDROID_OUTPUT_MAX_RETRIES = 60;

    function vendroidOutputShimActive() {
        try {
            return typeof HTMLMediaElement !== "undefined" &&
                !!HTMLMediaElement.prototype &&
                ("setSinkId" in HTMLMediaElement.prototype);
        } catch (e) {
            return false;
        }
    }

    // Bounded retry so a late store or prelude does not leave the warning up
    // for the session.
    function vendroidOutputRetry(reason) {
        if (_vendroidOutputSetupScheduled) return;
        if (_vendroidOutputSetupRetries++ >= VENDROID_OUTPUT_MAX_RETRIES) {
            console.error("[Vendroid] Output: giving up after " + VENDROID_OUTPUT_MAX_RETRIES +
                " retries (" + reason + ")");
            _vendroidOutputSetupDone = true;
            return;
        }
        _vendroidOutputSetupScheduled = true;
        setTimeout(function() {
            _vendroidOutputSetupScheduled = false;
            setupOutputDeviceSupport();
        }, 250);
    }

