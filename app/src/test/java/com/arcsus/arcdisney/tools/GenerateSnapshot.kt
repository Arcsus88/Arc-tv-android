package com.arcsus.arcdisney.tools

import com.arcsus.arcdisney.data.Catalogue
import com.arcsus.arcdisney.data.CatalogueStore
import com.arcsus.arcdisney.data.CatalogueUpdater
import com.arcsus.arcdisney.data.Film
import com.arcsus.arcdisney.data.Knaben
import com.arcsus.arcdisney.data.PirateBay
import com.arcsus.arcdisney.data.Tmdb
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds the catalogue snapshot bundled in the APK (src/main/assets/disney.json)
 * by running the app's own update on this machine. Opt-in, as it searches the
 * real Pirate Bay:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*GenerateSnapshot*' -DgenerateSnapshot=true
 *
 * With TMDB_API_KEY set it also asks TMDB for new films. -DsnapshotSeed=films.tsv
 * adds films from a tab-separated list (tmdb id, year, imdb id, poster path,
 * popularity, title). An existing snapshot is updated in place: films with
 * seeded releases keep them, the rest are searched (-DsnapshotRetry=true
 * searches films that came up empty again now, rather than when due).
 */
class GenerateSnapshot {

    @Test
    fun generate() {
        assumeTrue(System.getProperty("generateSnapshot") == "true")
        val asset = File("src/main/assets/disney.json")
        val log = File("build/snapshot.log").apply { parentFile?.mkdirs(); writeText("") }

        var catalogue = if (asset.exists()) CatalogueStore.decode(asset.readText()) else Catalogue()
        System.getProperty("snapshotSeed")?.let { path ->
            val known = catalogue.films.associateBy { it.id }
            val seeded = File(path).readLines().mapNotNull { line ->
                val c = line.split('\t')
                val id = c.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                known[id] ?: Film(
                    id = id,
                    year = c[1],
                    imdb = c[2].ifBlank { null },
                    poster = c[3],
                    popularity = c[4].toDoubleOrNull() ?: 0.0,
                    title = c[5],
                )
            }
            val seededIds = seeded.mapTo(HashSet()) { it.id }
            catalogue = catalogue.copy(films = seeded + catalogue.films.filter { it.id !in seededIds })
        }

        if (System.getProperty("snapshotRetry") == "true") {
            catalogue = catalogue.copy(films = catalogue.films.map { if (it.seeded) it else it.copy(scannedAt = null) })
        }

        val client = OkHttpClient.Builder().readTimeout(40, TimeUnit.SECONDS).build()
        val tmdb = System.getenv("TMDB_API_KEY")?.takeIf { it.isNotBlank() }?.let { Tmdb(client, it) }
        val limit = System.getProperty("snapshotLimit")?.toIntOrNull() ?: Int.MAX_VALUE
        val updater = CatalogueUpdater(PirateBay(client), tmdb, Knaben(client), concurrency = 3)

        val result = runBlocking {
            updater.update(
                catalogue,
                limit = limit,
                onProgress = { log.appendText("${it.phase} ${it.done}/${it.total}\n") },
                onCheckpoint = { asset.writeText(CatalogueStore.encode(it)) },
            )
        }
        asset.parentFile?.mkdirs()
        asset.writeText(CatalogueStore.encode(result.catalogue))
        val films = result.catalogue.films
        log.appendText(
            "done: films=${films.size} playable=${films.count { it.playable }} searched=${result.searched} " +
                "failed=${result.failed} new=${result.newFilms} error=${result.error}\n",
        )
    }
}
