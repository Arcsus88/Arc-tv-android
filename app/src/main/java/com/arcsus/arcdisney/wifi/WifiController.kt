@file:Suppress("DEPRECATION") // The app-managed Wi-Fi calls Android 10 retired are the ones Android 9 has.

package com.arcsus.arcdisney.wifi

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class WifiState(
    val enabled: Boolean = false,
    /** The Wi-Fi network the device is on; null when it isn't on one. */
    val connectedSsid: String? = null,
    /** Some network (Wi-Fi or other) reaches the internet. */
    val online: Boolean = false,
    val networks: List<WifiNetwork> = emptyList(),
    /** Location permission granted: Android 9 hides nearby networks without it. */
    val locationPermission: Boolean = false,
    /** Location turned on: Android 9 hides nearby networks without that too. */
    val locationOn: Boolean = true,
    /** The network being joined right now. */
    val connecting: String? = null,
    /** The last join that failed, and why. */
    val failed: Pair<String, String>? = null,
)

/**
 * Wi-Fi for a locked device, where the user can't reach Settings. Android 9
 * lets an app add a network and join it; Android 10 and later only let a
 * device owner do that, so elsewhere [canConnectInApp] is false and the
 * screen offers the system Wi-Fi panel instead.
 */
class WifiController(context: Context) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(WifiState())
    val state: StateFlow<WifiState> = _state.asStateFlow()

    val canConnectInApp: Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ||
            app.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(app.packageName) == true

    private var monitoring = false
    private var scanning = false

    // ---- Online or not, for the whole app ----

    private val defaultNetwork = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            main.post {
                _state.update { it.copy(online = online) }
                refresh()
            }
        }

        override fun onLost(network: Network) {
            main.post {
                _state.update { it.copy(online = false) }
                refresh()
            }
        }
    }

    fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        _state.update { it.copy(online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) }
        connectivity.registerDefaultNetworkCallback(defaultNetwork)
        refresh()
    }

    fun stopMonitoring() {
        if (!monitoring) return
        monitoring = false
        runCatching { connectivity.unregisterNetworkCallback(defaultNetwork) }
    }

    // ---- Nearby networks and joining, while the Wi-Fi screen is open ----

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SUPPLICANT_STATE_CHANGED_ACTION) {
                val error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1)
                val target = _state.value.connecting
                if (error == WifiManager.ERROR_AUTHENTICATING && target != null) {
                    fail(target, "Wrong password.")
                    return
                }
            }
            refresh()
        }
    }

    private val giveUp = Runnable {
        _state.value.connecting?.let { fail(it, "Couldn't connect. Check the password and try again.") }
    }

    fun startScanning() {
        if (scanning) return
        scanning = true
        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
        scan()
    }

    fun stopScanning() {
        if (!scanning) return
        scanning = false
        runCatching { app.unregisterReceiver(receiver) }
    }

    /** Asks for fresh results (Android 9 allows four a couple of minutes; extra asks are ignored). */
    fun scan() {
        if (wifi.isWifiEnabled) runCatching { wifi.startScan() }
    }

    fun turnOn() {
        runCatching { wifi.isWifiEnabled = true }
        refresh()
    }

    /** Called after the location permission prompt. */
    fun permissionChanged() {
        refresh()
        scan()
    }

    /**
     * Saves the network and joins it. Saved networks are remembered, so the
     * device joins again by itself after a restart.
     */
    fun connect(ssid: String, password: String, security: Security) {
        if (!security.supported) return fail(ssid, "This kind of network isn't supported here.")
        if (!wifi.isWifiEnabled) runCatching { wifi.isWifiEnabled = true }
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            when (security) {
                Security.OPEN -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                Security.WEP -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                    allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                    wepKeys[0] = quotedKey(password, security)
                    wepTxKeyIndex = 0
                }
                else -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    preSharedKey = quotedKey(password, security)
                }
            }
            // Typed in by hand: it may not broadcast its name.
            hiddenSSID = _state.value.networks.none { it.ssid == ssid }
        }
        // A new password replaces what this app saved before (it can't touch others' entries).
        runCatching { wifi.configuredNetworks }.getOrNull()
            ?.filter { it.SSID == config.SSID }
            ?.forEach { wifi.removeNetwork(it.networkId) }
        val id = wifi.addNetwork(config)
        if (id == -1) return fail(ssid, "Couldn't save this network.")

        _state.update { it.copy(connecting = ssid, failed = null) }
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, JOIN_TIMEOUT_MS)
        wifi.disconnect()
        if (!wifi.enableNetwork(id, true)) return fail(ssid, "Couldn't connect to this network.")
        wifi.reconnect()
    }

    private fun fail(ssid: String, message: String) {
        main.removeCallbacks(giveUp)
        _state.update { it.copy(connecting = null, failed = ssid to message) }
    }

    private fun refresh() {
        val permission = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val locationOn = app.getSystemService(LocationManager::class.java)
            ?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        val joined = info != null && info.networkId != -1 && info.supplicantState == SupplicantState.COMPLETED
        // Without location access Android 9 reports the name as "<unknown ssid>".
        val ssid = if (joined) unquoteSsid(info?.ssid) ?: _state.value.connecting ?: "Wi-Fi" else null
        val sightings = if (permission) {
            runCatching { wifi.scanResults }.getOrNull().orEmpty().map { Sighting(it.SSID.orEmpty(), it.level, it.capabilities.orEmpty()) }
        } else {
            emptyList()
        }
        _state.update { s ->
            val done = s.connecting != null && joined && (ssid == s.connecting || unquoteSsid(info?.ssid) == null)
            if (done) main.removeCallbacks(giveUp)
            s.copy(
                enabled = wifi.isWifiEnabled,
                connectedSsid = ssid,
                networks = nearbyNetworks(sightings, ssid),
                locationPermission = permission,
                locationOn = locationOn,
                connecting = if (done) null else s.connecting,
            )
        }
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 30_000L
    }
}
