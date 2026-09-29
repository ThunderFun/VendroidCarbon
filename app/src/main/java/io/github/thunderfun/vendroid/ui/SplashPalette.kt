package io.github.thunderfun.vendroid.ui

/**
 * Derives the boot splash palette from the Vendroid settings colors
 * (`vendroid_barColor`, `vendroid_orbColor`, `vendroid_splashBgColor`).
 *
 * Rules:
 *  - The stage takes the splash-bg setting verbatim when set: the user
 *    picked an exact color, so bright picks trade label/dot contrast for
 *    fidelity. Otherwise it is the bar tint clamped into the shade-of-black
 *    band (see [V_CAP]), with hue and saturation kept.
 *  - The ornaments (the three dots and three glow blobs) take the
 *    ornament source's hue (the glow color when set, else the bar tint)
 *    and keep their stock drawable's S/V profile, the +30° violet offset
 *    included. Greyscale sources (hue undefined) produce neutral
 *    ornaments rather than an arbitrary hue.
 *
 * Every entry point returns null for a null source; callers skip the
 * corresponding mutation and the XML drawables keep the stock look. That
 * is the right default, since the theme default bar color is
 * @color/status_bar_color (identical in values/ and values-night/):
 * "no tint" and "theme default" are the same visual case.
 *
 * All math is hand-rolled (RGB↔HSV, sRGB relative luminance) so this stays
 * testable on the plain JVM: android.graphics.Color's conversions are native
 * and throw "not mocked" in unit tests. BarColorManager hand-rolls luminance
 * for the same reason.
 */
/** Radial gradient stop colors per blob, in glow_a/b/c order. */
data class OrbColors(
    val start: List<Int>,
    val center: List<Int>,
    val end: List<Int>
)

object SplashPalette {

    /**
     * Stage lightness cap as an HSV value: the background is never brighter
     * than this (≈ #333333 for neutrals, relative luminance ≈ 0.033). Chosen
     * so dark hued shades like #0d1a33 (V = 0.20) pass through while a mid
     * grey (#808080, V ≈ 0.50) is pulled back to a dark grey.
     */
    private const val V_CAP = 0.2f

    /** Sources below this saturation have no meaningful hue; ornaments go neutral. */
    private const val NEUTRAL_SATURATION = 0.15f

    // Ornament S/V profiles transplanted from the stock drawables so a
    // hue-swapped palette reproduces the stock look: blurple dot
    // (S .65 / V .95) and blurple / violet (+30°) / indigo blobs.
    private const val DOT_S = 0.65f
    private const val DOT_V = 0.95f
    private val ORB_HUE_OFFSET = floatArrayOf(0f, 30f, 0f)
    private val ORB_S = floatArrayOf(0.65f, 0.39f, 0.71f)
    private val ORB_V = floatArrayOf(0.95f, 0.93f, 0.72f)

    // Per-blob gradient stop alphas from glow_blurple/glow_violet/glow_indigo
    // (start, center); every stock blob fades to a transparent end stop.
    private val ORB_START_ALPHA = intArrayOf(0x66, 0x59, 0x73)
    private val ORB_CENTER_ALPHA = intArrayOf(0x26, 0x20, 0x2E)

    /**
     * Auto stage color for the bar [tint]: the tint clamped into the
     * shade-of-black band, or null to keep the XML/theme default.
     */
    fun stageColor(tint: Int?): Int? {
        if (tint == null) return null
        val hsv = rgbToHsv(tint)

        // Only the value channel is clamped, so the clamp can never
        // brighten, and re-deriving an already-clamped background is a
        // no-op (its V sits exactly at the cap).
        return if (hsv[2] <= V_CAP) tint else hsvToRgb(hsv[0], hsv[1], V_CAP)
    }

    /** Dot color for [source], or null to keep the XML default. */
    fun dotColor(source: Int?): Int? {
        if (source == null) return null
        val hsv = rgbToHsv(source)
        val hue: Float? = if (hsv[1] < NEUTRAL_SATURATION) null else hsv[0]
        return ornamentRgb(hue, DOT_S, DOT_V)
    }

    /** Per-blob gradient stops for [source], or null to keep the XML defaults. */
    fun orbColors(source: Int?): OrbColors? {
        if (source == null) return null
        val hsv = rgbToHsv(source)

        // Hue-swapped S/V profiles; the +30° violet offset wraps through
        // hsvToRgb's mod-360 normalization. Greyscale sources pass
        // hue = null, so ornamentRgb builds them with S = 0.
        val hue: Float? = if (hsv[1] < NEUTRAL_SATURATION) null else hsv[0]
        val orbRgb = IntArray(3) { i ->
            ornamentRgb(hue?.let { it + ORB_HUE_OFFSET[i] }, ORB_S[i], ORB_V[i])
        }
        return OrbColors(
            start = List(3) { (ORB_START_ALPHA[it] shl 24) or (orbRgb[it] and 0xFFFFFF) },
            center = List(3) { (ORB_CENTER_ALPHA[it] shl 24) or (orbRgb[it] and 0xFFFFFF) },
            end = List(3) { orbRgb[it] and 0x00FFFFFF }
        )
    }

    /** Neutral fallback collapses S to 0 when the source has no hue. */
    private fun ornamentRgb(hue: Float?, s: Float, v: Float): Int =
        hsvToRgb(hue ?: 0f, if (hue == null) 0f else s, v)

    /** sRGB relative luminance, the same formula androidx ColorUtils uses.
     *  Alpha is ignored; inputs here are opaque. */
    private fun relativeLuminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = (argb shr shift and 0xFF) / 255.0
            return if (c < 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** ARGB → {hue 0..360, saturation 0..1, value 0..1}; alpha ignored. */
    private fun rgbToHsv(argb: Int): FloatArray {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val d = max - minOf(r, g, b)
        var h = 0f
        if (d > 0f) {
            // max is exactly one of r/g/b (maxOf returns an input), so the
            // equality branches below are exact, not tolerance-dependent.
            h = when (max) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
            if (h < 0f) h += 360f
        }
        val s = if (max <= 0f) 0f else d / max
        return floatArrayOf(h, s, max)
    }

    /** HSV → opaque ARGB; hue wraps mod 360 so +30° offsets cannot escape. */
    private fun hsvToRgb(h: Float, s: Float, v: Float): Int {
        val hn = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - kotlin.math.abs((hn / 60f) % 2f - 1f))
        val m = v - c
        // Standard HSV sectors: hue 0 is red, 120 green, 240 blue.
        val (r, g, b) = when {
            hn < 60f -> Triple(c, x, 0f)
            hn < 120f -> Triple(x, c, 0f)
            hn < 180f -> Triple(0f, c, x)
            hn < 240f -> Triple(0f, x, c)
            hn < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun byte(f: Float): Int = Math.round((f + m) * 255f)
        return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }
}
