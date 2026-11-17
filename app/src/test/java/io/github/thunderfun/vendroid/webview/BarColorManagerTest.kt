package io.github.thunderfun.vendroid.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarColorManagerTest {

    private class FakeTarget(
        var status: Int = 0,
        var navigation: Int = 0,
    ) : SystemBarTarget {
        var lightStatus = false
        var lightNavigation = false
        var colorWrites = 0

        override fun post(block: () -> Unit) = block()

        override fun applyBarColors(statusColor: Int, navigationColor: Int): Boolean {
            if (status == statusColor && navigation == navigationColor) return false
            status = statusColor
            navigation = navigationColor
            colorWrites++
            return true
        }

        override var isAppearanceLightStatusBars: Boolean
            get() = lightStatus
            set(value) { lightStatus = value }

        override var isAppearanceLightNavigationBars: Boolean
            get() = lightNavigation
            set(value) { lightNavigation = value }
    }

    private val theme = 0xFF112233.toInt()
    private val darkTint = 0xFF101010.toInt()
    private val lightTint = 0xFFF0F0F0.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun `custom tint paints both bars and picks icon contrast`() {
        val target = FakeTarget()
        val manager = BarColorManager(target, theme, theme)

        manager.publishCustomColor(darkTint)
        assertEquals(darkTint, target.status)
        assertEquals(darkTint, target.navigation)
        assertFalse(target.lightStatus)
        assertFalse(target.lightNavigation)

        manager.publishCustomColor(lightTint)
        assertEquals(lightTint, target.status)
        assertEquals(lightTint, target.navigation)
        assertTrue(target.lightStatus)
        assertTrue(target.lightNavigation)
    }

    @Test
    fun `null custom tint returns to theme colors`() {
        val target = FakeTarget()
        val manager = BarColorManager(target, theme, theme)

        manager.publishCustomColor(darkTint)
        manager.publishCustomColor(null)

        assertEquals(theme, target.status)
        assertEquals(theme, target.navigation)
        assertFalse(target.lightStatus)
        assertFalse(target.lightNavigation)
    }

    @Test
    fun `overlay blacks both bars regardless of custom tint`() {
        val target = FakeTarget()
        val manager = BarColorManager(target, theme, theme)

        manager.publishCustomColor(lightTint)
        manager.publishOverlayActive(true)

        assertEquals(black, target.status)
        assertEquals(black, target.navigation)
        assertFalse(target.lightStatus)
        assertFalse(target.lightNavigation)

        manager.publishOverlayActive(false)
        assertEquals(lightTint, target.status)
        assertEquals(lightTint, target.navigation)
        assertTrue(target.lightStatus)
        assertTrue(target.lightNavigation)
    }

    @Test
    fun `video blacks status only and composes with overlay`() {
        val target = FakeTarget()
        val manager = BarColorManager(target, theme, theme)

        manager.publishCustomColor(darkTint)
        manager.publishVideoFullscreen(true)
        assertEquals(black, target.status)
        assertEquals(darkTint, target.navigation)

        manager.publishOverlayActive(true)
        assertEquals(black, target.status)
        assertEquals(black, target.navigation)

        manager.publishOverlayActive(false)
        assertEquals(black, target.status)
        assertEquals(darkTint, target.navigation)

        manager.publishVideoFullscreen(false)
        assertEquals(darkTint, target.status)
        assertEquals(darkTint, target.navigation)
    }

    @Test
    fun `duplicate publish does not repaint the backdrop`() {
        val target = FakeTarget()
        val manager = BarColorManager(target, theme, theme)

        manager.publishCustomColor(darkTint)
        manager.publishCustomColor(darkTint)

        assertEquals(1, target.colorWrites)
    }
}
