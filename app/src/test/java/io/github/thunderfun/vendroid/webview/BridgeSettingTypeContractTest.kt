package io.github.thunderfun.vendroid.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the bridge settings type contract ([VencordNative.isTypeSafeBridgeWrite]):
 *
 *  - Keys read as Booleans (BOOLEAN_SETTING_KEYS) reject setString. A String
 *    there makes the startup getBoolean() throw ClassCastException (a crash
 *    loop for vendroid_confirmExternalLinks).
 *  - Keys read as Strings (STRING_SETTING_KEYS, notably clientMod) reject
 *    setBool. A Boolean there makes the startup getString throw an uncaught
 *    ClassCastException that kills the process on every cold start.
 *
 * clientMod and desktopMode are asserted by name, not just via set
 * iteration: clientMod was historically missing from the setBool side, and
 * desktopMode needs its allowlist entry and Boolean mirror to stay in sync.
 * A revert of either half fails these tests.
 */
class BridgeSettingTypeContractTest {

    @Test
    fun `clientMod rejects bool writes and accepts string writes`() {
        assertFalse(VencordNative.isTypeSafeBridgeWrite("setBool", "clientMod"))
        assertTrue(VencordNative.isTypeSafeBridgeWrite("setString", "clientMod"))
    }

    @Test
    fun `vencordLocation rejects bool writes`() {
        assertFalse(VencordNative.isTypeSafeBridgeWrite("setBool", "vencordLocation"))
    }

    @Test
    fun `desktopMode rejects string writes and accepts bool writes`() {
        // desktopMode feeds the startup UA switch, which reads it with
        // getBoolean; a String under it would make that read fall back to
        // false and silently disable the toggle.
        assertFalse(VencordNative.isTypeSafeBridgeWrite("setString", "desktopMode"))
        assertTrue(VencordNative.isTypeSafeBridgeWrite("setBool", "desktopMode"))
    }

    @Test
    fun `string-read keys reject bool writes`() {
        for (key in VencordNative.STRING_SETTING_KEYS)
            assertFalse("setBool must reject $key", VencordNative.isTypeSafeBridgeWrite("setBool", key))
    }

    @Test
    fun `boolean-read keys reject string writes but allow bool writes`() {
        for (key in VencordNative.BOOLEAN_SETTING_KEYS) {
            assertFalse("setString must reject $key", VencordNative.isTypeSafeBridgeWrite("setString", key))
            assertTrue("setBool must remain allowed for $key", VencordNative.isTypeSafeBridgeWrite("setBool", key))
        }
    }

    @Test
    fun `type contracts are disjoint`() {
        val overlap = VencordNative.BOOLEAN_SETTING_KEYS intersect VencordNative.STRING_SETTING_KEYS
        assertTrue("keys claimed by both contracts: $overlap", overlap.isEmpty())
    }

    @Test
    fun `unrelated keys pass both writers`() {
        assertTrue(VencordNative.isTypeSafeBridgeWrite("setBool", "vendroid_blockTypingIndicator"))
        assertTrue(VencordNative.isTypeSafeBridgeWrite("setString", "Vencord-TestPlugin"))
        assertFalse(VencordNative.isTypeSafeBridgeWrite("setString", "vendroid_rememberLastChannel"))
    }
}
