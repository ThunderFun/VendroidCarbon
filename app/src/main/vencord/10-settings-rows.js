    function injectSettingsRowsIfMissing() {
        try {
            if (document.hidden) return;
            // Retry the session-start captures before the injectors so the
            // restart-required rows never mount against an uncaptured value.
            captureDesktopModeAtBootIfNeeded();
            captureDiscordBranchAtBootIfNeeded();
            // .vde-rows-section exists only while the Vendroid Settings tab
            // is mounted; append into the last instance found.
            var sections = document.querySelectorAll('.vde-rows-section');
            // The icon menu lives on <body>, outside the section. If the
            // settings tab unmounted while it was open, close it here. The
            // poller skips backgrounded ticks; the menu then closes on the
            // first foreground tick, which is fine.
            var openIconMenu = document.querySelector('[data-vde-icon-menu]');
            if (openIconMenu && typeof openIconMenu.vdeClose === 'function' &&
                (!sections || sections.length === 0)) {
                openIconMenu.vdeClose();
            }
            // Same lifecycle for the color-picker menus: the poller closes
            // them when the settings tab unmounts.
            ['[data-vde-bar-color-menu]', '[data-vde-orb-color-menu]',
             '[data-vde-splash-bg-menu]'].forEach(function (sel) {
                var openMenu = document.querySelector(sel);
                if (openMenu && typeof openMenu.vdeClose === 'function' &&
                    (!sections || sections.length === 0)) {
                    openMenu.vdeClose();
                }
            });
            if (!sections || sections.length === 0) return;
            var section = sections[sections.length - 1];

            // Injector order is render order; the branch switcher stays
            // first, the client mod switcher second, desktop mode last.
            injectDiscordBranchSwitcherIfMissing(section);
            injectClientModSwitcherIfMissing(section);
            injectAppIconPickerIfMissing(section);
            injectBarColorRowIfMissing(section);
            injectOrbColorRowIfMissing(section);
            injectSplashBgRowIfMissing(section);
            injectLogsRowIfMissing(section);
            injectFirewallRowIfMissing(section);
            injectPrivacyToggleIfMissing(section);
            injectLinkConfirmToggleIfMissing(section);
            injectRememberChannelToggleIfMissing(section);
            injectGesturesToggleIfMissing(section);
            injectSupportWarningsToggleIfMissing(section);
            injectDesktopModeToggleIfMissing(section);
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

    // "Discord branch" switcher (Stable / PTB / Canary). The row only reads
    // and writes the discordBranch pref; setString can silently early-return
    // (rate limiter, token check), so the dots reconcile to the persisted
    // value after each write. The branch decides the origin loaded at the
    // next cold start, hence the "Restart now" affordance. The restart note
    // is derived from the session-start capture in 00-boot.js so it survives
    // settings-tab remounts, the same fix desktop mode uses.
    var VDE_DISCORD_BRANCHES = [
        { value: 'stable', label: 'Stable', host: 'discord.com' },
        { value: 'ptb', label: 'PTB', host: 'ptb.discord.com' },
        { value: 'canary', label: 'Canary', host: 'canary.discord.com' }
    ];

    function discordBranchLabel(value) {
        var norm = normalizeDiscordBranch(value);
        for (var i = 0; i < VDE_DISCORD_BRANCHES.length; i++) {
            if (VDE_DISCORD_BRANCHES[i].value === norm) return VDE_DISCORD_BRANCHES[i].label;
        }
        return 'Stable';
    }

    function injectDiscordBranchSwitcherIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-discord-branch-switcher]')) return;
            ensureSectionHeader(section, 'discord-branch', 'Discord branch');

            var wrap = document.createElement('div');
            wrap.setAttribute('data-vde-discord-branch-switcher', '1');
            wrap.style.cssText = 'width:100%;';

            var desc = document.createElement('div');
            desc.className = 'vde-component-setting-description';
            desc.style.cssText = 'color:var(--text-muted);margin-bottom:10px;';
            desc.textContent = 'Choose which Discord build the app opens. Vencord settings, themes, and QuickCSS are stored per build and do not carry over; you may need to sign in again on a build you have not used before. Takes effect after a restart.';
            wrap.appendChild(desc);

            // Restart prompt; hidden while the persisted branch matches the
            // one this session started on.
            var restartNote = document.createElement('div');
            restartNote.style.cssText = 'display:none;align-items:center;justify-content:space-between;gap:12px;margin-bottom:10px;';
            var restartText = document.createElement('span');
            restartText.style.cssText = 'color:var(--header-primary);font-size:13px;flex:1 1 auto;';
            var restartBtn = document.createElement('button');
            restartBtn.type = 'button';
            restartBtn.textContent = 'Restart now';
            restartBtn.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;padding:6px 14px;background:var(--brand-primary,#5865f2);color:#fff;border:none;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer;flex:none;-webkit-tap-highlight-color:transparent;';
            restartBtn.addEventListener('click', function() {
                try { VencordMobileNative.requestNative('restartApp'); } catch(e) {
                    console.error('[Vendroid] restartApp failed: ' + e.message);
                }
            });
            restartNote.appendChild(restartText);
            restartNote.appendChild(restartBtn);
            wrap.appendChild(restartNote);

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
                    setDotState(entry.dot, entry.value === active);
                });
            }

            function currentBranch() {
                try {
                    return normalizeDiscordBranch(
                        VencordMobileNative.getString('discordBranch', 'stable'));
                } catch (e) {
                    return 'stable';
                }
            }

            // If the boot capture never landed because the bridge was
            // unavailable, fall back to this mount's value so the note tracks
            // in-mount changes only. A local fallback, not a write to the
            // shared var, so the capture can still land later.
            var mountValue = currentBranch();
            var sessionStart = (typeof _vdeDiscordBranchAtBoot !== 'undefined')
                ? _vdeDiscordBranchAtBoot
                : mountValue;

            function syncRestartNote(persisted) {
                if (persisted === sessionStart) {
                    restartNote.style.display = 'none';
                    return;
                }
                restartText.textContent = 'Restart the app to open '
                    + discordBranchLabel(persisted) + '.';
                restartNote.style.display = 'flex';
            }
            syncRestartNote(mountValue);

            VDE_DISCORD_BRANCHES.forEach(function (entry) {
                var row = document.createElement('div');
                row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 0;cursor:pointer;-webkit-tap-highlight-color:transparent;';

                var labelWrap = document.createElement('div');
                labelWrap.style.cssText = 'display:flex;flex-direction:column;';
                var label = document.createElement('div');
                label.style.cssText = 'color:var(--header-primary);font-size:14px;font-weight:500;';
                label.textContent = entry.label;
                var host = document.createElement('div');
                host.style.cssText = 'color:var(--text-muted);font-size:12px;';
                host.textContent = entry.host;
                labelWrap.appendChild(label);
                labelWrap.appendChild(host);
                row.appendChild(labelWrap);

                var dot = document.createElement('span');
                dot.style.cssText = 'width:20px;height:20px;border-radius:50%;flex:none;box-sizing:border-box;';
                row.appendChild(dot);

                row.addEventListener('click', function () {
                    // Debounce double-taps; the 700ms window outlasts the
                    // bridge's 500ms write rate limit.
                    if (list.getAttribute('data-busy') === '1') return;
                    if (entry.value === currentBranch()) return;
                    list.setAttribute('data-busy', '1');
                    try {
                        VencordMobileNative.setString('discordBranch', entry.value);
                    } catch (e) {
                        console.error('[Vendroid] setString discordBranch failed: ' + e.message);
                    }
                    // Re-read: a rejected write must not leave an unapplied
                    // selection on screen.
                    var persisted = currentBranch();
                    renderSelection(persisted);
                    syncRestartNote(persisted);
                    if (persisted !== entry.value) {
                        console.error('[Vendroid] setString discordBranch rejected; selection unchanged');
                    }
                    setTimeout(function () { list.removeAttribute('data-busy'); }, 700);
                });

                list.appendChild(row);
                rows.push({ value: entry.value, dot: dot });
            });

            renderSelection(mountValue);

            var divider = document.createElement('div');
            divider.className = 'vde-divider-setting';
            divider.style.cssText = 'width:100%;height:1px;border-top:thin solid var(--background-modifier-accent);margin-top:20px;margin-bottom:20px;';
            wrap.appendChild(divider);

            section.appendChild(wrap);
            console.warn('[Vendroid] Discord branch switcher injected into Vendroid settings');
        } catch (e) {
            console.error('[Vendroid] injectDiscordBranchSwitcherIfMissing error: ' + e.message);
        }
    }

    // "Client mod" switcher (Vencord ⇄ Equicord). The row only reads and
    // writes the clientMod pref; setString can silently early-return (rate
    // limiter, token check), so the dots reconcile to the persisted value
    // after each write. The switch takes effect on the next cold start,
    // hence the "Restart now" affordance.
    function injectClientModSwitcherIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-client-mod-switcher]')) return;
            ensureSectionHeader(section, 'client-mod', 'Client mod');

            var wrap = document.createElement('div');
            wrap.setAttribute('data-vde-client-mod-switcher', '1');
            wrap.style.cssText = 'width:100%;';

            var desc = document.createElement('div');
            desc.className = 'vde-component-setting-description';
            desc.style.cssText = 'color:var(--text-muted);margin-bottom:10px;';
            desc.textContent = 'Choose which client mod bundle the app loads. Equicord is a Vencord fork with more plugins. Switching re-downloads the bundle on the next app start.';
            wrap.appendChild(desc);

            // Restart prompt; hidden until a switch actually lands.
            var restartNote = document.createElement('div');
            restartNote.style.cssText = 'display:none;align-items:center;justify-content:space-between;gap:12px;margin-bottom:10px;';
            var restartText = document.createElement('span');
            restartText.style.cssText = 'color:var(--header-primary);font-size:13px;flex:1 1 auto;';
            var restartBtn = document.createElement('button');
            restartBtn.type = 'button';
            restartBtn.textContent = 'Restart now';
            restartBtn.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;padding:6px 14px;background:var(--brand-primary,#5865f2);color:#fff;border:none;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer;flex:none;-webkit-tap-highlight-color:transparent;';
            restartBtn.addEventListener('click', function() {
                try { VencordMobileNative.requestNative('restartApp'); } catch(e) {
                    console.error('[Vendroid] restartApp failed: ' + e.message);
                }
            });
            restartNote.appendChild(restartText);
            restartNote.appendChild(restartBtn);
            wrap.appendChild(restartNote);

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

            // Anything but "equicord" reads as Vencord, matching
            // resolveBundleLocation.
            function currentMod() {
                try {
                    var v = VencordMobileNative.getString('clientMod', 'vencord');
                    return v === 'equicord' ? 'equicord' : 'vencord';
                } catch (e) {
                    return 'vencord';
                }
            }

            function showRestartPending(mod) {
                restartText.textContent = 'Restart the app to finish switching to ' + (mod === 'equicord' ? 'Equicord' : 'Vencord') + '.';
                restartNote.style.display = 'flex';
            }

            ['Vencord', 'Equicord'].forEach(function (name) {
                var value = name.toLowerCase();
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
                    // Debounce double-taps; the 700ms window outlasts the
                    // bridge's 500ms write rate limit.
                    if (list.getAttribute('data-busy') === '1') return;
                    var active = currentMod();
                    if (value === active) return;
                    list.setAttribute('data-busy', '1');
                    try {
                        VencordMobileNative.setString('clientMod', value);
                    } catch (e) {
                        console.error('[Vendroid] setString clientMod failed: ' + e.message);
                    }
                    // Re-read: a rejected write must not leave an unapplied
                    // selection on screen.
                    var persisted = currentMod();
                    renderSelection(persisted);
                    if (persisted === value) {
                        showRestartPending(persisted);
                    } else {
                        console.error('[Vendroid] setString clientMod rejected; selection unchanged');
                    }
                    setTimeout(function () { list.removeAttribute('data-busy'); }, 700);
                });

                list.appendChild(row);
                rows.push({ name: value, dot: dot });
            });

            renderSelection(currentMod());

            var divider = document.createElement('div');
            divider.className = 'vde-divider-setting';
            divider.style.cssText = 'width:100%;height:1px;border-top:thin solid var(--background-modifier-accent);margin-top:20px;margin-bottom:20px;';
            wrap.appendChild(divider);

            section.appendChild(wrap);
            console.warn('[Vendroid] Client mod switcher injected into Vendroid settings');
        } catch (e) {
            console.error('[Vendroid] injectClientModSwitcherIfMissing error: ' + e.message);
        }
    }

    // Desktop mode swaps the WebView user agent to a desktop Chrome UA, so
    // Discord serves its desktop web UI. The UA is fixed at WebView install
    // in MainActivity.applyWebViewSettings, so the row only offers a restart
    // and never applies live. Unlike the other rows, tapping the title or
    // description also flips the switch (toggleOnLabelPress). It compares the
    // persisted value against the session-start capture in 00-boot.js, which
    // keeps the restart note correct across settings-tab remounts.
    function injectDesktopModeToggleIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-toggle="desktop-mode"]')) return;
            ensureSectionHeader(section, 'layout', 'Layout');

            // Restart note; hidden while persisted matches the session start.
            var note = document.createElement('div');
            note.style.cssText = 'display:none;align-items:center;justify-content:space-between;gap:12px;margin-top:12px;';
            var noteText = document.createElement('span');
            noteText.style.cssText = 'color:var(--header-primary);font-size:13px;flex:1 1 auto;';
            var restartBtn = document.createElement('button');
            restartBtn.type = 'button';
            restartBtn.textContent = 'Restart now';
            restartBtn.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;padding:6px 14px;background:var(--brand-primary,#5865f2);color:#fff;border:none;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer;flex:none;-webkit-tap-highlight-color:transparent;';
            restartBtn.addEventListener('click', function() {
                try { VencordMobileNative.requestNative('restartApp'); } catch(e) {
                    console.error('[Vendroid] restartApp failed: ' + e.message);
                }
            });
            note.appendChild(noteText);
            note.appendChild(restartBtn);

            // Read once for the initial note state. If the session-start
            // capture never landed because the bridge was unavailable, fall
            // back to this mount's value so the note tracks in-mount
            // changes only.
            var mountValue = false;
            try {
                mountValue = !!VencordMobileNative.getBool('desktopMode', false);
            } catch(e) {
                console.error('[Vendroid] getBool for desktop-mode toggle failed: ' + e.message);
            }
            var sessionStart = (typeof _vdeDesktopModeAtBoot !== 'undefined')
                ? _vdeDesktopModeAtBoot
                : mountValue;

            function syncRestartNote(persisted) {
                if (persisted === sessionStart) {
                    note.style.display = 'none';
                    return;
                }
                noteText.textContent = persisted
                    ? 'Restart to apply the desktop UI.'
                    : 'Restart to return to the mobile UI.';
                note.style.display = 'flex';
            }
            syncRestartNote(mountValue);

            section.appendChild(buildVendroidToggleRow({
                attr: 'desktop-mode',
                prefKey: 'desktopMode',
                defaultValue: false,
                title: 'Desktop mode',
                description: 'Load Discord with a desktop browser user agent so it renders its desktop web UI. Takes effect after a restart; gesture navigation is inactive in desktop mode.',
                noteEl: note,
                toggleOnLabelPress: true,
                onChange: syncRestartNote
            }));
            console.warn('[Vendroid] Desktop mode toggle injected into Vendroid settings');
        } catch(e) {
            console.error('[Vendroid] injectDesktopModeToggleIfMissing error: ' + e.message);
        }
    }

    // One launcher activity-alias per icon (IconAliasManager.ICON_NAMES).
    // The bridge exposes changeAppIcon(token, id) as the privileged write,
    // getCurrentAppIcon for the initial selection, and getAppIcons for the
    // icon art (PNG data URIs rendered natively from the manifest aliases);
    // the bootstrap wrapper prepends the session token, so page JS passes
    // only the id.
    var VDE_ICON_CHOICES = ['Main', 'Basic', 'Jolly', 'Retro', 'Discord', 'TS12', 'Glass', 'Charcoal', 'OLED'];

    function currentAppIconName() {
        try {
            var name = window.VencordMobileNative && window.VencordMobileNative.getCurrentAppIcon();
            return name && VDE_ICON_CHOICES.indexOf(name) !== -1 ? name : null;
        } catch (e) {
            return null;
        }
    }

    // Icon art cache, fetched once per page load. Failures are not cached:
    // remounting the settings tab is the retry path, since the picker marker
    // blocks re-injection within one mount. A cached failure would lock the
    // text fallback for the whole page session.
    var vdeAppIconUris = null;

    function fetchAppIconImages() {
        if (vdeAppIconUris) return vdeAppIconUris;
        try {
            if (typeof VencordMobileNative === 'undefined' ||
                typeof VencordMobileNative.getAppIcons !== 'function') return null;
            var map = JSON.parse(VencordMobileNative.getAppIcons());
            if (!map || typeof map !== 'object') return null;
            // Keep only known names with data-URI values; anything else is
            // bridge noise, not tile art.
            var cleaned = {};
            var count = 0;
            VDE_ICON_CHOICES.forEach(function (name) {
                var uri = map[name];
                if (typeof uri === 'string' && uri.indexOf('data:image/') === 0) {
                    cleaned[name] = uri;
                    count++;
                }
            });
            if (count === 0) return null;
            vdeAppIconUris = cleaned;
            return vdeAppIconUris;
        } catch (e) {
            console.error('[Vendroid] getAppIcons failed: ' + e.message);
            return null;
        }
    }

    // Tile/row art for one icon: a background-image DIV, not an <img>.
    // Discord's delegated image handling keys on <img> elements and opened
    // its image viewer over the menu when tile art was an <img>; a div with
    // background-image:url(data:...) is invisible to that delegation. A
    // native render failure for one name must not drop the tile: the alias
    // is still switchable, so the initial-letter placeholder keeps it
    // selectable.
    function appIconImage(images, name, sizePx) {
        var el = document.createElement('div');
        el.style.cssText = 'width:' + sizePx + 'px;height:' + sizePx + 'px;border-radius:50%;flex:none;' +
            'background-repeat:no-repeat;background-position:center;background-size:cover;' +
            'display:flex;align-items:center;justify-content:center;' +
            'background-color:var(--background-modifier-accent,#4f545c);color:var(--text-muted,#b9bbbe);' +
            'font-size:' + Math.max(12, Math.round(sizePx / 3)) + 'px;font-weight:700;';
        var uri = images[name];
        if (typeof uri === 'string') {
            el.style.backgroundImage = 'url("' + uri + '")';
        } else {
            el.textContent = name.charAt(0).toUpperCase();
        }
        return el;
    }

    function updateAppIconRow(rowEl, images, active) {
        try {
            if (!rowEl) return;
            var label = rowEl.querySelector('[data-vde-app-icon-label]');
            if (label) label.textContent = active;
            var thumb = rowEl.querySelector('[data-vde-app-icon-thumb]');
            if (!thumb) return;
            var uri = images[active];
            if (typeof uri === 'string') {
                thumb.style.backgroundImage = 'url("' + uri + '")';
                thumb.textContent = '';
            }
        } catch (e) {
            console.error('[Vendroid] app icon row update failed: ' + e.message);
        }
    }

    // The menu mounts on <body>, outside Discord's themed container, so CSS
    // variables do not resolve there (an unset var() inherits, which
    // rendered the title black). Read the resolved values from the row, which
    // lives inside the themed tree, at open time and use literals. The menu
    // is ephemeral, so a theme change mid-open is not handled.
    function vdeThemeColor(el, prop, fallback) {
        try {
            var v = getComputedStyle(el).getPropertyValue(prop);
            if (v && v.trim()) return v.trim();
        } catch (e) {}
        return fallback;
    }

    // Picker menu: fixed backdrop + card on <body>, outside the section the
    // 750ms poller scans; the poller closes it if the settings tab unmounts
    // (see injectSettingsRowsIfMissing). z-index sits above Discord's own
    // popouts (~1000). The backdrop is the scroll container, with
    // overscroll-behavior:contain so drags on the dimmed area never scroll
    // the settings panel behind the menu.
    function openAppIconMenu(images, rowEl) {
        try {
            if (document.body.querySelector('[data-vde-icon-menu]')) return;

            var themeEl = rowEl || document.querySelector('.vde-rows-section');
            var cHeader = vdeThemeColor(themeEl, '--header-primary', '#f2f3f5');
            var cMuted = vdeThemeColor(themeEl, '--text-muted', '#b9bbbe');
            var cBg = vdeThemeColor(themeEl, '--background-mobile-primary', '#313338');
            var cBrand = vdeThemeColor(themeEl, '--brand-primary', '#5865f2');
            var cAccent = vdeThemeColor(themeEl, '--background-modifier-accent', '#4f545c');
            var cInteractive = vdeThemeColor(themeEl, '--interactive-active', '#ffffff');

            var backdrop = document.createElement('div');
            backdrop.setAttribute('data-vde-icon-menu', '1');
            backdrop.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.7);z-index:99999;display:flex;overflow-y:auto;overscroll-behavior:contain;-webkit-tap-highlight-color:transparent;';

            function onKey(ev) {
                if (ev && (ev.key === 'Escape' || ev.key === 'Esc')) {
                    // Swallow the Escape: after close(), propagation reached
                    // Discord's settings handlers and scrolled the panel to
                    // the bottom; the backdrop-click path never did.
                    // Window-capture runs before Discord's document-level
                    // listeners, so this contains the event.
                    ev.preventDefault();
                    ev.stopPropagation();
                    ev.stopImmediatePropagation();
                    close();
                }
            }
            function close() {
                try {
                    window.removeEventListener('keydown', onKey, true);
                    if (backdrop.parentNode) backdrop.parentNode.removeChild(backdrop);
                } catch (e) {
                    console.error('[Vendroid] app icon menu close failed: ' + e.message);
                }
            }
            // Handle for the poller's tab-unmount check.
            backdrop.vdeClose = close;

            var card = document.createElement('div');
            card.setAttribute('data-vde-icon-menu-card', '1');
            card.style.cssText = 'background:' + cBg + ';border-radius:8px;width:min(340px,calc(100vw - 48px));margin:auto;padding:16px;box-shadow:0 8px 32px rgba(0,0,0,.5);';

            var header = document.createElement('div');
            header.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:4px;';

            var title = document.createElement('div');
            title.style.cssText = 'color:' + cHeader + ';font-size:20px;font-weight:700;line-height:24px;';
            title.textContent = 'Choose app icon';
            header.appendChild(title);

            var closeBtn = document.createElement('button');
            closeBtn.type = 'button';
            closeBtn.setAttribute('aria-label', 'Close icon picker');
            closeBtn.style.cssText = 'width:32px;height:32px;border:none;border-radius:50%;background:' + cAccent + ';color:' + cInteractive + ';font-size:15px;line-height:1;cursor:pointer;flex:none;display:flex;align-items:center;justify-content:center;';
            closeBtn.textContent = '✕';
            closeBtn.addEventListener('click', function (ev) {
                ev.stopPropagation();
                close();
            });
            header.appendChild(closeBtn);

            card.appendChild(header);

            var note = document.createElement('div');
            note.style.cssText = 'color:' + cMuted + ';font-size:12px;margin-bottom:12px;';
            note.textContent = 'Some launchers take a moment to show the change.';
            card.appendChild(note);

            var grid = document.createElement('div');
            grid.style.cssText = 'display:grid;grid-template-columns:repeat(3,1fr);gap:8px;';

            var tiles = [];
            var busy = false;

            function renderSelection(active) {
                tiles.forEach(function (t) {
                    t.img.style.boxShadow = t.name === active ? '0 0 0 3px ' + cBrand : 'none';
                    t.badge.style.display = t.name === active ? 'flex' : 'none';
                });
            }

            VDE_ICON_CHOICES.forEach(function (name) {
                var tile = document.createElement('button');
                tile.type = 'button';
                // min-height + two-line labels absorb Android font scaling.
                tile.style.cssText = 'display:flex;flex-direction:column;align-items:center;gap:6px;padding:10px 4px;background:transparent;border:none;border-radius:8px;cursor:pointer;min-height:96px;';

                var iconWrap = document.createElement('span');
                iconWrap.style.cssText = 'position:relative;width:64px;height:64px;flex:none;';

                var img = appIconImage(images, name, 64);
                iconWrap.appendChild(img);

                var badge = document.createElement('span');
                badge.style.cssText = 'position:absolute;right:-2px;bottom:-2px;width:20px;height:20px;border-radius:50%;background:' + cBrand + ';display:none;align-items:center;justify-content:center;box-shadow:0 1px 3px rgba(0,0,0,.4);';
                // CSS-border checkmark: a ✓ character depends on font coverage.
                var check = document.createElement('span');
                check.style.cssText = 'width:9px;height:5px;border-left:2px solid #fff;border-bottom:2px solid #fff;transform:rotate(-45deg) translate(1px,-1px);';
                badge.appendChild(check);
                iconWrap.appendChild(badge);

                tile.appendChild(iconWrap);

                var lbl = document.createElement('div');
                lbl.style.cssText = 'color:' + cMuted + ';font-size:12px;font-weight:500;text-align:center;line-height:14px;';
                lbl.textContent = name;
                tile.appendChild(lbl);

                tile.addEventListener('click', function (ev) {
                    ev.stopPropagation();
                    // Busy flag stops double-taps from stacking launcher
                    // refreshes. changeAppIcon is synchronous across the
                    // bridge, heals stale aliases, and toasts its own result;
                    // re-read to reconcile, falling back to the tapped name
                    // if the getter is unavailable.
                    if (busy) return;
                    busy = true;
                    try {
                        VencordMobileNative.changeAppIcon(name);
                    } catch (e) {
                        console.error('[Vendroid] changeAppIcon failed: ' + e.message);
                    }
                    var resolved = currentAppIconName() || name;
                    renderSelection(resolved);
                    updateAppIconRow(rowEl, images, resolved);
                    setTimeout(function () { busy = false; }, 1500);
                });

                grid.appendChild(tile);
                tiles.push({ name: name, img: img, badge: badge });
            });

            renderSelection(currentAppIconName() || 'Main');
            card.appendChild(grid);

            backdrop.addEventListener('click', function (ev) {
                if (ev.target === backdrop) close();
            });
            window.addEventListener('keydown', onKey, true);

            backdrop.appendChild(card);
            document.body.appendChild(backdrop);
            console.warn('[Vendroid] App icon menu opened');
        } catch (e) {
            console.error('[Vendroid] openAppIconMenu error: ' + e.message);
        }
    }

    // Settings row: current icon thumbnail + name + chevron; opens the menu.
    function buildAppIconRow(wrap, images) {
        var row = document.createElement('div');
        row.setAttribute('data-vde-app-icon-row', '1');
        row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 0;cursor:pointer;-webkit-tap-highlight-color:transparent;';

        var left = document.createElement('div');
        left.style.cssText = 'display:flex;align-items:center;gap:12px;flex:1 1 auto;min-width:0;';

        var active = currentAppIconName() || 'Main';
        var thumb = appIconImage(images, active, 28);
        thumb.setAttribute('data-vde-app-icon-thumb', '1');
        left.appendChild(thumb);

        var label = document.createElement('div');
        label.setAttribute('data-vde-app-icon-label', '1');
        label.style.cssText = 'color:var(--header-primary);font-size:14px;font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
        label.textContent = active;
        left.appendChild(label);

        row.appendChild(left);

        var chevron = document.createElement('span');
        chevron.setAttribute('aria-hidden', 'true');
        chevron.style.cssText = 'color:var(--interactive-muted,#72767d);font-size:18px;flex:none;line-height:1;';
        chevron.textContent = '›';
        row.appendChild(chevron);

        row.addEventListener('click', function () {
            openAppIconMenu(images, row);
        });

        wrap.appendChild(row);
    }

    // Fallback: the original vertical text list, used when the bridge cannot
    // supply icon art (getAppIcons unavailable, render failure, size guard).
    // Images are an enhancement, not a dependency; the picker must not
    // regress to broken.
    function buildAppIconTextList(wrap) {
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
                // Same contract as the menu tiles: busy-flag, write,
                // reconcile to the persisted value.
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
    }

    function injectAppIconPickerIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-app-icon-picker]')) return;
            ensureSectionHeader(section, 'app-icon', 'App icon');

            var wrap = document.createElement('div');
            wrap.setAttribute('data-vde-app-icon-picker', '1');
            wrap.style.cssText = 'width:100%;';

            var images = fetchAppIconImages();
            if (images) {
                var desc = document.createElement('div');
                desc.className = 'vde-component-setting-description';
                desc.style.cssText = 'color:var(--text-muted);margin-bottom:10px;';
                desc.textContent = 'Choose the launcher icon.';
                wrap.appendChild(desc);
                buildAppIconRow(wrap, images);
            } else {
                buildAppIconTextList(wrap);
            }

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

    // Shared builder for the toggle rows used by gestures, support warnings,
    // and desktop mode. Same markup and reconcile-on-write behavior as the
    // hand-rolled toggles above: setBool can silently early-return through
    // the key allowlist or the rate limiter, so the change handler re-reads
    // the persisted value and syncs the UI to it. opts.noteEl appends a node
    // between the row and the divider, and opts.onChange fires with the
    // reconciled value after each change, never on init. Desktop mode uses
    // both for its restart note.
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

        // Fired with the reconciled persisted value after each change, never
        // on init; callers set their initial state themselves. The inner
        // catch keeps a throwing callback from breaking the toggle.
        function emitChange(persisted) {
            if (typeof opts.onChange !== 'function') return;
            try {
                opts.onChange(persisted);
            } catch(e) {
                console.error('[Vendroid] onChange for ' + opts.prefKey + ' failed: ' + e.message);
            }
        }

        cb.addEventListener('change', function() {
            try {
                VencordMobileNative.setBool(opts.prefKey, cb.checked);
                var persisted = VencordMobileNative.getBool(opts.prefKey, opts.defaultValue);
                if (persisted !== cb.checked) {
                    cb.checked = persisted;
                }
                render(cb.checked);
                emitChange(cb.checked);
            } catch(e) {
                console.error('[Vendroid] setBool for ' + opts.prefKey + ' failed: ' + e.message);
                cb.checked = !cb.checked;
                render(cb.checked);
            }
        });

        // Opt-in: label taps flip the checkbox through the same change
        // handler as the switch. Used by desktop mode only.
        if (opts.toggleOnLabelPress) {
            labelCol.style.cursor = 'pointer';
            labelCol.addEventListener('click', function() {
                cb.click();
            });
        }

        wrap.appendChild(row);

        if (opts.noteEl) {
            wrap.appendChild(opts.noteEl);
        }

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
                description: 'Swipe horizontally to move between the channel list, chat, and the member list. Inactive in desktop mode.'
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

    // ---------------------------------------------------------------------------
    // "Appearance" section: status-bar color picker + reset to theme default.
    // Android WebView ships no <input type=color> chooser, so this is
    // hand-built DOM like the app-icon menu. While the modal is open the
    // in-memory HSV triple is the single source of truth (hex is derived,
    // never re-parsed) so slider positions cannot drift; Apply and Reset are
    // the only bridge writes, each reconciled by re-reading getBarColor.
    // ---------------------------------------------------------------------------

    var VDE_COLOR_PRESETS = ['#121214', '#000000', '#5865f2', '#23272a', '#3b2a58', '#ffffff'];

    function currentBarHexOrNull() {
        try {
            var v = window.VencordMobileNative && VencordMobileNative.getBarColor();
            if (typeof v === 'string' && /^#[0-9a-f]{6}$/.test(v)) return v;
        } catch (e) {
            console.error('[Vendroid] getBarColor failed: ' + e.message);
        }
        return null; // null = theme default
    }

    function defaultBarHex() {
        try {
            var v = window.VencordMobileNative && VencordMobileNative.getDefaultBarColor();
            if (typeof v === 'string' && /^#[0-9a-f]{6}$/.test(v)) return v;
        } catch (e) {}
        return '#121214';
    }

    function currentBarHexOrDefault() {
        return currentBarHexOrNull() || defaultBarHex();
    }

    function updateBarRowSwatch(swatchEl) {
        if (!swatchEl) return;
        try { swatchEl.style.background = currentBarHexOrDefault(); } catch (e) {}
    }

    function currentOrbHexOrNull() {
        try {
            var v = window.VencordMobileNative && VencordMobileNative.getOrbColor();
            if (typeof v === 'string' && /^#[0-9a-f]{6}$/.test(v)) return v;
        } catch (e) {
            console.error('[Vendroid] getOrbColor failed: ' + e.message);
        }
        return null; // null = auto (follow the bar tint)
    }

    // Auto ornament color: the bar tint's hue through the bright S/V
    // profile (the dot and glow_a share 65/95), mirroring SplashPalette's
    // derivation for the preview swatch. The 0.15 threshold mirrors
    // NEUTRAL_SATURATION. The threshold uses the unrounded saturation:
    // vdeHexToHsv rounds to integer percent, and rounding straddles 0.15
    // (true s 0.148 rounds to 15), which would preview a hue where the boot
    // shows neutral grey.
    function derivedOrbHexOrDefault() {
        var src = currentBarHexOrDefault();
        var r = parseInt(src.slice(1, 3), 16) / 255;
        var g = parseInt(src.slice(3, 5), 16) / 255;
        var b = parseInt(src.slice(5, 7), 16) / 255;
        var max = Math.max(r, g, b), min = Math.min(r, g, b);
        var s = max <= 0 ? 0 : (max - min) / max;
        if (s < 0.15) return vdeHsvToHex({ h: 0, s: 0, v: 95 });
        return vdeHsvToHex({ h: vdeHexToHsv(src).h, s: 65, v: 95 });
    }

    function currentOrbSwatchHex() {
        return currentOrbHexOrNull() || derivedOrbHexOrDefault();
    }

    function updateOrbRowSwatch(swatchEl) {
        if (!swatchEl) return;
        try { swatchEl.style.background = currentOrbSwatchHex(); } catch (e) {}
    }

    // JS mirror of SplashPalette.stageColor's shade-of-black clamp (V_CAP
    // 0.2), auto preview only: v at or under 0.2 passes through, brighter
    // tints keep h/s and drop to v 20. The threshold compares unrounded v
    // (max of r/g/b). vdeHexToHsv rounds to integer percent and rounding
    // straddles 0.2 (true v 0.204 rounds to 20), so a rounded check would
    // pass through a tint the boot clamps.
    function vdeStageClampHex(hex) {
        var r = parseInt(hex.slice(1, 3), 16) / 255;
        var g = parseInt(hex.slice(3, 5), 16) / 255;
        var b = parseInt(hex.slice(5, 7), 16) / 255;
        if (Math.max(r, g, b) <= 0.2) return hex;
        var hsv = vdeHexToHsv(hex);
        return vdeHsvToHex({ h: hsv.h, s: hsv.s, v: 20 });
    }

    function currentSplashBgHexOrNull() {
        try {
            var v = window.VencordMobileNative && VencordMobileNative.getSplashBgColor();
            if (typeof v === 'string' && /^#[0-9a-f]{6}$/.test(v)) return v;
        } catch (e) {
            console.error('[Vendroid] getSplashBgColor failed: ' + e.message);
        }
        return null; // auto: follow the bar tint, clamped
    }

    function derivedSplashBgHexOrDefault() {
        return vdeStageClampHex(currentBarHexOrDefault());
    }

    function currentSplashBgSwatchHex() {
        // A stored pick shows verbatim; auto previews the clamped bar tint.
        return currentSplashBgHexOrNull() || derivedSplashBgHexOrDefault();
    }

    function updateSplashBgRowSwatch(swatchEl) {
        if (!swatchEl) return;
        try { swatchEl.style.background = currentSplashBgSwatchHex(); } catch (e) {}
    }

    // Integer HSV keeps round-trips deterministic: h 0..359 (360 normalized
    // to 0), s/v 0..100. Byte rounding absorbs the conversion drift.
    function vdeHexToHsv(hex) {
        var r = parseInt(hex.slice(1, 3), 16) / 255;
        var g = parseInt(hex.slice(3, 5), 16) / 255;
        var b = parseInt(hex.slice(5, 7), 16) / 255;
        var max = Math.max(r, g, b), min = Math.min(r, g, b);
        var d = max - min;
        var h = 0;
        if (d > 0) {
            if (max === r) h = ((g - b) / d) % 6;
            else if (max === g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h = Math.round(h * 60);
            if (h < 0) h += 360;
            if (h === 360) h = 0;
        }
        var s = max <= 0 ? 0 : Math.round((d / max) * 100);
        var v = Math.round(max * 100);
        return { h: h, s: s, v: v };
    }

    function vdeHsvToHex(hsv) {
        var h = ((hsv.h % 360) + 360) % 360;
        var s = hsv.s / 100, v = hsv.v / 100;
        var c = v * s;
        var x = c * (1 - Math.abs(((h / 60) % 2) - 1));
        var m = v - c;
        var r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        function toByte(f) {
            return ('0' + Math.round((f + m) * 255).toString(16)).slice(-2);
        }
        return '#' + toByte(r) + toByte(g) + toByte(b);
    }

    // Rough sRGB luma (no gamma), only for picking the preview label color.
    function vdeBarLuma(hex) {
        var r = parseInt(hex.slice(1, 3), 16) / 255;
        var g = parseInt(hex.slice(3, 5), 16) / 255;
        var b = parseInt(hex.slice(5, 7), 16) / 255;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    function injectBarColorRowIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-bar-color-row]')) return;
            ensureSectionHeader(section, 'appearance', 'Appearance');

            var wrap = document.createElement('div');
            wrap.setAttribute('data-vde-bar-color-picker', '1');
            wrap.style.cssText = 'width:100%;';

            var desc = document.createElement('div');
            desc.className = 'vde-component-setting-description';
            desc.style.cssText = 'color:var(--text-muted);margin-bottom:10px;';
            desc.textContent = 'Tint the Android status and navigation bars. Applies immediately. The boot splash stage follows your color unless the splash background setting below takes over. The recovery screen stays the app default.';
            wrap.appendChild(desc);

            var row = document.createElement('div');
            row.setAttribute('data-vde-bar-color-row', '1');
            row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 0;cursor:pointer;-webkit-tap-highlight-color:transparent;';

            var left = document.createElement('div');
            left.style.cssText = 'display:flex;align-items:center;gap:12px;flex:1 1 auto;min-width:0;';

            var swatch = document.createElement('span');
            swatch.setAttribute('data-vde-bar-swatch', '1');
            swatch.style.cssText = 'width:28px;height:28px;border-radius:8px;flex:none;box-sizing:border-box;border:thin solid var(--background-modifier-accent,#4f545c);';
            swatch.style.background = currentBarHexOrDefault();
            left.appendChild(swatch);

            var label = document.createElement('div');
            label.style.cssText = 'color:var(--header-primary);font-size:14px;font-weight:500;';
            label.textContent = 'Status bar color';
            left.appendChild(label);

            row.appendChild(left);

            var chevron = document.createElement('span');
            chevron.setAttribute('aria-hidden', 'true');
            chevron.style.cssText = 'color:var(--interactive-muted,#72767d);font-size:18px;flex:none;line-height:1;';
            chevron.textContent = '›';
            row.appendChild(chevron);

            row.addEventListener('click', function () {
                openBarColorMenu(swatch);
            });

            wrap.appendChild(row);

            var divider = document.createElement('div');
            divider.className = 'vde-divider-setting';
            divider.style.cssText = 'width:100%;height:1px;border-top:thin solid var(--background-modifier-accent);margin-top:20px;margin-bottom:20px;';
            wrap.appendChild(divider);

            section.appendChild(wrap);
            console.warn('[Vendroid] Bar color row injected into Vendroid settings');
        } catch (e) {
            console.error('[Vendroid] injectBarColorRowIfMissing error: ' + e.message);
        }
    }

    function injectOrbColorRowIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-orb-color-row]')) return;
            ensureSectionHeader(section, 'appearance', 'Appearance');

            var wrap = document.createElement('div');
            wrap.setAttribute('data-vde-orb-color-picker', '1');
            wrap.style.cssText = 'width:100%;';

            var desc = document.createElement('div');
            desc.className = 'vde-component-setting-description';
            desc.style.cssText = 'color:var(--text-muted);margin-bottom:10px;';
            desc.textContent = 'Color of the splash dots and glow orbs on the boot splash. Left unset, they follow the status bar color. Grey picks give a white-grey result. Applies on the next app start.';
            wrap.appendChild(desc);

            var row = document.createElement('div');
            row.setAttribute('data-vde-orb-color-row', '1');
            row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 0;cursor:pointer;-webkit-tap-highlight-color:transparent;';

            var left = document.createElement('div');
            left.style.cssText = 'display:flex;align-items:center;gap:12px;flex:1 1 auto;min-width:0;';

            var swatch = document.createElement('span');
            swatch.setAttribute('data-vde-orb-swatch', '1');
            swatch.style.cssText = 'width:28px;height:28px;border-radius:8px;flex:none;box-sizing:border-box;border:thin solid var(--background-modifier-accent,#4f545c);';
            swatch.style.background = currentOrbSwatchHex();
            left.appendChild(swatch);

            var label = document.createElement('div');
            label.style.cssText = 'color:var(--header-primary);font-size:14px;font-weight:500;';
            label.textContent = 'Splash glow color';
            left.appendChild(label);

            row.appendChild(left);

            var chevron = document.createElement('span');
            chevron.setAttribute('aria-hidden', 'true');
            chevron.style.cssText = 'color:var(--interactive-muted,#72767d);font-size:18px;flex:none;line-height:1;';
            chevron.textContent = '›';
            row.appendChild(chevron);

            row.addEventListener('click', function () {
                openOrbColorMenu(swatch);
            });

            wrap.appendChild(row);

            var divider = document.createElement('div');
            divider.className = 'vde-divider-setting';
            divider.style.cssText = 'width:100%;height:1px;border-top:thin solid var(--background-modifier-accent);margin-top:20px;margin-bottom:20px;';
            wrap.appendChild(divider);

            section.appendChild(wrap);
            console.warn('[Vendroid] Glow color row injected into Vendroid settings');
        } catch (e) {
            console.error('[Vendroid] injectOrbColorRowIfMissing error: ' + e.message);
        }
    }

    function injectSplashBgRowIfMissing(section) {
        try {
            if (section.querySelector('[data-vde-splash-bg-row]')) return;
            ensureSectionHeader(section, 'appearance', 'Appearance');

            var wrap = document.createElement('div');
            wrap.setAttribute('data-vde-splash-bg-picker', '1');
            wrap.style.cssText = 'width:100%;';

            var desc = document.createElement('div');
            desc.className = 'vde-component-setting-description';
            desc.style.cssText = 'color:var(--text-muted);margin-bottom:10px;';
            desc.textContent = 'Background color of the boot splash, shown exactly as picked. Left unset, it follows the status bar color, kept dark so the label stays readable. Applies on the next app start.';
            wrap.appendChild(desc);

            var row = document.createElement('div');
            row.setAttribute('data-vde-splash-bg-row', '1');
            row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 0;cursor:pointer;-webkit-tap-highlight-color:transparent;';

            var left = document.createElement('div');
            left.style.cssText = 'display:flex;align-items:center;gap:12px;flex:1 1 auto;min-width:0;';

            var swatch = document.createElement('span');
            swatch.setAttribute('data-vde-splash-bg-swatch', '1');
            swatch.style.cssText = 'width:28px;height:28px;border-radius:8px;flex:none;box-sizing:border-box;border:thin solid var(--background-modifier-accent,#4f545c);';
            swatch.style.background = currentSplashBgSwatchHex();
            left.appendChild(swatch);

            var label = document.createElement('div');
            label.style.cssText = 'color:var(--header-primary);font-size:14px;font-weight:500;';
            label.textContent = 'Splash background';
            left.appendChild(label);

            row.appendChild(left);

            var chevron = document.createElement('span');
            chevron.setAttribute('aria-hidden', 'true');
            chevron.style.cssText = 'color:var(--interactive-muted,#72767d);font-size:18px;flex:none;line-height:1;';
            chevron.textContent = '›';
            row.appendChild(chevron);

            row.addEventListener('click', function () {
                openSplashBgMenu(swatch);
            });

            wrap.appendChild(row);

            var divider = document.createElement('div');
            divider.className = 'vde-divider-setting';
            divider.style.cssText = 'width:100%;height:1px;border-top:thin solid var(--background-modifier-accent);margin-top:20px;margin-bottom:20px;';
            wrap.appendChild(divider);

            section.appendChild(wrap);
            console.warn('[Vendroid] Splash background row injected into Vendroid settings');
        } catch (e) {
            console.error('[Vendroid] injectSplashBgRowIfMissing error: ' + e.message);
        }
    }

    // Shared HSV picker modal for the color rows. opts: menuAttr (backdrop
    // data attribute, one per caller), title, resetLabel, getHex/setHex (the
    // persisted-value bridge round-trip; '' = unset), defaultHex (display
    // fallback), presets, swatchEl + refreshSwatch (row swatch refresh after
    // a committed write).
    function openColorMenu(opts) {
        try {
            if (document.body.querySelector('[' + opts.menuAttr + ']')) return;

            var themeEl = (opts.swatchEl && opts.swatchEl.closest('.vde-rows-section')) ||
                document.querySelector('.vde-rows-section');
            var cHeader = vdeThemeColor(themeEl, '--header-primary', '#f2f3f5');
            var cMuted = vdeThemeColor(themeEl, '--text-muted', '#b9bbbe');
            var cBg = vdeThemeColor(themeEl, '--background-mobile-primary', '#313338');
            var cBrand = vdeThemeColor(themeEl, '--brand-primary', '#5865f2');
            var cAccent = vdeThemeColor(themeEl, '--background-modifier-accent', '#4f545c');
            var cDanger = vdeThemeColor(themeEl, '--status-danger', '#f23f43');

            var hsv = vdeHexToHsv(opts.getHex() || opts.defaultHex());
            var menuBusy = false;
            var hexFocused = false;

            var backdrop = document.createElement('div');
            backdrop.setAttribute(opts.menuAttr, '1');
            backdrop.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.7);z-index:99999;display:flex;overflow-y:auto;overscroll-behavior:contain;-webkit-tap-highlight-color:transparent;';

            function onKey(ev) {
                if (ev && (ev.key === 'Escape' || ev.key === 'Esc')) {
                    // Swallow the Escape, matching the icon menu.
                    ev.preventDefault();
                    ev.stopPropagation();
                    ev.stopImmediatePropagation();
                    close();
                }
            }
            function close() {
                try {
                    window.removeEventListener('keydown', onKey, true);
                    // Blur before removing: dropping a focused element (the
                    // hue slider or hex input) yanks the WebView's scroll,
                    // which made the settings panel jump on exit.
                    var ae = document.activeElement;
                    if (ae && backdrop.contains(ae)) ae.blur();
                    if (backdrop.parentNode) backdrop.parentNode.removeChild(backdrop);
                } catch (e) {
                    console.error('[Vendroid] bar color menu close failed: ' + e.message);
                }
            }
            backdrop.vdeClose = close;

            var card = document.createElement('div');
            card.style.cssText = 'background:' + cBg + ';border-radius:8px;width:min(340px,calc(100vw - 48px));margin:auto;padding:16px;box-shadow:0 8px 32px rgba(0,0,0,.5);';

            var header = document.createElement('div');
            header.style.cssText = 'display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:4px;';
            var title = document.createElement('div');
            title.style.cssText = 'color:' + cHeader + ';font-size:20px;font-weight:700;line-height:24px;';
            title.textContent = opts.title;
            header.appendChild(title);
            var closeBtn = document.createElement('button');
            closeBtn.type = 'button';
            closeBtn.setAttribute('aria-label', 'Close color picker');
            closeBtn.style.cssText = 'width:32px;height:32px;border:none;border-radius:50%;background:' + cAccent + ';color:' + vdeThemeColor(themeEl, '--interactive-active', '#ffffff') + ';font-size:15px;line-height:1;cursor:pointer;flex:none;display:flex;align-items:center;justify-content:center;';
            closeBtn.textContent = '✕';
            closeBtn.addEventListener('click', function (ev) {
                ev.stopPropagation();
                close();
            });
            header.appendChild(closeBtn);
            card.appendChild(header);

            var preview = document.createElement('div');
            preview.style.cssText = 'height:36px;border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:600;margin-bottom:12px;';
            card.appendChild(preview);

            // SV pad: hue base + white and black gradient layers, absolutely
            // positioned. Pointer capture + touch-action:none keep the drag
            // from panning the settings panel behind the modal.
            var pad = document.createElement('div');
            pad.style.cssText = 'position:relative;width:100%;height:140px;border-radius:8px;touch-action:none;cursor:crosshair;overflow:hidden;flex:none;';
            var padBase = document.createElement('div');
            padBase.style.cssText = 'position:absolute;inset:0;';
            var padWhite = document.createElement('div');
            padWhite.style.cssText = 'position:absolute;inset:0;background:linear-gradient(to right,#fff,rgba(255,255,255,0));';
            var padBlack = document.createElement('div');
            padBlack.style.cssText = 'position:absolute;inset:0;background:linear-gradient(to top,#000,rgba(0,0,0,0));';
            var cursorDot = document.createElement('div');
            cursorDot.style.cssText = 'position:absolute;width:14px;height:14px;border-radius:50%;border:2px solid #fff;box-shadow:0 0 4px rgba(0,0,0,.5);transform:translate(-50%,-50%);pointer-events:none;box-sizing:border-box;';
            pad.appendChild(padBase);
            pad.appendChild(padWhite);
            pad.appendChild(padBlack);
            pad.appendChild(cursorDot);
            card.appendChild(pad);

            var dragging = false;
            function padMove(ev) {
                var rect = pad.getBoundingClientRect();
                if (rect.width < 1 || rect.height < 1) return;
                var x = (ev.clientX - rect.left) / rect.width;
                var y = (ev.clientY - rect.top) / rect.height;
                hsv.s = Math.round(Math.min(1, Math.max(0, x)) * 100);
                hsv.v = Math.round((1 - Math.min(1, Math.max(0, y))) * 100);
                render();
            }
            pad.addEventListener('pointerdown', function (ev) {
                ev.preventDefault();
                dragging = true;
                try { pad.setPointerCapture(ev.pointerId); } catch (e) {}
                padMove(ev);
            });
            pad.addEventListener('pointermove', function (ev) { if (dragging) padMove(ev); });
            pad.addEventListener('pointerup', function () { dragging = false; });
            pad.addEventListener('pointercancel', function () { dragging = false; });

            var hueWrap = document.createElement('div');
            hueWrap.style.cssText = 'margin-top:10px;';
            var hueInput = document.createElement('input');
            hueInput.type = 'range';
            hueInput.min = '0';
            hueInput.max = '359';
            hueInput.step = '1';
            hueInput.setAttribute('aria-label', 'Hue');
            hueInput.style.cssText = 'width:100%;margin:0;accent-color:' + cBrand + ';';
            hueInput.addEventListener('input', function () {
                hsv.h = parseInt(hueInput.value, 10) || 0;
                render();
            });
            hueWrap.appendChild(hueInput);
            card.appendChild(hueWrap);

            var hexWrap = document.createElement('div');
            hexWrap.style.cssText = 'display:flex;gap:8px;margin-top:10px;align-items:center;';
            var hexLabel = document.createElement('span');
            hexLabel.style.cssText = 'color:' + cMuted + ';font-size:12px;flex:none;';
            hexLabel.textContent = 'Hex';
            var hexInput = document.createElement('input');
            hexInput.type = 'text';
            hexInput.spellcheck = false;
            hexInput.maxLength = 7;
            hexInput.setAttribute('aria-label', 'Hex color');
            hexInput.style.cssText = 'flex:1 1 auto;min-width:0;padding:8px 10px;border-radius:8px;border:thin solid ' + cAccent + ';background:transparent;color:' + cHeader + ';font-size:14px;box-sizing:border-box;';
            hexInput.addEventListener('focus', function () { hexFocused = true; });
            hexInput.addEventListener('blur', function () {
                hexFocused = false;
                hexInput.style.borderColor = cAccent;
                render();
            });
            hexInput.addEventListener('input', function () {
                var v = hexInput.value.trim();
                if (v.charAt(0) !== '#') v = '#' + v;
                if (/^#[0-9a-fA-F]{6}$/.test(v)) {
                    hexInput.style.borderColor = cAccent;
                    hsv = vdeHexToHsv(v.toLowerCase());
                    render();
                } else {
                    hexInput.style.borderColor = cDanger;
                }
            });
            hexWrap.appendChild(hexLabel);
            hexWrap.appendChild(hexInput);
            card.appendChild(hexWrap);

            var presets = document.createElement('div');
            presets.style.cssText = 'display:flex;gap:8px;margin-top:12px;flex-wrap:wrap;';
            var presetButtons = [];
            opts.presets.forEach(function (hex) {
                var b = document.createElement('button');
                b.type = 'button';
                b.setAttribute('aria-label', 'Preset ' + hex);
                b.style.cssText = 'width:32px;height:32px;border-radius:8px;border:thin solid ' + cAccent + ';cursor:pointer;padding:0;flex:none;';
                b.style.background = hex;
                b.addEventListener('click', function () {
                    hsv = vdeHexToHsv(hex);
                    render();
                });
                presets.appendChild(b);
                presetButtons.push({ hex: hex, el: b });
            });
            card.appendChild(presets);

            var footer = document.createElement('div');
            footer.style.cssText = 'display:flex;align-items:center;gap:8px;margin-top:14px;';
            function footerButton(text, style) {
                var b = document.createElement('button');
                b.type = 'button';
                b.textContent = text;
                b.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;padding:8px 14px;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer;flex:none;-webkit-tap-highlight-color:transparent;' + style;
                return b;
            }
            var resetBtn = footerButton(opts.resetLabel, 'background:transparent;color:' + cDanger + ';border:thin solid ' + cDanger + ';');
            resetBtn.addEventListener('click', function () { if (!menuBusy) commit(''); });
            footer.appendChild(resetBtn);
            var spacer = document.createElement('span');
            spacer.style.cssText = 'flex:1 1 auto;';
            footer.appendChild(spacer);
            var cancelBtn = footerButton('Cancel', 'background:transparent;color:' + cMuted + ';border:none;');
            cancelBtn.addEventListener('click', function () { close(); });
            footer.appendChild(cancelBtn);
            var applyBtn = footerButton('Apply', 'background:' + cBrand + ';color:#fff;border:none;');
            applyBtn.addEventListener('click', function () { if (!menuBusy) commit(vdeHsvToHex(hsv)); });
            footer.appendChild(applyBtn);
            card.appendChild(footer);

            // Single commit point for Apply and Reset. Write, then re-read the
            // persisted value and reconcile: a rejected write must not leave
            // an unapplied selection on screen. No bridge traffic during
            // drags; the dedicated setHex call is one write per commit.
            function commit(value) {
                if (menuBusy) return;
                menuBusy = true;
                try {
                    opts.setHex(value);
                    var persisted = opts.getHex() || '';
                    if (persisted === value) {
                        hsv = vdeHexToHsv(persisted || opts.defaultHex());
                        opts.refreshSwatch(opts.swatchEl);
                        render();
                    } else {
                        console.error('[Vendroid] color write rejected; selection unchanged');
                    }
                } catch (e) {
                    console.error('[Vendroid] color commit failed: ' + e.message);
                }
                setTimeout(function () { menuBusy = false; }, 700);
            }

            function render() {
                var hex = vdeHsvToHex(hsv);
                padBase.style.background = 'hsl(' + hsv.h + ',100%,50%)';
                cursorDot.style.left = hsv.s + '%';
                cursorDot.style.top = (100 - hsv.v) + '%';
                cursorDot.style.background = hex;
                hueInput.value = hsv.h;
                if (!hexFocused) hexInput.value = hex;
                preview.style.background = hex;
                preview.style.color = vdeBarLuma(hex) > 0.5 ? '#000' : '#fff';
                preview.textContent = hex;
                presetButtons.forEach(function (p) {
                    p.el.style.boxShadow = p.hex === hex ? '0 0 0 3px ' + cBrand : 'none';
                });
            }

            // Contain clicks and keydowns at the backdrop bubble phase, after
            // our element handlers run: an event that reaches Discord's
            // document-level settings handlers scrolls the panel to the
            // bottom (the Escape note above; Cancel and backdrop-tap leaked
            // the same way). Detached-node listeners still fire, so this
            // holds even though close() removes the backdrop mid-dispatch.
            backdrop.addEventListener('click', function (ev) { ev.stopPropagation(); });
            backdrop.addEventListener('keydown', function (ev) { ev.stopPropagation(); });
            backdrop.addEventListener('click', function (ev) {
                if (ev.target === backdrop) close();
            });
            window.addEventListener('keydown', onKey, true);

            render();
            backdrop.appendChild(card);
            document.body.appendChild(backdrop);
            console.warn('[Vendroid] Color menu opened: ' + opts.menuAttr);
        } catch (e) {
            console.error('[Vendroid] openColorMenu error: ' + e.message);
        }
    }

    // Row wrappers: one color menu per pref. The glow and splash-bg menus
    // preview the derived value when unset ('' from their get bridges), so
    // the swatch and the picker's starting value match what the splash
    // renders without pinning anything; Apply pins it, Reset returns to
    // auto.
    function openBarColorMenu(swatchEl) {
        openColorMenu({
            menuAttr: 'data-vde-bar-color-menu',
            title: 'Status bar color',
            resetLabel: 'Reset to default',
            getHex: function () { return currentBarHexOrNull() || ''; },
            setHex: function (value) { VencordMobileNative.setBarColor(value); },
            defaultHex: defaultBarHex,
            presets: VDE_COLOR_PRESETS,
            swatchEl: swatchEl,
            refreshSwatch: function (el) {
                updateBarRowSwatch(el);
                // The glow and splash-bg swatches derive from the bar tint
                // when unset, so a bar color change re-derives both.
                updateOrbRowSwatch(document.querySelector('[data-vde-orb-swatch]'));
                updateSplashBgRowSwatch(document.querySelector('[data-vde-splash-bg-swatch]'));
            }
        });
    }

    function openOrbColorMenu(swatchEl) {
        openColorMenu({
            menuAttr: 'data-vde-orb-color-menu',
            title: 'Splash glow color',
            resetLabel: 'Reset to auto',
            getHex: function () { return currentOrbHexOrNull() || ''; },
            setHex: function (value) { VencordMobileNative.setOrbColor(value); },
            defaultHex: derivedOrbHexOrDefault,
            presets: VDE_COLOR_PRESETS,
            swatchEl: swatchEl,
            refreshSwatch: function (el) { updateOrbRowSwatch(el); }
        });
    }

    function openSplashBgMenu(swatchEl) {
        openColorMenu({
            menuAttr: 'data-vde-splash-bg-menu',
            title: 'Splash background',
            resetLabel: 'Reset to auto',
            getHex: function () { return currentSplashBgHexOrNull() || ''; },
            setHex: function (value) { VencordMobileNative.setSplashBgColor(value); },
            defaultHex: derivedSplashBgHexOrDefault,
            presets: VDE_COLOR_PRESETS,
            swatchEl: swatchEl,
            refreshSwatch: function (el) { updateSplashBgRowSwatch(el); }
        });
    }

    var _vendroidVoiceSetupDone = false;
    var _vendroidVoiceSetupRetries = 0;
    var _vendroidVoiceSetupScheduled = false;
    // 60 retries x 250ms = 15s, matching the other patches' lazy-load budget.
    var VENDROID_VOICE_MAX_RETRIES = 60;

