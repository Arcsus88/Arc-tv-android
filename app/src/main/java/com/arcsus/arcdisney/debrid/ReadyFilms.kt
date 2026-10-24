package com.arcsus.arcdisney.debrid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Which release of each film AllDebrid has, as last checked, and (from
 * supercharging) a link to it that's ready to play, kept in a small file:
 * Play goes straight to the link, or to that release, or straight to the
 * torrent when AllDebrid has none, instead of asking about each release
 * first. A check older than [freshMs] (a link older than [linkFreshMs]) is
 * left alone, as AllDebrid's cache changes and its links don't last.
 */
class ReadyFilms(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val freshMs: Long = 12 * 60 * 60 * 1000L,
    private val linkFreshMs: Long = LINK_FRESH_MS,
) {
    /**
     * [hash] is the release AllDebrid has, or null when it has none of them;
     * [stream], when there's one, is that release ready to play.
     */
    data class Ready(val hash: String?, val stream: DebridStream? = null)

    @Serializable
    private data class Entry(
        val hash: String? = null,
        val at: Long = 0,
        val link: String? = null,
        val file: String? = null,
        val size: Long = 0,
        val linkAt: Long = 0,
    )

    @Serializable
    private data class Saved(val superchargedAt: Long = 0, val films: Map<Int, Entry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var saved: Saved? = null

    /** What's known about [filmId], or null when it hasn't been checked lately. */
    suspend fun get(filmId: Int): Ready? = lock.withLock {
        val e = load().films[filmId] ?: return null
        if (now() - e.at > freshMs) return null
        Ready(e.hash, e.stream())
    }

    /** [stream] is the release ready to play, when there is one. */
    suspend fun put(filmId: Int, hash: String?, stream: DebridStream? = null) = lock.withLock {
        val s = load()
        val at = now()
        val entry = Entry(hash, at, stream?.url, stream?.fileName, stream?.size ?: 0, if (stream != null) at else 0)
        write(s.copy(films = s.films + (filmId to entry)))
    }

    /** A link that's no longer any good: the film's release is still known, the link isn't kept. */
    suspend fun dropLink(filmId: Int) = lock.withLock {
        val s = load()
        val e = s.films[filmId] ?: return@withLock
        write(s.copy(films = s.films + (filmId to e.copy(link = null, file = null, size = 0, linkAt = 0))))
    }

    /** Any film's link that's still fresh, best known first. */
    suspend fun anyStream(order: List<Int>): DebridStream? = lock.withLock {
        val films = load().films
        order.firstNotNullOfOrNull { films[it]?.stream() }
    }

    private fun Entry.stream(): DebridStream? =
        if (link != null && now() - linkAt <= linkFreshMs) DebridStream(link, file.orEmpty(), size) else null

    /** When the films were last supercharged, 0 if never. */
    suspend fun superchargedAt(): Long = lock.withLock { load().superchargedAt }

    suspend fun markSupercharged() = lock.withLock { write(load().copy(superchargedAt = now())) }

    /** Signed out: none of it applies to the next account. */
    suspend fun clear() = lock.withLock { write(Saved()) }

    private suspend fun load(): Saved = saved ?: withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(Saved.serializer(), file.readText()) }.getOrDefault(Saved())
    }.also { saved = it }

    private suspend fun write(s: Saved) {
        saved = s
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json.encodeToString(Saved.serializer(), s))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }

    companion object {
        /** AllDebrid's links don't last for ever; past this, a film's is fetched again. */
        const val LINK_FRESH_MS = 3 * 60 * 60 * 1000L
    }
}
