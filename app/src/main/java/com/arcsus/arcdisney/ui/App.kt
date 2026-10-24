package com.arcsus.arcdisney.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arcsus.arcdisney.playback.PlaybackController
import com.arcsus.arcdisney.playback.PlaybackController.Phase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private enum class Stage { SPLASH, SETUP, DEBRID, WALL }

/**
 * Splash, then on first run Wi-Fi setup (if the device isn't online) and the
 * AllDebrid offer, then the poster wall; the Wi-Fi and AllDebrid screens,
 * "Supercharging your streams", a film's launch screen and the player go on
 * top.
 */
@Composable
fun App(vm: DisneyViewModel) {
    val films by vm.films.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val askForKey by vm.askForKey.collectAsStateWithLifecycle()
    val playback by vm.playback.ui.collectAsStateWithLifecycle()
    val setupNeeded by vm.setupNeeded.collectAsStateWithLifecycle()
    val wifi by vm.wifiState.collectAsStateWithLifecycle()
    val wifiOpen by vm.wifiOpen.collectAsStateWithLifecycle()
    val debrid by vm.debrid.collectAsStateWithLifecycle()
    val debridOffer by vm.debridOffer.collectAsStateWithLifecycle()
    val debridOpen by vm.debridOpen.collectAsStateWithLifecycle()
    val supercharge by vm.supercharge.collectAsStateWithLifecycle()

    // The splash plays through once, and holds until the catalogue and settings are in.
    var splashDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!splashDone) delay(1_900)
        vm.setupNeeded.first { it != null }
        vm.debridOffer.first { it != null }
        vm.onSplashDone()
        splashDone = true
    }
    val stage = when {
        !splashDone || films == null -> Stage.SPLASH
        setupNeeded == true -> Stage.SETUP
        debridOffer == true -> Stage.DEBRID
        else -> Stage.WALL
    }

    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = stage, animationSpec = tween(700), label = "stage") { s ->
            when (s) {
                Stage.SPLASH -> SplashScreen()
                Stage.SETUP -> WifiRoute(
                    controller = vm.wifi,
                    state = wifi,
                    setup = true,
                    onContinue = vm::finishSetup,
                    onSkip = vm::finishSetup,
                    onBack = {},
                )
                Stage.DEBRID -> DebridScreen(
                    state = debrid,
                    setup = true,
                    onSignIn = vm::startDebridSignIn,
                    onCancel = vm::cancelDebridSignIn,
                    onSignOut = vm::signOutDebrid,
                    onContinue = vm::finishDebridOffer,
                    onBack = {},
                )
                Stage.WALL -> PosterWall(
                    films = films.orEmpty(),
                    query = query,
                    onQuery = vm::setQuery,
                    update = update,
                    message = message,
                    online = wifi.online,
                    onUpdate = vm::onUpdateClick,
                    onOpenWifi = vm::openWifi,
                    onAllDebrid = vm::openDebrid,
                    onTmdbKey = vm::onTmdbKey,
                    onPlay = vm::play,
                )
            }
        }

        AnimatedVisibility(visible = stage == Stage.WALL && wifiOpen, enter = fadeIn(), exit = fadeOut()) {
            WifiRoute(
                controller = vm.wifi,
                state = wifi,
                setup = false,
                onContinue = vm::closeWifi,
                onSkip = vm::closeWifi,
                onBack = vm::closeWifi,
            )
        }

        AnimatedVisibility(visible = stage == Stage.WALL && debridOpen, enter = fadeIn(), exit = fadeOut()) {
            DebridScreen(
                state = debrid,
                setup = false,
                onSignIn = vm::startDebridSignIn,
                onCancel = vm::cancelDebridSignIn,
                onSignOut = vm::signOutDebrid,
                onContinue = vm::closeDebrid,
                onBack = vm::closeDebrid,
            )
        }

        // After signing in to AllDebrid, over whichever AllDebrid screen it was.
        var lastSupercharge by remember { mutableStateOf<DisneyViewModel.Supercharge?>(null) }
        supercharge?.let { lastSupercharge = it }
        val superchargeShown = supercharge != null && stage != Stage.SPLASH && stage != Stage.SETUP
        AnimatedVisibility(visible = superchargeShown, enter = fadeIn(tween(400)), exit = fadeOut(tween(500))) {
            lastSupercharge?.let { SuperchargeScreen(it, onSkip = vm::finishSupercharge, onContinue = vm::finishSupercharge) }
        }

        val ui = playback
        val launching = ui != null && ui.phase !is Phase.Playing
        // The last launch state stays on screen while it fades out.
        var shown by remember { mutableStateOf<PlaybackController.Ui?>(null) }
        if (launching) shown = ui
        AnimatedVisibility(visible = launching, enter = fadeIn(tween(350)), exit = fadeOut(tween(350))) {
            shown?.let { current ->
                LaunchScreen(
                    ui = current,
                    onClose = vm.playback::stop,
                    onTryAnother = { vm.playback.tryAnother() },
                    onRetry = vm.playback::retry,
                )
            }
        }
        if (launching) BackHandler(onBack = vm.playback::stop)

        val phase = ui?.phase
        if (ui != null && phase is Phase.Playing) {
            PlayerScreen(
                libVlc = vm.vlc,
                url = phase.url,
                title = ui.film.title,
                stats = ui.stats,
                fromAllDebrid = ui.source == PlaybackController.Source.ALLDEBRID,
                onClose = vm.playback::stop,
                onError = { vm.playback.tryAnother(it) },
            )
        }
    }

    if (askForKey) {
        TmdbKeyDialog(onSave = vm::saveKey, onSkip = vm::skipKey, onDismiss = vm::dismissKeyDialog)
    }
}
