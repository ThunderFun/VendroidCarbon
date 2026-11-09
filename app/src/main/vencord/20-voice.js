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

    // Retry for lazy store or engine modules. Back off; the source patch
    // covers the normal path.
    function vendroidVoiceRetry(reason) {
        if (_vendroidVoiceSetupScheduled || _vendroidVoiceSetupDone) return;
        var attempt = _vendroidVoiceSetupRetries++;
        var delay = Math.min(
            VENDROID_VOICE_RETRY_BASE_MS * Math.pow(1.4, Math.min(attempt, 12)),
            VENDROID_VOICE_RETRY_MAX_MS
        );
        if (attempt === 0 || attempt === 5 || attempt === 20 || attempt % 50 === 0) {
            console.warn("[Vendroid] Voice: retry in " + Math.round(delay) +
                "ms (" + reason + ")");
        }
        _vendroidVoiceSetupScheduled = true;
        setTimeout(function() {
            _vendroidVoiceSetupScheduled = false;
            setupVoiceSupport();
        }, delay);
    }

    // Keep repeated misses out of the log without hiding the first few.
    function vendroidVoiceLog(message) {
        if (_vendroidVoiceSetupRetries <= 3 || _vendroidVoiceSetupRetries % 25 === 0) {
            console.warn("[Vendroid] Voice: " + message);
        }
    }

    // The first voice click retries setup when the store was not cached at
    // doInit. One subscription covers the lazy chunk.
    var _vendroidVoiceFluxHooked = false;
    function vendroidHookVoiceFlux() {
        if (_vendroidVoiceFluxHooked) return;
        var fd = findFluxDispatcher();
        if (!fd || typeof fd.subscribe !== "function") return;
        try {
            fd.subscribe("VOICE_CHANNEL_SELECT", function() {
                if (!_vendroidVoiceSetupDone) setupVoiceSupport();
            });
            _vendroidVoiceFluxHooked = true;
        } catch (e) {
            console.warn("[Vendroid] Voice: VOICE_CHANNEL_SELECT subscribe failed: " + e.message);
        }
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
            if (already) vendroidSetPatchStatus("voice", "fallback", true);
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
            prependListener: 1, prependOnceListener: 1, rawListeners: 1,
            _events: 1, _eventsCount: 1, _maxListeners: 1, domain: 1
        };
        var proto = Object.getPrototypeOf(webrtc);
        var names = Object.getOwnPropertyNames(proto);
        var copied = 0;
        var ownCopied = 0;

        // Copy one member, bound to the live engine. Fields stay shared via
        // accessors.
        function copyMember(key, desc, source) {
            try {
                if (desc.get || desc.set) {
                    Object.defineProperty(to, key, {
                        configurable: true, enumerable: true,
                        get: desc.get ? function() { return desc.get.call(source); } : undefined,
                        set: desc.set ? function(v) { desc.set.call(source, v); } : undefined
                    });
                } else if (typeof desc.value === "function") {
                    var fn = desc.value;
                    Object.defineProperty(to, key, {
                        configurable: true, enumerable: true, writable: true,
                        value: function() { return fn.apply(source, arguments); }
                    });
                } else {
                    Object.defineProperty(to, key, {
                        configurable: true, enumerable: true,
                        get: function() { return source[key]; },
                        set: function(v) { source[key] = v; }
                    });
                }
                return true;
            } catch (e) {
                return false;
            }
        }

        for (var i = 0; i < names.length; i++) {
            var key = names[i];
            if (KEEP[key]) continue;
            var desc = Object.getOwnPropertyDescriptor(proto, key);
            if (!desc) continue;
            if (copyMember(key, desc, webrtc)) copied++;
        }

        // Class fields are own properties, not prototype members. They hold
        // DAVE and secure-frames state, so a prototype-only copy can look
        // supported and fail during the join. Skip EventEmitter internals and
        // private fields.
        var ownNames = Object.getOwnPropertyNames(webrtc);
        for (var oi = 0; oi < ownNames.length; oi++) {
            var ownKey = ownNames[oi];
            if (KEEP[ownKey] || ownKey.charAt(0) === "_") continue;
            var ownDesc = Object.getOwnPropertyDescriptor(webrtc, ownKey);
            if (!ownDesc) continue;
            if (copyMember(ownKey, ownDesc, webrtc)) ownCopied++;
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

        var supported = false;
        try { supported = to.supported() === true; } catch (e) {}

        // DAVE initializes on the first connection. Its getter throws until
        // then; that is expected. Any other failure, or a missing input
        // capability, means the copy is incomplete.
        var dave = null;
        var daveOk = true;
        var daveError = null;
        try {
            if (typeof to.getSupportedSecureFramesProtocolVersion === "function") {
                dave = to.getSupportedSecureFramesProtocolVersion();
                daveOk = typeof dave === "number";
            }
        } catch (e) {
            daveError = e && e.message ? e.message : String(e);
            daveOk = /before DAVE was initialized/i.test(daveError);
        }
        var inputOk = true;
        try {
            if (typeof to.supports === "function") {
                inputOk = to.supports("AUDIO_INPUT_DEVICE") === true;
            }
        } catch (e) {
            inputOk = false;
        }

        var ok = supported && daveOk && inputOk;
        vendroidSetPatchStatus("voice", "fallback", ok);
        vendroidSetPatchStatus("voice", "dave", daveError ? "pending" : dave);
        vendroidSetPatchStatus("voice", "input", inputOk);
        if (ok) {
            try {
                Object.defineProperty(to, "__vendroidWebrtcInstalled",
                    { value: true, configurable: true });
            } catch (e) {}
            console.warn("[Vendroid] Voice: swapped store engine to WebRTC (methods copied=" +
                copied + ", own=" + ownCopied + ", dave=" + (daveError ? "pending" : String(dave)) +
                ", input=" + inputOk + ")");
        } else {
            console.error("[Vendroid] Voice: engine swap did not take (methods copied=" +
                copied + ", own=" + ownCopied + ", supported=" + supported +
                ", daveOk=" + daveOk + ", input=" + inputOk + ")");
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

            vendroidHookVoiceFlux();

            // Diagnostic only. Hz is a non-configurable export; 00-boot.js
            // patches the source.
            if (_vendroidVoiceSetupRetries === 0) {
                try {
                    var caps = null;
                    try { caps = vendroidFindByProps("g7", "fA", "zU"); } catch (e0) {}
                    if (!caps || caps.Hz === undefined) {
                        try { caps = findModuleByCode(['ua.indexOf("OculusBrowser")']); } catch (e0) {}
                    }
                    if (caps) {
                        vendroidSetPatchStatus("voice", "hz", caps.Hz);
                        console.warn("[Vendroid] Voice: Hz=" + caps.Hz +
                            " (source patch handles support)");
                    } else {
                        console.warn("[Vendroid] Voice: capability module not found");
                    }
                } catch (e) {
                    console.error("[Vendroid] Voice: Hz probe failed: " + e.message);
                }
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
                vendroidVoiceLog("WebRTC engine class not found");
            }

            // 3. Resolve the store by stable method names (property names are
            //    never minified). If it already bound the DUMMY engine, put a
            //    live WebRTC engine behind it so join/connect work now.
            var store = null;
            try { store = vendroidFindByProps("isSupported", "getMediaEngine", "getInputDevices"); } catch (e) {}
            if (!store || typeof store.getMediaEngine !== "function") {
                try { store = vendroidFindByProps("isSupported", "getMediaEngine"); } catch (e) {}
            }
            if (!store || typeof store.getMediaEngine !== "function") {
                // findByProps sees only executed modules. The store can load
                // lazily; its method shape is stable and findModuleByCode
                // force-loads it. That returns a namespace, so select the
                // store by shape.
                try {
                    var storeNs = findModuleByCode([
                        /isSupported\(\)\{return [A-Za-z_$][\w$]*\.supported\(\)\}/,
                        /getMediaEngine\(\)\{return [A-Za-z_$][\w$]*\}/
                    ]);
                    store = vendroidFindExport(storeNs, function(v) {
                        return v && typeof v === "object" &&
                            typeof v.isSupported === "function" &&
                            typeof v.getMediaEngine === "function";
                    });
                } catch (e) {}
            }
            if (!store || typeof store.getMediaEngine !== "function" || typeof store.isSupported !== "function") {
                vendroidVoiceLog("media engine store not found");
                vendroidVoiceRetry("store not found");
                return;
            }
            var supported = false;
            try { supported = store.isSupported() === true; } catch (e) { supported = false; }
            var engine = null;
            try { engine = store.getMediaEngine(); } catch (e) {}
            var engineName = engine && engine.constructor ? engine.constructor.name : "?";
            vendroidSetPatchStatus("voice", "store", "media-engine");
            vendroidSetPatchStatus("voice", "engine", engineName);
            vendroidSetPatchStatus("voice", "supported", supported);
            vendroidSetPatchStatus("voice", "runtime", supported ? "supported" : "pending");
            console.warn("[Vendroid] Voice: store isSupported=" + supported +
                " engine=" + engineName);
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
                vendroidSetPatchStatus("voice", "runtime", "fallback");
            } else {
                vendroidVoiceRetry("engine swap did not take");
            }
        } catch (e) {
            console.error("[Vendroid] setupVoiceSupport error: " + e.message);
            vendroidVoiceRetry("exception: " + e.message);
        }
    }

    // Fallback for the evaluate injection path. The prelude shim
    // (VENCORD_PRELUDE_JS) installs setSinkId before the deferred
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

