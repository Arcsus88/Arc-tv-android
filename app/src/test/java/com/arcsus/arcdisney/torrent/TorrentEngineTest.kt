package com.arcsus.arcdisney.torrent

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.random.Random

/**
 * The real engine against a real libtorrent seeder on loopback: magnet in,
 * file list fetched from the peer, start and end buffered, then the film
 * read back over HTTP, byte for byte, including a seek.
 */
class TorrentEngineTest {

    private lateinit var root: File
    private lateinit var seeder: LoopbackSeeder
    private lateinit var engine: TorrentEngine
    private lateinit var film: ByteArray

    @Before
    fun setUp() {
        root = Files.createTempDirectory("engine").toFile()
        film = Random(42).nextBytes(12 * 1024 * 1024 + 4321)
        // A real-looking release: a folder with the film and a small extra file.
        val release = File(root, "seed/Luca.2021.1080p.WEBRip").apply { mkdirs() }
        File(release, "Luca.2021.1080p.WEBRip.mp4").writeBytes(film)
        File(release, "Luca.2021.1080p.WEBRip.srt").writeText("1\n00:00:01,000 --> 00:00:02,000\nCiao!\n")
        seeder = LoopbackSeeder(release)
        engine = LoopbackSeeder.engine(File(root, "work"))
    }

    @After
    fun tearDown() {
        engine.shutdown()
        seeder.close()
        root.deleteRecursively()
    }

    private fun get(url: String, range: String? = null): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        range?.let { c.setRequestProperty("Range", it) }
        return c.responseCode to c.inputStream.use { it.readBytes() }
    }

    @Test
    fun streamsAMagnetFromAPeer() = runBlocking {
        val stream = engine.open(seeder.magnet, listOf(seeder.peer))

        val ready = withTimeout(60_000) {
            stream.state.first { it is StreamState.Ready || it is StreamState.Failed }
        }
        assertTrue("expected Ready, got $ready", ready is StreamState.Ready)
        ready as StreamState.Ready
        // The video, not the subtitle file.
        assertEquals("Luca.2021.1080p.WEBRip.mp4", ready.fileName)
        assertEquals(film.size.toLong(), stream.length)

        // A seek into the middle, as VLC does, then the whole file.
        val (code, middle) = get(ready.url, "bytes=6000000-6099999")
        assertEquals(206, code)
        assertArrayEquals(film.copyOfRange(6_000_000, 6_100_000), middle)
        val (full, all) = get(ready.url)
        assertEquals(200, full)
        assertArrayEquals(film, all)

        stream.close()
    }

    @Test
    fun givesUpWhenNobodyShares() = runBlocking {
        val lonely = LoopbackSeeder.engine(File(root, "lonely"), metadataTimeoutMs = 2_000)
        try {
            val stream = lonely.open("magnet:?xt=urn:btih:${"ab".repeat(20)}&dn=Nobody")
            val state = withTimeout(20_000) { stream.state.first { it is StreamState.Failed } }
            assertTrue((state as StreamState.Failed).message.contains("No one is sharing"))
        } finally {
            lonely.shutdown()
        }
    }
}
