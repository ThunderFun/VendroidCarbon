package io.github.thunderfun.vendroid.webview

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.widget.Toast
import io.github.thunderfun.vendroid.MainActivity
import io.github.thunderfun.vendroid.R
import io.github.thunderfun.vendroid.utils.VDELog
import java.io.ByteArrayOutputStream

internal object IconAliasManager {
    // Launcher activity-alias names (manifest: <activity-alias
    // android:name=".${name}MainActivity">). Declared as a List because
    // reconcileIconState() uses the order as tie-breaker when several
    // aliases are enabled.
    internal val ICON_NAMES = listOf("Main", "Basic", "Jolly", "Retro", "Discord", "TS12", "Glass", "Charcoal", "OLED")
    // Launcher icon currently believed active. PackageManager component
    // state is the source of truth; this is only a cache. changeAppIcon()
    // commits it only after the enable call succeeds, never speculatively.
    // Null means not yet resolved for this process. All access happens
    // under iconLock.
    @Volatile
    internal var currentIcon: String? = null
    internal val iconLock = Any()

    // Alias class names in the manifest expand against the AGP namespace
    // ("io.github.thunderfun.vendroid"), not the runtime applicationId. Debug and
    // dev builds append an applicationIdSuffix, so their manifest package
    // is "io.github.thunderfun.vendroid.debug" while alias classes stay
    // "io.github.thunderfun.vendroid.${name}MainActivity" (verified in the merged
    // debug manifest). Deriving the class from pkg.packageName would name
    // components that do not exist and make every PM write throw
    // IllegalArgumentException. Must match `namespace` in build.gradle.
    private const val ICON_COMPONENT_CLASS_PREFIX = "io.github.thunderfun.vendroid."

    // The Context supplies the owning package (the applicationId) for the
    // ComponentName; the class part is the namespace-prefixed alias name.
    internal fun iconComponent(pkg: Context, name: String): ComponentName =
        ComponentName(pkg, "$ICON_COMPONENT_CLASS_PREFIX${name}MainActivity")

