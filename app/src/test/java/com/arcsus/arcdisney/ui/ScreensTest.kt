package com.arcsus.arcdisney.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.SuccessResult
import com.arcsus.arcdisney.data.CatalogueStore
import com.arcsus.arcdisney.data.Film
import com.arcsus.arcdisney.debrid.AllDebridAccount
import com.arcsus.arcdisney.debrid.AllDebridPin
import com.arcsus.arcdisney.playback.PlaybackController
import com.arcsus.arcdisney.torrent.StreamStats
import com.arcsus.arcdisney.ui.theme.ArcDisneyTheme
import com.arcsus.arcdisney.wifi.Security
import com.arcsus.arcdisney.wifi.WifiNetwork
import com.arcsus.arcdisney.wifi.WifiState
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The app's screens rendered on the JVM, written to build/screens/. Posters
 * load from -DposterDir (TMDB images saved by file name) when it's given;
 * otherwise the tiles show their titles.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val films: List<Film> by lazy {
        CatalogueStore.decode(File("src/main/assets/disney.json").readText()).playable
    }

    @Before
    fun posters() {
        val dir = System.getProperty("posterDir")?.let(::File)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .interceptorDispatcher(Dispatchers.Unconfined)
                .components {
                    add(object : Interceptor {
                        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                            val name = (chain.request.data as? String)?.substringAfterLast('/')
                            val file = name?.let { n -> dir?.let { File(it, n) } }
                            val bitmap = file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                                ?: return ErrorResult(null, chain.request, IllegalStateException("no poster"))
                            return SuccessResult(BitmapDrawable(context.resources, bitmap), chain.request, DataSource.DISK)
                        }
                    })
                }
                .build(),
        )
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { ArcDisneyTheme { content() } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    @Test
    fun splash() = shoot("1-splash") { SplashScreen(animate = false) }

    @Test
    fun wall() = shoot("2-wall") {
        PosterWall(
            films = films,
            query = "",
            onQuery = {},
            update = DisneyViewModel.Update.Idle,
            message = null,
            online = true,
            onUpdate = {},
            onOpenWifi = {},
            onAllDebrid = {},
            onTmdbKey = {},
            onPlay = {},
        )
    }

    /** The header on a narrow phone: logo, search bar and update button still fit on one row. */
    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun wallOnASmallPhone() = shoot("18-wall-small-phone") {
        PosterWall(
            films = films,
            query = "",
            onQuery = {},
            update = DisneyViewModel.Update.Idle,
            message = null,
            online = true,
            onUpdate = {},
            onOpenWifi = {},
            onAllDebrid = {},
            onTmdbKey = {},
            onPlay = {},
        )
    }

    @Test
    fun wallSearchingAndUpdating() = shoot("3-wall-search-update") {
        PosterWall(
            films = films.filter { it.title.contains("Toy", ignoreCase = true) },
            query = "toy",
            onQuery = {},
            update = DisneyViewModel.Update.Searching(done = 12, total = 31),
            message = "2 new films to watch.",
            online = true,
            onUpdate = {},
            onOpenWifi = {},
            onAllDebrid = {},
            onTmdbKey = {},
            onPlay = {},
        )
    }

    private fun launchUi(phase: PlaybackController.Phase, note: String? = null): PlaybackController.Ui {
        val film = films.first { it.title == "Coco" }
        return PlaybackController.Ui(
            film = film,
            release = film.releases.first(),
            attempt = if (note != null) 2 else 1,
            candidates = 4,
            phase = phase,
            stats = StreamStats(peers = 23, seeds = 19, bytesPerSecond = 2_600_000, fileProgress = 0.01f),
            note = note,
        )
    }

    @Test
    fun launchBuffering() = shoot("4-launch-buffering") {
        LaunchScreen(launchUi(PlaybackController.Phase.Buffering(0.42f)), onClose = {}, onTryAnother = {}, onRetry = {})
    }

    @Test
    fun launchFindingAfterAFailedRelease() = shoot("5-launch-retrying") {
        LaunchScreen(
            launchUi(PlaybackController.Phase.Connecting, note = "No one is sharing this release right now. Trying another release."),
            onClose = {},
            onTryAnother = {},
            onRetry = {},
        )
    }

    @Test
    fun launchFailed() = shoot("6-launch-failed") {
        LaunchScreen(
            launchUi(PlaybackController.Phase.Failed("None of its releases would play right now.")),
            onClose = {},
            onTryAnother = {},
            onRetry = {},
        )
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-xxhdpi")
    fun playerControls() = shoot("7-player-controls") {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF3A2A5C), Color(0xFF0E3B52), Color(0xFF1B1B1B)))),
        ) {
            PlayerControls(
                title = "Coco",
                playing = true,
                time = 2_412_000,
                length = 6_300_000,
                scrub = null,
                detail = "47% downloaded \u00B7 3.0 MB/s \u00B7 31 peers",
                fill = false,
                onBack = {},
                onFill = {},
                onTracks = {},
                onPlayPause = {},
                onSkip = {},
                onScrub = {},
                onScrubEnd = {},
            )
        }
    }

    private val nearby = listOf(
        WifiNetwork("Home", 4, Security.PSK),
        WifiNetwork("BT-Hub7-6Q2K", 3, Security.PSK),
        WifiNetwork("Neighbours 5G", 2, Security.PSK),
        WifiNetwork("Cafe Guest", 2, Security.OPEN),
        WifiNetwork("Office", 1, Security.EAP),
    )

    @Composable
    private fun Wifi(state: WifiState, setup: Boolean = true) = WifiScreen(
        state = state,
        setup = setup,
        canConnectInApp = true,
        onTurnOn = {},
        onAllowLocation = {},
        onConnect = { _, _, _ -> },
        onOpenSystemWifi = {},
        onContinue = {},
        onSkip = {},
        onBack = {},
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    @Test
    fun wifiSetupList() = shoot("8-wifi-setup") {
        Wifi(WifiState(enabled = true, networks = nearby, locationPermission = true))
    }

    @Test
    fun wifiTypingAPassword() {
        compose.setContent { ArcDisneyTheme { Wifi(WifiState(enabled = true, networks = nearby, locationPermission = true)) } }
        compose.onNodeWithText("Home").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Password").performTextInput("correct horse")
        capture("9-wifi-password")
    }

    @Test
    fun wifiWrongPassword() {
        compose.setContent {
            ArcDisneyTheme {
                Wifi(WifiState(enabled = true, networks = nearby, locationPermission = true, failed = "Home" to "Wrong password."))
            }
        }
        compose.onNodeWithText("Home").performClick()
        capture("10-wifi-wrong-password")
    }

    @Test
    fun wifiConnected() = shoot("11-wifi-connected") {
        Wifi(WifiState(enabled = true, connectedSsid = "Home", online = true, networks = nearby, locationPermission = true))
    }

    @Test
    fun wifiLocationOff() = shoot("12-wifi-location-off") {
        Wifi(WifiState(enabled = true, locationPermission = true, locationOn = false))
    }

    @Test
    fun wallOffline() = shoot("13-wall-offline") {
        PosterWall(
            films = films,
            query = "",
            onQuery = {},
            update = DisneyViewModel.Update.Idle,
            message = null,
            online = false,
            onUpdate = {},
            onOpenWifi = {},
            onAllDebrid = {},
            onTmdbKey = {},
            onPlay = {},
        )
    }

    @Composable
    private fun Debrid(state: DisneyViewModel.Debrid, setup: Boolean = true) = DebridScreen(
        state = state,
        setup = setup,
        onSignIn = {},
        onCancel = {},
        onSignOut = {},
        onContinue = {},
        onBack = {},
    )

    @Test
    fun allDebridSetupStep() = shoot("14-alldebrid-setup") { Debrid(DisneyViewModel.Debrid.SignedOut) }

    @Test
    fun allDebridPin() = shoot("15-alldebrid-pin") {
        Debrid(
            DisneyViewModel.Debrid.WaitingForPin(
                AllDebridPin("K7QX", "chk", "https://alldebrid.com/pin/", "https://alldebrid.com/pin/?pin=K7QX", 600),
            ),
        )
    }

    @Test
    fun allDebridSignedIn() = shoot("16-alldebrid-signed-in") {
        Debrid(DisneyViewModel.Debrid.SignedIn(AllDebridAccount("richard", premium = true, premiumUntil = 1_830_000_000)), setup = false)
    }

    @Test
    fun launchCheckingAllDebrid() = shoot("17-launch-alldebrid") {
        val film = films.first { it.title == "Coco" }
        LaunchScreen(
            PlaybackController.Ui(
                film = film,
                release = film.releases.first(),
                attempt = 1,
                candidates = 5,
                phase = PlaybackController.Phase.CheckingDebrid,
                source = PlaybackController.Source.ALLDEBRID,
            ),
            onClose = {},
            onTryAnother = {},
            onRetry = {},
        )
    }
}
