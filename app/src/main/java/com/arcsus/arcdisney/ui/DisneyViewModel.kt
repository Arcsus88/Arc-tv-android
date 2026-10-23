package com.arcsus.arcdisney.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arcsus.arcdisney.ArcDisneyApp
import com.arcsus.arcdisney.BuildConfig
import com.arcsus.arcdisney.data.Catalogue
import com.arcsus.arcdisney.data.CatalogueUpdater
import com.arcsus.arcdisney.data.Film
import com.arcsus.arcdisney.data.PirateBay
import com.arcsus.arcdisney.data.Tmdb
import com.arcsus.arcdisney.data.matches
import com.arcsus.arcdisney.data.searchWords
import com.arcsus.arcdisney.playback.PlaybackController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DisneyViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as ArcDisneyApp).graph

    /** The update button's progress, shown under the search bar while it runs. */
    sealed interface Update {
        data object Idle : Update
        data object FindingFilms : Update
        data class Searching(val done: Int, val total: Int) : Update
    }

    private val catalogue = MutableStateFlow<Catalogue?>(null)
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Null until the catalogue has loaded (the splash waits for it). */
    val films: StateFlow<List<Film>?> = combine(catalogue, _query) { c, q ->
        val words = searchWords(q)
        c?.playable?.filter { it.matches(words) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _update = MutableStateFlow<Update>(Update.Idle)
    val update: StateFlow<Update> = _update.asStateFlow()

    /** A short line after an update, shown for a few seconds. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var messageJob: Job? = null

    private val _askForKey = MutableStateFlow(false)
    val askForKey: StateFlow<Boolean> = _askForKey.asStateFlow()

    val playback = PlaybackController({ graph.engine }, viewModelScope)

    val wifi = graph.wifi
    val wifiState = wifi.state

    /** Null until known: whether first-run setup (getting online) is still to do. */
    private val _setupNeeded = MutableStateFlow<Boolean?>(null)
    val setupNeeded: StateFlow<Boolean?> = _setupNeeded.asStateFlow()

    /** The Wi-Fi screen opened from the wall (long-press menu or the offline pill). */
    private val _wifiOpen = MutableStateFlow(false)
    val wifiOpen: StateFlow<Boolean> = _wifiOpen.asStateFlow()

    init {
        wifi.startMonitoring()
        viewModelScope.launch { catalogue.value = graph.store.load() }
        viewModelScope.launch { _setupNeeded.value = !graph.settings.setupDone() }
        // An update must never cut a film off: it waits while one is on screen.
        viewModelScope.launch { playback.ui.collect { graph.updates.busy.value = it != null } }
        viewModelScope.launch {
            val before = graph.settings.swapVersion(BuildConfig.VERSION_NAME)
            if (before != null && before != BuildConfig.VERSION_NAME) show("Updated to ${BuildConfig.VERSION_NAME}.")
        }
    }

    /** A device that's already online when the splash ends has nothing to set up. */
    fun onSplashDone() {
        if (_setupNeeded.value == true && wifiState.value.online) finishSetup()
    }

    fun finishSetup() {
        _setupNeeded.value = false
        viewModelScope.launch { graph.settings.finishSetup() }
    }

    fun openWifi() {
        _wifiOpen.value = true
    }

    fun closeWifi() {
        _wifiOpen.value = false
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun play(film: Film) = playback.play(film)

    fun onUpdateClick() {
        if (_update.value != Update.Idle) return
        viewModelScope.launch {
            val key = BuildConfig.TMDB_API_KEY.ifBlank { graph.settings.tmdbKey() }
            if (key.isBlank() && !graph.settings.skippedKey()) _askForKey.value = true else runUpdate(key)
        }
    }

    /** "TMDB key" in the update button's long-press menu: change (or add) the key. */
    fun onTmdbKey() {
        if (_update.value == Update.Idle) _askForKey.value = true
    }

    fun saveKey(key: String) {
        _askForKey.value = false
        viewModelScope.launch {
            graph.settings.saveTmdbKey(key)
            runUpdate(key.trim())
        }
    }

    fun skipKey() {
        _askForKey.value = false
        viewModelScope.launch {
            graph.settings.skipTmdbKey()
            runUpdate("")
        }
    }

    fun dismissKeyDialog() {
        _askForKey.value = false
    }

    private suspend fun runUpdate(key: String) {
        val current = catalogue.value ?: return
        val updater = CatalogueUpdater(
            bay = PirateBay(graph.http),
            tmdb = key.takeIf { it.isNotBlank() }?.let { Tmdb(graph.http, it) },
        )
        _update.value = if (key.isNotBlank()) Update.FindingFilms else Update.Searching(0, 0)
        try {
            val result = updater.update(
                current,
                onProgress = { p ->
                    _update.value = when (p.phase) {
                        CatalogueUpdater.Phase.FINDING_FILMS -> Update.FindingFilms
                        CatalogueUpdater.Phase.SEARCHING -> Update.Searching(p.done, p.total)
                    }
                },
                onCheckpoint = { graph.store.save(it) },
            )
            graph.store.save(result.catalogue)
            catalogue.value = result.catalogue
            val added = result.newlyPlayable
            show(
                when {
                    added > 0 && result.error == null -> if (added == 1) "1 new film to watch." else "$added new films to watch."
                    added > 0 -> "$added new to watch. ${result.error}"
                    result.error != null -> result.error
                    key.isBlank() -> "Releases re-checked. Long-press to add a TMDB key and find new films."
                    else -> "You're up to date."
                },
            )
        } finally {
            _update.value = Update.Idle
        }
    }

    private fun show(text: String) {
        messageJob?.cancel()
        _message.value = text
        messageJob = viewModelScope.launch {
            delay(5_000)
            _message.value = null
        }
    }

    override fun onCleared() {
        playback.stop()
        wifi.stopMonitoring()
    }
}
