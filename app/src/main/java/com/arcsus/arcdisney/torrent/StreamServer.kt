package com.arcsus.arcdisney.torrent

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** A file that is still arriving: readers wait for the bytes they need. */
interface StreamSource {
    val length: Long

    /** One reader per HTTP request, so the source knows where each one is reading. */
    fun reader(): StreamReader
}

interface StreamReader : Closeable {
    /**
     * Waits until the byte at [offset] is on disk, then returns how many bytes
     * from there can be read in one go (at least 1), or -1 once the source closes.
     */
    fun awaitReadable(offset: Long): Long

    /** Reads up to [len] already-readable bytes at [offset]. */
    fun read(offset: Long, buf: ByteArray, len: Int): Int
}

/**
 * The film's HTTP address for VLC: a tiny server on this phone's loopback
 * interface that answers range requests (VLC seeks by asking for a new range)
 * and holds each answer until the torrent has fetched the bytes it covers.
 */
class StreamServer(private val source: StreamSource, fileName: String) : Closeable {

    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val contentType = contentTypeFor(fileName)

    @Volatile
    private var closed = false

    val url: String = "http://127.0.0.1:${server.localPort}/" +
        URLEncoder.encode(fileName.substringAfterLast('/'), "UTF-8").replace("+", "%20")

    init {
        thread(name = "stream-accept", isDaemon = true) {
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                clients += socket
                thread(name = "stream-request", isDaemon = true) {
                    try {
                        serve(socket)
                    } catch (_: IOException) {
                        // VLC hung up (it does on every seek); nothing to tell anyone.
                    } finally {
                        clients -= socket
                        runCatching { socket.close() }
                    }
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
        }
        val method = requestLine.substringBefore(' ')
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        if (method != "GET" && method != "HEAD") {
            writeHead(out, "405 Method Not Allowed", listOf("Content-Length" to "0"))
            return
        }

        val total = source.length
        val rangeHeader = headers["range"]
        val range = if (rangeHeader == null) 0L until total else parseRange(rangeHeader, total)
        if (range == null) {
            writeHead(out, "416 Range Not Satisfiable", listOf("Content-Range" to "bytes */$total", "Content-Length" to "0"))
            return
        }
        val fields = mutableListOf(
            "Content-Type" to contentType,
            "Content-Length" to (range.last - range.first + 1).toString(),
            "Accept-Ranges" to "bytes",
        )
        if (rangeHeader != null) fields += "Content-Range" to "bytes ${range.first}-${range.last}/$total"
        writeHead(out, if (rangeHeader != null) "206 Partial Content" else "200 OK", fields)
        if (method == "HEAD") return

        socket.soTimeout = 0
        source.reader().use { reader ->
            val buf = ByteArray(256 * 1024)
            var pos = range.first
            while (pos <= range.last && !closed) {
                val readable = reader.awaitReadable(pos)
                if (readable < 0) break
                val want = minOf(readable, range.last - pos + 1, buf.size.toLong()).toInt()
                val n = reader.read(pos, buf, want)
                if (n <= 0) break
                out.write(buf, 0, n)
                pos += n
            }
            out.flush()
        }
    }

    private fun writeHead(out: OutputStream, status: String, fields: List<Pair<String, String>>) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            for ((k, v) in fields) append(k).append(": ").append(v).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        clients.forEach { runCatching { it.close() } }
    }

    companion object {
        /** "bytes=a-b", "bytes=a-" or "bytes=-n"; null when it can't be satisfied. */
        fun parseRange(header: String, total: Long): LongRange? {
            if (total <= 0) return null
            val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            val range = when {
                a.isEmpty() -> {
                    val n = b.toLongOrNull() ?: return null
                    if (n <= 0) return null
                    (total - n).coerceAtLeast(0) until total
                }
                else -> {
                    val start = a.toLongOrNull() ?: return null
                    val end = if (b.isEmpty()) total - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(total - 1)
                    start..end
                }
            }
            return if (range.first < 0 || range.first >= total || range.last < range.first) null else range
        }

        fun contentTypeFor(name: String): String = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "ts", "m2ts" -> "video/mp2t"
            else -> "application/octet-stream"
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                if (sb.length > 8192) throw IOException("Header line too long.")
                sb.append(c.toChar())
            }
        }
    }
}
