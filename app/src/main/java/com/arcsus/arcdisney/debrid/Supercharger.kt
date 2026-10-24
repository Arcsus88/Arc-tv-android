package com.arcsus.arcdisney.debrid

import com.arcsus.arcdisney.data.Film
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * "Supercharging your streams": asks AllDebrid ahead of time which release
 * of each film it already has, so those films start at once when picked
 * rather than after a wait on the launch screen. A film's releases are asked
 * about best first, stopping at the first AllDebrid has. An account problem
 * or a lost connection stops the run (as an IOException); what was found by
 * then is kept.
 */
class Supercharger(
    private val allDebrid: AllDebrid,
    private val ready: ReadyFilms,
    private val concurrency: Int = 3,
) {
    data class Progress(val done: Int, val total: Int, val ready: Int)

    suspend fun run(key: String, films: List<Film>, onProgress: (Progress) -> Unit = {}): Progress {
        val lock = Mutex()
        var done = 0
        var found = 0
        onProgress(Progress(0, films.size, 0))
        coroutineScope {
            val permits = Semaphore(concurrency)
            films.map { film ->
                async {
                    permits.withPermit {
                        val hash = film.releases.take(RELEASES_PER_FILM).firstOrNull { allDebrid.isCached(key, it.magnet) }?.hash
                        ready.put(film.id, hash)
                        lock.withLock {
                            done++
                            if (hash != null) found++
                            onProgress(Progress(done, films.size, found))
                        }
                    }
                }
            }.awaitAll()
        }
        ready.markSupercharged()
        return Progress(done, films.size, found)
    }

    companion object {
        /** As many as Play asks AllDebrid about. */
        const val RELEASES_PER_FILM = 5
    }
}
