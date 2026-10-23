package com.arcsus.arcdisney.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class CatalogueUpdaterTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val bayQueries = CopyOnWriteArrayList<String>()
    private val knabenQueries = CopyOnWriteArrayList<String>()
    private var bayBlocked = false
    private val now = 1_800_000_000_000L

    private fun bayRow(name: String, hash: Char, seeds: Int, category: String = "207") =
        """{"name":"$name","info_hash":"${hash.toString().repeat(40)}","seeders":"$seeds","size":"2147483648","category":"$category"}"""

    private fun knabenHit(title: String, hash: Char, seeds: Int, live: Int? = null, categories: String = "[3001000,3000000]") =
        """{"title":"$title","hash":"${hash.uppercase().repeat(40)}","seeders":$seeds,"liveSeeders":${live ?: "null"},""" +
            """"bytes":1438814044,"categoryId":$categories,"tracker":"YTS"}"""

    private fun knabenAnswer(query: String): String {
        val hits = when (query) {
            "Hercules 1997" -> listOf(
                knabenHit("Hercules (1997) 1080p BRRip x264 -YTS", 'a', 100, live = 130),
                knabenHit("Hercules (2014) 1080p BrRip x264 - YIFY", 'b', 77),
                knabenHit("Hercules 1997 1998 Animated Series", 'c', 20, categories = "[2000000]"),
            )
            "Tiger 2024" -> listOf(
                knabenHit("Tiger.3.2023.1080p.AMZN.WEB-DL", 'd', 300),
                knabenHit("Tiger.2024.1080p.DSNP.WEB-DL", 'e', 9),
            )
            else -> emptyList()
        }
        return """{"hits":[${hits.joinToString(",")}],"total":{"value":${hits.size}}}"""
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!
                return when (path.encodedPath) {
                    "/q.php" -> {
                        val q = path.queryParameter("q")!!
                        bayQueries += q
                        when {
                            bayBlocked -> MockResponse().setBody("<html>blocked by your ISP</html>")
                            q == "tt0000001" -> MockResponse().setBody(
                                "[${bayRow("Moana.2016.1080p.WEB-DL", 'a', 40)}," +
                                    "${bayRow("Moana.2016.HDCAM", 'b', 99)}," +
                                    "${bayRow("Moana.S01E01.1080p", 'c', 50)}]",
                            )
                            q.startsWith("Wish") -> MockResponse().setBody("[${bayRow("Wish.2023.720p.BluRay", 'd', 12)}]")
                            else -> MockResponse().setBody(
                                """[{"name":"No results returned","info_hash":"0000000000000000000000000000000000000000","seeders":"0","size":"0","category":"0"}]""",
                            )
                        }
                    }
                    "/v1" -> {
                        val q = Regex("\"query\":\"([^\"]*)\"").find(request.body.readUtf8())!!.groupValues[1]
                        knabenQueries += q
                        MockResponse().setBody(knabenAnswer(q))
                    }
                    "/3/discover/movie" -> MockResponse().setBody(
                        """{"page":1,"total_pages":1,"results":[
                            {"id":1,"title":"Moana","release_date":"2016-11-23","poster_path":"/moana.jpg","popularity":90.5},
                            {"id":2,"title":"Wish","release_date":"2023-11-22","poster_path":"/wish.jpg","popularity":40.1}]}""",
                    )
                    "/3/movie/1/external_ids" -> MockResponse().setBody("""{"imdb_id":"tt0000001"}""")
                    "/3/movie/2/external_ids" -> MockResponse().setBody("""{"imdb_id":null}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun updater(withTmdb: Boolean = true, withKnaben: Boolean = false) = CatalogueUpdater(
        bay = PirateBay(client, server.url("/").toString().trimEnd('/')),
        tmdb = if (withTmdb) Tmdb(client, "key", server.url("/3").toString()) else null,
        knaben = if (withKnaben) Knaben(client, server.url("/").toString().trimEnd('/')) else null,
        now = { now },
    )

    @Test
    fun findsNewFilmsAndTheirReleases() = runTest {
        val result = updater().update(Catalogue())
        assertEquals(2, result.newFilms)
        assertEquals(2, result.newlyPlayable)
        assertNull(result.error)

        val moana = result.catalogue.films.first { it.id == 1 }
        assertEquals("tt0000001", moana.imdb)
        // The cam rip and the TV-shaped name are filtered out.
        assertEquals(listOf("a".repeat(40)), moana.releases.map { it.hash })
        assertEquals(now, moana.scannedAt)
        assertEquals(listOf(1, 2), result.catalogue.films.map { it.id }) // most popular first

        val wish = result.catalogue.films.first { it.id == 2 }
        assertTrue(wish.playable)
        assertTrue("Wish 2023" in bayQueries)
    }

    @Test
    fun keepsSeededFilmsAsTheyAre() = runTest {
        val kept = Film(
            id = 1, title = "Moana", year = "2016", popularity = 90.5, scannedAt = 1L, attemptedAt = 1L,
            releases = listOf(Release("Moana.2016.720p", "f".repeat(40), seeds = 5)),
        )
        val result = updater(withTmdb = false).update(Catalogue(films = listOf(kept)))
        assertEquals(kept, result.catalogue.films.single())
        assertTrue(bayQueries.isEmpty())
    }

    @Test
    fun retriesEmptyFilmsOnlyWhenDue() = runTest {
        val dayMs = 24L * 60 * 60 * 1000
        val recentEmpty = Film(id = 3, title = "Zootopia 2", year = "2026", scannedAt = now - dayMs, attemptedAt = now - dayMs)
        val oldEmpty = Film(id = 4, title = "Pollyanna", year = "1960", scannedAt = now - 40 * dayMs, attemptedAt = now - 40 * dayMs)
        updater(withTmdb = false).update(Catalogue(films = listOf(recentEmpty, oldEmpty)))
        assertEquals(listOf("Pollyanna 1960"), bayQueries.toList())
    }

    @Test
    fun blockedSearchesNeverReadAsNoSeeds() = runTest {
        bayBlocked = true
        val films = (1..10).map { Film(id = 100 + it, title = "Film $it", year = "1990") }
        val result = updater(withTmdb = false).update(Catalogue(films = films))
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("blocked"))
        assertTrue(result.catalogue.films.all { it.scannedAt == null })
        // It gives up early instead of trying every film; only the tried ones are marked.
        val tried = result.catalogue.films.filter { it.attemptedAt == now }
        assertTrue(tried.isNotEmpty() && tried.size < films.size)
        assertTrue(bayQueries.size < films.size)
        assertFalse(result.catalogue.films.any { it.playable })
    }

    @Test
    fun knabenFillsInWhereThePirateBayHasNothing() = runTest {
        val hercules = Film(id = 5, title = "Hercules", year = "1997")
        val tiger = Film(id = 6, title = "Tiger", year = "2024")
        val result = updater(withTmdb = false, withKnaben = true).update(Catalogue(films = listOf(hercules, tiger)))
        assertNull(result.error)
        assertEquals(listOf("Hercules 1997", "Tiger 2024"), bayQueries.sorted())
        assertEquals(listOf("Hercules 1997", "Tiger 2024"), knabenQueries.sorted())
        // The 2014 film and the series are left out; YTS's capped seed count gives way to the live one.
        val found = result.catalogue.films.first { it.id == 5 }.releases
        assertEquals(listOf("a".repeat(40)), found.map { it.hash })
        assertEquals(130, found.single().seeds)
        // Tiger 3 (2023) is a different film, however well seeded.
        assertEquals(listOf("e".repeat(40)), result.catalogue.films.first { it.id == 6 }.releases.map { it.hash })
    }

    @Test
    fun knabenStandsInWhenThePirateBayIsBlocked() = runTest {
        bayBlocked = true
        val films = listOf(Film(id = 5, title = "Hercules", year = "1997"))
        val result = updater(withTmdb = false, withKnaben = true).update(Catalogue(films = films))
        assertNull(result.error)
        val hercules = result.catalogue.films.single()
        assertEquals(now, hercules.scannedAt)
        assertTrue(hercules.playable)
    }

    @Test
    fun storeStartsFromTheBundledSnapshotAndMergesNewerOnes() = runTest {
        val dir = Files.createTempDirectory("store").toFile()
        val seeded = Film(id = 1, title = "Moana", scannedAt = 5L, releases = listOf(Release("M", "a".repeat(40), seeds = 3)))
        var shipped = Catalogue(updatedAt = 10L, films = listOf(seeded))
        val store = CatalogueStore(File(dir, "catalogue.json")) {
            ByteArrayInputStream(CatalogueStore.encode(shipped).toByteArray())
        }

        val first = store.load()
        assertEquals(10L, first.bundledAt)
        assertEquals(listOf(seeded), first.films)

        // The phone's own update found nothing for Wish; a newer APK snapshot did.
        val emptyWish = Film(id = 2, title = "Wish", scannedAt = 6L)
        store.save(first.copy(films = first.films + emptyWish))
        val wish = emptyWish.copy(scannedAt = 20L, releases = listOf(Release("W", "b".repeat(40), seeds = 9)))
        shipped = Catalogue(updatedAt = 30L, films = listOf(seeded, wish))

        val merged = store.load()
        assertEquals(30L, merged.bundledAt)
        assertTrue(merged.films.first { it.id == 2 }.playable)
        dir.deleteRecursively()
    }
}
