package com.arcsus.arcdisney.data

import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.util.Locale

/** One torrent of a film, as found on The Pirate Bay or Knaben. */
@Serializable
data class Release(
    val name: String,
    /** v1 info-hash, 40 lowercase hex characters. */
    val hash: String,
    /** Total size in bytes (0 when unknown). */
    val size: Long = 0,
    /** Seeders when it was found. */
    val seeds: Int = 0,
) {
    /** A magnet with the trackers thepiratebay.org puts on its own magnets. */
    val magnet: String
        get() = "magnet:?xt=urn:btih:${hash.uppercase(Locale.ROOT)}&dn=${encode(name)}" +
            TRACKERS.joinToString("") { "&tr=${encode(it)}" }

    val sizeLabel: String get() = formatBytes(size)

    companion object {
        val TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://tracker.bittor.pw:1337/announce",
            "udp://public.popcorn-tracker.org:6969/announce",
            "udp://tracker.dler.org:6969/announce",
            "udp://exodus.desync.com:6969",
            "udp://open.demonii.com:1337/announce",
        )

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** A Disney-branded or Pixar feature, from TMDB. */
@Serializable
data class Film(
    /** TMDB id. */
    val id: Int,
    val title: String,
    val year: String = "",
    /** TMDB image path ("/abc.jpg"), "" when TMDB has no poster. */
    val poster: String = "",
    val popularity: Double = 0.0,
    val imdb: String? = null,
    /** When its releases were last searched (epoch ms); null = never. */
    val scannedAt: Long? = null,
    /** When a search was last tried, whether or not it finished. */
    val attemptedAt: Long? = null,
    /** The releases worth streaming, best first. */
    val releases: List<Release> = emptyList(),
) {
    val seeded: Boolean get() = releases.any { it.seeds > 0 }

    /** Searched, with something seeded to stream: the only films the wall shows. */
    val playable: Boolean get() = scannedAt != null && seeded

    fun posterUrl(width: Int = 342): String =
        if (poster.isBlank()) "" else "https://image.tmdb.org/t/p/w$width$poster"
}

@Serializable
data class Catalogue(
    /** When an update (or the snapshot build) last finished (epoch ms). */
    val updatedAt: Long = 0,
    /** The bundled snapshot this catalogue last merged in (its updatedAt). */
    val bundledAt: Long = 0,
    /** Most popular first. */
    val films: List<Film> = emptyList(),
) {
    val playable: List<Film> get() = films.filter { it.playable }
}

/** Lower-case, accent-free words, so "wall e" finds WALL·E and "mal" finds Maléfique. */
fun searchWords(s: String): List<String> = normTitle(s).split(' ').filter { it.isNotEmpty() }

/** Every typed word appears in the film's title or year. */
fun Film.matches(words: List<String>): Boolean {
    if (words.isEmpty()) return true
    val hay = " ${normTitle(title)} $year "
    return words.all { hay.contains(it) }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    // A no-break space, so "1.8 GB" never wraps between number and unit.
    return if (i >= 3) String.format(Locale.ROOT, "%.1f\u00A0%s", v, units[i]) else "${v.toInt()}\u00A0${units[i]}"
}
