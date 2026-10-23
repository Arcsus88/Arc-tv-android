package com.arcsus.arcdisney.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

class StreamServerTest {

    /** Bytes that "arrive" [chunk] at a time while a reader waits for them. */
    private class ArrivingSource(val bytes: ByteArray, val chunk: Int) : StreamSource {
        @Volatile
        var arrived = 0
        override val length = bytes.size.toLong()
        override fun reader() = object : StreamReader {
            override fun awaitReadable(offset: Long): Long {
                while (offset >= arrived) {
                    Thread.sleep(5)
                    arrived = minOf(bytes.size, arrived + chunk)
                }
                return arrived - offset
            }

            override fun read(offset: Long, buf: ByteArray, len: Int): Int {
                System.arraycopy(bytes, offset.toInt(), buf, 0, len)
                return len
            }

            override fun close() = Unit
        }
    }

    private val bytes = Random(7).nextBytes(3 * 1024 * 1024 + 17)

    private fun get(url: String, range: String? = null, method: String = "GET"): Triple<Int, Map<String, String>, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        range?.let { c.setRequestProperty("Range", it) }
        val code = c.responseCode
        val headers = c.headerFields.filterKeys { it != null }.mapValues { it.value.first() }
        val body = if (code < 400) c.inputStream.use { it.readBytes() } else ByteArray(0)
        return Triple(code, headers, body)
    }

    @Test
    fun servesTheWholeFileAsItArrives() {
        StreamServer(ArrivingSource(bytes, 200_000), "Coco.2017.1080p.mkv").use { server ->
            val (code, headers, body) = get(server.url)
            assertEquals(200, code)
            assertEquals("video/x-matroska", headers["Content-Type"])
            assertEquals(bytes.size.toString(), headers["Content-Length"])
            assertArrayEquals(bytes, body)
        }
    }

    @Test
    fun answersRangeRequestsTheWayVlcSeeks() {
        StreamServer(ArrivingSource(bytes, 500_000), "Up.mp4").use { server ->
            val (code, headers, body) = get(server.url, "bytes=2000000-2000999")
            assertEquals(206, code)
            assertEquals("bytes 2000000-2000999/${bytes.size}", headers["Content-Range"])
            assertArrayEquals(bytes.copyOfRange(2_000_000, 2_001_000), body)

            val (tailCode, _, tail) = get(server.url, "bytes=-100")
            assertEquals(206, tailCode)
            assertArrayEquals(bytes.copyOfRange(bytes.size - 100, bytes.size), tail)

            val (openCode, _, open) = get(server.url, "bytes=3000000-")
            assertEquals(206, openCode)
            assertArrayEquals(bytes.copyOfRange(3_000_000, bytes.size), open)
        }
    }

    @Test
    fun headAndBadRanges() {
        StreamServer(ArrivingSource(bytes, 1_000_000), "Cars.avi").use { server ->
            val (code, headers, body) = get(server.url, method = "HEAD")
            assertEquals(200, code)
            assertEquals("bytes", headers["Accept-Ranges"])
            assertEquals(0, body.size)
            assertEquals(416, get(server.url, "bytes=${bytes.size + 5}-").first)
        }
    }

    @Test
    fun parsesRanges() {
        assertEquals(0L..99L, StreamServer.parseRange("bytes=0-99", 1000))
        assertEquals(900L..999L, StreamServer.parseRange("bytes=-100", 1000))
        assertEquals(500L..999L, StreamServer.parseRange("bytes=500-", 1000))
        assertEquals(500L..999L, StreamServer.parseRange("bytes=500-5000", 1000))
        assertNull(StreamServer.parseRange("bytes=1000-", 1000))
        assertNull(StreamServer.parseRange("bytes=abc", 1000))
    }
}
