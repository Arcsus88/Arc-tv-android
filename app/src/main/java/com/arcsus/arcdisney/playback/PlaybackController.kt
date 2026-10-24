package com.arcsus.arcdisney.playback

import com.arcsus.arcdisney.data.Film
import com.arcsus.arcdisney.data.Release
import com.arcsus.arcdisney.debrid.AllDebrid
import com.arcsus.arcdisney.debrid.AllDebridException
import com.arcsus.arcdisney.debrid.NotCachedException
import com.arcsus.arcdisney.debrid.ReadyFilms
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
 * Tapping a poster. On a device signed in to AllDebrid, its cache is asked
 * first: a release it already has streams straight from AllDebrid at full
 * speed. Otherwise (or when it has none of them) the best seeded release
 * streams by torrent; if that can't find peers or stalls before VLC opens it,
 * the next one is tried, then the next, so a dead torrent never needs a
 * second tap.
 */
class PlaybackController(
    /** Called off the main thread: it loads libtorrent's native library. */
    engine: () -> TorrentEngine,
    private val scope: CoroutineScope,
    private val allDebrid: AllDebrid? = null,
    /** This device's own AllDebrid key, "" when it isn't signed in. */
    private val allDebridKey: suspend () -> String = { "" },
    /** Which release of each film AllDebrid has, from supercharging and earlier plays. */
    private val ready: ReadyFilms? = null,
) {
    private val engine by lazy(engine)

    enum class Source { ALLDEBRID, TORRENT }

    sealed interface Phase {
        /** Asking AllDebrid whether it already has one of the releases. */
        data object CheckingDebrid : Phase
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
        val source: Source = Source.TORRENT,
        val stats: StreamStats = StreamStats(),
        /** Why an earlier release (or AllDebrid) was passed over, for the launch screen. */
        val note: String? = null,
        /** Playing a link got ready by supercharging, without asking AllDebrid. */
        val prefetched: Boolean = false,
    ) {
        val canTryAnother: Boolean get() = source == Source.TORRENT && attempt < candidates
    }

    private val _ui = MutableStateFlow<Ui?>(null)
    val ui: StateFlow<Ui?> = _ui.asStateFlow()

    private var lookup: Job? = null
    private var job: Job? = null
    private var stream: TorrentStream? = null
    private var candidates: List<Release> = emptyList()
    private var index = 0

    fun play(film: Film) {
        stop()
        begin(film, linkFailed = false)
    }

    /**
     * A supercharged link plays at once; otherwise AllDebrid is asked, then
     * the torrent. After [linkFailed] (the supercharged link wouldn't play),
     * that link is dropped and AllDebrid is asked afresh.
     */
    private fun begin(film: Film, linkFailed: Boolean) {
        candidates = film.releases.filter { it.seeds > 0 }.take(MAX_TRIES)
        // AllDebrid's cache doesn't care whether anyone is still seeding.
        val cachedCandidates = film.releases.take(MAX_DEBRID_TRIES)
        if (candidates.isEmpty() && cachedCandidates.isEmpty()) return
        index = 0
        lookup = scope.launch {
            val key = allDebridKey()
            val signedIn = allDebrid != null && key.isNotBlank() && cachedCandidates.isNotEmpty()
            if (linkFailed) ready?.dropLink(film.id)
            val known = if (signedIn) ready?.get(film.id) else null
            val link = known?.stream
            if (link != null) {
                val release = film.releases.firstOrNull { it.hash == known.hash } ?: cachedCandidates.first()
                _ui.value = Ui(film, release, 1, 1, Phase.Playing(link.url, link.fileName), Source.ALLDEBRID, prefetched = true)
                return@launch
            }
            val note = when {
                allDebrid == null || !signedIn -> null
                // Checked lately and AllDebrid had none of them: no need to ask again.
                known != null && known.hash == null -> NOT_ON_ALLDEBRID
                // The one it has goes first.
                else -> viaAllDebrid(allDebrid, key, film, cachedCandidates.sortedBy { it.hash != known?.hash }) ?: return@launch
            }
            if (candidates.isEmpty()) {
                _ui.value = Ui(film, cachedCandidates.first(), 1, 1, Phase.Failed("${note.orEmpty()} No one is sharing it right now either.".trim()))
            } else {
                startTorrent(film, note)
            }
        }
    }

    /**
     * Plays the first release AllDebrid already has, or says why it couldn't
     * (null once one is playing).
     */
    private suspend fun viaAllDebrid(ad: AllDebrid, key: String, film: Film, releases: List<Release>): String? {
        _ui.value = Ui(film, releases.first(), 1, releases.size, Phase.CheckingDebrid, Source.ALLDEBRID)
        for ((i, release) in releases.withIndex()) {
            _ui.update { it?.copy(release = release, attempt = i + 1) }
            try {
                val s = ad.resolve(key, release.magnet)
                ready?.put(film.id, release.hash, s)
                _ui.update { it?.copy(phase = Phase.Playing(s.url, s.fileName)) }
                return null
            } catch (e: NotCachedException) {
                continue
            } catch (e: AllDebridException) {
                return "AllDebrid: ${e.message} Streaming by torrent instead."
            } catch (e: IOException) {
                return "Couldn't reach AllDebrid, so streaming by torrent."
            }
        }
        ready?.put(film.id, null)
        return NOT_ON_ALLDEBRID
    }

    /** The launch screen's "Try another release", or VLC failing to open the file. */
    fun tryAnother(reason: String? = null) {
        val ui = _ui.value ?: return
        if (ui.source == Source.ALLDEBRID && ui.prefetched) {
            // A supercharged link that's gone stale (or this device's IP changed): get a fresh one.
            job?.cancel()
            begin(ui.film, linkFailed = true)
            return
        }
        if (ui.source == Source.ALLDEBRID) {
            // AllDebrid's link wouldn't play: fall back to the torrent.
            if (candidates.isEmpty()) {
                _ui.value = ui.copy(phase = Phase.Failed(reason ?: "It wouldn't play from AllDebrid."))
            } else {
                index = 0
                startTorrent(ui.film, listOfNotNull(reason, "Streaming by torrent instead.").joinToString(" "))
            }
            return
        }
        if (index + 1 >= candidates.size) {
            _ui.value = ui.copy(phase = Phase.Failed(reason ?: "None of its releases would play right now."))
            return
        }
        index++
        startTorrent(ui.film, reason)
    }

    /** "Try again" after everything failed: from the top, AllDebrid included. */
    fun retry() {
        val ui = _ui.value ?: return
        play(ui.film)
    }

    fun stop() {
        lookup?.cancel()
        lookup = null
        job?.cancel()
        job = null
        val s = stream
        stream = null
        _ui.value = null
        if (s != null) scope.launch(Dispatchers.IO) { s.close() }
    }

    private fun startTorrent(film: Film, note: String?) {
        job?.cancel()
        stream?.let { old -> scope.launch(Dispatchers.IO) { old.close() } }
        stream = null
        val release = candidates[index]
        _ui.value = Ui(film, release, index + 1, candidates.size, Phase.Connecting, Source.TORRENT, note = note)
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
        const val MAX_DEBRID_TRIES = 5
        const val NOT_ON_ALLDEBRID = "Not ready on AllDebrid yet, so streaming by torrent."
    }
}
