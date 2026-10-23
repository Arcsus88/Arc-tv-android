package com.arcsus.arcdisney.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.arcsus.arcdisney.playback.PlaybackController
import com.arcsus.arcdisney.playback.PlaybackController.Phase
import com.arcsus.arcdisney.ui.theme.Night
import java.util.Locale

/**
 * The film's art while its torrent finds peers and fetches enough to start;
 * VLC takes over the moment it can. If every release fails, the reason and
 * a way to try again.
 */
@Composable
fun LaunchScreen(
    ui: PlaybackController.Ui,
    onClose: () -> Unit,
    onTryAnother: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val film = ui.film
    // Getting a film ready can take longer than the screen timeout.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            // Taps don't fall through to the posters underneath.
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        if (film.poster.isNotBlank()) {
            AsyncImage(
                model = film.posterUrl(500),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.55f)
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(36.dp) else Modifier),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to Night.Deep.copy(alpha = 0.55f), 0.5f to Night.Deep.copy(alpha = 0.8f), 1f to Night.Deep)),
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(36.dp))
            Box(
                Modifier
                    .width(170.dp)
                    .aspectRatio(2f / 3f)
                    .shadow(24.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(Night.Card)
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(film.title, color = Night.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                if (film.poster.isNotBlank()) {
                    AsyncImage(
                        model = film.posterUrl(500),
                        contentDescription = film.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                film.title,
                color = Night.Text,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (film.year.isNotBlank()) {
                Text(film.year, color = Night.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(28.dp))

            Column(Modifier.widthIn(max = 340.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (val phase = ui.phase) {
                    is Phase.Failed -> Failed(phase.message, onRetry, onClose)
                    else -> Starting(ui, phase, onTryAnother)
                }
            }
        }

        Box(
            Modifier
                .statusBarsPadding()
                .padding(12.dp)
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0x66000000))
                .border(1.dp, Night.Line, CircleShape)
                .clickable(onClickLabel = "Close", onClick = onClose)
                .align(Alignment.TopEnd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Night.Text, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Starting(ui: PlaybackController.Ui, phase: Phase, onTryAnother: () -> Unit) {
    val buffering = phase as? Phase.Buffering
    val fraction by animateFloatAsState(buffering?.fraction ?: 0f, label = "buffer")
    Text(
        when {
            buffering != null -> "Getting it ready · ${(fraction * 100).toInt()}%"
            phase is Phase.Playing -> "Starting…"
            else -> "Finding people sharing it…"
        },
        color = Night.Text,
        fontSize = 16.sp,
    )
    Spacer(Modifier.height(12.dp))
    if (buffering != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Night.Text,
            trackColor = Night.Line,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Night.Text,
            trackColor = Night.Line,
        )
    }
    Spacer(Modifier.height(10.dp))
    val s = ui.stats
    Text(
        buildString {
            append(if (s.peers == 1) "1 peer" else "${s.peers} peers")
            if (s.bytesPerSecond > 0) append(" · ").append(speed(s.bytesPerSecond))
        },
        color = Night.Muted,
        fontSize = 13.sp,
    )
    Spacer(Modifier.height(22.dp))
    Text(
        listOf(ui.release.name, ui.release.sizeLabel).filter { it.isNotBlank() }.joinToString(" · "),
        color = Night.Muted.copy(alpha = 0.8f),
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    ui.note?.let {
        Text(it, color = Night.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
    if (ui.canTryAnother) {
        TextButton(onClick = onTryAnother, modifier = Modifier.padding(top = 10.dp)) {
            Text("Try another release (${ui.attempt} of ${ui.candidates})", color = Night.Text)
        }
    }
}

@Composable
private fun Failed(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Text(message, color = Night.Text, fontSize = 16.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onClose) { Text("Close", color = Night.Muted) }
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Night.Text, contentColor = Night.Deep),
        ) { Text("Try again", fontWeight = FontWeight.SemiBold) }
    }
}

internal fun speed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024))
    bytesPerSecond > 0 -> "${bytesPerSecond / 1024} KB/s"
    else -> "0 KB/s"
}
