package com.arcsus.arcdisney.playback

import com.arcsus.arcdisney.data.Film
import com.arcsus.arcdisney.data.Release
import com.arcsus.arcdisney.torrent.StreamState
import com.arcsus.arcdisney.torrent.StreamStats
import com.arcsus.arcdisney.torrent.TorrentEngine
import com.arcsus.arcdisney.torrent.TorrentStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Tapping a poster: the best seeded release starts streaming; if it can't
 * find peers or stalls before VLC opens it, the next one is tried, then the
 * next, so a dead torrent never needs a second tap.
 */
class PlaybackController(
    /** Called off the main thread: it loads libtorrent's native library. */
    engine: () -> TorrentEngine,
    private val scope: CoroutineScope,
) {
    private val engine by lazy(engine)

    sealed interface Phase {
        data object Connecting : Phase
        data class Buffering(val fraction: Float) : Phase
        data class Playing(val url: String, val fileName: String) : Phase
        data class Failed(val message: String) : Phase
    }

    data class Ui(
        val film: Film,
        val release: Release,
        /** 1-based: which of [candidates] releases is being tried. */
        val attempt: Int,
        val candidates: Int,
        val phase: Phase,
        val stats: StreamStats = StreamStats(),
        /** Why an earlier release was skipped, for the launch screen. */
        val note: String? = null,
    ) {
        val canTryAnother: Boolean get() = attempt < candidates
    }

    private val _ui = MutableStateFlow<Ui?>(null)
    val ui: StateFlow<Ui?> = _ui.asStateFlow()

    private var job: Job? = null
    private var stream: TorrentStream? = null
    private var candidates: List<Release> = emptyList()
    private var index = 0

    fun play(film: Film) {
        stop()
        candidates = film.releases.filter { it.seeds > 0 }.take(MAX_TRIES)
        if (candidates.isEmpty()) return
        index = 0
        start(film, note = null)
    }

    /** The launch screen's "Try another release", or VLC failing to open the file. */
    fun tryAnother(reason: String? = null) {
        val ui = _ui.value ?: return
        if (index + 1 >= candidates.size) {
            _ui.value = ui.copy(phase = Phase.Failed(reason ?: "None of its releases would play right now."))
            return
        }
        index++
        start(ui.film, reason)
    }

    /** "Try again" after every release failed: back to the best one. */
    fun retry() {
        val ui = _ui.value ?: return
        index = 0
        start(ui.film, null)
    }

    fun stop() {
        job?.cancel()
        job = null
        val s = stream
        stream = null
        _ui.value = null
        if (s != null) scope.launch(Dispatchers.IO) { s.close() }
    }

    private fun start(film: Film, note: String?) {
        job?.cancel()
        stream?.let { old -> scope.launch(Dispatchers.IO) { old.close() } }
        stream = null
        val release = candidates[index]
        _ui.value = Ui(film, release, index + 1, candidates.size, Phase.Connecting, note = note)
        job = scope.launch {
            val s = try {
                withContext(Dispatchers.IO) { engine.open(release.magnet) }
            } catch (e: IOException) {
                _ui.update { it?.copy(phase = Phase.Failed(e.message ?: "Couldn't start the torrent.")) }
                return@launch
            }
            stream = s
            launch { s.stats.collect { st -> _ui.update { it?.copy(stats = st) } } }
            s.state.collect { st ->
                when (st) {
                    StreamState.Connecting -> _ui.update { it?.copy(phase = Phase.Connecting) }
                    is StreamState.Buffering -> _ui.update { it?.copy(phase = Phase.Buffering(st.fraction)) }
                    is StreamState.Ready -> _ui.update { it?.copy(phase = Phase.Playing(st.url, st.fileName)) }
                    is StreamState.Failed -> {
                        val wasPlaying = _ui.value?.phase is Phase.Playing
                        if (!wasPlaying && index + 1 < candidates.size) {
                            // Leave this collector before starting the next release.
                            scope.launch { tryAnother("${st.message} Trying another release.") }
                        } else {
                            _ui.update { it?.copy(phase = Phase.Failed(st.message)) }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_TRIES = 4
    }
}
