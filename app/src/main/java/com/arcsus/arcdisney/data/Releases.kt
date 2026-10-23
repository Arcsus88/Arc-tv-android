package com.arcsus.arcdisney.data

import java.text.Normalizer
import java.util.Locale
import kotlin.math.log10

// Release-name matching and ranking, ported from the arc-disney Edge Function
// and re-ranked for what this app does: stream the torrent itself into VLC.
// VLC plays TrueHD/DTS fine (a browser plays them silent), so those keep their
// place; size and seeders matter far more, as every byte comes from the swarm.

private val STOP = setOf("the", "a", "an", "with", "of", "and", "to", "part")
private val DIACRITICS = Regex("\\p{Mn}+")

fun normTitle(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFKD).replace(DIACRITICS, "").lowercase(Locale.ROOT)
        .replace("&", "and").replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()

private fun titleTokens(s: String): List<String> = normTitle(s).split(' ').filter { it.isNotEmpty() && it !in STOP }

/** The title as a search query: "WALL·E" → "WALL E", "Lilo & Stitch" → "Lilo and Stitch". */
fun searchForm(title: String): String =
    Normalizer.normalize(title, Normalizer.Form.NFKD).replace(DIACRITICS, "").replace("&", " and ")
        .replace(Regex("[^A-Za-z0-9]+"), " ").replace(Regex("\\s+"), " ").trim()

/** The Pirate Bay categories worth streaming: Movies, HD Movies, UHD Movies (not DVDR or 3D). */
val MOVIE_CATEGORIES = setOf("201", "207", "211")

