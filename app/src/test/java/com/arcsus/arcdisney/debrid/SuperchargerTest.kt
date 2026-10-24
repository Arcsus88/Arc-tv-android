package com.arcsus.arcdisney.debrid

import com.arcsus.arcdisney.data.Film
import com.arcsus.arcdisney.data.Release
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class SuperchargerTest {

    private val server = MockWebServer()
    private val uploaded = CopyOnWriteArrayList<String>()
    private val deleted = CopyOnWriteArrayList<String>()
    private val fetched = CopyOnWriteArrayList<String>()
    private var notPremium = false
    private val dir = Files.createTempDirectory("ready").toFile()
    private val size = 10L * 1024 * 1024

    /** AllDebrid has these releases (by hash); the last one's link won't play from here. */
    private val cached = setOf("a2".repeat(20), "c1".repeat(20), "d1".repeat(20))
    private val deadLink = "d1".repeat(20)

    private fun film(id: Int, vararg hashes: String) = Film(
        id = id,
        title = "Film $id",
        year = "2020",
        releases = hashes.map { Release("Film.$id.2020.1080p", it, size, seeds = 5) },
    )

    // Film 1: its second release is on AllDebrid. Film 2: none. Film 3: its first.
    // Film 4: on AllDebrid, but the link fails.
    private val films = listOf(
        film(1, "a1".repeat(20), "a2".repeat(20), "a3".repeat(20)),
        film(2, "b1".repeat(20), "b2".repeat(20)),
        film(3, "c1".repeat(20), "c2".repeat(20)),
        film(4, "d1".repeat(20)),
    )

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                fun ok(data: String) = MockResponse().setBody("""{"status":"success","data":$data}""")
                val url = request.requestUrl!!
                if (url.encodedPath.startsWith("/dl/")) {
                    fetched += "${url.encodedPath} ${request.getHeader("Range")}"
                    if (url.encodedPath.contains(deadLink)) return MockResponse().setResponseCode(403)
                    return MockResponse().setResponseCode(206).setBody(Buffer().write(ByteArray(Supercharger.PRIME_BYTES.toInt())))
                }
                if (notPremium) {
                    return MockResponse().setBody("""{"status":"error","error":{"code":"MUST_BE_PREMIUM","message":"You must be premium to process this link"}}""")
                }
                return when (url.encodedPath) {
                    "/v4/magnet/upload" -> {
                        val hash = Regex("btih%3A([0-9A-Fa-f]{40})").find(request.body.readUtf8())!!.groupValues[1].lowercase()
                        uploaded += hash
                        ok("""{"magnets":[{"id":"$hash","ready":${hash in cached}}]}""")
                    }
                    "/v4/magnet/delete" -> {
                        deleted += url.queryParameter("id")!!
                        ok("""{"message":"deleted"}""")
                    }
                    "/v4/magnet/files" -> {
                        val id = url.queryParameter("id[]")!!
                        ok("""{"magnets":[{"id":"$id","files":[{"n":"$id.mkv","s":$size,"l":"https://alldebrid.com/f/$id"}]}]}""")
                    }
                    "/v4/link/unlock" -> {
                        val id = url.queryParameter("link")!!.substringAfterLast('/')
                        ok("""{"link":"${server.url("/dl/$id.mkv")}","filename":"$id.mkv","filesize":$size}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private val ready = ReadyFilms(File(dir, "ready.json"))
    private val supercharger get() =
        Supercharger(AllDebrid(OkHttpClient(), server.url("/v4").toString().trimEnd('/')), ready, OkHttpClient())

    @Test
    fun getsEachFilmReadyToPlay() = runTest {
        val progress = CopyOnWriteArrayList<Supercharger.Progress>()
        val result = supercharger.run("KEY", films) { progress += it }

        assertEquals(Supercharger.Progress(done = 4, total = 4, ready = 2), result)
        assertEquals(result, progress.last())

        val one = ready.get(1)!!
        assertEquals("a2".repeat(20), one.hash)
        assertEquals(server.url("/dl/${"a2".repeat(20)}.mkv").toString(), one.stream!!.url)
        assertNull(ready.get(2)!!.hash) // checked: AllDebrid has none of them
        assertEquals("c1".repeat(20), ready.get(3)!!.stream!!.fileName.removeSuffix(".mkv"))
        // On AllDebrid, but its link wouldn't play from here: known, not kept.
        assertEquals(ReadyFilms.Ready("d1".repeat(20), null), ready.get(4))

        // Stops at the first release it has (a3, c2 never asked about). Ones it
        // doesn't have are removed; ones it has stay, for their links.
        assertEquals(6, uploaded.size)
        assertEquals(listOf("a1", "b1", "b2").map { it.repeat(20) }.sorted(), deleted.sorted())
        // The start and end of each film's file, fetched once.
        val primed = fetched.filter { !it.contains(deadLink) }
        assertEquals(4, primed.size)
        assertTrue(primed.any { it.endsWith("bytes=0-${Supercharger.PRIME_BYTES - 1}") })
        assertTrue(primed.any { it.endsWith("bytes=${size - Supercharger.PRIME_BYTES}-${size - 1}") })
        assertTrue(ready.superchargedAt() > 0)
    }

    @Test
    fun anAccountProblemStopsIt() = runTest {
        notPremium = true
        val e = runCatching { supercharger.run("KEY", films) }.exceptionOrNull()
        assertTrue(e is AllDebridException && e.message!!.contains("premium"))
        assertEquals(0L, ready.superchargedAt())
    }

    @Test
    fun oldChecksAndLinksAreLeftAlone() = runTest {
        var now = 1_000L
        val file = File(dir, "old.json")
        val store = ReadyFilms(file, now = { now }, freshMs = 5_000, linkFreshMs = 500)
        val link = DebridStream("https://cdn.example/a.mkv", "a.mkv", 1)
        store.put(1, "a2".repeat(20), link)
        assertEquals(link, store.get(1)!!.stream)
        assertEquals(link, store.anyStream(listOf(9, 1)))
        now += 1_000 // the link has gone stale, the check hasn't
        assertEquals(ReadyFilms.Ready("a2".repeat(20), null), store.get(1))
        assertNull(store.anyStream(listOf(1)))
        now += 5_000
        assertNull(store.get(1))
        // And it all survives a restart.
        store.put(2, null)
        assertNull(ReadyFilms(file, now = { now }).get(2)!!.hash)
    }
}
