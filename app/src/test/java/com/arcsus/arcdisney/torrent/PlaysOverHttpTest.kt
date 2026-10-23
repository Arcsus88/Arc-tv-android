package com.arcsus.arcdisney.torrent

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A real video, streamed by torrent and decoded by a real player over the
 * app's HTTP address. ffmpeg's HTTP input reads by range requests the way
 * VLC's does, so this covers the hand-off the phone makes, including an MP4
 * whose index sits at the end and a seek past what has downloaded.
 * Skipped where ffmpeg isn't installed.
 */
class PlaysOverHttpTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        assumeTrue(runCatching { run("ffmpeg", "-version").first == 0 }.getOrDefault(false))
        root = Files.createTempDirectory("plays").toFile()
    }

    @After
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(180, TimeUnit.SECONDS)) { "timed out: ${cmd.joinToString(" ")}" }
        return p.exitValue() to out
    }

    @Test
    fun ffmpegPlaysAndSeeksTheStream() = runBlocking {
        // 40 s of H.264 + AAC; MP4 without faststart keeps its index ("moov") at the end.
        val release = File(root, "seed/Bolt.2008.720p.WEB").apply { mkdirs() }
        val video = File(release, "Bolt.2008.720p.WEB.mp4")
        val (made, log) = run(
            "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=duration=40:size=1280x720:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=40",
            "-c:v", "libx264", "-preset", "veryfast", "-b:v", "3M", "-c:a", "aac", "-shortest", video.path,
        )
        assertEquals(log, 0, made)

        LoopbackSeeder(release).use { seeder ->
            val engine = LoopbackSeeder.engine(File(root, "work"))
            try {
                val stream = engine.open(seeder.magnet, listOf(seeder.peer))
                val ready = withTimeout(60_000) {
                    stream.state.first { it is StreamState.Ready || it is StreamState.Failed }
                }
                assertTrue("expected Ready, got $ready", ready is StreamState.Ready)
                val url = (ready as StreamState.Ready).url

                // Seek to 30 s first (as a viewer skipping ahead would), then play it all.
                val (seekCode, seekLog) = run("ffmpeg", "-v", "error", "-ss", "30", "-i", url, "-t", "3", "-f", "null", "-")
                assertEquals(seekLog, 0, seekCode)
                val (code, playLog) = run("ffmpeg", "-v", "error", "-i", url, "-f", "null", "-")
                assertEquals(playLog, 0, code)
                assertTrue("decoder complained: $playLog", playLog.isBlank())

                val (_, probe) = run("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", url)
                assertEquals(40.0, probe.trim().toDouble(), 0.5)
                stream.close()
            } finally {
                engine.shutdown()
            }
        }
    }
}