fun tvShaped(t: String): Boolean =
    Regex("s\\d{1,2}e\\d{1,3}", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
        Regex("\\b\\d{1,2}x\\d{2,3}\\b").containsMatchIn(t) ||
        Regex("\\bs\\d{1,2}\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
        Regex("season\\s*\\d+", RegexOption.IGNORE_CASE).containsMatchIn(t)

/** Multi-film packs: the player takes the largest file, which may be the wrong film. */
fun isPack(t: String): Boolean = Regex(
    "collection|trilogy|duology|quadrilogy|anthology|complete|\\bpack\\b|\\b\\d+\\s*movies?\\b|\\b\\d+\\s*films?\\b",
    RegexOption.IGNORE_CASE,
).containsMatchIn(t)

/** Word-bounded, so "Scamp" and "Camp" aren't cam rips. */
fun isCam(t: String): Boolean = Regex(
    "\\b(cam|camrip|hdcam|hqcam|ts|telesync|hdts|tc|telecine|dvdscr|scr|hdscr|dcprip)\\b",
    RegexOption.IGNORE_CASE,
).containsMatchIn(t)

/** Bonus discs and samples carry the film's name and IMDb tag but not the film. */
fun isExtras(t: String): Boolean = Regex(
    "\\b(extras?|bonus|featurettes?|sample|trailers?)\\b",
    RegexOption.IGNORE_CASE,
).containsMatchIn(t.replace('_', ' '))

/** The title as whole words, in order: "up" matches "Up.2009.1080p", not "Pick.Up". */
fun titleMatches(name: String, title: String): Boolean {
    val want = titleTokens(title).joinToString(" ")
    return want.isEmpty() || " ${titleTokens(name).joinToString(" ")} ".contains(" $want ")
}

/** Remakes share titles (The Lion King 1994/2019), so a name search must carry the year, ±1. */
fun yearMatches(name: String, year: Int): Boolean =
    Regex("(?<!\\d)(19[2-9]\\d|20[0-3]\\d)(?!\\d)").findAll(name).any { kotlin.math.abs(it.value.toInt() - year) <= 1 }

private val NUMERALS = mapOf("ii" to "2", "iii" to "3", "iv" to "4")
private val LEADING_TAG = Regex("^\\s*(?:\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}|www\\.\\S+)\\s*[-:]?\\s*")
private val DISNEY_PREFIXES = listOf(
    listOf("walt", "disney", "s", "classic"), listOf("walt", "disney", "classic"), listOf("walt", "disney", "s"),
    listOf("walt", "disneys"), listOf("walt", "disney"), listOf("disney", "s"), listOf("disneys"), listOf("disney", "movie"),
    listOf("disney"),
)

/** What may sit between a one-word title and its year: "Kidnapped (Action 1960)". */
private val FILLER = setOf(
    "action", "adventure", "animation", "animated", "comedy", "com", "drama", "west", "western", "westen", "family",
    "documentary", "doc", "mystery", "sci", "fi", "scifi", "horror", "musical", "music", "romance", "fantasy", "thriller",
    "war", "crime", "film", "movie", "aka",
)

private fun nameWords(s: String): List<String> =
    normTitle(s.replace('_', ' ')).split(' ').filter { it.isNotEmpty() && it !in STOP }.map { NUMERALS[it] ?: it }

/**
 * For releases found by searching the title: the name starts with the title
 * (after a "[site]" tag or "Disney's"), isn't a sequel of it, and carries the
 * year (±1) somewhere after. So "Aladdin (1992) 1080p" and "Disney Movie - Amy
 * 1981" match, but "Tiger.3.2023" isn't Tiger (2024), "Under.Wraps.2.2022"
 * isn't Under Wraps (2021), and "The Prime of Miss Jean Brodie (1969) Maggie
 * Smith" isn't Smith! (1969). Words are compared run together, so "Simbas"
 * matches "Simba's" and "Zombies" matches "Z-O-M-B-I-E-S".
 */
fun namedAs(name: String, title: String, year: Int): Boolean {
    var rest = name
    while (true) {
        val tag = LEADING_TAG.find(rest)?.takeIf { it.value.isNotEmpty() } ?: break
        rest = rest.substring(tag.range.last + 1)
    }
    val words = nameWords(rest)
    val titleWords = nameWords(title)
    val want = titleWords.joinToString("")
    if (want.isEmpty()) return false
    val starts = listOf(0) + DISNEY_PREFIXES
        .filter { p -> words.take(p.size) == p && !want.startsWith(p.joinToString("")) }
        .map { it.size }
    return starts.any { start ->
        var i = start
        val seen = StringBuilder()
        while (i < words.size && seen.length < want.length) seen.append(words[i++])
        if (seen.toString() != want) return@any false
        val after = words.drop(i)
        if (after.firstOrNull()?.let { it.length == 1 && it[0] in '2'..'9' } == true) return@any false
        val at = after.indexOfFirst { w ->
            w.length == 4 && w.all(Char::isDigit) && w.toInt() in 1920..2039 && kotlin.math.abs(w.toInt() - year) <= 1
        }
        at >= 0 && (titleWords.size > 1 || after.take(at).all { it in FILLER })
    }
}

private val FOREIGN = Regex(
    "\\b(multi|dual|french|truefrench|vff|vfq|vf2|ita|italian|german|spanish|castellano|latino|hindi|tamil|" +
        "telugu|russian|polish|dublado|nordic|swedish|korean|turkish)\\b",
)

private const val GIB = 1024L * 1024 * 1024
private const val MIB = 1024L * 1024

/**
 * How well a release streams on a phone, from its name, size and seeders.
 * 1080p is the target: 4K is a big download for a phone screen, and the
 * swarm has to deliver every byte before VLC can show it.
 */
fun streamScore(r: Release): Int {
    val t = r.name.lowercase(Locale.ROOT).replace('_', ' ') // "Multi_Group" hides the tag from \b
    var s = when {
        Regex("2160p|\\b4k\\b|\\buhd\\b").containsMatchIn(t) -> 2200
        t.contains("1080p") -> 3000
        t.contains("720p") -> 2600
        else -> 1200
    }
    s += when {
        t.contains("remux") -> -1500
        t.contains("bluray") || t.contains("blu-ray") || t.contains("bdrip") || t.contains("brrip") -> 300
        t.contains("web-dl") || t.contains("webdl") -> 250
        t.contains("webrip") || t.contains(" web ") || t.contains(".web.") -> 200
        t.contains("hdrip") -> 100
        else -> 0
    }
    when {
        r.size > 16 * GIB -> s -= 2500
        r.size > 8 * GIB -> s -= 1200
        r.size in 1 until 400 * MIB -> s -= 600
    }
    if (Regex("\\bav1\\b").containsMatchIn(t)) s -= 600 // software-decoded on most phones
    if (Regex("upscal|\\bai\\b.*\\bfps\\b|\\b60 ?fps\\b").containsMatchIn(t)) s -= 2500
    if (FOREIGN.containsMatchIn(t)) s -= 1500 // often default to a non-English track
    s += (600 * log10(r.seeds.coerceAtLeast(0) + 1.0)).toInt()
    return s
}

/** Seeded first, then by how well they stream; at most [keep]. */
fun rankReleases(releases: List<Release>, keep: Int = 8): List<Release> =
    releases.sortedWith(
        compareByDescending<Release> { it.seeds > 0 }.thenByDescending { streamScore(it) }.thenByDescending { it.seeds },
    ).take(keep)
