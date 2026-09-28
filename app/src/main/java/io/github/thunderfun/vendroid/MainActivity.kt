package io.github.thunderfun.vendroid

import android.annotation.SuppressLint
import android.Manifest
import android.app.Dialog
import android.app.AlertDialog
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import java.io.File
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.widget.Toast
import io.github.thunderfun.vendroid.utils.Constants
import io.github.thunderfun.vendroid.utils.FirewallConfig
import io.github.thunderfun.vendroid.utils.SettingKeys
import io.github.thunderfun.vendroid.utils.VDELog
import io.github.thunderfun.vendroid.utils.getBooleanSafe
import io.github.thunderfun.vendroid.utils.getStringSafe
import io.github.thunderfun.vendroid.utils.vdeGson
import io.github.thunderfun.vendroid.ui.LoadingScreenManager
import io.github.thunderfun.vendroid.ui.SplashPalette
import io.github.thunderfun.vendroid.webview.BarColorManager
import io.github.thunderfun.vendroid.webview.HttpClient
import io.github.thunderfun.vendroid.webview.HttpClient.fetchVencord
import io.github.thunderfun.vendroid.webview.LinkHandler
import io.github.thunderfun.vendroid.webview.MainFrameDiskCache
import io.github.thunderfun.vendroid.webview.NavigationPolicy
import io.github.thunderfun.vendroid.webview.RuntimeInjector
import io.github.thunderfun.vendroid.webview.UrlNormalizer
import io.github.thunderfun.vendroid.webview.VChromeClient
import io.github.thunderfun.vendroid.webview.VWebviewClient
import io.github.thunderfun.vendroid.webview.VencordNative
import io.github.thunderfun.vendroid.webview.WindowSystemBarTarget
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {
    private var wvInitialized = false
    private var prewarmUsed = false
    // WebView threading model (read before touching wv or its callbacks):
    //  - All access is on the UI thread. onDestroy nulls wv right after
    //    destroy(), and since callbacks also run on the UI thread, nothing
    //    can observe the gap: wv != null means the WebView is alive.
    //  - Entry checks like "val w = wv ?: return" are the destroy-guards;
    //    try/catch IllegalStateException elsewhere is only defense-in-depth.
    //  - A destroyed WebView may still deliver pending evaluateJavascript
    //    results (null or stale). A callback holding a captured instance must
    //    re-check this field before calling into it; see the probe callback
    //    in injectVencordAttempt and showDiscordToast.
    private var wv: WebView? = null

    /** Cached URL for bridge-thread safety: updated on the UI thread and read
     *  off-thread instead of calling wv.url directly. */
    @Volatile
    var currentUrlForBridge: String? = null
    @Volatile
    var currentHostForBridge: String? = null
    /** True between a main-frame commit and onPageFinished. Lets the bridge's
     *  strict domain check skip the UI-thread round trip in steady state. */
    @Volatile
    var navigationInProgress = false
    @Volatile
    var missedInjection = false
    /** Deep link received via onNewIntent during onCreate; applied once the
     *  WebView is initialized so the initial load does not overwrite it. */
    @Volatile
    private var pendingDeepLink: String? = null
    private lateinit var chromeClient: VChromeClient
    private lateinit var vencordNative: VencordNative

    /**
     * Sole writer of this window's status/nav bar colors. The Vencord overlay
     * and fullscreen video publish their state here instead of managing
     * colors themselves; see [BarColorManager]. Route any future bar-color
     * change through it. Direct window writes reintroduce the clobbering
     * bug this replaced. Per-window by design; it dies with this activity
     * instance, so a theme change (activity recreation) re-captures the new
     * theme's colors rather than restoring a stale snapshot.
     */
    val barColors: BarColorManager by lazy {
        BarColorManager(WindowSystemBarTarget(window) { runOnUiThread(it) })
    }

    /**
     * Effective custom bar tint from the Vendroid settings color picker, or
     * null when the theme default applies. UI-thread-only writes (startup and
     * the setBarColor bridge path); read by installWebView.
     */
    var customBarColor: Int? = null
        private set

    /** Splash glow color from the settings picker, or null for auto (the
     *  splash ornaments follow the bar tint). Read once at startup; the
     *  splash is the only consumer, so unlike [customBarColor] there is no
     *  live path. */
    private var customOrbColor: Int? = null

    /** Splash background (stage) color from the settings picker, or null for
     *  auto (the stage follows the bar tint through SplashPalette's clamp).
     *  Read once at startup; the splash is the only consumer, so there is no
     *  live path. */
    private var customSplashBgColor: Int? = null

    /**
     * Theme-resolved bar color: the picker's "default" and the reset target.
     * Resolves the attribute (not the color resource) so values-night and any
     * future re-point of the attr stay correct.
     */
    // android.R.attr.statusBarColor is deprecated on API 36+ along with the
    // setter, but reading it is still the only way to get the theme's value,
    // and the app opts out of edge-to-edge enforcement.
    @Suppress("DEPRECATION")
    fun resolveThemeBarColor(): Int {
        val tv = TypedValue()
        if (theme.resolveAttribute(android.R.attr.statusBarColor, tv, true)) {
            try {
                val color = if (tv.type == TypedValue.TYPE_REFERENCE ||
                    tv.type == TypedValue.TYPE_STRING
                ) {
                    if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else null
                } else {
                    tv.data
                }
                if (color != null) return color
            } catch (t: Throwable) {
                VDELog.w("Main", "theme bar color resolve failed; using fallback: $t")
            }
        }
        return Color.parseColor("#121214")
    }

    /** Startup path: read the persisted tint, publish it and tint the chrome. */
    private fun applyCustomBarColor(sPrefs: SharedPreferences) {
        val stored = sPrefs.getStringSafe(SettingKeys.KEY_VENDROID_BAR_COLOR, "") {
            VDELog.e("Main", "vendroid_barColor type-poisoned; using default", it)
        }
        val parsed = VencordNative.parseBarColorOrNull(stored)
        if (parsed != null) applyBarChromeTint(parsed)
    }

    /** Startup path: read the persisted splash glow color (null = auto). */
    private fun loadCustomOrbColor(sPrefs: SharedPreferences) {
        val stored = sPrefs.getStringSafe(SettingKeys.KEY_VENDROID_ORB_COLOR, "") {
            VDELog.e("Main", "vendroid_orbColor type-poisoned; using default", it)
        }
        customOrbColor = VencordNative.parseBarColorOrNull(stored)
    }

    /** Startup path: read the persisted splash background (null = auto). */
    private fun loadSplashBgColor(sPrefs: SharedPreferences) {
        val stored = sPrefs.getStringSafe(SettingKeys.KEY_VENDROID_SPLASH_BG_COLOR, "") {
            VDELog.e("Main", "vendroid_splashBgColor type-poisoned; using default", it)
        }
        customSplashBgColor = VencordNative.parseBarColorOrNull(stored)
        applySplashStageColor()
    }

    /** Paints the loading screen stage: the splash-bg pick verbatim, else
     *  the clamped bar tint (auto), else the theme default. UI thread
     *  only. */
    private fun applySplashStageColor() {
        val stage = customSplashBgColor
            ?: SplashPalette.stageColor(customBarColor)
            ?: resolveThemeBarColor()
        findViewById<View>(R.id.loading_screen)?.background = ColorDrawable(stage)
    }

    /**
     * Applies the picker tint to every surface that visually continues the
     * bars (window background, WebView background); null resets to the
     * theme default. The loading screen is the one exception: its stage
     * comes from the splash-bg setting when set, else this tint clamped by
     * SplashPalette, so the splash stays dark and the ornaments keep
     * contrast. LoadingScreenManager tints the ornaments once at start().
     *
     * UI thread only; safe during onCreate (wv not yet installed) and from
     * the bridge. Publishes to [barColors] before the window writes, which
     * can fail during teardown (detached decor view); a failure must not
     * leave the manager missing the tint the persisted pref now holds.
     */
    fun applyBarChromeTint(color: Int?) {
        customBarColor = color
        try {
            barColors.publishCustomColor(color)
            val effective = color ?: resolveThemeBarColor()
            window.setBackgroundDrawable(ColorDrawable(effective))
            applySplashStageColor()
            wv?.setBackgroundColor(effective)
        } catch (t: Throwable) {
            VDELog.e("Main", "applyBarChromeTint failed", t)
        }
    }

    @JvmField
    var filePathCallback: ValueCallback<Array<Uri>>? = null

    // WebView capture (getUserMedia) permission flow. onPermissionRequest
    // runs on the UI thread, but the runtime prompt is answered later, so the
    // request is parked here until onRequestPermissionsResult. UI thread only,
    // like wv.
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingPermissionResources: Array<String> = emptyArray()

    val fileChooserLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult
        if (result.data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        val resultArray = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data!!)
        callback.onReceiveValue(resultArray)
    }

    private lateinit var loadingScreenManager: LoadingScreenManager

    /** Public so the WebView/JS layers can schedule/dismiss the loading screen
     *  directly without a pass-through facade on the activity. */
    val loadingScreen: LoadingScreenManager get() = loadingScreenManager

    // Dialogs created by this activity (risk warning, link popup, asset
    // editors). UI-thread only, like wv.
    private val managedDialogs = mutableListOf<Dialog>()

    /** Registers a dialog for teardown in onDestroy. Call before [Dialog.show]. */
    fun registerDialog(dialog: Dialog) {
        managedDialogs.add(dialog)
    }

    /** Drops a dialog whose dismiss listener has run. */
    fun unregisterDialog(dialog: Dialog) {
        managedDialogs.remove(dialog)
    }

    private fun dismissManagedDialogs() {
        // Snapshot: dismiss listeners (posted) call unregisterDialog.
        for (dialog in managedDialogs.toList()) {
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (t: Throwable) {
                VDELog.w("Main", "Dialog dismiss failed during teardown: $t")
            }
        }
        managedDialogs.clear()
    }

    private val fetchExecutor = Executors.newSingleThreadExecutor()

    private fun migrateSettings() {
        val sPrefs = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)
        // getBooleanSafe: migratedSettings itself can arrive wrong-typed (restored
        // or hand-edited XML). Treating it as unmigrated is safe; the apply()
        // below overwrites it with a real Boolean.
        if (sPrefs.getBooleanSafe(SettingKeys.KEY_MIGRATED_SETTINGS, false)) return
        val ed = sPrefs.edit()
        ed.putBoolean(SettingKeys.KEY_MIGRATED_SETTINGS, true)

        // Flag, derived values, and legacy-key removals share one apply(): a
        // lost batch re-runs the whole migration instead of leaving the flag
        // set with half the work done.
        val migration = computeSettingsMigration(sPrefs.all)
        for (key in migration.uncoercible) {
            VDELog.w("Main", "$key type-poisoned; using default")
        }
        ed.putBoolean(SettingKeys.KEY_CHECK_VDE_UPDATES, migration.checkVDEUpdates)
        // Both toggles were historically controlled by the single legacy
        // checkVendroidUpdates flag; keep them in sync during migration so an
        // existing user does not silently lose one.
        ed.putBoolean(SettingKeys.KEY_CHECK_ANNOUNCEMENTS, migration.checkVDEUpdates)
        // Derive clientMod from the legacy boolean only if unset; re-runs
        // (reinstall/flag wipe) must not clobber an existing choice.
        migration.clientMod?.let { ed.putString(SettingKeys.KEY_CLIENT_MOD, it) }

        ed.remove(SettingKeys.KEY_CHECK_VENDROID_UPDATES)
        ed.remove(SettingKeys.KEY_EQUICORD)
        ed.remove("splash")

        ed.apply()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VDELog.i("Main", "onCreate()")
        // VendroidApp gates FirewallConfig.init on process-name detection,
        // which can fail on API 26/27. This activity only ever runs in :web
        // and init() is idempotent, so initializing here is always safe.
        if (!FirewallConfig.isInitialized()) {
            FirewallConfig.init(applicationContext)
            Constants.invalidateFirewallCaches()
        }
        // Load settings once and reuse throughout onCreate.
        val sPrefs = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)
        // migrateSettings early-returns once migratedSettings is set. Its
        // reads are guarded; this catch exists so a future unguarded read
        // degrades to defaults instead of crash-looping cold start.
        try {
            migrateSettings()
        } catch (t: Throwable) {
            VDELog.e("Main", "Settings migration failed; continuing with defaults", t)
        }

        // One-shot notice for the boot-time vencordLocation heal
        // (VendroidApp.healUnusableVencordLocation). First-entry gate, same
        // pattern as safeMode below: the flag persists until a MainActivity
        // runs, so launching RecoveryActivity first just delays the notice.
        // The heal writes the flag as a Boolean; getBooleanSafe contains any
        // future regression instead of crash-looping cold start.
        if (sPrefs.getBooleanSafe(VendroidApp.PREF_VENCORD_LOCATION_HEALED, false) {
                VDELog.w("Main", "heal notice flag type-poisoned; ignoring: $it")
            }) {
            Toast.makeText(
                this,
                "Removed custom Vencord source (no longer permitted); the official bundle is used instead",
                Toast.LENGTH_LONG
            ).show()
            // Clear after showing: a crash in between repeats the notice
            // once rather than losing it.
            sPrefs.edit().remove(VendroidApp.PREF_VENCORD_LOCATION_HEALED).apply()
            VDELog.i("Main", "Notified: unusable vencordLocation was healed at boot")
        }

        // First-run security disclosure. Do not load Discord, the WebView, or
        // any injected code until the user accepts the risks of a modified
        // Discord client running third-party code.
        // getBooleanSafe: a poisoned value would crash-loop the :web cold start,
        // and no recovery action rewrites this key. Defaulting to false
        // re-shows the warning; accepting overwrites the key with a real Boolean.
        if (!sPrefs.getBooleanSafe(SettingKeys.KEY_RISK_WARNING_ACCEPTED, false) {
                VDELog.w("Main", "riskWarningAccepted type-poisoned; showing warning: $it")
            }) {
            showFirstRunWarning(sPrefs)
            return
        }
        proceedWithStartup(sPrefs)
    }

    /**
     * Shows the first-run security warning. Blocks app startup until the user
     * explicitly accepts the risks; declining (back/dismiss) closes the app.
     * Persists acceptance so this only shows once.
     */
    private fun showFirstRunWarning(sPrefs: SharedPreferences) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.risk_warning_title)
            .setMessage(R.string.risk_warning_body)
            .setCancelable(false) // block bypass via outside tap / back
            .setPositiveButton(R.string.risk_warning_accept) { _, _ ->
                sPrefs.edit().putBoolean(SettingKeys.KEY_RISK_WARNING_ACCEPTED, true).apply()
                proceedWithStartup(sPrefs)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .create()
        dialog.setOnDismissListener {
            unregisterDialog(dialog)
            // Teardown dismissal must not re-enter finish() on a dying activity.
            if (isFinishing || isDestroyed) return@setOnDismissListener
            if (!sPrefs.getBooleanSafe(SettingKeys.KEY_RISK_WARNING_ACCEPTED, false)) finish()
        }
        registerDialog(dialog)
        dialog.show()
    }

    /** The body of the original onCreate, run only after the risk warning is
     *  accepted. */
    private fun proceedWithStartup(sPrefs: SharedPreferences) {
        window.setFormat(PixelFormat.OPAQUE)

        val editor = sPrefs.edit()

        // WebView debugging exposes the page (cookies, token, JS context) to
        // any attached debugger. Gate it behind an explicit build flag.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.ALLOW_WEBVIEW_DEBUGGING)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Publish the persisted picker tint before anything else draws: the
        // manager snapshots the theme values on its first reapply, so this
        // must run after setContentView (theme attrs applied to the window)
        // and before any overlay/video publish could possibly arrive.
        applyCustomBarColor(sPrefs)
        loadCustomOrbColor(sPrefs)
        loadSplashBgColor(sPrefs)

        setupBackPress()

        loadingScreenManager = LoadingScreenManager(
            this, findViewById(R.id.loading_screen), customBarColor, customOrbColor
        )
        // Start the animation now so the splash does not freeze during WebView setup.
        loadingScreenManager.start()
        loadingScreenManager.scheduleTimeout(30000)

        installWebView(sPrefs)
        syncFeatureToggles(sPrefs)
        configureServiceWorker()

        loadVencordRuntimes(sPrefs, editor)

        val initialUrl = resolveInitialUrl(sPrefs, intent)
        currentUrlForBridge = initialUrl

        // resolveInitialUrl just consumed the launch intent; defuse it.
        // configChanges (manifest) omits uiMode/locale/density, so dark-mode
        // and locale changes recreate this activity. The recreation
        // redelivers this same intent, setIntent included, so without the
        // defuse every recreation re-ran the deep link: invites threw the
        // user out of their channel and policy-triggering links re-showed
        // the popup. A plain Intent() has a null action, so recreation falls
        // through to the remembered-URL path. setIntent(null) would instead
        // NPE resolveInitialUrl's intent.action read. Client-side only, so
        // a process-death relaunch still redelivers the original link once.
        if (intent.action == Intent.ACTION_VIEW) {
            setIntent(Intent())
        }

        wvInitialized = true

        // Apply a deep link stashed by onNewIntent during onCreate.
        pendingDeepLink?.let { link ->
            pendingDeepLink = null
            handleUrl(Uri.parse(link))
        }
    }

    /** Registers the back-press handler that proxies to the Discord JS app. */
    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv != null) {
                    val isFullscreen = chromeClient.isFullscreen
                    if (isFullscreen) {
                        chromeClient.hideCustomView()
                        return
                    }
                    wv!!.evaluateJavascript("VencordMobile.onBackPress()") { r ->
                        // "true" = JS handled it (closed a modal etc.). Anything
                        // else (null on a JS error in safe mode or before the
                        // runtime loads) falls through to the default back
                        // action so the user is never stuck.
                        if ("true" != r) {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    /** Installs the real WebView and wires the WebView/Chrome clients and
     *  settings. */
    private fun installWebView(sPrefs: SharedPreferences) {
        // The layout's @id/webview is a plain View placeholder so setContentView
        // does not inflate a WebView (Chromium init) before clients are wired up.
        val placeholder = findViewById<View>(R.id.webview)
        val parent = placeholder?.parent as? ViewGroup
        val params = placeholder?.layoutParams
        val index = if (parent != null) parent.indexOfChild(placeholder) else -1
        val prewarmed = VendroidApp.prewarmedWebView
        wv = if (prewarmed != null) {
            prewarmUsed = true
            prewarmed
        } else {
            WebView(this)
        }
        // Custom tint when set; falls back to the theme color so the WebView
        // never flashes a rectangle that mismatches the bars above it.
        wv!!.setBackgroundColor(customBarColor ?: resolveThemeBarColor())
        // Keep the id so VChromeClient / VencordNative still find the WebView.
        wv!!.id = R.id.webview
        if (parent != null && params != null && index >= 0) {
            parent.removeView(placeholder)
            parent.addView(wv, index, params)
        }
        VendroidApp.prewarmedWebView = null

        chromeClient = VChromeClient(this)
        val webViewClient = VWebviewClient(this)
        wv!!.setWebViewClient(webViewClient)
        wv!!.setWebChromeClient(chromeClient)

        applyWebViewSettings(wv!!.settings, sPrefs)
        applyWebViewViewFlags(wv!!)

        CookieManager.getInstance().setAcceptThirdPartyCookies(wv!!, false)
    }

    /** Applies the WebSettings half of [installWebView]'s setup. */
    private fun applyWebViewSettings(s: WebSettings, sPrefs: SharedPreferences) {
        // getBoolean throws on a non-Boolean value under desktopMode; fall
        // back to the default so stale or type-poisoned prefs cannot crash
        // cold start.
        if (sPrefs.getBooleanSafe(SettingKeys.KEY_DESKTOP_MODE, false)) {
            s.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        }
        // Sync the UA cache VWebviewClient uses for intercepted fetches.
        VWebviewClient.updateWebViewUserAgent(s.userAgentString)
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false

        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.setBuiltInZoomControls(false)
        s.setUseWideViewPort(true)
        s.setLoadWithOverviewMode(true)
        s.textZoom = 100
        // Pre-rasterize offscreen tiles during scroll/fling so new content
        // appears painted when scrolled into view. Modest GPU memory cost,
        // visibly smoother scrolling on long message lists.
        s.offscreenPreRaster = true

        // Disable Safe Browsing
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(s, false)
        }
    }

    /** Applies the View-level half of [installWebView]'s setup. */
    private fun applyWebViewViewFlags(wv: WebView) {
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.isLongClickable = false
        wv.isHapticFeedbackEnabled = false
        wv.isScrollContainer = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.defaultFocusHighlightEnabled = false
        }

        // Keep the Chromium renderer at IMPORTANT priority and never waive it
        // when hidden, so the OS cannot kill or throttle it and touch stays fast.
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
    }

    /** Syncs the feature-toggle flags (read once at startup) to the live
     *  seams. */
    private fun syncFeatureToggles(sPrefs: SharedPreferences) {
        // Read into a @Volatile field so shouldInterceptRequest does not hit
        // SharedPreferences per request.
        // getBooleanSafe: a String-typed key left by an older build would crash
        // startup here, before the bridge's write-path recovery can purge it.
        // The first setBool from the settings panel overwrites the bad value.
        val blockTyping = sPrefs.getBooleanSafe(SettingKeys.KEY_VENDROID_BLOCK_TYPING_INDICATOR, false) {
            VDELog.w("Main", "vendroid_blockTypingIndicator type-poisoned; using default: $it")
        }
        VWebviewClient.updateTypingBlock(blockTyping)

        // Sync the external-link confirmation toggle to the link popup.
        // getBooleanSafe: a String-typed key left by an older build would crash
        // startup here, before the bridge's write-path recovery can purge it.
        val confirmLinks = sPrefs.getBooleanSafe(SettingKeys.KEY_VENDROID_CONFIRM_EXTERNAL_LINKS, true)
        LinkHandler.updateConfirmExternalLinks(confirmLinks)
    }

    /** Intercepts Service Worker fetch events (API 24+), which bypass
     *  WebViewClient.shouldInterceptRequest entirely. */
    private fun configureServiceWorker() {
        if (WebViewFeature.isFeatureSupported(
                WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            ServiceWorkerControllerCompat.getInstance()
                .setServiceWorkerClient(
                    object : ServiceWorkerClientCompat() {
                        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
                        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                            // Reuse the shared gate so the SW path cannot drift
                            // from the WebView client.
                            return VWebviewClient.shouldBlockForRequest(request)
                        }
                    }
                )
        }
    }

    /** Loads the Vencord runtimes (bridge + JS bundle) unless safe mode is
     *  active. Keyed on the kill switch OR the pref: the pref is reset by this
     *  branch's first run, so a mid-session activity recreation (dark-mode
     *  toggle, not covered by configChanges) must not fall through and
     *  re-publish the on-disk bundle.
     *
     *  Runs on the UI thread; disk I/O is delegated to
     *  [loadVencordRuntimesFromDisk]. */
    private fun loadVencordRuntimes(sPrefs: SharedPreferences, editor: SharedPreferences.Editor) {
        // getBooleanSafe: a String-typed safeMode key (restored or hand-edited
        // XML) would crash-loop :web cold start. The fallback is TRUE, not
        // the file's usual false: false means "load Vencord", which would
        // silently ignore the user's recovery request forever, since nothing
        // rewrites a poisoned key. True fails safe and routes into the else
        // branch, where the one-shot reset below overwrites the poison with
        // a real Boolean. Read once for both branches; nothing rewrites the
        // key before the one-shot reset below.
        val safeMode = sPrefs.getBooleanSafe(SettingKeys.KEY_SAFE_MODE, false, poisonDefault = true) {
            VDELog.w("Main", "safeMode type-poisoned; failing safe: $it")
        }
        // One-shot recovery flag for the "Disable themes" card. VendroidApp
        // already raised the in-memory gate at process start, so this read
        // only drives the toast and the reset; it sits outside the branch
        // below because a themes-disabled session loads Vencord normally.
        // Poison reads as true, mirroring the safeMode read above.
        val disableThemes = sPrefs.getBooleanSafe(
            SettingKeys.KEY_DISABLE_THEMES, false, poisonDefault = true
        ) {
            VDELog.w("Main", "disableThemes type-poisoned; failing safe: $it")
        }
        if (disableThemes) {
            Toast.makeText(this, "User themes disabled for this session", Toast.LENGTH_SHORT)
                .show()
            VDELog.w("Main", "User themes disabled for this session; resetting one-shot flag")
            editor.putBoolean(SettingKeys.KEY_DISABLE_THEMES, false)
            editor.apply()
        }
        if (!HttpClient.vencordDisabled && !safeMode) {
            vencordNative = VencordNative(WeakReference(this), wv!!)
            wv?.addJavascriptInterface(vencordNative, "VencordMobileNative")
            // Usually a no-op: VendroidApp.onCreate() preloads both runtimes
            // on a background thread, but a cold start can lose that race with
            // the preload still mid-read. Never load inline. This is the UI
            // thread, and reading ~1 MB (plus its SHA-256 hash, plus the regex
            // pass a stale patch flag triggers) is startup jank at the busiest
            // point of the launch. The reads queue on fetchExecutor
            // immediately; the conditional GET runs much later (see
            // scheduleDeferredBundleCheck), so the disk read always completes
            // first and the 304 branch skips its re-read.
            if (HttpClient.VencordRuntime == null || HttpClient.VencordMobileRuntime == null) {
                loadVencordRuntimesFromDisk(sPrefs)
            }
            scheduleDeferredBundleCheck()
        } else {
            // Raise the switch and clear anything already in memory. No-ops
            // after a cold start (VendroidApp did both); load-bearing when
            // the activity re-enters safe mode in a running process.
            HttpClient.vencordDisabled = true
            HttpClient.setVencordRuntime(null)
            HttpClient.setVencordMobileRuntime(null)
            // First-entry gate: the kill switch persists across recreations,
            // the pref does not, so only the first run toasts and resets.
            // Reads the single safeMode val above; poison reads as true, so
            // this reset still runs and the key heals.
            if (safeMode) {
                Toast.makeText(this, "Safe mode enabled, Vencord won't be loaded", Toast.LENGTH_SHORT)
                    .show()
                VDELog.w("Main", "Safe mode enabled; Vencord will not load")
                editor.putBoolean(SettingKeys.KEY_SAFE_MODE, false)
                editor.apply()
            }
        }
    }

    /**
     * Schedules the bundle freshness check past the boot window instead of
     * running it at startup, keeping the second-host TCP/TLS setup and any
     * 200 download out of the most latency-sensitive window of boot. The
     * deferral is safe only while a runtime can paint without the network,
     * since the check produces just freshness bookkeeping and, on a new
     * bundle, a mid-session publish applied on the next navigation. When no
     * runtime is loadable (a clientMod switch deleted the bundle, an app
     * version bump skipped the disk preload, a fresh install), the fetch
     * starts with boot instead; a deferral would leave first paint un-modded
     * until the missedInjection reload. [bundleCheckDelayMs] owns the verdict.
     *
     * The posted Runnable captures only locals plus a WeakReference:
     * referencing [fetchExecutor] in the lambda would resolve it through the
     * activity and strongly retain it for the whole deferral.
     *
     * Liveness is re-checked at execution time because onDestroy may run
     * while the post is pending. isDestroyed is set before onDestroy
     * dispatches and this callback is serialized with it on the main thread,
     * so the guard is airtight even though a finished activity is not
     * necessarily isFinishing (config-change recreation); the
     * RejectedExecutionException catch below is not the primary guard.
     */
    private fun scheduleDeferredBundleCheck() {
        val executor = fetchExecutor
        val weakSelf = WeakReference(this)
        val sPrefs = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)
        // Cheap pref reads; the delay must be decided before postDelayed.
        val delayMs = bundleCheckDelayMs(
            runtimeInMemory = HttpClient.VencordRuntime != null,
            needsRedownload = HttpClient.needsBundleRedownload(sPrefs),
            bundleFileExists = File(filesDir, "vencord.js").exists()
        )
        Handler(Looper.getMainLooper()).postDelayed({
            val act = weakSelf.get()
            if (act == null || act.isFinishing || act.isDestroyed) return@postDelayed
            // Mirrors runSafetyNetLoad's enqueue-race guard. fetchVencord now
            // self-gates on the kill switch, so this only avoids enqueueing
            // the task in safe mode. No in-process path currently flips
            // vencordDisabled after scheduling (RecoveryActivity kills the
            // :web process before committing safeMode, and the pref is
            // one-shot-reset), so the check is cheap insurance.
            if (HttpClient.vencordDisabled) return@postDelayed
            try {
                executor.execute {
                    val a = weakSelf.get()
                    if (a == null || a.isFinishing || a.isDestroyed) return@execute
                    try {
                        fetchVencord(a)
                    } catch (e: Exception) {
                        // Deliberately broader than IOException: an uncaught
                        // throw on this shared executor kills the process. A
                        // failed bundle check must degrade to a logged error,
                        // never a crash loop; the cached file stays on disk as
                        // the offline fallback.
                        VDELog.e("Main", "fetchVencord failed", e)
                    }
                }
            } catch (_: RejectedExecutionException) {
                // fetchExecutor.shutdownNow() between the post and here;
                // unreachable via the lifecycle (see the liveness note above),
                // kept so a future caller cannot crash the main thread.
            }
        }, delayMs)
    }

    /** Loads whichever runtimes are still missing, off the UI thread. */
    private fun loadVencordRuntimesFromDisk(sPrefs: SharedPreferences) {
        // Capture while the activity is alive; resources/filesDir are not
        // guaranteed after onDestroy. The body lives in the companion object,
        // so the queued lambda holds no activity reference.
        val res = resources
        val dir = filesDir
        val weakSelf = WeakReference(this)
        fetchExecutor.execute { runSafetyNetLoad(sPrefs, res, dir, weakSelf) }
    }

    /** Loads the app shell and re-anchors the bridge host to it. Returns the
     *  shell URL so callers can mirror it into currentUrlForBridge where the
     *  bridge must name the shell before the document commits. */
    private fun loadAppShell(): String {
        wv!!.loadUrl(Constants.APP_SHELL_URL)
        currentHostForBridge = Constants.APP_SHELL_HOST
        return Constants.APP_SHELL_URL
    }

    /**
     * Deep-link policy shared by [resolveInitialUrl] and [handleUrl]:
     * NavigationPolicy.decide on a main-frame navigation, with a SHOW_POPUP
     * verdict routed to the link popup. Returns the verdict; the load/defer
     * handling stays with the callers (cold start loads the shell, a running
     * session defers via pendingDeepLink or drops).
     */
    private fun decideDeepLinkWithPopup(url: Uri): NavigationPolicy.Action {
        // Route through NavigationPolicy so path rules (e.g. /blog -> popup)
        // apply to deep links like in-WebView navigations, instead of
        // bypassing them via a direct loadUrl.
        val action = NavigationPolicy.decide(url, true)
        if (action == NavigationPolicy.Action.SHOW_POPUP) {
            LinkHandler(this).showLinkPopup(url)
        }
        return action
    }

    /** Resolves the initial URL from a deep link intent or the last resume
     *  URL. The caller defuses a consumed deep-link launch (setIntent) so
     *  recreation cannot re-run it. */
    private fun resolveInitialUrl(sPrefs: SharedPreferences, intent: Intent): String {
        if (intent.action == Intent.ACTION_VIEW) {
            val data = intent.data
            val host = data?.host
            // Deep-link gate, see Constants.isDeepLinkHandledDomain.
            if (host != null && Constants.isDeepLinkHandledDomain(host)) {
                val target = data.toString()
                val action = decideDeepLinkWithPopup(data)
                if (action == NavigationPolicy.Action.LOAD_IN_WEBVIEW) {
                    wv!!.loadUrl(target)
                    currentUrlForBridge = target
                    // A discord.gg invite 302s to an app origin; onPageStarted
                    // refreshes both fields on commit.
                    currentHostForBridge = host
                    return target
                }
                // Path/domain rule (e.g. /blog or a CDN host): the popup was
                // already shown by [decideDeepLinkWithPopup] (IGNORE skips
                // it). Either way, load the app shell.
                // Popup variant: this path also re-anchors currentUrlForBridge;
                // set it before loadUrl (as before) so a bridge read before the
                // commit names the shell, not a stale page.
                currentUrlForBridge = Constants.APP_SHELL_URL
                return loadAppShell()
            }
            // Non-Discord deep link (or no data): load the default app shell.
            return loadAppShell()
        }
        // Remember-last-channel off: load the app shell instead of a saved
        // position, and drop any saved URL so re-enabling can't restore one.
        // getBooleanSafe: a String-typed key left by an older build would crash
        // startup here, before the bridge's write-path recovery can purge it.
        if (!sPrefs.getBooleanSafe(SettingKeys.KEY_VENDROID_REMEMBER_LAST_CHANNEL, false) {
                VDELog.w("Main", "vendroid_rememberLastChannel type-poisoned; using default: $it")
            }) {
            if (sPrefs.contains(SettingKeys.KEY_LAST_URL)) {
                sPrefs.edit { remove(SettingKeys.KEY_LAST_URL) }
            }
            return loadAppShell()
        }
        val lastUrl = sPrefs.getString(SettingKeys.KEY_LAST_URL, null)
        if (lastUrl != null) {
            val host = Uri.parse(lastUrl).host
            // The restore below calls loadUrl(), which bypasses
            // shouldOverrideUrlLoading and NavigationPolicy.decide, so
            // isResumableRoute (https + app origin + app-shell path, the
            // cache predicate) is the only policy the resumed URL gets.
            if (host != null && MainFrameDiskCache.isResumableRoute(lastUrl)) {
                wv!!.loadUrl(lastUrl)
                currentUrlForBridge = lastUrl
                currentHostForBridge = host
                return lastUrl
            }
            // Stale non-app URL (e.g. /blog/...): fall back to /app rather
            // than reloading a page with no back history.
            return loadAppShell()
        }
        return loadAppShell()
    }

    private fun handleUrl(url: Uri?) {
        if (url == null) return
        val host = url.host
        // Unhandled hosts are dropped; resolveInitialUrl loads the app shell
        // for them on cold start, but a running session has nothing to load.
        // Deep-link gate, see Constants.isDeepLinkHandledDomain.
        if (host == null || !Constants.isDeepLinkHandledDomain(host)) return
        val path = url.path ?: ""
        // Shared policy with the cold-start path (resolveInitialUrl).
        // Otherwise a /blog link drives the
        // SPA to a page with no back path, and a cdn.discordapp.com link
        // builds a garbage transitionTo route from the URL's path.
        val action = decideDeepLinkWithPopup(url)
        if (action == NavigationPolicy.Action.IGNORE) {
            return
        }
        if (action != NavigationPolicy.Action.LOAD_IN_WEBVIEW) {
            // Path/domain rule triggered (e.g. /blog or a CDN host): the
            // popup was already shown by [decideDeepLinkWithPopup]; stay on
            // the current page (cold start has no current page, so it loads
            // the shell instead). Leave the bridge URL/host fields alone so
            // they keep naming the live page; a non-app-origin host fails
            // VencordNative's domain checks closed.
            VDELog.d("Main", "Deep link policy popup: ${UrlNormalizer.redactForLog(url.toString())}")
            return
        }
        if (!Constants.isDiscordAppOrigin(host)) {
            // discord.gg invites and Activity hosts load as a full navigation,
            // never via transitionTo. An SPA route change fires no page
            // events, so currentHostForBridge would stay non-app-origin and
            // every bridge domain check would fail closed until the next full
            // navigation.
            if (!wvInitialized || wv == null) {
                // Defer until onCreate finishes; handleUrl re-runs the gate
                // on every entry, including routePendingDeepLink.
                pendingDeepLink = url.toString()
            } else {
                // Bridge fields stay untouched; onPageStarted refreshes them
                // as the invite redirect commits.
                wv?.loadUrl(url.toString())
            }
            return
        }
        currentUrlForBridge = url.toString()
        currentHostForBridge = host
        if (!wvInitialized || wv == null) {
            // Defer until onCreate finishes; loadUrl now would be
            // overwritten by the initial-URL load. handleUrl re-runs the
            // policy gate on every entry, including routePendingDeepLink.
            pendingDeepLink = url.toString()
        } else if (HttpClient.VencordMobileRuntime == null) {
            if (HttpClient.vencordDisabled) {
                // Safe mode never consumes a deferred link (no runtimes to
                // inject), and the pref is already reset by now, so key on
                // the session flag.
                VDELog.w("Main", "Safe mode active; loading deep link directly: ${UrlNormalizer.redactForLog(url.toString())}")
                wv?.loadUrl(url.toString())
            } else {
                // Runtime not injected into this page yet; transitionTo would
                // no-op against a page without Vencord. Defer until the
                // runtimes are injected (see injectVencordIfReady).
                pendingDeepLink = url.toString()
            }
        } else {
            // Guarded so a page without Vencord (safe mode / failed load)
            // fails silently instead of throwing a ReferenceError.
            wv!!.evaluateJavascript(
                "if(window.Vencord&&Vencord.Webpack&&Vencord.Webpack.Common)" +
                    "{Vencord.Webpack.Common.NavigationRouter.transitionTo(${vdeGson.toJson(path)})}",
                null
            )
        }
    }

    /**
     * Answers a WebView capture permission request from [VChromeClient]. Only
     * audio maps to an Android runtime permission; everything else is denied.
     * Grants wait until RECORD_AUDIO is held, since the WebView rejects
     * getUserMedia if the app grants capture without it.
     *
     * Runs on the UI thread.
     */
    fun requestVoicePermissions(
        request: PermissionRequest,
        resources: Array<String>
    ) {
        // Grant only resources backed by an Android permission this app holds.
        // A page can bundle camera capture into the same request; granting the
        // full list would hand out permissions we never requested.
        val grantable = resources.filter {
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }.toTypedArray()
        val androidPerms = grantable.mapNotNull {
            when (it) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    Manifest.permission.RECORD_AUDIO
                else -> null
            }
        }.toTypedArray()
        if (androidPerms.isEmpty()) {
            VDELog.w("Voice", "Capture request with no grantable resource; denying")
            request.deny()
            return
        }
        val missing = androidPerms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isEmpty()) {
            VDELog.i("Voice", "Granting WebView capture (runtime permission already held)")
            request.grant(grantable)
            return
        }
        // Deny a stale request before parking the new one, or the page waits
        // forever.
        pendingPermissionRequest?.let { stale ->
            VDELog.w("Voice", "Superseding an unanswered WebView capture request")
            try {
                stale.deny()
            } catch (t: Throwable) {
                VDELog.w("Voice", "Superseded deny failed: $t")
            }
        }
        pendingPermissionRequest = request
        pendingPermissionResources = grantable
        VDELog.i("Voice", "Requesting Android runtime permission: ${missing.joinToString(",")}")
        requestPermissions(missing, VOICE_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != VOICE_PERMISSION_REQUEST) return
        val req = pendingPermissionRequest
        val res = pendingPermissionResources
        pendingPermissionRequest = null
        pendingPermissionResources = emptyArray()
        if (req == null) {
            VDELog.w("Voice", "Permission result with no pending capture request")
            return
        }
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            VDELog.i("Voice", "Android microphone permission granted; granting WebView capture")
            try {
                req.grant(res)
            } catch (t: Throwable) {
                VDELog.e("Voice", "Granting WebView capture failed", t)
            }
        } else {
            VDELog.w("Voice", "Android microphone permission denied; denying WebView capture")
            try {
                req.deny()
            } catch (t: Throwable) {
                VDELog.e("Voice", "Denying WebView capture failed", t)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { handleUrl(it) }
        }
    }

    override fun onPause() {
        val url = wv?.url
        if (url != null) {
            currentUrlForBridge = url
            currentHostForBridge = Uri.parse(url).host
            val host = currentHostForBridge
            // Persist only resumable app-shell routes (the
            // MainFrameDiskCache.isResumableRoute predicate, shared with the
            // disk-cache gate) and only while remember-last-channel is on; a
            // saved /blog page would reload with empty back history.
            val prefs = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)
            // getBooleanSafe: a String-typed key left by an older build would
            // crash onPause. Falling back to off skips the persist.
            if (host != null && MainFrameDiskCache.isResumableRoute(url) &&
                prefs.getBooleanSafe(SettingKeys.KEY_VENDROID_REMEMBER_LAST_CHANNEL, false) {
                    VDELog.w("Main", "vendroid_rememberLastChannel type-poisoned; using default: $it")
                }) {
                prefs.edit() { putString(SettingKeys.KEY_LAST_URL, url) }
            }
        }
        // Spoof document.hidden and pause CSS animations so the React app
        // throttles and the compositor stops wasted GPU work while
        // backgrounded. Run before onPause(): a paused renderer defers
        // pending evaluateJavascript, so the spoof would land late or wait
        // for resume.
        wv?.evaluateJavascript(
            "if(window.__vendroidSetVisibility)window.__vendroidSetVisibility('hidden');" +
            "if(window.__vendroidPauseAnimations)window.__vendroidPauseAnimations()",
            null
        )
        wv?.onPause()
        wv?.pauseTimers()
        // Stop the loading animation loop while backgrounded.
        if (::loadingScreenManager.isInitialized) loadingScreenManager.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Restore visibility spoofing before resuming timers/rendering so
        // Discord sees the page as foregrounded immediately.
        wv?.evaluateJavascript(
            "if(window.__vendroidSetVisibility)window.__vendroidSetVisibility('visible');" +
            "if(window.__vendroidResumeAnimations)window.__vendroidResumeAnimations()",
            null
        )
        wv?.onResume()
        wv?.resumeTimers()
        // Resume the loading animation loop if still showing.
        if (::loadingScreenManager.isInitialized) loadingScreenManager.resume()
    }

    override fun onDestroy() {
        // Deny a request that never got a runtime-permission answer; the
        // WebView may already be destroyed.
        pendingPermissionRequest?.let {
            try {
                it.deny()
            } catch (t: Throwable) {
                VDELog.w("Voice", "Could not deny pending capture request on destroy: $t")
            }
        }
        pendingPermissionRequest = null
        pendingPermissionResources = emptyArray()
        // Dismiss tracked dialogs before anything else. After onDestroy the
        // framework's window cleanup logs WindowLeaked and removes the views
        // without running dismiss listeners. SecureWebViewDialog destroys its
        // WebView only in its dismiss listener, so this is the last point where
        // the editor WebViews are still destroyable.
        dismissManagedDialogs()
        // loadingScreenManager is set only once startup passes the first-run
        // risk warning. If the user declines, or the activity is destroyed
        // while the warning is shown, it is never set and must not be touched.
        if (::loadingScreenManager.isInitialized) loadingScreenManager.cleanup()
        wv?.onPause()
        wv?.pauseTimers()
        wv?.stopLoading()
        wvInitialized = false
        (wv?.parent as? ViewGroup)?.removeView(wv)
        // wv is nulled immediately (see the threading-model note on wv):
        // callbacks flushed after destroy() see null and no-op.
        wv?.destroy()
        wv = null
        if (::vencordNative.isInitialized) vencordNative.shutdown()
        fetchExecutor.shutdownNow()
        if (!prewarmUsed) {
            VendroidApp.destroyPrewarmedWebViewIfUnused()
        }
        super.onDestroy()
    }

    fun injectVencordIfReady() {
        // Safe mode: never inject. Also covers the fetchVencord caller, which
        // publishes a downloaded bundle before calling this.
        if (HttpClient.vencordDisabled) return
        val (runtime, mobileRuntime) = HttpClient.runtimeSnapshot()
        if (wv == null || runtime == null || mobileRuntime == null) return
        // Only inject on Discord pages; the runtimes are designed for Discord
        // and must not run on whitelisted non-Discord pages.
        val url = currentUrlForBridge ?: return
        if (!Constants.isDiscordAppOrigin(Uri.parse(url).host ?: "")) return
        injectVencordAttempt(runtime, mobileRuntime, 0)
    }

    /**
     * Injects whichever runtime parts the current document is missing. The
     * decision waits for parsing to finish (readyState past "loading"): before
     * that, an embedded <script> block may not have executed yet and typeof
     * checks would report a false "absent", causing a double injection. A
     * document already running Vencord is never re-evaluated; a bundle
     * downloaded mid-session applies on the next navigation instead.
     */
    private fun injectVencordAttempt(runtime: String, mobileRuntime: String, attempt: Int) {
        // Destroy-guard: after onDestroy, wv == null and the probe eval below
        // never runs. The probe result callback re-checks liveness itself.
        val w = wv ?: return
        val expectedHost = Uri.parse(currentUrlForBridge ?: return).host
            ?.let { vdeGson.toJson(it) } ?: return
        // The renderer must already sit on the expected Discord host. A
        // mismatch means a provisional document (mid-navigation commit)
        // with no guaranteed quota-managed storage yet; evaluating the
        // bundle in that state has broken boot before.
        w.evaluateJavascript(
            "(document.readyState==='loading'?'L'" +
            ":location.hostname!==$expectedHost?'H'" +
            ":(typeof Vencord!=='undefined'" +
                "?(typeof VencordMobile!=='undefined'?'B':'V')" +
                ":'N'))"
        ) { raw ->
            // Liveness guard for the whole callback. A destroyed WebView can
            // still deliver pending eval results; by then wv is null, so
            // wv !== w and we bail. Covers every WebView call below, including
            // w.reload(), which is not wrapped in try/catch: on a destroyed
            // WebView reload() throws NPE inside Chromium on many builds, not
            // IllegalStateException, so a catch would not contain it.
            if (wv !== w) return@evaluateJavascript
            when (raw?.trim('"')) {
                "L", "H" -> {
                    // Still parsing or wrong document; retry briefly, then
                    // give up and let the next navigation restart the flow.
                    // Safe after destroy: postDelayed on a detached view does
                    // not throw and the action never runs.
                    if (attempt < INJECT_POLL_MAX_ATTEMPTS) {
                        w.postDelayed({ injectVencordAttempt(runtime, mobileRuntime, attempt + 1) }, 100)
                    }
                }
                "B" -> routePendingDeepLink()
                "V" -> {
                    // Main runtime present but the mobile runtime missing (the
                    // embed's shared script tag aborted partway). Inject only
                    // the missing part.
                    try { w.evaluateJavascript(mobileRuntime + ";", null) }
                    // WebView torn down since the probe; the scheduled boot re-verify still runs.
                    catch (_: IllegalStateException) {}
                    // The page-finished probe may have already persisted a fail
                    // with the mobile runtime absent; re-verify after the repair.
                    scheduleBootVerify("mobile-repair")
                    routePendingDeepLink()
                }
                else -> {
                    if (missedInjection) {
                        missedInjection = false
                        VDELog.w("Main", "Missed injection, scheduling reload")
                        w.reload()
                        return@evaluateJavascript
                    }
                    VDELog.i("Main", "Injecting Vencord runtime (${runtime.length} chars, mobile=${mobileRuntime.length} chars)")
                    RuntimeInjector.injectViaBridge(w, runtime, mobileRuntime)
                    // Verify the runtimes actually booted (separate eval so it
                    // runs even if the bundle eval died mid-script).
                    scheduleBootVerify("eval-inject")
                    routePendingDeepLink()
                }
            }
        }
    }

    /** Routes a deep link that arrived before the runtime was ready. */
    private fun routePendingDeepLink() {
        pendingDeepLink?.let { link ->
            pendingDeepLink = null
            handleUrl(Uri.parse(link))
        }
    }

    /**
     * Schedules a boot-verify probe after the runtimes have had time to boot.
     * Called from injection paths and [VWebviewClient.onPageFinished].
     */
    fun scheduleBootVerify(source: String, delayMs: Long = 2000) {
        val w = wv ?: return
        w.postDelayed({ bootVerify(source) }, delayMs)
    }

    private fun bootVerify(source: String) {
        // Destroy-guard (see the threading-model note on wv): return before
        // the unguarded evaluateJavascript below. isFinishing/isDestroyed
        // skips the probe once teardown has begun.
        val w = wv ?: return
        if (isFinishing || isDestroyed) return
        val url = currentUrlForBridge ?: return
        val host = Uri.parse(url).host ?: return
        if (!Constants.isDiscordAppOrigin(host)) return
        // Probing in safe mode would report a healthy boot as failed and
        // overwrite the crash state that brought the user to recovery.
        // Record that safe mode ran instead.
        if (HttpClient.vencordDisabled) {
            persistSafeModeBootState()
            return
        }
        BootVerify.runProbe(w, source) { ok, verdict ->
            persistBootState { build -> (if (ok) "ok" else "fail") + " $build | " + verdict.take(140) }
        }
    }

    /** Persists a short human-readable boot summary for the recovery screen.
     *  [formatState] renders the summary from the current bundle build tag.
     *  Best-effort: any failure is swallowed. */
    private fun persistBootState(formatState: (build: String) -> String) {
        try {
            val sPrefs = getSharedPreferences(SettingKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val build = sPrefs.getString(HttpClient.PREF_BUNDLE_BUILD, null) ?: "unknown-build"
            val state = formatState(build)
            if (sPrefs.getString(PREF_LAST_BOOT_STATE, null) == state) return
            sPrefs.edit().putString(PREF_LAST_BOOT_STATE, state).apply()
        } catch (_: Exception) {
            // A failed pref write must not disturb boot verification.
        }
    }

    /** Persists the safe-mode marker for the recovery screen. */
    private fun persistSafeModeBootState() {
        persistBootState { build -> "safe-mode (Vencord disabled) $build" }
    }

    fun showDiscordToast(message: String, type: String) {
        // message is JSON-encoded via vdeGson.toJson before interpolation, but type
        // is concatenated raw. Keep the allowList strict; widening it would
        // allow JS injection via the unencoded type.
        val allowedTypes = setOf("SUCCESS", "ERROR", "INFO", "WARN")
        val safeType = if (type in allowedTypes) type else "INFO"
        wv?.post(Runnable {
            wv?.evaluateJavascript(
                "toasts=Vencord.Webpack.Common.Toasts; toasts.show({id: toasts.genId(), message: ${vdeGson.toJson(message)}, type: toasts.Type.$safeType, options: {position: toasts.Position.BOTTOM,}})",
                null
            )
        })
    }

    companion object {
        /** SharedPreferences key for the last boot state summary (recovery screen). */
        const val PREF_LAST_BOOT_STATE = "lastBootState"

        /**
         * Pure decision core of [migrateSettings]; internal so the contract
         * can be pinned by a JVM unit test without Android (same pattern as
         * [runSafetyNetLoad]).
         *
         * [all] is the getAll() snapshot of the "settings" file; a null value
         * counts as an absent key. The legacy keys are not type-trusted: an
         * older build could persist them as Strings, and a plain getBoolean
         * on those threw ClassCastException, which crash-looped cold start.
         * Reading through [coerceLegacyBoolean] cannot throw.
         *
         * Coercion contract: a Boolean passes through, exact "true"/"false"
         * Strings coerce, anything else present falls back to the default and
         * is reported in [SettingsMigrationPlan.uncoercible]. A fallback
         * loses the user's setting for good, since migrateSettings removes
         * the legacy keys right after; callers must log uncoercible keys.
         */
        internal fun computeSettingsMigration(all: Map<String, Any?>): SettingsMigrationPlan {
            val checkUpdates = coerceLegacyBoolean(all[SettingKeys.KEY_CHECK_VENDROID_UPDATES])
            val equicord = coerceLegacyBoolean(all[SettingKeys.KEY_EQUICORD])
            val clientMod = when {
                all[SettingKeys.KEY_CLIENT_MOD] != null -> null // already set; never clobber
                equicord == true -> "equicord"
                else -> "vencord"
            }
            val uncoercible = listOf(SettingKeys.KEY_CHECK_VENDROID_UPDATES, SettingKeys.KEY_EQUICORD).filter { key ->
                all[key] != null && coerceLegacyBoolean(all[key]) == null
            }
            return SettingsMigrationPlan(checkUpdates ?: true, clientMod, uncoercible)
        }

        /**
         * Best-effort read of a legacy key whose stored type is not trusted.
         * Returns null when the value is absent or unrecoverable; the caller
         * supplies the default.
         */
        internal fun coerceLegacyBoolean(raw: Any?): Boolean? = when (raw) {
            is Boolean -> raw
            is String -> when (raw) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }

        /** Bound on the parse-completion retries in injectVencordAttempt (100ms apart). */
        private const val INJECT_POLL_MAX_ATTEMPTS = 20

        /** requestCode for the WebView capture runtime-permission flow. */
        private const val VOICE_PERMISSION_REQUEST = 0x564F

        /** Delay before the deferred bundle freshness check fires (see
         *  [scheduleDeferredBundleCheck]). Tunable; should sit past the boot
         *  window (main frame + Vencord boot) yet still land on the user's
         *  first session. */
        private const val BUNDLE_CHECK_DEFER_MS = 10_000L

        /**
         * Delay for [scheduleDeferredBundleCheck]; see that KDoc for the
         * rationale. [deferMs] when this boot can paint with a runtime
         * without the network (one already in memory, or a loadable on-disk
         * bundle the disk preload is about to publish); 0 otherwise, so the
         * fetch starts with boot.
         */
        internal fun bundleCheckDelayMs(
            runtimeInMemory: Boolean,
            needsRedownload: Boolean,
            bundleFileExists: Boolean,
            deferMs: Long = BUNDLE_CHECK_DEFER_MS
        ): Long =
            if (runtimeInMemory || (!needsRedownload && bundleFileExists)) deferMs else 0L

        /**
         * Body of [loadVencordRuntimesFromDisk]. Companion-scoped and internal
         * so unit tests can drive it synchronously, and so the queued lambda
         * captures no activity.
         *
         * Guards are re-evaluated at execution time: the queue wait can span
         * a safe-mode re-entry or a bundle-cache invalidation. Publishes go
         * through the compare-and-set helpers
         * [HttpClient.setVencordMobileRuntimeIfNull] and
         * [HttpClient.setVencordRuntimeIfNull] because the preload thread may
         * publish the same content while this task waits; an unconditional set
         * would clobber it, and make tests that stub the runtimes flaky.
         */
        internal fun runSafetyNetLoad(
            sPrefs: SharedPreferences,
            res: Resources,
            dir: File,
            weakSelf: WeakReference<MainActivity>
        ) {
            // Safe mode may have been raised since enqueue; a session that can
            // never publish should not pay for the reads.
            if (HttpClient.vencordDisabled) return
            var published = false
            try {
                // 1. Mobile runtime (65 KB raw resource).
                if (HttpClient.VencordMobileRuntime == null) {
                    val mobile = res.openRawResource(R.raw.vencord_mobile).use {
                        HttpClient.readAsText(it)
                    }
                    if (HttpClient.setVencordMobileRuntimeIfNull(mobile)) {
                        published = true
                    }
                }
                // 2. Main runtime (~1 MB from disk).
                if (!HttpClient.vencordDisabled && HttpClient.VencordRuntime == null) {
                    val vendroidFile = File(dir, "vencord.js")
                    // Skip the cached file while a redownload is pending so the
                    // stale bundle is never published; fetchVencord installs a
                    // fresh one or loads this file from its own offline
                    // fallback. Freshness bookkeeping lives in HttpClient alone.
                    if (!HttpClient.needsBundleRedownload(sPrefs) && vendroidFile.exists()) {
                        try {
                            // readBundleFromDisk patches when the persisted flag
                            // is stale; the result is ready to publish.
                            val fileContent = HttpClient.readBundleFromDisk(sPrefs, vendroidFile)
                            // stillValid re-checks the guards at publish time;
                            // the read can stall across a clientMod switch,
                            // which deletes the file and forces a redownload.
                            if (HttpClient.setVencordRuntimeIfNull(fileContent) {
                                    !HttpClient.needsBundleRedownload(sPrefs) && vendroidFile.exists()
                                }
                            ) {
                                published = true
                            }
                        } catch (e: Exception) {
                            VDELog.e("Main", "Failed to read vendroidFile", e)
                        }
                    }
                }
            } catch (e: Exception) {
                // Shared executor: an uncaught throw here would kill the
                // worker and the process with it, losing the rest of this
                // task's reads; the CAS publishes tolerate a partial run, and
                // the log keeps the gap diagnosable.
                VDELog.e("Main", "Vencord runtime safety-net load failed", e)
            }
            if (!published) return
            // Reads are async now, so the first page can finish before this
            // publish lands: onPageStarted then flags missedInjection and
            // nothing re-checks until fetchVencord's network path completes.
            // injectVencordIfReady is idempotent; it injects into the live
            // document, or reloads once via the missedInjection branch when
            // the page booted without the runtimes.
            val act = weakSelf.get()
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                act.runOnUiThread { act.injectVencordIfReady() }
            }
        }
    }
}

/** See [MainActivity.computeSettingsMigration] for the contract. */
internal data class SettingsMigrationPlan(
    /** Value for checkVDEUpdates; checkAnnouncements mirrors it. */
    val checkVDEUpdates: Boolean,
    /** null leaves an existing clientMod untouched. */
    val clientMod: String?,
    /** Legacy keys stored under an unrecoverable type. */
    val uncoercible: List<String>
)
