package com.arcsus.arcdisney.debrid

import com.arcsus.arcdisney.data.Film
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * "Supercharging your streams": gets films ready to play from AllDebrid ahead
 * of time, so that wait happens once, on the supercharge screen, rather than
 * on the launch screen each time. For each film: the first of its releases
 * AllDebrid has (best first; ones it hasn't are removed from the account
 * again, ones it has are kept), that release's file unlocked for this device,
 * and the start and end of the file fetched once, which checks the link
 * plays from here and gets AllDebrid's servers sending it. Play then hands
 * the link straight to VLC. An account problem or a lost connection stops the
 * run (as an IOException); what was ready by then is kept.
 */
class Supercharger(
    private val allDebrid: AllDebrid,
    private val ready: ReadyFilms,
    http: OkHttpClient,
    private val concurrency: Int = 3,
) {
    /** [ready] films are ready to play: AllDebrid has them and their links work. */
    data class Progress(val done: Int, val total: Int, val ready: Int)

    private val http = http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()

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
                        val playable = prepare(key, film)
                        lock.withLock {
                            done++
                            if (playable) found++
                            onProgress(Progress(done, films.size, found))
                        }
                    }
                }
            }.awaitAll()
        }
        ready.markSupercharged()
        return Progress(done, films.size, found)
    }

    /** True when the film is ready to play straight from AllDebrid. */
    private suspend fun prepare(key: String, film: Film): Boolean {
        for (release in film.releases.take(RELEASES_PER_FILM)) {
            val id = allDebrid.cachedId(key, release.magnet) ?: continue
            val stream = try {
                allDebrid.streamOf(key, id).takeIf { prime(it) }
            } catch (e: NotCachedException) {
                null
            } catch (e: AllDebridException) {
                if (e.isAccountProblem) throw e
                null
            }
            ready.put(film.id, release.hash, stream)
            return stream != null
        }
        ready.put(film.id, null)
        return false
    }

    /** The first and last [PRIME_BYTES] of the file: what VLC reads before it shows a frame. */
    private suspend fun prime(stream: DebridStream): Boolean = withContext(Dispatchers.IO) {
        val ranges = buildList {
            add("bytes=0-${PRIME_BYTES - 1}")
            if (stream.size > 2 * PRIME_BYTES) add("bytes=${stream.size - PRIME_BYTES}-${stream.size - 1}")
        }
        ranges.all { range ->
            try {
                http.newCall(Request.Builder().url(stream.url).header("Range", range).build()).execute().use { r ->
                    r.isSuccessful && r.body?.source()?.let { src ->
                        src.request(PRIME_BYTES)
                        true
                    } == true
                }
            } catch (e: IOException) {
                false
            } catch (e: IllegalArgumentException) {
                false // not a link OkHttp can use
            }
        }
    }

    companion object {
        /** As many as Play asks AllDebrid about. */
        const val RELEASES_PER_FILM = 5
        const val PRIME_BYTES = 256 * 1024L
    }
}
