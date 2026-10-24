package com.arcsus.arcdisney.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcsus.arcdisney.ui.DisneyViewModel.Supercharge
import com.arcsus.arcdisney.ui.theme.Night

/**
 * "Supercharging your streams", straight after signing in to AllDebrid: a
 * progress bar while the wall's first films are checked on AllDebrid (see
 * Supercharger), so that wait happens here rather than on the launch screen.
 * Skip carries on with the check in the background.
 */
@Composable
fun SuperchargeScreen(state: Supercharge, onSkip: () -> Unit, onContinue: () -> Unit) {
    BackHandler(onBack = if (state is Supercharge.Running) onSkip else onContinue)
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    val target = when (state) {
        is Supercharge.Running -> if (state.total == 0) 0f else state.done.toFloat() / state.total
        is Supercharge.Done -> 1f
        is Supercharge.Failed -> 0f
    }
    val progress by animateFloatAsState(target, tween(450), label = "progress")

    Box(
        Modifier
            .fillMaxSize()
            .background(Night.Deep)
            .background(Brush.verticalGradient(listOf(Night.Sky.copy(alpha = 0.8f), Color.Transparent), endY = 1400f)),
    ) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(112.dp)
                    .scale(if (state is Supercharge.Running) glow else 1f)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Night.Glow.copy(alpha = 0.55f), Night.Glow.copy(alpha = 0.08f)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state is Supercharge.Done) Icons.Default.Check else AppIcons.Bolt,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
            Text(
                when (state) {
                    is Supercharge.Running -> "Supercharging your streams"
                    is Supercharge.Done -> "Your streams are supercharged"
                    is Supercharge.Failed -> "Couldn't supercharge just now"
                },
                color = Night.Text,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when (state) {
                    is Supercharge.Running -> "Getting your films ready on AllDebrid, so they start in seconds."
                    is Supercharge.Done -> when (state.ready) {
                        0 -> "None of the top films are on AllDebrid yet. They'll stream by torrent."
                        1 -> "1 film is ready to start straight away."
                        else -> "${state.ready} films are ready to start straight away."
                    }
                    is Supercharge.Failed -> state.message
                },
                color = Night.Muted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            if (state !is Supercharge.Failed) {
                Spacer(Modifier.height(36.dp))
                ProgressBar(progress)
                Spacer(Modifier.height(12.dp))
                Text(
                    when (state) {
                        is Supercharge.Running -> "${state.done} of ${state.total} films checked"
                        is Supercharge.Done -> "${state.total} of ${state.total} films checked"
                        is Supercharge.Failed -> ""
                    },
                    color = Night.Muted,
                    fontSize = 13.sp,
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (state) {
                is Supercharge.Running -> TextButton(onClick = onSkip) { Text("Skip", color = Night.Muted, fontSize = 15.sp) }
                is Supercharge.Done -> Unit // moves on by itself
                is Supercharge.Failed -> Box(Modifier.padding(horizontal = 32.dp)) { SetupButton("Continue", onClick = onContinue) }
            }
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Night.Line),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                // A sliver from the start, so it never looks stuck at nothing.
                .fillMaxWidth(fraction.coerceIn(0.03f, 1f))
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(listOf(Night.Glow, Color(0xFF9BB8FF), Color.White))),
        )
    }
}
