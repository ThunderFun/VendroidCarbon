package io.github.thunderfun.vendroid.webview

import android.graphics.Color
import android.os.Build
import android.view.Window
import androidx.core.view.WindowCompat
import io.github.thunderfun.vendroid.utils.VDELog

/**
 * Read/write seam over the window system-bar state [BarColorManager] manages.
 * Isolates every android.view.Window access from the manager's decision logic.
 */
interface SystemBarTarget {
    /**
     * Runs [block] on the thread that owns the target (the app's UI thread).
     * Like Activity.runOnUiThread, this must execute [block] inline when
     * already on that thread, so publishes made from the UI thread take
     * effect before the caller proceeds.
     */
    fun post(block: () -> Unit)

    var statusBarColor: Int
    var navigationBarColor: Int

    /**
     * Whether isStatusBarContrastEnforced / isNavigationBarContrastEnforced
     * are available (API 35+). When false, the manager never reads or writes
     * them.
     */
    val contrastEnforcementSupported: Boolean

    var isStatusBarContrastEnforced: Boolean
    var isNavigationBarContrastEnforced: Boolean

    /**
     * Light-system-icon appearance for each bar. Via the compat wrapper this
     * works on every supported API (legacy view flags pre-30), so it is NOT
     * gated behind [contrastEnforcementSupported].
     */
    var isAppearanceLightStatusBars: Boolean
    var isAppearanceLightNavigationBars: Boolean
}

/**
 * [SystemBarTarget] backed by an Activity [Window]. [postToUiThread] should be
 * Activity.runOnUiThread (inline on the UI thread, posted otherwise).
 */
@Suppress("DEPRECATION") // bar color/contrast setters are deprecated on recent
// APIs but remain live here. The app theme opts out of edge-to-edge
// enforcement (windowOptOutEdgeToEdgeEnforcement=true), so the deprecated
// setters are the only way to color the bars on all supported devices.
class WindowSystemBarTarget(
    private val window: Window,
    private val postToUiThread: (() -> Unit) -> Unit,
) : SystemBarTarget {
    override fun post(block: () -> Unit) = postToUiThread(block)

    override var statusBarColor: Int
        get() = window.statusBarColor
        set(value) { window.statusBarColor = value }

    override var navigationBarColor: Int
        get() = window.navigationBarColor
        set(value) { window.navigationBarColor = value }

    override val contrastEnforcementSupported: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    override var isStatusBarContrastEnforced: Boolean
        get() = if (contrastEnforcementSupported) window.isStatusBarContrastEnforced else true
        set(value) { if (contrastEnforcementSupported) window.isStatusBarContrastEnforced = value }

    override var isNavigationBarContrastEnforced: Boolean
        get() = if (contrastEnforcementSupported) window.isNavigationBarContrastEnforced else true
        set(value) { if (contrastEnforcementSupported) window.isNavigationBarContrastEnforced = value }

    // Non-deprecated accessor (the WindowInsetsControllerCompat(window, view)
    // constructor is deprecated on recent androidx.core). Lazy: the first
    // reader is captureOriginals(), which only runs after the decor view
    // exists.
    private val insetsController by lazy {
        WindowCompat.getInsetsController(window, window.decorView)
    }

    override var isAppearanceLightStatusBars: Boolean
        get() = insetsController.isAppearanceLightStatusBars
        set(value) { insetsController.isAppearanceLightStatusBars = value }

    override var isAppearanceLightNavigationBars: Boolean
        get() = insetsController.isAppearanceLightNavigationBars
        set(value) { insetsController.isAppearanceLightNavigationBars = value }
}

/**
 * Sole writer of an activity window's status and navigation bar colors.
 *
 * Three features recolor the bars: the Vencord overlay (status+nav black while
 * active), fullscreen video (status black while a custom view shows; the nav
 * bar is hidden via insets, not recolored), and the Vendroid settings bar
 * color picker (a user tint for both bars). Each feature publishes its state
 * through its own publish method and reads none of the others'. Every publish
 * re-derives the bar state from the three published values ([reapply]), so
 * any interleaving converges. Bars are black iff at least one of
 * overlay/video wants them black; otherwise the custom tint applies when
 * set; otherwise the captured original applies.
 *
 * This replaces two independent per-feature "original color" caches that
 * clobbered each other (a video started under the overlay snapshotted the
 * overlay's black as "original" and restored it on exit; an overlay activated
 * during fullscreen was dropped entirely, leaving the wrong color latched).
 *
 * Contract:
 *  - This class must be the only code writing this window's bar colors.
 *    Route any future bar-color change through a publish + reapply here.
 *  - Do not reintroduce per-feature color snapshots. Originals are captured
 *    exactly once, before the first write ([captureOriginals]).
 *  - Publishers write their flag first and only then queue the reapply, so
 *    every queued reapply observes it. reapply reads each flag once; a
 *    concurrent publish queues a second reapply that converges.
 */
class BarColorManager(private val target: SystemBarTarget) {
    /** Overlay (Vencord title bar) active. Published from the JS bridge thread. */
    @Volatile
    private var overlayActive = false

    /** Fullscreen video custom view showing. Published from the UI thread. */
    @Volatile
    private var videoFullscreen = false

    /**
     * User tint from the Vendroid settings picker; null = theme default.
     * A third state source like the two flags above, never a snapshot:
     * reapply() derives the bars from it on every publish.
     */
    @Volatile
    private var customColor: Int? = null

