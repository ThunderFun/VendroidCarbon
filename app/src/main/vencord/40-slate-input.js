    function setupSlateOverride() {
        if (_vendroidSlateOverrideDone) return;
        try {
            if (typeof Vencord === "undefined" || !Vencord.Webpack || !Vencord.Webpack.findByProps) {
                if (_vendroidSlateOverrideRetries++ < 300) setTimeout(setupSlateOverride, 50);
                return;
            }
            var PlatformUtils = null;
            try {
                PlatformUtils = Vencord.Webpack.findByProps("isAndroidWeb", "isDesktop", "isIOS");
            } catch(e) {}
            if (!PlatformUtils || typeof PlatformUtils.isAndroidWeb !== "function") {
                if (_vendroidSlateOverrideRetries++ < 300) setTimeout(setupSlateOverride, 50);
                return;
            }
            var origResult = false;
            try { origResult = PlatformUtils.isAndroidWeb.call(); } catch(e) {}
            // Capture the pre-override result for the ported mstyle gate
            // (see isAndroidWebDevicePreOverride).
            _vendroidOrigIsAndroidWeb = origResult;
            if (!origResult) {
                _vendroidSlateOverrideDone = true;
                return;
            }
            try {
                Object.defineProperty(PlatformUtils, "isAndroidWeb", {
                    value: function() { return false; },
                    writable: true, configurable: true, enumerable: true
                });
                _vendroidSlateOverrideDone = true;
                console.warn("[Vendroid] Slate override: isAndroidWeb → false (enables Slate + command browser)");
            } catch(e) {
                try {
                    PlatformUtils.isAndroidWeb = function() { return false; };
                    _vendroidSlateOverrideDone = true;
                    console.warn("[Vendroid] Slate override: isAndroidWeb → false (fallback)");
                } catch(e2) {
                    console.error("[Vendroid] Slate override failed: " + e2.message);
                }
            }
            // The platform result is now captured; the mstyle gate can decide.
            applyVendroidPluginCss();
        } catch(e) {
            console.error("[Vendroid] setupSlateOverride error: " + e.message);
            if (_vendroidSlateOverrideRetries++ < 300) setTimeout(setupSlateOverride, 50);
        }
    }

    // Re-enable autocorrect on the Slate editor.  setupSlateOverride() makes
    // Discord render its desktop editor, which sets autocorrect="off" /
    // spellcheck="false" on the contenteditable div.  On Android WebView a
    // contenteditable without autocorrect="on" never receives IME corrections,
    // so suggestions appear but never apply.  A MutationObserver re-applies the
    // attributes so React re-renders can't revert them.
    var _vendroidAutocorrectInstalled = false;

    function applyAutocorrectAttrs(el) {
        var curAC = el.getAttribute("autocorrect");
        var curSC = el.getAttribute("spellcheck");
        if (curAC !== "on" || curSC !== "true") {
            console.warn("[Vendroid] autocorrect attr fix: autocorrect=" + curAC + "→on spellcheck=" + curSC + "→true");
        }
        if (el.getAttribute("autocorrect") !== "on") el.setAttribute("autocorrect", "on");
        if (el.getAttribute("spellcheck") !== "true") el.setAttribute("spellcheck", "true");
    }

    function setupSlateAutocorrect() {
        if (_vendroidAutocorrectInstalled) return;
        try {
            var existing = document.querySelector("[data-slate-editor]");
            if (existing) applyAutocorrectAttrs(existing);

            var observer = new MutationObserver(function(mutations) {
                for (var i = 0; i < mutations.length; i++) {
                    var m = mutations[i];
                    if (m.type === "attributes") {
                        if (m.target.getAttribute("data-slate-editor") === "true") {
                            applyAutocorrectAttrs(m.target);
                        }
                    } else if (m.type === "childList") {
                        for (var j = 0; j < m.addedNodes.length; j++) {
                            var node = m.addedNodes[j];
                            if (node.nodeType !== 1) continue;
                            if (node.getAttribute && node.getAttribute("data-slate-editor") === "true") {
                                applyAutocorrectAttrs(node);
                            } else if (node.querySelector) {
                                var ed = node.querySelector("[data-slate-editor]");
                                if (ed) applyAutocorrectAttrs(ed);
                            }
                        }
                    }
                }
            });
            observer.observe(document.body || document.documentElement, {
                childList: true, subtree: true, attributes: true,
                attributeFilter: ["autocorrect", "spellcheck"]
            });
            _vendroidAutocorrectInstalled = true;
            console.warn("[Vendroid] Slate autocorrect enabled");
        } catch(e) {
            console.error("[Vendroid] setupSlateAutocorrect error: " + e.message);
        }
    }

    // Slate Android input fix. Android WebView's beforeinput events desync
    // Slate's model (backspace, text insertion, composition). We intercept
    // at capture phase, preventDefault, and route through Slate's editor API.
    var _vendroidSlateFixInstalled = false;

    function setupSlateInputFix() {
        if (_vendroidSlateFixInstalled) return;
        try {
            var composingText = null;  // last composition text inserted via Slate
            var isComposing = false;

            // Helper to read the current text from the Slate editor DOM
            function getEditorText() {
                var el = document.querySelector("[data-slate-editor]");
                return el ? el.textContent : "(no editor)";
            }

            document.addEventListener("compositionstart", function(e) {
                var target = e.target;
                if (!target || !target.hasAttribute) return;
                if (!target.hasAttribute("data-slate-editor") &&
                    !target.closest("[data-slate-editor]")) return;
                isComposing = true;
                composingText = null;
                if (VENDROID_VERBOSE_INPUT) console.warn("[Vendroid] compositionstart");
            }, true);

            document.addEventListener("compositionend", function(e) {
                var target = e.target;
                if (!target || !target.hasAttribute) return;
                if (!target.hasAttribute("data-slate-editor") &&
                    !target.closest("[data-slate-editor]")) return;
                isComposing = false;
                composingText = null;
                // Note: do NOT log e.data here — it can contain typed message text.
                if (VENDROID_VERBOSE_INPUT) console.warn("[Vendroid] compositionend");
            }, true);

            document.addEventListener("beforeinput", function(e) {
                var target = e.target;
                if (!target || !target.hasAttribute) return;
                if (!target.hasAttribute("data-slate-editor") &&
                    !target.closest("[data-slate-editor]")) return;

                var inputType = e.inputType;
                var data = e.data;

                // Log beforeinput metadata for the Slate editor. Do NOT log
                // `data` — it carries the typed message content.
                if (VENDROID_VERBOSE_INPUT) console.warn("[Vendroid] beforeinput type=" + inputType + " len=" + (data ? data.length : 0) + " composing=" + isComposing);

                // Only handle types needing manual Slate routing. Skip
                // insertCompositionText — Discord's Slate plugin has its own
                // deferred-diff system that batches keystrokes and flushes on
                // compositionend, keeping the model in sync and the command
                // autocomplete filter live.
                if (inputType !== "deleteContentBackward" &&
                    inputType !== "deleteContentForward" &&
                    inputType !== "insertText" &&
                    inputType !== "insertReplacementText") {
                    return;
                }

                var editor = findSlateEditorFromDOM(target);
                if (!editor) return;

                // After routing through Slate's API, dispatch a synthetic
                // InputEvent so Discord's autocomplete plugin (which filters
                // on native 'input' events) sees the change. Without this,
                // editor.insertText() updates Slate's model and onChange but
                // not the DOM 'input' event we suppressed via preventDefault.
                // Only needed in slash-command context (editor starts with
                // "/"); elsewhere Discord's plugin handles re-rendering and
                // the popout isn't open.
                function notifyDiscordInputEvent(evType, evData) {
                    try {
                        var curEditorText = getEditorText();
                        if (!curEditorText || curEditorText.charCodeAt(0) !== 0x2F) return; // only in slash context
                        var ev = new InputEvent("input", {
                            data: evData || null,
                            inputType: evType || "insertText",
                            bubbles: true,
                            cancelable: false,
                            composed: false
                        });
                        target.dispatchEvent(ev);
                        if (VENDROID_VERBOSE_INPUT) console.warn("[Vendroid] synthetic input dispatched. type=" + (evType || "insertText"));
                    } catch(e) {
                        console.error("[Vendroid] synthetic input error: " + e.message);
                    }
                }

                if (inputType === "deleteContentBackward") {
                    var sel = document.getSelection();
                    if (!sel || !sel.isCollapsed) return; // non-collapsed handled by Slate
                    e.preventDefault();
                    e.stopPropagation();
                    try { editor.deleteBackward("character"); } catch(err) {
                        console.error("[Vendroid] Slate backspace error: " + err.message);
                    }
                    notifyDiscordInputEvent("deleteContentBackward", null);
                } else if (inputType === "deleteContentForward") {
                    var sel2 = document.getSelection();
                    if (!sel2 || !sel2.isCollapsed) return;
                    e.preventDefault();
                    e.stopPropagation();
                    try { editor.deleteForward("character"); } catch(err) {
                        console.error("[Vendroid] Slate delete error: " + err.message);
                    }
                    notifyDiscordInputEvent("deleteContentForward", null);
                } else if (inputType === "insertText") {
                    // Direct text insertion (non-composition, e.g. paste, quick type)
                    e.preventDefault();
                    e.stopPropagation();
                    try {
                        editor.insertText(data || "");
                        if (VENDROID_VERBOSE_INPUT) console.warn("[Vendroid] insertText done.");
                    } catch(err) {
                        console.error("[Vendroid] Slate insertText error: " + err.message);
                    }
                    notifyDiscordInputEvent("insertText", data);
                } else if (inputType === "insertReplacementText") {
                    // IME autocorrect / suggestion completion.  Same delete-then-
                    // insert pattern: delete the characters being replaced, then
                    // insert the corrected text via Slate's API.
                    e.preventDefault();
                    e.stopPropagation();
                    try {
                        var delLen = 0;
                        var ranges = e.getTargetRanges ? e.getTargetRanges() : null;
                        if (ranges && ranges.length > 0) {
                            delLen = ranges[0].endOffset - ranges[0].startOffset;
                        } else if (composingText && composingText.length > 0) {
                            delLen = composingText.length;
                        }
                        // Do NOT log `data` here — it's the replacement text.
                        if (VENDROID_VERBOSE_INPUT) console.warn("[Vendroid] insertReplacementText delLen=" + delLen);
                        for (var r = 0; r < delLen; r++) {
                            editor.deleteBackward("character");
                        }
                        if (data && data.length > 0) {
                            editor.insertText(data);
                        }
                        composingText = data || "";
                    } catch(err) {
                        console.error("[Vendroid] Slate replacement error: " + err.message);
                    }
                    notifyDiscordInputEvent("insertReplacementText", data);
                }
            }, true); // capture phase, runs before Slate's handler

            _vendroidSlateFixInstalled = true;
            console.warn("[Vendroid] Slate input fix installed (backspace + text + autocorrect)");
        } catch(e) {
            console.error("[Vendroid] setupSlateInputFix failed: " + e.message);
        }
    }

    // Walk the React fiber tree to find the Slate editor instance.
    function findSlateEditorFromDOM(element) {
        try {
            var fiberKey = null;
            for (var key in element) {
                if (key.startsWith("__reactFiber") || key.startsWith("__reactInternalInstance")) {
                    fiberKey = key;
                    break;
                }
            }
            if (!fiberKey) return null;
            var fiber = element[fiberKey];
            var visited = 0;
            while (fiber && visited < 30) {
                var props = fiber.memoizedProps;
                if (props && props.editor && typeof props.editor.deleteBackward === "function" &&
                    typeof props.editor.insertText === "function") {
                    return props.editor;
                }
                fiber = fiber.return;
                visited++;
            }
        } catch(e) {}
        return null;
    }

    // Built-in text command dispatcher (/me, /tableflip, /shrug, …).
    // Safety net for cases where the command browser's text-transform path
    // doesn't fire.  Intercepts sendMessage and applies the transform.
    // MessageEvents.addMessagePreSendListener no longer exists in the
    // mobile bundle, so we patch MessageActions.sendMessage directly.

    // Built-in TEXT commands whose execute() is a pure string transform (from
    // Discord module 917012, array `x`). Commands needing stores/RPCs (/nick,
    // /kick, /ban, /timeout, /thread, /msg, /roll-dice) are omitted and fall
    // through to the command browser / plain text.
    var VENDROID_TEXT_COMMANDS = {
        shrug:    function(msg) { return { content: (msg + " \xaf\\_(\u30C4)_/\xaf").trim() }; },
        tableflip:function(msg) { return { content: (msg + " (\u256F\u00B0\u25A1\u00B0\u256F\uFE35) \u253B\u2501\u253B").trim() }; },
        unflip:   function(msg) { return { content: (msg + " \u252C\u2500\u252C\u30CE( \xBA _ \xBA\u30CE)").trim() }; },
        tts:      function(msg) { return { content: msg, tts: true }; },
        me:       function(msg) { return { content: "_" + msg + "_" }; },
        spoiler:  function(msg) { return { content: ("||" + msg + "||").trim() }; }
    };

    var _vendroidCmdPatched = false;
    var _vendroidCmdRetryCount = 0;

