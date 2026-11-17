package io.github.thunderfun.vendroid.utils

import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat
import io.github.thunderfun.vendroid.R

/** Theme-resolved system-bar colors shared by the main and recovery screens. */
internal object BarColors {
    /**
     * Resolves @android:attr/statusBarColor from the current theme so
     * values-night and any future re-point of the attribute stay correct.
     * The attribute is deprecated on API 36+, but reading it is still the
     * only way to get the theme's intended bar color.
     */
    @Suppress("DEPRECATION")
    fun resolveThemeBarColor(context: Context): Int {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.statusBarColor, tv, true)) {
            try {
                val color = if (tv.type == TypedValue.TYPE_REFERENCE ||
                    tv.type == TypedValue.TYPE_STRING
                ) {
                    if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else null
                } else {
                    tv.data
                }
                if (color != null) return color
            } catch (t: Throwable) {
                VDELog.w("BarColors", "theme bar color resolve failed; using fallback: $t")
            }
        }
        return ContextCompat.getColor(context, R.color.status_bar_color)
    }

    fun resolveThemeBarColorHex(context: Context): String =
        BarColorHex.format(resolveThemeBarColor(context))
}
