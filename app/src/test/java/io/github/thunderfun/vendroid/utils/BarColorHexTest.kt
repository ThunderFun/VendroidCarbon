package io.github.thunderfun.vendroid.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BarColorHexTest {
    @Test
    fun `parses strict six-digit hex`() {
        assertEquals(0xFF121214.toInt(), BarColorHex.parseOrNull("#121214"))
        assertEquals(0xFFABCDEF.toInt(), BarColorHex.parseOrNull("#ABCDEF"))
    }

    @Test
    fun `rejects malformed and non-ascii digits`() {
        assertNull(BarColorHex.parseOrNull(""))
        assertNull(BarColorHex.parseOrNull("#12345"))
        assertNull(BarColorHex.parseOrNull("#1234567"))
        assertNull(BarColorHex.parseOrNull("121214"))
        assertNull(BarColorHex.parseOrNull("#GGGGGG"))
        // Character.digit would accept these; the picker protocol must not.
        assertNull(BarColorHex.parseOrNull("#١٢١٢١٢"))
        assertFalse(BarColorHex.isValid("#12345"))
    }

    @Test
    fun `formats lowercase and masks alpha`() {
        assertEquals("#121214", BarColorHex.format(0xFF121214.toInt()))
        assertEquals("#abcdef", BarColorHex.format(0x00ABCDEF))
    }
}
