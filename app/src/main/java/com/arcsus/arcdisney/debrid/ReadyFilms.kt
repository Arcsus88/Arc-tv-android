package com.arcsus.arcdisney.debrid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Which release of each film AllDebrid has, as last checked, kept in a small
 * file: Play goes straight to that release, or straight to the torrent when
 * AllDebrid has none, instead of asking about each release first. A check
 * older than [freshMs] is left alone, as AllDebrid's cache changes.
 */
class ReadyFilms(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val freshMs: Long = 12 * 60 * 60 * 1000L,
) {
    /** [hash] is the release AllDebrid has, or null when it has none of them. */
    data class Ready(val hash: String?)

    @Serializable
    private data class Entry(val hash: String? = null, val at: Long = 0)

    @Serializable
    private data class Saved(val superchargedAt: Long = 0, val films: Map<Int, Entry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var saved: Saved? = null

    /** What's known about [filmId], or null when it hasn't been checked lately. */
    suspend fun get(filmId: Int): Ready? = lock.withLock {
        val e = load().films[filmId] ?: return null
        if (now() - e.at > freshMs) null else Ready(e.hash)
    }

    suspend fun put(filmId: Int, hash: String?) = lock.withLock {
        val s = load()
        write(s.copy(films = s.films + (filmId to Entry(hash, now()))))
    }

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
}
