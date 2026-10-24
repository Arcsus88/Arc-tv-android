package com.arcsus.arcdisney.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arcsus.arcdisney.ArcDisneyApp
import com.arcsus.arcdisney.BuildConfig
import com.arcsus.arcdisney.data.Catalogue
import com.arcsus.arcdisney.data.CatalogueUpdater
import com.arcsus.arcdisney.data.Film
import com.arcsus.arcdisney.data.Knaben
import com.arcsus.arcdisney.data.PirateBay
import com.arcsus.arcdisney.data.Tmdb
import com.arcsus.arcdisney.data.matches
import com.arcsus.arcdisney.data.searchWords
import com.arcsus.arcdisney.debrid.AllDebridAccount
import com.arcsus.arcdisney.debrid.AllDebridException
import com.arcsus.arcdisney.debrid.AllDebridPin
import com.arcsus.arcdisney.debrid.ReadyFilms
import com.arcsus.arcdisney.playback.PlaybackController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

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

    val playback = PlaybackController(
        engine = { graph.engine },
        scope = viewModelScope,
        allDebrid = graph.allDebrid,
        allDebridKey = { graph.settings.allDebridKey.first() },
        ready = graph.ready,
    )

    val wifi = graph.wifi
    val wifiState = wifi.state

    /** The app's one VLC (loading it is slow, so it's loaded once, at start). */
    val vlc get() = graph.vlc

    /** Null until known: whether first-run setup (getting online) is still to do. */
    private val _setupNeeded = MutableStateFlow<Boolean?>(null)
    val setupNeeded: StateFlow<Boolean?> = _setupNeeded.asStateFlow()

    /** The Wi-Fi screen opened from the wall (long-press menu or the offline pill). */
    private val _wifiOpen = MutableStateFlow(false)
    val wifiOpen: StateFlow<Boolean> = _wifiOpen.asStateFlow()

    /** This device's AllDebrid sign-in, as the AllDebrid screen shows it. */
    sealed interface Debrid {
        data object SignedOut : Debrid
        data object GettingPin : Debrid
        data class WaitingForPin(val pin: AllDebridPin) : Debrid

        /** [account] is null until AllDebrid has said who it is (or if it couldn't). */
        data class SignedIn(val account: AllDebridAccount?) : Debrid
        data class Failed(val message: String) : Debrid
    }

    private val _debrid = MutableStateFlow<Debrid>(Debrid.SignedOut)
    val debrid: StateFlow<Debrid> = _debrid.asStateFlow()
    private var signIn: Job? = null

    /** Null until known: whether first run still has to offer the AllDebrid sign-in. */
    private val _debridOffer = MutableStateFlow<Boolean?>(null)
    val debridOffer: StateFlow<Boolean?> = _debridOffer.asStateFlow()

    private val _debridOpen = MutableStateFlow(false)
    val debridOpen: StateFlow<Boolean> = _debridOpen.asStateFlow()

    /** The "Supercharging your streams" screen after signing in; null when it isn't showing. */
    sealed interface Supercharge {
        data class Running(val done: Int, val total: Int, val ready: Int) : Supercharge
        data class Done(val ready: Int, val total: Int) : Supercharge
        data class Failed(val message: String) : Supercharge
    }

    private val _supercharge = MutableStateFlow<Supercharge?>(null)
    val supercharge: StateFlow<Supercharge?> = _supercharge.asStateFlow()
    private var superchargeJob: Job? = null

    init {
        wifi.startMonitoring()
        viewModelScope.launch { catalogue.value = graph.store.load() }
        viewModelScope.launch { _setupNeeded.value = !graph.settings.setupDone() }
        viewModelScope.launch { _debridOffer.value = !graph.settings.debridOffered() }
        viewModelScope.launch {
            val key = graph.settings.allDebridKey.first()
            if (key.isNotBlank()) {
                _debrid.value = Debrid.SignedIn(null)
                loadAccount(key)
            }
        }
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

    // ---- AllDebrid ----

    fun openDebrid() {
        _debridOpen.value = true
    }

    fun closeDebrid() {
        cancelDebridSignIn()
        _debridOpen.value = false
    }

    /** First run's AllDebrid step is done, signed in or skipped. */
    fun finishDebridOffer() {
        cancelDebridSignIn()
        _debridOffer.value = false
        viewModelScope.launch { graph.settings.markDebridOffered() }
    }

    /** Shows a PIN, waits for it to be entered on another device, keeps the key. */
    fun startDebridSignIn() {
        signIn?.cancel()
        _debrid.value = Debrid.GettingPin
        signIn = viewModelScope.launch {
            try {
                val pin = graph.allDebrid.startPin()
                _debrid.value = Debrid.WaitingForPin(pin)
                val key = graph.allDebrid.awaitKey(pin)
                if (key == null) {
                    _debrid.value = Debrid.Failed("The code ran out before it was entered. Try again for a new one.")
                    return@launch
                }
                graph.settings.saveAllDebridKey(key)
                _debrid.value = Debrid.SignedIn(null)
                supercharge(key)
                loadAccount(key)
            } catch (e: IOException) {
                _debrid.value = Debrid.Failed(e.message ?: "Couldn't reach AllDebrid.")
            }
        }
    }

    fun cancelDebridSignIn() {
        val waiting = _debrid.value is Debrid.GettingPin || _debrid.value is Debrid.WaitingForPin || _debrid.value is Debrid.Failed
        signIn?.cancel()
        signIn = null
        if (waiting) _debrid.value = Debrid.SignedOut
    }

    fun signOutDebrid() {
        cancelDebridSignIn()
        superchargeJob?.cancel()
        _supercharge.value = null
        _debrid.value = Debrid.SignedOut
        viewModelScope.launch {
            graph.settings.saveAllDebridKey("")
            graph.ready.clear()
        }
    }

    /**
     * The app came on screen. Signed in to AllDebrid, its films are got ready
     * again (on the supercharge screen) once their links have gone stale;
     * otherwise VLC is warmed up with one that's still good.
     */
    fun onScreen() {
        viewModelScope.launch {
            val key = graph.settings.allDebridKey.first()
            if (key.isBlank() || superchargeJob?.isActive == true) return@launch
            if (System.currentTimeMillis() - graph.ready.superchargedAt() > ReadyFilms.LINK_FRESH_MS) {
                supercharge(key)
            } else {
                preroll()
            }
        }
    }

    private suspend fun superchargeFilms() = catalogue.filterNotNull().first().playable.take(SUPERCHARGE_FILMS)

    /** VLC's first stream over HTTPS, done now with a ready film, so the first one picked starts at once. */
    private suspend fun preroll() {
        graph.ready.anyStream(superchargeFilms().map { it.id })?.let { graph.prerollVlc(it.url) }
    }

    /**
     * Gets the wall's first films ready to play from AllDebrid (see
     * Supercharger), on the "Supercharging your streams" screen.
     */
    private fun supercharge(key: String) {
        superchargeJob?.cancel()
        superchargeJob = viewModelScope.launch {
            val films = superchargeFilms()
            if (films.isEmpty()) return@launch
            _supercharge.value = Supercharge.Running(0, films.size, 0)
            try {
                val result = graph.supercharger.run(key, films) { p ->
                    _supercharge.update { s -> if (s is Supercharge.Running) Supercharge.Running(p.done, p.total, p.ready) else s }
                }
                preroll()
                if (_supercharge.value == null) return@launch
                _supercharge.value = Supercharge.Done(result.ready, result.total)
                delay(DONE_SHOWN_MS)
                finishSupercharge()
            } catch (e: IOException) {
                if (_supercharge.value != null) {
                    _supercharge.value = Supercharge.Failed(
                        (e as? AllDebridException)?.message?.let { "AllDebrid: $it" } ?: "Couldn't reach AllDebrid. Films will still play.",
                    )
                }
            }
        }
    }

    /** Off the supercharge screen (it carries on quietly if it isn't done) and on to the wall. */
    fun finishSupercharge() {
        _supercharge.value = null
        if (_debridOffer.value == true) finishDebridOffer()
        if (_debridOpen.value) closeDebrid()
    }

    private suspend fun loadAccount(key: String) {
        val account = try {
            graph.allDebrid.account(key)
        } catch (e: IOException) {
            return // still signed in; the details just aren't shown
        }
        if (_debrid.value is Debrid.SignedIn) _debrid.value = Debrid.SignedIn(account)
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
            knaben = Knaben(graph.http),
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

    private companion object {
        /** The wall's first rows: the films most likely to be picked. */
        const val SUPERCHARGE_FILMS = 30
        const val DONE_SHOWN_MS = 1_800L
    }
}
