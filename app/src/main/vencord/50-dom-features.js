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
    // Shared by native search and the voice engine resolver.
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


    // Unblock the built-in GIF picker on mobile.
    //
    // The picker hides the GIF tab behind non-configurable feature flags.
    // 00-boot.js patches the factory before execution; this setup wraps the
    // memo render to clear the props that hide the tab and resets
    // lastActiveView so the emoji button opens on emoji.

    var _vendroidGifPatched = false;
    var _vendroidGifRetryCount = 0;
    var _vendroidGifGaveUp = false;
    var _vendroidGifOverlayPatched = false;
    var _vendroidGifOverlayLogged = false;

    // If the overlay ran before the hooks loaded, wreq.c holds unpatched
    // exports and rewriting wreq.m is too late. Re-run the factory with both
    // GIF rewrites and copy only the memo render into the live memo.
    function vendroidReloadGifOverlay(OverlayModule) {
        try {
            var wreq = Vencord.Webpack.wreq;
            if (!wreq || !wreq.m || !wreq.c) return false;
            // Wrapper modules can re-export the same memo. Pick the factory
            // whose source contains the overlay.
            function factoryHasOverlay(candidateId) {
                try {
                    var f = wreq.m[candidateId];
                    // wreq.m is a Vencord proxy. Call factory.toString();
                    // Function.prototype.toString.call sees the proxy.
                    return typeof f === "function" &&
                        f.toString().indexOf("gifs?.allowSending") !== -1;
                } catch(e) { return false; }
            }
            var id = null;
            try {
                var candidate = Vencord.Webpack.findModuleId(
                    /hideGifFavorites:[A-Za-z_$][\w$]*,onSelectGIF:/);
                if (candidate != null && factoryHasOverlay(candidate)) {
                    id = candidate;
                }
            } catch(e) {}
            if (id == null) {
                for (var key in wreq.c) {
                    try {
                        var entry = wreq.c[key];
                        if (entry && entry.exports &&
                            entry.exports.A === OverlayModule.A &&
                            factoryHasOverlay(key)) {
                            id = key;
                            break;
                        }
                    } catch(e) {}
                }
            }
            if (id == null || typeof wreq.m[id] !== "function") {
                console.warn("[Vendroid] GIF: overlay factory id not found");
                return false;
            }
            var code = wreq.m[id].toString();
            var next = code
                .replace(/gifs\?\.allowSending&&![A-Za-z_$][\w$]*\.Fr/,
                    "gifs?.allowSending&&!0/*vde-gif-tab*/")
                .replace(/([A-Za-z_$][\w$]*)=[A-Za-z_$][\w$]*&&([A-Za-z_$][\w$]*),([A-Za-z_$][\w$]*)=[A-Za-z_$][\w$]*&&!([A-Za-z_$][\w$]*),/,
                    "$1=!1,$3=!0/*vde-gif-nav*/,");
            if (next.indexOf("vde-gif-tab") === -1 ||
                next.indexOf("vde-gif-nav") === -1) {
                var at = code.indexOf("gifs?.allowSending");
                console.warn("[Vendroid] GIF: overlay factory anchors missing (id=" +
                    id + ", source=" +
                    (at >= 0 ? code.slice(at, at + 120) : "no gifs anchor") + ")");
                return false;
            }
            var isArrow = next.charAt(0) === "(";
            var expr = "0," + (!isArrow ? "function" : "") +
                next.slice(next.indexOf("("));
            var factory = (0, eval)(
                "// Webpack Module " + id + " - GIF re-executed by Vendroid\n" +
                expr + "\n//# sourceURL=https://discord.com/vencord-gif-overlay");
            var fake = { exports: {} };
            factory(fake, fake.exports, wreq);
            var fresh = fake.exports && fake.exports.A;
            if (!fresh || typeof fresh.type !== "function") {
                console.warn("[Vendroid] GIF: re-executed overlay has no component");
                return false;
            }
            OverlayModule.A.type = fresh.type;
            if (OverlayModule.A.type !== fresh.type) {
                console.warn("[Vendroid] GIF: live memo type is read-only");
                return false;
            }
            console.warn("[Vendroid] GIF: re-executed overlay factory " + id +
                " (patched gates)");
            return true;
        } catch(e) {
            console.error("[Vendroid] GIF: overlay re-execution failed: " +
                e.message);
            return false;
        }
    }

    // Props can hide the tab too: onlyEmojis, a missing gifs.allowSending,
    // or the emoji-packs gate. Wrap the memo render and clear them only when
    // onSelectGIF is present.
    function vendroidPatchGifOverlayProps() {
        if (_vendroidGifOverlayPatched) return true;
        var OverlayModule = null;
        try {
            OverlayModule = findModuleByCode([
                /hideGifFavorites:[A-Za-z_$][\w$]*,onSelectGIF:[A-Za-z_$][\w$]*,/,
                "hideGifFavorites"
            ]);
        } catch(e) {}
        if (!OverlayModule || !OverlayModule.A ||
            typeof OverlayModule.A.type !== "function") {
            console.warn("[Vendroid] GIF: overlay component not found for prop wrapper");
            return false;
        }
        vendroidReloadGifOverlay(OverlayModule);
        var orig = OverlayModule.A.type;
        try {
            var origSrc = Function.prototype.toString.call(orig);
            var mark = origSrc.indexOf("gifs?.allowSending");
            console.warn("[Vendroid] GIF: executed overlay source " +
                (mark >= 0 ? origSrc.slice(mark, mark + 260) : "anchor-gone"));
        } catch(e) {}
        var patched = function(props) {
            try {
                if (!_vendroidGifOverlayLogged) {
                    _vendroidGifOverlayLogged = true;
                    var t0 = props && props.type;
                    console.warn("[Vendroid] GIF: overlay props allow=" +
                        !!(t0 && t0.gifs && t0.gifs.allowSending) +
                        " onlyEmojis=" + !!(t0 && t0.expressionPicker &&
                            t0.expressionPicker.onlyEmojis) +
                        " hasOnSelectGIF=" + !!(props && props.onSelectGIF));
                }
                if (props && props.onSelectGIF && props.type) {
                    var t = props.type;
                    var g = t.gifs;
                    var ep = t.expressionPicker;
                    if (!g || g.allowSending !== true ||
                        (ep && ep.onlyEmojis === true)) {
                        var nextType = Object.assign({}, t, {
                            gifs: Object.assign({}, g, { allowSending: true }),
                            expressionPicker: Object.assign({}, ep,
                                { onlyEmojis: false })
                        });
                        var nextProps = Object.assign({}, props, { type: nextType });
                        return orig.call(this, nextProps);
                    }
                }
            } catch(e) {
                console.error("[Vendroid] GIF: prop wrapper failed: " + e.message);
            }
            return orig.apply(this, arguments);
        };
        try {
            OverlayModule.A.type = patched;
            if (OverlayModule.A.type !== patched) {
                throw new Error("memo .type is read-only");
            }
            _vendroidGifOverlayPatched = true;
            vendroidSetPatchStatus("gif", "props", "wrapped");
            console.warn("[Vendroid] GIF: overlay prop wrapper installed");
            return true;
        } catch(e) {
            console.error("[Vendroid] GIF: prop wrapper install failed: " + e.message);
            return false;
        }
    }

    function setupGifPickerButton() {
        if (_vendroidGifPatched) return;
        try {
            if (typeof Vencord === "undefined" || !Vencord.Webpack) {
                if (_vendroidGifRetryCount++ < 300) setTimeout(setupGifPickerButton, 50);
                return;
            }

            var ExpressionPickerStore = null;
            try {
                ExpressionPickerStore = vendroidFindByProps("r$", "ed", "v8", "RQ");
            } catch(e) {}
            if (!ExpressionPickerStore) {
                // findByProps sees only executed modules. findModuleByCode
                // scans the registry and force-loads lazy store and view modules.
                try { ExpressionPickerStore = findModuleByCode(["expression-picker-last-active-view"]); } catch(e) {}
            }
            var ExpressionPickerViewTypes = null;
            try {
                ExpressionPickerViewTypes = vendroidFindByProps("kx", "VQ", "wp");
            } catch(e) {}
            if (!ExpressionPickerViewTypes) {
                try { ExpressionPickerViewTypes = findModuleByCode(["expression-picker-chat-input-button"]); } catch(e) {}
            }

            if (!ExpressionPickerStore || !ExpressionPickerStore.r$ ||
                !ExpressionPickerViewTypes || !ExpressionPickerViewTypes.kx) {
                if (_vendroidGifRetryCount++ < 300) {
                    setTimeout(setupGifPickerButton, 50);
                } else if (!_vendroidGifGaveUp) {
                    _vendroidGifGaveUp = true;
                    _vendroidGifPatched = true;
                    vendroidSetPatchStatus("gif", "runtime", "store-missing");
                    console.warn("[Vendroid] GIF: picker store not found after 300 attempts; " +
                        "factory patch state is in the VDE stats");
                }
                return;
            }

            vendroidPatchGifOverlayProps();

            // Reset lastActiveView so the emoji button opens emoji by default.
            try {
                ExpressionPickerStore.U(ExpressionPickerViewTypes.kx.EMOJI);
                vendroidSetPatchStatus("gif", "runtime", "ready");
            } catch(e) {
                vendroidSetPatchStatus("gif", "runtime", "reset-failed");
                console.error("[Vendroid] GIF: failed to reset lastActiveView: " + e.message);
            }

            _vendroidGifPatched = true;

        } catch(e) {
            console.error("[Vendroid] setupGifPickerButton failed: " + e.message);
            if (_vendroidGifRetryCount++ < 300) setTimeout(setupGifPickerButton, 50);
        }
    }


    // Native channel search: mount the built-in search widget in our own
    // top-anchored card, with the native results dock behind it.
    //
    // openNativeSearch locates the launcher by source signature and parses
    // its createPromise loader for the chunk set and widget module id. A
    // pinned snapshot is used only when discovery fails. The widget does not
    // read PlatformUtils.Fr.
    //
    // Do not reassign webpack exports: they are non-configurable. Factory
    // source patches are the reliable hook. A dedicated ReactDOM root also
    // avoids commit-time failures in the modal layer.

    // Result memo for discoverSearchTargets. The TTL lets a long-lived
    // session re-resolve after the client swaps bundles.
    var _vendroidDiscovered = null;
    var _VENDROID_DISCOVERY_TTL = 10 * 60 * 1000;

    // Source signatures for the HeaderBar launcher. Any match is accepted.
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
                        // overlay card, bypassing the modal layer
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
                            // Mount path B (fallback): the modal system.
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
    // renders behind it, fully visible and clickable. Dedicated createRoot
    // with no modal layer involved.
    function openSearchOverlayWithRoot(RD, R, WidgetComp, ctxKey, fdUnsubFactory) {
        // Reuse a live instance for the same channel. Re-show it without
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

    // Pinned HeaderBar loader snapshot, used only when discovery fails.
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

