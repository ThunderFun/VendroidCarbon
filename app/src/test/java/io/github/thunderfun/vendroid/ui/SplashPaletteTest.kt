package io.github.thunderfun.vendroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the auto stage clamp ([SplashPalette.stageColor]): tints above the
 * cap clamp down with hue and saturation kept, in-band tints pass through,
 * null keeps the XML/theme default. An explicit splash-bg pick bypasses
 * the clamp and renders verbatim (applySplashStageColor).
 */
class SplashPaletteTest {

    @Test
    fun `bright tints clamp down to the cap with hue and saturation kept`() {
        // White: V 1.0 clamps to 0.2, and S 0 keeps it a neutral dark grey.
        assertEquals(0xFF333333.toInt(), SplashPalette.stageColor(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `tints at or under the cap pass through unchanged`() {
        assertEquals(0xFF121214.toInt(), SplashPalette.stageColor(0xFF121214.toInt()))
        // V 0.20 sits exactly at the cap, so re-running the clamp is a no-op.
        assertEquals(0xFF0D1A33.toInt(), SplashPalette.stageColor(0xFF0D1A33.toInt()))
    }

    @Test
    fun `null tint keeps the XML or theme default`() {
        assertNull(SplashPalette.stageColor(null))
    }
}
