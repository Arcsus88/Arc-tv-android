package com.arcsus.arcdisney.torrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.session_handle
import org.libtorrent4j.swig.settings_pack
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.util.BitSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Where a film is on its way to the player. */
sealed interface StreamState {
    /** Looking for peers and fetching the torrent's file list. */
    data object Connecting : StreamState

    /** Fetching the start and end of the film before VLC opens it. */
    data class Buffering(val fraction: Float) : StreamState

    data class Ready(val url: String, val fileName: String) : StreamState

    data class Failed(val message: String) : StreamState
}

data class StreamStats(
    val peers: Int = 0,
    val seeds: Int = 0,
    val bytesPerSecond: Long = 0,
    /** Share of the film's file on the phone so far. */
    val fileProgress: Float = 0f,
    val fileSize: Long = 0,
)

/**
 * libtorrent on the phone: one session for the app, one torrent at a time
 * in practice. Each film streams from [workDir] and is deleted when it closes.
 *
 * A cold session is slow to find anyone sharing a film: its DHT starts with
 * no nodes. So the session starts as soon as the app is on screen
 * ([setForeground]) and stays up while it is, and the DHT's nodes are kept
 * in [Config.stateFile] for the next start.
 */
class TorrentEngine(
    private val workDir: File,
    private val config: Config = Config(),
) {
    data class Config(
        val listenInterfaces: String = "0.0.0.0:0,[::]:0",
        val dht: Boolean = true,
        val lsd: Boolean = true,
        val portMapping: Boolean = true,
        /** No file list within this long: nobody is sharing the release. */
        val metadataTimeoutMs: Long = 45_000,
        /** No new bytes for this long before VLC opens: the swarm is dead. */
        val stallTimeoutMs: Long = 60_000,
        /** Off screen with no film open this long, libtorrent (DHT and all) stops until it's needed. */
        val idleStopMs: Long = 60_000,
        /** Where the session's state (the DHT's nodes above all) is kept between runs; null keeps none. */
        val stateFile: File? = null,
    )

    private val session = SessionManager(false)
    private val streams = ConcurrentHashMap<String, TorrentStream>()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleStop: Job? = null
    private var saver: Job? = null

    @Volatile
    private var foreground = false

    // Stopping deletes the native session; a call into a torrent at that moment
    // crashes the process. Torrent calls run under the read lock and skip while
    // a stop is under way; the stop waits for those in flight.
    private val gate = ReentrantReadWriteLock()

    @Volatile
    private var stopping = false

    /** Runs [block] against the live session, or returns null when there isn't one. */
    internal fun <T> live(block: () -> T): T? {
        if (stopping || !gate.readLock().tryLock()) return null
        try {
            if (stopping || !session.isRunning) return null
            return runCatching(block).getOrNull()
        } finally {
            gate.readLock().unlock()
        }
    }

    private fun stopSession() {
        saver?.cancel()
        saveState()
        stopping = true
        gate.writeLock().lock()
        try {
            if (session.isRunning) session.stop()
        } finally {
            gate.writeLock().unlock()
            stopping = false
        }
    }

    private val listener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.PIECE_FINISHED.swig(),
            AlertType.TORRENT_ERROR.swig(),
            AlertType.FILE_ERROR.swig(),
            AlertType.METADATA_FAILED.swig(),
        )

        override fun alert(alert: Alert<*>) {
            if (stopping) return
            val handle = (alert as? TorrentAlert<*>)?.handle() ?: return
            val stream = streams[hexOf(handle)] ?: return
            when (alert.type()) {
                AlertType.METADATA_RECEIVED -> stream.onMetadata()
                AlertType.PIECE_FINISHED -> stream.onPiece((alert as PieceFinishedAlert).pieceIndex())
                else -> stream.fail(alert.message())
            }
        }
    }

    init {
        session.addListener(listener)
    }

    @Synchronized
    private fun ensureStarted() {
        if (session.isRunning) return
        // Anything left from a film the app was killed during.
        workDir.deleteRecursively()
        workDir.mkdirs()
        val settings = SettingsPack()
            .listenInterfaces(config.listenInterfaces)
            .alertQueueSize(10_000)
            .connectionsLimit(200)
            .activeDownloads(2)
        settings.setEnableDht(config.dht)
        settings.setEnableLsd(config.lsd)
        settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), config.portMapping)
        settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), config.portMapping)
        val params = savedState()?.apply {
            setSettings(settings)
            setPosixDiskIO()
        } ?: posixSession(settings)
        session.start(params)
        // Kept now and then too, in case the app is killed rather than stopped.
        saver = scope.launch {
            delay(FIRST_SAVE_MS)
            while (true) {
                saveState()
                delay(SAVE_EVERY_MS)
            }
        }
    }

    private fun savedState(): SessionParams? {
        val file = config.stateFile?.takeIf { it.isFile } ?: return null
        return runCatching { SessionParams(file.readBytes()) }.getOrNull()
    }

    private val saving = Any()

    private fun saveState() {
        val file = config.stateFile ?: return
        synchronized(saving) {
            val state = live { session.saveState() } ?: return
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeBytes(state)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }

    /**
     * On screen, the session starts (if it hasn't) and stays up between films;
     * off screen, it stops [Config.idleStopMs] after the last film closes.
     */
    fun setForeground(on: Boolean) {
        foreground = on
        scope.launch { if (on) startIfForeground() else scheduleIdleStop() }
    }

    @Synchronized
    private fun startIfForeground() {
        if (!foreground) return
        idleStop?.cancel()
        ensureStarted()
    }

    @Synchronized
    private fun scheduleIdleStop() {
        if (foreground || streams.isNotEmpty() || !session.isRunning) return
        idleStop?.cancel()
        idleStop = scope.launch {
            delay(config.idleStopMs)
            stopIfIdle()
        }
    }

    /**
     * Starts fetching [magnet]. [peers] are dialled straight away, on top of
     * whatever the trackers and DHT turn up (the tests seed from loopback).
     */
    @Synchronized
    fun open(magnet: String, peers: List<InetSocketAddress> = emptyList()): TorrentStream {
        idleStop?.cancel()
        ensureStarted()
        val params = AddTorrentParams.parseMagnetUri(magnet)
        val hash = params.infoHashes.best.toHex().lowercase(Locale.ROOT)
        streams.remove(hash)?.close()
        val dir = File(workDir, hash).apply { mkdirs() }
        params.savePath = dir.absolutePath
        if (peers.isNotEmpty()) params.peers(peers.map { TcpEndpoint(it.hostString, it.port) })
        val ec = error_code()
        val raw = session.swig().add_torrent(params.swig(), ec)
        if (ec.failed()) throw IOException("Couldn't start the torrent: ${ec.message()}")
        val stream = TorrentStream(this, TorrentHandle(raw), hash, dir)
        streams[hash] = stream
        stream.start()
        return stream
    }

    @Synchronized
    internal fun remove(stream: TorrentStream) {
        streams.remove(stream.hash, stream)
        live { if (stream.handle.isValid) session.remove(stream.handle, session_handle.delete_files) }
        scheduleIdleStop()
    }

    @Synchronized
    private fun stopIfIdle() {
        if (streams.isEmpty() && !foreground) stopSession()
    }

    internal val running: Boolean get() = session.isRunning

    @Synchronized
    fun shutdown() {
        idleStop?.cancel()
        streams.values.toList().forEach { it.close() }
        stopSession()
        scope.cancel()
        workDir.deleteRecursively()
    }

    private fun hexOf(handle: TorrentHandle): String =
        runCatching { handle.infoHash().toHex().lowercase(Locale.ROOT) }.getOrDefault("")

    internal val stallTimeoutMs get() = config.stallTimeoutMs

    companion object {
        /**
         * Plain read/write storage instead of libtorrent's memory-mapped default.
         * The mmap storage installs its own SIGSEGV/SIGBUS handler, which kills
         * the process on faults the runtime would have handled itself (a desktop
         * JVM's null checks; ART chains signals so it copes, but there's no need
         * to rely on that). Streaming one film doesn't need mmap's speed.
         */
        fun posixSession(settings: SettingsPack) = SessionParams(settings).apply { setPosixDiskIO() }

        private const val FIRST_SAVE_MS = 2 * 60_000L
        private const val SAVE_EVERY_MS = 10 * 60_000L
    }
    internal val metadataTimeoutMs get() = config.metadataTimeoutMs
}

