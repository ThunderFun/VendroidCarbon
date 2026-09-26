    function injectSettingsRowsIfMissing() {
        try {
            if (document.hidden) return;
            // .vde-rows-section exists only while the Vendroid Settings tab
            // is mounted; append into the last instance found.
            var sections = document.querySelectorAll('.vde-rows-section');
            if (!sections || sections.length === 0) return;
            var section = sections[sections.length - 1];

            injectAppIconPickerIfMissing(section);
            injectLogsRowIfMissing(section);
            injectFirewallRowIfMissing(section);
            injectPrivacyToggleIfMissing(section);
            injectLinkConfirmToggleIfMissing(section);
            injectRememberChannelToggleIfMissing(section);
            injectGesturesToggleIfMissing(section);
            injectSupportWarningsToggleIfMissing(section);
        } catch(e) {
            console.error('[Vendroid] injectSettingsRowsIfMissing error: ' + e.message);
        }
    }

    // Section headers. data-vde-section markers make repeat poller ticks
    // no-ops; injectors call this right before appending their row, so
    // first-pass DOM order matches the injector order. 20px/700 matches
    // Discord's FormTitle h2. Later headers get a 40px section break; the
    // first sits flush against the panel's own top padding.
    function ensureSectionHeader(section, marker, title) {
        if (section.querySelector('[data-vde-section="' + marker + '"]')) return;
        var header = document.createElement('div');
        header.setAttribute('data-vde-section', marker);
        header.style.cssText = 'color:var(--header-primary);font-size:20px;font-weight:700;line-height:24px;width:100%;margin-bottom:4px;' +
            (section.firstChild ? 'margin-top:40px;' : '');
        header.textContent = title;
        section.appendChild(header);
    }

    // One launcher activity-alias per icon (IconAliasManager.ICON_NAMES).
    // The bridge exposes changeAppIcon(token, id) as the privileged write
    // and getCurrentAppIcon for the initial selection; the bootstrap wrapper
    // prepends the session token, so page JS passes only the id.
    var VDE_ICON_CHOICES = ['Main', 'Jolly', 'Discord', 'Retro', 'TS12'];

    function currentAppIconName() {
        try {
            var name = window.VencordMobileNative && window.VencordMobileNative.getCurrentAppIcon();
            return name && VDE_ICON_CHOICES.indexOf(name) !== -1 ? name : null;
        } catch (e) {
            return null;
        }
    }

    function injectAppIconPickerIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-app-icon-picker]')) return;
            ensureSectionHeader(section, 'app-icon', 'App icon');

            var wrap = document.createElement('div');
            wrap.setAttribute('data-vde-app-icon-picker', '1');
            wrap.style.cssText = 'width:100%;';

            var desc = document.createElement('div');
            desc.className = 'vde-component-setting-description';
            desc.style.cssText = 'color:var(--text-muted);margin-bottom:10px;';
            desc.textContent = 'Choose the launcher icon. Some launchers take a moment to show the change.';
            wrap.appendChild(desc);

            var list = document.createElement('div');
            list.style.cssText = 'display:flex;flex-direction:column;width:100%;';
            wrap.appendChild(list);

            var rows = [];
            function setDotState(dot, selected) {
                dot.style.border = selected ? '6px solid var(--brand-primary,#5865f2)' : '2px solid var(--interactive-muted,#72767d)';
                dot.style.background = selected ? '#fff' : 'transparent';
            }

            function renderSelection(active) {
                rows.forEach(function (entry) {
                    setDotState(entry.dot, entry.name === active);
                });
            }

            VDE_ICON_CHOICES.forEach(function (name) {
                var row = document.createElement('div');
                row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 0;cursor:pointer;-webkit-tap-highlight-color:transparent;';

                var label = document.createElement('div');
                label.style.cssText = 'color:var(--header-primary);font-size:14px;font-weight:500;';
                label.textContent = name;
                row.appendChild(label);

                var dot = document.createElement('span');
                dot.style.cssText = 'width:20px;height:20px;border-radius:50%;flex:none;box-sizing:border-box;';
                row.appendChild(dot);

                row.addEventListener('click', function () {
                    // The busy flag stops double-taps from stacking launcher
                    // refreshes. changeAppIcon is synchronous across the
                    // bridge, heals stale aliases, and toasts its own
                    // result; re-read to reconcile, falling back to the
                    // clicked name if the getter is unavailable.
                    if (list.getAttribute('data-busy') === '1') return;
                    list.setAttribute('data-busy', '1');
                    try {
                        VencordMobileNative.changeAppIcon(name);
                    } catch (e) {
                        console.error('[Vendroid] changeAppIcon failed: ' + e.message);
                    }
                    renderSelection(currentAppIconName() || name);
                    setTimeout(function () { list.removeAttribute('data-busy'); }, 1500);
                });

                list.appendChild(row);
                rows.push({ name: name, dot: dot });
            });

            renderSelection(currentAppIconName());

            var divider = document.createElement('div');
            divider.className = 'vde-divider-setting';
            divider.style.cssText = 'width:100%;height:1px;border-top:thin solid var(--background-modifier-accent);margin-top:20px;margin-bottom:20px;';
            wrap.appendChild(divider);

            section.appendChild(wrap);
            console.warn('[Vendroid] App icon picker injected into Vendroid settings');
        } catch (e) {
            console.error('[Vendroid] injectAppIconPickerIfMissing error: ' + e.message);
        }
    }

    // Injects a "View logs" row into the Vendroid Settings tab.
    // Called by the shared settings poller with the resolved target <section>.
    function injectLogsRowIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-logs-btn]')) return;
            ensureSectionHeader(section, 'diagnostics', 'Diagnostics');

            var wrap = document.createElement('div');
            wrap.setAttribute('data-vde-logs-btn', '1');
            wrap.style.cssText = 'margin-top:20px;width:100%;';

            var title = document.createElement('div');
            title.className = 'vde-component-setting-title';
            title.style.cssText = 'color:var(--header-primary);margin-bottom:8px;font-weight:600;';
            title.textContent = 'View logs';
            wrap.appendChild(title);

            var desc = document.createElement('div');
            desc.className = 'vde-component-setting-description';
            desc.style.cssText = 'color:var(--text-muted);margin-bottom:10px;';
            desc.textContent = 'Open the Vendroid in-app log viewer. Useful for debugging patching, injection, or loading issues.';
            wrap.appendChild(desc);

            var btn = document.createElement('button');
            btn.type = 'button';
            btn.textContent = 'View logs';
            btn.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;padding:8px 16px;background:var(--brand-primary,#5865f2);color:#fff;border:none;border-radius:8px;font-size:14px;font-weight:600;cursor:pointer;-webkit-tap-highlight-color:transparent;';
            btn.addEventListener('click', function() {
                try { VencordMobileNative.requestNative('openLogs'); } catch(e) {
                    console.error('[Vendroid] openLogs failed: ' + e.message);
                }
            });
            wrap.appendChild(btn);

            var divider = document.createElement('div');
            divider.className = 'vde-divider-setting';
            divider.style.cssText = 'width:100%;height:1px;border-top:thin solid var(--background-modifier-accent);margin-top:20px;margin-bottom:20px;';
            wrap.appendChild(divider);

            section.appendChild(wrap);
            console.warn('[Vendroid] Logs row injected into Vendroid settings');
        } catch(e) {
            console.error('[Vendroid] injectLogsRowIfMissing error: ' + e.message);
        }
    }

    // Injects a "Firewall" row into the Vendroid Settings tab.
    // Called by the shared settings poller with the resolved target <section>.
    function injectFirewallRowIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-firewall-btn]')) return;
            ensureSectionHeader(section, 'diagnostics', 'Diagnostics');

            var wrap = document.createElement('div');
            wrap.setAttribute('data-vde-firewall-btn', '1');
            wrap.style.cssText = 'margin-top:20px;width:100%;';

            var title = document.createElement('div');
            title.className = 'vde-component-setting-title';
            title.style.cssText = 'color:var(--header-primary);margin-bottom:8px;font-weight:600;';
            title.textContent = 'Firewall';
            wrap.appendChild(title);

            var desc = document.createElement('div');
            desc.className = 'vde-component-setting-description';
            desc.style.cssText = 'color:var(--text-muted);margin-bottom:10px;';
            desc.textContent = 'Edit the domain allowlist that controls which hosts the WebView and in-page JS can contact.';
            wrap.appendChild(desc);

            var btn = document.createElement('button');
            btn.type = 'button';
            btn.textContent = 'Open firewall editor';
            btn.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;padding:8px 16px;background:var(--brand-primary,#5865f2);color:#fff;border:none;border-radius:8px;font-size:14px;font-weight:600;cursor:pointer;-webkit-tap-highlight-color:transparent;';
            btn.addEventListener('click', function() {
                try { VencordMobileNative.requestNative('openFirewallEditor'); } catch(e) {
                    console.error('[Vendroid] openFirewallEditor failed: ' + e.message);
                }
            });
            wrap.appendChild(btn);

            var divider = document.createElement('div');
            divider.className = 'vde-divider-setting';
            divider.style.cssText = 'width:100%;height:1px;border-top:thin solid var(--background-modifier-accent);margin-top:20px;margin-bottom:20px;';
            wrap.appendChild(divider);

            section.appendChild(wrap);
            console.warn('[Vendroid] Firewall row injected into Vendroid settings');
        } catch(e) {
            console.error('[Vendroid] injectFirewallRowIfMissing error: ' + e.message);
        }
    }

    // Injects a "Block typing indicator" toggle into the Vendroid Settings
    // tab. Called by the shared settings poller with the resolved target
    // <section>.
    function injectPrivacyToggleIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-privacy-toggle]')) return;
            ensureSectionHeader(section, 'privacy', 'Privacy');

            var wrap = document.createElement('div');
            wrap.setAttribute('data-vde-privacy-toggle', '1');
            wrap.style.cssText = 'margin-top:20px;width:100%;';

            var row = document.createElement('div');
            row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:12px;';

            var labelCol = document.createElement('div');
            labelCol.style.cssText = 'flex:1 1 auto;';

            var label = document.createElement('div');
            label.style.cssText = 'color:var(--header-primary);font-size:14px;font-weight:500;';
            label.textContent = 'Block typing indicator';
            labelCol.appendChild(label);

            var desc = document.createElement('div');
            desc.className = 'vde-component-setting-description';
            desc.style.cssText = 'color:var(--text-muted);margin-top:4px;font-size:12px;';
            desc.textContent = 'Prevent Discord from sending typing indicators. Telemetry, Sentry, and fingerprinting are always blocked.';
            labelCol.appendChild(desc);

            row.appendChild(labelCol);

            var sw = document.createElement('label');
            sw.style.cssText = 'position:relative;width:44px;height:24px;flex:none;cursor:pointer;';
            var cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.style.cssText = 'opacity:0;width:0;height:0;position:absolute;';
            var sl = document.createElement('span');
            sl.style.cssText = 'position:absolute;inset:0;background:var(--background-modifier-accent,#36393f);border-radius:24px;transition:background .2s;';
            var knob = document.createElement('span');
            knob.style.cssText = 'position:absolute;width:18px;height:18px;left:3px;top:3px;background:#fff;border-radius:50%;transition:transform .2s;';
            sl.appendChild(knob);
            sw.appendChild(cb);
            sw.appendChild(sl);
            row.appendChild(sw);

            var checked = false;
            try {
                checked = VencordMobileNative.getBool('vendroid_blockTypingIndicator', false);
            } catch(e) {
                console.error('[Vendroid] getBool for typing toggle failed: ' + e.message);
            }
            cb.checked = checked;
            if (checked) {
                sl.style.background = 'var(--brand-primary,#5865f2)';
                knob.style.transform = 'translateX(20px)';
            }

            // setBool can silently early-return (strict-domain gate, rate
            // limiter, key allowlist), so re-read the persisted value and
            // reconcile the UI to the actual state.
            cb.addEventListener('change', function() {
                try {
                    VencordMobileNative.setBool('vendroid_blockTypingIndicator', cb.checked);
                    var persisted = VencordMobileNative.getBool('vendroid_blockTypingIndicator', false);
                    if (persisted !== cb.checked) {
                        cb.checked = persisted;
                    }
                    if (cb.checked) {
                        sl.style.background = 'var(--brand-primary,#5865f2)';
                        knob.style.transform = 'translateX(20px)';
                    } else {
                        sl.style.background = 'var(--background-modifier-accent,#36393f)';
                        knob.style.transform = 'translateX(0)';
                    }
                } catch(e) {
                    console.error('[Vendroid] setBool for typing toggle failed: ' + e.message);
                    cb.checked = !cb.checked;
                    if (cb.checked) {
                        sl.style.background = 'var(--brand-primary,#5865f2)';
                        knob.style.transform = 'translateX(20px)';
                    } else {
                        sl.style.background = 'var(--background-modifier-accent,#36393f)';
                        knob.style.transform = 'translateX(0)';
                    }
                }
            });

            wrap.appendChild(row);

            var divider = document.createElement('div');
            divider.className = 'vde-divider-setting';
            divider.style.cssText = 'width:100%;height:1px;border-top:thin solid var(--background-modifier-accent);margin-top:20px;margin-bottom:20px;';
            wrap.appendChild(divider);

            section.appendChild(wrap);
            console.warn('[Vendroid] Privacy toggle injected into Vendroid settings');
        } catch(e) {
            console.error('[Vendroid] injectPrivacyToggleIfMissing error: ' + e.message);
        }
    }

    // Injects a "Confirm external links" toggle into the Vendroid Settings
    // tab. Called by the shared settings poller with the resolved target
    // <section>.
    function injectLinkConfirmToggleIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-link-confirm-toggle]')) return;
            ensureSectionHeader(section, 'browsing', 'Browsing');

            var wrap = document.createElement('div');
            wrap.setAttribute('data-vde-link-confirm-toggle', '1');
            wrap.style.cssText = 'margin-top:20px;width:100%;';

            var row = document.createElement('div');
            row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:12px;';

            var labelCol = document.createElement('div');
            labelCol.style.cssText = 'flex:1 1 auto;';

            var label = document.createElement('div');
            label.style.cssText = 'color:var(--header-primary);font-size:14px;font-weight:500;';
            label.textContent = 'Confirm external links';
            labelCol.appendChild(label);

            var desc = document.createElement('div');
            desc.className = 'vde-component-setting-description';
            desc.style.cssText = 'color:var(--text-muted);margin-top:4px;font-size:12px;';
            desc.textContent = 'Show a Copy / Open / Share prompt when tapping a link that would leave Discord.';
            labelCol.appendChild(desc);

            row.appendChild(labelCol);

            var sw = document.createElement('label');
            sw.style.cssText = 'position:relative;width:44px;height:24px;flex:none;cursor:pointer;';
            var cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.style.cssText = 'opacity:0;width:0;height:0;position:absolute;';
            var sl = document.createElement('span');
            sl.style.cssText = 'position:absolute;inset:0;background:var(--background-modifier-accent,#36393f);border-radius:24px;transition:background .2s;';
            var knob = document.createElement('span');
            knob.style.cssText = 'position:absolute;width:18px;height:18px;left:3px;top:3px;background:#fff;border-radius:50%;transition:transform .2s;';
            sl.appendChild(knob);
            sw.appendChild(cb);
            sw.appendChild(sl);
            row.appendChild(sw);

            // Default ON; matches the SharedPreferences default in MainActivity.
            var checked = true;
            try {
                checked = VencordMobileNative.getBool('vendroid_confirmExternalLinks', true);
            } catch(e) {
                console.error('[Vendroid] getBool for link-confirm toggle failed: ' + e.message);
            }
            cb.checked = checked;
            if (checked) {
                sl.style.background = 'var(--brand-primary,#5865f2)';
                knob.style.transform = 'translateX(20px)';
            }

            cb.addEventListener('change', function() {
                try {
                    VencordMobileNative.setBool('vendroid_confirmExternalLinks', cb.checked);
                    var persisted = VencordMobileNative.getBool('vendroid_confirmExternalLinks', true);
                    if (persisted !== cb.checked) {
                        cb.checked = persisted;
                    }
                    if (cb.checked) {
                        sl.style.background = 'var(--brand-primary,#5865f2)';
                        knob.style.transform = 'translateX(20px)';
                    } else {
                        sl.style.background = 'var(--background-modifier-accent,#36393f)';
                        knob.style.transform = 'translateX(0)';
                    }
                } catch(e) {
                    console.error('[Vendroid] setBool for link-confirm toggle failed: ' + e.message);
                    cb.checked = !cb.checked;
                    if (cb.checked) {
                        sl.style.background = 'var(--brand-primary,#5865f2)';
                        knob.style.transform = 'translateX(20px)';
                    } else {
                        sl.style.background = 'var(--background-modifier-accent,#36393f)';
                        knob.style.transform = 'translateX(0)';
                    }
                }
            });

            wrap.appendChild(row);

            var divider = document.createElement('div');
            divider.className = 'vde-divider-setting';
            divider.style.cssText = 'width:100%;height:1px;border-top:thin solid var(--background-modifier-accent);margin-top:20px;margin-bottom:20px;';
            wrap.appendChild(divider);

            section.appendChild(wrap);
            console.warn('[Vendroid] Link-confirm toggle injected into Vendroid settings');
        } catch(e) {
            console.error('[Vendroid] injectLinkConfirmToggleIfMissing error: ' + e.message);
        }
    }

    // Injects a "Remember last channel" toggle into the Vendroid Settings
    // tab. Called by the shared settings poller with the resolved target
    // <section>.
    function injectRememberChannelToggleIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-remember-channel-toggle]')) return;
            ensureSectionHeader(section, 'browsing', 'Browsing');

            var wrap = document.createElement('div');
            wrap.setAttribute('data-vde-remember-channel-toggle', '1');
            wrap.style.cssText = 'margin-top:20px;width:100%;';

            var row = document.createElement('div');
            row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:12px;';

            var labelCol = document.createElement('div');
            labelCol.style.cssText = 'flex:1 1 auto;';

            var label = document.createElement('div');
            label.style.cssText = 'color:var(--header-primary);font-size:14px;font-weight:500;';
            label.textContent = 'Remember last channel';
            labelCol.appendChild(label);

            var desc = document.createElement('div');
            desc.className = 'vde-component-setting-description';
            desc.style.cssText = 'color:var(--text-muted);margin-top:4px;font-size:12px;';
            desc.textContent = 'Reopen the channel or page you were viewing when you closed the app. Turning this off also clears the saved location.';
            labelCol.appendChild(desc);

            row.appendChild(labelCol);

            var sw = document.createElement('label');
            sw.style.cssText = 'position:relative;width:44px;height:24px;flex:none;cursor:pointer;';
            var cb = document.createElement('input');
            cb.type = 'checkbox';
            cb.style.cssText = 'opacity:0;width:0;height:0;position:absolute;';
            var sl = document.createElement('span');
            sl.style.cssText = 'position:absolute;inset:0;background:var(--background-modifier-accent,#36393f);border-radius:24px;transition:background .2s;';
            var knob = document.createElement('span');
            knob.style.cssText = 'position:absolute;width:18px;height:18px;left:3px;top:3px;background:#fff;border-radius:50%;transition:transform .2s;';
            sl.appendChild(knob);
            sw.appendChild(cb);
            sw.appendChild(sl);
            row.appendChild(sw);

            // Default OFF; matches the SharedPreferences default in MainActivity.
            var checked = false;
            try {
                checked = VencordMobileNative.getBool('vendroid_rememberLastChannel', false);
            } catch(e) {
                console.error('[Vendroid] getBool for remember-channel toggle failed: ' + e.message);
            }
            cb.checked = checked;
            if (checked) {
                sl.style.background = 'var(--brand-primary,#5865f2)';
                knob.style.transform = 'translateX(20px)';
            }

            // setBool can silently early-return (strict-domain gate, rate
            // limiter, key allowlist), so re-read the persisted value and
            // reconcile the UI to the actual state.
            cb.addEventListener('change', function() {
                try {
                    VencordMobileNative.setBool('vendroid_rememberLastChannel', cb.checked);
                    var persisted = VencordMobileNative.getBool('vendroid_rememberLastChannel', false);
                    if (persisted !== cb.checked) {
                        cb.checked = persisted;
                    }
                    if (cb.checked) {
                        sl.style.background = 'var(--brand-primary,#5865f2)';
                        knob.style.transform = 'translateX(20px)';
                    } else {
                        sl.style.background = 'var(--background-modifier-accent,#36393f)';
                        knob.style.transform = 'translateX(0)';
                    }
                } catch(e) {
                    console.error('[Vendroid] setBool for remember-channel toggle failed: ' + e.message);
                    cb.checked = !cb.checked;
                    if (cb.checked) {
                        sl.style.background = 'var(--brand-primary,#5865f2)';
                        knob.style.transform = 'translateX(20px)';
                    } else {
                        sl.style.background = 'var(--background-modifier-accent,#36393f)';
                        knob.style.transform = 'translateX(0)';
                    }
                }
            });

            wrap.appendChild(row);

            var divider = document.createElement('div');
            divider.className = 'vde-divider-setting';
            divider.style.cssText = 'width:100%;height:1px;border-top:thin solid var(--background-modifier-accent);margin-top:20px;margin-bottom:20px;';
            wrap.appendChild(divider);

            section.appendChild(wrap);
            console.warn('[Vendroid] Remember-channel toggle injected into Vendroid settings');
        } catch(e) {
            console.error('[Vendroid] injectRememberChannelToggleIfMissing error: ' + e.message);
        }
    }

    // Shared builder for the ported-feature toggle rows (gestures, support
    // warnings). Same markup and reconcile-on-write behavior as the older
    // hand-rolled toggles above: setBool can silently early-return (key
    // allowlist, rate limiter), so the change handler re-reads the
    // persisted value and reconciles the UI to the actual state.
    function buildVendroidToggleRow(opts) {
        var wrap = document.createElement('div');
        wrap.setAttribute('data-vde-toggle', opts.attr);
        wrap.style.cssText = 'margin-top:20px;width:100%;';

        var row = document.createElement('div');
        row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:12px;';

        var labelCol = document.createElement('div');
        labelCol.style.cssText = 'flex:1 1 auto;';

        var label = document.createElement('div');
        label.style.cssText = 'color:var(--header-primary);font-size:14px;font-weight:500;';
        label.textContent = opts.title;
        labelCol.appendChild(label);

        var desc = document.createElement('div');
        desc.className = 'vde-component-setting-description';
        desc.style.cssText = 'color:var(--text-muted);margin-top:4px;font-size:12px;';
        desc.textContent = opts.description;
        labelCol.appendChild(desc);

        row.appendChild(labelCol);

        var sw = document.createElement('label');
        sw.style.cssText = 'position:relative;width:44px;height:24px;flex:none;cursor:pointer;';
        var cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.style.cssText = 'opacity:0;width:0;height:0;position:absolute;';
        var sl = document.createElement('span');
        sl.style.cssText = 'position:absolute;inset:0;background:var(--background-modifier-accent,#36393f);border-radius:24px;transition:background .2s;';
        var knob = document.createElement('span');
        knob.style.cssText = 'position:absolute;width:18px;height:18px;left:3px;top:3px;background:#fff;border-radius:50%;transition:transform .2s;';
        sl.appendChild(knob);
        sw.appendChild(cb);
        sw.appendChild(sl);
        row.appendChild(sw);

        function render(checked) {
            sl.style.background = checked ? 'var(--brand-primary,#5865f2)' : 'var(--background-modifier-accent,#36393f)';
            knob.style.transform = checked ? 'translateX(20px)' : 'translateX(0)';
        }

        var checked = opts.defaultValue;
        try {
            checked = VencordMobileNative.getBool(opts.prefKey, opts.defaultValue);
        } catch(e) {
            console.error('[Vendroid] getBool for ' + opts.prefKey + ' failed: ' + e.message);
        }
        cb.checked = checked;
        render(checked);

        cb.addEventListener('change', function() {
            try {
                VencordMobileNative.setBool(opts.prefKey, cb.checked);
                var persisted = VencordMobileNative.getBool(opts.prefKey, opts.defaultValue);
                if (persisted !== cb.checked) {
                    cb.checked = persisted;
                }
                render(cb.checked);
            } catch(e) {
                console.error('[Vendroid] setBool for ' + opts.prefKey + ' failed: ' + e.message);
                cb.checked = !cb.checked;
                render(cb.checked);
            }
        });

        wrap.appendChild(row);

        var divider = document.createElement('div');
        divider.className = 'vde-divider-setting';
        divider.style.cssText = 'width:100%;height:1px;border-top:thin solid var(--background-modifier-accent);margin-top:20px;margin-bottom:20px;';
        wrap.appendChild(divider);
        return wrap;
    }

    // Ported vendroidEnhancements enableGestures setting. The pref is read
    // live by the swipe handler in setupGestures.
    function injectGesturesToggleIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-toggle="gestures"]')) return;
            ensureSectionHeader(section, 'navigation', 'Navigation');
            section.appendChild(buildVendroidToggleRow({
                attr: 'gestures',
                prefKey: 'vendroid_gestures',
                defaultValue: true,
                title: 'Gesture navigation',
                description: 'Swipe horizontally to move between the channel list, chat, and the member list.'
            }));
            console.warn('[Vendroid] Gestures toggle injected into Vendroid settings');
        } catch(e) {
            console.error('[Vendroid] injectGesturesToggleIfMissing error: ' + e.message);
        }
    }

    // Ported vendroidEnhancements allowSupportMessageSending setting,
    // inverted: the row enables/disables the support-channel warning modal.
    // The pref is read live by the setupSupportWarnings handlers.
    function injectSupportWarningsToggleIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-toggle="support-warnings"]')) return;
            ensureSectionHeader(section, 'misc', 'Miscellaneous');
            section.appendChild(buildVendroidToggleRow({
                attr: 'support-warnings',
                prefKey: 'vendroid_support_warnings',
                defaultValue: true,
                title: 'Support server warnings',
                description: 'Show a warning and restrict messaging in the Vencord and Equicord support servers, which do not provide support for this app.'
            }));
            console.warn('[Vendroid] Support-warnings toggle injected into Vendroid settings');
        } catch(e) {
            console.error('[Vendroid] injectSupportWarningsToggleIfMissing error: ' + e.message);
        }
    }

    var _vendroidVoiceSetupDone = false;
    var _vendroidVoiceSetupRetries = 0;
    var _vendroidVoiceSetupScheduled = false;
    // 60 retries x 250ms = 15s, matching the other patches' lazy-load budget.
    var VENDROID_VOICE_MAX_RETRIES = 60;

