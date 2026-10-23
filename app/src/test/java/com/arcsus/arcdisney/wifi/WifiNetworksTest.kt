package com.arcsus.arcdisney.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiNetworksTest {

    @Test
    fun readsSecurityFromCapabilities() {
        assertEquals(Security.PSK, Security.of("[WPA2-PSK-CCMP][ESS]"))
        assertEquals(Security.PSK, Security.of("[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS]")) // WPA3 transition
        assertEquals(Security.SAE, Security.of("[RSN-SAE-CCMP][ESS]"))
        assertEquals(Security.EAP, Security.of("[WPA2-EAP-CCMP][ESS]"))
        assertEquals(Security.WEP, Security.of("[WEP][ESS]"))
        assertEquals(Security.OPEN, Security.of("[ESS]"))
        assertFalse(Security.SAE.supported)
        assertTrue(Security.PSK.needsPassword)
        assertFalse(Security.OPEN.needsPassword)
    }

    @Test
    fun oneRowPerNameConnectedFirstThenStrongest() {
        val list = nearbyNetworks(
            listOf(
                Sighting("Neighbours", -50, "[WPA2-PSK-CCMP]"),
                Sighting("Home", -80, "[WPA2-PSK-CCMP]"),
                Sighting("Home", -60, "[WPA2-PSK-CCMP]"), // a second access point, stronger
                Sighting("", -40, "[WPA2-PSK-CCMP]"), // hidden
                Sighting("Cafe", -70, "[ESS]"),
            ),
            connectedSsid = "Home",
        )
        assertEquals(listOf("Home", "Neighbours", "Cafe"), list.map { it.ssid })
        assertEquals(3, list.first().level)
        assertEquals(Security.OPEN, list.last().security)
    }

    @Test
    fun quotesTextKeysButNotHexOnes() {
        assertEquals("\"hunter22\"", quotedKey("hunter22", Security.PSK))
        val hex = "a".repeat(64)
        assertEquals(hex, quotedKey(hex, Security.PSK))
        assertEquals("1234567890", quotedKey("1234567890", Security.WEP))
        assertEquals("\"abcde\"", quotedKey("abcde", Security.WEP))
    }

    @Test
    fun unquotesSsids() {
        assertEquals("Home", unquoteSsid("\"Home\""))
        assertNull(unquoteSsid("<unknown ssid>"))
        assertNull(unquoteSsid(null))
        assertEquals("Home", unquoteSsid("Home"))
    }
}
