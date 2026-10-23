package com.arcsus.arcdisney.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

/**
 * The catalogue on the phone: a JSON file in the app's storage, started from
 * the snapshot bundled in the APK (assets/disney.json) so a fresh install has
 * every film and its releases without searching anything.
 */
class CatalogueStore(
    private val file: File,
    private val bundled: () -> InputStream?,
) {
    suspend fun load(): Catalogue = withContext(Dispatchers.IO) {
        val local = if (file.exists()) runCatching { decode(file.readText()) }.getOrNull() else null
        val shipped = runCatching { bundled()?.use { decode(it.readBytes().decodeToString()) } }.getOrNull()
        when {
            local == null -> shipped?.copy(bundledAt = shipped.updatedAt) ?: Catalogue()
            // An app update brought a newer snapshot: take what it found that this phone hasn't.
            shipped != null && shipped.updatedAt > local.bundledAt -> merge(local, shipped)
            else -> local
        }
    }

    suspend fun save(catalogue: Catalogue) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(encode(catalogue))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun decode(text: String): Catalogue = json.decodeFromString(Catalogue.serializer(), text)

        fun encode(catalogue: Catalogue): String = json.encodeToString(Catalogue.serializer(), catalogue)

        /** Films the phone lacks, and releases for films it couldn't play, come from [shipped]. */
        fun merge(local: Catalogue, shipped: Catalogue): Catalogue {
            val films = local.films.associateByTo(LinkedHashMap()) { it.id }
            for (s in shipped.films) {
                val l = films[s.id]
                films[s.id] = when {
                    l == null -> s
                    !l.playable && s.playable ->
                        l.copy(imdb = l.imdb ?: s.imdb, scannedAt = s.scannedAt, attemptedAt = s.attemptedAt, releases = s.releases)
                    else -> l
                }
            }
            return local.copy(
                bundledAt = shipped.updatedAt,
                films = films.values.sortedByDescending { it.popularity },
            )
        }
    }
}
