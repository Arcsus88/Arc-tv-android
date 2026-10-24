package com.arcsus.arcdisney.playback

import com.arcsus.arcdisney.data.Film
import com.arcsus.arcdisney.data.Release
import com.arcsus.arcdisney.debrid.AllDebrid
import com.arcsus.arcdisney.debrid.DebridStream
import com.arcsus.arcdisney.debrid.ReadyFilms
import com.arcsus.arcdisney.playback.PlaybackController.Phase
import com.arcsus.arcdisney.playback.PlaybackController.Source
import com.arcsus.arcdisney.torrent.LoopbackSeeder
import com.arcsus.arcdisney.torrent.TorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Which way a film comes in: the device's AllDebrid account first, the torrent otherwise. */
class PlaybackControllerTest {

    private val server = MockWebServer()
    private val uploads = AtomicInteger()
    private var cachedHash = ""
    private var accountProblem = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root = Files.createTempDirectory("playback").toFile()
    private var engine: TorrentEngine? = null
    private val ready = ReadyFilms(File(root, "ready.json"))

    private val film = Film(
        id = 1,
        title = "Coco",
        year = "2017",
        scannedAt = 1,
        releases = listOf(
            Release("Coco.2017.1080p.WEBRip", "a".repeat(40), 1_900_000_000, seeds = 40),
            Release("Coco.2017.720p.BluRay", "b".repeat(40), 900_000_000, seeds = 12),
            Release("Coco.2017.2160p.REMUX", "c".repeat(40), 60_000_000_000, seeds = 0),
        ),
    )

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                fun ok(data: String) = MockResponse().setBody("""{"status":"success","data":$data}""")
                if (accountProblem) {
                    return MockResponse().setBody("""{"status":"error","error":{"code":"AUTH_BAD_APIKEY","message":"The auth apikey is invalid"}}""")
                }
                return when (request.requestUrl!!.encodedPath) {
                    "/v4/magnet/upload" -> {
                        uploads.incrementAndGet()
                        val ready = request.body.readUtf8().contains(cachedHash.uppercase().ifEmpty { "nothing" })
                        ok("""{"magnets":[{"id":"7","ready":$ready}]}""")
                    }
                    "/v4/magnet/files" -> ok("""{"magnets":[{"files":[{"n":"Coco.mkv","s":100,"l":"https://alldebrid.com/f/x"}]}]}""")
                    "/v4/link/unlock" -> ok("""{"link":"https://cdn.example/Coco.mkv","filename":"Coco.mkv"}""")
                    else -> ok("{}")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        engine?.shutdown()
        server.shutdown()
        root.deleteRecursively()
    }

    private fun controller(key: String) = PlaybackController(
        engine = { LoopbackSeeder.engine(root, metadataTimeoutMs = 60_000).also { engine = it } },
        scope = scope,
        allDebrid = AllDebrid(OkHttpClient(), server.url("/v4").toString().trimEnd('/')),
        allDebridKey = { key },
        ready = ready,
    )

    private fun PlaybackController.await(test: (PlaybackController.Ui) -> Boolean) = runBlocking {
        withTimeout(20_000) { ui.first { it != null && test(it) }!! }
    }

    @Test
    fun aCachedReleasePlaysFromAllDebridWithoutTheTorrent() {
        cachedHash = "b".repeat(40) // AllDebrid has the second-best release, not the first
        val c = controller("KEY")
        c.play(film)
        val ui = c.await { it.phase is Phase.Playing }
        assertEquals(Source.ALLDEBRID, ui.source)
        assertEquals("https://cdn.example/Coco.mkv", (ui.phase as Phase.Playing).url)
        assertEquals("Coco.2017.720p.BluRay", ui.release.name)
        assertEquals(null, engine) // libtorrent never started
        assertEquals("b".repeat(40), runBlocking { ready.get(1) }!!.hash) // remembered for next time
        c.stop()
    }

    @Test
    fun aReleaseKnownToBeOnAllDebridIsAskedForFirst() {
        cachedHash = "b".repeat(40)
        runBlocking { ready.put(1, "b".repeat(40)) } // supercharged
        val c = controller("KEY")
        c.play(film)
        val ui = c.await { it.phase is Phase.Playing }
        assertEquals(Source.ALLDEBRID, ui.source)
        assertEquals(1, uploads.get()) // straight to it: the first release wasn't asked about
        c.stop()
    }

    @Test
    fun aSuperchargedFilmPlaysItsLinkWithoutAskingAllDebrid() {
        runBlocking { ready.put(1, "b".repeat(40), DebridStream("https://cdn.example/ready.mkv", "Coco.mkv", 1)) }
        val c = controller("KEY")
        c.play(film)
        val ui = c.await { it.phase is Phase.Playing }
        assertEquals("https://cdn.example/ready.mkv", (ui.phase as Phase.Playing).url)
        assertTrue(ui.prefetched)
        assertEquals("Coco.2017.720p.BluRay", ui.release.name)
        assertEquals(0, uploads.get())
        c.stop()
    }

    @Test
    fun aSuperchargedLinkThatWontPlayIsFetchedAgain() {
        cachedHash = "b".repeat(40)
        runBlocking { ready.put(1, "b".repeat(40), DebridStream("https://cdn.example/stale.mkv", "Coco.mkv", 1)) }
        val c = controller("KEY")
        c.play(film)
        c.await { it.prefetched }
        c.tryAnother("VLC couldn't play this release.") // as the player reports it
        val ui = c.await { it.phase is Phase.Playing && !it.prefetched }
        assertEquals("https://cdn.example/Coco.mkv", (ui.phase as Phase.Playing).url)
        assertEquals(Source.ALLDEBRID, ui.source)
        assertEquals(1, uploads.get()) // straight to the release it has
        assertEquals("https://cdn.example/Coco.mkv", runBlocking { ready.get(1) }!!.stream!!.url) // kept for next time
        c.stop()
    }

    @Test
    fun aFilmKnownNotToBeOnAllDebridGoesStraightToTheTorrent() {
        runBlocking { ready.put(1, null) }
        val c = controller("KEY")
        c.play(film)
        val ui = c.await { it.source == Source.TORRENT }
        assertEquals(0, uploads.get())
        assertTrue(ui.note!!.contains("Not ready on AllDebrid"))
        c.stop()
    }

    @Test
    fun nothingCachedFallsBackToTheTorrent() {
        val c = controller("KEY")
        c.play(film)
        val ui = c.await { it.source == Source.TORRENT }
        assertEquals(3, uploads.get()) // every release, seeded or not, was asked about
        assertTrue(ui.note!!.contains("Not ready on AllDebrid"))
        assertEquals(ReadyFilms.Ready(null), runBlocking { ready.get(1) })
        assertEquals("Coco.2017.1080p.WEBRip", ui.release.name)
        assertEquals(2, ui.candidates) // the torrent only tries seeded releases
        c.stop()
    }

    @Test
    fun signedOutGoesStraightToTheTorrent() {
        val c = controller("")
        c.play(film)
        val ui = c.await { it.source == Source.TORRENT }
        assertEquals(0, uploads.get())
        assertEquals(null, ui.note)
        c.stop()
    }

    @Test
    fun anAccountProblemSaysSoAndStillPlays() {
        accountProblem = true
        val c = controller("BADKEY")
        c.play(film)
        val ui = c.await { it.source == Source.TORRENT }
        assertTrue(ui.note!!.contains("The auth apikey is invalid"))
        c.stop()
    }
}