    // Original (theme) bar state. Captured once before the first write (see
    // the class contract), then valid for the window's lifetime.
    private var captured = false
    private var originalStatusBarColor = Color.BLACK
    private var originalNavigationBarColor = Color.BLACK
    private var originalStatusBarContrastEnforced = true
    private var originalNavigationBarContrastEnforced = true
    private var originalLightStatusIcons = false
    private var originalLightNavIcons = false

    /**
     * Overlay state change. Safe from any thread.
     */
    fun publishOverlayActive(active: Boolean) {
        overlayActive = active
        target.post { reapply() }
    }

    /**
     * Fullscreen-video state change. Called from WebChromeClient callbacks,
     * so always on the UI thread.
     */
    fun publishVideoFullscreen(active: Boolean) {
        videoFullscreen = active
        target.post { reapply() }
    }

    /**
     * Custom bar tint from the picker, or null to reset to the theme colors.
     * Safe from any thread.
     */
    fun publishCustomColor(color: Int?) {
        customColor = color
        target.post { reapply() }
    }

    private fun reapply() {
        try {
            // One read per flag; see the class contract.
            val overlay = overlayActive
            val video = videoFullscreen
            val custom = customColor

            if (!captured) captureOriginals()

            val statusBlack = overlay || video
            val navBlack = overlay
            val status = if (statusBlack) Color.BLACK else (custom ?: originalStatusBarColor)
            val nav = if (navBlack) Color.BLACK else (custom ?: originalNavigationBarColor)

            // Icon appearance is derived, never snapshotted per feature: black
            // bars always take light icons (otherwise a light custom tint
            // would survive into the overlay as invisible dark icons); a
            // custom tint picks by luminance; the theme's captured values
            // apply only while the theme colors show.
            val statusLight = when {
                statusBlack -> false
                custom != null -> relativeLuminance(custom) > LUMINANCE_LIGHT_THRESHOLD
                else -> originalLightStatusIcons
            }
            val navLight = when {
                navBlack -> false
                custom != null -> relativeLuminance(custom) > LUMINANCE_LIGHT_THRESHOLD
                else -> originalLightNavIcons
            }

            // Contrast enforcement would scrim the user's exact color, so it
            // stays off for every manager-written color; only the theme pair
            // keeps its captured value.
            val statusContrast =
                if (statusBlack || custom != null) false else originalStatusBarContrastEnforced
            val navContrast =
                if (navBlack || custom != null) false else originalNavigationBarContrastEnforced

            var changed = false
            if (target.statusBarColor != status) {
                target.statusBarColor = status
                changed = true
            }
            if (target.navigationBarColor != nav) {
                target.navigationBarColor = nav
                changed = true
            }
            if (target.contrastEnforcementSupported) {
                if (target.isStatusBarContrastEnforced != statusContrast) {
                    target.isStatusBarContrastEnforced = statusContrast
                    changed = true
                }
                if (target.isNavigationBarContrastEnforced != navContrast) {
                    target.isNavigationBarContrastEnforced = navContrast
                    changed = true
                }
            }
            if (target.isAppearanceLightStatusBars != statusLight) {
                target.isAppearanceLightStatusBars = statusLight
                changed = true
            }
            if (target.isAppearanceLightNavigationBars != navLight) {
                target.isAppearanceLightNavigationBars = navLight
                changed = true
            }
            if (changed) {
                VDELog.d(
                    TAG,
                    "applied status=${hex(status)} nav=${hex(nav)} " +
                        "contrast=$statusContrast/$navContrast " +
                        "lightIcons=$statusLight/$navLight " +
                        "(overlay=$overlay video=$video custom=${custom != null})"
                )
            }
        } catch (t: Throwable) {
            // A publish can land during activity teardown, where window
            // attribute writes throw (detached decor view). Never propagate.
            VDELog.e(TAG, "reapply failed", t)
        }
    }

    private fun captureOriginals() {
        // Read into locals first. If a read throws (dying window), `captured`
        // stays false and the next reapply retries with fresh values.
        val status = target.statusBarColor
        val nav = target.navigationBarColor
        val statusContrast =
            if (target.contrastEnforcementSupported) target.isStatusBarContrastEnforced else true
        val navContrast =
            if (target.contrastEnforcementSupported) target.isNavigationBarContrastEnforced else true
        val statusLight = target.isAppearanceLightStatusBars
        val navLight = target.isAppearanceLightNavigationBars

        originalStatusBarColor = status
        originalNavigationBarColor = nav
        originalStatusBarContrastEnforced = statusContrast
        originalNavigationBarContrastEnforced = navContrast
        originalLightStatusIcons = statusLight
        originalLightNavIcons = navLight
        captured = true
        // Debug visibility for the theme's appearance values: if a theme
        // ships dark icons on its dark bar, this line is how it gets caught
        // in QA (the Material3 parents never set windowLightStatusBar
        // explicitly, so the resolved default is worth watching).
        VDELog.d(
            TAG,
            "captured original status=${hex(status)} nav=${hex(nav)} " +
                "contrast=$statusContrast/$navContrast lightIcons=$statusLight/$navLight"
        )
    }

    private fun hex(color: Int): String = "0x" + Integer.toHexString(color).uppercase()

    /**
     * sRGB relative luminance, the same formula androidx ColorUtils uses,
     * hand-rolled so reapply() never touches android.graphics (those calls
     * throw "not mocked" under JVM unit tests and would abort the whole
     * derivation). Alpha is ignored; colors reaching here are opaque.
     */
    private fun relativeLuminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = (argb shr shift and 0xFF) / 255.0
            return if (c < 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    companion object {
        private const val TAG = "BarColors"

        /** Above this relative luminance a custom tint takes dark icons. */
        private const val LUMINANCE_LIGHT_THRESHOLD = 0.5
    }
}
