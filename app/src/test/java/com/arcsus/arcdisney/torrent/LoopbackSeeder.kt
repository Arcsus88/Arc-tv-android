package com.arcsus.arcdisney.torrent

import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentInfo
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest

/** A libtorrent session on 127.0.0.1 seeding [release] (a file or a folder). */
class LoopbackSeeder(release: File, pieceSize: Int = 256 * 1024) : Closeable {
    val info: TorrentInfo = TorrentInfo(torrentFile(release, pieceSize))
    val port = freePort()
    val peer get() = InetSocketAddress("127.0.0.1", port)
    val magnet get() = "magnet:?xt=urn:btih:${info.infoHash().toHex()}&dn=test"

    private val session = SessionManager(false)

    init {
        val settings = SettingsPack().listenInterfaces("127.0.0.1:$port")
        settings.setEnableDht(false)
        settings.setEnableLsd(false)
        session.start(TorrentEngine.posixSession(settings))
        session.download(info, release.parentFile)
    }

    override fun close() = session.stop()

    companion object {
        fun freePort() = ServerSocket(0).use { it.localPort }

        /** An engine that only talks to loopback peers. */
        fun engine(dir: File, metadataTimeoutMs: Long = 45_000) = TorrentEngine(
            dir,
            TorrentEngine.Config(
                listenInterfaces = "127.0.0.1:${freePort()}",
                dht = false,
                lsd = false,
                portMapping = false,
                metadataTimeoutMs = metadataTimeoutMs,
            ),
        )

        /**
         * A v1 .torrent for [release], built here rather than with libtorrent's
         * TorrentBuilder: that hashes through the mmap storage, whose signal
         * handler takes SIGSEGV away from the test JVM.
         */
        fun torrentFile(release: File, pieceSize: Int): ByteArray {
            val files = if (release.isDirectory) release.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList() else listOf(release)
            val sha1 = MessageDigest.getInstance("SHA-1")
            val pieces = ByteArrayOutputStream()
            val piece = ByteArray(pieceSize)
            var filled = 0
            for (f in files) {
                f.inputStream().use { input ->
                    while (true) {
                        val n = input.read(piece, filled, pieceSize - filled)
                        if (n < 0) break
                        filled += n
                        if (filled == pieceSize) {
                            pieces.write(sha1.digest(piece))
                            filled = 0
                        }
                    }
                }
            }
            if (filled > 0) pieces.write(sha1.digest(piece.copyOf(filled)))

            val info = sortedMapOf<String, Any>(
                "name" to release.name,
                "piece length" to pieceSize.toLong(),
                "pieces" to pieces.toByteArray(),
            )
            if (release.isDirectory) {
                info["files"] = files.map { f ->
                    sortedMapOf<String, Any>(
                        "length" to f.length(),
                        "path" to f.relativeTo(release).invariantSeparatorsPath.split('/'),
                    )
                }
            } else {
                info["length"] = release.length()
            }
            return bencode(sortedMapOf<String, Any>("info" to info))
        }

        private fun bencode(value: Any): ByteArray = ByteArrayOutputStream().also { encode(value, it) }.toByteArray()

        private fun encode(v: Any, out: ByteArrayOutputStream) {
            when (v) {
                is Long -> out.write("i${v}e".toByteArray())
                is Int -> out.write("i${v}e".toByteArray())
                is String -> v.toByteArray().let { out.write("${it.size}:".toByteArray()); out.write(it) }
                is ByteArray -> { out.write("${v.size}:".toByteArray()); out.write(v) }
                is List<*> -> { out.write('l'.code); v.forEach { encode(it!!, out) }; out.write('e'.code) }
                is Map<*, *> -> {
                    out.write('d'.code)
                    v.entries.sortedBy { it.key as String }.forEach { (k, x) -> encode(k!!, out); encode(x!!, out) }
                    out.write('e'.code)
                }
                else -> error("can't bencode $v")
            }
        }
    }
}