/**
 * One film's torrent: picks the video file, fetches its start and end so VLC
 * can open it, then serves it over [StreamServer] while it downloads,
 * bringing forward whatever pieces the player is about to read.
 */
class TorrentStream internal constructor(
    private val engine: TorrentEngine,
    internal val handle: TorrentHandle,
    internal val hash: String,
    private val dir: File,
) : StreamSource, Closeable {

    private val _state = MutableStateFlow<StreamState>(StreamState.Connecting)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    private val lock = Object()
    private val have = BitSet()

    @Volatile
    private var closed = false

    // Set once the file list arrives.
    @Volatile
    private var file: File? = null
    private var fileName = ""
    private var fileStart = 0L
    private var fileLength = 0L
    private var pieceLength = 1
    private var firstPiece = 0
    private var lastPiece = 0
    private var prebuffer = IntArray(0)
    private var server: StreamServer? = null

    /** Each open reader's current piece, so the pieces just ahead of all of them come first. */
    private val readers = ConcurrentHashMap<Long, Int>()
    private val readerIds = AtomicLong()

    override val length: Long get() = fileLength

    internal fun start() {
        engine.scope.launch {
            val started = System.currentTimeMillis()
            var lastBytes = -1L
            var lastGain = started
            while (isActive && !closed) {
                val now = System.currentTimeMillis()
                val st = engine.live { handle.status() }
                if (st != null) {
                    val wanted = st.totalWanted()
                    _stats.value = StreamStats(
                        peers = st.numPeers(),
                        seeds = st.numSeeds(),
                        bytesPerSecond = st.downloadPayloadRate().toLong(),
                        fileProgress = if (wanted > 0 && file != null) st.totalWantedDone().toFloat() / wanted else 0f,
                        fileSize = fileLength,
                    )
                    val done = st.totalWantedDone()
                    if (done != lastBytes) {
                        lastBytes = done
                        lastGain = now
                    }
                }
                when (val s = _state.value) {
                    // The file-list alert can come before this stream is registered.
                    StreamState.Connecting -> if (st?.hasMetadata() == true) {
                        onMetadata()
                    } else if (now - started > engine.metadataTimeoutMs) {
                        fail("No one is sharing this release right now.")
                    }
                    is StreamState.Buffering -> {
                        if (now - lastGain > engine.stallTimeoutMs) fail("This release stopped downloading.")
                        else _state.value = s.copy(fraction = prebufferFraction())
                    }
                    else -> Unit
                }
                delay(1_000)
            }
        }
    }

    // The alert thread and the status poll can both get here; once is enough.
    @Synchronized
    internal fun onMetadata() {
        if (closed || file != null) return
        val problem = engine.live { pickFile() } ?: return
        if (problem.isNotEmpty()) return fail(problem)
        _state.value = StreamState.Buffering(0f)
        checkReady()
    }

    /** Chooses the film's file and sets it downloading; returns why it can't, or "". */
    private fun pickFile(): String {
        // Not owned by Java: only good while the torrent is in the session.
        val info = handle.torrentFile() ?: return "This release has no file list."
        val files = info.files()
        val index = (0 until files.numFiles())
            .filter { !files.padFileAt(it) }
            .maxWithOrNull(compareBy<Int> { isVideo(files.fileName(it)) }.thenBy { files.fileSize(it) })
            ?: return "This release has no files."
        if (!isVideo(files.fileName(index))) return "This release has no video in it."

        val size = files.fileSize(index)
        val free = dir.usableSpace
        if (free in 1 until size + 64L * 1024 * 1024) {
            return "It needs ${formatSize(size)} free and the phone has ${formatSize(free)}."
        }

        synchronized(lock) {
            fileName = files.fileName(index)
            fileStart = files.fileOffset(index)
            fileLength = size
            pieceLength = info.pieceLength()
            firstPiece = pieceAt(0)
            lastPiece = pieceAt(size - 1)
            file = File(dir, files.filePath(index))
        }
        handle.prioritizeFiles(Array(files.numFiles()) { if (it == index) Priority.DEFAULT else Priority.IGNORE })
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

        // VLC reads the start, and for most MP4s and MKVs the index at the end,
        // before it shows a frame; fetch both first.
        val headPieces = ((HEAD_BYTES + pieceLength - 1) / pieceLength).coerceAtLeast(2)
        val tailPieces = ((TAIL_BYTES + pieceLength - 1) / pieceLength).coerceAtLeast(1)
        prebuffer = ((firstPiece until firstPiece + headPieces) + ((lastPiece - tailPieces + 1)..lastPiece))
            .filter { it in firstPiece..lastPiece }.distinct().toIntArray()
        prebuffer.forEachIndexed { i, p -> handle.setPieceDeadline(p, i * 200) }
        return ""
    }

    internal fun onPiece(piece: Int) {
        synchronized(lock) {
            have.set(piece)
            lock.notifyAll()
        }
        checkReady()
    }

    private fun checkReady() {
        val srv = synchronized(lock) {
            if (closed || server != null || _state.value !is StreamState.Buffering) return
            if (prebuffer.any { !have.get(it) }) return
            StreamServer(this, fileName).also { server = it }
        }
        _state.value = StreamState.Ready(srv.url, fileName)
    }

    private fun prebufferFraction(): Float =
        if (prebuffer.isEmpty()) 0f else prebuffer.count { hasPiece(it) }.toFloat() / prebuffer.size

    private fun hasPiece(p: Int): Boolean = synchronized(lock) { have.get(p) }

    internal fun fail(message: String) {
        if (closed) return
        val s = _state.value
        if (s is StreamState.Failed) return
        _state.value = StreamState.Failed(message)
    }

    // ---- StreamSource, for the HTTP server ----

    override fun reader(): StreamReader = object : StreamReader {
        private val id = readerIds.incrementAndGet()
        private var raf: RandomAccessFile? = null

        override fun awaitReadable(offset: Long): Long {
            if (offset >= fileLength) return -1
            val p = pieceAt(offset)
            if (readers.put(id, p) != p) prioritize()
            synchronized(lock) {
                var waited = 0L
                while (!closed && !have.get(p)) {
                    lock.wait(250)
                    waited += 250
                    // Alerts can be dropped when the queue overflows; ask libtorrent directly now and then.
                    if (waited % 2_000 == 0L && engine.live { handle.havePiece(p) } == true) have.set(p)
                }
                if (closed) return -1
                var q = p
                while (q < lastPiece && have.get(q + 1) && (q - p) < MAX_RUN_PIECES) q++
                val runEnd = minOf((q.toLong() + 1) * pieceLength - fileStart, fileLength)
                return runEnd - offset
            }
        }

        override fun read(offset: Long, buf: ByteArray, len: Int): Int {
            val f = raf ?: RandomAccessFile(file ?: return -1, "r").also { raf = it }
            f.seek(offset)
            return f.read(buf, 0, len)
        }

        override fun close() {
            runCatching { raf?.close() }
            if (readers.remove(id) != null) prioritize()
        }
    }

    /** Deadlines for the next stretch of the film ahead of every open reader. */
    private fun prioritize() {
        if (closed || file == null) return
        val window = (READ_AHEAD_BYTES / pieceLength).coerceAtLeast(4)
        engine.live {
            handle.clearPieceDeadlines()
            for (start in readers.values.toSet()) {
                var rank = 0
                for (p in start..minOf(start + window, lastPiece)) {
                    if (!hasPiece(p)) handle.setPieceDeadline(p, rank++ * 400)
                }
            }
        }
    }

    private fun pieceAt(offsetInFile: Long): Int = ((fileStart + offsetInFile) / pieceLength).toInt()

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            lock.notifyAll()
        }
        server?.close()
        engine.remove(this)
        // libtorrent deletes the files too; this catches anything it left.
        engine.scope.launch {
            delay(2_000)
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val HEAD_BYTES = 8 * 1024 * 1024
        const val TAIL_BYTES = 2 * 1024 * 1024
        const val READ_AHEAD_BYTES = 48 * 1024 * 1024
        const val MAX_RUN_PIECES = 8
        val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "mov", "wmv", "webm", "ts", "m2ts", "mpg", "mpeg")

        fun isVideo(name: String) = name.substringAfterLast('.').lowercase(Locale.ROOT) in VIDEO

        fun formatSize(bytes: Long): String = String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
