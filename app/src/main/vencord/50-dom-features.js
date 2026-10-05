    function setupTextCommandDispatcher() {
        if (_vendroidCmdPatched) return;
        try {
            var Common = Vencord.Webpack && Vencord.Webpack.Common;
            var MessageActions = Common && Common.MessageActions;
            if (!MessageActions || typeof MessageActions.sendMessage !== "function") {
                // MessageActions not ready yet; retry up to ~30s (slow page
                // loads keep the registry partial past 15s)
                if (_vendroidCmdRetryCount++ < 600) {
                    setTimeout(setupTextCommandDispatcher, 50);
                }
                return;
            }

            var origSendMessage = MessageActions.sendMessage;
            if (origSendMessage.__vendroidCmdPatched) {
                _vendroidCmdPatched = true;
                return;
            }

            MessageActions.sendMessage = function(channelId, messageObj, flag, options) {
                try {
                    var content = messageObj && typeof messageObj.content === "string" ? messageObj.content : "";
                    // Only intercept messages that start with "/"
                    if (content && content.charCodeAt(0) === 0x2F) { // 0x2F = '/'
                        // Parse: /commandName rest-of-line
                        var match = content.match(/^\/(\S+)\s*([\s\S]*)$/);
                        if (match) {
                            var cmdName = match[1].toLowerCase();
                            var msgArg = match[2] || "";
                            var executor = VENDROID_TEXT_COMMANDS[cmdName];
                            if (executor) {
                                // Run the command's execute transform
                                var result = executor(msgArg);
                                if (result && typeof result.content === "string") {
                                    // Replace content in the message object and
                                    // let the original sendMessage handle it.
                                    messageObj.content = result.content;
                                    if (result.tts) messageObj.tts = true;
                                }
                            }
                        }
                    }
                } catch(e) {
                    console.error("[Vendroid] text command dispatcher error: " + e.message);
                    // On error, let the original send proceed unchanged
                }
                // Call original sendMessage with (possibly modified) args
                return origSendMessage.apply(this, arguments);
            };
            // Mark patched so we don't double-patch on re-injection
            MessageActions.sendMessage.__vendroidCmdPatched = true;
            _vendroidCmdPatched = true;
            console.warn("[Vendroid] Text command dispatcher installed (/me, /shrug, /tableflip, /unflip, /tts, /spoiler)");
        } catch(e) {
            // Retries every 50ms: log the first failure, then one line
            // every ~2s, or a slow page load prints ~400 identical errors.
            if (_vendroidCmdRetryCount === 0 || _vendroidCmdRetryCount % 40 === 0) {
                console.error("[Vendroid] setupTextCommandDispatcher failed (retry " + _vendroidCmdRetryCount + "): " + e.message);
            }
            if (_vendroidCmdRetryCount++ < 600) {
                setTimeout(setupTextCommandDispatcher, 50);
            }
        }
    }


    // Shared module resolver: resolves a webpack module by source-code
    // signature. `patterns` may be a string, RegExp, or an array of either;
    // the first match wins. Resolution order: Vencord's findModuleId (scans
    // the factory registry, so lazily-loaded modules resolve too), then
    // findByCode (instantiated modules only), then a manual factory scan.
    // Shared by the GIF picker and native-search patches below.
    function findModuleByCode(patterns) {
        if (!Array.isArray(patterns)) patterns = [patterns];
        if (typeof Vencord.Webpack.findModuleId === "function") {
            for (var pi = 0; pi < patterns.length; pi++) {
                try {
                    var mid = Vencord.Webpack.findModuleId(patterns[pi]);
                    if (mid != null && typeof Vencord.Webpack.wreq === "function") {
                        var mod = Vencord.Webpack.wreq(mid);
                        if (mod) {
                            console.warn("[Vendroid] module-by-code: found id=" + mid + " via findModuleId (pattern " + pi + ")");
                            return mod;
                        }
                    }
                } catch(e) {
                    console.error("[Vendroid] module-by-code: findModuleId(pattern " + pi + ") failed: " + e.message);
                }
            }
        }
        // Fallback: Vencord's findByCode (works for already-loaded modules).
        if (typeof Vencord.Webpack.findByCode === "function") {
            for (var pi2 = 0; pi2 < patterns.length; pi2++) {
                try {
                    var mod2 = Vencord.Webpack.findByCode(patterns[pi2]);
                    if (mod2) {
                        console.warn("[Vendroid] module-by-code: found via findByCode (pattern " + pi2 + ")");
                        return mod2;
                    }
                } catch(e) {
                    console.error("[Vendroid] module-by-code: findByCode(pattern " + pi2 + ") failed: " + e.message);
                }
            }
        }
        // Fallback: manual scan of the webpack factory source.
        var wreq = Vencord.Webpack.wreq;
        if (!wreq || !wreq.m) return null;
        var factories = wreq.m;
        try {
            var ids = Object.keys(factories);
            for (var i = 0; i < ids.length; i++) {
                var id = ids[i];
                if (typeof factories[id] !== "function") continue;
                var src = factories[id].toString();
                for (var fi = 0; fi < patterns.length; fi++) {
                    var pat = patterns[fi];
                    var hit = (typeof pat === "string")
                        ? (src.indexOf(pat) !== -1)
                        : pat.test(src);
                    if (hit) {
                        try {
                            var exports = wreq(id);
                            console.warn("[Vendroid] module-by-code: found by factory scan -> id=" + id + " (pattern " + fi + ")");
                            return exports;
                        } catch(e) {
                            console.error("[Vendroid] module-by-code: wreq(" + id + ") failed: " + e.message);
                        }
                    }
                }
            }
        } catch(e) {
            console.error("[Vendroid] module-by-code: factory scan error: " + e.message);
        }
        return null;
    }


    // GIF picker: unblock Discord's built-in GIF picker on mobile.
    //
    // The expression-picker overlay hides the GIF tab when isMobile (Fr) is
    // true: `en = gifs.allowSending && !d.Fr && ...`. We wrap the overlay's
    // render (React.memo — inner fn at memo.type) so Fr reads false only
    // during that one render, then restores it. A permanent Fr=false breaks
    // navigation (MobileWebSidebarStore.getIsOpen returns `!Fr || aW`, so the
    // sidebar would always report open).
    //
    // Result: the GIF tab appears inside the existing emoji picker — tap the
    // emoji button and switch to GIF, like desktop. No separate button; sending
    // works via the overlay's native onSelectGIF.

    var _vendroidGifPatched = false;
    var _vendroidGifOverlayPatched = false;
    var _vendroidGifRetryCount = 0;
    var _vendroidGifGaveUp = false;
    // Verify failures are deterministic per webpack state (a read-only export
    // stays read-only), so unlike module resolution this budget stays small.
    var _vendroidGifPatchRetryCount = 0;

    function setupGifPickerButton() {
        if (_vendroidGifPatched) return;
        try {
            if (typeof Vencord === "undefined" || !Vencord.Webpack) {
                if (_vendroidGifRetryCount++ < 300) setTimeout(setupGifPickerButton, 50);
                return;
            }

            // Resolve core modules.
            var PlatformUtils = null;
            try {
                PlatformUtils = vendroidFindByProps("Fr", "Ct", "KY", "v1");
            } catch(e) {}
            var ExpressionPickerStore = null;
            try {
                ExpressionPickerStore = vendroidFindByProps("r$", "ed", "v8", "RQ");
            } catch(e) {}
            var ExpressionPickerViewTypes = null;
            try {
                ExpressionPickerViewTypes = vendroidFindByProps("kx", "VQ", "wp");
            } catch(e) {}

            if (!PlatformUtils || PlatformUtils.Fr === undefined ||
                !ExpressionPickerStore || !ExpressionPickerStore.r$ ||
                !ExpressionPickerViewTypes || !ExpressionPickerViewTypes.kx) {
                if (_vendroidGifRetryCount++ < 300) {
                    setTimeout(setupGifPickerButton, 50);
                } else if (!_vendroidGifGaveUp) {
                    _vendroidGifGaveUp = true;
                    console.warn("[Vendroid] GIF: core modules not found after 300 attempts; " +
                        "picker stays stock");
                }
                return;
            }
            console.warn("[Vendroid] GIF: core modules resolved (Fr=" + PlatformUtils.Fr + ")");

            // Patch the overlay: flip Fr->false only during its render call.
            // Only needed on mobile (Fr===true); desktop already shows GIF.
            if (PlatformUtils.Fr === true && !_vendroidGifOverlayPatched) {
                var OverlayModule = findModuleByCode([
                    // Match stable handler prop names; tolerates Discord
                    // re-minifying the per-build value letters.
                    /onSelectGIF:[A-Za-z0-9_$],onSelectEmoji:[A-Za-z0-9_$],onSelectSticker:[A-Za-z0-9_$],onSelectSound:[A-Za-z0-9_$]/,
                    // Fallback: exact current minified signature.
                    "onSelectGIF:a,onSelectEmoji:l,onSelectSticker:A,onSelectSound:v,channel:b"
                ]);
                var overlayTypeFn = null;
                var overlayIsMemo = false;
                if (OverlayModule && OverlayModule.A) {
                    // React.memo: the render fn is at .type, not the memo object.
                    if (typeof OverlayModule.A.type === "function") {
                        overlayTypeFn = OverlayModule.A.type;
                        overlayIsMemo = true;
                    } else if (typeof OverlayModule.A === "function") {
                        overlayTypeFn = OverlayModule.A;
                    }
                }
                if (overlayTypeFn) {
                    _vendroidGifOverlayPatched = true;
                    var origOverlayRender = overlayTypeFn;
                    var _gifOriginalFrGetter = null;
                    try {
                        var desc = Object.getOwnPropertyDescriptor(PlatformUtils, "Fr");
                        if (desc && desc.get) _gifOriginalFrGetter = desc.get;
                    } catch(e) {}

                    function flipFrForRender() {
                        try {
                            Object.defineProperty(PlatformUtils, "Fr", {
                                get: function() { return false; },
                                configurable: true, enumerable: true
                            });
                        } catch(e) {}
                    }
                    function restoreFr(savedFr) {
                        try {
                            Object.defineProperty(PlatformUtils, "Fr", {
                                get: _gifOriginalFrGetter || function() { return savedFr; },
                                configurable: true, enumerable: true
                            });
                        } catch(e) {}
                    }

                    var patchedOverlay = function() {
                        var savedFr = PlatformUtils.Fr;
                        flipFrForRender();
                        var res;
                        try {
                            res = origOverlayRender.apply(this, arguments);
                        } finally {
                            restoreFr(savedFr);
                        }
                        return res;
                    };
                    try {
                        if (overlayIsMemo) {
                            OverlayModule.A.type = patchedOverlay;
                            // A getter-only .type would silently ignore the
                            // assignment; verify it took so the catch below
                            // clears the patched flag and retries.
                            if (OverlayModule.A.type !== patchedOverlay) throw new Error("memo .type is read-only");
                        } else {
                            // Plain function component: React calls A(props)
                            // directly, so .type is a no-op. Replace the export
                            // itself, copying statics (propTypes, displayName).
                            for (var k in origOverlayRender) patchedOverlay[k] = origOverlayRender[k];
                            OverlayModule.A = patchedOverlay;
                            // Harmony exports expose A as a get-only property;
                            // the assignment can silently no-op in sloppy mode,
                            // so verify it actually took.
                            if (OverlayModule.A !== patchedOverlay) throw new Error("export A is read-only");
                        }
                        console.warn("[Vendroid] GIF: patched overlay (Fr scoped to render only)");
                    } catch(e) {
                        // Return so the code below cannot mark setup complete
                        // and mask this failure for the session.
                        _vendroidGifOverlayPatched = false;
                        console.error("[Vendroid] GIF: overlay patch failed: " + e.message);
                        // Reset the view even on failure; a persisted GIF
                        // lastActiveView would otherwise open the picker on
                        // a view whose tab is hidden.
                        try {
                            ExpressionPickerStore.U(ExpressionPickerViewTypes.kx.EMOJI);
                        } catch(e2) {
                            console.error("[Vendroid] GIF: failed to reset lastActiveView: " + e2.message);
                        }
                        if (_vendroidGifPatchRetryCount++ < 10) setTimeout(setupGifPickerButton, 200);
                        return;
                    }
                } else {
                    console.error("[Vendroid] GIF: overlay not found (A type=" + (OverlayModule ? typeof OverlayModule.A : "null") + ")");
                }
            }

            // Reset lastActiveView to emoji so the emoji button opens emoji
            // by default (not GIF left over from prior r$ calls).
            try {
                ExpressionPickerStore.U(ExpressionPickerViewTypes.kx.EMOJI);
            } catch(e) {
                console.error("[Vendroid] GIF: failed to reset lastActiveView: " + e.message);
            }

            _vendroidGifPatched = true;
            console.warn("[Vendroid] GIF picker setup complete");

        } catch(e) {
            console.error("[Vendroid] setupGifPickerButton failed: " + e.message);
            if (_vendroidGifRetryCount++ < 300) setTimeout(setupGifPickerButton, 50);
        }
    }


    // Native channel search: expose Discord's built-in Search widget on
    // mobile web through our own entry point. We mount the component the
    // desktop toolbar loads into our own ReactDOM root inside a top-anchored
    // card; the native results dock renders behind it and stays clickable
    // (openSearchOverlayWithRoot below).
    //
    // Targets survive rebuilds: openNativeSearch locates the HeaderBar
    // launcher factory by source signature (anchor regexes below, verified
    // unique against live bundles) and parses its createPromise loader for
    // the chunk set and widget module id, so id rotation cannot pin this
    // feature to dead numbers. The pinned snapshot applies only when
    // discovery fails. Nothing inside the Search graph reads
    // PlatformUtils.Fr, so the widget is platform-agnostic once mounted.
    //
    // Why NOT the GIF-style export wrap: webpack's __webpack_require__.d
    // defines exports as non-configurable accessors, so HeaderBarModule.A
    // cannot be reassigned or redefined at runtime (verified on-device:
    // assignment silently no-ops, defineProperty throws). The GIF picker
    // worked because its target was a mutable React.memo object (.type);
    // .A here is a plain function.
    //
    // Why NOT openModalLazy: abandoned after commit-time TypeErrors thrown
    // by vendor factories. Our own root sidesteps the modal layer entirely,
    // including the focus/popout machinery the widget fought there.

    // Result memo for discoverSearchTargets; the TTL lets a long-lived
    // session re-resolve after Discord swaps bundles mid-session.
    var _vendroidDiscovered = null;
    var _VENDROID_DISCOVERY_TTL = 10 * 60 * 1000;

    // Anchor signatures identifying the HeaderBar launcher factory; any one
    // match is accepted.
    var _VENDROID_SEARCH_ANCHORS = [
        /toolbar:[A-Za-z_$]{1,3},mobileToolbar:[A-Za-z_$]{1,3},"aria-label":/,
        /name:"Search",renderLoader:/,
        /webpackId:\d{4,7},name:"Search"/
    ];

    // Parse {chunks, mid} out of a factory source string. Null when the
    // loader data is absent or outside sanity bounds (2..40 chunks).
    function _vendroidExtractSearchTargets(src) {
        try {
            var ci = src.indexOf("createPromise:");
            if (ci === -1) return null;
            // Limit the scan window so digit runs elsewhere in this large
            // factory cannot leak in as chunk ids.
            var segEnd = src.indexOf(",name:", ci);
            if (segEnd === -1) segEnd = Math.min(src.length, ci + 6000);
            var seg = src.slice(ci, segEnd);

            var chunks = [];
            var seen = {};
            var ai = seg.indexOf("Promise.all");
            if (ai !== -1) {
                // Quote-aware walk: brackets inside string literals must not
                // skew depth counting.
                var ob = seg.indexOf("[", ai);
                var depth = 0, j = ob, q = null, esc = false;
                for (; j < seg.length; j++) {
                    var c = seg.charAt(j);
                    if (q) {
                        if (esc) esc = false;
                        else if (c === "\\") esc = true;
                        else if (c === q) q = null;
                        continue;
                    }
                    if (c === '"' || c === "'") { q = c; continue; }
                    if (c === "[") depth++;
                    else if (c === "]") { depth--; if (depth === 0) break; }
                }
                var arrText = seg.slice(ob + 1, j);
                var ids = arrText.match(/\d{4,7}/g) || [];
                for (var ii = 0; ii < ids.length; ii++) {
                    if (!seen[ids[ii]]) { seen[ids[ii]] = 1; chunks.push(ids[ii]); }
                }
            } else {
                // Chained loaders instead of Promise.all: take every
                // x.e("id") / x.e(id) call in declaration order.
                var reSeq = /[A-Za-z_$][A-Za-z0-9_$]{0,3}\.e\(\s*(?:"(\d{4,7})"|'(\d{4,7})'|(\d{4,7}))\s*\)/g;
                var ms;
                while ((ms = reSeq.exec(seg)) !== null) {
                    var cid = ms[1] || ms[2] || ms[3];
                    if (cid && !seen[cid]) { seen[cid] = 1; chunks.push(cid); }
                }
            }

            var mid = null;
            var mBind = seg.match(/\.then\s*\(\s*[A-Za-z_$][A-Za-z0-9_$]{0,3}\.bind\s*\(\s*[A-Za-z_$][A-Za-z0-9_$]{0,3}\s*,\s*(\d{4,7})\s*\)\s*\)/);
            if (mBind) mid = parseInt(mBind[1], 10);
            else {
                // Ternary or renamed .then shapes: the webpackId literal is
                // emitted by the same macro and names the bind target.
                var mWid = seg.match(/webpackId\s*:\s*(\d{4,7})/);
                if (mWid) mid = parseInt(mWid[1], 10);
            }
            if (!(chunks.length >= 2 && chunks.length <= 40) || mid == null) return null;
            return { chunks: chunks, mid: mid };
        } catch(e) {
            console.error("[Vendroid] Search: extract from factory source threw: " + e.message);
            return null;
        }
    }

    // Chunk ids + widget module id for THIS build, or null when discovery
    // fails (caller falls back to the pinned snapshot). Never throws.
    function discoverSearchTargets(W) {
        try {
            if (_vendroidDiscovered &&
                (Date.now() - _vendroidDiscovered.ts) < _VENDROID_DISCOVERY_TTL) {
                return _vendroidDiscovered;
            }

            // First locate the launcher module id. findModuleId reads raw
            // factories (so lazily-loaded modules count); the manual wreq.m
            // scan covers Vencord builds without it.
            var hid = null;
            if (typeof W.findModuleId === "function") {
                for (var ai = 0; ai < _VENDROID_SEARCH_ANCHORS.length && hid == null; ai++) {
                    try {
                        var cand = W.findModuleId(_VENDROID_SEARCH_ANCHORS[ai]);
                        if (cand != null) hid = cand;
                    } catch(eFmi) {}
                }
            }
            if (hid == null && W.wreq && W.wreq.m) {
                var factories = W.wreq.m;
                var fids = Object.keys(factories);
                for (var fi = 0; fi < fids.length && hid == null; fi++) {
                    try {
                        if (typeof factories[fids[fi]] !== "function") continue;
                        var fsrc = String(factories[fids[fi]]);
                        for (var pi = 0; pi < _VENDROID_SEARCH_ANCHORS.length; pi++) {
                            if (_VENDROID_SEARCH_ANCHORS[pi].test(fsrc)) { hid = fids[fi]; break; }
                        }
                    } catch(eScan) {}
                }
            }
            if (hid == null) throw new Error("stage=locate: launcher module not found");

            // Then parse the lazy-search registration out of its source.
            var facFn = W.wreq.m[hid];
            if (typeof facFn !== "function") throw new Error("stage=extract: factory gone");
            var targets = _vendroidExtractSearchTargets(String(facFn));
            if (!targets) throw new Error("stage=extract/sanity: no usable createPromise/bind target");

            _vendroidDiscovered = {
                chunks: targets.chunks,
                mid: targets.mid,
                ts: Date.now()
            };
            console.warn("[Vendroid] Search: discovered chunks=" + targets.chunks.length +
                " mid=" + targets.mid + " (header id=" + hid + ")");
            return _vendroidDiscovered;
        } catch(eDisc) {
            // No negative caching: the next click retries, so boot races and
            // rebuild churn recover without polling.
            console.error("[Vendroid] Search: discovery failed (" + eDisc.message +
                "); falling back to pinned snapshot");
            return null;
        }
    }

    var _vendroidSearchPatched = false;
    var _vendroidSearchRetryCount = 0;
    var _vendroidSearchOpening = false;
    var _vendroidSearchDiagInstalled = false;
    var _vendroidSearchSyncObserver = null;
    var _vendroidSearchSyncHost = null;

    function setupNativeSearch() {
        if (_vendroidSearchPatched) return;
        try {
            if (typeof Vencord === "undefined" || !Vencord.Webpack || !Vencord.Webpack.findByProps) {
                if (_vendroidSearchRetryCount++ < 300) setTimeout(setupNativeSearch, 50);
                return;
            }

            // Crash forensics: route every uncaught error/rejection through
            // the [Vendroid] log channel with stack info. Console.error lines
            // lose the column and stack; these carry both.
            if (!_vendroidSearchDiagInstalled) {
                _vendroidSearchDiagInstalled = true;
                window.addEventListener("error", function(ev) {
                    var loc = ev.filename ? ev.filename.split("/").pop() +
                        ":" + ev.lineno + ":" + ev.colno : "?";
                    console.error("[Vendroid] Search-diag uncaught @" + loc +
                        " :: " + ev.message +
                        (ev.error && ev.error.stack ? "\nSTACK: " + ev.error.stack : ""));
                });
                window.addEventListener("unhandledrejection", function(ev) {
                    var r = ev.reason;
                    var m = r && r.message ? r.message : String(r);
                    // Benign WebView audio race (sound-effect play() vs
                    // pause()), not a search failure. preventDefault also
                    // stops Chromium's "Uncaught (in promise)" line.
                    if (r && r.name === "AbortError" && m.indexOf("play() request was interrupted") !== -1) {
                        ev.preventDefault();
                        return;
                    }
                    console.error("[Vendroid] Search-diag rejection :: " + m +
                        (r && r.stack ? "\nSTACK: " + r.stack : ""));
                });
                console.warn("[Vendroid] Search-diag listeners installed");
            }

            _vendroidSearchPatched = true;
            console.warn("[Vendroid] Native search setup complete (entry-point poller installed)");
            setInterval(injectSearchButtonIfMissing, 750);
            ensureSearchButtonStyle();
            injectSearchButtonIfMissing();
        } catch(e) {
            console.error("[Vendroid] setupNativeSearch failed: " + e.message);
            if (_vendroidSearchRetryCount++ < 300) setTimeout(setupNativeSearch, 50);
        }
    }

    function makeSearchIconSvg() {
        return '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" ' +
            'stroke="currentColor" stroke-width="2" stroke-linecap="round">' +
            '<circle cx="11" cy="11" r="7"></circle>' +
            '<line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>';
    }

    // Builds the entry point: the same bare circular slot the feature has
    // always used, inline-styled and flex-frozen so the composer's stretchy row
    // cannot distort it into an oval. Top-anchored here; syncSearchButtonToRow
    // applies the vertical offset onto the buttons row.
    function createSearchButton() {
        var btn = document.createElement('div');
        btn.setAttribute('data-vde-search-btn', '1');
        btn.setAttribute('role', 'button');
        btn.setAttribute('aria-label', 'Search');
        btn.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;' +
            'flex:0 0 auto;width:26px;height:26px;margin:0 2px 0 8px;border-radius:50%;' +
            'cursor:pointer;color:var(--interactive-normal,#b5bac1);align-self:flex-start;' +
            '-webkit-tap-highlight-color:transparent;';
        btn.innerHTML = makeSearchIconSvg();
        btn.addEventListener('click', openNativeSearch);
        return btn;
    }

    // The live composer button row, or null when none is on screen.
    function findSearchButtonHost() {
        try {
            var hosts = document.querySelectorAll('[class*="channelTextArea"] [class*="buttons"]');
            var host = null;
            for (var i = 0; i < hosts.length; i++) {
                var h = hosts[i];
                var r = h.getBoundingClientRect();
                if (r.width > 0 && r.height > 0) host = h; // keep LAST match
            }
            return host;
        } catch(e) { return null; }
    }

    // The slot is a sibling of the buttons row, not a child, so flex does not
    // keep them on one line once the composer grows (Shift+Enter adds lines and
    // the row drops while a centered slot stays put). Measure the row's centre
    // and translate the slot onto it. Uses transform rather than margin so the
    // offset cannot feed back into layout, and re-runs on every composer resize.
    function syncSearchButtonToRow(btn, host) {
        try {
            if (!btn || !host || !btn.isConnected || !host.isConnected) return;
            if (btn.classList.contains('vde-search-fab')) return;
            // Measure from the natural (top-anchored) position.
            btn.style.transform = 'none';
            var br = btn.getBoundingClientRect();
            var hr = host.getBoundingClientRect();
            if (!br.height || !hr.height) return;
            var dy = (hr.top + hr.height / 2) - (br.top + br.height / 2);
            if (Math.abs(dy) < 1) dy = 0;
            btn.style.transform = dy ? 'translateY(' + dy + 'px)' : 'none';
        } catch(e) {}
    }

    // Re-align the slot whenever the composer's box changes; the growing
    // textarea is what moves the row. The 750ms poll is a backstop when
    // ResizeObserver is unavailable.
    function watchSearchComposer(host) {
        var parent = host && host.parentElement;
        if (!parent || typeof ResizeObserver === 'undefined') return;
        if (_vendroidSearchSyncHost === host && _vendroidSearchSyncObserver) return;
        try { if (_vendroidSearchSyncObserver) _vendroidSearchSyncObserver.disconnect(); } catch(e) {}
        _vendroidSearchSyncHost = host;
        try {
            _vendroidSearchSyncObserver = new ResizeObserver(function() {
                var btn = document.querySelector('[data-vde-search-btn]');
                if (btn && host.isConnected) syncSearchButtonToRow(btn, host);
            });
            _vendroidSearchSyncObserver.observe(parent);
        } catch(e) {
            _vendroidSearchSyncObserver = null;
            _vendroidSearchSyncHost = null;
        }
    }

    // Press feedback matching the native buttons. There is no hover on touch,
    // so :active is the only cue.
    function ensureSearchButtonStyle() {
        try {
            if (document.querySelector('style[data-vde-search-style]')) return;
            var s = document.createElement('style');
            s.setAttribute('data-vde-search-style', '1');
            s.textContent =
                '[data-vde-search-btn]{transition:background .1s ease;}' +
                '[data-vde-search-btn]:active{background:var(--background-modifier-active,' +
                'var(--background-modifier-hover,rgba(255,255,255,.06)));}';
            (document.head || document.documentElement).appendChild(s);
        } catch(e) {}
    }

    // Idempotent injector. Same 26px slot, now reconciled every tick against
    // the live row: a re-render that orphans the node gets it dropped and
    // re-anchored before the current row instead of skipped forever.
    function injectSearchButtonIfMissing() {
        try {
            if (document.hidden) return;
            if (!_vendroidInChatView()) return;

            var host = findSearchButtonHost();
            var existing = document.querySelector('[data-vde-search-btn]');

            if (!host || !host.parentElement) {
                // No composer row: keep the FAB, or replace a stranded slot.
                if (existing && existing.classList.contains('vde-search-fab')) return;
                if (existing) existing.remove();
                var fab = createSearchButton();
                fab.classList.add('vde-search-fab');
                fab.style.cssText += 'position:fixed;right:12px;bottom:110px;z-index:2000;' +
                    'width:44px;height:44px;background:var(--background-secondary-alt,#313338);' +
                    'box-shadow:0 2px 8px rgba(0,0,0,.35);';
                document.body.appendChild(fab);
                return;
            }

            // Already anchored before the live row in the same parent: re-seat
            // it on the row and keep tracking.
            if (existing && !existing.classList.contains('vde-search-fab') &&
                existing.parentElement === host.parentElement &&
                (existing.compareDocumentPosition(host) & 4)) {
                syncSearchButtonToRow(existing, host);
                watchSearchComposer(host);
                return;
            }

            if (existing) existing.remove();
            var fresh = createSearchButton();
            host.parentElement.insertBefore(fresh, host);
            watchSearchComposer(host);
            // First layout pass has not run yet; seat it once it has a box.
            if (typeof requestAnimationFrame === 'function') {
                requestAnimationFrame(function() { syncSearchButtonToRow(fresh, host); });
            } else {
                setTimeout(function() { syncSearchButtonToRow(fresh, host); }, 0);
            }
        } catch(e) {
            console.error("[Vendroid] Search: button injection failed: " + e.message);
        }
    }

    // Cheap "are we in a channel view" probe.
    function _vendroidInChatView() {
        try {
            return document.querySelector('[class*="channelTextArea"]') != null;
        } catch(e) { return false; }
    }

    function openNativeSearch() {
        if (_vendroidSearchOpening) return;
        _vendroidSearchOpening = true;
        try {
            var W = Vencord.Webpack;
            var wr = W.wreq;
            if (!wr || typeof wr.e !== "function") {
                console.error("[Vendroid] Search: webpack require without chunk loader");
                _vendroidSearchOpening = false;
                return;
            }
            var ModalApi = null;
            try { ModalApi = W.findByProps("openModalLazy", "openModal", "closeModal"); } catch(e) {}
            if (!ModalApi || typeof ModalApi.openModalLazy !== "function") {
                console.error("[Vendroid] Search: modal API not found");
                _vendroidSearchOpening = false;
                return;
            }

            var targetChunks = VENDROID_SEARCH_CHUNKS.slice();
            var targetMid = VENDROID_SEARCH_MODULE_ID;
            var discovered = discoverSearchTargets(W);
            if (discovered && discovered.chunks && discovered.chunks.length >= 2 &&
                typeof discovered.mid === "number") {
                targetChunks = discovered.chunks;
                targetMid = discovered.mid;
            } else {
                console.warn("[Vendroid] Search: fallback to pinned ids (chunks=" +
                    VENDROID_SEARCH_CHUNKS.length + " mid=" + VENDROID_SEARCH_MODULE_ID + ")");
            }

            Promise.all(targetChunks.map(function(c) { return wr.e(c); }))
                .then(function() {
                    // Probe pass: touching each module in Search.default's
                    // import header runs its factory now, inside try/catch
                    // with its id attached, instead of anonymously mid-commit
                    // when React first renders it.
                    var probeFails = [];
                    for (var pi = 0; pi < VENDROID_SEARCH_DEP_PROBES.length; pi++) {
                        var pid = VENDROID_SEARCH_DEP_PROBES[pi];
                        var pmod = null;
                        var perr = null;
                        try { pmod = wr(pid); } catch(pe) { perr = pe; }
                        if (perr || !pmod) {
                            probeFails.push(pid + (perr ? " (" + perr.message + ")" : " (empty)"));
                            console.error("[Vendroid] Search: dep probe FAILED id=" + pid +
                                (perr ? " :: " + perr.message : ""));
                        }
                    }
                    if (probeFails.length === 0) {
                        console.warn("[Vendroid] Search: all " +
                            VENDROID_SEARCH_DEP_PROBES.length + " dependency probes OK");
                    } else if (VENDROID_SEARCH_PROBES_FATAL) {
                        console.error("[Vendroid] Search: " + probeFails.length +
                            " dependency probes failed; aborting modal open" +
                            " (VENDROID_SEARCH_PROBES_FATAL=true)");
                        _vendroidSearchOpening = false;
                        return;
                    } else {
                        // Probes are forensics, not a gate: every failure was
                        // logged with its id above, and the commit path below
                        // runs guarded regardless.
                        console.error("[Vendroid] Search: " + probeFails.length +
                            " dependency probes failed; continuing (non-fatal)");
                    }

                    try {
                        var mod = wr(targetMid);
                        if (!mod || typeof mod.default !== "function") {
                            throw new Error("Search module missing/unexpected (id " +
                                targetMid + ")");
                        }

                        var R = (W.Common && W.Common.React) ||
                                W.findByProps("createElement", "Fragment");
                        if (!R || typeof R.createElement !== "function") {
                            throw new Error("React.createElement unavailable");
                        }
                        console.warn("[Vendroid] Search: React resolved (source=" +
                            ((W.Common && W.Common.React) ? "Common" : "findByProps") +
                            ", version=" + (R.version || "?") + ")");

                        // Same props the desktop toolbar passes; guildId comes
                        // from the channel record (DMs have none -> undefined).
                        var channelId, guildId;
                        try {
                            var ChanStore = W.findByProps("getChannel", "hasChannel");
                            var SelCh = W.findByProps("getChannelId", "getVoiceChannelId");
                            channelId = SelCh && SelCh.getChannelId && SelCh.getChannelId();
                            var ch = ChanStore && channelId && ChanStore.getChannel(channelId);
                            if (ch && ch.guild_id) guildId = ch.guild_id;
                        } catch(e2) {}

                        var SearchComp = mod.default;
                        var vdeRenderCount = 0;
                        function VdeSafeSearch(props) {
                            vdeRenderCount++;
                            try {
                                return R.createElement(SearchComp,
                                    Object.assign({}, props, { guildId: guildId, channelId: channelId }));
                            } catch(errR) {
                                console.error("[Vendroid] Search: wrapper render threw @call#" +
                                    vdeRenderCount + "\nSTACK: " +
                                    (errR && errR.stack ? errR.stack : String(errR)));
                                return null;
                            }
                        }

                        // Mount path A: our own ReactDOM root inside the
                        // overlay card, bypassing Discord's modal layer
                        // entirely. Client resolution order:
                        // 1. findByProps("createRoot","hydrateRoot"), keyed
                        //    on prop identity so id rotation cannot miss it;
                        //    current web builds tree-shake hydrateRoot away,
                        //    so this often finds nothing today.
                        // 2. Pinned vendor id 490349, shipped in a boot-time
                        //    <script> and unreachable via wr.e(). The
                        //    createRoot type check keeps a reused id pointing
                        //    at the wrong module from passing.
                        // 3. Null: fall back to the modal path below.
                        var RD = null;
                        try {
                            var rdByProps = typeof W.findByProps === "function" ?
                                W.findByProps("createRoot", "hydrateRoot") : null;
                            if (rdByProps && typeof rdByProps.createRoot === "function") RD = rdByProps;
                        } catch(eRDP) {}
                        if (!RD) {
                            try {
                                var rdMod = wr(490349);
                                if (rdMod && typeof rdMod.createRoot === "function") RD = rdMod;
                            } catch(eRD) {}
                        }
                        if (RD && typeof RD.createRoot === "function") {
                            var searchCtxKey = String(guildId == null ? "dm" : guildId) +
                                "/" + String(channelId == null ? "?" : channelId);
                            openSearchOverlayWithRoot(RD, R, VdeSafeSearch,
                                searchCtxKey,
                                function() {
                                    // Disposing on channel change prevents a
                                    // stale context surviving navigation.
                                    var fd = findFluxDispatcher();
                                    if (!(fd && typeof fd.subscribe === "function")) return null;
                                    var onNav = function() { closeSearchOverlay(true); };
                                    fd.subscribe("CHANNEL_SELECT", onNav);
                                    return function() {
                                        try { fd.unsubscribe("CHANNEL_SELECT", onNav); } catch(e) {}
                                    };
                                });
                            console.warn("[Vendroid] Search: widget opened via own root" +
                                " (channelId=" + channelId + ", guildId=" + guildId + ")");
                        } else {
                            // Mount path B (fallback): Discord's modal system.
                            ModalApi.openModalLazy(function() {
                                return Promise.resolve({ default: VdeSafeSearch });
                            });
                            console.warn("[Vendroid] Search: widget opened via modal API" +
                                " (channelId=" + channelId + ", guildId=" + guildId + ")");
                        }
                    } catch(err2) {
                        console.error("[Vendroid] Search: open failed: " + err2.message);
                    } finally {
                        _vendroidSearchOpening = false;
                    }
                }, function(err3) {
                    console.error("[Vendroid] Search: chunk load failed: " +
                        (err3 && err3.message ? err3.message : String(err3)));
                    _vendroidSearchOpening = false;
                });
        } catch(e) {
            _vendroidSearchOpening = false;
            console.error("[Vendroid] openNativeSearch failed: " + e.message);
        }
    }

    var _vendroidSearchOverlay = null;

    // Close semantics: unmounting the widget runs its cleanup effect, which
    // calls sidebarApi.setSelectedSearchContext(null) and tears down the
    // native search session the app renders behind us. Closing therefore
    // only hides the card; full disposal happens on channel change or when
    // back unwinds a hidden session (dispose=true).
    function closeSearchOverlay(dispose) {
        if (!_vendroidSearchOverlay) return;
        var o = _vendroidSearchOverlay;
        try { document.removeEventListener('keydown', o.onKey, true); } catch(e) {}
        o.el.style.display = 'none';
        if (dispose === true) {
            _vendroidSearchOverlay = null;
            try { o.fdUnsub && o.fdUnsub(); } catch(e) {}
            try { o.root.unmount(); } catch(e) {}
            try { o.el.remove(); } catch(e) {}
            console.warn("[Vendroid] Search overlay disposed");
        } else {
            console.warn("[Vendroid] Search overlay hidden (native search session left running)");
        }
        notifyOverlayState();
    }

    // Top-anchored card hosting only the input; the native results dock
    // renders behind it, fully visible and clickable. Dedicated createRoot —
    // no Discord modal layer involved.
    function openSearchOverlayWithRoot(RD, R, WidgetComp, ctxKey, fdUnsubFactory) {
        // Reuse a live instance for the same channel — re-show it without
        // remounting (remounting would recreate the search context and
        // reset state).
        if (_vendroidSearchOverlay && _vendroidSearchOverlay.ctxKey === ctxKey &&
            document.body.contains(_vendroidSearchOverlay.el)) {
            _vendroidSearchOverlay.el.style.display = 'flex';
            // closeSearchOverlay(false) removes the Esc handler on hide;
            // re-add it (remove() is a no-op if still attached).
            try {
                document.removeEventListener('keydown', _vendroidSearchOverlay.onKey, true);
                document.addEventListener('keydown', _vendroidSearchOverlay.onKey, true);
            } catch(e) {}
            console.warn("[Vendroid] Search overlay re-shown for same channel");
            notifyOverlayState();
            return;
        }
        closeSearchOverlay(true);

        var el = document.createElement('div');
        el.setAttribute('data-vde-search-overlay', '1');
        // No backdrop dim: pointer events pass through everywhere except the
        // card itself, keeping the native results interactive below.
        el.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:2147483000;' +
            'display:flex;justify-content:center;padding:10px;pointer-events:none;';

        var panel = document.createElement('div');
        panel.style.cssText = 'width:min(680px,100%);max-height:40vh;border-radius:12px;' +
            'overflow:hidden;background:var(--background-primary,#313338);' +
            'box-shadow:0 8px 32px rgba(0,0,0,.5);pointer-events:auto;display:flex;' +
            'flex-direction:column;border:1px solid var(--background-modifier-accent,#3f4147);';

        var head = document.createElement('div');
        head.style.cssText = 'display:flex;align-items:center;gap:8px;padding:8px 12px;';
        var title = document.createElement('span');
        title.textContent = 'Search';
        title.style.cssText = 'flex:1;font-weight:600;font-size:14px;color:var(--header-primary,#f2f3f5);';
        var hint = document.createElement('span');
        hint.textContent = 'Results open below';
        hint.style.cssText = 'font-size:11px;color:var(--text-muted,#949ba4);margin-right:6px;';
        var closeBtn = document.createElement('div');
        closeBtn.textContent = '✕';
        closeBtn.setAttribute('role', 'button');
        closeBtn.setAttribute('aria-label', 'Hide search bar');
        closeBtn.style.cssText = 'cursor:pointer;padding:2px 8px;font-size:13px;' +
            'color:var(--interactive-normal,#b5bac1);';
        closeBtn.addEventListener('click', function() { closeSearchOverlay(false); });
        head.appendChild(title);
        head.appendChild(hint);
        head.appendChild(closeBtn);

        var mountNode = document.createElement('div');
        mountNode.style.cssText = 'padding:6px 12px 12px;';

        panel.appendChild(head);
        panel.appendChild(mountNode);
        el.appendChild(panel);
        document.body.appendChild(el);

        var onKey = function(e) {
            if (e.key !== 'Escape') return;
            e.preventDefault(); e.stopPropagation();
            closeSearchOverlay(false);
        };
        document.addEventListener('keydown', onKey, true);

        var root = RD.createRoot(mountNode);
        root.render(R.createElement(WidgetComp, {}));
        _vendroidSearchOverlay = {
            el: el, root: root, onKey: onKey, ctxKey: ctxKey,
            fdUnsub: typeof fdUnsubFactory === "function" ? fdUnsubFactory() : null
        };
        console.warn("[Vendroid] Search overlay opened for " + ctxKey);
        notifyOverlayState();
    }

    // Pinned snapshot of the HeaderBar desktop loader from an older web
    // build: Promise.all over 13 chunk ids, then module 907745 (exports
    // default = function G({className, guildId, channelId}) building a
    // searchContext internally via E.J). Consulted only when discovery fails.
    var VENDROID_SEARCH_CHUNKS = ["245553", "855151", "421630", "113582", "966016",
                                  "781202", "421225", "671367", "79171", "798567",
                                  "220803", "417664", "183752"];
    var VENDROID_SEARCH_MODULE_ID = 907745;

    // Set true while debugging a rebuild: aborting on the first failed probe
    // names the dead dep id immediately instead of surfacing it as an
    // anonymous commit-time error.
    var VENDROID_SEARCH_PROBES_FATAL = false;

    // Modules imported by Search.default's own dependency header (transcribed
    // from the widget chunk source), plus representatives of the shared
    // vendor mega-file (react-dom client 490349, popper 888767, the large
    // utility modules) whose factories execute lazily on first render and
    // were implicated in the earlier anonymous "TypeError: e is not a
    // function".
    var VENDROID_SEARCH_DEP_PROBES = [
        477900, 582128, 64015, 775602, 138298, 761640, 734057, 71393,
        309010, 256796, 517381, 822382, 408730, 616252, 753806, 775427,
        145331, 742788, 921242, 652215, 375708,
        /* vendor + large utilities */ 490349, 888767, 819354, 311358,
        902537, 727222, 596829, 683402, 195554, 666624, 333007,
        822986, 733344, 821500, 382811, 552229, 458265, 499957, 247320
    ];

