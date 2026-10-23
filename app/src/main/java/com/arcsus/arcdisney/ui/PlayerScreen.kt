package com.arcsus.arcdisney.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arcsus.arcdisney.torrent.StreamStats
import com.arcsus.arcdisney.ui.theme.Night
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

/**
 * VLC inside the app: the film plays from the torrent's local HTTP address,
 * full screen and landscape, with VLC's own decoders (every codec and audio
 * format a release might use).
 */
@Composable
fun PlayerScreen(
    url: String,
    title: String,
    stats: StreamStats,
    /** Streaming from AllDebrid rather than the torrent: no download figures to show. */
    fromAllDebrid: Boolean,
    onClose: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val onErrorNow by rememberUpdatedState(onError)
    val onCloseNow by rememberUpdatedState(onClose)

    val libVlc = remember {
        LibVLC(context, arrayListOf("--network-caching=4000", "--http-reconnect", "--audio-time-stretch"))
    }
    val player = remember { MediaPlayer(libVlc) }

    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(true) }
    var tracks by remember { mutableStateOf(false) }
    var fill by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var poke by remember { mutableLongStateOf(0L) }

    DisposableEffect(url) {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> playing = true
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.Buffering -> buffering = e.buffering
                MediaPlayer.Event.TimeChanged -> time = e.timeChanged
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.EndReached -> onCloseNow()
                MediaPlayer.Event.EncounteredError -> onErrorNow("VLC couldn't play this release.")
            }
        }
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=4000")
        }
        player.media = media
        media.release()
        player.play()
        onDispose {
            player.setEventListener(null)
            player.stop()
            player.detachViews()
            player.release()
            libVlc.release()
        }
    }

    // Landscape, edge to edge, screen on; put everything back on the way out.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            insets?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Leaving the app pauses the film.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Controls fade after a few seconds of play.
    LaunchedEffect(controls, playing, poke, scrub) {
        if (controls && playing && scrub == null) {
            delay(3_500)
            controls = false
        }
    }

    BackHandler(onBack = onClose)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) { controls = !controls },
    ) {
        AndroidView(
            factory = { ctx -> VLCVideoLayout(ctx).also { player.attachViews(it, null, true, false) } },
            modifier = Modifier.fillMaxSize(),
        )
        LaunchedEffect(fill) {
            player.videoScale = if (fill) MediaPlayer.ScaleType.SURFACE_FILL else MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }

        // VLC reports its cache filling 0-100% at the start and whenever the torrent falls behind.
        if (buffering < 100f) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(48.dp))
        }

        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            PlayerControls(
                title = title,
                playing = playing,
                time = time,
                length = length,
                scrub = scrub,
                detail = if (fromAllDebrid) {
                    "Streaming from AllDebrid"
                } else {
                    "${(stats.fileProgress * 100).toInt()}% downloaded \u00B7 ${speed(stats.bytesPerSecond)} \u00B7 " +
                        if (stats.peers == 1) "1 peer" else "${stats.peers} peers"
                },
                fill = fill,
                onBack = onClose,
                onFill = {
                    fill = !fill
                    poke++
                },
                onTracks = { tracks = true },
                onPlayPause = {
                    if (player.isPlaying) player.pause() else player.play()
                    poke++
                },
                onSkip = { delta ->
                    val to = (player.time + delta).coerceAtLeast(0)
                    player.setTime(if (length > 0) to.coerceAtMost(length - 1_000) else to)
                    poke++
                },
                onScrub = { scrub = it },
                onScrubEnd = {
                    scrub?.let { if (length > 0) player.setTime((it * length).toLong()) }
                    scrub = null
                    poke++
                },
            )
        }
    }

    if (tracks) TracksDialog(player) { tracks = false }
}

/** The overlay over the film: back, title, fit/fill, tracks, play/pause, skips, the seek bar. */
@OptIn(ExperimentalMaterial3Api::class) // Slider's thumb and track slots
@Composable
fun PlayerControls(
    title: String,
    playing: Boolean,
    time: Long,
    length: Long,
    scrub: Float?,
    /** Under the seek bar, right: where the film is coming from. */
    detail: String,
    fill: Boolean,
    onBack: () -> Unit,
    onFill: () -> Unit,
    onTracks: () -> Unit,
    onPlayPause: () -> Unit,
    onSkip: (Long) -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.7f),
                        0.25f to Color.Transparent,
                        0.7f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.8f),
                    ),
                ),
        )
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RoundButton(AppIcons.AspectRatio, if (fill) "Fit to screen" else "Fill screen", onClick = onFill)
                RoundButton(AppIcons.Subtitles, "Audio and subtitles", onClick = onTracks)
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipButton(back = true) { onSkip(-10_000) }
                Spacer(Modifier.width(36.dp))
                Box(
                    Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClickLabel = if (playing) "Pause" else "Play", onClick = onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) AppIcons.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp),
                    )
                }
                Spacer(Modifier.width(36.dp))
                SkipButton(back = false) { onSkip(10_000) }
            }
            Spacer(Modifier.weight(1f))
            val shown = scrub?.let { (it * length).toLong() } ?: time
            val position = if (length > 0) (scrub ?: (time.toFloat() / length)).coerceIn(0f, 1f) else 0f
            Slider(
                value = position,
                onValueChange = onScrub,
                onValueChangeFinished = onScrubEnd,
                enabled = length > 0,
                // Slimmer than Material's default bar, which is heavy over a film.
                thumb = {
                    Box(
                        Modifier
                            .size(if (scrub != null) 18.dp else 14.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                },
                track = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(Modifier.fillMaxWidth(position).fillMaxHeight().background(Color.White))
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text("${clock(shown)} / ${clock(length)}", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    detail,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun SkipButton(back: Boolean, onClick: () -> Unit) {
    val label = if (back) "Back 10 seconds" else "Forward 10 seconds"
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.Skip,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(40.dp).graphicsLayer { scaleX = if (back) -1f else 1f },
        )
        Text("10", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Audio and subtitle tracks, as VLC lists them. */
@Composable
private fun TracksDialog(player: MediaPlayer, onDismiss: () -> Unit) {
    val audio = remember { player.audioTracks.orEmpty().toList() }
    val subs = remember { player.spuTracks.orEmpty().toList() }
    var audioId by remember { mutableStateOf(player.audioTrack) }
    var subId by remember { mutableStateOf(player.spuTrack) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Audio and subtitles") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Audio", color = Night.Muted, fontSize = 13.sp)
                if (audio.isEmpty()) Text("None", modifier = Modifier.padding(vertical = 8.dp))
                audio.forEach { t ->
                    TrackRow(t.name, t.id == audioId) {
                        player.setAudioTrack(t.id)
                        audioId = t.id
                    }
                }
                Spacer(Modifier.padding(top = 12.dp))
                Text("Subtitles", color = Night.Muted, fontSize = 13.sp)
                if (subs.isEmpty()) Text("None in this release", modifier = Modifier.padding(vertical = 8.dp))
                subs.forEach { t ->
                    TrackRow(t.name, t.id == subId) {
                        player.setSpuTrack(t.id)
                        subId = t.id
                    }
                }
            }
        },
    )
}

@Composable
private fun TrackRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(name, modifier = Modifier.padding(start = 4.dp))
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
