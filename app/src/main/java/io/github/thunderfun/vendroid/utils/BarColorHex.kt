package io.github.thunderfun.vendroid.utils

import java.util.Locale

/**
 * Strict "#RRGGBB" parsing shared by the startup readers and the bridge color
 * picker. Pure (no android.graphics) so the settings payload tests run on the
 * plain JVM. Empty is the reserved "auto / theme default" value and is handled
 * by callers, not parsed here.
 */
internal object BarColorHex {
    fun parseOrNull(value: String): Int? {
        if (value.length != 7 || value[0] != '#') return null
        var rgb = 0
        for (i in 1..6) {
            val digit = when (val c = value[i]) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> return null
            }
            rgb = (rgb shl 4) or digit
        }
        return 0xFF000000.toInt() or rgb
    }

    fun isValid(value: String): Boolean = parseOrNull(value) != null

    fun format(color: Int): String =
        String.format(Locale.US, "#%06x", color and 0xFFFFFF)
}
