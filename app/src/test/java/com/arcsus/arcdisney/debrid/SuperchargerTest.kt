package com.arcsus.arcdisney.debrid

import com.arcsus.arcdisney.data.Film
import com.arcsus.arcdisney.data.Release
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SuperchargerTest {

    private val server = MockWebServer()
    private val ids = AtomicInteger()
    private val uploaded = CopyOnWriteArrayList<String>()
    private val deleted = CopyOnWriteArrayList<String>()
    private var notPremium = false
    private val dir = Files.createTempDirectory("ready").toFile()

    /** AllDebrid has these releases (by hash). */
    private val cached = setOf("a2".repeat(20), "c1".repeat(20))

    private fun film(id: Int, vararg hashes: String) = Film(
        id = id,
        title = "Film $id",
        year = "2020",
        releases = hashes.map { Release("Film.$id.2020.1080p", it, 1_000_000_000, seeds = 5) },
    )

    // Film 1: its second release is on AllDebrid. Film 2: none. Film 3: its first.
    private val films = listOf(
        film(1, "a1".repeat(20), "a2".repeat(20), "a3".repeat(20)),
        film(2, "b1".repeat(20), "b2".repeat(20)),
        film(3, "c1".repeat(20), "c2".repeat(20)),
    )

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                fun ok(data: String) = MockResponse().setBody("""{"status":"success","data":$data}""")
                if (notPremium) {
                    return MockResponse().setBody("""{"status":"error","error":{"code":"MUST_BE_PREMIUM","message":"You must be premium to process this link"}}""")
                }
                return when (request.requestUrl!!.encodedPath) {
                    "/v4/magnet/upload" -> {
                        val hash = Regex("btih%3A([0-9A-Fa-f]{40})").find(request.body.readUtf8())!!.groupValues[1].lowercase()
                        uploaded += hash
                        ok("""{"magnets":[{"id":"${ids.incrementAndGet()}","ready":${hash in cached}}]}""")
                    }
                    "/v4/magnet/delete" -> {
                        deleted += request.requestUrl!!.queryParameter("id")!!
                        ok("""{"message":"deleted"}""")
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

    private val ready = ReadyFilms(java.io.File(dir, "ready.json"))
    private val supercharger get() = Supercharger(AllDebrid(OkHttpClient(), server.url("/v4").toString().trimEnd('/')), ready)

    @Test
    fun findsWhichReleaseEachFilmHasOnAllDebrid() = runTest {
        val progress = CopyOnWriteArrayList<Supercharger.Progress>()
        val result = supercharger.run("KEY", films) { progress += it }

        assertEquals(Supercharger.Progress(done = 3, total = 3, ready = 2), result)
        assertEquals(Supercharger.Progress(3, 3, 2), progress.last())
        assertEquals("a2".repeat(20), ready.get(1)!!.hash)
        assertNull(ready.get(2)!!.hash) // checked: AllDebrid has none of them
        assertEquals("c1".repeat(20), ready.get(3)!!.hash)
        // Stops at the first it has (a3 and c2 never asked about), and leaves nothing in the account.
        assertEquals(5, uploaded.size)
        assertEquals(uploaded.size, deleted.size)
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
    fun oldChecksAreLeftAlone() = runTest {
        var now = 1_000L
        val store = ReadyFilms(java.io.File(dir, "old.json"), now = { now }, freshMs = 500)
        store.put(1, "a2".repeat(20))
        assertEquals("a2".repeat(20), store.get(1)!!.hash)
        now += 1_000
        assertNull(store.get(1))
        // And it all survives a restart.
        store.put(2, null)
        assertNull(ReadyFilms(java.io.File(dir, "old.json"), now = { now }).get(2)!!.hash)
    }
}
