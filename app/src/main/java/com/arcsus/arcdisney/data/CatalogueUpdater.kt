package com.arcsus.arcdisney.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

/**
 * The update button, done on the phone instead of the arc-disney backend:
 * TMDB for any Disney films the catalogue lacks ("sync"), then The Pirate
 * Bay and Knaben for their releases ("scan"). Like the backend, films that already
 * have seeded releases keep them and are never searched again. Films that
 * came up empty are tried again after a while, since new films often have
 * no releases until their digital release.
 */
class CatalogueUpdater(
    private val bay: PirateBay,
    /** Null without a TMDB key: then only the releases are searched. */
    private val tmdb: Tmdb?,
    /** A second place to look for releases; null searches The Pirate Bay alone. */
    private val knaben: Knaben? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val concurrency: Int = 4,
) {
    enum class Phase { FINDING_FILMS, SEARCHING }

    data class Progress(val phase: Phase, val done: Int = 0, val total: Int = 0)

    data class Result(
        val catalogue: Catalogue,
        /** Films TMDB listed that the catalogue didn't have. */
        val newFilms: Int,
        /** Films that can now be played and couldn't before. */
        val newlyPlayable: Int,
        val searched: Int,
        val failed: Int,
        /** Why the update fell short, or null when it didn't. */
        val error: String?,
    )

    suspend fun update(
        current: Catalogue,
        limit: Int = Int.MAX_VALUE,
        onProgress: (Progress) -> Unit = {},
        onCheckpoint: suspend (Catalogue) -> Unit = {},
    ): Result {
        val start = now()
        val films = current.films.associateByTo(LinkedHashMap()) { it.id }
        val wasPlayable = films.values.filter { it.playable }.mapTo(HashSet()) { it.id }

        var newFilms = 0
        var syncError: String? = null
        if (tmdb != null) {
            onProgress(Progress(Phase.FINDING_FILMS))
            try {
                for (f in tmdb.disneyFilms(Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate())) {
                    val old = films[f.id]
                    if (old == null) {
                        films[f.id] = f
                        newFilms++
                    } else {
                        films[f.id] = old.copy(
                            title = f.title.ifBlank { old.title },
                            year = f.year.ifBlank { old.year },
                            poster = f.poster.ifBlank { old.poster },
                            popularity = f.popularity,
                        )
                    }
                }
            } catch (e: IOException) {
                syncError = e.message ?: "Couldn't reach TMDB."
            }
        }

        val due = films.values.filter { isDue(it, start) }
            .sortedWith(compareBy<Film> { it.scannedAt != null }.thenByDescending { it.popularity })
            .take(limit)

        val lock = Mutex()
        var done = 0
        var failed = 0
        var lastError: String? = null
        fun snapshot() = Catalogue(
            updatedAt = now(),
            bundledAt = current.bundledAt,
            films = films.values.sortedByDescending { it.popularity },
        )

        coroutineScope {
            val permits = Semaphore(concurrency)
            due.map { film ->
                async {
                    permits.withPermit {
                        // Nothing has worked yet and several have failed: the
                        // site is blocked or down, so stop rather than grind on.
                        if (done == 0 && failed >= ABORT_AFTER) return@withPermit
                        val at = now()
                        var error: IOException? = null
                        val updated = try {
                            val (imdb, releases) = scan(film)
                            film.copy(imdb = imdb, releases = releases, scannedAt = at, attemptedAt = at)
                        } catch (e: IOException) {
                            error = e
                            // A failed search must not read as "no seeds": scannedAt stays as it was.
                            film.copy(attemptedAt = at)
                        }
                        lock.withLock {
                            films[film.id] = updated
                            if (error == null) {
                                done++
                            } else {
                                failed++
                                lastError = error.message
                            }
                            onProgress(Progress(Phase.SEARCHING, done + failed, due.size))
                            if ((done + failed) % CHECKPOINT_EVERY == 0) onCheckpoint(snapshot())
                        }
                    }
                }
            }.awaitAll()
        }

        val result = snapshot()
        val newlyPlayable = result.films.count { it.playable && it.id !in wasPlayable }
        val error = when {
            due.isNotEmpty() && done == 0 && failed > 0 ->
                "Couldn't search for releases (${lastError ?: "no answer"}). The sites may be blocked on this network."
            syncError != null -> syncError
            failed > 0 -> "$failed of ${due.size} searches failed; they'll be tried again next time."
            else -> null
        }
        return Result(result, newFilms, newlyPlayable, done, failed, error)
    }

    /** Never searched, or searched without finding anything seeded and due another look. */
    private fun isDue(f: Film, now: Long): Boolean {
        if (f.scannedAt == null) return true
        if (f.seeded) return false
        val last = f.attemptedAt ?: f.scannedAt
        val thisYear = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).year
        val recent = (f.year.toIntOrNull() ?: 0) >= thisYear - 1
        return now - last >= if (recent) RETRY_RECENT_MS else RETRY_OLD_MS
    }

    /**
     * IMDb-tagged uploads are exact; a title + year search fills in when few
     * are tagged, then Knaben when The Pirate Bay has few seeded. The film
     * counts as searched if either site answered.
     */
    private suspend fun scan(f: Film): Pair<String?, List<Release>> {
        val imdb = f.imdb ?: tmdb?.let {
            try {
                it.imdbId(f.id)
            } catch (e: IOException) {
                null
            }
        }
        val year = f.year.toIntOrNull() ?: 0
        val found = LinkedHashMap<String, Release>()
        var answered = false
        var failure: IOException? = null
        suspend fun search(find: suspend () -> List<Release>, keep: (Release) -> Boolean) {
            try {
                find().filter(keep).forEach { found.putIfAbsent(it.hash, it) }
                answered = true
            } catch (e: IOException) {
                failure = e
            }
        }
        val named: (Release) -> Boolean = { if (year > 0) namedAs(it.name, f.title, year) else titleMatches(it.name, f.title) }
        val withYear = if (year > 0) " $year" else ""
        val query = searchForm(f.title) + withYear

        if (imdb != null) {
            search({ bay.search(imdb) }) { titleMatches(it.name, f.title) || (year > 0 && yearMatches(it.name, year)) }
        }
        if (found.size < 3) search({ bay.search(query) }, named)
        if (knaben != null && found.values.count { it.seeds > 0 } < 3) {
            search({ knaben.search(searchForm(f.title, keepApostrophes = true) + withYear) }, named)
        }
        if (!answered) throw failure ?: IOException("No search answered.")
        return imdb to rankReleases(found.values.toList())
    }

    private companion object {
        const val ABORT_AFTER = 6
        const val CHECKPOINT_EVERY = 25
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val RETRY_RECENT_MS = 3 * DAY_MS
        const val RETRY_OLD_MS = 30 * DAY_MS
    }
}
