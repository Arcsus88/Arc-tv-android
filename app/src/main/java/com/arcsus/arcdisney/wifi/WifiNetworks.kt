package com.arcsus.arcdisney.wifi

/** How a network asks to be joined, read from a scan result's capabilities. */
enum class Security {
    OPEN,
    WEP,

    /** WPA/WPA2 Personal (a WPA3 transition network accepts this too). */
    PSK,

    /** WPA3-only: Android 9 has no way to add one. */
    SAE,

    /** Enterprise (802.1X): needs more than a password; not offered. */
    EAP,
    ;

    val needsPassword: Boolean get() = this == WEP || this == PSK

    val supported: Boolean get() = this == OPEN || this == WEP || this == PSK

    /** Shortest password the network can have, for enabling Connect. */
    val minPassword: Int get() = when (this) {
        PSK -> 8
        WEP -> 5
        else -> 0
    }

    companion object {
        /** "[WPA2-PSK-CCMP][ESS]" → PSK, "[RSN-SAE-CCMP]" → SAE, "[ESS]" → OPEN. */
        fun of(capabilities: String): Security {
            val c = capabilities.uppercase()
            return when {
                "EAP" in c -> EAP
                "PSK" in c -> PSK
                "SAE" in c -> SAE
                "WEP" in c -> WEP
                else -> OPEN
            }
        }
    }
}

/** One network in range, strongest access point only. */
data class WifiNetwork(
    val ssid: String,
    /** Signal, 0 (weakest) to 4. */
    val level: Int,
    val security: Security,
)

/** A scan result reduced to what the list needs, so it can be tested off-device. */
data class Sighting(val ssid: String, val rssi: Int, val capabilities: String)

/**
 * Nearby networks for the list: one row per name (hidden networks have
 * none), the network the device is on first, then strongest first.
 */
fun nearbyNetworks(sightings: List<Sighting>, connectedSsid: String?): List<WifiNetwork> =
    sightings
        .filter { it.ssid.isNotBlank() }
        .groupBy { it.ssid }
        .map { (ssid, seen) ->
            val best = seen.maxBy { it.rssi }
            WifiNetwork(ssid, signalLevel(best.rssi), Security.of(best.capabilities))
        }
        .sortedWith(
            compareByDescending<WifiNetwork> { it.ssid == connectedSsid }
                .thenByDescending { it.level }
                .thenBy { it.ssid.lowercase() },
        )

/** RSSI (dBm) to 0..4 bars, the way Android's own Wi-Fi list buckets them. */
fun signalLevel(rssi: Int): Int = when {
    rssi >= -55 -> 4
    rssi >= -66 -> 3
    rssi >= -77 -> 2
    rssi >= -88 -> 1
    else -> 0
}

/** WifiConfiguration wants text keys quoted and raw hex keys bare. */
fun quotedKey(password: String, security: Security): String {
    val hex = password.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    val raw = when (security) {
        Security.PSK -> hex && password.length == 64
        Security.WEP -> hex && password.length in setOf(10, 26, 58)
        else -> false
    }
    return if (raw) password else "\"$password\""
}

/** WifiInfo and WifiConfiguration put SSIDs in quotes; "<unknown ssid>" means it's hidden from us. */
fun unquoteSsid(ssid: String?): String? {
    val s = ssid?.trim() ?: return null
    if (s.isEmpty() || s == "<unknown ssid>" || s == "0x") return null
    return if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1) else s
}