    // getComponentEnabledSetting documents no exceptions, but a component
    // missing from this build (flavor or manifest drift) must not break
    // reconciliation. A missing component reads as the manifest default.
    private fun iconComponentState(pm: PackageManager, pkg: Context, name: String): Int =
        try {
            pm.getComponentEnabledSetting(iconComponent(pkg, name))
        } catch (t: Throwable) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }

    private fun iconFromComponent(cn: ComponentName?): String? {
        val cls = cn?.className ?: return null
        if (!cls.startsWith(ICON_COMPONENT_CLASS_PREFIX)) return null
        val short = cls.removePrefix(ICON_COMPONENT_CLASS_PREFIX)
        return ICON_NAMES.find { short == "${it}MainActivity" }
    }

    /**
     * Reconcile the [currentIcon] cache with PackageManager state and
     * repair corruption in either direction:
     *  - the cache names an alias PM does not have enabled (a failed
     *    switch leaves this behind; it made "already active" lie and
     *    blocked retries), or
     *  - several aliases are enabled (a half-applied switch leaves this
     *    behind; it shows up as duplicate launcher entries).
     *
     * Resolution when several aliases are enabled: the cached icon (last
     * known intent) wins, else the alias this activity was launched from
     * (the entry the user actually tapped), else ICON_NAMES order. Every
     * other enabled alias is disabled, not just dropped from the cache.
     */
    internal fun reconcileIconState(act: MainActivity) {
        val pm = act.packageManager
        val pkg = act.applicationContext
        val states = ICON_NAMES.associateWith { iconComponentState(pm, pkg, it) }
        val enabled = ICON_NAMES.filter {
            states[it] == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        val cached = currentIcon
        val resolved = when {
            cached != null && enabled.contains(cached) -> cached
            enabled.size == 1 -> enabled[0]
            enabled.size > 1 ->
                iconFromComponent(act.intent?.component)?.takeIf { enabled.contains(it) }
                    ?: enabled.first()
            // No alias is explicitly enabled. Main normally sits at its
            // manifest default (android:enabled="true") and that is what
            // the launcher shows. If Main was also explicitly disabled,
            // the launcher has no entry at all and the switcher would
            // report "already active" forever, so restore the default.
            else -> {
                if (states["Main"] == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    try {
                        pm.setComponentEnabledSetting(
                            iconComponent(pkg, "Main"),
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        VDELog.w("VN", "Icon repair: launcher had no enabled entry; re-enabled Main")
                    } catch (t: Throwable) {
                        VDELog.e("VN", "Icon repair: could not re-enable Main", t)
                    }
                }
                "Main"
            }
        }
        if (resolved != cached) {
            // Assign before logging so a throwing logger cannot leave the
            // cache stale.
            currentIcon = resolved
            if (cached == null) {
                // First resolution this process, not a desync.
                VDELog.d("VN", "Icon cache resolved to '$resolved'")
            } else {
                VDELog.w("VN", "Icon cache desync (cache=$cached, pm=$resolved), repaired")
            }
        }
        for (name in enabled) {
            if (name == resolved) continue
            try {
                pm.setComponentEnabledSetting(
                    iconComponent(pkg, name),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                VDELog.w("VN", "Icon repair: disabled stale enabled launcher alias '$name'")
            } catch (t: Throwable) {
                VDELog.e("VN", "Icon repair: could not disable stale launcher alias '$name'", t)
            }
        }
    }

    /**
     * Body of [VencordNative.changeAppIcon]. Resolves [rawId] against
     * [ICON_NAMES] (case-insensitive, after trimming) and switches the
     * launcher alias. The caller has already authorized the bridge token and
     * run the strict Discord-domain gate. Runs under [iconLock].
     */
    internal fun applyIconChange(act: MainActivity, rawId: String?) {
        val trimmed = rawId?.trim()
        val safeId = trimmed?.let { r -> ICON_NAMES.find { it.equals(r, ignoreCase = true) } }
        if (trimmed == null || safeId == null) {
            // rawId is page-controlled and unbounded, and lands in a Toast
            // (which gets parcellized), so bound it.
            val why = if (trimmed == null) "null id" else "unknown id '${trimmed.take(64)}'"
            act.runOnUiThread { Toast.makeText(act, "Icon change: $why", Toast.LENGTH_SHORT).show() }
            return
        }
        try {
            synchronized(iconLock) {
                // Verify the cache against PM before trusting it, so the guard
                // and oldIcon below are truthful. Runs even on the
                // already-active path so re-selecting the same icon cleans up
                // leftover aliases.
                try {
                    reconcileIconState(act)
                } catch (t: Throwable) {
                    // Reads inside are exception-safe; this is only a safety
                    // net so a bridge method can never crash the process.
                    VDELog.e("VN", "changeAppIcon: icon state reconcile failed", t)
                }
                if (safeId == currentIcon) {
                    act.runOnUiThread {
                        Toast.makeText(act, "Icon '$safeId' is already active", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                val oldIcon = currentIcon
                if (oldIcon == null) {
                    // Reconcile failed to establish a baseline (it logs the
                    // reason). Guessing "Main" could disable the alias about
                    // to be enabled and leave the launcher with no entry. No
                    // PM writes have happened yet, so abort; the next attempt
                    // reconciles again.
                    VDELog.e("VN", "changeAppIcon: no resolved icon baseline; aborting")
                    act.runOnUiThread {
                        Toast.makeText(act, "Icon change failed: icon state unavailable", Toast.LENGTH_LONG).show()
                    }
                    return
                }
                val pm = act.packageManager
                val pkg = act.applicationContext
                // Enable first: while both aliases are briefly enabled the
                // launcher still has an entry; disabling first could leave none.
                pm.setComponentEnabledSetting(
                    iconComponent(pkg, safeId),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                // The new alias is live in the launcher from here on, so the
                // cache must claim it now, even if the cleanup below fails.
                currentIcon = safeId
                fun disableOld(): Boolean = try {
                    pm.setComponentEnabledSetting(
                        iconComponent(pkg, oldIcon),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    true
                } catch (t: Throwable) {
                    VDELog.e("VN", "changeAppIcon: disabling old icon '$oldIcon' failed", t)
                    false
                }
                // One retry covers transient binder failures. If it still
                // fails, reconcileIconState heals the leftover alias on the
                // next icon change or app start.
                val cleanupFailed = !disableOld() && !disableOld()
                act.runOnUiThread {
                    Toast.makeText(
                        act,
                        if (cleanupFailed)
                            "Icon switched to $safeId, but the old icon could not be removed. Opening the icon switcher again (even on the same icon) repairs it."
                        else
                            "Icon changed to $safeId. Restart launcher if it doesn't update.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } catch (t: Throwable) {
            // Safety net: an exception escaping a @JavascriptInterface method
            // kills the process. On exit the cache is either unchanged (the
            // enable threw before any commit) or already claims the live
            // alias.
            VDELog.e("VN", "changeAppIcon failed for id=$safeId", t)
            act.runOnUiThread {
                Toast.makeText(act, "Icon change failed: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Called when a VencordNative (and thus a MainActivity) is created.
     * Refreshes the icon cache from PackageManager and repairs any
     * corruption found; this is what heals duplicate launcher entries
     * after a process restart. PM reads are cheap, and only actual
     * corruption triggers writes.
     */
    fun initCurrentIcon(activity: MainActivity?) {
        val act = activity ?: return
        try {
            synchronized(iconLock) { reconcileIconState(act) }
        } catch (t: Throwable) {
            // Runs from the class constructor (MainActivity.onCreate);
            // construction must never fail. A failed reconcile leaves the
            // cache unresolved; the next changeAppIcon reconciles again.
            VDELog.e("VN", "initCurrentIcon: reconcile failed", t)
        }
    }

    // Density-scaled render size for picker tiles, clamped to 128..256 px
    // (256 px = 256 KB per ARGB_8888 bitmap).
    private const val ICON_ART_BASE_PX = 64

    internal fun iconArtRenderSizePx(act: Context): Int {
        val density = act.resources.displayMetrics.density
        if (density <= 0f) return 192
        return Math.ceil(ICON_ART_BASE_PX * density.toDouble()).toInt().coerceIn(128, 256)
    }

    // Maps the drawable's full adaptive canvas onto the tile. The adaptive
    // spec's 72/108 visible-region scaling (1.5×) rendered zoomed-in versus
    // the on-device launcher look, which this preview must match. Retune
    // here only against a launcher comparison.
    private const val ICON_VIEWPORT_SCALE = 1f

    /**
     * Renders [d] into a square [sizePx] bitmap, circularly masked like a
     * round-icon launcher. The drawable is drawn through its own bounds
     * machinery at [ICON_VIEWPORT_SCALE]; drawing the parent instead of its
     * child layers keeps <monochrome> out of the result. The mask is an
     * antialiased circle via BitmapShader; Canvas.clipPath is not
     * antialiased in software and leaves jagged edges.
     */
    internal fun renderIconBitmap(d: Drawable, sizePx: Int): Bitmap {
        val stage = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val stageCanvas = Canvas(stage)
        val bleed = (sizePx * ICON_VIEWPORT_SCALE).toInt().coerceAtLeast(sizePx)
        stageCanvas.translate((sizePx - bleed) / 2f, (sizePx - bleed) / 2f)
        d.setBounds(0, 0, bleed, bleed)
        d.draw(stageCanvas)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(stage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(out).drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
        stage.recycle()
        return out
    }

    /**
     * True when a sparse sample grid finds no opaque pixel. A masked icon
     * always has an opaque center, so an all-transparent result means the
     * render drew nothing and should fall back to a placeholder tile.
     */
    internal fun isEffectivelyEmpty(bmp: Bitmap): Boolean {
        val step = (bmp.width / 5).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if (bmp.getPixel(x, y) != 0) return false
                x += step
            }
            y += step
        }
        return true
    }

    // Round launcher-icon resource per choice in ICON_NAMES. Loaded from
    // resources rather than PackageManager.getActivityIcon: on-device,
    // component lookups only produced art for the enabled alias, while
    // resource loading ignores component state. The manifest pins each
    // alias's roundIcon to these same mipmaps.
    private val ICON_ROUND_RES: Map<String, Int> = mapOf(
        "Main" to R.mipmap.ic_launcher_round,
        "Basic" to R.mipmap.ic_launcher_v_round,
        "Jolly" to R.mipmap.ic_launcher_jolly_round,
        "Retro" to R.mipmap.ic_launcher_retro_round,
        "Discord" to R.mipmap.ic_launcher_discord_round,
        "TS12" to R.mipmap.ic_launcher_ts12_round,
        "Glass" to R.mipmap.ic_launcher_glass_round,
        "Charcoal" to R.mipmap.ic_launcher_charcoal_round,
        "OLED" to R.mipmap.ic_launcher_oled_round
    )

    /**
     * One PNG data URI per icon name for the settings-tab picker. Per-name
     * failures are skipped and logged so one bad name cannot break the map;
     * the picker keeps that tile selectable via a placeholder.
     */
    internal fun renderIconDataUris(act: Context): Map<String, String> {
        val sizePx = iconArtRenderSizePx(act)
        val res = act.resources
        val theme = act.theme
        val uris = LinkedHashMap<String, String>()
        for (name in ICON_NAMES) {
            val resId = ICON_ROUND_RES[name]
            if (resId == null || resId == 0) {
                VDELog.w("VN", "Icon art: no resource mapped for '$name'")
                continue
            }
            try {
                val drawable = res.getDrawable(resId, theme)
                val bitmap = renderIconBitmap(drawable, sizePx)
                if (isEffectivelyEmpty(bitmap)) {
                    bitmap.recycle()
                    VDELog.w("VN", "Icon art render for '$name' produced empty art; skipping")
                    continue
                }
                val png = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
                bitmap.recycle()
                uris[name] = "data:image/png;base64," +
                    Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP)
            } catch (t: Throwable) {
                VDELog.w("VN", "Icon art render failed for '$name': ${t.message}")
            }
        }
        VDELog.d("VN", "Icon art: ${uris.size}/${ICON_NAMES.size} icons rendered at ${sizePx}px")
        return uris
    }
}
