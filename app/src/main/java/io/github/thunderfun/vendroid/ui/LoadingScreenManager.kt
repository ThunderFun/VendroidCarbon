package io.github.thunderfun.vendroid.ui

import android.app.ActivityManager
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import androidx.appcompat.app.AppCompatActivity
import io.github.thunderfun.vendroid.R
import java.util.Random

/**
 * Manages the loading screen: dot pulse, aurora glow backdrop, timeout, and
 * dismiss scheduling. Extracted from MainActivity to keep lifecycle
 * orchestration separate from UI chrome. Lives in the `ui` package as a pure
 * UI/loading controller.
 *
 * Perf contract for the glow: each blob rasterizes once into a hardware
 * layer, and the loop only writes translation/scale, so per-frame cost is
 * pure GPU composition. Animating gradient colors or radius would force
 * re-rasterization every frame, so the SplashPalette colors are written
 * once in start(), before the layers exist.
 */
class LoadingScreenManager(
    private val activity: AppCompatActivity,
    private val loadingScreenLayout: ViewGroup,
    /**
     * Vendroid settings tint (vendroid_barColor, opaque ARGB). The stage is
     * painted by MainActivity's applySplashStageColor; within the manager
     * this is the ornament fallback when [orbColor] is null. Applied once
     * in start(); see SplashPalette. Live retints after start() are not
     * applied; unreachable in practice, since the only setBarColor writer,
     * the settings tab, appears after the splash dismisses.
     */
    private val tint: Int? = null,
    /**
     * Vendroid settings glow color (vendroid_orbColor, opaque ARGB), or
     * null to let the ornaments (dots and blobs) follow [tint] per
     * SplashPalette. Applied once in start(); live retints are not
     * applied, same reasoning as [tint].
     */
    private val orbColor: Int? = null
) {
    /** Static config for one glow blob. Periods are spread across 7-15s so
     *  the combined drift path never visibly repeats during a load. */
    private data class BlobSpec(
        val periodX: Long,
        val periodY: Long,
        val periodS: Long,
        /** Structural phase keeping the blobs desynced from each other; the
         *  random per-launch offset lives in phaseOffsets. */
        val phase: Float,
        /** Rest position as a fraction of the smaller screen dimension,
         *  fanning blobs around center so dots and label keep contrast. */
        val baseXFrac: Float,
        val baseYFrac: Float
    )

    private var dismissed = false
    private var started = false
    private var animRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null
    private var dismissRunnable: Runnable? = null
    private var dismissAnimator: ViewPropertyAnimator? = null
    private var animStartTime: Long = 0
    private var pausedAt: Long = 0
    private var ampX: Float = 0f
    private var ampY: Float = 0f
    private var lowRam: Boolean = false
    private val baseX = FloatArray(BLOB_COUNT)
    private val baseY = FloatArray(BLOB_COUNT)
    /** Per-launch random phase per blob, drawn in start(). Blob position is
     *  a pure function of phase, so without this every cold boot opened on
     *  the same arrangement. */
    private val phaseOffsets = FloatArray(BLOB_COUNT)
    private val handler = Handler(Looper.getMainLooper())

    // Dots are resolved once at construction; they never change for the activity.
    private val dots = intArrayOf(R.id.dot1, R.id.dot2, R.id.dot3)
        .map { activity.findViewById<View>(it) }

    private val blobSpecs = listOf(
        BlobSpec(7000L, 11000L, 9000L, 0f, -0.20f, 0.16f),
        BlobSpec(9000L, 13000L, 9000L, 1.7f, 0.18f, 0.20f),
        BlobSpec(11000L, 15000L, 9000L, 3.4f, 0f, -0.30f)
    )
    private val blobViews = intArrayOf(R.id.glow_a, R.id.glow_b, R.id.glow_c)
        .map { activity.findViewById<View>(it) }

    fun start() {
        // A second call would post a competing animation loop.
        if (started || dismissed) return
        started = true
        animStartTime = System.currentTimeMillis()

        lowRam = activity.getSystemService(ActivityManager::class.java)
            ?.isLowRamDevice == true

        // Scale by the smaller screen dimension: rotation-invariant under
        // configChanges, and phones/tablets get a comparable look.
        // displayMetrics is valid pre-layout; measured sizes are still 0 here.
        val minDim = minOf(
            activity.resources.displayMetrics.widthPixels,
            activity.resources.displayMetrics.heightPixels
        ).toFloat()
        ampX = minDim * 0.16f
        ampY = minDim * 0.20f
        val rng = Random()
        blobSpecs.forEachIndexed { i, spec ->
            baseX[i] = spec.baseXFrac * minDim
            baseY[i] = spec.baseYFrac * minDim
            phaseOffsets[i] = rng.nextFloat() * (2.0 * Math.PI).toFloat()
        }

        // Ornament colors, written before the hardware layers below
        // rasterize: a later write would force a re-rasterization or never
        // show through the cached layer. The glow color drives the whole
        // ornament set when set, else the bar tint does; a null source
        // skips both blocks and leaves the XML drawables stock.
        val ornamentSource = orbColor ?: tint
        SplashPalette.dotColor(ornamentSource)?.let { dotColor ->
            dots.forEach { dot ->
                (dot.background as? GradientDrawable)?.let { d ->
                    // mutate() per view: the three dots share one
                    // ConstantState, and an unmutated write would leak into
                    // future inflations (same hazard as the dither below).
                    d.mutate()
                    d.setColor(dotColor)
                }
            }
        }
        SplashPalette.orbColors(ornamentSource)?.let { orb ->
            blobViews.forEachIndexed { i, view ->
                (view.background as? GradientDrawable)?.let { d ->
                    d.mutate()
                    // Keeps the radial type, radius, and oval shape; three
                    // stops map to the XML start/center/end positions.
                    d.setColors(intArrayOf(orb.start[i], orb.center[i], orb.end[i]))
                }
            }
        }

        // animator duration scale ("Remove animations") is deliberately
        // ignored. The dot loop always ignored it, and honoring it here left
        // those devices with a frozen splash that reads as broken; the splash
        // is short and decorative, so it always animates.
        blobViews.forEachIndexed { i, view ->
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            // Dither prevents banding in dark radial gradients on OLED.
            // mutate() first: drawable resources share a ConstantState, and
            // setDither would leak into future inflations.
            (view.background as? GradientDrawable)?.let { d ->
                d.mutate()
                d.setDither(true)
            }
            // Low-RAM devices keep a single blob and skip scale breathing.
            if (lowRam && i > 0) view.visibility = GONE
        }

        dots.forEach { dot ->
            dot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            dot.scaleX = 0.4f
            dot.scaleY = 0.4f
            dot.alpha = 0.3f
        }

        // Drive the animation with postDelayed at ~30fps because ValueAnimator
        // misses callbacks for very long-duration animations in this use case.
        val runnable = object : Runnable {
            override fun run() {
                if (dismissed) return
                val elapsed = System.currentTimeMillis() - animStartTime
                val cycleMs = 1500L
                dots.forEachIndexed { i, dot ->
                    // Stagger is cycleMs / 6 so the dots stay evenly phased.
                    val phase = (elapsed - i * cycleMs / 6).toDouble()
                    val t = (phase % cycleMs) / cycleMs
                    val wave = (Math.sin(t * 2.0 * Math.PI - Math.PI / 2.0) + 1.0) / 2.0
                    dot.scaleX = 0.4f + wave.toFloat() * 0.6f
                    dot.scaleY = 0.4f + wave.toFloat() * 0.6f
                    dot.alpha = 0.3f + wave.toFloat() * 0.7f
                }
                blobSpecs.forEachIndexed { i, spec ->
                    if (lowRam && i > 0) return@forEachIndexed
                    val view = blobViews[i]
                    val tau = 2.0 * Math.PI
                    val phase = spec.phase + phaseOffsets[i]
                    val tx = Math.sin(elapsed / spec.periodX.toDouble() * tau + phase).toFloat()
                    val ty = Math.cos(elapsed / spec.periodY.toDouble() * tau + phase).toFloat()
                    view.translationX = baseX[i] + tx * ampX
                    view.translationY = baseY[i] + ty * ampY
                    if (!lowRam) {
                        val s = 1f + 0.15f *
                            Math.sin(elapsed / spec.periodS.toDouble() * tau + spec.phase).toFloat()
                        view.scaleX = s
                        view.scaleY = s
                    }
                }
                animRunnable = this
                handler.postDelayed(this, 33)
            }
        }
        runnable.run()
    }

    /** Stops the animation loop to avoid background CPU churn. */
    fun pause() {
        // onPause can precede start() (risk-warning dialog): no loop or clock
        // exists yet, and a recorded pausedAt would corrupt animStartTime in
        // resume().
        if (dismissed || animRunnable == null) return
        pausedAt = System.currentTimeMillis()
        animRunnable?.let { handler.removeCallbacks(it) }
    }

    /** Resumes the animation loop after [pause]. */
    fun resume() {
        if (dismissed) return
        // Positions are absolute functions of elapsed time, so shift the
        // clock base by the paused duration instead of teleporting on resume.
        if (pausedAt > 0) {
            animStartTime += System.currentTimeMillis() - pausedAt
            pausedAt = 0
        }
        // Remove before posting so a resume without a prior pause (e.g. the
        // initial onResume) doesn't start a second concurrent loop.
        animRunnable?.let { handler.removeCallbacks(it); handler.post(it) }
    }

    fun scheduleTimeout(delayMs: Long) {
        timeoutRunnable = Runnable {
            if (!dismissed) dismiss()
        }
        handler.postDelayed(timeoutRunnable!!, delayMs)
    }

    fun scheduleDismiss(delayMs: Long) {
        if (dismissed) return
        val runnable = Runnable { dismiss() }
        dismissRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        animRunnable?.let { handler.removeCallbacks(it) }
        animRunnable = null
        // The container fade carries dots, blobs, and label; hardware layers
        // release when the view goes GONE.
        dismissAnimator = loadingScreenLayout.animate()?.alpha(0f)?.setDuration(500)?.withEndAction {
            loadingScreenLayout.visibility = GONE
            loadingScreenLayout.alpha = 1f
        }
        dismissAnimator?.start()
    }

    fun cleanup() {
        // A repeated schedule call overwrites the field holding its runnable's
        // only reference, so per-field removal leaks queued orphans past
        // onDestroy. The handler is private, so a blanket removal is safe.
        handler.removeCallbacksAndMessages(null)
        // Cancel any in-flight dismiss animation so its withEndAction doesn't
        // touch a detached/destroyed view hierarchy after onDestroy.
        dismissAnimator?.cancel()
        timeoutRunnable = null
        dismissRunnable = null
        animRunnable = null
        dismissAnimator = null
        pausedAt = 0
    }

    private companion object {
        const val BLOB_COUNT = 3
    }
}
