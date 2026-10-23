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

/** Splash, then the poster wall; a film's launch screen and player go on top. */
@Composable
fun App(vm: DisneyViewModel) {
    val films by vm.films.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val askForKey by vm.askForKey.collectAsStateWithLifecycle()
    val playback by vm.playback.ui.collectAsStateWithLifecycle()

    // The splash plays through once, and holds until the catalogue is in.
    var splashDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!splashDone) delay(1_900)
        splashDone = true
    }
    val showWall = splashDone && films != null

    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = showWall, animationSpec = tween(700), label = "splash") { wall ->
            if (wall) {
                PosterWall(
                    films = films.orEmpty(),
                    query = query,
                    onQuery = vm::setQuery,
                    update = update,
                    message = message,
                    onUpdate = vm::onUpdateClick,
                    onUpdateLongPress = vm::onUpdateLongClick,
                    onPlay = vm::play,
                )
            } else {
                SplashScreen()
            }
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
                url = phase.url,
                title = ui.film.title,
                stats = ui.stats,
                onClose = vm.playback::stop,
                onError = { vm.playback.tryAnother(it) },
            )
        }
    }

    if (askForKey) {
        TmdbKeyDialog(onSave = vm::saveKey, onSkip = vm::skipKey, onDismiss = vm::dismissKeyDialog)
    }
}
