@file:Suppress("DEPRECATION")

package com.arcsus.arcdisney.wifi

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Joining a network the way it happens on an Android 9 device. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class WifiControllerTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Test
    fun anAppCanJoinWifiItselfOnAndroid9() {
        assertTrue(WifiController(app).canConnectInApp)
    }

    @Test
    fun savesAndJoinsAPasswordNetwork() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        wifi.isWifiEnabled = false
        val controller = WifiController(app)

        controller.connect("Home Wi-Fi", "correct horse", Security.PSK)

        assertTrue("turns Wi-Fi on first", wifi.isWifiEnabled)
        val saved = wifi.configuredNetworks.single()
        assertEquals("\"Home Wi-Fi\"", saved.SSID)
        assertEquals("\"correct horse\"", saved.preSharedKey)
        assertTrue(saved.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK))
        val enabled = shadowOf(wifi).lastEnabledNetwork // android.util.Pair(network id, disable others)
        assertEquals(saved.networkId, enabled.first)
        assertTrue(enabled.second)
        assertEquals("Home Wi-Fi", controller.state.value.connecting)
        assertNull(controller.state.value.failed)
    }

    @Test
    fun aNewPasswordReplacesTheOldEntry() {
        val controller = WifiController(app)
        controller.connect("Home", "old password", Security.PSK)
        controller.connect("Home", "new password", Security.PSK)
        assertEquals("\"new password\"", wifi.configuredNetworks.single().preSharedKey)
    }

    @Test
    fun joinsAnOpenNetworkWithoutAKey() {
        val controller = WifiController(app)
        controller.connect("Cafe", "", Security.OPEN)
        val saved = wifi.configuredNetworks.single()
        assertTrue(saved.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE))
    }

    @Test
    fun refusesWpa3OnlyAndEnterpriseNetworks() {
        val controller = WifiController(app)
        controller.connect("Office", "secret123", Security.EAP)
        assertEquals("Office", controller.state.value.failed?.first)
        assertTrue(wifi.configuredNetworks.isEmpty())
    }
}
