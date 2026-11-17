package io.github.thunderfun.vendroid.webview

import android.graphics.Color
import android.os.Build
import android.view.Window
import androidx.core.view.WindowCompat
import io.github.thunderfun.vendroid.ui.SystemBarBackdropView
import io.github.thunderfun.vendroid.utils.VDELog

/**
 * Read/write seam over the system-bar state [BarColorManager] manages.
 * Isolates Window/View access from the manager's decision logic.
 */
interface SystemBarTarget {
    /**
     * Runs [block] on the thread that owns the target (the app's UI thread).
     * Like Activity.runOnUiThread, this must execute [block] inline when
     * already on that thread, so publishes made from the UI thread take
     * effect before the caller proceeds.
     */
    fun post(block: () -> Unit)

    /**
     * Paints the status/navigation bar backdrops. Returns true when the
     * colors actually changed.
     */
    fun applyBarColors(statusColor: Int, navigationColor: Int): Boolean

    var isAppearanceLightStatusBars: Boolean
    var isAppearanceLightNavigationBars: Boolean
}

/**
 * [SystemBarTarget] backed by an Activity [Window] and the edge-to-edge
 * backdrop view. Window color setters are not used; the backdrop is the
 * single source of truth.
 */
class WindowSystemBarTarget(
    private val window: Window,
    private val backdrop: SystemBarBackdropView,
    private val postToUiThread: (() -> Unit) -> Unit,
) : SystemBarTarget {
    private var lastStatus = Int.MIN_VALUE
    private var lastNavigation = Int.MIN_VALUE

    override fun post(block: () -> Unit) = postToUiThread(block)

    @Suppress("DEPRECATION") // setters are legacy-only below API 29
    override fun applyBarColors(statusColor: Int, navigationColor: Int): Boolean {
        // The backdrop is opaque, so a system contrast scrim would only
        // alter the exact color the user picked. Disable it where the
        // platform exposes the switch. Navigation contrast exists since
        // API 29; status contrast since API 35.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        } else {
            // enableEdgeToEdge installs a contrast scrim on API 26-28. Clear
            // the legacy bar colors so the backdrop shows through instead.
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.isStatusBarContrastEnforced = false
        }
        if (statusColor == lastStatus && navigationColor == lastNavigation) return false
        lastStatus = statusColor
        lastNavigation = navigationColor
        backdrop.setBarColors(statusColor, navigationColor)
        return true
    }

    // Non-deprecated accessor (the WindowInsetsControllerCompat(window, view)
    // constructor is deprecated on recent androidx.core). Lazy: the first
    // reader runs after the decor view exists.
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
 * Sole writer of the activity's status and navigation bar appearance.
 *
 * Three features recolor the bars: the Vencord overlay and in-page viewer
 * (status+nav black while active), native fullscreen video (status black
 * while a custom view shows; the nav bar is hidden via insets, not recolored),
 * and the settings color picker (a user tint for both bars). Each feature
 * publishes its state through its own method and reads none of the others'.
 * Every publish re-derives the bar state from the three published values
 * ([reapply]), so any interleaving converges. Bars are black iff at least one
 * of overlay/video wants them black; otherwise the custom tint applies when
 * set; otherwise the theme color applies.
 *
 * The colors are painted into [SystemBarBackdropView], not written to
 * Window.statusBarColor/navigationBarColor. At targetSdk 36+, edge-to-edge is
 * enforced and those setters are no-ops; the inset strips work on every
 * supported version.
 *
 * Contract:
 *  - This class must be the only code changing this window's bars.
 *  - Publishers write their flag first and only then queue the reapply, so
 *    every queued reapply observes it. reapply reads each flag once; a
 *    concurrent publish queues a second reapply that converges.
 */
class BarColorManager(
    private val target: SystemBarTarget,
    private val themeStatusBarColor: Int,
    private val themeNavigationBarColor: Int,
) {
    /** In-page image/JS-video viewer active. Published from the JS bridge thread. */
    @Volatile
    private var overlayActive = false

    /** Fullscreen video custom view showing. Published from the UI thread. */
    @Volatile
    private var videoFullscreen = false

    /**
     * User tint from the settings picker; null = theme default. A state
     * source like the flags above, never a snapshot: reapply() derives the
     * bars from it on every publish.
     */
    @Volatile
    private var customColor: Int? = null

    /** In-page viewer state change. Safe from any thread. */
    fun publishOverlayActive(active: Boolean) {
        overlayActive = active
        target.post { reapply() }
    }

    /** Fullscreen-video state change. Called from WebChromeClient callbacks,
     *  so always on the UI thread. */
    fun publishVideoFullscreen(active: Boolean) {
        videoFullscreen = active
        target.post { reapply() }
    }

    /** Custom bar tint from the picker, or null to reset to the theme colors.
     *  Safe from any thread. */
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

            val statusBlack = overlay || video
            val navBlack = overlay
            val status = if (statusBlack) Color.BLACK else (custom ?: themeStatusBarColor)
            val nav = if (navBlack) Color.BLACK else (custom ?: themeNavigationBarColor)

            // The icon appearance is derived from the color actually painted,
            // never from a theme snapshot: dark bars always take light icons,
            // regardless of whether the color came from the theme, the picker,
            // or a feature override. A mid/light tint takes dark icons.
            val statusLight = relativeLuminance(status) > LUMINANCE_LIGHT_THRESHOLD
            val navLight = relativeLuminance(nav) > LUMINANCE_LIGHT_THRESHOLD

            val colorsChanged = target.applyBarColors(status, nav)
            var iconsChanged = false
            if (target.isAppearanceLightStatusBars != statusLight) {
                target.isAppearanceLightStatusBars = statusLight
                iconsChanged = true
            }
            if (target.isAppearanceLightNavigationBars != navLight) {
                target.isAppearanceLightNavigationBars = navLight
                iconsChanged = true
            }
            if (colorsChanged || iconsChanged) {
                VDELog.d(
                    TAG,
                    "applied status=${hex(status)} nav=${hex(nav)} " +
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

        /** Above this relative luminance a tint takes dark icons. */
        private const val LUMINANCE_LIGHT_THRESHOLD = 0.5
    }
}
